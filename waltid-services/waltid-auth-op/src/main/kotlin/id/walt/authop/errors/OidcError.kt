package id.walt.authop.errors

import id.walt.authop.domain.AuthRequest
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.formUrlEncode
import io.ktor.http.parametersOf
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.header
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * OIDC protocol errors the OP can produce, categorised by the wire-level
 * behaviour they demand. Structured as a sealed hierarchy so each variant
 * locks in (code, httpStatus, redirectBehavior) as an invariant — callers
 * can't construct an [InvalidClient] that accidentally redirects, etc.
 *
 * Rows map to the error table in the design doc §Error handling. The two
 * axes that matter to the dispatcher are:
 *   - **redirectBehavior** — decides whether we render a page, redirect to
 *     the RP with `?error=…`, or return a JSON body.
 *   - **code** — the spec-defined token that goes into the response
 *     (`invalid_request`, `invalid_grant`, etc.).
 *
 * Descriptions, where present, are included verbatim in the response
 * (either the HTML body, the `error_description` query param, or the
 * JSON body). They are developer-facing diagnostics, not user copy.
 */
sealed class OidcError(
    val code: String,
    val description: String?,
    val httpStatus: HttpStatusCode,
    val redirectBehavior: RedirectBehavior,
) {
    enum class RedirectBehavior {
        /** 302 to the RP's `redirect_uri` with `?error=…[&error_description=…][&state=…]`. */
        REDIRECT_TO_RP,

        /** 400 rendered HTML page. Used when we cannot trust the redirect_uri. */
        PLAIN_ERROR_PAGE,

        /** 400 or 401 JSON body `{"error": "…"[, "error_description": "…"]}`. Token/userinfo endpoints. */
        JSON_BODY,
    }

    // -- Redirect-to-RP (302) --------------------------------------------
    data class InvalidRequest(val reason: String? = null) :
        OidcError("invalid_request", reason, HttpStatusCode.Found, RedirectBehavior.REDIRECT_TO_RP)

    object LoginRequired :
        OidcError("login_required", null, HttpStatusCode.Found, RedirectBehavior.REDIRECT_TO_RP)

    object UnsupportedResponseType :
        OidcError("unsupported_response_type", null, HttpStatusCode.Found, RedirectBehavior.REDIRECT_TO_RP)

    data class InvalidScope(val reason: String? = null) :
        OidcError("invalid_scope", reason, HttpStatusCode.Found, RedirectBehavior.REDIRECT_TO_RP)

    data class AccessDenied(val reason: String? = null) :
        OidcError("access_denied", reason, HttpStatusCode.Found, RedirectBehavior.REDIRECT_TO_RP)

    /**
     * OIDC Core §3.1.2.6 — the authorization server encountered an unexpected
     * condition that prevented it from fulfilling the request. Currently emitted
     * by the post-consent flow-update path when the n8n workflow fails, times
     * out, or its session expires before the browser returns to /consent/flow-done.
     * RP receives a clean `error=server_error` on its redirect_uri.
     */
    data class ServerError(val reason: String? = null) :
        OidcError("server_error", reason, HttpStatusCode.Found, RedirectBehavior.REDIRECT_TO_RP)

    // -- Plain error page (400) — redirect_uri not trusted ---------------
    data class UnknownClient(val clientId: String) :
        OidcError(
            code = "unauthorized_client",
            description = "client '$clientId' is not registered",
            httpStatus = HttpStatusCode.BadRequest,
            redirectBehavior = RedirectBehavior.PLAIN_ERROR_PAGE,
        )

    data class UnregisteredRedirectUri(val redirectUri: String) :
        OidcError(
            code = "invalid_request",
            description = "redirect_uri not registered",
            httpStatus = HttpStatusCode.BadRequest,
            redirectBehavior = RedirectBehavior.PLAIN_ERROR_PAGE,
        )

    // -- JSON body — token / userinfo endpoints --------------------------
    object InvalidClient :
        OidcError("invalid_client", null, HttpStatusCode.Unauthorized, RedirectBehavior.JSON_BODY)

    data class InvalidGrant(val reason: String? = null) :
        OidcError("invalid_grant", reason, HttpStatusCode.BadRequest, RedirectBehavior.JSON_BODY)

    object InvalidToken :
        OidcError("invalid_token", null, HttpStatusCode.Unauthorized, RedirectBehavior.JSON_BODY)

    /**
     * RFC 6749 §5.2 — the authorization server does not support the grant
     * type requested by the client at `/token`. 400 JSON. Separate variant
     * rather than reusing [InvalidGrant] because the spec code is distinct
     * and RPs may branch on it.
     */
    data class UnsupportedGrantType(val reason: String? = null) :
        OidcError("unsupported_grant_type", reason, HttpStatusCode.BadRequest, RedirectBehavior.JSON_BODY)

    /**
     * RFC 6749 §5.2 — the request is missing a required parameter, includes
     * an unsupported parameter value, or is otherwise malformed. 400 JSON
     * variant used by /token (the REDIRECT_TO_RP [InvalidRequest] variant
     * above handles the /authorize side of this same code).
     */
    data class InvalidRequestJson(val reason: String? = null) :
        OidcError("invalid_request", reason, HttpStatusCode.BadRequest, RedirectBehavior.JSON_BODY)
}

/**
 * Render [err] on the current call per its [OidcError.redirectBehavior].
 *
 * - `PLAIN_ERROR_PAGE` — 400 minimal HTML. Never touches [authReq] since by
 *   definition we got here because the redirect_uri is untrustworthy.
 * - `REDIRECT_TO_RP` — 302 to `authReq.redirectUri?error=…`, echoing
 *   `state` byte-exact (via [parametersOf] + [formUrlEncode], never string
 *   concatenation) so RPs can correlate. Requires a non-null [authReq];
 *   passing null is a developer error — we throw [IllegalArgumentException]
 *   rather than silently swallow.
 * - `JSON_BODY` — OAuth 2.0 §5.2 shape. Adds `WWW-Authenticate` for the
 *   401 variants per RFC 6749 §5.2 / RFC 6750 §3.
 */
suspend fun ApplicationCall.respondOidcError(err: OidcError, authReq: AuthRequest? = null) {
    when (err.redirectBehavior) {
        OidcError.RedirectBehavior.PLAIN_ERROR_PAGE -> {
            // Every value interpolated into this HTML is potentially attacker-controlled
            // (e.g. UnknownClient.clientId echoes the `?client_id=…` query param). Escape
            // both code and description so reflected input renders as text, never script.
            val body = buildString {
                append("<!doctype html><html><body><h1>Authentication error</h1>")
                append("<p><strong>").append(err.code.htmlEscape()).append("</strong></p>")
                err.description?.let { append("<p>").append(it.htmlEscape()).append("</p>") }
                append("</body></html>")
            }
            respondText(body, ContentType.Text.Html, err.httpStatus)
        }

        OidcError.RedirectBehavior.REDIRECT_TO_RP -> {
            requireNotNull(authReq) {
                "REDIRECT_TO_RP error '${err.code}' requires an AuthRequest for redirect_uri"
            }
            val pairs = buildList<Pair<String, List<String>>> {
                add("error" to listOf(err.code))
                err.description?.let { add("error_description" to listOf(it)) }
                authReq.state?.let { add("state" to listOf(it)) }
            }
            val query = parametersOf(*pairs.toTypedArray()).formUrlEncode()
            respondRedirect("${authReq.redirectUri}?$query")
        }

        OidcError.RedirectBehavior.JSON_BODY -> {
            when (err) {
                is OidcError.InvalidClient ->
                    response.header(HttpHeaders.WWWAuthenticate, "Basic")
                is OidcError.InvalidToken ->
                    response.header(HttpHeaders.WWWAuthenticate, """Bearer error="invalid_token"""")
                else -> Unit
            }
            val body = buildJsonObject {
                put("error", err.code)
                err.description?.let { put("error_description", it) }
            }
            // Serialise explicitly via respondText so the dispatcher works regardless
            // of whether the mounting route installs ContentNegotiation { json() }.
            respondText(body.toString(), ContentType.Application.Json, err.httpStatus)
        }
    }
}

/**
 * Minimal HTML-escape for the five characters that break out of text contexts.
 * Used by the PLAIN_ERROR_PAGE branch where attacker-controlled values land in
 * the response body. Keep this private to [respondOidcError] — callers should
 * not be minting raw HTML anywhere else.
 */
private fun String.htmlEscape(): String = buildString(length) {
    for (c in this@htmlEscape) when (c) {
        '&' -> append("&amp;")
        '<' -> append("&lt;")
        '>' -> append("&gt;")
        '"' -> append("&quot;")
        '\'' -> append("&#39;")
        else -> append(c)
    }
}
