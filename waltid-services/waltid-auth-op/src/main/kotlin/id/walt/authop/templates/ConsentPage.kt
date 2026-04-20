package id.walt.authop.templates

import id.walt.authop.config.ClientConfig
import id.walt.authop.config.RealmConfig
import id.walt.authop.config.ScopeDefinition
import id.walt.authop.domain.AuthRequest
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.html.respondHtml
import kotlinx.html.ButtonType
import kotlinx.html.FormMethod
import kotlinx.html.body
import kotlinx.html.button
import kotlinx.html.div
import kotlinx.html.form
import kotlinx.html.h1
import kotlinx.html.h2
import kotlinx.html.head
import kotlinx.html.hiddenInput
import kotlinx.html.id
import kotlinx.html.li
import kotlinx.html.link
import kotlinx.html.meta
import kotlinx.html.p
import kotlinx.html.script
import kotlinx.html.span
import kotlinx.html.style
import kotlinx.html.title
import kotlinx.html.ul
import kotlinx.html.unsafe
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Glass-UI consent screen matching the login page's immersive chrome.
 *
 * Renders two lists driven by the realm's scope catalog and the disclosed
 * claim set on the AuthRequest:
 *
 *  - **"Shared with {merchant} during this session"** — the PII surfaced
 *    for transparency (name, nationality, etc). Labelled so the user
 *    understands this is ephemeral display only.
 *  - **"{merchant} will keep"** — the booleans actually projected into the
 *    id_token ([id.walt.authop.claims.ScopeProjector]). Mirrors what
 *    completeConsent will persist.
 *
 * Falls back to the legacy scope-list rendering for realms without a scope
 * catalog (OIDC realms, or OID4VP realms still using the static DCQL file).
 *
 * Theme toggle + palette tokens duplicated from LoginPage so the two pages
 * look and feel identical without the risk of a full extract. `localStorage`
 * key is the same (`authop-theme`) so a theme chosen on /login carries
 * through to /consent with no flash.
 */
internal suspend fun ApplicationCall.respondConsentPage(
    authReq: AuthRequest,
    client: ClientConfig,
    realm: RealmConfig?,
    csrfToken: String,
) {
    val merchantName = client.clientId
    val scopeCatalog = realm?.oid4vp?.scopes ?: emptyMap()
    val sessionClaims = buildSessionClaims(authReq.claims, scopeCatalog, authReq.scope)
    val retainedClaims = buildRetainedClaims(scopeCatalog, authReq.scope, authReq.claims)

    respondHtml(HttpStatusCode.OK) {
        head {
            meta(charset = "utf-8")
            meta(name = "viewport", content = "width=device-width,initial-scale=1")
            title { +"Authorize $merchantName" }
            link(rel = "preconnect", href = "https://fonts.googleapis.com")
            link(rel = "preconnect", href = "https://fonts.gstatic.com") { attributes["crossorigin"] = "" }
            link(
                rel = "stylesheet",
                href = "https://fonts.googleapis.com/css2?family=Inter:wght@400;500;600;700&family=Space+Grotesk:wght@500;700&display=swap",
            )
            style { unsafe { +CONSENT_CSS } }
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
                        unsafe { +CHECK_SVG }
                    }
                    h1 { +"You're about to sign in" }
                    p {
                        id = "brand-subtitle"
                        +"Review what "
                        span { attributes["class"] = "merchant-name"; +merchantName }
                        +" will see and keep."
                    }
                }

                if (sessionClaims.isNotEmpty() || retainedClaims.isNotEmpty()) {
                    div {
                        attributes["class"] = "claim-group"
                        h2 {
                            attributes["class"] = "group-title"
                            +"Shared with "
                            span { attributes["class"] = "merchant-name-inline"; +merchantName }
                            +" this session"
                        }
                        p {
                            attributes["class"] = "group-lead"
                            +"Shown to the merchant for display only. Not stored after your session."
                        }
                        ul {
                            attributes["class"] = "claim-list session"
                            if (sessionClaims.isEmpty()) {
                                li {
                                    attributes["class"] = "claim-empty"
                                    +"Nothing — only a pseudonymous identifier."
                                }
                            } else {
                                sessionClaims.forEach { (label, value) ->
                                    li {
                                        attributes["class"] = "claim-row"
                                        span { attributes["class"] = "claim-label"; +label }
                                        span { attributes["class"] = "claim-value"; +value }
                                    }
                                }
                            }
                        }
                    }

                    div {
                        attributes["class"] = "claim-group kept"
                        h2 {
                            attributes["class"] = "group-title"
                            span {
                                attributes["class"] = "merchant-name-inline"
                                +merchantName
                            }
                            +" will keep"
                        }
                        p {
                            attributes["class"] = "group-lead"
                            +"The only information persisted by the merchant."
                        }
                        ul {
                            attributes["class"] = "claim-list retained"
                            if (retainedClaims.isEmpty()) {
                                li {
                                    attributes["class"] = "claim-empty"
                                    +"Only a pseudonymous identifier."
                                }
                            } else {
                                retainedClaims.forEach { (label, value) ->
                                    li {
                                        attributes["class"] = "claim-row"
                                        unsafe { +CHECK_MARK_SVG }
                                        span { attributes["class"] = "claim-label"; +label }
                                        span { attributes["class"] = "claim-value"; +value }
                                    }
                                }
                            }
                        }
                    }
                } else {
                    // Legacy path: no scope catalog → fall back to scope list.
                    div {
                        attributes["class"] = "claim-group"
                        h2 { attributes["class"] = "group-title"; +"The application is requesting" }
                        ul {
                            attributes["class"] = "claim-list session"
                            authReq.scope.forEach { scope ->
                                li {
                                    attributes["class"] = "claim-row"
                                    span { attributes["class"] = "claim-label"; +scopeDescription(scope) }
                                    span { attributes["class"] = "claim-value"; +scope }
                                }
                            }
                        }
                    }
                }

                form(action = "/consent", method = FormMethod.post) {
                    attributes["class"] = "consent-form"
                    hiddenInput(name = "csrf_token") { value = csrfToken }
                    button(type = ButtonType.submit, name = "decision") {
                        attributes["class"] = "primary-btn"
                        attributes["value"] = "accept"
                        span { +"Share & continue" }
                        unsafe { +ARROW_SVG }
                    }
                    button(type = ButtonType.submit, name = "decision") {
                        attributes["class"] = "ghost-btn deny-btn"
                        attributes["value"] = "deny"
                        span { +"Cancel" }
                    }
                }

                div {
                    id = "footer-fineprint"
                    +"Secured by auth-op · OpenID4VP / OIDC Core"
                }
            }

            script { unsafe { +CONSENT_JS } }
        }
    }
}

/** One claim row on the consent screen: human label + rendered value. */
private data class ConsentRow(val label: String, val value: String)

/**
 * Build the "shared this session" list — the human-readable claims disclosed
 * by the wallet. For each requested scope that's in the catalog we surface
 * the claim values that satisfy its `required_claims`. KYC scopes end up
 * showing name/nationality; age scopes end up showing "Yes"/"No"
 * confirmations. Values are already [ClaimMapper]-flattened into the
 * AuthRequest.claims map.
 */
private fun buildSessionClaims(
    disclosed: Map<String, JsonElement>,
    catalog: Map<String, ScopeDefinition>,
    requestedScopes: List<String>,
): List<ConsentRow> {
    if (catalog.isEmpty()) return emptyList()
    val rows = mutableListOf<ConsentRow>()
    val emitted = HashSet<String>()
    requestedScopes.forEach { scope ->
        val def = catalog[scope] ?: return@forEach
        def.requiredClaims.forEach { claimName ->
            if (emitted.add(claimName)) {
                val value = disclosed[claimName]?.let(::renderClaim)
                if (value != null) {
                    rows += ConsentRow(humaniseClaim(claimName), value)
                }
            }
        }
    }
    return rows
}

/**
 * Build the "merchant will keep" list — the projected id-token claims,
 * already boolean. This is the UI mirror of [id.walt.authop.claims.ScopeProjector];
 * keeping the logic near the page avoids rendering a mismatch between what
 * consent promises and what the token actually carries.
 */
private fun buildRetainedClaims(
    catalog: Map<String, ScopeDefinition>,
    requestedScopes: List<String>,
    disclosed: Map<String, JsonElement>,
): List<ConsentRow> {
    if (catalog.isEmpty()) return emptyList()
    return requestedScopes.mapNotNull { scope ->
        val def = catalog[scope] ?: return@mapNotNull null
        val idTokenClaim = def.idTokenClaim ?: return@mapNotNull null
        val allSatisfied = def.requiredClaims.all { name -> isClaimTruthy(disclosed[name]) }
        if (!allSatisfied) return@mapNotNull null
        ConsentRow(
            label = def.consentLabel ?: humaniseClaim(idTokenClaim),
            value = "Yes",
        )
    }
}

/** Humanise a machine claim name for display. `given_name` → "Given name". */
private fun humaniseClaim(name: String): String = name
    .replace('_', ' ')
    .replaceFirstChar { it.uppercaseChar() }

private fun renderClaim(element: JsonElement): String = when (element) {
    is JsonPrimitive -> when (val b = element.booleanOrNull) {
        true -> "Yes"
        false -> "No"
        null -> element.content
    }
    else -> element.toString()
}

private fun isClaimTruthy(element: JsonElement?): Boolean {
    if (element == null) return false
    val prim = element as? JsonPrimitive ?: return true
    return when (val b = prim.booleanOrNull) {
        null -> prim.content.isNotBlank() && prim.content != "null"
        else -> b
    }
}

/** Fallback copy for non-catalog scopes (OIDC realms etc). */
private fun scopeDescription(scope: String): String = when (scope) {
    "openid" -> "Verify your identity"
    "profile" -> "Access your profile information (name, preferred username)"
    "email" -> "Access your email address"
    else -> scope
}

private val CHECK_SVG = """
<svg viewBox="0 0 48 48" width="48" height="48" fill="none" aria-hidden="true">
  <defs>
    <linearGradient id="cbg" x1="0" y1="0" x2="48" y2="48" gradientUnits="userSpaceOnUse">
      <stop offset="0" stop-color="var(--accent)" stop-opacity="0.9"/>
      <stop offset="1" stop-color="var(--accent-2)" stop-opacity="0.6"/>
    </linearGradient>
  </defs>
  <rect x="2" y="2" width="44" height="44" rx="12" fill="url(#cbg)" opacity="0.25"/>
  <path d="M14 24l7 7 13-14" stroke="var(--accent)" stroke-width="3.2" stroke-linecap="round" stroke-linejoin="round"/>
</svg>
""".trimIndent()

private val CHECK_MARK_SVG = """
<svg class="claim-check" viewBox="0 0 20 20" width="14" height="14" fill="none" aria-hidden="true">
  <path d="M4 10l4 4 8-9" stroke="var(--accent)" stroke-width="2.6" stroke-linecap="round" stroke-linejoin="round"/>
</svg>
""".trimIndent()

private val ARROW_SVG = """
<svg viewBox="0 0 24 24" width="18" height="18" fill="none" aria-hidden="true">
  <path d="M4 12h15m-5-5 5 5-5 5" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/>
</svg>
""".trimIndent()

/**
 * Consent-page stylesheet. Shares the immersive chrome tokens (theme
 * palettes, mesh, glass card, theme toggle, buttons) with LoginPage; the
 * duplication is deliberate to decouple the two pages during this change.
 * See task #13 for a follow-up to DRY both into a SharedChrome module.
 */
private val CONSENT_CSS = """
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
  width: min(540px, 100%);
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
.merchant-name, .merchant-name-inline { color: var(--accent); font-weight: 600; }

.claim-group {
  margin-bottom: 22px;
  padding: 18px 20px;
  background: rgba(0,0,0,0.18);
  border: 1px solid var(--panel-border);
  border-radius: 16px;
}
.claim-group.kept {
  background: linear-gradient(180deg, rgba(255,255,255,0.06), rgba(255,255,255,0.02));
  border-color: var(--panel-border-hover);
}
.group-title { margin: 0 0 4px; font-family: 'Space Grotesk', Inter, sans-serif; font-weight: 600; font-size: 14px; letter-spacing: -0.01em; }
.group-lead { margin: 0 0 14px; color: var(--text-dim); font-size: 12px; line-height: 1.55; }

.claim-list { list-style: none; padding: 0; margin: 0; display: grid; gap: 8px; }
.claim-row {
  display: grid; grid-template-columns: auto 1fr auto; align-items: center; gap: 10px;
  padding: 10px 14px;
  background: rgba(255,255,255,0.03);
  border: 1px solid var(--panel-border);
  border-radius: 10px;
  font-size: 13px;
}
.claim-list.session .claim-row { grid-template-columns: 1fr auto; }
.claim-label { color: var(--text-dim); }
.claim-value { color: var(--text); font-weight: 600; letter-spacing: -0.005em; }
.claim-empty { padding: 10px 14px; color: var(--text-dim); font-size: 13px; font-style: italic; }
.claim-check { flex-shrink: 0; }

.consent-form { display: grid; grid-template-columns: 1fr; gap: 10px; margin-top: 12px; }

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
.deny-btn { color: var(--text-dim); }
.deny-btn:hover { color: var(--text); }

#footer-fineprint { text-align: center; font-size: 11px; color: var(--text-dim); margin-top: 24px; letter-spacing: 0.05em; text-transform: uppercase; }

@media (prefers-reduced-motion: reduce) {
  #mesh { animation: none; }
  #card { animation: none; }
}
@media (max-width: 520px) {
  #card { padding: 28px 22px 20px; border-radius: 22px; }
  #brand h1 { font-size: 24px; }
}
""".trimIndent()

private val CONSENT_JS = """
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
})();
""".trimIndent()
