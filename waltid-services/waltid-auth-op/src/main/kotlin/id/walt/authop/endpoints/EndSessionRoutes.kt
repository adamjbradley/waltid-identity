package id.walt.authop.endpoints

import id.walt.authop.AuthOpDeps
import id.walt.authop.config.ClientConfig
import id.walt.authop.config.RealmMethod
import id.walt.authop.store.LogoutFlow
import id.walt.authop.upstream.OidcClientException
import io.ktor.http.ContentType
import io.ktor.http.Cookie
import io.ktor.http.CookieEncoding
import io.ktor.http.HttpStatusCode
import io.ktor.http.URLBuilder
import io.ktor.http.Url
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import java.security.SecureRandom
import java.util.Base64

/**
 * RP-initiated logout (OpenID Connect RP-Initiated Logout 1.0).
 *
 * Two routes:
 *
 * - `GET /end_session` — the RP bounces the user agent here with
 *   `id_token_hint` (optional) / `client_id` (optional if hint present) /
 *   `post_logout_redirect_uri` / `state` (optional). We:
 *     1. Resolve the client (from `client_id` or decode `aud` from the hint).
 *     2. Validate `post_logout_redirect_uri` against
 *        [ClientConfig.postLogoutRedirectUris] (Keycloak-style wildcard match).
 *     3. If a `sid` cookie points at an active [id.walt.authop.domain.Session]
 *        with an [id.walt.authop.domain.Session.upstreamIdToken], chain the
 *        logout through the realm's upstream `end_session_endpoint`.
 *     4. Otherwise clear the cookie + session and 302 back to the RP.
 *
 * - `GET /end_session/upstream_return` — the upstream's return leg. Consume
 *   the one-shot [LogoutFlow] by `state`, clear cookie + session, redirect to
 *   the stored RP `post_logout_redirect_uri` (with the original RP `state`
 *   echoed when present).
 *
 * ### Security invariants
 *
 * - **Validated redirect only.** We 302 only to URIs we matched against the
 *   client's `postLogoutRedirectUris`. An unregistered URI → plain 400; we do
 *   not fall back to a default or render the raw user input in a redirect.
 * - **Single-use upstream state.** The [LogoutFlow] entry is removed atomically
 *   on consume, so a replayed `/upstream_return` URL fails the lookup.
 * - **No session ≠ error.** OIDC RP-Initiated Logout 1.0 §2 treats logout of
 *   an already-logged-out user as a successful no-op. We 302 to the validated
 *   RP URI without touching any store.
 * - **Discovery-failure fallback.** If the realm's upstream discovery fails
 *   or the upstream has no `end_session_endpoint`, we do NOT fail the logout —
 *   we clear locally and 302 to the RP. Logging out of the local session is
 *   the part that actually matters; the upstream chain is best-effort.
 * - **No credential data or secrets logged.** `id_token_hint` contents are
 *   decoded only for `aud` extraction; we never log them.
 */
fun Route.endSessionRoutes(deps: AuthOpDeps) {
    get("/end_session") { handleEndSession(deps) }
    get("/end_session/upstream_return") { handleUpstreamReturn(deps) }
}

private suspend fun io.ktor.server.routing.RoutingContext.handleEndSession(deps: AuthOpDeps) {
    val params = call.request.queryParameters

    val idTokenHint = params["id_token_hint"]?.takeIf { it.isNotBlank() }
    val rawClientId = params["client_id"]?.takeIf { it.isNotBlank() }
    val postLogoutRedirectUri = params["post_logout_redirect_uri"]?.takeIf { it.isNotBlank() }
        ?: return call.respondPlainEndSessionBadRequest(
            "invalid_request",
            "post_logout_redirect_uri is required",
        )
    val rpState = params["state"]?.takeIf { it.isNotBlank() }

    // Resolve client_id: prefer the explicit param, otherwise peek at the hint.
    // Decoding the hint is unverified (we don't have the upstream's key here)
    // but that's fine — we only use it as a routing hint, and we still validate
    // the resolved client + redirect URI below.
    val clientId = rawClientId ?: idTokenHint?.let { extractAudience(it) }
    if (clientId.isNullOrBlank()) {
        return call.respondPlainEndSessionBadRequest(
            "invalid_request",
            "client_id is required (explicit or derivable from id_token_hint)",
        )
    }

    val client = deps.clientRegistry[clientId]
        ?: return call.respondPlainEndSessionBadRequest(
            "invalid_request",
            "unknown client_id",
        )

    if (!matchesAnyPostLogoutRedirectUri(client, postLogoutRedirectUri)) {
        return call.respondPlainEndSessionBadRequest(
            "invalid_request",
            "post_logout_redirect_uri is not registered for this client",
        )
    }

    // No session cookie = already-logged-out. Spec: successful no-op → 302 to
    // the validated redirect. We skip any cookie clearing (there's nothing to
    // clear) and echo the RP state on the way out.
    val sid = call.request.cookies["sid"]
    if (sid == null) {
        return call.respondRedirect(appendState(postLogoutRedirectUri, rpState))
    }

    val session = deps.sessionStore.get(sid)
    if (session == null) {
        // Cookie without a live session — still a no-op per spec, but clear
        // the stale cookie on the way out.
        call.clearSidCookie(deps)
        return call.respondRedirect(appendState(postLogoutRedirectUri, rpState))
    }

    // VP realm or OIDC realm without a stored upstream id_token: nothing to
    // chain. Clear locally and return.
    val upstreamIdToken = session.upstreamIdToken
    if (upstreamIdToken.isNullOrBlank()) {
        deps.sessionStore.remove(sid)
        call.clearSidCookie(deps)
        return call.respondRedirect(appendState(postLogoutRedirectUri, rpState))
    }

    // OIDC realm with upstream hint. Look up the realm + discovery. A realm
    // that's gone (reloaded registry, id changed) falls back to local logout.
    val realm = deps.realmRegistry[session.realmId]
    val oidcCfg = realm?.takeIf { it.method == RealmMethod.OIDC }?.oidc
    if (oidcCfg == null) {
        deps.sessionStore.remove(sid)
        call.clearSidCookie(deps)
        return call.respondRedirect(appendState(postLogoutRedirectUri, rpState))
    }

    val discovery = try {
        deps.oidcClient.discover(oidcCfg.issuer)
    } catch (_: OidcClientException) {
        // Upstream unreachable or mis-configured → clear locally and redirect.
        // The user's local session is the part of logout that matters to them;
        // the upstream chain is best-effort.
        deps.sessionStore.remove(sid)
        call.clearSidCookie(deps)
        return call.respondRedirect(appendState(postLogoutRedirectUri, rpState))
    }

    val endSessionEndpoint = discovery.endSessionEndpoint
    if (endSessionEndpoint.isNullOrBlank()) {
        // Upstream doesn't advertise end_session — skip the chain.
        deps.sessionStore.remove(sid)
        call.clearSidCookie(deps)
        return call.respondRedirect(appendState(postLogoutRedirectUri, rpState))
    }

    // Mint a fresh state nonce, stash the flow, and 302 to the upstream with
    // OUR /upstream_return as the upstream's post_logout_redirect_uri. We
    // delay clearing the cookie + session until the return leg fires — if the
    // user never comes back (closed tab, upstream hang), the session still
    // expires via its TTL and the LogoutFlow evicts after 5 min.
    val upstreamState = randomToken()
    deps.logoutFlowStore.put(
        upstreamState,
        LogoutFlow(
            sid = sid,
            postLogoutRedirectUri = postLogoutRedirectUri,
            rpState = rpState,
        ),
    )

    val ourReturn = "${deps.config.canonicalIssuer}/end_session/upstream_return"
    val upstreamUrl = buildUpstreamEndSessionUrl(
        endSessionEndpoint = endSessionEndpoint,
        idTokenHint = upstreamIdToken,
        postLogoutRedirectUri = ourReturn,
        state = upstreamState,
    )
    call.respondRedirect(upstreamUrl)
}

private suspend fun io.ktor.server.routing.RoutingContext.handleUpstreamReturn(deps: AuthOpDeps) {
    val state = call.request.queryParameters["state"]?.takeIf { it.isNotBlank() }
        ?: return call.respondPlainEndSessionBadRequest(
            "invalid_request",
            "state is required",
        )

    // Atomic consume — a replayed return URL lands on the null branch.
    val flow = deps.logoutFlowStore.consume(state)
        ?: return call.respondPlainEndSessionBadRequest(
            "invalid_request",
            "unknown or already-consumed state",
        )

    // Clear the bound session + cookie. This is the point where local logout
    // actually happens for an OIDC-realm user — before here the cookie was
    // kept live so that a failure to reach the upstream didn't orphan them.
    deps.sessionStore.remove(flow.sid)
    call.clearSidCookie(deps)
    call.respondRedirect(appendState(flow.postLogoutRedirectUri, flow.rpState))
}

/**
 * Keycloak-style wildcard match for registered `post_logout_redirect_uris`.
 *
 * Matching rules:
 *  - Registered pattern ends with `*` → prefix match: `candidate` must start
 *    with the pattern minus the trailing `*`.
 *  - Otherwise → strict equality.
 *
 * **Security note.** Prefix matching is security-sensitive: a registered
 * pattern like `https://rp.example/` followed by `*` must end with a `/`
 * before the wildcard so it cannot be tricked into matching
 * `https://rp.example.attacker/...`. We do NOT enforce that shape here —
 * the operator configures patterns correctly. This matches Keycloak's
 * behavior for the same reason (operator responsibility).
 *
 * This IS slightly different from Keycloak in one edge case: Keycloak also
 * does query-param reassembly for some patterns; we treat the stored string
 * as a literal prefix. For v1 MVP that's intentional — it's the stricter
 * behavior and closes a class of `?`-vs-`/` ambiguity.
 */
private fun matchesAnyPostLogoutRedirectUri(client: ClientConfig, candidate: String): Boolean =
    client.postLogoutRedirectUris.any { matches(it, candidate) }

private fun matches(registered: String, candidate: String): Boolean =
    if (registered.endsWith("*")) {
        candidate.startsWith(registered.dropLast(1))
    } else {
        candidate == registered
    }

/** Append `state=<rpState>` to the URL when non-null; otherwise return verbatim. */
private fun appendState(baseUrl: String, rpState: String?): String {
    if (rpState.isNullOrBlank()) return baseUrl
    val builder = URLBuilder(Url(baseUrl))
    builder.parameters.append("state", rpState)
    return builder.buildString()
}

/**
 * Build the upstream end-session URL with id_token_hint +
 * post_logout_redirect_uri + state, following OIDC RP-Initiated Logout 1.0 §3.
 */
private fun buildUpstreamEndSessionUrl(
    endSessionEndpoint: String,
    idTokenHint: String,
    postLogoutRedirectUri: String,
    state: String,
): String {
    val builder = URLBuilder(Url(endSessionEndpoint)).apply {
        parameters.append("id_token_hint", idTokenHint)
        parameters.append("post_logout_redirect_uri", postLogoutRedirectUri)
        parameters.append("state", state)
    }
    return builder.buildString()
}

/**
 * Decode a JWT and return its `aud` claim (single-value or first of an array).
 * This is best-effort and UN-VERIFIED — it exists only so we can derive a
 * `client_id` when the RP omits it and sends only `id_token_hint`. The client
 * is still validated through [AuthOpDeps.clientRegistry] and the redirect URI
 * is still validated against that client's list.
 */
private fun extractAudience(jwt: String): String? = try {
    val segments = jwt.split(".")
    if (segments.size < 2) null
    else {
        val padded = padBase64Url(segments[1])
        val bytes = Base64.getUrlDecoder().decode(padded)
        val payload = Json.parseToJsonElement(bytes.decodeToString()).jsonObject
        when (val aud = payload["aud"]) {
            is JsonPrimitive -> aud.content.takeIf { aud.isString && it.isNotBlank() }
            is JsonArray -> aud.jsonArray
                .firstOrNull()
                ?.let { it as? JsonPrimitive }
                ?.content
                ?.takeIf { it.isNotBlank() }
            else -> null
        }
    }
} catch (_: Throwable) {
    null
}

private fun padBase64Url(s: String): String = when (s.length % 4) {
    0 -> s
    2 -> "$s=="
    3 -> "$s="
    else -> s
}

/** 128-bit URL-safe random token (unpadded Base64URL). */
private val secureRandom = SecureRandom()

private fun randomToken(): String {
    val bytes = ByteArray(16)
    secureRandom.nextBytes(bytes)
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
}

/**
 * Clear the `sid` cookie by re-setting it with an empty value and Max-Age=0.
 * Attribute shape (Path=/, HttpOnly, SameSite=Lax, Secure gated on config)
 * mirrors the original set in [authorizeRoutes] so the browser matches on the
 * same cookie identity.
 */
private fun ApplicationCall.clearSidCookie(deps: AuthOpDeps) {
    response.cookies.append(
        Cookie(
            name = "sid",
            value = "",
            encoding = CookieEncoding.RAW,
            maxAge = 0,
            httpOnly = true,
            secure = deps.config.cookieSecure,
            path = "/",
            extensions = mapOf("SameSite" to "Lax"),
        ),
    )
}

private suspend fun ApplicationCall.respondPlainEndSessionBadRequest(
    code: String,
    description: String,
) {
    val body = buildString {
        append("<!doctype html><html><body><h1>Logout error</h1>")
        append("<p><strong>").append(code).append("</strong></p>")
        append("<p>").append(description).append("</p>")
        append("</body></html>")
    }
    respondText(body, ContentType.Text.Html, HttpStatusCode.BadRequest)
}
