package id.walt.authop.templates

import id.walt.authop.config.ClientConfig
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
import kotlinx.html.head
import kotlinx.html.hiddenInput
import kotlinx.html.li
import kotlinx.html.meta
import kotlinx.html.p
import kotlinx.html.title
import kotlinx.html.ul
import kotlinx.html.unsafe

/**
 * Consent page rendered by `GET /consent` for non-trusted clients.
 *
 * Escaping: all interpolated values go through Ktor HTML DSL's `+"..."` which
 * escapes text nodes, preventing XSS from attacker-controllable client IDs or
 * scope strings.
 *
 * The hidden `csrf_token` input carries the one-time value issued by
 * [id.walt.authop.store.CsrfTokenStore.issue]; the POST handler calls
 * [id.walt.authop.store.CsrfTokenStore.validate] which consumes it.
 */
internal suspend fun ApplicationCall.respondConsentPage(
    authReq: AuthRequest,
    client: ClientConfig,
    csrfToken: String,
) {
    respondHtml(HttpStatusCode.OK) {
        head {
            meta(charset = "utf-8")
            meta(name = "viewport", content = "width=device-width, initial-scale=1")
            title { +"Authorize ${client.clientId}" }
            unsafe {
                +"""<style>
*,*::before,*::after{box-sizing:border-box;margin:0;padding:0}
body{font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,sans-serif;background:#f0f4f8;min-height:100vh;display:flex;align-items:center;justify-content:center}
.card{background:#fff;border-radius:16px;box-shadow:0 4px 24px rgba(0,0,0,.08),0 1px 4px rgba(0,0,0,.05);padding:2.5rem 2rem;width:100%;max-width:400px;margin:1rem}
.brand{display:flex;justify-content:center;margin-bottom:1.75rem}
h1{font-size:1.25rem;font-weight:700;color:#111827;text-align:center;margin-bottom:.375rem}
.sub{font-size:.875rem;color:#6b7280;text-align:center;margin-bottom:1.5rem}
ul{list-style:none;display:flex;flex-direction:column;gap:.375rem;margin-bottom:1.75rem;padding:.75rem 1rem;background:#f9fafb;border-radius:8px;border:1px solid #f3f4f6}
ul li{font-size:.875rem;color:#374151;padding:.25rem 0;display:flex;align-items:center;gap:.625rem}
ul li::before{content:'✓';color:#059669;font-weight:700;flex-shrink:0}
.actions{display:flex;gap:.75rem}
.btn{flex:1;padding:.875rem 1rem;border-radius:10px;font-size:.9375rem;font-weight:600;cursor:pointer;border:none;transition:background .15s,opacity .15s}
.btn-allow{background:#4f46e5;color:#fff}
.btn-allow:hover{background:#4338ca}
.btn-deny{background:#fff;color:#6b7280;border:1.5px solid #e5e7eb}
.btn-deny:hover{border-color:#d1d5db;color:#374151}
</style>"""
            }
        }
        body {
            div(classes = "card") {
                div(classes = "brand") {
                    unsafe {
                        +"""<svg width="44" height="44" viewBox="0 0 44 44" fill="none" xmlns="http://www.w3.org/2000/svg"><rect width="44" height="44" rx="11" fill="#ede9fe"/><path d="M22 9L12 14v10c0 6.1 4.8 11.7 10 13.3C27.2 35.7 32 30.1 32 24V14L22 9z" fill="#4f46e5"/><path d="M18.5 22l2.5 2.5 4.5-4.5" stroke="#fff" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/></svg>"""
                    }
                }
                h1 { +"Authorize ${client.clientId}" }
                p(classes = "sub") { +"The application is requesting:" }
                ul {
                    authReq.scope.forEach { scope ->
                        li { +scopeDescription(scope) }
                    }
                }
                form(action = "/consent", method = FormMethod.post) {
                    hiddenInput(name = "csrf_token") { value = csrfToken }
                    div(classes = "actions") {
                        button(type = ButtonType.submit, name = "decision", classes = "btn btn-allow") {
                            attributes["value"] = "accept"
                            +"Allow"
                        }
                        button(type = ButtonType.submit, name = "decision", classes = "btn btn-deny") {
                            attributes["value"] = "deny"
                            +"Deny"
                        }
                    }
                }
            }
        }
    }
}

/**
 * Human-readable description for a requested scope. Only the three standard
 * OIDC scopes carry a description; anything else falls through to the raw scope string.
 */
private fun scopeDescription(scope: String): String = when (scope) {
    "openid" -> "Verify your identity"
    "profile" -> "Access your profile information (name, preferred username)"
    "email" -> "Access your email address"
    else -> scope
}
