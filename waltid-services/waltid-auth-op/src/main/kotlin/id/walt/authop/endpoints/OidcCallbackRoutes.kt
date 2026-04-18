@file:OptIn(ExperimentalTime::class)

package id.walt.authop.endpoints

import id.walt.authop.AuthOpDeps
import id.walt.authop.claims.ClaimMapper
import id.walt.authop.config.RealmConfig
import id.walt.authop.config.RealmMethod
import id.walt.authop.domain.Session
import id.walt.authop.errors.OidcError
import id.walt.authop.errors.respondOidcError
import id.walt.authop.store.UpstreamFlow
import id.walt.authop.upstream.OidcClientException
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.URLBuilder
import io.ktor.http.Url
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.util.getOrFail
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import java.security.SecureRandom
import java.util.Base64
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * Upstream-OIDC realm adapter.
 *
 * Two routes live here, both entered with the pre-login `sid` cookie minted at
 * `/authorize`:
 *
 * - `GET /login/realm/{realmId}` — kick off the upstream OIDC flow. For an
 *   OIDC realm we mint fresh state + nonce, persist them in
 *   [id.walt.authop.store.UpstreamFlowStore], stamp `chosenRealmId` on the
 *   pending AuthRequest, and 302 the user agent to the upstream's authorize
 *   endpoint. For an OID4VP realm we 501 here — Task 17 will replace that
 *   with the QR page.
 *
 * - `GET /callback/oidc` — the upstream's return leg. We consume the state
 *   (single-use), bind it to the cookie, exchange the code at the upstream
 *   token endpoint via [id.walt.authop.upstream.OidcClient.exchangeCode]
 *   (which verifies signature + iss/aud/exp/nonce), run [ClaimMapper] against
 *   the ID-token claims, and mint a [Session] keyed by the same `sid`. The
 *   AuthRequest is then hydrated with subject + mapped claims and we redirect
 *   to `/consent`.
 *
 * ### Security invariants
 *
 * - **State binding to user agent.** The `sid` cookie MUST match the
 *   `authRequestId` that was bound to the state at kickoff. Without this,
 *   an attacker who obtained a victim's callback URL could complete the
 *   flow in the attacker's browser, then redirect the victim's browser to
 *   /consent — a CSRF-style session fixation.
 * - **Single-use state.** The store's `consume` is atomic get-and-remove.
 *   Replaying a callback URL fails the `consume` lookup and 400s.
 * - **Nonce.** The upstream's ID token must carry the nonce we minted. The
 *   OidcClient enforces this via `expectedNonce`; we just pass it through.
 * - **No RP redirect for plain state/cookie failures.** When state is
 *   unknown (no flow to look up the AuthRequest), we cannot know what
 *   redirect_uri to route an error to — so we render a plain 400. Only
 *   upstream exchange failures (where we DO have a flow and therefore an
 *   AuthRequest + redirect_uri) redirect back to the RP with `access_denied`.
 */
fun Route.oidcCallbackRoutes(deps: AuthOpDeps) {
    get("/login/realm/{realmId}") {
        val realmId = call.parameters.getOrFail("realmId")

        // --- Cookie + AuthRequest + Client invariants ------------------
        // Mirror /login's contract: the sid cookie MUST be present and MUST
        // map to an in-flight AuthRequest whose client is still registered.
        // A missing cookie means we have no trusted redirect_uri to route an
        // error to — plain 400. Same for an expired/unknown AuthRequest or a
        // client that disappeared from the registry mid-flight.
        val sid = call.request.cookies["sid"]
            ?: return@get call.respondPlainBadRequest("invalid_request", "missing sid cookie")
        val authReq = deps.authRequestStore.get(sid)
            ?: return@get call.respondPlainBadRequest(
                "invalid_request",
                "auth request not found or expired",
            )
        val client = deps.clientRegistry[authReq.clientId]
            ?: return@get call.respondPlainBadRequest(
                "invalid_request",
                "client no longer registered",
            )

        // --- Realm lookup + client gate --------------------------------
        // Unknown realm → plain 400 (no error to route to the RP; this is a
        // mis-linked realm picker). Realm not in client.allowed_realms →
        // plain 400 as well; an RP that DIDN'T request this realm shouldn't
        // receive a protocol-level error for a UI mis-click that their user
        // performed against the realm picker WE rendered.
        val realm: RealmConfig = deps.realmRegistry[realmId]
            ?: return@get call.respondPlainBadRequest(
                "invalid_request",
                "unknown realm",
            )
        if (client.allowedRealms.isNotEmpty() && realmId !in client.allowedRealms) {
            return@get call.respondPlainBadRequest(
                "invalid_request",
                "realm not allowed for client",
            )
        }

        when (realm.method) {
            RealmMethod.OIDC -> {
                val oidcCfg = realm.oidc
                    // Config-level invariant; RealmRegistry.validate catches
                    // method=oidc && oidc=null at load time, so reaching this
                    // branch means the registry is inconsistent — a bug.
                    ?: return@get call.respondPlainBadRequest(
                        "server_error",
                        "realm '$realmId' has no oidc block",
                    )

                // Discovery fetch. Cached by OidcClient so repeat kickoffs
                // don't hammer the upstream. A failure here is a server-side
                // problem (the upstream is misconfigured or down) — plain 400
                // with a generic message so attackers can't fingerprint us
                // on upstream topology.
                val discovery = try {
                    deps.oidcClient.discover(oidcCfg.issuer)
                } catch (e: OidcClientException) {
                    return@get call.respondPlainBadRequest(
                        "server_error",
                        "upstream discovery failed",
                    )
                }

                // Fresh state + nonce — 128 bits each, Base64URL. The `state`
                // is BOTH the store key AND the `state` param echoed by the
                // upstream; the `nonce` is also echoed back in the ID token
                // and cross-checked in exchangeCode.
                val upstreamState = randomToken()
                val upstreamNonce = randomToken()
                deps.upstreamFlowStore.put(
                    upstreamState,
                    UpstreamFlow(
                        authRequestId = authReq.authRequestId,
                        realmId = realmId,
                        upstreamNonce = upstreamNonce,
                        issuer = oidcCfg.issuer,
                        clientId = oidcCfg.clientId,
                        clientSecret = oidcCfg.clientSecret,
                        createdAt = Clock.System.now(),
                    ),
                )

                // Stamp chosenRealmId now so /consent and /callback/oidc can
                // cross-reference the realm the user picked. Subject + claims
                // remain null until the callback fires.
                deps.authRequestStore.update(sid) { current ->
                    current.copy(chosenRealmId = realmId)
                }

                val callbackUri = "${deps.config.canonicalIssuer}/callback/oidc"
                val authorizeUrl = buildUpstreamAuthorizeUrl(
                    authorizationEndpoint = discovery.authorizationEndpoint,
                    clientId = oidcCfg.clientId,
                    redirectUri = callbackUri,
                    scopes = oidcCfg.scopes,
                    state = upstreamState,
                    nonce = upstreamNonce,
                )
                call.respondRedirect(authorizeUrl)
            }

            RealmMethod.OID4VP -> {
                // Task 17 owns this. Returning 501 (not 400) makes clear
                // this is intentionally-incomplete-server, not a malformed
                // request from the user.
                call.respondText(
                    "VP realms not yet implemented",
                    ContentType.Text.Plain,
                    HttpStatusCode.NotImplemented,
                )
            }
        }
    }

    get("/callback/oidc") {
        val code = call.request.queryParameters["code"]
        val state = call.request.queryParameters["state"]
        if (code.isNullOrBlank() || state.isNullOrBlank()) {
            return@get call.respondPlainBadRequest(
                "invalid_request",
                "code and state are required",
            )
        }

        // Single-use state consumption. A replayed callback URL lands here.
        val flow = deps.upstreamFlowStore.consume(state)
            ?: return@get call.respondPlainBadRequest(
                "invalid_request",
                "unknown or already-consumed state",
            )

        // Cookie binding: the user agent MUST be the one we minted the state
        // for. If missing or mismatched we refuse — stops an attacker who
        // grabbed the callback URL from completing the flow in their browser
        // to hand over an authenticated session to the victim.
        val sid = call.request.cookies["sid"]
        if (sid == null || sid != flow.authRequestId) {
            return@get call.respondPlainBadRequest(
                "invalid_request",
                "sid cookie does not match upstream flow",
            )
        }

        // AuthRequest must still exist (TTL might have ticked through). If
        // absent we can't route an access_denied back — plain 400.
        val authReq = deps.authRequestStore.get(sid)
            ?: return@get call.respondPlainBadRequest(
                "invalid_request",
                "auth request not found or expired",
            )

        // Re-fetch discovery (the cache hit keeps this cheap; a miss means
        // the OidcClient's ttl expired between kickoff and callback and we
        // round-trip to the upstream once). A discovery failure here is
        // server-side; treat like an upstream exchange failure and redirect
        // back to the RP with access_denied so the RP knows the user's flow
        // died, rather than leaking a bare 400 they can't correlate.
        val discovery = try {
            deps.oidcClient.discover(flow.issuer)
        } catch (e: OidcClientException) {
            return@get call.respondOidcError(
                OidcError.AccessDenied("upstream: ${e.code}"),
                authReq,
            )
        }

        val callbackUri = "${deps.config.canonicalIssuer}/callback/oidc"
        val tokenResult = try {
            deps.oidcClient.exchangeCode(
                discovery = discovery,
                clientId = flow.clientId,
                clientSecret = flow.clientSecret,
                code = code,
                redirectUri = callbackUri,
                expectedNonce = flow.upstreamNonce,
            )
        } catch (e: OidcClientException) {
            // Any upstream failure (token 4xx, signature, iss/aud/exp/nonce)
            // funnels to access_denied with a machine-readable upstream code.
            // The description is developer-facing; the RP can log it, but the
            // user-facing error is just "access_denied".
            return@get call.respondOidcError(
                OidcError.AccessDenied("upstream: ${e.code}"),
                authReq,
            )
        }

        // --- Claim mapping -------------------------------------------------
        // Realm must still be present (same mid-flight-mutation guard as
        // kickoff). Apply the configured mapping to the upstream ID-token
        // payload; the output is the flat claim set we stamp on AuthRequest
        // and issue downstream in our own ID/userinfo tokens.
        val realm = deps.realmRegistry[flow.realmId]
            ?: return@get call.respondPlainBadRequest(
                "server_error",
                "realm '${flow.realmId}' disappeared",
            )
        val mappedClaims: Map<String, JsonElement> =
            ClaimMapper.apply(tokenResult.idTokenClaims, realm.claimMapping)

        // --- Subject derivation --------------------------------------------
        // OIDC realms pass the upstream `sub` through. If the mapping didn't
        // project a `sub`, that's a realm-config bug: we cannot mint a Session
        // without a subject. 500 plain — the operator needs to fix the config.
        val subjectClaim = mappedClaims["sub"] as? JsonPrimitive
        val subject = subjectClaim?.contentOrNull()
        if (subject.isNullOrBlank()) {
            return@get call.respondPlainServerError(
                "realm '${flow.realmId}' claim mapping did not produce a non-empty 'sub'",
            )
        }

        // --- Session creation ----------------------------------------------
        // Keyed by the pre-login sid. Task 10 planned to rotate sid on login,
        // but until that lands the shared key is consistent with LoginRoutes'
        // SSO reuse of the same cookie.
        val session = Session(
            sessionId = sid,
            subject = subject,
            realmId = flow.realmId,
            amr = listOf("pwd"),                          // conservative default
            acr = "urn:walt:upstream-oidc",
            authTime = Clock.System.now(),
            upstreamIdToken = tokenResult.idToken,        // for RP-initiated logout chain
        )
        deps.sessionStore.put(sid, session)

        // --- AuthRequest hydration -----------------------------------------
        // Subject + claims flow downstream to /consent → /token. We also
        // re-stamp `realm` as a claim so the minted ID token can project
        // which realm this session came from (design doc §JWT claims).
        val claimsForAuthReq = buildMap<String, JsonElement> {
            put("realm", JsonPrimitive(flow.realmId))
            putAll(mappedClaims)
        }
        deps.authRequestStore.update(sid) { current ->
            current.copy(
                subject = subject,
                claims = claimsForAuthReq,
                chosenRealmId = flow.realmId,
            )
        }

        call.respondRedirect("/consent")
    }
}

/** Build the upstream authorize URL with response_type=code + the given state/nonce. */
private fun buildUpstreamAuthorizeUrl(
    authorizationEndpoint: String,
    clientId: String,
    redirectUri: String,
    scopes: List<String>,
    state: String,
    nonce: String,
): String {
    // Use URLBuilder so param encoding is correct for every value (scope set
    // with spaces, redirect_uri containing colons / slashes, etc.).
    val builder = URLBuilder(Url(authorizationEndpoint)).apply {
        parameters.append("response_type", "code")
        parameters.append("client_id", clientId)
        parameters.append("redirect_uri", redirectUri)
        parameters.append("scope", scopes.joinToString(" "))
        parameters.append("state", state)
        parameters.append("nonce", nonce)
    }
    return builder.buildString()
}

/** 128-bit URL-safe random token (unpadded Base64URL). */
private val secureRandom = SecureRandom()
private fun randomToken(): String {
    val bytes = ByteArray(16)
    secureRandom.nextBytes(bytes)
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
}

/**
 * Null-safe content accessor — returns null for JSON `null` primitives so a
 * mapped-but-null sub is treated as missing, not as the string "null".
 */
private fun JsonPrimitive.contentOrNull(): String? =
    if (!isString && content == "null") null else content

private suspend fun ApplicationCall.respondPlainBadRequest(code: String, description: String) {
    val body = buildString {
        append("<!doctype html><html><body><h1>Authentication error</h1>")
        append("<p><strong>").append(code).append("</strong></p>")
        append("<p>").append(description).append("</p>")
        append("</body></html>")
    }
    respondText(body, ContentType.Text.Html, HttpStatusCode.BadRequest)
}

private suspend fun ApplicationCall.respondPlainServerError(description: String) {
    val body = buildString {
        append("<!doctype html><html><body><h1>Server error</h1>")
        append("<p>").append(description).append("</p>")
        append("</body></html>")
    }
    respondText(body, ContentType.Text.Html, HttpStatusCode.InternalServerError)
}
