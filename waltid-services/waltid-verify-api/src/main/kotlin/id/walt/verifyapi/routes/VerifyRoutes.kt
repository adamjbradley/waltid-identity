package id.walt.verifyapi.routes

import id.walt.verifyapi.auth.ApiKeyPrincipal
import id.walt.verifyapi.service.VerificationService
import id.walt.verifyapi.session.ResponseMode
import io.ktor.http.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Request to initiate an identity verification.
 */
@Serializable
data class IdentityVerificationRequest(
    /** Name of the verification template to use */
    val template: String,
    /** How to return verification results: "answers" or "raw_credentials" */
    @SerialName("response_mode")
    val responseMode: String = "answers",
    /** Optional redirect URI for same-device flows */
    @SerialName("redirect_uri")
    val redirectUri: String? = null,
    /** Optional metadata to attach to the session */
    val metadata: Map<String, String>? = null
)

/**
 * Response containing verification session details and QR code data.
 */
@Serializable
data class VerificationResponse(
    /** Unique session ID (vs_xxxx format) */
    @SerialName("session_id")
    val sessionId: String,
    /** URL to retrieve the QR code image */
    @SerialName("qr_code_url")
    val qrCodeUrl: String,
    /** Raw data encoded in the QR code (openid4vp:// URL) */
    @SerialName("qr_code_data")
    val qrCodeData: String,
    /** Deep link URL for same-device wallet flows */
    @SerialName("deep_link")
    val deepLink: String,
    /** Epoch millis when session expires */
    @SerialName("expires_at")
    val expiresAt: Long
)

/**
 * Configure verification routes under /v1/verify.
 *
 * All routes require API key authentication.
 */
fun Route.verifyRoutes() {
    authenticate("api-key") {
        route("/v1/verify") {
            /**
             * POST /v1/verify/identity
             *
             * Initiate an identity verification session.
             * Returns QR code data and deep link URLs for wallet interaction.
             */
            post("/identity") {
                val principal = call.principal<ApiKeyPrincipal>()!!
                val request = call.receive<IdentityVerificationRequest>()

                // Validate template name
                if (request.template.isBlank()) {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        mapOf("error" to "Template name is required")
                    )
                    return@post
                }

                val responseMode = when (request.responseMode.lowercase()) {
                    "raw_credentials" -> ResponseMode.RAW_CREDENTIALS
                    "answers" -> ResponseMode.ANSWERS
                    else -> {
                        call.respond(
                            HttpStatusCode.BadRequest,
                            mapOf("error" to "Invalid responseMode. Must be 'answers' or 'raw_credentials'")
                        )
                        return@post
                    }
                }

                val result = VerificationService.createIdentityVerification(
                    organizationId = principal.organizationId,
                    templateName = request.template,
                    responseMode = responseMode,
                    redirectUri = request.redirectUri,
                    metadata = request.metadata
                )

                call.respond(HttpStatusCode.Created, result)
            }
        }
    }
}
