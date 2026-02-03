package id.walt.eudi

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

/**
 * Test client mimicking EUDI wallet verification (VP presentation) behavior.
 * Handles OID4VP 1.0 authorization request/response flow against waltid-verifier-api2.
 *
 * This client provides methods to:
 * - Create verification sessions
 * - Fetch authorization requests (as a wallet would)
 * - Submit VP token responses
 * - Check session status
 */
class EudiVerificationClient(
    private val verifierApiUrl: String = "http://localhost:7004"
) {
    private val httpClient = HttpClient {
        install(ContentNegotiation) {
            json(Json {
                ignoreUnknownKeys = true
                prettyPrint = true
            })
        }
    }

    /**
     * Create a verification session and get the authorization request URI.
     *
     * @param sessionSetup The session setup as a JsonObject
     * @return Session creation response with session ID and authorization request URL
     */
    suspend fun createVerificationSession(
        sessionSetup: JsonObject
    ): VerificationSessionResponse {
        val response = httpClient.post("$verifierApiUrl/verification-session/create") {
            contentType(ContentType.Application.Json)
            setBody(sessionSetup)
        }
        check(response.status.isSuccess()) {
            "Failed to create verification session: ${response.status} - ${response.bodyAsText()}"
        }
        return response.body()
    }

    /**
     * Fetch authorization request from verifier (wallet-side operation).
     * This simulates a wallet fetching the full authorization request from the request_uri.
     *
     * @param sessionId The verification session ID
     * @return The authorization request as JsonObject
     */
    suspend fun getAuthorizationRequest(sessionId: String): JsonObject {
        val response = httpClient.get("$verifierApiUrl/verification-session/$sessionId/request")
        check(response.status.isSuccess()) {
            "Failed to get authorization request: ${response.status} - ${response.bodyAsText()}"
        }
        return response.body()
    }

    /**
     * Submit VP token response to verifier (wallet-side operation).
     * This simulates a wallet responding to the authorization request with a VP token.
     *
     * @param sessionId The verification session ID
     * @param vpToken The VP token (can be JWT, SD-JWT, or CBOR-encoded mDoc)
     * @param presentationSubmission Optional presentation submission descriptor
     * @return Response from the verifier
     */
    suspend fun submitVpResponse(
        sessionId: String,
        vpToken: String,
        presentationSubmission: JsonObject? = null
    ): JsonObject {
        val response = httpClient.submitForm(
            url = "$verifierApiUrl/verification-session/$sessionId/response",
            formParameters = parameters {
                append("vp_token", vpToken)
                if (presentationSubmission != null) {
                    append("presentation_submission", presentationSubmission.toString())
                }
            }
        )
        return response.body()
    }

    /**
     * Submit VP token response with state parameter.
     *
     * @param sessionId The verification session ID
     * @param vpToken The VP token
     * @param state The state value from the authorization request
     * @param presentationSubmission Optional presentation submission descriptor
     * @return Response from the verifier
     */
    suspend fun submitVpResponseWithState(
        sessionId: String,
        vpToken: String,
        state: String,
        presentationSubmission: JsonObject? = null
    ): JsonObject {
        val response = httpClient.submitForm(
            url = "$verifierApiUrl/verification-session/$sessionId/response",
            formParameters = parameters {
                append("vp_token", vpToken)
                append("state", state)
                if (presentationSubmission != null) {
                    append("presentation_submission", presentationSubmission.toString())
                }
            }
        )
        return response.body()
    }

    /**
     * Get session info/status.
     *
     * @param sessionId The verification session ID
     * @return The session data as JsonObject
     */
    suspend fun getSessionInfo(sessionId: String): JsonObject {
        val response = httpClient.get("$verifierApiUrl/verification-session/$sessionId/info")
        check(response.status.isSuccess()) {
            "Failed to get session info: ${response.status} - ${response.bodyAsText()}"
        }
        return response.body()
    }

    /**
     * Check if the session verification was successful.
     *
     * @param sessionId The verification session ID
     * @return True if verification was successful
     */
    suspend fun isVerificationSuccessful(sessionId: String): Boolean {
        val session = getSessionInfo(sessionId)
        val status = session["status"]?.jsonPrimitive?.contentOrNull
        return status == "SUCCESSFUL"
    }

    /**
     * Get the session status string.
     *
     * @param sessionId The verification session ID
     * @return The status string (e.g., "ACTIVE", "SUCCESSFUL", "FAILED")
     */
    suspend fun getSessionStatus(sessionId: String): String? {
        val session = getSessionInfo(sessionId)
        return session["status"]?.jsonPrimitive?.contentOrNull
    }

    /**
     * Wait for session to reach a final state (successful, failed, or expired).
     *
     * @param sessionId The verification session ID
     * @param maxWaitMs Maximum time to wait in milliseconds
     * @param pollIntervalMs Polling interval in milliseconds
     * @return The final session state as JsonObject
     */
    suspend fun waitForSessionCompletion(
        sessionId: String,
        maxWaitMs: Long = 30000,
        pollIntervalMs: Long = 500
    ): JsonObject {
        val startTime = System.currentTimeMillis()
        val finalStatuses = setOf("SUCCESSFUL", "FAILED", "UNSUCCESSFUL", "EXPIRED")

        while (System.currentTimeMillis() - startTime < maxWaitMs) {
            val session = getSessionInfo(sessionId)
            val status = session["status"]?.jsonPrimitive?.contentOrNull
            if (status != null && status in finalStatuses) {
                return session
            }
            kotlinx.coroutines.delay(pollIntervalMs)
        }
        return getSessionInfo(sessionId)
    }

    /**
     * Close the HTTP client and release resources.
     */
    fun close() {
        httpClient.close()
    }
}

/**
 * Response from creating a verification session.
 */
@Serializable
data class VerificationSessionResponse(
    val sessionId: String,
    val bootstrapAuthorizationRequestUrl: String? = null,
    val fullAuthorizationRequestUrl: String? = null,
    val creationTarget: String? = null
)
