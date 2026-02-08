package id.walt.verifyapi.widget

import id.walt.verifyapi.db.VerifyOrganizations
import id.walt.verifyapi.db.VerifyWidgetTokens
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import java.util.*
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

private val logger = KotlinLogging.logger {}

/**
 * Request to create a new client token for widget SDK authentication.
 */
@Serializable
data class CreateClientTokenRequest(
    /** List of template names/IDs the token is allowed to use. Empty list = all templates. */
    val allowedTemplates: List<String> = emptyList(),
    /** List of allowed origins for CORS validation. Empty list = any origin. */
    val allowedOrigins: List<String> = emptyList(),
    /** Token lifetime in seconds. Default: 900 (15 minutes), Max: 86400 (24 hours). */
    val expiresInSeconds: Int = 900,
    /** Maximum number of uses. Null = unlimited. */
    val maxUses: Int? = null
)

/**
 * Response containing the created client token.
 */
@Serializable
data class CreateClientTokenResponse(
    /** The client token to use for widget SDK authentication. Format: ct_{base64}.{signature} */
    val token: String,
    /** Unix timestamp when the token expires. */
    val expiresAt: Long,
    /** Token prefix for identification (ct_xxxxxxxx). */
    val tokenPrefix: String
)

/**
 * Represents a validated client token with its associated permissions.
 */
data class ValidatedClientToken(
    /** UUID of the token record. */
    val tokenId: UUID,
    /** Organization ID that owns this token. */
    val organizationId: UUID,
    /** Organization name. */
    val organizationName: String,
    /** Templates this token is allowed to use. Empty = all. */
    val allowedTemplates: List<String>,
    /** Origins this token is allowed from. Empty = any. */
    val allowedOrigins: List<String>,
    /** Token expiration timestamp. */
    val expiresAt: Instant,
    /** Maximum uses allowed. Null = unlimited. */
    val maxUses: Int?,
    /** Current use count. */
    val useCount: Int
)

/**
 * Result of token validation.
 */
sealed class TokenValidationResult {
    /** Token is valid and can be used. */
    data class Valid(val token: ValidatedClientToken) : TokenValidationResult()

    /** Token has invalid format. */
    data object InvalidFormat : TokenValidationResult()

    /** Token signature verification failed. */
    data object InvalidSignature : TokenValidationResult()

    /** Token not found in database. */
    data object NotFound : TokenValidationResult()

    /** Token has expired. */
    data object Expired : TokenValidationResult()

    /** Token has exceeded maximum uses. */
    data object UsageLimitExceeded : TokenValidationResult()

    /** Origin is not allowed for this token. */
    data class OriginNotAllowed(val origin: String) : TokenValidationResult()

    /** Template is not allowed for this token. */
    data class TemplateNotAllowed(val template: String) : TokenValidationResult()
}

/**
 * Service for managing client tokens used by the widget SDK.
 *
 * Client tokens are short-lived tokens that merchants generate server-side
 * (using their API key) and pass to their frontend. The frontend widget
 * uses these tokens to authenticate verification requests without exposing
 * the API key.
 *
 * Token format: ct_{base64(payload)}.{hmac_signature}
 *
 * Security model:
 * 1. Merchant backend calls POST /v1/widget/tokens with API key
 * 2. API returns a client token (ct_...) valid for ~15 minutes
 * 3. Merchant passes token to frontend widget
 * 4. Widget uses token to authenticate verification requests
 */
object ClientTokenService {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /** Default token lifetime: 15 minutes. */
    val DEFAULT_TOKEN_LIFETIME: Duration = 15.minutes

    /** Maximum token lifetime: 24 hours. */
    val MAX_TOKEN_LIFETIME: Duration = Duration.parse("24h")

    /** Minimum token lifetime: 1 minute. */
    val MIN_TOKEN_LIFETIME: Duration = 1.minutes

    /** HMAC key for token signing. In production, this should be from a secure key store. */
    private val hmacKey: ByteArray by lazy {
        // Try to get from environment, otherwise generate a random key
        // In production, this should be a persistent key from a secure key store
        val envKey = System.getenv("VERIFY_CLIENT_TOKEN_SECRET")
        if (envKey != null) {
            envKey.toByteArray()
        } else {
            logger.warn { "VERIFY_CLIENT_TOKEN_SECRET not set, using random key. Tokens will be invalidated on restart." }
            ByteArray(32).also { SecureRandom().nextBytes(it) }
        }
    }

    private val secureRandom = SecureRandom()

    /**
     * Payload encoded in the client token.
     */
    @Serializable
    private data class TokenPayload(
        /** Token ID (UUID). */
        val tid: String,
        /** Organization ID. */
        val oid: String,
        /** Expiration timestamp (epoch seconds). */
        val exp: Long,
        /** Random nonce for uniqueness. */
        val nonce: String
    )

    /**
     * Generate a new client token for the given organization.
     *
     * @param organizationId The organization creating the token.
     * @param request The token creation request with permissions.
     * @return The created token response.
     */
    fun generateToken(
        organizationId: UUID,
        request: CreateClientTokenRequest
    ): CreateClientTokenResponse {
        // Validate and constrain expiration
        val expiresInSeconds = request.expiresInSeconds.coerceIn(
            MIN_TOKEN_LIFETIME.inWholeSeconds.toInt(),
            MAX_TOKEN_LIFETIME.inWholeSeconds.toInt()
        )

        val now = Instant.now()
        val expiresAt = now.plusSeconds(expiresInSeconds.toLong())
        val nonce = generateNonce()
        val tokenId = UUID.randomUUID()
        val tokenPrefix = "ct_${nonce.take(8)}"

        // Create the payload
        val payload = TokenPayload(
            tid = tokenId.toString(),
            oid = organizationId.toString(),
            exp = expiresAt.epochSecond,
            nonce = nonce
        )

        // Encode and sign
        val payloadJson = json.encodeToString(payload)
        val payloadBase64 = Base64.getUrlEncoder().withoutPadding().encodeToString(payloadJson.toByteArray())
        val signature = signPayload(payloadBase64)
        val fullToken = "ct_$payloadBase64.$signature"

        // Store in database
        val tokenHash = hashToken(fullToken)

        transaction {
            VerifyWidgetTokens.insert {
                it[id] = tokenId
                it[this.organizationId] = organizationId
                it[this.tokenHash] = tokenHash
                it[this.tokenPrefix] = tokenPrefix
                it[allowedTemplates] = if (request.allowedTemplates.isNotEmpty()) {
                    json.encodeToString(request.allowedTemplates)
                } else null
                it[allowedOrigins] = if (request.allowedOrigins.isNotEmpty()) {
                    json.encodeToString(request.allowedOrigins)
                } else null
                it[this.expiresAt] = expiresAt
                it[maxUses] = request.maxUses
                it[useCount] = 0
                it[createdAt] = now
            }
        }

        logger.debug { "Generated client token $tokenPrefix for org $organizationId, expires at $expiresAt" }

        return CreateClientTokenResponse(
            token = fullToken,
            expiresAt = expiresAt.epochSecond,
            tokenPrefix = tokenPrefix
        )
    }

    /**
     * Validate a client token.
     *
     * @param token The full client token string.
     * @param origin Optional origin header for CORS validation.
     * @param templateName Optional template name to validate access.
     * @return The validation result.
     */
    fun validateToken(
        token: String,
        origin: String? = null,
        templateName: String? = null
    ): TokenValidationResult {
        // Check format: ct_{base64}.{signature}
        if (!token.startsWith("ct_")) {
            logger.debug { "Token validation failed: invalid prefix" }
            return TokenValidationResult.InvalidFormat
        }

        val tokenBody = token.removePrefix("ct_")
        val parts = tokenBody.split(".")
        if (parts.size != 2) {
            logger.debug { "Token validation failed: invalid format (expected 2 parts, got ${parts.size})" }
            return TokenValidationResult.InvalidFormat
        }

        val payloadBase64 = parts[0]
        val providedSignature = parts[1]

        // Verify signature
        val expectedSignature = signPayload(payloadBase64)
        if (!constantTimeEquals(providedSignature, expectedSignature)) {
            logger.debug { "Token validation failed: invalid signature" }
            return TokenValidationResult.InvalidSignature
        }

        // Decode and parse payload
        val payloadJson = try {
            String(Base64.getUrlDecoder().decode(payloadBase64))
        } catch (e: Exception) {
            logger.debug { "Token validation failed: base64 decode error" }
            return TokenValidationResult.InvalidFormat
        }

        val payload = try {
            json.decodeFromString<TokenPayload>(payloadJson)
        } catch (e: Exception) {
            logger.debug { "Token validation failed: JSON parse error" }
            return TokenValidationResult.InvalidFormat
        }

        // Check expiration from payload (quick check before DB lookup)
        if (Instant.now().epochSecond > payload.exp) {
            logger.debug { "Token validation failed: expired (from payload)" }
            return TokenValidationResult.Expired
        }

        // Look up in database and validate
        val tokenHash = hashToken(token)

        return transaction {
            val row = (VerifyWidgetTokens innerJoin VerifyOrganizations)
                .selectAll()
                .where { VerifyWidgetTokens.tokenHash eq tokenHash }
                .singleOrNull()

            if (row == null) {
                logger.debug { "Token validation failed: not found in database" }
                return@transaction TokenValidationResult.NotFound
            }

            val expiresAt = row[VerifyWidgetTokens.expiresAt]
            if (Instant.now().isAfter(expiresAt)) {
                logger.debug { "Token validation failed: expired (from database)" }
                return@transaction TokenValidationResult.Expired
            }

            val maxUses = row[VerifyWidgetTokens.maxUses]
            val currentUseCount = row[VerifyWidgetTokens.useCount]
            if (maxUses != null && currentUseCount >= maxUses) {
                logger.debug { "Token validation failed: usage limit exceeded ($currentUseCount >= $maxUses)" }
                return@transaction TokenValidationResult.UsageLimitExceeded
            }

            // Parse allowed templates and origins
            val allowedTemplatesJson = row[VerifyWidgetTokens.allowedTemplates]
            val allowedTemplates: List<String> = if (allowedTemplatesJson != null) {
                json.decodeFromString(allowedTemplatesJson)
            } else emptyList()

            val allowedOriginsJson = row[VerifyWidgetTokens.allowedOrigins]
            val allowedOrigins: List<String> = if (allowedOriginsJson != null) {
                json.decodeFromString(allowedOriginsJson)
            } else emptyList()

            // Validate origin if specified
            if (origin != null && allowedOrigins.isNotEmpty()) {
                if (!isOriginAllowed(origin, allowedOrigins)) {
                    logger.debug { "Token validation failed: origin not allowed ($origin)" }
                    return@transaction TokenValidationResult.OriginNotAllowed(origin)
                }
            }

            // Validate template if specified
            if (templateName != null && allowedTemplates.isNotEmpty()) {
                if (templateName !in allowedTemplates) {
                    logger.debug { "Token validation failed: template not allowed ($templateName)" }
                    return@transaction TokenValidationResult.TemplateNotAllowed(templateName)
                }
            }

            val validatedToken = ValidatedClientToken(
                tokenId = row[VerifyWidgetTokens.id].value,
                organizationId = row[VerifyOrganizations.id].value,
                organizationName = row[VerifyOrganizations.name],
                allowedTemplates = allowedTemplates,
                allowedOrigins = allowedOrigins,
                expiresAt = expiresAt,
                maxUses = maxUses,
                useCount = currentUseCount
            )

            TokenValidationResult.Valid(validatedToken)
        }
    }

    /**
     * Increment the usage count for a token.
     * Should be called after successful token use.
     *
     * @param tokenId The token UUID.
     * @return The new use count, or null if token not found.
     */
    fun incrementUsage(tokenId: UUID): Int? {
        return transaction {
            val current = VerifyWidgetTokens.selectAll()
                .where { VerifyWidgetTokens.id eq tokenId }
                .singleOrNull()
                ?.get(VerifyWidgetTokens.useCount)
                ?: return@transaction null

            val newCount = current + 1
            VerifyWidgetTokens.update({ VerifyWidgetTokens.id eq tokenId }) {
                it[useCount] = newCount
            }

            logger.debug { "Incremented token $tokenId usage to $newCount" }
            newCount
        }
    }

    /**
     * Revoke a token by deleting it from the database.
     *
     * @param tokenId The token UUID.
     * @param organizationId The organization ID (for authorization).
     * @return True if the token was deleted, false if not found or unauthorized.
     */
    fun revokeToken(tokenId: UUID, organizationId: UUID): Boolean {
        return transaction {
            val deleted = VerifyWidgetTokens.deleteWhere {
                (VerifyWidgetTokens.id eq tokenId) and
                (VerifyWidgetTokens.organizationId eq organizationId)
            }

            if (deleted > 0) {
                logger.info { "Revoked token $tokenId for org $organizationId" }
                true
            } else {
                logger.debug { "Token $tokenId not found or not owned by org $organizationId" }
                false
            }
        }
    }

    /**
     * Hash a token using SHA-256.
     *
     * @param token The full token string.
     * @return Hex-encoded SHA-256 hash.
     */
    fun hashToken(token: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(token.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    /**
     * Sign a payload using HMAC-SHA256.
     *
     * @param payload The payload to sign.
     * @return URL-safe Base64 encoded signature.
     */
    internal fun signPayload(payload: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(hmacKey, "HmacSHA256"))
        val signature = mac.doFinal(payload.toByteArray())
        return Base64.getUrlEncoder().withoutPadding().encodeToString(signature)
    }

    /**
     * Generate a cryptographically secure random nonce.
     *
     * @return A 16-character hex nonce.
     */
    internal fun generateNonce(): String {
        val bytes = ByteArray(8)
        secureRandom.nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    /**
     * Constant-time string comparison to prevent timing attacks.
     */
    private fun constantTimeEquals(a: String, b: String): Boolean {
        if (a.length != b.length) return false
        var result = 0
        for (i in a.indices) {
            result = result or (a[i].code xor b[i].code)
        }
        return result == 0
    }

    /**
     * Check if an origin is allowed by the allowedOrigins list.
     * Supports exact matches and wildcard patterns.
     *
     * @param origin The origin to check (e.g., "https://shop.example.com").
     * @param allowedOrigins List of allowed origin patterns.
     * @return True if the origin is allowed.
     */
    internal fun isOriginAllowed(origin: String, allowedOrigins: List<String>): Boolean {
        for (pattern in allowedOrigins) {
            if (pattern == "*") return true
            if (pattern == origin) return true

            // Support wildcard subdomain matching: *.example.com
            if (pattern.startsWith("*.")) {
                val domain = pattern.removePrefix("*")  // e.g., ".example.com"
                val originHost = origin.substringAfter("://")  // e.g., "api.shop.example.com"

                // Check if origin ends with the domain suffix (e.g., "api.shop.example.com" ends with ".example.com")
                if (originHost.endsWith(domain)) {
                    return true
                }

                // Also match the bare domain (e.g., "example.com" matches "*.example.com")
                if (originHost == domain.removePrefix(".")) {
                    return true
                }
            }
        }
        return false
    }
}
