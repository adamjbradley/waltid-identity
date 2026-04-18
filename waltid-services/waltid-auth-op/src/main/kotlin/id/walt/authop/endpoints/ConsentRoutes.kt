@file:OptIn(ExperimentalTime::class)

package id.walt.authop.endpoints

import id.walt.authop.AuthOpDeps
import id.walt.authop.domain.AuthCode
import id.walt.authop.domain.AuthRequest
import id.walt.authop.errors.OidcError
import id.walt.authop.errors.respondOidcError
import id.walt.authop.templates.respondConsentPage
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.formUrlEncode
import io.ktor.http.parametersOf
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveParameters
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import java.security.SecureRandom
import java.util.Base64
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * OIDC `/consent` endpoint — decides between trusted-skip, user-decided allow,
 * and user-decided deny.
 *
 * ### State-machine placement
 *
 * `/consent` is reachable only after `/login` has stamped the user's `subject`
 * onto the pending [AuthRequest]. We assert that precondition: a NULL subject
 * means the state machine was violated (someone hit `/consent` directly), and
 * we 400 rather than silently redirecting to `/login` — the plan explicitly
 * chooses the loud failure to flush out bugs in the routing order.
 *
 * ### Trusted clients
 *
 * When `ClientConfig.trusted = true` the consent page is skipped entirely.
 * We mint an auth code, stash it in the [id.walt.authop.store.AuthCodeStore],
 * delete the [AuthRequest] (single-use flow), and redirect to the RP. The
 * HTML page is not rendered — the GET goes straight to a 302, and the
 * `trusted client skips consent` test asserts on this exact shape.
 *
 * ### Non-trusted clients
 *
 * Render the consent page with a hidden CSRF token (256 bits from
 * [SecureRandom], Base64URL). The token is single-use: [validate] consumes
 * on match, so a resubmitted form or concurrent browser tab on the same `sid`
 * fails with 403. This plus the SameSite=Lax cookie from `/authorize` gives
 * us defence-in-depth against cross-site POSTs.
 *
 * ### Error echoing
 *
 * `deny` flows through [OidcError.AccessDenied] so the dispatcher echoes
 * `state` exactly (URL-encoded via `parametersOf`, never concatenation) —
 * same path the rest of the REDIRECT_TO_RP errors use.
 */
fun Route.consentRoutes(deps: AuthOpDeps) {
    get("/consent") {
        val (authReq, client) = loadConsentContext(deps) ?: return@get

        if (client.trusted) {
            completeConsent(deps, authReq)
            return@get
        }

        val csrf = deps.csrfTokenStore.issue(authReq.authRequestId)
        call.respondConsentPage(authReq, client, csrf)
    }

    post("/consent") {
        val (authReq, _) = loadConsentContext(deps) ?: return@post

        val form = call.receiveParameters()
        val submittedToken = form["csrf_token"]
        val decision = form["decision"]

        if (submittedToken == null || !deps.csrfTokenStore.validate(authReq.authRequestId, submittedToken)) {
            // We deliberately do NOT redirect to the RP here — a forged POST
            // without a valid CSRF token could carry attacker-controlled
            // `state`, and we'd be turning our /consent into an open redirect
            // oracle. Plain 403 with no RP-routable output.
            call.respondPlainForbidden("csrf_token missing or invalid")
            return@post
        }

        when (decision) {
            "accept" -> completeConsent(deps, authReq)
            "deny" -> call.respondOidcError(OidcError.AccessDenied("user denied consent"), authReq)
            else -> call.respondPlainForbidden("decision must be 'accept' or 'deny'")
        }
    }
}

/**
 * Load the pending [AuthRequest] + [id.walt.authop.config.ClientConfig] for the
 * current request. Returns null after responding with a 400 when any step fails
 * — cookie missing, AuthRequest missing/expired, subject not yet populated, or
 * client no longer registered. The short-circuit-via-null lets the route bodies
 * stay linear.
 */
private suspend fun io.ktor.server.routing.RoutingContext.loadConsentContext(
    deps: AuthOpDeps,
): Pair<AuthRequest, id.walt.authop.config.ClientConfig>? {
    val sid = call.request.cookies["sid"]
    if (sid == null) {
        call.respondPlainBadRequest("invalid_request", "missing sid cookie")
        return null
    }
    val authReq = deps.authRequestStore.get(sid)
    if (authReq == null) {
        call.respondPlainBadRequest("invalid_request", "auth request not found or expired")
        return null
    }
    // State-machine violation: reaching /consent without an authenticated
    // subject means /login was bypassed. Loud failure so bugs surface.
    if (authReq.subject == null) {
        call.respondPlainBadRequest("invalid_request", "login not completed")
        return null
    }
    val client = deps.clientRegistry[authReq.clientId]
    if (client == null) {
        call.respondPlainBadRequest("invalid_request", "client no longer registered")
        return null
    }
    return authReq to client
}

/**
 * Success path shared by trusted-client skip and explicit `accept`: mint a
 * fresh auth code, stash it, delete the AuthRequest, and redirect the user
 * agent back to the RP with `code` + optional `state` echoed byte-exact.
 *
 * The AuthRequest deletion is what enforces single-use at the flow level —
 * hitting `/consent` a second time on the same `sid` cookie lands on the
 * "auth request not found or expired" path above.
 */
private suspend fun io.ktor.server.routing.RoutingContext.completeConsent(
    deps: AuthOpDeps,
    authReq: AuthRequest,
) {
    val code = randomCode()
    // authReq.subject is guaranteed non-null by the loadConsentContext check
    // above, but the compiler doesn't know — !! is the least-surprise shape.
    // authTime uses Clock.System.now() here — the moment consent completes
    // (trusted-skip or explicit accept) IS when the auth flow finishes. For
    // v1 this is a faithful approximation of "when the user authenticated";
    // future work could thread the Session.authTime through if the flow
    // gains an upstream auth step that predates consent by more than a
    // trivial wall-clock delta.
    deps.authCodeStore.put(
        code,
        AuthCode(
            code = code,
            clientId = authReq.clientId,
            redirectUri = authReq.redirectUri,
            subject = authReq.subject!!,
            claims = authReq.claims,
            codeChallenge = authReq.codeChallenge,
            codeChallengeMethod = authReq.codeChallengeMethod,
            nonce = authReq.nonce,
            authTime = Clock.System.now(),
            scope = authReq.scope,
        ),
    )
    // Single-use: remove so any replay of the flow (e.g. browser back-button
    // then re-submit) lands on the "not found" branch rather than minting a
    // second code for the same request.
    deps.authRequestStore.remove(authReq.authRequestId)

    // Build the query the same way OidcError does — parametersOf + formUrlEncode,
    // never string concatenation, so `state` survives unusual characters.
    val pairs = buildList<Pair<String, List<String>>> {
        add("code" to listOf(code))
        authReq.state?.let { add("state" to listOf(it)) }
    }
    val query = parametersOf(*pairs.toTypedArray()).formUrlEncode()
    call.respondRedirect("${authReq.redirectUri}?$query")
}

/**
 * Mint a 256-bit Base64URL auth code. Larger than the 128-bit `authRequestId`
 * because this code is what the RP redeems at `/token` — it IS a capability.
 */
private val secureRandom = SecureRandom()
private fun randomCode(): String {
    val bytes = ByteArray(32)
    secureRandom.nextBytes(bytes)
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
}

/**
 * Plain 400 for state-machine / cookie errors. Same shape as the sibling
 * helper in [LoginRoutes] / [AuthorizeRoutes]; kept file-local rather than
 * shared because the three helpers diverge quickly as each route picks up
 * its own error taxonomy.
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
 * Plain 403 for CSRF failures. We avoid OidcError.AccessDenied here because
 * that would redirect an attacker-chosen `state` back to the RP — we want
 * the failure to stop at the OP.
 */
private suspend fun ApplicationCall.respondPlainForbidden(description: String) {
    val body = buildString {
        append("<!doctype html><html><body><h1>Forbidden</h1>")
        append("<p>").append(description).append("</p>")
        append("</body></html>")
    }
    respondText(body, ContentType.Text.Html, HttpStatusCode.Forbidden)
}
