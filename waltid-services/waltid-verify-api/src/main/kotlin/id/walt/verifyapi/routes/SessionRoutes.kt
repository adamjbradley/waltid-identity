package id.walt.verifyapi.routes

import id.walt.verifyapi.auth.ApiKeyPrincipal
import id.walt.verifyapi.session.SessionManager
import io.ktor.http.*
import io.ktor.server.auth.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Response model for session status endpoint.
 * Returns the current state of a verification session.
 */
@Serializable
data class SessionStatusResponse(
    @SerialName("session_id")
    val sessionId: String,
    val status: String,
    @SerialName("template_name")
    val templateName: String,
    val result: SessionResultResponse? = null,
    @SerialName("verified_at")
    val verifiedAt: Long? = null,
    val metadata: Map<String, String>? = null,
    @SerialName("expires_at")
    val expiresAt: Long
)

/**
 * Response model for verification result data.
 */
@Serializable
data class SessionResultResponse(
    val answers: Map<String, String>? = null,
    val credentials: List<CredentialResponse>? = null
)

/**
 * Response model for individual credential data.
 */
@Serializable
data class CredentialResponse(
    val format: String,
    val vct: String? = null,
    val doctype: String? = null,
    @SerialName("disclosed_claims")
    val disclosedClaims: Map<String, String>
)

/**
 * Error response model.
 */
@Serializable
data class ErrorResponse(
    val error: String
)

/**
 * Session management routes for the Verify API.
 *
 * GET /v1/sessions/{session_id} - Get session status and result
 */
fun Route.sessionRoutes() {
    authenticate("api-key") {
        route("/v1/sessions") {
            get("/{session_id}") {
                val principal = call.principal<ApiKeyPrincipal>()!!
                val sessionId = call.parameters["session_id"]
                    ?: return@get call.respond(
                        HttpStatusCode.BadRequest,
                        ErrorResponse("Missing session_id")
                    )

                val session = SessionManager.getSession(sessionId)

                if (session == null) {
                    call.respond(
                        HttpStatusCode.NotFound,
                        ErrorResponse("Session not found or expired")
                    )
                    return@get
                }

                // Verify session belongs to this organization
                if (session.organizationId != principal.organizationId.toString()) {
                    call.respond(
                        HttpStatusCode.NotFound,
                        ErrorResponse("Session not found")
                    )
                    return@get
                }

                val resultResponse = session.result?.let { result ->
                    SessionResultResponse(
                        answers = result.answers,
                        credentials = result.credentials?.map { cred ->
                            CredentialResponse(
                                format = cred.format,
                                vct = cred.vct,
                                doctype = cred.doctype,
                                disclosedClaims = cred.disclosedClaims
                            )
                        }
                    )
                }

                call.respond(
                    SessionStatusResponse(
                        sessionId = session.id,
                        status = session.status.name.lowercase(),
                        templateName = session.templateName,
                        result = resultResponse,
                        verifiedAt = session.result?.verifiedAt,
                        metadata = session.metadata,
                        expiresAt = session.expiresAt
                    )
                )
            }
        }
    }
}
