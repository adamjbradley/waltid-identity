package id.walt.verifyapi.routes

import id.walt.verifyapi.auth.ApiKeyPrincipal
import id.walt.verifyapi.widget.ClientTokenService
import id.walt.verifyapi.widget.CreateClientTokenRequest
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.http.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

private val logger = KotlinLogging.logger {}

/**
 * Request DTO for creating a widget client token.
 *
 * This is the external API format, which is then mapped to the internal
 * CreateClientTokenRequest used by ClientTokenService.
 */
@Serializable
data class WidgetTokenRequest(
    /** List of template names the token is allowed to use. Empty list = all templates. */
    val templates: List<String> = emptyList(),
    /** Token lifetime in seconds. Default: 900 (15 minutes), Max: 86400 (24 hours). */
    @SerialName("expires_in")
    val expiresIn: Int = 900,
    /** Maximum number of uses. Null = unlimited. */
    @SerialName("max_uses")
    val maxUses: Int? = null,
    /** List of allowed origins for CORS validation. Empty list = any origin. */
    @SerialName("allowed_origins")
    val allowedOrigins: List<String> = emptyList()
)

/**
 * Response DTO for a created widget client token.
 */
@Serializable
data class WidgetTokenResponse(
    /** The client token to use for widget SDK authentication. Format: ct_{base64}.{signature} */
    @SerialName("client_token")
    val clientToken: String,
    /** Unix timestamp (seconds) when the token expires. */
    @SerialName("expires_at")
    val expiresAt: Long
)

/**
 * Configure widget token routes under /v1/widget/tokens.
 *
 * These endpoints allow merchants to create client tokens for their frontend
 * widget SDK. Client tokens are short-lived tokens that can be safely exposed
 * in the browser without revealing the API key.
 *
 * Security model:
 * 1. Merchant backend calls POST /v1/widget/tokens with API key
 * 2. API returns a client token (ct_...) valid for ~15 minutes
 * 3. Merchant passes token to frontend widget
 * 4. Widget uses token to authenticate verification requests
 */
fun Route.widgetTokenRoutes() {
    authenticate("api-key") {
        route("/v1/widget/tokens") {
            /**
             * POST /v1/widget/tokens
             *
             * Generate a new client token for widget SDK authentication.
             *
             * The client token can be safely passed to the browser and used
             * by the widget SDK to initiate verification sessions without
             * exposing the API key.
             *
             * Request body:
             * - templates: List of template names this token can use (empty = all)
             * - expires_in: Token lifetime in seconds (default: 900, max: 86400)
             * - max_uses: Maximum number of uses (null = unlimited)
             * - allowed_origins: List of allowed origins for CORS (empty = any)
             *
             * Response:
             * - client_token: The token string (ct_xxx.yyy format)
             * - expires_at: Unix timestamp when the token expires
             */
            post {
                val principal = call.principal<ApiKeyPrincipal>()!!
                val request = call.receive<WidgetTokenRequest>()

                logger.debug { "Creating widget token for organization: ${principal.organizationId}" }

                // Validate expires_in
                if (request.expiresIn < 60) {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        mapOf("error" to "expires_in must be at least 60 seconds")
                    )
                    return@post
                }

                if (request.expiresIn > 86400) {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        mapOf("error" to "expires_in cannot exceed 86400 seconds (24 hours)")
                    )
                    return@post
                }

                // Validate max_uses
                if (request.maxUses != null && request.maxUses < 1) {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        mapOf("error" to "max_uses must be at least 1 if specified")
                    )
                    return@post
                }

                // Validate allowed_origins format (basic check)
                for (origin in request.allowedOrigins) {
                    if (origin != "*" && !origin.startsWith("http://") && !origin.startsWith("https://") && !origin.startsWith("*.")) {
                        call.respond(
                            HttpStatusCode.BadRequest,
                            mapOf("error" to "allowed_origins must be valid origin URLs (http://, https://) or wildcards (*, *.domain.com)")
                        )
                        return@post
                    }
                }

                // Map to internal request format
                val internalRequest = CreateClientTokenRequest(
                    allowedTemplates = request.templates,
                    allowedOrigins = request.allowedOrigins,
                    expiresInSeconds = request.expiresIn,
                    maxUses = request.maxUses
                )

                // Generate the token
                val result = ClientTokenService.generateToken(
                    organizationId = principal.organizationId,
                    request = internalRequest
                )

                logger.info { "Created widget token ${result.tokenPrefix} for organization: ${principal.organizationId}" }

                // Map to external response format
                val response = WidgetTokenResponse(
                    clientToken = result.token,
                    expiresAt = result.expiresAt
                )

                call.respond(HttpStatusCode.Created, response)
            }
        }
    }
}
