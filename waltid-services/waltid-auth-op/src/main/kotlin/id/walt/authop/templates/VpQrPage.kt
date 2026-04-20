package id.walt.authop.templates

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.html.respondHtml
import kotlinx.html.a
import kotlinx.html.body
import kotlinx.html.div
import kotlinx.html.h1
import kotlinx.html.head
import kotlinx.html.id
import kotlinx.html.img
import kotlinx.html.meta
import kotlinx.html.p
import kotlinx.html.script
import kotlinx.html.title
import kotlinx.html.unsafe

/**
 * Minimal QR-scan page rendered by the OID4VP kickoff.
 *
 * The page has three moving parts:
 *  1. A QR image whose `src` encodes [qrPayloadUrl] — the wallet scans this to
 *     fetch the verifier-api2 authorization request. We use the external
 *     `api.qrserver.com` renderer as the MVP path (no JS library dependency);
 *     a future iteration can swap to an inline JS QR generator.
 *  2. A same-device deep link (`<a href="openid4vp://…">`) that triggers a
 *     wallet on the same device (phone users hitting this page directly).
 *  3. Inline polling JS that hits `statusUrl` every 2s and redirects to
 *     `/login/realm/{id}/complete` on `SUCCESSFUL`. `UNSUCCESSFUL` surfaces an
 *     inline error. Tests find the polling URL via `data-status-url` on
 *     `<body>`, and find the verifier session id via `data-verifier-session-id`.
 *
 * Test-friendliness: all load-bearing values (QR payload, deep link, status
 * URL, verifier session id) are rendered as HTML text or attributes so tests
 * can assert on them without parsing JavaScript. The `data-*` attributes on
 * `<body>` are the designated machine-readable surface.
 *
 * No CSS, no branding — the page will grow cosmetics under separate tasks.
 */
internal suspend fun ApplicationCall.respondVpQrPage(
    qrPayloadUrl: String,
    deepLink: String,
    verifierSessionId: String,
    statusUrl: String,
    completeUrl: String,
) {
    respondHtml(HttpStatusCode.OK) {
        head {
            meta(charset = "utf-8")
            title { +"Present your credential" }
        }
        body {
            // The data-attrs let tests key on stable handles instead of
            // parsing the embedded script body. JS reads them too.
            attributes["data-status-url"] = statusUrl
            attributes["data-verifier-session-id"] = verifierSessionId
            attributes["data-complete-url"] = completeUrl

            h1 { +"Present your credential" }
            p { +"Scan the QR code below with your wallet, or open the wallet on this device." }

            // QR image — external renderer keeps this MVP small.
            val qrSrc = "https://api.qrserver.com/v1/create-qr-code/?size=300x300&data=" +
                java.net.URLEncoder.encode(qrPayloadUrl, "UTF-8")
            div {
                id = "qr"
                attributes["data-url"] = qrPayloadUrl
                img(alt = "QR code", src = qrSrc)
            }

            // Same-device deep link. openid4vp:// is an app-scheme URI;
            // the DSL's a(href = ...) passes through URL-scheme values.
            p {
                a(href = deepLink) { +"Open wallet on this device" }
            }

            // Poll status every 2s. On SUCCESSFUL, navigate to /complete.
            // On UNSUCCESSFUL, write an error into #vp-error.
            // `window.location.replace` is correct here — we don't want the
            // QR page in the history stack.
            div {
                id = "vp-error"
                // Empty placeholder; populated by the polling JS on failure.
            }
            script {
                unsafe {
                    +"""
                    (function() {
                      var body = document.body;
                      var statusUrl = body.getAttribute('data-status-url');
                      var completeUrl = body.getAttribute('data-complete-url');
                      var err = document.getElementById('vp-error');
                      function tick() {
                        fetch(statusUrl, { credentials: 'same-origin' })
                          .then(function(r) { return r.ok ? r.json() : null; })
                          .then(function(j) {
                            if (!j) return;
                            if (j.status === 'SUCCESSFUL') {
                              window.location.replace(completeUrl);
                            } else if (j.status === 'UNSUCCESSFUL') {
                              err.textContent = 'Presentation failed. Please try again.';
                            } else {
                              setTimeout(tick, 2000);
                            }
                          })
                          .catch(function() { setTimeout(tick, 2000); });
                      }
                      setTimeout(tick, 2000);
                    })();
                    """.trimIndent()
                }
            }

            // Conditional-UI passkey login. Runs independently of the wallet
            // QR polling above: if the browser has a discoverable passkey
            // for this origin, the platform authenticator surfaces a silent
            // prompt; otherwise nothing happens and the user scans the QR
            // as today. When passkey support is disabled at the server,
            // /webauthn/login/begin returns 404 and we no-op.
            script {
                unsafe {
                    +"""
                    (function() {
                      if (!window.PublicKeyCredential ||
                          !PublicKeyCredential.parseRequestOptionsFromJSON ||
                          !PublicKeyCredential.isConditionalMediationAvailable) {
                        return;
                      }
                      PublicKeyCredential.isConditionalMediationAvailable().then(function(ok) {
                        if (!ok) return;
                        fetch('/webauthn/login/begin', { method: 'POST', credentials: 'same-origin' })
                          .then(function(r) { return r.ok ? r.json() : null; })
                          .then(function(envelope) {
                            if (!envelope) return;
                            var opts = PublicKeyCredential.parseRequestOptionsFromJSON(
                              envelope.options.publicKey || envelope.options
                            );
                            return navigator.credentials.get({
                              publicKey: opts,
                              mediation: 'conditional'
                            }).then(function(assertion) {
                              if (!assertion) return;
                              return fetch('/webauthn/login/complete', {
                                method: 'POST',
                                headers: {'Content-Type': 'application/json'},
                                credentials: 'same-origin',
                                body: JSON.stringify({
                                  flow_id: envelope.flow_id,
                                  assertion: assertion.toJSON()
                                })
                              }).then(function(r) { return r.ok ? r.json() : null; })
                                .then(function(j) {
                                  if (j && j.redirect) window.location.replace(j.redirect);
                                });
                            });
                          })
                          .catch(function() { /* silent — QR path still works */ });
                      });
                    })();
                    """.trimIndent()
                }
            }
        }
    }
}
