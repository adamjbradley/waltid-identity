package id.walt.authop.endpoints

import id.walt.authop.AuthOpDeps
import id.walt.authop.config.RealmConfig
import id.walt.authop.domain.AuthRequest
import id.walt.authop.domain.VpSession
import id.walt.authop.domain.VpSessionStatus
import id.walt.authop.templates.respondVpQrPage
import id.walt.authop.upstream.Verifier2ClientException
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.util.getOrFail
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.nio.file.Files
import java.nio.file.Paths
import java.security.SecureRandom
import java.util.Base64

/**
 * OID4VP realm adapter for `/login/realm/{id}` (the VP branch) plus the
 * per-session status polling endpoint.
 *
 * ## Two entry points
 *
 * - [vpRealmKickoff] (helper, not a route) — invoked from the OID4VP branch
 *   of `/login/realm/{id}` in [oidcCallbackRoutes]. Creates a session on
 *   verifier-api2, stores the mirror [VpSession] locally keyed by the
 *   verifier's session id, and renders the QR page.
 *
 * - [vpStatusRoutes] — registers `GET /login/realm/{id}/status?verifierSessionId=…`
 *   which the QR page polls. Reports our local [VpSession.status] with strict
 *   cookie binding so a leaked verifier session id is useless without the
 *   matching browser.
 *
 * ## Security invariants
 *
 *  - **Status polling is cookie-bound.** A mismatched `sid` cookie vs the
 *    stored [VpSession.sessionCookieId] returns 403. This blocks cross-browser
 *    hijack of an in-flight presentation status.
 *  - **Status endpoint never returns credential data.** Only the lifecycle
 *    enum. Credential payloads flow through the webhook path (Task 18) and the
 *    `/complete` route (Task 19).
 *  - **Webhook secret is freshly minted per session.** 256 bits of urandom,
 *    Base64URL — the verifier-api2 webhook hand-off MUST present this on the
 *    inbound callback; `Task 18`'s handler validates it.
 *
 * ## Recovery path
 *
 * If the user refreshes `/login/realm/{id}` after their VP already succeeded
 * (same browser, same auth request), kickoff short-circuits to `/complete`
 * instead of creating a fresh verifier session. Implemented by stamping the
 * verifier session id on [AuthRequest.activeVpSessionId] at kickoff time and
 * checking it on re-entry.
 */

/**
 * OID4VP-realm entry point invoked from the OID4VP branch of
 * `/login/realm/{id}` in [oidcCallbackRoutes]. Assumes the caller has already
 * validated the sid cookie, loaded the AuthRequest + client, looked up the
 * realm, and enforced `client.allowedRealms`.
 */
internal suspend fun ApplicationCall.vpRealmKickoff(
    realm: RealmConfig,
    authReq: AuthRequest,
    deps: AuthOpDeps,
    sid: String,
) {
    val oid4vpCfg = realm.oid4vp
        ?: run {
            respondPlainBadRequest("server_error", "realm '${realm.id}' has no oid4vp block")
            return
        }

    // --- Recovery path ---------------------------------------------------
    // If a VP session is already SUCCESSFUL for THIS browser + AuthRequest,
    // jump straight to /complete. Match on all three of:
    //   * AuthRequest.activeVpSessionId (set at kickoff time below)
    //   * VpSession.sessionCookieId == current sid (cookie binding)
    //   * VpSession.authRequestId == this AuthRequest
    // All three must align — an attacker who guessed a verifier session id
    // still needs the right sid cookie, and a stale AuthRequest id prevents
    // accidental cross-request recovery.
    authReq.activeVpSessionId?.let { existingId ->
        val existing = deps.vpSessionStore.get(existingId)
        if (existing != null &&
            existing.status == VpSessionStatus.SUCCESSFUL &&
            existing.sessionCookieId == sid &&
            existing.authRequestId == authReq.authRequestId
        ) {
            respondRedirect("/login/realm/${realm.id}/complete?verifierSessionId=$existingId")
            return
        }
    }

    // --- Load DCQL ------------------------------------------------------
    val dcqlQuery = try {
        loadDcqlQuery(oid4vpCfg.dcqlQueryFile)
    } catch (t: Throwable) {
        respondPlainBadRequest(
            "server_error",
            "failed to load DCQL query: ${t.message}",
        )
        return
    }

    // --- Verifier2 session-create --------------------------------------
    val webhookSecret = randomSecret()
    val webhookUrl = "${deps.config.canonicalIssuer}${oid4vpCfg.webhookCallbackPath}"

    val response = try {
        deps.verifier2Client.createSession(
            verifierBaseUrl = oid4vpCfg.verifierBaseUrl,
            dcqlQuery = dcqlQuery,
            webhookUrl = webhookUrl,
            webhookSecret = webhookSecret,
            rpId = oid4vpCfg.rpId,
        )
    } catch (e: Verifier2ClientException) {
        respondPlainBadRequest(
            "server_error",
            "verifier-api2 session create failed (${e.code})",
        )
        return
    }

    // --- Persist mirror session ----------------------------------------
    val vpSession = VpSession(
        verifierSessionId = response.sessionId,
        realmId = realm.id,
        authRequestId = authReq.authRequestId,
        sessionCookieId = sid,
        webhookSecret = webhookSecret,
        status = VpSessionStatus.PENDING,
        capturedCredential = null,
    )
    deps.vpSessionStore.put(response.sessionId, vpSession)

    // Stamp chosenRealmId + activeVpSessionId so the recovery path on refresh
    // can find an already-SUCCESSFUL session without scanning the whole store.
    deps.authRequestStore.update(sid) { current ->
        current.copy(
            chosenRealmId = realm.id,
            activeVpSessionId = response.sessionId,
        )
    }

    // --- Render QR page -------------------------------------------------
    // bootstrapAuthorizationRequestUrl is present for cross-device (which is
    // what verifier-api2 returns for our `flow_type=cross_device`); fall back
    // to fullAuthorizationRequestUrl if null so cross/same-device degrade
    // gracefully.
    val qrPayload = response.bootstrapAuthorizationRequestUrl
        ?: response.fullAuthorizationRequestUrl
    respondVpQrPage(
        qrPayloadUrl = qrPayload,
        deepLink = response.fullAuthorizationRequestUrl,
        verifierSessionId = response.sessionId,
        statusUrl = "/login/realm/${realm.id}/status?verifierSessionId=${response.sessionId}",
        completeUrl = "/login/realm/${realm.id}/complete?verifierSessionId=${response.sessionId}",
    )
}

/**
 * Registers `GET /login/realm/{realmId}/status?verifierSessionId=…` — the
 * polling endpoint used by the QR page JS. Returns `{status: "..."}` only;
 * never returns credential data.
 */
fun Route.vpStatusRoutes(deps: AuthOpDeps) {
    get("/login/realm/{realmId}/status") {
        val realmId = call.parameters.getOrFail("realmId")
        val verifierSessionId = call.request.queryParameters["verifierSessionId"]
            ?: return@get call.respond(
                HttpStatusCode.BadRequest,
                mapOf("error" to "invalid_request", "description" to "missing verifierSessionId"),
            )

        val vpSession = deps.vpSessionStore.get(verifierSessionId)
            ?: return@get call.respond(
                HttpStatusCode.NotFound,
                mapOf("error" to "not_found", "description" to "unknown verifierSessionId"),
            )

        // Realm id in the URL MUST match the session's stored realm. Mostly a
        // paranoia check — a mis-routed client shouldn't be able to inspect a
        // session whose realm doesn't match the URL context.
        if (vpSession.realmId != realmId) {
            return@get call.respond(
                HttpStatusCode.NotFound,
                mapOf("error" to "not_found", "description" to "unknown verifierSessionId"),
            )
        }

        // Cookie-binding check. A leaked verifierSessionId is useless without
        // the matching browser's sid cookie. 403 (not 404) because we know the
        // session exists; revealing that lets us return a clearer diagnostic
        // without changing the leak surface (the session id was already in the
        // request).
        val sid = call.request.cookies["sid"]
        if (sid == null || sid != vpSession.sessionCookieId) {
            return@get call.respond(
                HttpStatusCode.Forbidden,
                mapOf("error" to "forbidden", "description" to "session binding mismatch"),
            )
        }

        call.respond(HttpStatusCode.OK, mapOf("status" to vpSession.status.name))
    }
}

// ---- helpers --------------------------------------------------------------

/**
 * Read and parse the realm's DCQL query JSON file. The content is passed
 * through byte-for-byte to verifier-api2, so the file must be a JSON object
 * (DCQL top-level is `{credentials: [...], credential_sets: [...]}`). An
 * empty file or a non-object top-level is a config bug — caller surfaces it
 * as `server_error`.
 */
private fun loadDcqlQuery(path: String): JsonObject {
    val bytes = Files.readAllBytes(Paths.get(path))
    val parsed = Json.parseToJsonElement(bytes.decodeToString())
    return parsed as? JsonObject
        ?: error("DCQL file '$path' top-level is not a JSON object")
}

/**
 * 256-bit URL-safe random secret (unpadded Base64URL). Used as the
 * webhook shared secret passed to verifier-api2 at session-create and
 * re-presented on the inbound webhook POST (Task 18).
 */
private val secureRandom = SecureRandom()
private fun randomSecret(): String {
    val bytes = ByteArray(32)
    secureRandom.nextBytes(bytes)
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
}

/**
 * Plain 400 mirrors the one in LoginRoutes / OidcCallbackRoutes — kept
 * private per-file to avoid a shared internal helper becoming a junk
 * drawer. Used when we cannot classify the failure into an OidcError that
 * can redirect back to the RP (no trusted redirect_uri context).
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
