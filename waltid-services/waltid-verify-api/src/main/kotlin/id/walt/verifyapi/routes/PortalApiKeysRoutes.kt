package id.walt.verifyapi.routes

import id.walt.verifyapi.auth.AUTH_PORTAL_JWT
import id.walt.verifyapi.auth.ApiKeyAuthProvider
import id.walt.verifyapi.db.VerifyApiKeys
import id.walt.verifyapi.portal.PortalUserPrincipal
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.http.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.security.SecureRandom
import java.time.Instant
import java.util.UUID

private val logger = KotlinLogging.logger {}

/**
 * Request to create a new API key.
 */
@Serializable
data class CreateApiKeyRequest(
    /** Optional friendly name for the key */
    val name: String? = null,
    /** Environment: "live" or "test" */
    val environment: String = "test"
)

/**
 * Response after creating an API key.
 * The `key` field contains the full key value and is ONLY returned at creation time.
 */
@Serializable
data class CreateApiKeyResponse(
    /** Unique key ID */
    val id: String,
    /** Full API key value - ONLY returned on creation, cannot be retrieved later */
    val key: String,
    /** Key prefix for identification (e.g., "vfy_test_abc123") */
    val keyPrefix: String,
    /** Environment: "live" or "test" */
    val environment: String,
    /** Optional friendly name */
    val name: String?,
    /** ISO 8601 creation timestamp */
    val createdAt: String
)

/**
 * Response for listing API keys.
 * Does NOT include the full key value.
 */
@Serializable
data class ApiKeyResponse(
    /** Unique key ID */
    val id: String,
    /** Key prefix for identification (e.g., "vfy_test_abc123") */
    val keyPrefix: String,
    /** Environment: "live" or "test" */
    val environment: String,
    /** Optional friendly name */
    val name: String?,
    /** ISO 8601 timestamp of last use, or null if never used */
    val lastUsedAt: String?,
    /** ISO 8601 creation timestamp */
    val createdAt: String,
    /** Whether the key is revoked */
    val revoked: Boolean
)

/**
 * Valid environments for API keys.
 */
private val VALID_ENVIRONMENTS = setOf("live", "test")

/**
 * Configure API key management routes for the portal under /portal/api-keys.
 *
 * Provides endpoints for creating, listing, and revoking API keys.
 * Requires portal JWT authentication.
 */
fun Route.portalApiKeysRoutes() {
    authenticate(AUTH_PORTAL_JWT) {
        route("/portal/api-keys") {
            /**
             * GET /portal/api-keys
             *
             * List all API keys for the authenticated organization.
             * Returns keys with their metadata but NOT the full key value.
             * Includes both active and revoked keys.
             */
            get {
                val principal = call.principal<PortalUserPrincipal>()!!
                logger.debug { "Listing API keys for organization: ${principal.organizationId}" }

                val keys = transaction {
                    VerifyApiKeys.selectAll()
                        .where { VerifyApiKeys.organizationId eq principal.organizationId }
                        .orderBy(VerifyApiKeys.createdAt to org.jetbrains.exposed.v1.core.SortOrder.DESC)
                        .map { row ->
                            ApiKeyResponse(
                                id = row[VerifyApiKeys.id].value.toString(),
                                keyPrefix = row[VerifyApiKeys.keyPrefix],
                                environment = row[VerifyApiKeys.environment],
                                name = row[VerifyApiKeys.name],
                                lastUsedAt = row[VerifyApiKeys.lastUsedAt]?.toString(),
                                createdAt = row[VerifyApiKeys.createdAt].toString(),
                                revoked = row[VerifyApiKeys.revokedAt] != null
                            )
                        }
                }

                call.respond(keys)
            }

            /**
             * POST /portal/api-keys
             *
             * Create a new API key.
             * Returns the full key value ONCE - it cannot be retrieved later.
             */
            post {
                val principal = call.principal<PortalUserPrincipal>()!!
                val request = call.receive<CreateApiKeyRequest>()

                logger.debug { "Creating API key for organization: ${principal.organizationId}" }

                // Validate environment
                if (request.environment !in VALID_ENVIRONMENTS) {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        mapOf(
                            "error" to "Invalid environment",
                            "message" to "Environment must be 'live' or 'test'",
                            "valid_environments" to VALID_ENVIRONMENTS.toList()
                        )
                    )
                    return@post
                }

                // Validate name length if provided
                if (request.name != null && request.name.length > 100) {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        mapOf(
                            "error" to "Invalid name",
                            "message" to "Name must be 100 characters or less"
                        )
                    )
                    return@post
                }

                // Generate the full API key
                val (fullKey, prefix) = generateApiKey(request.environment)
                val keyHash = ApiKeyAuthProvider.hashApiKey(fullKey)
                val now = Instant.now()

                val keyId = transaction {
                    VerifyApiKeys.insert {
                        it[organizationId] = principal.organizationId
                        it[VerifyApiKeys.keyHash] = keyHash
                        it[keyPrefix] = prefix
                        it[environment] = request.environment
                        it[name] = request.name
                        it[createdAt] = now
                    } get VerifyApiKeys.id
                }

                logger.info { "Created API key ${keyId.value} (${prefix}***) for organization: ${principal.organizationId}" }

                call.respond(
                    HttpStatusCode.Created,
                    CreateApiKeyResponse(
                        id = keyId.value.toString(),
                        key = fullKey,  // Full key only returned here
                        keyPrefix = prefix,
                        environment = request.environment,
                        name = request.name,
                        createdAt = now.toString()
                    )
                )
            }

            /**
             * DELETE /portal/api-keys/{id}
             *
             * Revoke an API key.
             * The key becomes invalid immediately but is retained for audit purposes.
             */
            delete("/{id}") {
                val principal = call.principal<PortalUserPrincipal>()!!
                val keyId = call.parameters["id"]?.let {
                    runCatching { UUID.fromString(it) }.getOrNull()
                }

                if (keyId == null) {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        mapOf("error" to "Invalid key ID format")
                    )
                    return@delete
                }

                val updated = transaction {
                    // First check if the key exists and belongs to this organization
                    val existingKey = VerifyApiKeys.selectAll()
                        .where {
                            (VerifyApiKeys.id eq keyId) and
                            (VerifyApiKeys.organizationId eq principal.organizationId)
                        }
                        .singleOrNull()

                    if (existingKey == null) {
                        return@transaction null
                    }

                    // Check if already revoked
                    if (existingKey[VerifyApiKeys.revokedAt] != null) {
                        return@transaction "already_revoked"
                    }

                    // Revoke the key by setting revokedAt timestamp
                    VerifyApiKeys.update({
                        (VerifyApiKeys.id eq keyId) and
                        (VerifyApiKeys.organizationId eq principal.organizationId)
                    }) {
                        it[revokedAt] = Instant.now()
                    }

                    "revoked"
                }

                when (updated) {
                    null -> {
                        call.respond(
                            HttpStatusCode.NotFound,
                            mapOf("error" to "API key not found")
                        )
                    }
                    "already_revoked" -> {
                        call.respond(
                            HttpStatusCode.Conflict,
                            mapOf("error" to "API key is already revoked")
                        )
                    }
                    else -> {
                        logger.info { "Revoked API key $keyId for organization: ${principal.organizationId}" }
                        call.respond(HttpStatusCode.NoContent)
                    }
                }
            }

            /**
             * GET /portal/api-keys/{id}
             *
             * Get details of a specific API key.
             * Does NOT return the full key value.
             */
            get("/{id}") {
                val principal = call.principal<PortalUserPrincipal>()!!
                val keyId = call.parameters["id"]?.let {
                    runCatching { UUID.fromString(it) }.getOrNull()
                }

                if (keyId == null) {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        mapOf("error" to "Invalid key ID format")
                    )
                    return@get
                }

                val key = transaction {
                    VerifyApiKeys.selectAll()
                        .where {
                            (VerifyApiKeys.id eq keyId) and
                            (VerifyApiKeys.organizationId eq principal.organizationId)
                        }
                        .singleOrNull()
                        ?.let { row ->
                            ApiKeyResponse(
                                id = row[VerifyApiKeys.id].value.toString(),
                                keyPrefix = row[VerifyApiKeys.keyPrefix],
                                environment = row[VerifyApiKeys.environment],
                                name = row[VerifyApiKeys.name],
                                lastUsedAt = row[VerifyApiKeys.lastUsedAt]?.toString(),
                                createdAt = row[VerifyApiKeys.createdAt].toString(),
                                revoked = row[VerifyApiKeys.revokedAt] != null
                            )
                        }
                }

                if (key == null) {
                    call.respond(
                        HttpStatusCode.NotFound,
                        mapOf("error" to "API key not found")
                    )
                    return@get
                }

                call.respond(key)
            }
        }
    }
}

/**
 * Generate a new API key with the format: vfy_{env}_{random}
 *
 * @param environment The environment ("live" or "test")
 * @return Pair of (fullKey, prefix) where prefix is the first 16 characters
 */
private fun generateApiKey(environment: String): Pair<String, String> {
    val envPrefix = if (environment == "live") "live" else "test"
    val randomPart = generateRandomString(24)
    val fullKey = "vfy_${envPrefix}_$randomPart"
    // Prefix includes enough to identify the key: vfy_{env}_{first8chars}
    val prefix = "vfy_${envPrefix}_${randomPart.take(8)}"
    return fullKey to prefix
}

/**
 * Generate a cryptographically secure random string for the API key.
 *
 * @param length Length of the random string
 * @return Random alphanumeric string
 */
private fun generateRandomString(length: Int): String {
    val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
    val random = SecureRandom()
    return (1..length)
        .map { chars[random.nextInt(chars.length)] }
        .joinToString("")
}
