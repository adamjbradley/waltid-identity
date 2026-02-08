package id.walt.verifyapi.routes

import id.walt.verifyapi.service.VerificationService
import id.walt.verifyapi.session.ResponseMode
import id.walt.verifyapi.session.SessionManager
import id.walt.verifyapi.widget.ClientTokenService
import id.walt.verifyapi.widget.TokenValidationResult
import id.walt.verifyapi.widget.ValidatedClientToken
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.http.*
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
         */
        get("/sdk.js") {
            val sdkJs = generateSdkJs()
            call.respondText(sdkJs, ContentType.Text.JavaScript)
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

/**
 * Generate the widget SDK JavaScript code.
 *
 * This is a minimal placeholder that will be expanded in Phase 10.5.
 * The SDK provides a simple API for merchants to embed verification widgets.
 */
private fun generateSdkJs(): String {
    val publicBaseUrl = System.getenv("PUBLIC_BASE_URL") ?: "http://localhost:7010"

    // Using buildString to avoid Kotlin parser issues with JS comments
    return buildString {
        appendLine("// Walt.id Verify Widget SDK")
        appendLine("// Version: 1.0.0-SNAPSHOT")
        appendLine("//")
        appendLine("// Usage:")
        appendLine("//   const verify = new WaltIdVerify({ clientToken: 'ct_xxx.yyy' });")
        appendLine("//   const session = await verify.createSession({ template: 'age_check' });")
        appendLine("//   verify.showQRCode(session.qr_code_data, '#container');")
        appendLine("//   const result = await verify.waitForResult(session.session_id);")
        appendLine("")
        appendLine("(function(global) {")
        appendLine("    'use strict';")
        appendLine("")
        appendLine("    const API_BASE = '$publicBaseUrl';")
        appendLine("")
        appendLine("    // Walt.id Verify Widget SDK")
        appendLine("    // @param {Object} options - Configuration options")
        appendLine("    // @param {string} options.clientToken - Client token (ct_xxx.yyy) obtained from your backend")
        appendLine("    function WaltIdVerify(options) {")
        appendLine("        if (!options || !options.clientToken) {")
        appendLine("            throw new Error('WaltIdVerify: clientToken is required');")
        appendLine("        }")
        appendLine("        if (!options.clientToken.startsWith('ct_')) {")
        appendLine("            throw new Error('WaltIdVerify: clientToken must start with \"ct_\"');")
        appendLine("        }")
        appendLine("        this.clientToken = options.clientToken;")
        appendLine("        this.pollInterval = options.pollInterval || 2000;")
        appendLine("    }")
        appendLine("")
        appendLine("    // Create a new verification session")
        appendLine("    // @param {Object} params - Session parameters")
        appendLine("    // @param {string} params.template - Template name to use")
        appendLine("    // @returns {Promise<Object>} Session details including QR code data")
        appendLine("    WaltIdVerify.prototype.createSession = async function(params) {")
        appendLine("        if (!params || !params.template) {")
        appendLine("            throw new Error('WaltIdVerify.createSession: template is required');")
        appendLine("        }")
        appendLine("")
        appendLine("        const response = await fetch(API_BASE + '/widget/v1/verify', {")
        appendLine("            method: 'POST',")
        appendLine("            headers: {")
        appendLine("                'Content-Type': 'application/json',")
        appendLine("                'Authorization': 'Bearer ' + this.clientToken")
        appendLine("            },")
        appendLine("            body: JSON.stringify({")
        appendLine("                template: params.template,")
        appendLine("                response_mode: params.responseMode || 'answers',")
        appendLine("                redirect_uri: params.redirectUri,")
        appendLine("                metadata: params.metadata")
        appendLine("            })")
        appendLine("        });")
        appendLine("")
        appendLine("        if (!response.ok) {")
        appendLine("            const error = await response.json().catch(() => ({ error: 'Unknown error' }));")
        appendLine("            throw new Error('Failed to create session: ' + (error.error || response.statusText));")
        appendLine("        }")
        appendLine("")
        appendLine("        return response.json();")
        appendLine("    };")
        appendLine("")
        appendLine("    // Get the status of a verification session")
        appendLine("    // @param {string} sessionId - The session ID to check")
        appendLine("    // @returns {Promise<Object>} Session status and result if available")
        appendLine("    WaltIdVerify.prototype.getSessionStatus = async function(sessionId) {")
        appendLine("        if (!sessionId) {")
        appendLine("            throw new Error('WaltIdVerify.getSessionStatus: sessionId is required');")
        appendLine("        }")
        appendLine("")
        appendLine("        const response = await fetch(API_BASE + '/widget/v1/sessions/' + encodeURIComponent(sessionId), {")
        appendLine("            method: 'GET',")
        appendLine("            headers: {")
        appendLine("                'Authorization': 'Bearer ' + this.clientToken")
        appendLine("            }")
        appendLine("        });")
        appendLine("")
        appendLine("        if (!response.ok) {")
        appendLine("            const error = await response.json().catch(() => ({ error: 'Unknown error' }));")
        appendLine("            throw new Error('Failed to get session status: ' + (error.error || response.statusText));")
        appendLine("        }")
        appendLine("")
        appendLine("        return response.json();")
        appendLine("    };")
        appendLine("")
        appendLine("    // Wait for verification result by polling")
        appendLine("    // @param {string} sessionId - The session ID to wait for")
        appendLine("    // @param {Object} [options] - Polling options")
        appendLine("    // @returns {Promise<Object>} Final session result")
        appendLine("    WaltIdVerify.prototype.waitForResult = async function(sessionId, options) {")
        appendLine("        options = options || {};")
        appendLine("        const timeout = options.timeout || 300000;")
        appendLine("        const startTime = Date.now();")
        appendLine("        let lastStatus = null;")
        appendLine("")
        appendLine("        while (Date.now() - startTime < timeout) {")
        appendLine("            const status = await this.getSessionStatus(sessionId);")
        appendLine("")
        appendLine("            if (status.status !== lastStatus && options.onStatusChange) {")
        appendLine("                options.onStatusChange(status);")
        appendLine("                lastStatus = status.status;")
        appendLine("            }")
        appendLine("")
        appendLine("            if (status.status === 'verified' || status.status === 'failed' || status.status === 'expired') {")
        appendLine("                return status;")
        appendLine("            }")
        appendLine("")
        appendLine("            await new Promise(resolve => setTimeout(resolve, this.pollInterval));")
        appendLine("        }")
        appendLine("")
        appendLine("        throw new Error('Verification timeout');")
        appendLine("    };")
        appendLine("")
        appendLine("    // Render a QR code into a container element")
        appendLine("    // NOTE: This is a placeholder. Full implementation in Phase 10.5.")
        appendLine("    WaltIdVerify.prototype.showQRCode = function(data, container) {")
        appendLine("        const el = typeof container === 'string' ? document.querySelector(container) : container;")
        appendLine("        if (!el) {")
        appendLine("            throw new Error('WaltIdVerify.showQRCode: container not found');")
        appendLine("        }")
        appendLine("        // Placeholder: In Phase 10.5, this will use a QR code library")
        appendLine("        el.innerHTML = '<div style=\"padding: 20px; background: #f0f0f0; text-align: center;\">' +")
        appendLine("            '<p style=\"font-size: 12px; word-break: break-all;\">QR Data: ' + data + '</p>' +")
        appendLine("            '<p style=\"color: #666;\">Full QR rendering coming in Phase 10.5</p>' +")
        appendLine("            '</div>';")
        appendLine("    };")
        appendLine("")
        appendLine("    // Show a modal verification dialog")
        appendLine("    // NOTE: This is a placeholder. Full implementation in Phase 10.5/10.9.")
        appendLine("    WaltIdVerify.prototype.showModal = function(params) {")
        appendLine("        console.warn('WaltIdVerify.showModal: Not yet implemented. Coming in Phase 10.5/10.9.');")
        appendLine("    };")
        appendLine("")
        appendLine("    // Export for different module systems")
        appendLine("    if (typeof module !== 'undefined' && module.exports) {")
        appendLine("        module.exports = WaltIdVerify;")
        appendLine("    } else if (typeof define === 'function' && define.amd) {")
        appendLine("        define([], function() { return WaltIdVerify; });")
        appendLine("    } else {")
        appendLine("        global.WaltIdVerify = WaltIdVerify;")
        appendLine("    }")
        appendLine("")
        appendLine("})(typeof window !== 'undefined' ? window : this);")
    }
}
