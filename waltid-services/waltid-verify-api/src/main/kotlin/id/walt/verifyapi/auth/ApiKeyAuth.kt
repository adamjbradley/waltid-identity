package id.walt.verifyapi.auth

import id.walt.verifyapi.db.VerifyApiKeys
import id.walt.verifyapi.db.VerifyOrganizations
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.server.auth.*
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID

private val logger = KotlinLogging.logger {}

/**
 * Principal representing an authenticated API key.
 * Contains information about the organization and environment.
 */
data class ApiKeyPrincipal(
    val organizationId: UUID,
    val organizationName: String,
    val environment: String,
    val keyId: UUID
) : Principal

/**
 * Custom Ktor authentication provider for API key authentication.
 * Supports keys in Authorization header (with or without Bearer prefix) or X-API-Key header.
 *
 * API key format: vfy_{env}_{random} where env is 'live' or 'test'
 * Keys are stored as SHA-256 hashes for security.
 */
class ApiKeyAuthProvider(config: Config) : AuthenticationProvider(config) {

    class Config(name: String?) : AuthenticationProvider.Config(name)

    override suspend fun onAuthenticate(context: AuthenticationContext) {
        val call = context.call
        val authHeader = call.request.headers["Authorization"]
            ?: call.request.headers["X-API-Key"]

        if (authHeader == null) {
            logger.debug { "No API key provided in request" }
            context.challenge("ApiKey", AuthenticationFailedCause.NoCredentials) { ch, _ ->
                ch.complete()
            }
            return
        }

        val apiKey = authHeader.removePrefix("Bearer ").trim()
        val principal = validateApiKey(apiKey)

        if (principal == null) {
            logger.debug { "Invalid API key provided" }
            context.challenge("ApiKey", AuthenticationFailedCause.InvalidCredentials) { ch, _ ->
                ch.complete()
            }
            return
        }

        context.principal(principal)
    }

    /**
     * Validates an API key against the database.
     * Updates the last_used_at timestamp on successful validation.
     *
     * @param apiKey The raw API key to validate
     * @return ApiKeyPrincipal if valid, null otherwise
     */
    private fun validateApiKey(apiKey: String): ApiKeyPrincipal? {
        // Validate key format
        if (!apiKey.startsWith("vfy_")) {
            logger.debug { "Invalid key format: doesn't start with vfy_" }
            return null
        }

        val keyHash = hashApiKey(apiKey)

        return transaction {
            val row = (VerifyApiKeys innerJoin VerifyOrganizations)
                .selectAll()
                .where {
                    (VerifyApiKeys.keyHash eq keyHash) and
                    (VerifyApiKeys.revokedAt eq null)
                }
                .singleOrNull() ?: return@transaction null

            // Update last used timestamp
            VerifyApiKeys.update({ VerifyApiKeys.id eq row[VerifyApiKeys.id] }) {
                it[lastUsedAt] = Instant.now()
            }

            ApiKeyPrincipal(
                organizationId = row[VerifyOrganizations.id].value,
                organizationName = row[VerifyOrganizations.name],
                environment = row[VerifyApiKeys.environment],
                keyId = row[VerifyApiKeys.id].value
            )
        }
    }

    companion object {
        /**
         * Hash an API key using SHA-256.
         *
         * @param key The raw API key
         * @return Hex-encoded SHA-256 hash
         */
        fun hashApiKey(key: String): String {
            val digest = MessageDigest.getInstance("SHA-256")
            return digest.digest(key.toByteArray()).joinToString("") { "%02x".format(it) }
        }
    }
}

/**
 * Extension function to register the API key authentication provider with Ktor.
 *
 * Usage:
 * ```kotlin
 * install(Authentication) {
 *     apiKey("api-key")
 * }
 * ```
 */
fun AuthenticationConfig.apiKey(name: String? = null, configure: ApiKeyAuthProvider.Config.() -> Unit = {}) {
    val config = ApiKeyAuthProvider.Config(name).apply(configure)
    register(ApiKeyAuthProvider(config))
}
