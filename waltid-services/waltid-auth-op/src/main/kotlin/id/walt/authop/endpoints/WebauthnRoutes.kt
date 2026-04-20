@file:OptIn(ExperimentalTime::class)

package id.walt.authop.endpoints

import com.github.benmanes.caffeine.cache.Caffeine
import com.yubico.webauthn.AssertionRequest
import com.yubico.webauthn.data.PublicKeyCredentialCreationOptions
import id.walt.authop.AuthOpDeps
import id.walt.authop.domain.Session
import id.walt.authop.passkey.PasskeyService
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.html.respondHtml
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.html.a
import kotlinx.html.body
import kotlinx.html.button
import kotlinx.html.div
import kotlinx.html.h1
import kotlinx.html.head
import kotlinx.html.id
import kotlinx.html.meta
import kotlinx.html.p
import kotlinx.html.script
import kotlinx.html.title
import kotlinx.html.unsafe
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.util.concurrent.TimeUnit
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * WebAuthn ceremony endpoints for the auth-op citizens realm.
 *
 * All four endpoints return 404 if [AuthOpDeps.passkeyService] is null
 * (feature disabled via missing `passkey` config block).
 *
 * ## Registration (post-VP)
 *
 * The wallet VP flow has just completed (`VpFlowRoutes.handleVpComplete`
 * stashed the derived sub in a Session and redirected here instead of
 * `/consent`).
 *
 * 1. `POST /webauthn/register/begin` — body: `{sub, displayName}`; response:
 *    `PublicKeyCredentialCreationOptions` JSON for the browser.
 *    Server stashes the request options keyed by a generated `flow_id`.
 * 2. `POST /webauthn/register/complete` — body: `{flow_id, sub, displayName,
 *    attestation}`; server verifies via Yubico, persists the credential.
 *
 * ## Authentication (returning user, conditional UI)
 *
 * 3. `POST /webauthn/login/begin` — response: `PublicKeyCredentialRequestOptions`
 *    with empty `allowCredentials` so the browser's discoverable-credential
 *    flow can surface any passkey for the RP. Stashes the request keyed by
 *    a generated `flow_id`.
 * 4. `POST /webauthn/login/complete` — body: `{flow_id, assertion}`; server
 *    verifies, resolves sub, returns `{sub}` on success so the caller can
 *    wire the sub into the existing Session/auth-code mint.
 *
 * State is held in short-lived Caffeine caches rather than the session
 * cookie: the flow_id acts as a server-issued nonce that the browser echoes
 * back on /complete. TTL is 5 minutes to cover slow platform authenticator
 * UIs (hardware keys, user PIN entry).
 */
fun Route.webauthnRoutes(deps: AuthOpDeps) {
    val registrationState = Caffeine.newBuilder()
        .expireAfterWrite(5, TimeUnit.MINUTES)
        .maximumSize(10_000)
        .build<String, RegistrationFlowState>()

    val loginState = Caffeine.newBuilder()
        .expireAfterWrite(5, TimeUnit.MINUTES)
        .maximumSize(10_000)
        .build<String, AssertionRequest>()

    route("/webauthn") {
        route("register") {
            post("begin") { call.handleRegisterBegin(deps.passkeyService, registrationState) }
            post("complete") { call.handleRegisterComplete(deps.passkeyService, registrationState) }
        }
        route("login") {
            post("begin") { call.handleLoginBegin(deps.passkeyService, loginState) }
            post("complete") { call.handleLoginComplete(deps, loginState) }
        }
        // Minimal GET for operator sanity (curl from the server host): is the
        // feature enabled? Doesn't leak registered credential counts.
        get("status") {
            val enabled = deps.passkeyService != null
            call.respond(buildJsonObject { put("enabled", enabled) })
        }
    }
    // Post-VP enrollment page. `/register-passkey` is the destination that
    // VpFlowRoutes.handleVpComplete redirects to when the passkey feature is
    // enabled. The page resolves the current sub + display name from the
    // sid-bound session, drives `navigator.credentials.create()`, and
    // finally navigates to `/consent` regardless of enrollment outcome so
    // the user is never stuck on failure.
    get("/register-passkey") { call.handleRegisterPasskeyPage(deps) }
}

private data class RegistrationFlowState(
    val sub: String,
    val displayName: String,
    val options: PublicKeyCredentialCreationOptions,
)

private suspend fun ApplicationCall.handleRegisterBegin(
    service: PasskeyService?,
    state: com.github.benmanes.caffeine.cache.Cache<String, RegistrationFlowState>,
) {
    if (service == null) return respond(HttpStatusCode.NotFound, "passkey feature disabled")
    val body = Json.parseToJsonElement(receiveText()).let {
        it as? JsonObject ?: return respondPlainBadRequest("invalid_request", "body must be JSON object")
    }
    val sub = body["sub"]?.let { it.toString().trim('"') }.orEmpty()
    val displayName = body["displayName"]?.let { it.toString().trim('"') }.orEmpty()
    if (sub.isBlank()) return respondPlainBadRequest("invalid_request", "sub required")

    val options = service.startRegistration(sub, displayName)
    val flowId = java.util.UUID.randomUUID().toString()
    state.put(flowId, RegistrationFlowState(sub, displayName, options))

    // Yubico provides `.toCredentialsCreateJson()` which returns the WebAuthn
    // spec's `PublicKeyCredentialCreationOptions` JSON as a String. Wrap it
    // in an envelope that also carries the flow_id the client must echo
    // on /complete.
    respondText(
        """{"flow_id":"$flowId","options":${options.toCredentialsCreateJson()}}""",
        io.ktor.http.ContentType.Application.Json,
    )
}

private suspend fun ApplicationCall.handleRegisterComplete(
    service: PasskeyService?,
    state: com.github.benmanes.caffeine.cache.Cache<String, RegistrationFlowState>,
) {
    if (service == null) return respond(HttpStatusCode.NotFound, "passkey feature disabled")
    val body = Json.parseToJsonElement(receiveText()) as? JsonObject
        ?: return respondPlainBadRequest("invalid_request", "body must be JSON object")
    val flowId = body["flow_id"]?.toString()?.trim('"').orEmpty()
    if (flowId.isBlank()) return respondPlainBadRequest("invalid_request", "flow_id required")
    val flow = state.getIfPresent(flowId)
        ?: return respondPlainBadRequest("invalid_request", "flow_id unknown or expired")
    state.invalidate(flowId)

    val attestation = body["attestation"]
        ?: return respondPlainBadRequest("invalid_request", "attestation required")

    try {
        service.finishRegistration(
            sub = flow.sub,
            displayName = flow.displayName,
            requestOptions = flow.options,
            responseJson = attestation.toString(),
        )
    } catch (t: Throwable) {
        return respondPlainBadRequest("registration_failed", t.message ?: "attestation rejected")
    }
    respond(HttpStatusCode.OK, buildJsonObject { put("status", "registered") })
}

private suspend fun ApplicationCall.handleLoginBegin(
    service: PasskeyService?,
    state: com.github.benmanes.caffeine.cache.Cache<String, AssertionRequest>,
) {
    if (service == null) return respond(HttpStatusCode.NotFound, "passkey feature disabled")
    val request = service.startAssertion()
    val flowId = java.util.UUID.randomUUID().toString()
    state.put(flowId, request)

    respondText(
        """{"flow_id":"$flowId","options":${request.toCredentialsGetJson()}}""",
        io.ktor.http.ContentType.Application.Json,
    )
}

private suspend fun ApplicationCall.handleLoginComplete(
    deps: AuthOpDeps,
    state: com.github.benmanes.caffeine.cache.Cache<String, AssertionRequest>,
) {
    val service = deps.passkeyService
        ?: return respond(HttpStatusCode.NotFound, "passkey feature disabled")
    val body = Json.parseToJsonElement(receiveText()) as? JsonObject
        ?: return respondPlainBadRequest("invalid_request", "body must be JSON object")
    val flowId = body["flow_id"]?.toString()?.trim('"').orEmpty()
    if (flowId.isBlank()) return respondPlainBadRequest("invalid_request", "flow_id required")
    val assertionRequest = state.getIfPresent(flowId)
        ?: return respondPlainBadRequest("invalid_request", "flow_id unknown or expired")
    state.invalidate(flowId)

    val assertion = body["assertion"]
        ?: return respondPlainBadRequest("invalid_request", "assertion required")

    val resolution = try {
        service.finishAssertion(assertionRequest, assertion.toString())
    } catch (t: Throwable) {
        return respondPlainBadRequest("assertion_failed", t.message ?: "assertion rejected")
    }

    // Bind the assertion outcome to the current AuthRequest via the sid
    // cookie (authRequestId == sid, see AuthorizeRoutes). Without a sid
    // we still return the sub so the client can handle standalone passkey
    // tests, but no Session is minted and the caller won't have a /consent
    // redirect to follow.
    val sid: String? = this.request.cookies["sid"]
    if (sid.isNullOrBlank()) {
        return respond(
            HttpStatusCode.OK,
            buildJsonObject {
                put("sub", resolution.sub)
                put("displayName", resolution.displayName)
            },
        )
    }

    deps.authRequestStore.get(sid)
        ?: return respondPlainBadRequest("invalid_request", "no active AuthRequest for sid")

    // Hydrate the AuthRequest with the sub minted by the passkey ceremony.
    // We don't have fresh claim_mapping inputs here (the wallet wasn't
    // re-presented), so we reuse the sub as both subject and the minimal
    // claim set. Downstream consent / token routes only need `sub`.
    deps.authRequestStore.update(sid) { current ->
        current.copy(
            subject = resolution.sub,
            claims = mapOf("sub" to JsonPrimitive(resolution.sub)),
        )
    }

    val session = Session(
        sessionId = sid,
        subject = resolution.sub,
        realmId = "citizens",
        amr = listOf("hwk"),
        acr = "urn:walt:passkey",
        authTime = Clock.System.now(),
        upstreamIdToken = null,
    )
    deps.sessionStore.put(sid, session)

    respond(
        HttpStatusCode.OK,
        buildJsonObject {
            put("sub", resolution.sub)
            put("displayName", resolution.displayName)
            put("redirect", "/consent")
        },
    )
}

private suspend fun ApplicationCall.handleRegisterPasskeyPage(deps: AuthOpDeps) {
    if (deps.passkeyService == null) {
        return respondRedirect("/consent")
    }
    val sid = this.request.cookies["sid"]
    if (sid.isNullOrBlank()) {
        return respondPlainBadRequest("invalid_request", "missing sid cookie")
    }
    val session = deps.sessionStore.get(sid)
    if (session == null) {
        return respondRedirect("/login")
    }
    val authReq = deps.authRequestStore.get(sid)
    val givenName = (authReq?.claims?.get("given_name") as? JsonPrimitive)?.contentOrNull
    val familyName = (authReq?.claims?.get("family_name") as? JsonPrimitive)?.contentOrNull
    val displayName = listOfNotNull(givenName, familyName).joinToString(" ").ifBlank { session.subject }

    respondHtml(HttpStatusCode.OK) {
        head {
            meta(charset = "utf-8")
            title { +"Register a passkey" }
        }
        body {
            attributes["data-sub"] = session.subject
            attributes["data-display-name"] = displayName
            h1 { +"Register a passkey for faster login" }
            p {
                +"You've verified with your wallet. Register a passkey on this device so next time you can sign in with just your biometric or PIN — no wallet scan needed."
            }
            div {
                id = "passkey-actions"
                button {
                    id = "register-btn"
                    +"Register passkey"
                }
                +" "
                a(href = "/consent") { +"Skip for now" }
            }
            div { id = "passkey-status" }
            script { unsafe { +WEBAUTHN_REGISTER_JS } }
        }
    }
}

// Browser-side WebAuthn registration. Uses manual base64url <-> ArrayBuffer
// conversion so the code works on any WebAuthn-capable browser, not just
// those that ship PublicKeyCredential.parseCreationOptionsFromJSON (added
// to Chrome/Safari in late 2023; not yet on all shipped versions).
private val WEBAUTHN_REGISTER_JS = """
(function() {
  var body = document.body;
  var sub = body.getAttribute('data-sub');
  var displayName = body.getAttribute('data-display-name');
  var status = document.getElementById('passkey-status');
  var btn = document.getElementById('register-btn');

  function skip() { window.location.replace('/consent'); }
  function show(msg) { console.log('[passkey]', msg); if (status) status.textContent = msg; }

  function b64urlToBuf(s) {
    s = String(s).replace(/-/g, '+').replace(/_/g, '/');
    while (s.length % 4) s += '=';
    var bin = atob(s);
    var a = new Uint8Array(bin.length);
    for (var i = 0; i < bin.length; i++) a[i] = bin.charCodeAt(i);
    return a.buffer;
  }
  function bufToB64url(b) {
    var bytes = new Uint8Array(b);
    var s = '';
    for (var i = 0; i < bytes.length; i++) s += String.fromCharCode(bytes[i]);
    return btoa(s).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+${'$'}/, '');
  }
  function hydrateCreation(o) {
    // Manually convert every base64url field the WebAuthn API expects as
    // ArrayBuffer. Mirror of PublicKeyCredential.parseCreationOptionsFromJSON.
    var p = o.publicKey || o;
    p.challenge = b64urlToBuf(p.challenge);
    if (p.user && p.user.id) p.user.id = b64urlToBuf(p.user.id);
    if (Array.isArray(p.excludeCredentials)) {
      p.excludeCredentials = p.excludeCredentials.map(function(c) {
        return Object.assign({}, c, { id: b64urlToBuf(c.id) });
      });
    }
    return p;
  }
  function serializeAttestation(cred) {
    var r = cred.response;
    return {
      id: cred.id,
      rawId: bufToB64url(cred.rawId),
      type: cred.type,
      clientExtensionResults: cred.getClientExtensionResults ? cred.getClientExtensionResults() : {},
      response: {
        clientDataJSON: bufToB64url(r.clientDataJSON),
        attestationObject: bufToB64url(r.attestationObject),
        transports: r.getTransports ? r.getTransports() : []
      }
    };
  }

  if (!window.PublicKeyCredential) {
    show('Your browser does not support passkeys. Continuing without one.');
    setTimeout(skip, 1500);
    return;
  }

  btn.addEventListener('click', function() {
    btn.disabled = true;
    show('Follow your browser / OS prompt…');

    fetch('/webauthn/register/begin', {
      method: 'POST',
      headers: {'Content-Type': 'application/json'},
      credentials: 'same-origin',
      body: JSON.stringify({ sub: sub, displayName: displayName })
    })
      .then(function(r) { if (!r.ok) throw new Error('begin HTTP ' + r.status); return r.json(); })
      .then(function(envelope) {
        var opts = hydrateCreation(envelope.options);
        console.log('[passkey] calling navigator.credentials.create', opts);
        return navigator.credentials.create({ publicKey: opts }).then(function(cred) {
          console.log('[passkey] create succeeded', cred);
          return fetch('/webauthn/register/complete', {
            method: 'POST',
            headers: {'Content-Type': 'application/json'},
            credentials: 'same-origin',
            body: JSON.stringify({ flow_id: envelope.flow_id, attestation: serializeAttestation(cred) })
          });
        });
      })
      .then(function(r) { if (!r.ok) return r.text().then(function(t){ throw new Error('complete HTTP ' + r.status + ': ' + t); }); return r.json(); })
      .then(function() {
        show('Passkey registered! Continuing…');
        setTimeout(skip, 800);
      })
      .catch(function(err) {
        console.error('[passkey] registration error', err);
        show('Passkey registration failed (' + (err.message || 'error') + '). Continuing without one.');
        setTimeout(skip, 2500);
      });
  });
})();
""".trimIndent()

private suspend fun ApplicationCall.respondPlainBadRequest(code: String, description: String) {
    response.status(HttpStatusCode.BadRequest)
    respondText("error: $code\n$description", io.ktor.http.ContentType.Text.Plain)
}
