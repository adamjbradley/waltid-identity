package id.walt.authop.endpoints

import id.walt.authop.config.AuthOpServiceConfig
import id.walt.authop.config.ClientConfig
import id.walt.authop.config.ClientRegistry
import id.walt.authop.config.RealmRegistry
import id.walt.authop.domain.AuthRequest
import id.walt.authop.errors.OidcError
import id.walt.authop.errors.respondOidcError
import id.walt.authop.store.AuthRequestStore
import io.ktor.http.Cookie
import io.ktor.http.CookieEncoding
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import java.security.SecureRandom
import java.util.Base64

/**
 * OIDC `/authorize` entry point.
 *
 * Validation order mirrors the design doc §Error handling + Task 8 spec: we
 * refuse to redirect to a `redirect_uri` we haven't validated, so the first
 * four checks render a plain 400 page. After the `redirect_uri` is proven
 * trusted, subsequent validation failures become 302 redirects to the RP
 * with an `?error=…` per OIDC Core §3.1.2.6.
 *
 * On success we mint an `authRequestId` (which doubles as the `sid` cookie
 * value — see Task 8 spec §cookie binding), persist an [AuthRequest] to
 * [authRequestStore], set the pre-login cookie, and 302 to `/login`.
 *
 * The `/login` route itself lands in Task 9; for now we just redirect the
 * browser at the string literal `/login`.
 */
fun Route.authorizeRoutes(
    clientRegistry: ClientRegistry,
    @Suppress("UNUSED_PARAMETER") realmRegistry: RealmRegistry,
    authRequestStore: AuthRequestStore,
    config: AuthOpServiceConfig,
) {
    get("/authorize") {
        val params = call.request.queryParameters

        // --- 1. Required params present (before any lookup) ---------------
        // Missing any of these three means we can't even consider a redirect —
        // we don't know the RP (no client_id), or we can't trust the target
        // (no redirect_uri), or the response shape is undefined. Render a
        // plain page. This is NOT an OidcError — OidcError requires knowing
        // what kind of error it is; this is "request too broken to classify".
        val clientId = params["client_id"]
        val redirectUri = params["redirect_uri"]
        val responseType = params["response_type"]
        if (clientId.isNullOrBlank() || redirectUri.isNullOrBlank() || responseType.isNullOrBlank()) {
            call.respondPlainBadRequest(
                code = "invalid_request",
                description = "client_id, redirect_uri and response_type are required",
            )
            return@get
        }

        // --- 2. Unknown client_id -----------------------------------------
        val client: ClientConfig = clientRegistry[clientId]
            ?: run {
                call.respondOidcError(OidcError.UnknownClient(clientId))
                return@get
            }

        // --- 3. redirect_uri not registered (exact match) -----------------
        // RFC 6749 §4.1.2.1: if we don't recognise the redirect_uri, we MUST
        // NOT redirect to it. No wildcards, no scheme substitution.
        if (redirectUri !in client.redirectUris) {
            call.respondOidcError(OidcError.UnregisteredRedirectUri(redirectUri))
            return@get
        }

        // --- 4+ From here on we have a trusted redirect_uri ---------------
        // Any subsequent error is a REDIRECT_TO_RP OidcError. Build a
        // "proto-AuthRequest" — enough to drive the dispatcher, with a nullable
        // state field we haven't yet validated for presence (OIDC makes it
        // optional). This proto value is NEVER written to the store; we only
        // use it to route errors. On success we construct the real AuthRequest.
        val state = params["state"]
        val scopeParam = params["scope"].orEmpty()
        val scope = scopeParam.split(' ').filter { it.isNotBlank() }
        val nonce = params["nonce"]
        val prompt = params["prompt"]
        val codeChallenge = params["code_challenge"]
        val codeChallengeMethod = params["code_challenge_method"]
        val responseMode = params["response_mode"]

        // Build a reusable stub AuthRequest purely for the dispatcher's
        // redirect_uri / state needs. The other fields are placeholders.
        val protoAuthReq = AuthRequest(
            authRequestId = "",
            clientId = clientId,
            redirectUri = redirectUri,
            scope = scope,
            state = state,
            nonce = nonce,
            codeChallenge = codeChallenge.orEmpty(),
            codeChallengeMethod = codeChallengeMethod ?: "S256",
            prompt = prompt,
            chosenRealmId = null,
            subject = null,
            claims = emptyMap(),
        )

        // --- 5. response_type must be "code" ------------------------------
        if (responseType != "code") {
            call.respondOidcError(OidcError.UnsupportedResponseType, protoAuthReq)
            return@get
        }

        // --- 6. response_mode (when present) must be "query" --------------
        // Task 8 spec: pick one error code and be consistent. We go with
        // unsupported_response_type — the response_mode is coupled to the
        // response_type surface; RFC 9207 and OAuth 2.0 Multiple Response
        // Types §3 treat unsupported modes under unsupported_response_type.
        if (responseMode != null && responseMode != "query") {
            call.respondOidcError(OidcError.UnsupportedResponseType, protoAuthReq)
            return@get
        }

        // --- 7. scope must include openid ---------------------------------
        if ("openid" !in scope) {
            call.respondOidcError(OidcError.InvalidScope("scope must include 'openid'"), protoAuthReq)
            return@get
        }

        // --- 8. scopes must be subset of client.allowedScopes -------------
        val disallowed = scope.filter { it !in client.allowedScopes }
        if (disallowed.isNotEmpty()) {
            call.respondOidcError(
                OidcError.InvalidScope("scope(s) not allowed for client: ${disallowed.joinToString(" ")}"),
                protoAuthReq,
            )
            return@get
        }

        // --- 9. code_challenge required -----------------------------------
        if (codeChallenge.isNullOrBlank()) {
            call.respondOidcError(OidcError.InvalidRequest("missing code_challenge"), protoAuthReq)
            return@get
        }

        // --- 10. code_challenge_method must be S256 -----------------------
        // Spec is strict: missing OR not exactly `S256` → reject. `plain` is
        // never acceptable; this OP advertises only S256 in discovery.
        if (codeChallengeMethod != "S256") {
            call.respondOidcError(
                OidcError.InvalidRequest("code_challenge_method must be S256"),
                protoAuthReq,
            )
            return@get
        }

        // --- Success: mint authRequestId, persist, set cookie, redirect ----
        val authRequestId = randomId()
        val authRequest = AuthRequest(
            authRequestId = authRequestId,
            clientId = clientId,
            redirectUri = redirectUri,
            scope = scope,
            state = state,
            nonce = nonce,
            codeChallenge = codeChallenge,
            codeChallengeMethod = codeChallengeMethod,
            prompt = prompt,
            chosenRealmId = null,
            subject = null,
            claims = emptyMap(),
        )
        authRequestStore.put(authRequestId, authRequest)

        // The `sid` cookie value equals the authRequestId. See Task 8 spec:
        // authRequestId is not a secret, it's a lookup key, and keeping them
        // unified removes the need for a second store. /login (Task 9) reads
        // this cookie to find the pending AuthRequest.
        //
        // SameSite=Lax — sent on top-level navigations (the /login redirect)
        //   but not on cross-site POSTs, preventing CSRF on consent POSTs.
        // HttpOnly — blocks JS access so a leaked sid can't be exfiltrated
        //   via XSS (PLAIN_ERROR_PAGE is already escaped; defence in depth).
        // Secure — gated on config.cookieSecure so dev over plain HTTP works
        //   without operators flipping it off; prod MUST enable it.
        // Path=/ — covers every endpoint in this service.
        call.response.cookies.append(
            Cookie(
                name = "sid",
                value = authRequestId,
                encoding = CookieEncoding.RAW,
                httpOnly = true,
                secure = config.cookieSecure,
                path = "/",
                extensions = mapOf("SameSite" to "Lax"),
            ),
        )

        call.respondRedirect("/login")
    }
}

/**
 * Minimal plain HTML 400 page for invalid_request when we cannot construct
 * an [OidcError]. Mirrors the PLAIN_ERROR_PAGE branch of the dispatcher but
 * is reserved for the "too broken to classify" case (missing required params).
 */
private suspend fun ApplicationCall.respondPlainBadRequest(code: String, description: String) {
    val body = buildString {
        append("<!doctype html><html><body><h1>Authentication error</h1>")
        append("<p><strong>").append(code).append("</strong></p>")
        append("<p>").append(description).append("</p>")
        append("</body></html>")
    }
    respondText(body, ContentType.Text.Html, HttpStatusCode.BadRequest)
}

/**
 * Generate a URL-safe random 128-bit ID (Base64URL, no padding).
 * Uses [SecureRandom] — sufficient for cookie / session lookup keys.
 *
 * NB: the authRequestId doubles as the `sid` cookie value. It is NOT a
 * capability on its own — the code/token exchange still requires PKCE —
 * but we want it unguessable all the same.
 */
private val secureRandom = SecureRandom()
private fun randomId(): String {
    val bytes = ByteArray(16)
    secureRandom.nextBytes(bytes)
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
}
