package id.walt.verifyapi.service

import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.okhttp.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

private val logger = KotlinLogging.logger {}

/**
 * HTTP client for communicating with verifier-api2.
 * Handles session creation with signed JAR requests for EUDI wallet compatibility.
 */
object Verifier2Client {

    private val verifierApi2Url: String by lazy {
        System.getenv("VERIFIER_API2_URL") ?: "http://localhost:7004"
    }

    /**
     * Signing key configuration for EUDI wallet compatibility.
     * Must be an EC P-256 key with private key component.
     */
    private val signingKey: JsonObject by lazy {
        val keyJson = System.getenv("VERIFIER_SIGNING_KEY") ?: DEFAULT_SIGNING_KEY
        Json.parseToJsonElement(keyJson).jsonObject
    }

    /**
     * X.509 certificate chain for client identification.
     * Used with x509_san_dns client ID prefix.
     */
    private val x5c: List<String> by lazy {
        val x5cJson = System.getenv("VERIFIER_X5C") ?: DEFAULT_X5C
        Json.parseToJsonElement(x5cJson).jsonArray.map { it.jsonPrimitive.content }
    }

    /**
     * Client ID for EUDI wallet verification.
     * Must be in format: x509_san_dns:{domain}
     */
    private val clientId: String by lazy {
        System.getenv("VERIFIER_CLIENT_ID") ?: DEFAULT_CLIENT_ID
    }

    private val httpClient = HttpClient(OkHttp) {
        install(ContentNegotiation) {
            json(Json {
                ignoreUnknownKeys = true
                encodeDefaults = true
            })
        }
    }

    /**
     * Response from verifier-api2 session creation.
     */
    @Serializable
    data class VerificationSessionResponse(
        val sessionId: String,
        val bootstrapAuthorizationRequestUrl: String,
        val fullAuthorizationRequestUrl: String? = null
    )

    /**
     * Response from verifier-api2 session info endpoint.
     */
    @Serializable
    data class SessionInfoResponse(
        val id: String,
        val status: String,
        val attempted: Boolean = false,
        val presentedCredentials: JsonObject? = null,
        val policyResults: JsonObject? = null
    )

    /**
     * Creates a verification session on verifier-api2 with EUDI-compatible signed JAR request.
     *
     * @param dcqlQuery The DCQL query JSON specifying credentials and claims to request
     * @param rpId Optional registered RP ID. When provided, verifier-api2 resolves the RP's
     *             own clientId, signing key, and x5c from the RP Registrar. When null, the
     *             global verifier config (clientId, signingKey, x5c) is used as fallback.
     * @return Session response with authorization URLs
     */
    /**
     * Builds the session creation URL, appending `?rpId=` when an RP ID is provided.
     */
    internal fun buildSessionUrl(rpId: String?): String {
        return if (rpId != null) {
            "$verifierApi2Url/verification-session/create?rpId=$rpId"
        } else {
            "$verifierApi2Url/verification-session/create"
        }
    }

    /**
     * Builds the session creation request body.
     * When rpId is null, includes global signing config (clientId, key, x5c).
     * When rpId is set, omits them so verifier-api2 resolves from the registered RP.
     */
    internal fun buildSessionRequestBody(dcqlQuery: JsonObject, rpId: String?): JsonObject {
        return buildJsonObject {
            put("flow_type", "cross_device")
            putJsonObject("core_flow") {
                put("signed_request", true)
                put("dcql_query", dcqlQuery)
                if (rpId == null) {
                    // Fallback: use global verifier config
                    put("clientId", clientId)
                    putJsonObject("key") {
                        put("type", "jwk")
                        put("jwk", signingKey)
                    }
                    putJsonArray("x5c") {
                        x5c.forEach { add(it) }
                    }
                }
                // When rpId is set, verifier-api2 resolves key/x5c from the registered RP
            }
        }
    }

    suspend fun createSession(dcqlQuery: JsonObject, rpId: String? = null): VerificationSessionResponse {
        logger.info { "Creating verifier-api2 session with DCQL query${rpId?.let { " (rpId=$it)" } ?: " (global config)"}" }
        logger.debug { "DCQL Query: $dcqlQuery" }

        val requestBody = buildSessionRequestBody(dcqlQuery, rpId)
        val url = buildSessionUrl(rpId)

        logger.debug { "Request URL: $url" }
        logger.debug { "Request body: $requestBody" }

        val response = httpClient.post(url) {
            contentType(ContentType.Application.Json)
            setBody(requestBody)
        }

        if (!response.status.isSuccess()) {
            val errorBody = response.bodyAsText()
            logger.error { "Failed to create verifier-api2 session: ${response.status} - $errorBody" }
            throw RuntimeException("Failed to create verification session: ${response.status} - $errorBody")
        }

        val sessionResponse = response.body<VerificationSessionResponse>()
        logger.info { "Created verifier-api2 session: ${sessionResponse.sessionId}" }

        return sessionResponse
    }

    /**
     * Gets the status of a verification session from verifier-api2.
     *
     * @param sessionId The verifier-api2 session ID
     * @return Session info including status and presented credentials
     */
    suspend fun getSessionInfo(sessionId: String): SessionInfoResponse {
        val response = httpClient.get("$verifierApi2Url/verification-session/$sessionId/info")

        if (!response.status.isSuccess()) {
            val errorBody = response.bodyAsText()
            logger.error { "Failed to get session info: ${response.status} - $errorBody" }
            throw RuntimeException("Failed to get session info: ${response.status}")
        }

        return response.body()
    }

    /**
     * Lists all registered RPs from verifier-api2's RP Registrar.
     * Used for self-healing when an RP ID becomes stale.
     */
    suspend fun listRegisteredRps(): List<JsonObject> {
        val response = httpClient.get("$verifierApi2Url/admin/rp")

        if (!response.status.isSuccess()) {
            logger.warn { "Failed to list registered RPs: ${response.status}" }
            return emptyList()
        }

        return try {
            val body = response.body<JsonArray>()
            body.filterIsInstance<JsonObject>()
        } catch (e: Exception) {
            logger.warn(e) { "Failed to parse RP list response" }
            emptyList()
        }
    }

    /**
     * Resolves an RP ID by matching the domain in the registered RPs.
     * Looks for active RPs whose clientId contains the given domain.
     *
     * @param domain The domain to search for (e.g., "rp.theaustraliahack.com")
     * @return The RP ID if found, null otherwise
     */
    suspend fun resolveRpIdByDomain(domain: String): String? {
        val rps = listRegisteredRps()
        for (rp in rps) {
            val status = (rp["status"] as? JsonPrimitive)?.content
            if (status != "ACTIVE") continue

            val rpClientId = (rp["clientId"] as? JsonPrimitive)?.content
            val rpDomain = (rp["domain"] as? JsonPrimitive)?.content
            val rpId = (rp["id"] as? JsonPrimitive)?.content

            if (rpId != null && (rpDomain == domain || rpClientId?.contains(domain) == true)) {
                logger.info { "Resolved domain '$domain' to RP ID: $rpId" }
                return rpId
            }
        }
        logger.warn { "No active RP found for domain: $domain" }
        return null
    }

    // Default values from environment - these match verifier2.theaustraliahack.com configuration
    private const val DEFAULT_CLIENT_ID = "x509_san_dns:verifier2.theaustraliahack.com"

    private const val DEFAULT_SIGNING_KEY = """{"kty":"EC","crv":"P-256","x":"1Z2eGpdQVfWkAQQmNv8oT-lMwbhsFxWTZmhAYFHR5wY","y":"tvX699C21qGEMq7zqjpEhqy2kPT8KInnbxlLZzeSXdo","d":"j6-GyxLnrDSQGCljc678kmrihQFa0GR92JZXHDEQX38"}"""

    private const val DEFAULT_X5C = """["MIIBnzCCAUagAwIBAgIUQSg5NhDlxwDFyAM7YJe++0QGyKIwCgYIKoZIzj0EAwIwKTEnMCUGA1UEAwwedmVyaWZpZXIyLnRoZWF1c3RyYWxpYWhhY2suY29tMB4XDTI2MDIwMzAzNTIwM1oXDTI3MDIwMzAzNTIwM1owKTEnMCUGA1UEAwwedmVyaWZpZXIyLnRoZWF1c3RyYWxpYWhhY2suY29tMFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAE1Z2eGpdQVfWkAQQmNv8oT+lMwbhsFxWTZmhAYFHR5wa29fr30LbWoYQyrvOqOkSGrLaQ9PwoiedvGUtnN5Jd2qNMMEowKQYDVR0RBCIwIIIedmVyaWZpZXIyLnRoZWF1c3RyYWxpYWhhY2suY29tMB0GA1UdDgQWBBRt0uKz8aKVlUxKF9j6vhAsGl3nHDAKBggqhkjOPQQDAgNHADBEAiAQ+AlF3Q4dput8QTizDyKo99R/sv3CC7BzqEjOxxsnzQIgF+rnBf0HghobWkjSVNwP8j/ekasfjp+1HDJclcNaUvs="]"""
}
