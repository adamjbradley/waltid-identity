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
     * @return Session response with authorization URLs
     */
    suspend fun createSession(dcqlQuery: JsonObject): VerificationSessionResponse {
        logger.info { "Creating verifier-api2 session with DCQL query" }
        logger.debug { "DCQL Query: $dcqlQuery" }

        val requestBody = buildJsonObject {
            put("flow_type", "cross_device")
            putJsonObject("core_flow") {
                put("signed_request", true)
                put("clientId", clientId)
                putJsonObject("key") {
                    put("type", "jwk")
                    put("jwk", signingKey)
                }
                putJsonArray("x5c") {
                    x5c.forEach { add(it) }
                }
                put("dcql_query", dcqlQuery)
            }
        }

        logger.debug { "Request body: $requestBody" }

        val response = httpClient.post("$verifierApi2Url/verification-session/create") {
            contentType(ContentType.Application.Json)
            setBody(requestBody)
        }

        if (!response.status.isSuccess()) {
            val errorBody = response.bodyAsText()
            logger.error { "Failed to create verifier-api2 session: ${response.status} - $errorBody" }
            throw RuntimeException("Failed to create verification session: ${response.status}")
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

    // Default values from environment - these match verifier2.theaustraliahack.com configuration
    private const val DEFAULT_CLIENT_ID = "x509_san_dns:verifier2.theaustraliahack.com"

    private const val DEFAULT_SIGNING_KEY = """{"kty":"EC","crv":"P-256","x":"1Z2eGpdQVfWkAQQmNv8oT-lMwbhsFxWTZmhAYFHR5wY","y":"tvX699C21qGEMq7zqjpEhqy2kPT8KInnbxlLZzeSXdo","d":"j6-GyxLnrDSQGCljc678kmrihQFa0GR92JZXHDEQX38"}"""

    private const val DEFAULT_X5C = """["MIIBnzCCAUagAwIBAgIUQSg5NhDlxwDFyAM7YJe++0QGyKIwCgYIKoZIzj0EAwIwKTEnMCUGA1UEAwwedmVyaWZpZXIyLnRoZWF1c3RyYWxpYWhhY2suY29tMB4XDTI2MDIwMzAzNTIwM1oXDTI3MDIwMzAzNTIwM1owKTEnMCUGA1UEAwwedmVyaWZpZXIyLnRoZWF1c3RyYWxpYWhhY2suY29tMFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAE1Z2eGpdQVfWkAQQmNv8oT+lMwbhsFxWTZmhAYFHR5wa29fr30LbWoYQyrvOqOkSGrLaQ9PwoiedvGUtnN5Jd2qNMMEowKQYDVR0RBCIwIIIedmVyaWZpZXIyLnRoZWF1c3RyYWxpYWhhY2suY29tMB0GA1UdDgQWBBRt0uKz8aKVlUxKF9j6vhAsGl3nHDAKBggqhkjOPQQDAgNHADBEAiAQ+AlF3Q4dput8QTizDyKo99R/sv3CC7BzqEjOxxsnzQIgF+rnBf0HghobWkjSVNwP8j/ekasfjp+1HDJclcNaUvs="]"""
}
