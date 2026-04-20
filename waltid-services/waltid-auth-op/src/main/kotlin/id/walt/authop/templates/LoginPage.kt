package id.walt.authop.templates

import id.walt.authop.config.ClientConfig
import id.walt.authop.config.RealmConfig
import id.walt.authop.domain.AuthRequest
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.html.respondHtml
import kotlinx.html.body
import kotlinx.html.button
import kotlinx.html.div
import kotlinx.html.h1
import kotlinx.html.head
import kotlinx.html.id
import kotlinx.html.input
import kotlinx.html.link
import kotlinx.html.meta
import kotlinx.html.p
import kotlinx.html.script
import kotlinx.html.span
import kotlinx.html.style
import kotlinx.html.title
import kotlinx.html.unsafe

/**
 * Full-viewport immersive glass realm picker with three selectable palettes.
 * All realm flows render inline in the same glass card — the wallet flow
 * (OID4VP) lazily fetches its QR / deep link / passkey surface via
 * `POST /login/realm/{id}/kickoff` and polls `/status` in place, so
 * nothing about picking the wallet option navigates away. The OIDC
 * realm still has to hand off to the upstream provider's own login UI
 * (cross-origin, not embeddable), but clicks there fade out the page
 * before redirecting so the transition feels like part of one experience.
 *
 * Palettes live entirely in CSS custom properties keyed by
 * `body[data-theme]`: Midnight (cyan on ink), Onyx (gold on near-black),
 * Aurora (purple/pink/cyan mesh). A corner toggle cycles through them
 * and persists the choice in localStorage.
 */
internal suspend fun ApplicationCall.respondLoginPage(
    @Suppress("UNUSED_PARAMETER") authReq: AuthRequest,
    @Suppress("UNUSED_PARAMETER") client: ClientConfig,
    realms: Collection<RealmConfig>,
) {
    val oidcRealm = realms.firstOrNull { it.oidc != null }
    val vpRealm = realms.firstOrNull { it.oid4vp != null }

    respondHtml(HttpStatusCode.OK) {
        head {
            meta(charset = "utf-8")
            meta(name = "viewport", content = "width=device-width,initial-scale=1")
            title { +"Sign in" }
            link(rel = "preconnect", href = "https://fonts.googleapis.com")
            link(rel = "preconnect", href = "https://fonts.gstatic.com") { attributes["crossorigin"] = "" }
            link(
                rel = "stylesheet",
                href = "https://fonts.googleapis.com/css2?family=Inter:wght@400;500;600;700&family=Space+Grotesk:wght@500;700&display=swap",
            )
            style { unsafe { +PAGE_CSS } }
        }
        body {
            attributes["data-theme"] = "midnight"

            div { id = "mesh" }
            div { id = "mesh-overlay" }

            div {
                id = "theme-toggle"
                attributes["title"] = "Cycle theme"
                attributes["role"] = "button"
                attributes["tabindex"] = "0"
                span { id = "theme-dot" }
                span {
                    id = "theme-label"
                    +"Midnight"
                }
            }

            div {
                id = "card"

                div {
                    id = "brand"
                    div {
                        attributes["class"] = "brand-mark"
                        unsafe { +BRAND_SVG }
                    }
                    h1 { +"Welcome back" }
                    p {
                        id = "brand-subtitle"
                        +"Choose how you'd like to sign in."
                    }
                }

                div {
                    id = "segmented"
                    attributes["role"] = "tablist"

                    if (oidcRealm != null) {
                        div {
                            attributes["class"] = "seg-tab"
                            attributes["role"] = "tab"
                            attributes["data-target"] = "pane-oidc"
                            attributes["aria-selected"] = "true"
                            +oidcRealm.name
                        }
                    }
                    if (vpRealm != null) {
                        div {
                            attributes["class"] = "seg-tab"
                            attributes["role"] = "tab"
                            attributes["data-target"] = "pane-vp"
                            attributes["aria-selected"] = "false"
                            +vpRealm.name
                        }
                    }
                    div { id = "seg-indicator" }
                }

                if (oidcRealm != null) {
                    div {
                        id = "pane-oidc"
                        attributes["class"] = "pane active"
                        attributes["role"] = "tabpanel"
                        p {
                            attributes["class"] = "pane-lead"
                            +"You'll be taken to your organisation's sign-in service to confirm it's you."
                        }
                        div {
                            attributes["class"] = "oidc-host-chip"
                            span { attributes["class"] = "chip-label"; +"Provider" }
                            span {
                                attributes["class"] = "chip-value"
                                +(oidcRealm.oidc?.issuer?.let { shortHost(it) } ?: "OIDC provider")
                            }
                        }
                        button {
                            attributes["class"] = "primary-btn"
                            attributes["data-realm"] = oidcRealm.id
                            id = "oidc-continue"
                            span { +"Continue" }
                            unsafe { +ARROW_SVG }
                        }
                    }
                }

                if (vpRealm != null) {
                    div {
                        id = "pane-vp"
                        attributes["class"] = "pane"
                        attributes["role"] = "tabpanel"
                        attributes["data-realm"] = vpRealm.id
                        p {
                            attributes["class"] = "pane-lead"
                            +"Scan the code with your wallet, open the wallet on this device, or sign in with a passkey you've already registered."
                        }
                        div {
                            id = "vp-loading"
                            attributes["class"] = "pane-state"
                            div { attributes["class"] = "spinner" }
                            span { +"Preparing secure link…" }
                        }
                        div {
                            id = "vp-ready"
                            attributes["class"] = "pane-state hidden"
                            div { id = "qr" }
                            div {
                                attributes["class"] = "vp-actions"
                                button {
                                    id = "vp-deeplink"
                                    attributes["class"] = "ghost-btn"
                                    span { +"Open wallet on this device" }
                                }
                                button {
                                    id = "passkey-btn"
                                    attributes["class"] = "ghost-btn hidden"
                                    attributes["type"] = "button"
                                    unsafe { +KEY_SVG }
                                    span { +"Sign in with passkey" }
                                }
                            }
                            input {
                                id = "passkey-username"
                                attributes["autocomplete"] = "username webauthn"
                                attributes["aria-hidden"] = "true"
                                attributes["tabindex"] = "-1"
                                attributes["class"] = "offscreen"
                            }
                            div { id = "passkey-status"; attributes["class"] = "status-text" }
                        }
                        div {
                            id = "vp-error"
                            attributes["class"] = "pane-state hidden"
                        }
                    }
                }

                div {
                    id = "footer-fineprint"
                    +"Secured by auth-op · OpenID4VP / OIDC Core"
                }
            }

            script { unsafe { +PAGE_JS } }
        }
    }
}

private fun shortHost(url: String): String = try {
    java.net.URI(url).host ?: url
} catch (_: Throwable) {
    url
}

private val BRAND_SVG = """
<svg viewBox="0 0 48 48" width="48" height="48" fill="none" aria-hidden="true">
  <defs>
    <linearGradient id="bg" x1="0" y1="0" x2="48" y2="48" gradientUnits="userSpaceOnUse">
      <stop offset="0" stop-color="var(--accent)" stop-opacity="0.9"/>
      <stop offset="1" stop-color="var(--accent-2)" stop-opacity="0.6"/>
    </linearGradient>
  </defs>
  <rect x="2" y="2" width="44" height="44" rx="12" fill="url(#bg)" opacity="0.25"/>
  <path d="M14 24l7 7 13-14" stroke="var(--accent)" stroke-width="3.2" stroke-linecap="round" stroke-linejoin="round"/>
</svg>
""".trimIndent()

private val ARROW_SVG = """
<svg viewBox="0 0 24 24" width="18" height="18" fill="none" aria-hidden="true">
  <path d="M4 12h15m-5-5 5 5-5 5" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/>
</svg>
""".trimIndent()

private val KEY_SVG = """
<svg viewBox="0 0 24 24" width="18" height="18" fill="none" aria-hidden="true">
  <circle cx="8" cy="15" r="4" stroke="currentColor" stroke-width="2"/>
  <path d="M10.5 12.5 20 3m-3 4 2.5 2.5M14 10l2.5 2.5" stroke="currentColor" stroke-width="2" stroke-linecap="round"/>
</svg>
""".trimIndent()

private val PAGE_CSS = """
:root {
  --bg-0: #05070E; --bg-1: #0B0F1F;
  --panel: rgba(255, 255, 255, 0.06);
  --panel-border: rgba(255, 255, 255, 0.12);
  --panel-border-hover: rgba(255, 255, 255, 0.22);
  --text: #EAF0FF; --text-dim: #97A3BF;
  --accent: #3ABFFF; --accent-2: #7B5CFF;
  --mesh-a: #3ABFFF; --mesh-b: #7B5CFF; --mesh-c: #5AA0FF;
  --shadow: 0 40px 80px -20px rgba(0, 0, 0, 0.6), 0 0 0 1px rgba(255, 255, 255, 0.04) inset;
}
[data-theme="onyx"] {
  --bg-0: #06050A; --bg-1: #0A0A0C;
  --panel: rgba(255, 255, 255, 0.04);
  --panel-border: rgba(212, 175, 55, 0.22);
  --panel-border-hover: rgba(212, 175, 55, 0.45);
  --text: #F4EFE1; --text-dim: #B4A88C;
  --accent: #D4AF37; --accent-2: #F3D07C;
  --mesh-a: #3B2F12; --mesh-b: #1A1606; --mesh-c: #564428;
}
[data-theme="aurora"] {
  --bg-0: #0B0720; --bg-1: #0D0B24;
  --panel: rgba(255, 255, 255, 0.07);
  --panel-border: rgba(255, 255, 255, 0.14);
  --panel-border-hover: rgba(255, 255, 255, 0.26);
  --text: #F3EFFF; --text-dim: #AFA3D2;
  --accent: #FF3D9E; --accent-2: #4F7BFF;
  --mesh-a: #4F00FF; --mesh-b: #FF0080; --mesh-c: #00D4FF;
}

* { box-sizing: border-box; }
html, body {
  margin: 0; padding: 0; min-height: 100vh;
  background: radial-gradient(ellipse at top, var(--bg-1) 0%, var(--bg-0) 80%);
  color: var(--text);
  font-family: 'Inter', system-ui, -apple-system, Segoe UI, sans-serif;
  -webkit-font-smoothing: antialiased;
  overflow-x: hidden;
  transition: background 600ms ease;
}
body { display: grid; place-items: center; padding: 48px 20px; }

#mesh {
  position: fixed; inset: -20%;
  background:
    radial-gradient(60% 60% at 18% 22%, var(--mesh-a) 0%, transparent 60%),
    radial-gradient(55% 55% at 82% 30%, var(--mesh-b) 0%, transparent 60%),
    radial-gradient(60% 60% at 50% 95%, var(--mesh-c) 0%, transparent 60%);
  filter: blur(80px) saturate(140%);
  opacity: 0.55;
  animation: drift 24s ease-in-out infinite alternate;
  pointer-events: none; z-index: 0;
}
[data-theme="onyx"] #mesh { opacity: 0.35; filter: blur(100px) saturate(110%); }
[data-theme="aurora"] #mesh { opacity: 0.75; animation-duration: 18s; }
#mesh-overlay {
  position: fixed; inset: 0;
  background: radial-gradient(ellipse at center, transparent 0%, rgba(0,0,0,0.55) 100%);
  pointer-events: none; z-index: 1;
}
@keyframes drift {
  0% { transform: translate3d(0, 0, 0) scale(1); }
  100% { transform: translate3d(-4%, 3%, 0) scale(1.1); }
}

#theme-toggle {
  position: fixed; top: 20px; right: 20px; z-index: 10;
  display: inline-flex; align-items: center; gap: 10px;
  padding: 10px 16px; border-radius: 999px;
  background: var(--panel); border: 1px solid var(--panel-border);
  backdrop-filter: blur(20px) saturate(140%); -webkit-backdrop-filter: blur(20px) saturate(140%);
  color: var(--text); font-size: 13px; font-weight: 500;
  cursor: pointer; transition: all 180ms ease; user-select: none;
}
#theme-toggle:hover { border-color: var(--panel-border-hover); transform: translateY(-1px); }
#theme-dot { width: 10px; height: 10px; border-radius: 50%; background: var(--accent); box-shadow: 0 0 12px var(--accent); }

#card {
  position: relative; z-index: 5;
  width: min(460px, 100%);
  padding: 40px 40px 28px;
  background: var(--panel);
  border: 1px solid var(--panel-border);
  border-radius: 28px;
  backdrop-filter: blur(28px) saturate(160%); -webkit-backdrop-filter: blur(28px) saturate(160%);
  box-shadow: var(--shadow);
  animation: card-in 720ms cubic-bezier(0.2, 0.9, 0.2, 1.1) both;
}
@keyframes card-in {
  from { opacity: 0; transform: translateY(14px) scale(0.985); filter: blur(8px); }
  to   { opacity: 1; transform: translateY(0) scale(1); filter: none; }
}

#brand { text-align: center; margin-bottom: 28px; }
.brand-mark { display: inline-flex; padding: 10px; border-radius: 16px; background: rgba(255,255,255,0.03); margin-bottom: 12px; }
#brand h1 { margin: 0; font-family: 'Space Grotesk', Inter, sans-serif; font-weight: 700; font-size: 28px; letter-spacing: -0.02em; }
#brand p { margin: 8px 0 0; color: var(--text-dim); font-size: 15px; }

#segmented {
  position: relative;
  display: grid; grid-template-columns: 1fr 1fr;
  padding: 4px; margin-bottom: 24px;
  background: rgba(0,0,0,0.25);
  border: 1px solid var(--panel-border);
  border-radius: 14px;
}
.seg-tab {
  position: relative; z-index: 2;
  padding: 11px 10px;
  text-align: center; font-size: 13px; font-weight: 600;
  color: var(--text-dim);
  cursor: pointer; border-radius: 10px;
  transition: color 220ms ease; user-select: none;
}
.seg-tab[aria-selected="true"] { color: var(--text); }
#seg-indicator {
  position: absolute; z-index: 1; top: 4px; bottom: 4px; left: 4px;
  width: calc(50% - 4px);
  background: linear-gradient(135deg, rgba(255,255,255,0.12), rgba(255,255,255,0.04));
  border: 1px solid var(--panel-border-hover);
  border-radius: 10px;
  transition: transform 360ms cubic-bezier(0.22, 1, 0.36, 1);
  box-shadow: 0 8px 20px -10px var(--accent);
}
#seg-indicator[data-at="1"] { transform: translateX(100%); }

.pane { display: none; animation: pane-in 480ms cubic-bezier(0.22, 1, 0.36, 1) both; }
.pane.active { display: block; }
@keyframes pane-in {
  from { opacity: 0; transform: translateY(8px); filter: blur(6px); }
  to   { opacity: 1; transform: translateY(0);   filter: none; }
}
.pane-lead { margin: 0 0 20px; color: var(--text-dim); font-size: 14px; line-height: 1.55; }

.pane-state { display: flex; flex-direction: column; align-items: center; gap: 16px; }
.pane-state.hidden { display: none; }

.oidc-host-chip {
  display: inline-flex; align-items: center; gap: 10px;
  padding: 8px 14px;
  background: rgba(255,255,255,0.04);
  border: 1px solid var(--panel-border);
  border-radius: 10px;
  font-size: 12px; margin-bottom: 20px;
}
.chip-label { color: var(--text-dim); }
.chip-value { color: var(--text); font-weight: 500; }

.primary-btn {
  width: 100%;
  display: inline-flex; align-items: center; justify-content: center; gap: 8px;
  padding: 14px 20px;
  background: linear-gradient(135deg, var(--accent), var(--accent-2));
  color: #fff; border: none; border-radius: 12px;
  font: inherit; font-size: 15px; font-weight: 600;
  cursor: pointer;
  box-shadow: 0 18px 34px -14px var(--accent), 0 0 0 1px rgba(255,255,255,0.1) inset;
  transition: transform 160ms ease, box-shadow 160ms ease;
}
.primary-btn:hover { transform: translateY(-1px); box-shadow: 0 24px 40px -14px var(--accent), 0 0 0 1px rgba(255,255,255,0.15) inset; }
.primary-btn:active { transform: translateY(0); }
.primary-btn:disabled { opacity: 0.6; cursor: wait; }

.ghost-btn {
  width: 100%;
  display: inline-flex; align-items: center; justify-content: center; gap: 8px;
  padding: 12px 18px;
  background: rgba(255,255,255,0.04);
  color: var(--text);
  border: 1px solid var(--panel-border);
  border-radius: 12px;
  font: inherit; font-size: 14px; font-weight: 500;
  cursor: pointer;
  transition: all 160ms ease;
}
.ghost-btn:hover { background: rgba(255,255,255,0.08); border-color: var(--panel-border-hover); }
.ghost-btn.hidden { display: none; }

#qr { position: relative; padding: 16px; background: #fff; border-radius: 18px; box-shadow: 0 22px 50px -20px rgba(0,0,0,0.6); }
#qr img { display: block; width: 240px; height: 240px; border-radius: 8px; }

.vp-actions { width: 100%; display: grid; grid-template-columns: 1fr; gap: 10px; }

.offscreen { position: absolute; left: -10000px; width: 1px; height: 1px; opacity: 0; }
.status-text { font-size: 12px; color: var(--text-dim); min-height: 1em; text-align: center; }

.spinner {
  width: 32px; height: 32px; border-radius: 50%;
  border: 3px solid rgba(255,255,255,0.1);
  border-top-color: var(--accent);
  animation: spin 900ms linear infinite;
}
@keyframes spin { to { transform: rotate(360deg); } }

#footer-fineprint { text-align: center; font-size: 11px; color: var(--text-dim); margin-top: 24px; letter-spacing: 0.05em; text-transform: uppercase; }

body.leaving #card { opacity: 0; transform: translateY(-8px) scale(0.98); transition: all 320ms ease; }
body.leaving #mesh { opacity: 0.25; }

@media (prefers-reduced-motion: reduce) {
  #mesh { animation: none; }
  #card { animation: none; }
  .pane { animation: none; }
  #seg-indicator { transition: none; }
}
@media (max-width: 520px) {
  #card { padding: 28px 22px 20px; border-radius: 22px; }
  #brand h1 { font-size: 24px; }
}
""".trimIndent()

private val PAGE_JS = """
(function() {
  var THEMES = ['midnight', 'onyx', 'aurora'];
  var LABELS = { midnight: 'Midnight', onyx: 'Onyx', aurora: 'Aurora' };
  var body = document.body;
  var label = document.getElementById('theme-label');
  var saved = null;
  try { saved = localStorage.getItem('authop-theme'); } catch (_) {}
  if (saved && THEMES.indexOf(saved) >= 0) {
    body.setAttribute('data-theme', saved);
    if (label) label.textContent = LABELS[saved];
  }
  var toggle = document.getElementById('theme-toggle');
  if (toggle) {
    toggle.addEventListener('click', function() {
      var cur = body.getAttribute('data-theme') || 'midnight';
      var next = THEMES[(THEMES.indexOf(cur) + 1) % THEMES.length];
      body.setAttribute('data-theme', next);
      if (label) label.textContent = LABELS[next];
      try { localStorage.setItem('authop-theme', next); } catch (_) {}
    });
    toggle.addEventListener('keydown', function(e) {
      if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); toggle.click(); }
    });
  }

  var tabs = Array.prototype.slice.call(document.querySelectorAll('.seg-tab'));
  var panes = {
    'pane-oidc': document.getElementById('pane-oidc'),
    'pane-vp':   document.getElementById('pane-vp'),
  };
  var indicator = document.getElementById('seg-indicator');
  var vpLoaded = false;

  function selectTab(idx) {
    tabs.forEach(function(t, i) { t.setAttribute('aria-selected', i === idx ? 'true' : 'false'); });
    if (indicator) indicator.setAttribute('data-at', String(idx));
    Object.keys(panes).forEach(function(k) { if (panes[k]) panes[k].classList.remove('active'); });
    var target = tabs[idx] && tabs[idx].getAttribute('data-target');
    if (target && panes[target]) panes[target].classList.add('active');
    if (target === 'pane-vp' && !vpLoaded) startVpFlow();
  }
  tabs.forEach(function(tab, i) {
    tab.addEventListener('click', function() { selectTab(i); });
    tab.addEventListener('keydown', function(e) {
      if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); selectTab(i); }
    });
  });

  var oidcBtn = document.getElementById('oidc-continue');
  if (oidcBtn) {
    oidcBtn.addEventListener('click', function() {
      oidcBtn.disabled = true;
      body.classList.add('leaving');
      var realm = oidcBtn.getAttribute('data-realm');
      setTimeout(function() { window.location.href = '/login/realm/' + realm; }, 220);
    });
  }

  function clearChildren(el) { while (el.firstChild) el.removeChild(el.firstChild); }

  function startVpFlow() {
    vpLoaded = true;
    var pane = document.getElementById('pane-vp');
    var realm = pane && pane.getAttribute('data-realm');
    if (!realm) return;

    var loading = document.getElementById('vp-loading');
    var ready = document.getElementById('vp-ready');
    var errBox = document.getElementById('vp-error');
    var qrEl = document.getElementById('qr');
    var deepBtn = document.getElementById('vp-deeplink');

    fetch('/login/realm/' + realm + '/kickoff', {
      method: 'POST', credentials: 'same-origin'
    })
      .then(function(r) { if (!r.ok) throw new Error('kickoff HTTP ' + r.status); return r.json(); })
      .then(function(data) {
        loading.classList.add('hidden');
        ready.classList.remove('hidden');

        var qrSrc = 'https://api.qrserver.com/v1/create-qr-code/?size=300x300&data='
          + encodeURIComponent(data.qrPayloadUrl);
        clearChildren(qrEl);
        var img = document.createElement('img');
        img.alt = 'QR code';
        img.src = qrSrc;
        qrEl.appendChild(img);
        qrEl.setAttribute('data-url', data.qrPayloadUrl);

        deepBtn.onclick = function() { window.location.href = data.deepLink; };

        function tick() {
          fetch(data.statusUrl, { credentials: 'same-origin' })
            .then(function(r) { return r.ok ? r.json() : null; })
            .then(function(j) {
              if (!j) return;
              if (j.status === 'SUCCESSFUL') {
                window.location.replace(data.completeUrl);
              } else if (j.status === 'UNSUCCESSFUL') {
                errBox.textContent = 'Presentation failed. Please try again.';
                errBox.classList.remove('hidden');
              } else {
                setTimeout(tick, 2000);
              }
            })
            .catch(function() { setTimeout(tick, 2500); });
        }
        setTimeout(tick, 2000);
        wirePasskey();
      })
      .catch(function(err) {
        loading.classList.add('hidden');
        errBox.textContent = 'Could not start the wallet session: ' + (err.message || err);
        errBox.classList.remove('hidden');
      });
  }

  function wirePasskey() {
    if (!window.PublicKeyCredential) return;
    var btn = document.getElementById('passkey-btn');
    var input = document.getElementById('passkey-username');
    var status = document.getElementById('passkey-status');
    if (btn) btn.classList.remove('hidden');

    function show(msg, err) {
      if (console) console[err ? 'error' : 'log']('[passkey]', msg);
      if (status) { status.textContent = msg; status.style.color = err ? '#ff8a8a' : ''; }
    }
    function b64urlToBuf(s) {
      s = String(s).replace(/-/g, '+').replace(/_/g, '/');
      while (s.length % 4) s += '=';
      var bin = atob(s); var a = new Uint8Array(bin.length);
      for (var i = 0; i < bin.length; i++) a[i] = bin.charCodeAt(i);
      return a.buffer;
    }
    function bufToB64url(b) {
      var bytes = new Uint8Array(b); var s = '';
      for (var i = 0; i < bytes.length; i++) s += String.fromCharCode(bytes[i]);
      return btoa(s).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+${'$'}/, '');
    }
    function hydrateReq(o) {
      var p = o.publicKey || o; p.challenge = b64urlToBuf(p.challenge);
      if (Array.isArray(p.allowCredentials)) p.allowCredentials = p.allowCredentials.map(function(c){ return Object.assign({}, c, { id: b64urlToBuf(c.id) }); });
      return p;
    }
    function serializeAssertion(cred) {
      var r = cred.response;
      return {
        id: cred.id, rawId: bufToB64url(cred.rawId), type: cred.type,
        clientExtensionResults: cred.getClientExtensionResults ? cred.getClientExtensionResults() : {},
        response: {
          clientDataJSON: bufToB64url(r.clientDataJSON),
          authenticatorData: bufToB64url(r.authenticatorData),
          signature: bufToB64url(r.signature),
          userHandle: r.userHandle ? bufToB64url(r.userHandle) : null,
        }
      };
    }

    var pendingAbort = null;
    function runGet(mediation) {
      if (pendingAbort) { try { pendingAbort.abort(); } catch(_){} }
      var ac = new AbortController(); pendingAbort = ac;
      return fetch('/webauthn/login/begin', { method: 'POST', credentials: 'same-origin' })
        .then(function(r) { return r.ok ? r.json() : null; })
        .then(function(env) {
          if (!env) throw new Error('no envelope');
          var opts = hydrateReq(env.options);
          return navigator.credentials.get({ publicKey: opts, mediation: mediation, signal: ac.signal })
            .then(function(assertion) {
              if (!assertion) return null;
              return fetch('/webauthn/login/complete', {
                method: 'POST', headers: { 'Content-Type': 'application/json' }, credentials: 'same-origin',
                body: JSON.stringify({ flow_id: env.flow_id, assertion: serializeAssertion(assertion) })
              }).then(function(r){ return r.ok ? r.json() : r.text().then(function(t){ throw new Error('complete HTTP ' + r.status + ': ' + t); }); });
            });
        })
        .then(function(j) { if (j && j.redirect) window.location.replace(j.redirect); });
    }

    if (btn) btn.addEventListener('click', function() {
      btn.disabled = true; show('Follow your browser prompt…');
      runGet('optional').catch(function(err) {
        if (err && err.name === 'AbortError') return;
        show('Passkey sign-in failed: ' + (err.message || err), true);
        btn.disabled = false;
      });
    });

    if (input && PublicKeyCredential.isConditionalMediationAvailable) {
      PublicKeyCredential.isConditionalMediationAvailable().then(function(ok) {
        if (!ok) return;
        input.addEventListener('focus', function() { runGet('conditional').catch(function(){}); }, { once: true });
      });
    }
  }
})();
""".trimIndent()
