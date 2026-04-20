package id.walt.authop.templates

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.html.respondHtml
import kotlinx.html.a
import kotlinx.html.body
import kotlinx.html.button
import kotlinx.html.div
import kotlinx.html.h1
import kotlinx.html.head
import kotlinx.html.id
import kotlinx.html.img
import kotlinx.html.input
import kotlinx.html.meta
import kotlinx.html.p
import kotlinx.html.script
import kotlinx.html.title
import kotlinx.html.unsafe

/**
 * QR-scan page rendered by the OID4VP kickoff.
 *
 * Three moving parts:
 *  1. A QR image whose `src` encodes [qrPayloadUrl] — the wallet scans this.
 *  2. A same-device deep link (`openid4vp://…`) for phone users.
 *  3. Polling JS that hits `statusUrl` every 2s and navigates to [completeUrl] on SUCCESSFUL.
 *
 * Test-friendliness: all load-bearing values are in data-* attributes on <body>
 * so tests can assert without parsing JavaScript. The `#qr`, `#vp-error`,
 * `#passkey-btn`, `#passkey-username`, `#passkey-status` IDs are stable handles.
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
            meta(name = "viewport", content = "width=device-width, initial-scale=1")
            title { +"Present your credential" }
            unsafe {
                +"""<style>
*,*::before,*::after{box-sizing:border-box;margin:0;padding:0}
body{font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,sans-serif;background:#f0f4f8;min-height:100vh;display:flex;align-items:center;justify-content:center}
.card{background:#fff;border-radius:16px;box-shadow:0 4px 24px rgba(0,0,0,.08),0 1px 4px rgba(0,0,0,.05);padding:2rem 2rem 2.5rem;width:100%;max-width:440px;margin:1rem;text-align:center}
.brand{display:flex;justify-content:center;margin-bottom:1.25rem}
h1{font-size:1.25rem;font-weight:700;color:#111827;margin-bottom:.5rem}
.intro{font-size:.875rem;color:#6b7280;margin-bottom:1.5rem;line-height:1.5}
#qr{display:inline-flex;padding:12px;border:1.5px solid #e5e7eb;border-radius:12px;margin-bottom:1.5rem}
#qr img{display:block;border-radius:4px}
.divider{display:flex;align-items:center;gap:.75rem;margin:.25rem 0 1rem;color:#9ca3af;font-size:.75rem;text-transform:uppercase;letter-spacing:.05em}
.divider::before,.divider::after{content:'';flex:1;height:1px;background:#e5e7eb}
.btn-device{display:flex;align-items:center;justify-content:center;gap:.5rem;padding:.875rem 1.25rem;background:#4f46e5;color:#fff;border-radius:10px;text-decoration:none;font-size:.9375rem;font-weight:600;transition:background .15s}
.btn-device:hover{background:#4338ca}
#passkey-login{margin-top:1rem}
#passkey-btn{width:100%;padding:.8rem 1.25rem;background:#fff;color:#374151;border:1.5px solid #d1d5db;border-radius:10px;font-size:.9rem;font-weight:500;cursor:pointer;transition:border-color .15s,background .15s}
#passkey-btn:hover{border-color:#4f46e5;background:#fafafe}
#passkey-status{margin-top:.5rem;font-size:.8125rem;color:#6b7280}
#vp-error{margin-top:1rem;padding:.75rem 1rem;background:#fef2f2;border:1px solid #fecaca;border-radius:8px;color:#dc2626;font-size:.875rem;display:none}
#vp-error:not(:empty){display:block}
</style>"""
            }
        }
        body {
            attributes["data-status-url"] = statusUrl
            attributes["data-verifier-session-id"] = verifierSessionId
            attributes["data-complete-url"] = completeUrl

            div(classes = "card") {
                div(classes = "brand") {
                    unsafe {
                        +"""<svg width="44" height="44" viewBox="0 0 44 44" fill="none" xmlns="http://www.w3.org/2000/svg"><rect width="44" height="44" rx="11" fill="#ede9fe"/><path d="M22 9L12 14v10c0 6.1 4.8 11.7 10 13.3C27.2 35.7 32 30.1 32 24V14L22 9z" fill="#4f46e5"/><path d="M18.5 22l2.5 2.5 4.5-4.5" stroke="#fff" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/></svg>"""
                    }
                }
                h1 { +"Present your credential" }
                p(classes = "intro") { +"Scan the QR code below with your wallet, or open the wallet on this device." }

                div {
                    id = "qr"
                    attributes["data-url"] = qrPayloadUrl
                    val qrSrc = "https://api.qrserver.com/v1/create-qr-code/?size=300x300&data=" +
                        java.net.URLEncoder.encode(qrPayloadUrl, "UTF-8")
                    img(alt = "QR code", src = qrSrc)
                }

                div(classes = "divider") { +"or" }

                a(href = deepLink, classes = "btn-device") { +"Open wallet on this device" }

                div {
                    id = "passkey-login"
                    button {
                        id = "passkey-btn"
                        attributes["type"] = "button"
                        attributes["style"] = "display:none;"
                        +"Sign in with passkey"
                    }
                    input {
                        id = "passkey-username"
                        attributes["autocomplete"] = "username webauthn"
                        attributes["style"] = "position:absolute;left:-10000px;width:1px;height:1px;"
                        attributes["aria-hidden"] = "true"
                        attributes["tabindex"] = "-1"
                    }
                    div {
                        id = "passkey-status"
                        attributes["style"] = "margin-top:8px;font-size:0.9em;opacity:0.8;"
                    }
                }

                div {
                    id = "vp-error"
                }
            }

            // Poll status every 2s. On SUCCESSFUL, navigate to /complete.
            // On UNSUCCESSFUL, write an error into #vp-error.
            // `window.location.replace` is correct here — we don't want the
            // QR page in the history stack.
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

            // Passkey login. Two paths:
            //  1. Explicit button ("Sign in with passkey") — uses
            //     mediation: 'optional' so the platform authenticator
            //     prompt appears immediately on click. This is what most
            //     production sites do and works on every WebAuthn browser.
            //  2. Background conditional mediation attached to the hidden
            //     <input autocomplete="username webauthn">. If the browser
            //     supports it, tabbing/clicking into the input surfaces
            //     the passkey as an autofill suggestion. Zero-cost fallback
            //     for users who don't see/notice the button.
            //
            // Both paths share a single /webauthn/login/complete server
            // handshake. When the server has no passkeys (or the feature
            // is disabled), both paths degrade to no-ops and the QR flow
            // continues unchanged.
            script {
                unsafe {
                    +"""
                    (function() {
                      if (!window.PublicKeyCredential) return;
                      var btn = document.getElementById('passkey-btn');
                      var input = document.getElementById('passkey-username');
                      var status = document.getElementById('passkey-status');
                      if (btn) btn.style.display = 'inline-block';

                      function show(msg, isError) {
                        console[isError ? 'error' : 'log']('[passkey]', msg);
                        if (status) {
                          status.textContent = msg;
                          status.style.color = isError ? '#b00020' : '';
                        }
                      }

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
                      function hydrateRequest(o) {
                        var p = o.publicKey || o;
                        p.challenge = b64urlToBuf(p.challenge);
                        if (Array.isArray(p.allowCredentials)) {
                          p.allowCredentials = p.allowCredentials.map(function(c) {
                            return Object.assign({}, c, { id: b64urlToBuf(c.id) });
                          });
                        }
                        return p;
                      }
                      function serializeAssertion(cred) {
                        var r = cred.response;
                        return {
                          id: cred.id,
                          rawId: bufToB64url(cred.rawId),
                          type: cred.type,
                          clientExtensionResults: cred.getClientExtensionResults ? cred.getClientExtensionResults() : {},
                          response: {
                            clientDataJSON: bufToB64url(r.clientDataJSON),
                            authenticatorData: bufToB64url(r.authenticatorData),
                            signature: bufToB64url(r.signature),
                            userHandle: r.userHandle ? bufToB64url(r.userHandle) : null
                          }
                        };
                      }

                      // Serialise navigator.credentials.get() calls ourselves
                      // so the button click never collides with an in-flight
                      // conditional mediation call. One AbortController per
                      // pending call, replaced on each new invocation.
                      var pendingAbort = null;

                      function runGet(mediation) {
                        if (pendingAbort) { try { pendingAbort.abort(); } catch (_) {} }
                        var ac = new AbortController();
                        pendingAbort = ac;
                        return fetch('/webauthn/login/begin', {
                          method: 'POST', credentials: 'same-origin'
                        })
                          .then(function(r) { return r.ok ? r.json() : null; })
                          .then(function(envelope) {
                            if (!envelope) throw new Error('no envelope from /login/begin');
                            var opts = hydrateRequest(envelope.options);
                            console.log('[passkey] login options', opts, 'mediation', mediation);
                            return navigator.credentials.get({
                              publicKey: opts,
                              mediation: mediation,
                              signal: ac.signal,
                            }).then(function(assertion) {
                              if (!assertion) return null;
                              console.log('[passkey] assertion received', assertion);
                              return fetch('/webauthn/login/complete', {
                                method: 'POST',
                                headers: {'Content-Type': 'application/json'},
                                credentials: 'same-origin',
                                body: JSON.stringify({
                                  flow_id: envelope.flow_id,
                                  assertion: serializeAssertion(assertion)
                                })
                              }).then(function(r) {
                                if (r.ok) return r.json();
                                return r.text().then(function(t){ throw new Error('complete HTTP ' + r.status + ': ' + t); });
                              });
                            });
                          })
                          .then(function(j) {
                            if (j && j.redirect) window.location.replace(j.redirect);
                          });
                      }

                      if (btn) {
                        btn.addEventListener('click', function() {
                          btn.disabled = true;
                          show('Follow your browser / OS prompt…');
                          runGet('optional').catch(function(err) {
                            // AbortError from cancelling our own conditional
                            // call isn't user-visible — only real failures.
                            if (err && err.name === 'AbortError') return;
                            show('Passkey sign-in failed: ' + (err && err.message ? err.message : err), true);
                            btn.disabled = false;
                          });
                        });
                      }

                      // W3C conditional-UI pattern: attach on input focus, NOT
                      // on page load. Firing get({mediation:'conditional'}) at
                      // load-time holds a WebAuthn "slot" forever and makes
                      // the button's click collide. Browser surfaces the
                      // passkey via autofill when the user tabs into the
                      // input; if they click the button instead, the button
                      // path aborts any focus-triggered call and wins.
                      if (input && PublicKeyCredential.isConditionalMediationAvailable) {
                        PublicKeyCredential.isConditionalMediationAvailable().then(function(ok) {
                          if (!ok) return;
                          input.addEventListener('focus', function() {
                            runGet('conditional').catch(function() {});
                          }, { once: true });
                        });
                      }
                    })();
                    """.trimIndent()
                }
            }
        }
    }
}
