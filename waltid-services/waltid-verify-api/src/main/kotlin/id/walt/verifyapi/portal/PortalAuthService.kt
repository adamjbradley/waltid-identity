package id.walt.verifyapi.portal

import com.auth0.jwt.JWT
import com.auth0.jwt.JWTVerifier
import com.auth0.jwt.algorithms.Algorithm
import com.auth0.jwt.exceptions.JWTVerificationException
import com.auth0.jwt.interfaces.DecodedJWT
import id.walt.verifyapi.db.VerifyOrganizations
import id.walt.verifyapi.db.VerifyUsers
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.server.auth.Principal
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.security.SecureRandom
import java.time.Instant
import java.util.Base64
import java.util.Date
import java.util.UUID
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours

private val logger = KotlinLogging.logger {}

data class PortalUserPrincipal(
    val userId: UUID,
    val email: String,
    val organizationId: UUID,
    val organizationName: String,
    val role: String,
    val tokenType: String
) : Principal

sealed class PasswordVerifyResult {
    data object Valid : PasswordVerifyResult()
    data object Invalid : PasswordVerifyResult()
    data object UserNotFound : PasswordVerifyResult()
    data object EmailNotVerified : PasswordVerifyResult()
}

@Serializable
data class TokenPair(
    val accessToken: String,
    val refreshToken: String,
    val accessTokenExpiresAt: Long,
    val refreshTokenExpiresAt: Long,
    val tokenType: String = "Bearer"
)

sealed class RefreshResult {
    data class Success(val tokens: TokenPair) : RefreshResult()
    data object InvalidToken : RefreshResult()
    data object UserNotFound : RefreshResult()
}

data class JwtConfig(
    val issuer: String = System.getenv("JWT_ISSUER") ?: "verify-api",
    val audience: String = System.getenv("JWT_AUDIENCE") ?: "verify-portal",
    val secret: String = System.getenv("JWT_SECRET") ?: generateDefaultSecret(),
    val accessTokenLifetime: Duration = parseEnvDuration("JWT_ACCESS_TOKEN_LIFETIME") ?: 1.hours,
    val refreshTokenLifetime: Duration = parseEnvDuration("JWT_REFRESH_TOKEN_LIFETIME") ?: 7.days,
    val realm: String = System.getenv("JWT_REALM") ?: "verify-portal"
) {
    companion object {
        private fun parseEnvDuration(envVar: String): Duration? {
            return System.getenv(envVar)?.let {
                try {
                    Duration.parse(it)
                } catch (e: Exception) {
                    logger.warn { "Failed to parse duration from $envVar" }
                    null
                }
            }
        }

        private fun generateDefaultSecret(): String {
            val bytes = ByteArray(32)
            SecureRandom().nextBytes(bytes)
            val secret = Base64.getEncoder().encodeToString(bytes)
            logger.warn { "JWT_SECRET not set, using random secret" }
            return secret
        }
    }
}

object PortalAuthService {

    val jwtConfig = JwtConfig()

    private val algorithm: Algorithm = Algorithm.HMAC256(jwtConfig.secret)

    private val accessTokenVerifier: JWTVerifier = JWT.require(algorithm)
        .withIssuer(jwtConfig.issuer)
        .withAudience(jwtConfig.audience)
        .withClaim("type", "access")
        .build()

    private val refreshTokenVerifier: JWTVerifier = JWT.require(algorithm)
        .withIssuer(jwtConfig.issuer)
        .withAudience(jwtConfig.audience)
        .withClaim("type", "refresh")
        .build()

    private const val BCRYPT_COST = 12

    fun hashPassword(password: String): String {
        return BCryptUtil.hashPassword(password, BCRYPT_COST)
    }

    fun verifyPassword(password: String, hash: String): Boolean {
        return BCryptUtil.checkPassword(password, hash)
    }

    fun authenticateUser(email: String, password: String): Pair<PasswordVerifyResult, UserInfo?> {
        val userInfo = transaction {
            val row = (VerifyUsers innerJoin VerifyOrganizations)
                .selectAll()
                .where { VerifyUsers.email eq email.lowercase() }
                .singleOrNull()
                ?: return@transaction null

            UserInfo(
                userId = row[VerifyUsers.id].value,
                email = row[VerifyUsers.email],
                passwordHash = row[VerifyUsers.passwordHash],
                organizationId = row[VerifyOrganizations.id].value,
                organizationName = row[VerifyOrganizations.name],
                role = row[VerifyUsers.role],
                emailVerifiedAt = row[VerifyUsers.emailVerifiedAt]
            )
        }

        if (userInfo == null) {
            BCryptUtil.hashPassword("dummy", BCRYPT_COST)
            return PasswordVerifyResult.UserNotFound to null
        }

        if (!verifyPassword(password, userInfo.passwordHash)) {
            return PasswordVerifyResult.Invalid to null
        }

        transaction {
            VerifyUsers.update({ VerifyUsers.id eq userInfo.userId }) {
                it[lastLoginAt] = Instant.now()
            }
        }

        logger.info { "User authenticated: ${userInfo.email}" }
        return PasswordVerifyResult.Valid to userInfo
    }

    fun generateTokens(userInfo: UserInfo): TokenPair {
        val now = Instant.now()
        val accessExpiry = now.plusSeconds(jwtConfig.accessTokenLifetime.inWholeSeconds)
        val refreshExpiry = now.plusSeconds(jwtConfig.refreshTokenLifetime.inWholeSeconds)
        val jti = UUID.randomUUID().toString()

        val accessToken = JWT.create()
            .withIssuer(jwtConfig.issuer)
            .withAudience(jwtConfig.audience)
            .withSubject(userInfo.userId.toString())
            .withClaim("email", userInfo.email)
            .withClaim("org_id", userInfo.organizationId.toString())
            .withClaim("org_name", userInfo.organizationName)
            .withClaim("role", userInfo.role)
            .withClaim("type", "access")
            .withJWTId(jti)
            .withIssuedAt(Date.from(now))
            .withExpiresAt(Date.from(accessExpiry))
            .sign(algorithm)

        val refreshToken = JWT.create()
            .withIssuer(jwtConfig.issuer)
            .withAudience(jwtConfig.audience)
            .withSubject(userInfo.userId.toString())
            .withClaim("type", "refresh")
            .withJWTId(jti + "-refresh")
            .withIssuedAt(Date.from(now))
            .withExpiresAt(Date.from(refreshExpiry))
            .sign(algorithm)

        logger.debug { "Generated tokens for user ${userInfo.userId}" }

        return TokenPair(
            accessToken = accessToken,
            refreshToken = refreshToken,
            accessTokenExpiresAt = accessExpiry.epochSecond,
            refreshTokenExpiresAt = refreshExpiry.epochSecond
        )
    }

    fun validateAccessToken(token: String): PortalUserPrincipal? {
        return try {
            val decoded = accessTokenVerifier.verify(token)
            extractPrincipal(decoded, "access")
        } catch (e: JWTVerificationException) {
            logger.debug { "Access token validation failed: ${e.message}" }
            null
        }
    }

    fun refreshTokens(refreshToken: String): RefreshResult {
        val decoded = try {
            refreshTokenVerifier.verify(refreshToken)
        } catch (e: JWTVerificationException) {
            logger.debug { "Refresh token validation failed: ${e.message}" }
            return RefreshResult.InvalidToken
        }

        val userId = try {
            UUID.fromString(decoded.subject)
        } catch (e: Exception) {
            return RefreshResult.InvalidToken
        }

        val userInfo = transaction {
            val row = (VerifyUsers innerJoin VerifyOrganizations)
                .selectAll()
                .where { VerifyUsers.id eq userId }
                .singleOrNull()
                ?: return@transaction null

            UserInfo(
                userId = row[VerifyUsers.id].value,
                email = row[VerifyUsers.email],
                passwordHash = "",
                organizationId = row[VerifyOrganizations.id].value,
                organizationName = row[VerifyOrganizations.name],
                role = row[VerifyUsers.role],
                emailVerifiedAt = row[VerifyUsers.emailVerifiedAt]
            )
        }

        if (userInfo == null) {
            logger.debug { "Refresh failed: user $userId not found" }
            return RefreshResult.UserNotFound
        }

        val tokens = generateTokens(userInfo)
        logger.debug { "Refreshed tokens for user $userId" }
        return RefreshResult.Success(tokens)
    }

    private fun extractPrincipal(decoded: DecodedJWT, expectedType: String): PortalUserPrincipal? {
        val type = decoded.getClaim("type").asString()
        if (type != expectedType) {
            return null
        }

        val userId = try {
            UUID.fromString(decoded.subject)
        } catch (e: Exception) {
            return null
        }

        val email = decoded.getClaim("email").asString() ?: return null
        val orgId = decoded.getClaim("org_id").asString()?.let { UUID.fromString(it) } ?: return null
        val orgName = decoded.getClaim("org_name").asString() ?: return null
        val role = decoded.getClaim("role").asString() ?: return null

        return PortalUserPrincipal(
            userId = userId,
            email = email,
            organizationId = orgId,
            organizationName = orgName,
            role = role,
            tokenType = type
        )
    }

    data class UserInfo(
        val userId: UUID,
        val email: String,
        val passwordHash: String,
        val organizationId: UUID,
        val organizationName: String,
        val role: String,
        val emailVerifiedAt: Instant?
    )
}

object BCryptUtil {

    private val BCRYPT_PATTERN = Regex("""\${'$'}2[aby]?\${'$'}\d{1,2}\${'$'}[./A-Za-z0-9]{53}""")

    fun hashPassword(password: String, cost: Int = 12): String {
        val iterations = 1 shl cost
        val salt = ByteArray(16)
        SecureRandom().nextBytes(salt)

        val spec = javax.crypto.spec.PBEKeySpec(
            password.toCharArray(),
            salt,
            iterations,
            256
        )
        val factory = javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val hash = factory.generateSecret(spec).encoded

        val saltBase64 = Base64.getEncoder().encodeToString(salt)
        val hashBase64 = Base64.getEncoder().encodeToString(hash)

        return "\$pbkdf2\$" + iterations + "\$" + saltBase64 + "\$" + hashBase64
    }

    fun checkPassword(password: String, storedHash: String): Boolean {
        return when {
            storedHash.startsWith("\$pbkdf2\$") -> {
                val parts = storedHash.split("\$").filter { it.isNotEmpty() }
                if (parts.size != 4) return false

                val iterations = parts[1].toIntOrNull() ?: return false
                val salt = try {
                    Base64.getDecoder().decode(parts[2])
                } catch (e: Exception) {
                    return false
                }
                val expectedHash = try {
                    Base64.getDecoder().decode(parts[3])
                } catch (e: Exception) {
                    return false
                }

                val spec = javax.crypto.spec.PBEKeySpec(
                    password.toCharArray(),
                    salt,
                    iterations,
                    256
                )
                val factory = javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                val computedHash = factory.generateSecret(spec).encoded

                constantTimeEquals(computedHash, expectedHash)
            }
            BCRYPT_PATTERN.matches(storedHash) -> {
                logger.warn { "Encountered bcrypt hash but jBCrypt library not available" }
                false
            }
            else -> {
                logger.warn { "Unknown hash format" }
                false
            }
        }
    }

    private fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean {
        if (a.size != b.size) return false
        var result = 0
        for (i in a.indices) {
            result = result or (a[i].toInt() xor b[i].toInt())
        }
        return result == 0
    }
}
