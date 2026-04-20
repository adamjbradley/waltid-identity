package id.walt.authop.templates

import id.walt.authop.config.ClientConfig
import id.walt.authop.domain.AuthRequest
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.html.respondHtml
import kotlinx.html.ButtonType
import kotlinx.html.FormMethod
import kotlinx.html.InputType
import kotlinx.html.body
import kotlinx.html.button
import kotlinx.html.form
import kotlinx.html.h1
import kotlinx.html.head
import kotlinx.html.hiddenInput
import kotlinx.html.li
import kotlinx.html.meta
import kotlinx.html.p
import kotlinx.html.title
import kotlinx.html.ul

/**
 * Minimal consent page rendered by `GET /consent` for non-trusted clients.
 *
 * Scope rendering is deliberately narrow: only the three standard OIDC scopes
 * carry a human-readable description. Any additional scopes the RP requested
 * render as the raw scope string — that's a conscious v1 tradeoff. Custom
 * scopes are a Task 14+ concern once realm-level claim mapping expands.
 *
 * Escaping: `client_id` comes straight from attacker-controllable config /
 * URL lookup (the RP could in theory be registered with an exotic id), and
 * scope values similarly travel end-to-end from the wire. Ktor HTML DSL's
 * `+"..."` escapes text nodes, so every interpolated value renders as text.
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
            title { +"Authorize ${client.clientId}" }
        }
        body {
            // client_id is the only identity we have for v1 — no display_name
            // yet on ClientConfig (design doc explicitly cuts that). If we add
            // a display_name field later, this is the single place to update.
            h1 { +"Authorize ${client.clientId}" }
            p { +"The application is requesting:" }
            ul {
                authReq.scope.forEach { scope ->
                    li { +scopeDescription(scope) }
                }
            }
            form(action = "/consent", method = FormMethod.post) {
                hiddenInput(name = "csrf_token") { value = csrfToken }
                button(type = ButtonType.submit, name = "decision") {
                    attributes["value"] = "accept"
                    +"Allow"
                }
                button(type = ButtonType.submit, name = "decision") {
                    attributes["value"] = "deny"
                    +"Deny"
                }
            }
        }
    }
}

/**
 * Human-readable description for a requested scope. Only the three standard
 * OIDC scopes carry copy; anything else falls through to the raw scope string.
 * Copy comes from the Task 10 spec §Consent page HTML.
 */
private fun scopeDescription(scope: String): String = when (scope) {
    "openid" -> "Verify your identity"
    "profile" -> "Access your profile information (name, preferred username)"
    "email" -> "Access your email address"
    else -> scope
}
