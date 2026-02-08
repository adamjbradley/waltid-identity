package id.walt.federation

import id.walt.federation.exceptions.EntityStatementFetchException
import id.walt.federation.models.EntityStatement
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

private val log = KotlinLogging.logger {}

class EntityStatementFetcher(
    private val httpClient: HttpClient,
    private val cacheTtlSeconds: Long = 3600
) {
    private val cache = mutableMapOf<String, CachedStatement>()
    private val mutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true }

    private data class CachedStatement(
        val statement: EntityStatement,
        val fetchedAt: Instant
    )

    suspend fun fetch(entityId: String): EntityStatement {
        mutex.withLock {
            cache[entityId]?.let { cached ->
                val age = Clock.System.now() - cached.fetchedAt
                if (age < cacheTtlSeconds.seconds) {
                    return cached.statement
                }
            }
        }

        return try {
            val wellKnownUrl = buildWellKnownUrl(entityId)
            log.info { "Fetching entity statement from $wellKnownUrl" }
            val response = httpClient.get(wellKnownUrl).bodyAsText()

            // Entity statements are JWTs - extract the payload
            val statement = parseEntityStatementJwt(response)

            mutex.withLock {
                cache[entityId] = CachedStatement(statement, Clock.System.now())
            }

            statement
        } catch (e: Exception) {
            throw EntityStatementFetchException(entityId, e)
        }
    }

    suspend fun fetchSubordinateStatement(superiorEntityId: String, subordinateEntityId: String): EntityStatement {
        return try {
            val fetchUrl = "$superiorEntityId/fetch?sub=$subordinateEntityId"
            log.info { "Fetching subordinate statement from $fetchUrl" }
            val response = httpClient.get(fetchUrl).bodyAsText()
            parseEntityStatementJwt(response)
        } catch (e: Exception) {
            throw EntityStatementFetchException(subordinateEntityId, e)
        }
    }

    private fun buildWellKnownUrl(entityId: String): String {
        val base = entityId.trimEnd('/')
        return "$base/.well-known/openid-federation"
    }

    private fun parseEntityStatementJwt(jwt: String): EntityStatement {
        // JWT format: header.payload.signature
        val parts = jwt.trim().split(".")
        if (parts.size != 3) {
            throw IllegalArgumentException("Invalid JWT format: expected 3 parts, got ${parts.size}")
        }

        val payloadJson = decodeBase64Url(parts[1])
        return json.decodeFromString<EntityStatement>(payloadJson)
    }

    private fun decodeBase64Url(input: String): String {
        // Pad to multiple of 4
        val padded = when (input.length % 4) {
            2 -> "$input=="
            3 -> "$input="
            else -> input
        }
        // Convert base64url to base64
        val base64 = padded.replace('-', '+').replace('_', '/')
        // Decode
        val bytes = kotlin.io.encoding.Base64.decode(base64)
        return bytes.decodeToString()
    }

    fun clearCache() {
        cache.clear()
    }
}
