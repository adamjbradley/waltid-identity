package id.walt.verify.sdk

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.okhttp.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.delay
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.Closeable

// =============================================================================
// Configuration
// =============================================================================

/**
 * Configuration for the VerifyClient.
 *
 * @property apiKey API key for authentication (required)
 * @property baseUrl Base URL of the Verify API (defaults to https://verify.example.com)
 */
data class VerifyConfig(
    val apiKey: String,
    val baseUrl: String = "https://verify.example.com"
) {
    init {
        require(apiKey.isNotBlank()) { "API key is required" }
    }
}

// =============================================================================
// Request Types
// =============================================================================

/**
 * Request to initiate identity verification.
 *
 * @property template Name of the verification template to use (required)
 * @property responseMode How to return verification results: "answers" or "raw_credentials"
 * @property redirectUri Optional redirect URI for same-device flows
 * @property metadata Optional metadata to attach to the session
 */
@Serializable
data class VerificationRequest(
    val template: String,
    @SerialName("response_mode")
    val responseMode: String? = "answers",
    @SerialName("redirect_uri")
    val redirectUri: String? = null,
    val metadata: Map<String, String>? = null
)

// =============================================================================
// Response Types
// =============================================================================

/**
 * Response from initiating a verification request.
 *
 * @property sessionId Unique session identifier (vs_xxxx format)
 * @property qrCodeUrl URL to retrieve the QR code image
 * @property qrCodeData Raw data encoded in the QR code (openid4vp:// URL)
 * @property deepLink Deep link URL for same-device wallet flows
 * @property expiresAt Epoch millis when session expires
 */
@Serializable
data class VerificationResponse(
    @SerialName("session_id")
    val sessionId: String,
    @SerialName("qr_code_url")
    val qrCodeUrl: String,
    @SerialName("qr_code_data")
    val qrCodeData: String,
    @SerialName("deep_link")
    val deepLink: String,
    @SerialName("expires_at")
    val expiresAt: Long
)

/**
 * Session status representing the current state of a verification session.
 *
 * @property sessionId Unique session identifier
 * @property status Current status: "pending", "verified", "failed", or "expired"
 * @property templateName Name of the template used for this session
 * @property result Verification result (present when status is "verified")
 * @property verifiedAt Epoch millis when verification completed
 * @property metadata Custom metadata associated with the session
 * @property expiresAt Epoch millis when the session expires
 */
@Serializable
data class SessionStatus(
    @SerialName("session_id")
    val sessionId: String,
    val status: String,
    @SerialName("template_name")
    val templateName: String,
    val result: SessionResult? = null,
    @SerialName("verified_at")
    val verifiedAt: Long? = null,
    val metadata: Map<String, String>? = null,
    @SerialName("expires_at")
    val expiresAt: Long
) {
    /** Check if the session is still pending */
    val isPending: Boolean get() = status == "pending"

    /** Check if the session completed successfully */
    val isVerified: Boolean get() = status == "verified"

    /** Check if the session failed */
    val isFailed: Boolean get() = status == "failed"

    /** Check if the session expired */
    val isExpired: Boolean get() = status == "expired"

    /** Check if the session has reached a terminal state */
    val isTerminal: Boolean get() = !isPending
}

/**
 * Verification result containing the verified data.
 *
 * @property answers Mapped answers when response_mode is "answers"
 * @property credentials Full credential data when response_mode is "raw_credentials"
 */
@Serializable
data class SessionResult(
    val answers: Map<String, String>? = null,
    val credentials: List<Credential>? = null
)

/**
 * A credential with its disclosed claims.
 *
 * @property format Credential format: dc+sd-jwt, mso_mdoc, jwt_vc, etc.
 * @property vct Verifiable Credential Type for SD-JWT credentials
 * @property doctype Document type for mdoc credentials
 * @property disclosedClaims Claims that were disclosed from this credential
 */
@Serializable
data class Credential(
    val format: String,
    val vct: String? = null,
    val doctype: String? = null,
    @SerialName("disclosed_claims")
    val disclosedClaims: Map<String, String>
)

// =============================================================================
// Error Types
// =============================================================================

/**
 * Exception thrown when the Verify API returns an error.
 *
 * @property statusCode HTTP status code if applicable
 * @property errorCode Error code from the API
 */
class VerifyException(
    message: String,
    val statusCode: Int? = null,
    val errorCode: String? = null
) : Exception(message)

/**
 * Exception thrown when polling times out.
 *
 * @property sessionId The session that timed out
 * @property timeoutMs The timeout duration in milliseconds
 */
class PollingTimeoutException(
    val sessionId: String,
    val timeoutMs: Long
) : Exception("Polling timed out after ${timeoutMs}ms for session $sessionId")

// =============================================================================
// Client Implementation
// =============================================================================

/**
 * Client for the walt.id Verify API.
 *
 * This client provides methods to initiate identity verification requests and
 * poll for their completion. It handles all HTTP communication and JSON
 * serialization/deserialization.
 *
 * Example usage:
 * ```kotlin
 * val client = VerifyClient(VerifyConfig(
 *     apiKey = "your-api-key",
 *     baseUrl = "https://verify.yourdomain.com"
 * ))
 *
 * // Start verification
 * val verification = client.verifyIdentity(VerificationRequest(
 *     template = "kyc-basic",
 *     responseMode = "answers"
 * ))
 *
 * // Display QR code to user...
 * println("Scan QR code: ${verification.qrCodeUrl}")
 *
 * // Poll for result
 * val result = client.pollSession(verification.sessionId)
 * if (result.isVerified) {
 *     println("Verified! ${result.result?.answers}")
 * }
 *
 * // Clean up
 * client.close()
 * ```
 *
 * @param config Configuration for the client
 */
class VerifyClient(private val config: VerifyConfig) : Closeable {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val client = HttpClient(OkHttp) {
        install(ContentNegotiation) {
            json(json)
        }
    }

    private val baseUrl = config.baseUrl.trimEnd('/')

    /**
     * Initiate an identity verification request.
     *
     * This creates a new verification session and returns QR code data and
     * deep link URLs for wallet interaction.
     *
     * @param request The verification request parameters
     * @return VerificationResponse with session details and QR code data
     * @throws VerifyException When the API returns an error
     *
     * Example:
     * ```kotlin
     * val verification = client.verifyIdentity(VerificationRequest(
     *     template = "kyc-basic",
     *     responseMode = "answers",
     *     metadata = mapOf("userId" to "12345")
     * ))
     * ```
     */
    suspend fun verifyIdentity(request: VerificationRequest): VerificationResponse {
        val response = client.post("$baseUrl/v1/verify/identity") {
            header("Authorization", "Bearer ${config.apiKey}")
            contentType(ContentType.Application.Json)
            setBody(request)
        }

        if (!response.status.isSuccess()) {
            val errorBody = runCatching { response.body<ErrorResponse>() }.getOrNull()
            throw VerifyException(
                message = errorBody?.error ?: "Verification request failed: ${response.status}",
                statusCode = response.status.value,
                errorCode = errorBody?.code
            )
        }

        return response.body()
    }

    /**
     * Get the current status of a verification session.
     *
     * @param sessionId The session ID to query
     * @return SessionStatus with current state and result if verified
     * @throws VerifyException When the API returns an error
     *
     * Example:
     * ```kotlin
     * val status = client.getSession("vs_abc123")
     * if (status.isVerified) {
     *     println("Name: ${status.result?.answers?.get("full_name")}")
     * }
     * ```
     */
    suspend fun getSession(sessionId: String): SessionStatus {
        val response = client.get("$baseUrl/v1/sessions/$sessionId") {
            header("Authorization", "Bearer ${config.apiKey}")
        }

        if (!response.status.isSuccess()) {
            val errorBody = runCatching { response.body<ErrorResponse>() }.getOrNull()
            throw VerifyException(
                message = errorBody?.error ?: "Failed to get session: ${response.status}",
                statusCode = response.status.value,
                errorCode = errorBody?.code
            )
        }

        return response.body()
    }

    /**
     * Poll a session until it reaches a terminal state (verified, failed, or expired).
     *
     * This method repeatedly queries the session status at the specified interval
     * until the session is no longer pending or the timeout is reached.
     *
     * @param sessionId The session ID to poll
     * @param intervalMs Polling interval in milliseconds (default: 2000)
     * @param timeoutMs Maximum time to poll in milliseconds (default: 300000 = 5 minutes)
     * @return SessionStatus in a terminal state
     * @throws PollingTimeoutException When polling exceeds the timeout
     * @throws VerifyException When the API returns an error
     *
     * Example:
     * ```kotlin
     * try {
     *     val status = client.pollSession("vs_abc123", intervalMs = 2000, timeoutMs = 60000)
     *     println("Final status: ${status.status}")
     * } catch (e: PollingTimeoutException) {
     *     println("User did not complete verification in time")
     * }
     * ```
     */
    suspend fun pollSession(
        sessionId: String,
        intervalMs: Long = 2000,
        timeoutMs: Long = 300000
    ): SessionStatus {
        val startTime = System.currentTimeMillis()

        while (System.currentTimeMillis() - startTime < timeoutMs) {
            val status = getSession(sessionId)

            if (status.isTerminal) {
                return status
            }

            delay(intervalMs)
        }

        throw PollingTimeoutException(sessionId, timeoutMs)
    }

    /**
     * Poll a session with a callback for each status update.
     *
     * This is useful for updating UI during the polling process.
     *
     * @param sessionId The session ID to poll
     * @param intervalMs Polling interval in milliseconds (default: 2000)
     * @param timeoutMs Maximum time to poll in milliseconds (default: 300000 = 5 minutes)
     * @param onStatusUpdate Callback invoked with each status update
     * @return SessionStatus in a terminal state
     * @throws PollingTimeoutException When polling exceeds the timeout
     * @throws VerifyException When the API returns an error
     *
     * Example:
     * ```kotlin
     * val status = client.pollSessionWithUpdates(
     *     sessionId = "vs_abc123",
     *     onStatusUpdate = { status ->
     *         updateUI("Status: ${status.status}")
     *     }
     * )
     * ```
     */
    suspend fun pollSessionWithUpdates(
        sessionId: String,
        intervalMs: Long = 2000,
        timeoutMs: Long = 300000,
        onStatusUpdate: suspend (SessionStatus) -> Unit
    ): SessionStatus {
        val startTime = System.currentTimeMillis()

        while (System.currentTimeMillis() - startTime < timeoutMs) {
            val status = getSession(sessionId)
            onStatusUpdate(status)

            if (status.isTerminal) {
                return status
            }

            delay(intervalMs)
        }

        throw PollingTimeoutException(sessionId, timeoutMs)
    }

    /**
     * Close the HTTP client and release resources.
     *
     * Call this when you're done using the client to properly clean up.
     */
    override fun close() {
        client.close()
    }
}

// =============================================================================
// Internal Types
// =============================================================================

/**
 * Error response from the API.
 */
@Serializable
internal data class ErrorResponse(
    val error: String,
    val code: String? = null
)
