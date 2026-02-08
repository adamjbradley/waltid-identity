package id.walt.verifyapi.routes

import id.walt.verifyapi.service.VerificationService
import id.walt.verifyapi.session.ResponseMode
import id.walt.verifyapi.session.SessionManager
import id.walt.verifyapi.widget.ClientTokenService
import id.walt.verifyapi.widget.TokenValidationResult
import id.walt.verifyapi.widget.ValidatedClientToken
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.http.*
import io.ktor.http.HttpHeaders
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

private val logger = KotlinLogging.logger {}

/**
 * Request to start a verification via the widget SDK.
 */
@Serializable
data class WidgetVerifyRequest(
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
 * Response containing verification session details for the widget.
 */
@Serializable
data class WidgetVerifyResponse(
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
 * Widget session status response.
 */
@Serializable
data class WidgetSessionStatusResponse(
    @SerialName("session_id")
    val sessionId: String,
    val status: String,
    @SerialName("template_name")
    val templateName: String,
    val result: SessionResultResponse? = null,
    @SerialName("verified_at")
    val verifiedAt: Long? = null,
    @SerialName("expires_at")
    val expiresAt: Long
)

/**
 * Error response for widget API.
 */
@Serializable
data class WidgetErrorResponse(
    val error: String,
    val code: String? = null
)

/**
 * Widget SDK routes under `/widget/v1/`.
 *
 * These endpoints are authenticated via Client Token (ct_) rather than API keys.
 * The SDK.js endpoint is public.
 *
 * Security model:
 * 1. Merchant backend generates a client token via POST /v1/widget/tokens (API key auth)
 * 2. Frontend widget uses the client token to authenticate these endpoints
 * 3. Client tokens are short-lived and scoped to specific templates/origins
 */
fun Route.widgetRoutes() {
    route("/widget/v1") {
        /**
         * GET /widget/v1/sdk.js
         *
         * Serve the widget SDK JavaScript file.
         * This endpoint is PUBLIC (no authentication required).
         *
         * The SDK is served from a static file with cache headers for performance.
         * In development, the file is loaded from resources; in production, it's cached.
         */
        get("/sdk.js") {
            // Load SDK from static resources
            val sdkJs = loadSdkJs()

            // Set cache headers (1 hour in dev, longer in production)
            val maxAge = System.getenv("SDK_CACHE_MAX_AGE")?.toIntOrNull() ?: 3600
            call.response.header(HttpHeaders.CacheControl, "public, max-age=$maxAge")
            call.response.header(HttpHeaders.ContentType, "application/javascript; charset=utf-8")

            call.respondText(sdkJs, ContentType.Text.JavaScript)
        }

        /**
         * GET /widget/v1/sdk.min.js
         *
         * Serve the minified widget SDK JavaScript file (~21KB).
         * This endpoint is PUBLIC (no authentication required).
         */
        get("/sdk.min.js") {
            val sdkMinJs = loadSdkMinJs()

            // Longer cache for minified version (immutable)
            val maxAge = System.getenv("SDK_CACHE_MAX_AGE")?.toIntOrNull() ?: 86400
            call.response.header(HttpHeaders.CacheControl, "public, max-age=$maxAge, immutable")
            call.response.header(HttpHeaders.ContentType, "application/javascript; charset=utf-8")

            call.respondText(sdkMinJs, ContentType.Text.JavaScript)
        }

        /**
         * GET /widget/v1/test
         *
         * Serve the SDK test page for development/testing.
         * This endpoint is PUBLIC (no authentication required).
         */
        get("/test") {
            val testHtml = loadTestHtml()
            call.respondText(testHtml, ContentType.Text.Html)
        }

        /**
         * POST /widget/v1/verify
         *
         * Start a verification session using a client token.
         * Requires Bearer token authentication with a valid client token (ct_*).
         */
        post("/verify") {
            // Extract and validate client token
            val validatedToken = validateClientToken(call) ?: return@post

            val request = try {
                call.receive<WidgetVerifyRequest>()
            } catch (e: Exception) {
                logger.debug { "Failed to parse request body: ${e.message}" }
                call.respond(
                    HttpStatusCode.BadRequest,
                    WidgetErrorResponse("Invalid request body", "INVALID_REQUEST")
                )
                return@post
            }

            // Validate template name
            if (request.template.isBlank()) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    WidgetErrorResponse("Template name is required", "MISSING_TEMPLATE")
                )
                return@post
            }

            // Check if token is allowed to use this template
            if (validatedToken.allowedTemplates.isNotEmpty() &&
                request.template !in validatedToken.allowedTemplates) {
                logger.warn { "Token ${validatedToken.tokenId} not allowed to use template: ${request.template}" }
                call.respond(
                    HttpStatusCode.Forbidden,
                    WidgetErrorResponse("Token not authorized for template: ${request.template}", "TEMPLATE_NOT_ALLOWED")
                )
                return@post
            }

            val responseMode = when (request.responseMode.lowercase()) {
                "raw_credentials" -> ResponseMode.RAW_CREDENTIALS
                "answers" -> ResponseMode.ANSWERS
                else -> {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        WidgetErrorResponse("Invalid responseMode. Must be 'answers' or 'raw_credentials'", "INVALID_RESPONSE_MODE")
                    )
                    return@post
                }
            }

            // Create the verification session
            val result = try {
                VerificationService.createIdentityVerification(
                    organizationId = validatedToken.organizationId,
                    templateName = request.template,
                    responseMode = responseMode,
                    redirectUri = request.redirectUri,
                    metadata = request.metadata
                )
            } catch (e: IllegalArgumentException) {
                logger.warn { "Template not found: ${request.template}" }
                call.respond(
                    HttpStatusCode.NotFound,
                    WidgetErrorResponse("Template not found: ${request.template}", "TEMPLATE_NOT_FOUND")
                )
                return@post
            } catch (e: Exception) {
                logger.error(e) { "Failed to create verification session" }
                call.respond(
                    HttpStatusCode.InternalServerError,
                    WidgetErrorResponse("Failed to create verification session", "INTERNAL_ERROR")
                )
                return@post
            }

            // Increment token usage after successful use
            ClientTokenService.incrementUsage(validatedToken.tokenId)

            logger.info { "Widget verification created: ${result.sessionId} using token ${validatedToken.tokenId}" }

            call.respond(
                HttpStatusCode.Created,
                WidgetVerifyResponse(
                    sessionId = result.sessionId,
                    qrCodeUrl = result.qrCodeUrl,
                    qrCodeData = result.qrCodeData,
                    deepLink = result.deepLink,
                    expiresAt = result.expiresAt
                )
            )
        }

        /**
         * GET /widget/v1/sessions/{id}
         *
         * Check the status of a verification session.
         * Requires Bearer token authentication with a valid client token (ct_*).
         */
        get("/sessions/{id}") {
            // Extract and validate client token
            val validatedToken = validateClientToken(call) ?: return@get

            val sessionId = call.parameters["id"]
            if (sessionId.isNullOrBlank()) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    WidgetErrorResponse("Session ID is required", "MISSING_SESSION_ID")
                )
                return@get
            }

            // Get the session
            val session = SessionManager.getSession(sessionId)

            if (session == null) {
                call.respond(
                    HttpStatusCode.NotFound,
                    WidgetErrorResponse("Session not found or expired", "SESSION_NOT_FOUND")
                )
                return@get
            }

            // Verify session belongs to the same organization as the token
            if (session.organizationId != validatedToken.organizationId.toString()) {
                logger.warn { "Token org ${validatedToken.organizationId} tried to access session ${sessionId} owned by ${session.organizationId}" }
                call.respond(
                    HttpStatusCode.NotFound,
                    WidgetErrorResponse("Session not found", "SESSION_NOT_FOUND")
                )
                return@get
            }

            // Poll verifier-api2 for latest status
            val status = try {
                VerificationService.getSessionStatus(sessionId)
            } catch (e: Exception) {
                logger.warn(e) { "Failed to get session status from verifier, using local data" }
                // Fall back to local session data
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
                return@get call.respond(
                    WidgetSessionStatusResponse(
                        sessionId = session.id,
                        status = session.status.name.lowercase(),
                        templateName = session.templateName,
                        result = resultResponse,
                        verifiedAt = session.result?.verifiedAt,
                        expiresAt = session.expiresAt
                    )
                )
            }

            // Convert service status to response
            val resultResponse = status.result?.let { result ->
                SessionResultResponse(
                    answers = result.answers?.mapValues { it.value.toString() },
                    credentials = result.credentials?.map { cred ->
                        CredentialResponse(
                            format = cred.format,
                            vct = cred.vct,
                            doctype = cred.doctype,
                            issuer = cred.issuer,
                            disclosedClaims = cred.disclosedClaims
                        )
                    }
                )
            }

            // Note: We don't increment usage for status checks - only for creating verifications

            call.respond(
                WidgetSessionStatusResponse(
                    sessionId = status.sessionId,
                    status = status.status,
                    templateName = status.templateName,
                    result = resultResponse,
                    verifiedAt = status.verifiedAt,
                    expiresAt = status.expiresAt
                )
            )
        }
    }
}

/**
 * Extract and validate a client token from the Authorization header.
 *
 * @param call The Ktor ApplicationCall
 * @return The validated token, or null if validation failed (response already sent)
 */
private suspend fun validateClientToken(call: io.ktor.server.application.ApplicationCall): ValidatedClientToken? {
    // Extract token from Authorization header
    val authHeader = call.request.header(HttpHeaders.Authorization)
    if (authHeader == null) {
        call.respond(
            HttpStatusCode.Unauthorized,
            WidgetErrorResponse("Missing Authorization header", "MISSING_AUTH")
        )
        return null
    }

    if (!authHeader.startsWith("Bearer ", ignoreCase = true)) {
        call.respond(
            HttpStatusCode.Unauthorized,
            WidgetErrorResponse("Invalid Authorization header format. Expected: Bearer <token>", "INVALID_AUTH_FORMAT")
        )
        return null
    }

    val token = authHeader.removePrefix("Bearer ").removePrefix("bearer ").trim()
    if (token.isBlank()) {
        call.respond(
            HttpStatusCode.Unauthorized,
            WidgetErrorResponse("Empty token", "EMPTY_TOKEN")
        )
        return null
    }

    // Must be a client token (ct_*)
    if (!token.startsWith("ct_")) {
        call.respond(
            HttpStatusCode.Unauthorized,
            WidgetErrorResponse("Invalid token type. Expected client token (ct_*)", "INVALID_TOKEN_TYPE")
        )
        return null
    }

    // Get origin from request for validation
    val origin = call.request.header(HttpHeaders.Origin)

    // Validate the token
    return when (val result = ClientTokenService.validateToken(token, origin)) {
        is TokenValidationResult.Valid -> result.token

        is TokenValidationResult.InvalidFormat -> {
            call.respond(
                HttpStatusCode.Unauthorized,
                WidgetErrorResponse("Invalid token format", "INVALID_TOKEN_FORMAT")
            )
            null
        }

        is TokenValidationResult.InvalidSignature -> {
            call.respond(
                HttpStatusCode.Unauthorized,
                WidgetErrorResponse("Invalid token signature", "INVALID_SIGNATURE")
            )
            null
        }

        is TokenValidationResult.NotFound -> {
            call.respond(
                HttpStatusCode.Unauthorized,
                WidgetErrorResponse("Token not found or revoked", "TOKEN_NOT_FOUND")
            )
            null
        }

        is TokenValidationResult.Expired -> {
            call.respond(
                HttpStatusCode.Unauthorized,
                WidgetErrorResponse("Token has expired", "TOKEN_EXPIRED")
            )
            null
        }

        is TokenValidationResult.UsageLimitExceeded -> {
            call.respond(
                HttpStatusCode.Forbidden,
                WidgetErrorResponse("Token usage limit exceeded", "USAGE_LIMIT_EXCEEDED")
            )
            null
        }

        is TokenValidationResult.OriginNotAllowed -> {
            logger.warn { "Origin not allowed: ${result.origin}" }
            call.respond(
                HttpStatusCode.Forbidden,
                WidgetErrorResponse("Origin not allowed: ${result.origin}", "ORIGIN_NOT_ALLOWED")
            )
            null
        }

        is TokenValidationResult.TemplateNotAllowed -> {
            // This shouldn't happen here since we check template separately in verify
            call.respond(
                HttpStatusCode.Forbidden,
                WidgetErrorResponse("Template not allowed: ${result.template}", "TEMPLATE_NOT_ALLOWED")
            )
            null
        }
    }
}

/** Cache for the loaded SDK JavaScript (unminified) */
private var cachedSdkJs: String? = null

/** Cache for the loaded SDK JavaScript (minified) */
private var cachedSdkMinJs: String? = null

/** Cache for the test HTML page */
private var cachedTestHtml: String? = null

/**
 * Load the widget SDK JavaScript from static resources.
 *
 * The SDK provides a full-featured API for merchants to embed verification widgets:
 * - WaltVerify.init({ clientToken, theme })
 * - WaltVerify.verifyAge({ minAge, onSuccess, onFailure })
 * - WaltVerify.verify({ template, ... })
 * - Built-in QR code generation and modal UI
 * - Automatic status polling with callbacks
 *
 * @return The SDK JavaScript code as a string (~60KB unminified)
 */
private fun loadSdkJs(): String {
    // Return cached version if available
    cachedSdkJs?.let { return it }

    // Load from static resources
    val sdk = object {}.javaClass.getResourceAsStream("/static/widget/sdk.js")
        ?.bufferedReader()
        ?.readText()
        ?: throw IllegalStateException("Widget SDK not found at /static/widget/sdk.js")

    // Cache for subsequent requests
    cachedSdkJs = sdk
    logger.info { "Widget SDK loaded from static resources (${sdk.length} bytes)" }

    return sdk
}

/**
 * Load the minified widget SDK JavaScript from static resources.
 *
 * @return The minified SDK JavaScript code as a string (~21KB minified)
 */
private fun loadSdkMinJs(): String {
    // Return cached version if available
    cachedSdkMinJs?.let { return it }

    // Load from static resources
    val sdk = object {}.javaClass.getResourceAsStream("/static/widget/sdk.min.js")
        ?.bufferedReader()
        ?.readText()
        ?: throw IllegalStateException("Minified Widget SDK not found at /static/widget/sdk.min.js")

    // Cache for subsequent requests
    cachedSdkMinJs = sdk
    logger.info { "Minified Widget SDK loaded from static resources (${sdk.length} bytes)" }

    return sdk
}

/**
 * Load the test HTML page from static resources.
 *
 * @return The test HTML page as a string
 */
private fun loadTestHtml(): String {
    // Return cached version if available
    cachedTestHtml?.let { return it }

    // Load from static resources
    val html = object {}.javaClass.getResourceAsStream("/static/widget/test.html")
        ?.bufferedReader()
        ?.readText()
        ?: throw IllegalStateException("Test HTML not found at /static/widget/test.html")

    // Cache for subsequent requests
    cachedTestHtml = html
    logger.info { "Test HTML loaded from static resources (${html.length} bytes)" }

    return html
}
