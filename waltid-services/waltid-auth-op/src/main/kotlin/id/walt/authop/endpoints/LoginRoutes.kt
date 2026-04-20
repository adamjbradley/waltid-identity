package id.walt.authop.endpoints

import id.walt.authop.AuthOpDeps
import id.walt.authop.domain.AuthRequest
import id.walt.authop.errors.OidcError
import id.walt.authop.errors.respondOidcError
import id.walt.authop.templates.respondLoginPage
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

/**
 * OIDC `/login` entry point — realm picker + `prompt` short-circuits.
 *
 * Preconditions established by Task 8 (`/authorize`): the browser carries a
 * `sid` cookie whose value equals the pending [AuthRequest] id. If the
 * cookie or the underlying AuthRequest is missing we cannot safely redirect
 * anywhere — we don't have a trusted `redirect_uri` — so those cases render
 * a plain 400 page in the same style as `/authorize`'s PLAIN_ERROR_PAGE.
 *
 * Prompt handling follows OIDC Core §3.1.2.1:
 *
 * - `prompt=none` — MUST NOT display UI. With a session we silently complete
 *   the request (go to /consent, Task 10). Without one we redirect the RP
 *   with `error=login_required`.
 * - `prompt=login` — MUST re-authenticate. Ignore any existing session and
 *   render the realm picker.
 * - default (null) — SSO: if a session exists, silently continue; otherwise
 *   render the realm picker.
 *
 * SSO branch: we update the [AuthRequest] with the session's subject and
 * redirect to `/consent`. `/consent` itself is Task 10; for Task 9 the
 * redirect leaves the cookie intact and is intentionally a dead link —
 * tests assert on the Location header rather than on final-code issuance.
 */
fun Route.loginRoutes(deps: AuthOpDeps) {
    get("/login") {
        // --- Cookie presence -------------------------------------------
        // Missing sid = we have nothing to bind this request to; any
        // AuthRequest we lookup would be attacker-chosen. Refuse.
        val sid = call.request.cookies["sid"]
            ?: return@get call.respondPlainBadRequest(
                code = "invalid_request",
                description = "missing sid cookie",
            )

        // --- AuthRequest lookup ----------------------------------------
        // Expired or unknown id = we cannot trust the redirect_uri we
        // would need to redirect an error to. Render a plain page.
        val authReq = deps.authRequestStore.get(sid)
            ?: return@get call.respondPlainBadRequest(
                code = "invalid_request",
                description = "auth request not found or expired",
            )

        // --- Client lookup (should always succeed, but guard anyway) --
        // The ClientRegistry is a snapshot — in the hypothetical case that
        // a client was removed between /authorize and /login, treat it as
        // a 400. No redirect target can be trusted for a deleted client.
        val client = deps.clientRegistry[authReq.clientId]
            ?: return@get call.respondPlainBadRequest(
                code = "invalid_request",
                description = "client no longer registered",
            )

        // --- SSO lookup -------------------------------------------------
        // Task 9 keys the SessionStore by the same sid used for the
        // pending AuthRequest. Task 10 will rotate `sid` on successful
        // login so the post-login session cookie is distinct from the
        // pre-login binding; for now the shared key is deliberate.
        val existingSession = deps.sessionStore.get(sid)

        // --- Prompt handling -------------------------------------------
        when (authReq.prompt) {
            "none" -> {
                if (existingSession != null) {
                    // Silent reauth: propagate session identity onto the
                    // AuthRequest and proceed. Claims stay empty for Task 9;
                    // the per-realm login handlers will populate them.
                    deps.authRequestStore.update(sid) { current ->
                        current.copy(
                            subject = existingSession.subject,
                            chosenRealmId = existingSession.realmId,
                        )
                    }
                    return@get call.respondRedirect("/consent")
                }
                // No session → spec requires login_required back to RP.
                return@get call.respondOidcError(OidcError.LoginRequired, authReq)
            }

            "login" -> {
                // Force re-auth. Session (if any) is ignored — the user
                // must pick a realm and go through the upstream flow again.
                return@get call.respondLoginPage(authReq, client, visibleRealms(deps, client))
            }

            else -> {
                // Default: SSO if we have a session, otherwise render.
                if (existingSession != null) {
                    deps.authRequestStore.update(sid) { current ->
                        current.copy(
                            subject = existingSession.subject,
                            chosenRealmId = existingSession.realmId,
                        )
                    }
                    return@get call.respondRedirect("/consent")
                }
                return@get call.respondLoginPage(authReq, client, visibleRealms(deps, client))
            }
        }
    }
}

/**
 * Realms visible to this RP. If [id.walt.authop.config.ClientConfig.allowedRealms]
 * is non-empty, filter the global registry down to that subset (preserving
 * registry ordering, not the client config's ordering — the registry is the
 * source of truth for display order). Empty/missing on the client means
 * "all realms", matching the permissive default at registry load time.
 */
private fun visibleRealms(
    deps: AuthOpDeps,
    client: id.walt.authop.config.ClientConfig,
): Collection<id.walt.authop.config.RealmConfig> {
    val allowed = client.allowedRealms.takeIf { it.isNotEmpty() }?.toSet()
    return deps.realmRegistry.all().filter { allowed == null || it.id in allowed }
}

/**
 * Minimal plain HTML 400 page used when `/login` cannot classify the
 * failure into an [OidcError] (no trusted redirect_uri available).
 * Mirrors the counterpart in [authorizeRoutes] — kept private here to
 * avoid coupling the two files via a shared internal helper.
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
