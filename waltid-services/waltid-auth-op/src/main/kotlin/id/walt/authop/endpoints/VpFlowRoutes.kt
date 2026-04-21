@file:OptIn(ExperimentalTime::class)

package id.walt.authop.endpoints

import id.walt.authop.AuthOpDeps
import id.walt.authop.claims.ClaimMapper
import id.walt.authop.claims.SubDerivation
import id.walt.authop.config.RealmConfig
import id.walt.authop.domain.AuthRequest
import id.walt.authop.domain.CapturedCredential
import id.walt.authop.domain.Session
import id.walt.authop.domain.VpSession
import id.walt.authop.domain.VpSessionStatus
import id.walt.authop.errors.OidcError
import id.walt.authop.errors.respondOidcError
import id.walt.authop.templates.respondVpQrPage
import id.walt.authop.upstream.Verifier2ClientException
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.util.getOrFail
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.nio.file.Paths
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

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

    // --- Build DCQL -----------------------------------------------------
    // Static file (legacy) or dynamic composition from the realm scope
    // catalog ∩ the RP's requested scopes, whichever the realm declared.
    val dcqlQuery = try {
        buildDcqlQuery(oid4vpCfg, authReq.scope)
    } catch (t: Throwable) {
        respondPlainBadRequest(
            "server_error",
            "failed to build DCQL query: ${t.message}",
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
/**
 * JSON counterpart of `GET /login/realm/{id}` for OID4VP realms. Creates a
 * fresh verifier-api2 session and returns the QR payload + polling URLs as
 * JSON rather than rendering the full VpQrPage. Used by the redesigned
 * `/login` glass page to render the wallet UX inline without navigating
 * away to the standalone QR view.
 *
 * Scope-matches vpRealmKickoff's preconditions (sid + AuthRequest + realm +
 * client.allowedRealms) but surfaces failures as JSON 400s so the caller
 * can render an error in-place.
 */
fun Route.vpKickoffJsonRoutes(deps: AuthOpDeps) {
    post("/login/realm/{realmId}/kickoff") {
        val realmId = call.parameters.getOrFail("realmId")

        val sid = call.request.cookies["sid"]
            ?: return@post call.respondJsonBadRequest("invalid_request", "missing sid cookie")

        val authReq = deps.authRequestStore.get(sid)
            ?: return@post call.respondJsonBadRequest("invalid_request", "auth request not found")

        val client = deps.clientRegistry[authReq.clientId]
            ?: return@post call.respondJsonBadRequest("invalid_request", "client no longer registered")

        val allowed = client.allowedRealms.takeIf { it.isNotEmpty() }
        if (allowed != null && realmId !in allowed) {
            return@post call.respondJsonBadRequest("access_denied", "realm '$realmId' not allowed for this client")
        }

        val realm = deps.realmRegistry[realmId]
            ?: return@post call.respondJsonBadRequest("invalid_request", "unknown realm '$realmId'")

        val oid4vpCfg = realm.oid4vp
            ?: return@post call.respondJsonBadRequest("invalid_request", "realm '$realmId' is not an oid4vp realm")

        val dcqlQuery = try {
            buildDcqlQuery(oid4vpCfg, authReq.scope)
        } catch (t: Throwable) {
            return@post call.respondJsonBadRequest("server_error", "DCQL build failed: ${t.message}")
        }

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
            return@post call.respondJsonBadRequest("server_error", "verifier-api2 create failed: ${e.code}")
        }

        deps.vpSessionStore.put(
            response.sessionId,
            VpSession(
                verifierSessionId = response.sessionId,
                realmId = realm.id,
                authRequestId = authReq.authRequestId,
                sessionCookieId = sid,
                webhookSecret = webhookSecret,
                status = VpSessionStatus.PENDING,
                capturedCredential = null,
            )
        )
        deps.authRequestStore.update(sid) { current ->
            current.copy(
                chosenRealmId = realm.id,
                activeVpSessionId = response.sessionId,
            )
        }

        val qrPayload = response.bootstrapAuthorizationRequestUrl ?: response.fullAuthorizationRequestUrl
        call.respond(
            kotlinx.serialization.json.buildJsonObject {
                put("qrPayloadUrl", JsonPrimitive(qrPayload))
                put("deepLink", JsonPrimitive(response.fullAuthorizationRequestUrl))
                put("verifierSessionId", JsonPrimitive(response.sessionId))
                put("statusUrl", JsonPrimitive("/login/realm/${realm.id}/status?verifierSessionId=${response.sessionId}"))
                put("completeUrl", JsonPrimitive("/login/realm/${realm.id}/complete?verifierSessionId=${response.sessionId}"))
            }
        )
    }
}

private suspend fun ApplicationCall.respondJsonBadRequest(code: String, description: String) {
    respond(
        HttpStatusCode.BadRequest,
        kotlinx.serialization.json.buildJsonObject {
            put("error", JsonPrimitive(code))
            put("description", JsonPrimitive(description))
        }
    )
}

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

/**
 * Registers `GET /login/realm/{realmId}/complete?verifierSessionId=…` — the
 * terminal step of the OID4VP login flow.
 *
 * Invoked by the QR page's polling JS once it observes
 * `{status: "SUCCESSFUL"}` from [vpStatusRoutes]. Consumes the captured
 * credential, derives the OIDC `sub` per the realm's [id.walt.authop.config.SubStrategy],
 * applies [ClaimMapper], mints the OP-level [Session], hydrates the
 * [AuthRequest] with subject + claims, clears the captured credential
 * (security — no retention past consumption), and redirects to `/consent`.
 *
 * ## Failure-mode taxonomy
 *
 * Every failure mode funnels to an HTTP response that EITHER:
 *  - closes the oracle (uniform plain errors) when the caller can't be
 *    routed back to the RP yet (missing query param / cookie / unknown
 *    session / realm mismatch / state inconsistency);
 *  - OR — for the one case where we have enough trust to talk to the RP —
 *    routes an `access_denied` back via [OidcError.AccessDenied] when the
 *    VP was verified-but-unsuccessful (policy did not pass; there's a valid
 *    AuthRequest + redirect_uri in context).
 *
 * Specifically:
 *
 * 1. Missing `verifierSessionId` query param → 400 plain.
 * 2. Missing `sid` cookie → 400 plain.
 * 3. Unknown [VpSession] → 400 plain (same as missing verifierSessionId;
 *    closes the "does this session exist?" oracle with the same shape as
 *    the missing-param branch).
 * 4. Cookie binding mismatch → 403 plain. Another browser cannot redeem.
 * 5. Realm-in-URL != VpSession.realmId → 404 plain.
 * 6. `VpSession.status == SUCCESSFUL` else → `access_denied` back to RP
 *    (we have an AuthRequest + redirect_uri so we CAN route to the RP).
 *    Description: "presentation did not satisfy requirements".
 * 7. `VpSession.capturedCredential == null` on SUCCESSFUL → 500 plain
 *    (state inconsistency — never expected; Task 18 guarantees capture
 *    on SUCCESSFUL).
 *
 * ## Security invariants
 *
 * - **Cookie binding** is the first security check after query-param
 *   presence. Prevents cross-browser session redemption.
 * - **Captured credential cleared on consumption.** Even on the happy path
 *   we write back a null `capturedCredential` so a subsequent replay of
 *   the URL finds nothing to consume. Defence in depth: the VpSession
 *   store TTL also expires the entry, but explicit clear means even a
 *   back-button refresh within TTL can't re-mint claims.
 * - **`acr=urn:walt:vp` and `amr=["swk"]`** stamped on [Session] AND
 *   merged into [AuthRequest.claims]. `swk` = "software key" (the wallet's
 *   key material lives in software, not hardware; a realm that wants
 *   `hwk` for a hardware-backed wallet can project through claim
 *   mapping, but v1 is conservative).
 * - **Realm claim is namespaced** under `<canonicalIssuer>/realm` so it
 *   cannot collide with a reserved OIDC claim name.
 * - **Session.upstreamIdToken = null for VP realms.** There's no upstream
 *   OIDC ID token to unwind on logout.
 */
fun Route.vpCompleteRoutes(deps: AuthOpDeps) {
    get("/login/realm/{realmId}/complete") {
        call.handleVpComplete(deps)
    }
}

/** Implementation of the /complete handler; call-site kept tiny for test-wiring parity with other files. */
private suspend fun ApplicationCall.handleVpComplete(deps: AuthOpDeps) {
    val realmId = parameters.getOrFail("realmId")

    // 1. verifierSessionId query param
    val verifierSessionId = request.queryParameters["verifierSessionId"]
    if (verifierSessionId.isNullOrBlank()) {
        return respondPlainBadRequest("invalid_request", "missing verifierSessionId")
    }

    // 2. sid cookie
    val sid = request.cookies["sid"]
    if (sid.isNullOrBlank()) {
        return respondPlainBadRequest("invalid_request", "missing sid cookie")
    }

    // 3. VpSession lookup
    val vpSession = deps.vpSessionStore.get(verifierSessionId)
        ?: return respondPlainBadRequest("invalid_request", "unknown verifierSessionId")

    // 4. Cookie-binding check. The session was opened by a specific browser;
    // a different browser cannot redeem its captured credential. 403 — we
    // know the session exists but the caller isn't its owner.
    if (sid != vpSession.sessionCookieId) {
        return respondPlainForbidden("sid cookie does not match VpSession")
    }

    // 5. Realm-in-URL cross-check. A session opened for realm `vp` must be
    // completed at `/login/realm/vp/complete`, not some other realm's URL.
    // 404 — hide the session's existence under the wrong realm URL.
    if (vpSession.realmId != realmId) {
        return respondPlainNotFound("unknown verifierSessionId")
    }

    // Load AuthRequest before status check: even the "redirect to RP"
    // branch needs AuthRequest.redirect_uri + state.
    val authReq = deps.authRequestStore.get(vpSession.authRequestId)
        ?: return respondPlainServerError(
            "auth request ${vpSession.authRequestId} not found; VP outlived the auth request",
        )

    // 6. Status gate. UNSUCCESSFUL (or still-PENDING) means the wallet
    // returned but policy didn't pass. We have an AuthRequest with a
    // trusted redirect_uri, so we CAN route access_denied back to the RP
    // per the plan's behaviour spec step 6.
    if (vpSession.status != VpSessionStatus.SUCCESSFUL) {
        return respondOidcError(
            OidcError.AccessDenied("presentation did not satisfy requirements"),
            authReq,
        )
    }

    // 7. Captured credential invariant. SUCCESSFUL without a captured
    // credential is a webhook bug — Task 18 guarantees capture on
    // SUCCESSFUL. 500 so the operator's alerting catches it.
    val captured = vpSession.capturedCredential
        ?: return respondPlainServerError(
            "state inconsistency: SUCCESSFUL VpSession has no capturedCredential",
        )

    // 8. Realm config lookup. The realm was validated at kickoff; a
    // disappearance now is a mid-flight config reload, treated the same
    // as OIDC callback's disappeared-realm path.
    val realm = deps.realmRegistry[realmId]
        ?: return respondPlainServerError("realm '$realmId' disappeared")

    // 9-10. Pick the first verified credential from the first entry of
    // presentedCredentials. Shape (per verifier-api2 Verification2Session):
    //   Map<String, List<DigitalCredential>> → JsonObject { name: [ {...}, ... ] }
    //
    // A DigitalCredential carries a `credentialData: JsonObject` which is
    // the CLAIM payload — that's what ClaimMapper / SubDerivation apply
    // to. Fall back to the whole credential object if `credentialData` is
    // absent (defensive: verifier-api2 format changes).
    val firstCredential = firstCredentialData(captured)
        ?: return respondPlainServerError(
            "presentedCredentials contained no DigitalCredential with credentialData",
        )

    // 11. Apply claim mapping.
    val mappedClaims: Map<String, JsonElement> =
        ClaimMapper.apply(firstCredential, realm.claimMapping)

    // 12. Derive sub.
    val strategy = realm.subStrategy
        ?: return respondPlainServerError(
            "realm '$realmId' has no subStrategy — OID4VP realms must declare one",
        )
    val sub = try {
        SubDerivation.derive(
            strategy = strategy,
            realmId = realmId,
            credential = firstCredential,
            sourceClaimNames = realm.subSourceClaims,
        )
    } catch (iae: IllegalArgumentException) {
        // CREDENTIAL_SUBJECT_ID with no id; that's an operator/wallet
        // misalignment. Redirect the RP back with access_denied — the
        // user's wallet simply cannot satisfy this realm.
        return respondOidcError(
            OidcError.AccessDenied("cannot derive sub: ${iae.message}"),
            authReq,
        )
    }

    // 13. Build final claims: mapped + namespaced realm + acr + amr.
    // Overlay order mirrors the OIDC callback path — mapped claims first,
    // then operator-immutable meta claims on top so a realm's claim_mapping
    // cannot override acr/amr/realm.
    val realmClaimName = "${deps.config.canonicalIssuer}/realm"
    val finalClaims: Map<String, JsonElement> = mappedClaims + mapOf(
        realmClaimName to JsonPrimitive(realmId),
        "acr" to JsonPrimitive("urn:walt:vp"),
        "amr" to buildJsonArray { add("swk") },
    )

    // 14. Session creation. Keyed by sid; upstreamIdToken=null (no OIDC
    // chain on VP realms).
    val session = Session(
        sessionId = sid,
        subject = sub,
        realmId = realmId,
        amr = listOf("swk"),
        acr = "urn:walt:vp",
        authTime = Clock.System.now(),
        upstreamIdToken = null,
    )
    deps.sessionStore.put(sid, session)

    // 15. AuthRequest hydration.
    deps.authRequestStore.update(authReq.authRequestId) { current ->
        current.copy(
            subject = sub,
            claims = finalClaims,
        )
    }

    // 16. Clear captured credential on successful consumption. This is
    // security-critical: we don't retain sensitive credential bytes past
    // the point they've been mapped to claims.
    deps.vpSessionStore.update(verifierSessionId) { it.copy(capturedCredential = null) }

    // 17. Hand the user off to the next step. When the glass /login page
    // polls /complete with Accept: application/json it wants to swap the
    // panel in-place instead of navigating away — return a small JSON
    // envelope describing where the SPA should go next. Non-JSON callers
    // still get the classic 302 redirect so /login/realm/{id}/complete
    // stays a valid standalone entry point.
    val next = if (deps.passkeyService != null) "register_passkey" else "consent"
    val nextUrl = if (next == "register_passkey") "/register-passkey" else "/consent"
    val wantsJson = request.headers["Accept"]?.contains("application/json") == true
    if (wantsJson) {
        respond(
            kotlinx.serialization.json.buildJsonObject {
                put("next", JsonPrimitive(next))
                put("nextUrl", JsonPrimitive(nextUrl))
                put("sub", JsonPrimitive(sub))
                // displayName lets the inline register panel greet the user
                // with their wallet-asserted name without a second fetch.
                put("displayName", JsonPrimitive(
                    listOfNotNull(
                        (mappedClaims["given_name"] as? JsonPrimitive)?.contentOrNull,
                        (mappedClaims["family_name"] as? JsonPrimitive)?.contentOrNull,
                    ).joinToString(" ").ifBlank { sub }
                ))
            }
        )
    } else {
        respondRedirect(nextUrl)
    }
}

/**
 * Extract the first credential's `credentialData` (the JSON claim body)
 * from verifier-api2's `presentedCredentials` map-of-arrays.
 *
 * Structure per `Verification2Session.presentedCredentials`:
 * `Map<String, List<DigitalCredential>>`. Serialized on the wire as a
 * JSON object whose values are arrays of credential objects, each with a
 * `credentialData` field carrying the VC claim body.
 *
 * Policy: "first credential from the first entry". With a `LinkedHashMap`
 * the underlying verifier iterates in insertion order, so first-entry is
 * deterministic. If the wallet sent multiple credentials under the same
 * DCQL id, we take index 0 — multi-credential realms should project
 * through DCQL's credential_sets rather than rely on ordering inside a
 * single entry.
 *
 * Returns null if there's nothing usable (empty map, empty list, or a
 * credential element without a `credentialData` object). Callers treat
 * null as a 500 state inconsistency.
 */
private fun firstCredentialData(captured: CapturedCredential): JsonObject? {
    val credsByDcqlId = captured.presentedCredentials
    if (credsByDcqlId.isEmpty()) return null
    // first-entry is well-defined on a serialized JsonObject — Json preserves
    // insertion order (LinkedHashMap underneath).
    val firstEntry = credsByDcqlId.entries.firstOrNull() ?: return null
    val array = firstEntry.value as? JsonArray ?: return null
    val firstCred = array.firstOrNull() as? JsonObject ?: return null
    // credentialData is the field verifier-api2's DigitalCredential
    // exposes for the VC's claim payload.
    val claimData = firstCred["credentialData"] as? JsonObject
    if (claimData != null) return claimData
    // Defensive fallback: if credentialData is missing (stub credential
    // shape in tests, or a future verifier serialization change), treat
    // the whole credential object as the claim source. This matches the
    // existing "preserve the shape" posture of ClaimMapper — paths that
    // don't resolve silently drop.
    return firstCred
}

/**
 * Registers `POST /login/realm/{realmId}/webhook` — the verifier-api2 session
 * notification callback. verifier-api2 POSTs a JSON body of shape
 * `{target, event, session}` where `session` is a serialized
 * `Verification2Session`. The POST is authenticated via
 * `Authorization: Bearer <webhookSecret>` — the same secret this OP minted at
 * VP kickoff time and handed to verifier-api2 on session-create.
 *
 * Verified against verifier-api2 source (not only the doc):
 *  - Bearer scheme: `WebhookNotifier.kt:28-30` calls `bearerAuth(config.bearerToken!!)`
 *    against `VerificationSessionWebhookNotification.bearerToken`
 *    (`waltid-libraries/web/waltid-ktor-notifications-core/.../KtorSessionNotifications.kt:21`).
 *  - Envelope shape `{target,event,session}`:
 *    `waltid-libraries/web/waltid-ktor-notifications-core/.../KtorSessionUpdate.kt:7-11`.
 *  - Event name literal `policy_results_available`:
 *    `waltid-libraries/protocols/waltid-openid4vp-verifier/.../SessionEvent.kt:10`.
 *  - Session status literals (`SUCCESSFUL`, `UNSUCCESSFUL`, `FAILED`, `EXPIRED`):
 *    `waltid-libraries/protocols/waltid-openid4vp-verifier/.../Verification2Session.kt:147-177`
 *    (enum `VerificationSessionStatus`).
 *  - Session fields `id`, `status`, `presentedCredentials`, `presentedPresentations`:
 *    `waltid-libraries/protocols/waltid-openid4vp-verifier/.../Verification2Session.kt:33,58,99,100`.
 *
 * ## Security invariants
 *
 *  - **Secret compared constant-time** via [MessageDigest.isEqual] — never `==`.
 *  - **Unknown-session and wrong-secret both return 401** (not 404). This
 *    removes the "does this verifier sessionId exist on this OP?" oracle an
 *    unauthenticated attacker could otherwise probe. Cost: a stale/replayed
 *    request from the real verifier with a timed-out session id gets 401
 *    instead of 404; the verifier's own logs record the delivery failure.
 *  - **Capture only on `policy_results_available`.** Earlier events
 *    (attempted_presentation, parsed_presentation_available, etc.) do not
 *    carry final credential data. Other events → 200 without mutation so the
 *    verifier doesn't retry.
 *  - **No logging of credential data, the session body, or the secret.** The
 *    webhook body is highly sensitive (signed credentials + VP). We log
 *    nothing at all from this handler on the happy path; callers rely on the
 *    status endpoint to observe transitions.
 *  - **Status transition is always recorded.** Even an UNSUCCESSFUL final
 *    status is persisted so the status endpoint can flip PENDING → UNSUCCESSFUL
 *    for the polling QR page; credential capture is gated on SUCCESSFUL
 *    (a UNSUCCESSFUL capture would be meaningless and is written as null
 *    below).
 */
fun Route.vpWebhookRoutes(deps: AuthOpDeps) {
    post("/login/realm/{realmId}/webhook") {
        call.handleVpWebhook(deps)
    }
}

/**
 * Uniform unauthorized response. Both "wrong Bearer secret" and
 * "unknown session id" route through this to close the enumeration oracle.
 * Body is a static JSON error with no session-specific detail.
 */
private suspend fun ApplicationCall.respondWebhookAuthError() {
    respond(HttpStatusCode.Unauthorized, mapOf("error" to "unauthorized"))
}

/**
 * Constant-time byte-array equality. [MessageDigest.isEqual] does not
 * short-circuit on first mismatch, so comparison time is independent of where
 * the first differing byte sits. Use for any secret comparison.
 *
 * A length mismatch short-circuits here — that's fine: a TLS attacker
 * observing on the wire already sees the Authorization header length. The
 * constant-time guarantee we need is over the CONTENT of equal-length secrets.
 */
private fun constantTimeEqualsBytes(a: ByteArray, b: ByteArray): Boolean {
    if (a.size != b.size) return false
    return MessageDigest.isEqual(a, b)
}

private suspend fun ApplicationCall.handleVpWebhook(deps: AuthOpDeps) {
    // --- 1. Extract bearer secret from Authorization header -------------------
    // verifier-api2's WebhookNotifier sends `Authorization: Bearer <token>`;
    // a missing or non-Bearer header is always 401.
    val authHeader = request.headers["Authorization"]
    if (authHeader == null || !authHeader.startsWith("Bearer ", ignoreCase = true)) {
        return respondWebhookAuthError()
    }
    val suppliedSecret = authHeader.substring("Bearer ".length).trim()
    if (suppliedSecret.isEmpty()) return respondWebhookAuthError()

    // --- 2. Parse body as JSON -----------------------------------------------
    // receiveText() first so we control parsing (no content-negotiation magic
    // on a security-critical path). Malformed JSON → 400 so the verifier has a
    // diagnostic; this doesn't leak session state.
    val rawBody = receiveText()
    val body = try {
        Json.parseToJsonElement(rawBody).jsonObject
    } catch (_: SerializationException) {
        return respond(HttpStatusCode.BadRequest, mapOf("error" to "invalid_body"))
    } catch (_: IllegalArgumentException) {
        // parseToJsonElement throws IAE on type mismatch (e.g. top-level is a JSON array)
        return respond(HttpStatusCode.BadRequest, mapOf("error" to "invalid_body"))
    }

    // --- 3. Extract session envelope -----------------------------------------
    // Shape: {target, event, session}. `session` is the serialized
    // Verification2Session whose `id` we keyed the local VpSession by.
    val sessionObj = body["session"] as? JsonObject
        ?: return respond(HttpStatusCode.BadRequest, mapOf("error" to "missing_session"))
    val verifierSessionId = sessionObj["id"]?.jsonPrimitive?.contentOrNull
        ?: return respond(HttpStatusCode.BadRequest, mapOf("error" to "missing_session_id"))

    // --- 4. Look up VpSession, then constant-time compare secret -------------
    // Unknown-session and wrong-secret BOTH return 401 (uniform response) to
    // close the "does this sessionId exist?" oracle. See kdoc above. We still
    // always do the isEqual() call (even on the unknown-session branch) to
    // keep total handler time similar across branches — defence-in-depth, not
    // a hard constant-time guarantee (JSON parsing time already leaks).
    val vpSession = deps.vpSessionStore.get(verifierSessionId)
    val suppliedBytes = suppliedSecret.toByteArray(Charsets.UTF_8)
    val storedBytes = (vpSession?.webhookSecret ?: "").toByteArray(Charsets.UTF_8)
    val secretOk = constantTimeEqualsBytes(storedBytes, suppliedBytes)
    if (vpSession == null || !secretOk) {
        return respondWebhookAuthError()
    }

    // --- 5. Gate on event type -----------------------------------------------
    // Only `policy_results_available` carries final credential data per
    // SessionEvent.kt:10 and TRANSACTIONAL_VERIFICATION.md ("Final event —
    // credential data cleared after this"). Earlier events we ACK but do not
    // capture — the verifier won't retry, and our VpSession stays PENDING
    // until the final event lands.
    val event = body["event"]?.jsonPrimitive?.contentOrNull
    if (event != "policy_results_available") {
        return respond(HttpStatusCode.OK, mapOf("accepted" to true))
    }

    // --- 6. Map verifier status to our domain enum ---------------------------
    // Verifier2Session.VerificationSessionStatus values relevant here:
    //   SUCCESSFUL  → SUCCESSFUL
    //   UNSUCCESSFUL, FAILED, EXPIRED → UNSUCCESSFUL
    //   any other (UNKNOWN, in-flight) → UNSUCCESSFUL (conservative — we only
    //     reach this branch on the terminal event; an in-flight status there
    //     is a verifier bug, treat as failure).
    val statusStr = sessionObj["status"]?.jsonPrimitive?.contentOrNull
    val mappedStatus = when (statusStr) {
        "SUCCESSFUL" -> VpSessionStatus.SUCCESSFUL
        "UNSUCCESSFUL", "FAILED", "EXPIRED" -> VpSessionStatus.UNSUCCESSFUL
        else -> VpSessionStatus.UNSUCCESSFUL
    }

    // --- 7. Extract credential payload (only on SUCCESSFUL) ------------------
    // Field names verified against Verification2Session.kt:99-100:
    //   var presentedPresentations: Map<String, VerifiablePresentation>?
    //   var presentedCredentials: Map<String, List<DigitalCredential>>?
    // Serialize-through as JsonObject — /complete does JSONPath on them.
    val captured = if (mappedStatus == VpSessionStatus.SUCCESSFUL) {
        val presentedCredentials = sessionObj["presentedCredentials"] as? JsonObject
            ?: JsonObject(emptyMap())
        val presentedPresentations = sessionObj["presentedPresentations"] as? JsonObject
            ?: JsonObject(emptyMap())
        CapturedCredential(
            presentedCredentials = presentedCredentials,
            presentedPresentations = presentedPresentations,
        )
    } else {
        // Preserve existing capture if any; otherwise remain null. An
        // UNSUCCESSFUL final event should not clobber a prior SUCCESSFUL
        // capture (defensive — in practice we only transition once).
        vpSession.capturedCredential
    }

    // --- 8. Transition session -----------------------------------------------
    deps.vpSessionStore.update(verifierSessionId) { current ->
        current.copy(
            status = mappedStatus,
            capturedCredential = captured,
        )
    }

    // --- 9. ACK the verifier -------------------------------------------------
    // 200 with a minimal body so the verifier's WebhookNotifier doesn't
    // interpret a blank/non-JSON response as an error. We intentionally do NOT
    // echo anything from the request body or the VpSession.
    respond(HttpStatusCode.OK, mapOf("accepted" to true))
}

// ---- helpers --------------------------------------------------------------

/**
 * Build the DCQL query sent to verifier-api2 for this realm and authorize
 * request. Supports two modes driven by realm config (RealmRegistry enforces
 * exactly one is present):
 *
 * - **Static file.** [Oid4vpRealmConfig.dcqlQueryFile] path points at a JSON
 *   file read byte-for-byte — pre-scope-catalog behaviour.
 * - **Dynamic compose.** [Oid4vpRealmConfig.scopes] is a catalog; for each
 *   requested scope we union its [id.walt.authop.config.ScopeDefinition.claimPaths]
 *   into the claim list. One credential query is emitted per entry in the realm's
 *   [Oid4vpRealmConfig.vctValues] (each with a single-element `vct_values`), and
 *   they're unioned under a single `credential_sets` option so the wallet can
 *   satisfy the presentation by matching any one. Wallets still prompt once — the
 *   spec's credential_sets semantics mean the wallet chooses a single credential.
 *   Per-VCT queries also work around an EUDI iOS wallet-kit bug where multi-VCT
 *   `vct_values` only ever matches the first entry.
 *
 * Composition rules:
 *  - Requested scopes not in the catalog are silently dropped (OIDC Core §3.1.2.1
 *    — unknown scopes are not a protocol error).
 *  - If no requested scopes survive intersection with the catalog we throw
 *    `IllegalArgumentException` — an empty DCQL query would pass no claims and
 *    is almost certainly an RP config bug worth surfacing.
 *  - Claim paths are de-duplicated; two scopes sharing a path don't produce a
 *    duplicate DCQL claims entry.
 */
private fun buildDcqlQuery(
    cfg: id.walt.authop.config.Oid4vpRealmConfig,
    requestedScopes: List<String>,
): JsonObject {
    if (!cfg.dcqlQueryFile.isNullOrBlank()) {
        val bytes = Files.readAllBytes(Paths.get(cfg.dcqlQueryFile))
        val parsed = Json.parseToJsonElement(bytes.decodeToString())
        return parsed as? JsonObject
            ?: error("DCQL file '${cfg.dcqlQueryFile}' top-level is not a JSON object")
    }

    val catalog = cfg.scopes
    val matched = requestedScopes.filter { it in catalog }
    require(matched.isNotEmpty()) {
        "none of the requested scopes (${requestedScopes.joinToString(",")}) " +
            "are in the realm scope catalog (${catalog.keys.joinToString(",")})"
    }

    // Collect, de-dup, preserve catalog order for deterministic output.
    val seen = LinkedHashSet<List<String>>()
    matched.forEach { scope ->
        catalog[scope]?.claimPaths?.forEach { seen.add(it) }
    }

    val claimsArray = buildJsonArray {
        seen.forEach { path ->
            add(buildJsonObject {
                put("path", buildJsonArray { path.forEach { add(JsonPrimitive(it)) } })
            })
        }
    }
    // Emit one credential query per VCT, then union them under a single
    // credential_sets option. The EUDI iOS wallet-kit (eudi-lib-ios-wallet-kit
    // Openid4VpUtils.swift) only reads the *first* entry of a multi-VCT
    // `vct_values` array when resolving credentials, so a wallet holding any
    // VCT other than the first would see "document not available". Per-VCT
    // queries sidestep that — each query is single-valued, and credential_sets
    // lets the wallet satisfy the presentation by matching *any one* of them.
    val queryIds = cfg.vctValues.mapIndexed { idx, _ -> "pid_$idx" }
    val credentialsArray = buildJsonArray {
        cfg.vctValues.forEachIndexed { idx, vct ->
            add(buildJsonObject {
                put("id", JsonPrimitive(queryIds[idx]))
                put("format", JsonPrimitive(cfg.credentialFormat))
                put("meta", buildJsonObject {
                    put("vct_values", buildJsonArray { add(JsonPrimitive(vct)) })
                })
                put("claims", claimsArray)
            })
        }
    }
    val credentialSets = buildJsonArray {
        add(buildJsonObject {
            put("required", JsonPrimitive(true))
            put("options", buildJsonArray {
                queryIds.forEach { id ->
                    add(buildJsonArray { add(JsonPrimitive(id)) })
                }
            })
        })
    }
    return buildJsonObject {
        put("credentials", credentialsArray)
        put("credential_sets", credentialSets)
    }
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

/** Plain 403 used by /complete cookie-binding mismatch. */
private suspend fun ApplicationCall.respondPlainForbidden(description: String) {
    val body = buildString {
        append("<!doctype html><html><body><h1>Forbidden</h1>")
        append("<p>").append(description).append("</p>")
        append("</body></html>")
    }
    respondText(body, ContentType.Text.Html, HttpStatusCode.Forbidden)
}

/** Plain 404 used by /complete when the URL's realm doesn't match the VpSession's realm. */
private suspend fun ApplicationCall.respondPlainNotFound(description: String) {
    val body = buildString {
        append("<!doctype html><html><body><h1>Not found</h1>")
        append("<p>").append(description).append("</p>")
        append("</body></html>")
    }
    respondText(body, ContentType.Text.Html, HttpStatusCode.NotFound)
}

/** Plain 500 used by /complete for state-inconsistency branches. */
private suspend fun ApplicationCall.respondPlainServerError(description: String) {
    val body = buildString {
        append("<!doctype html><html><body><h1>Server error</h1>")
        append("<p>").append(description).append("</p>")
        append("</body></html>")
    }
    respondText(body, ContentType.Text.Html, HttpStatusCode.InternalServerError)
}
