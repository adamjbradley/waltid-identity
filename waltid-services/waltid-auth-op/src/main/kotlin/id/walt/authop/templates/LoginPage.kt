package id.walt.authop.templates

import id.walt.authop.config.ClientConfig
import id.walt.authop.config.RealmConfig
import id.walt.authop.domain.AuthRequest
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.html.respondHtml
import kotlinx.html.a
import kotlinx.html.body
import kotlinx.html.h1
import kotlinx.html.head
import kotlinx.html.li
import kotlinx.html.meta
import kotlinx.html.title
import kotlinx.html.ul

/**
 * Minimal realm-picker HTML page rendered by `GET /login`.
 *
 * Scope is deliberately tiny for Task 9: show one link per realm allowed to
 * this client. The follow-up routes that actually start the realm flow
 * (`/login/realm/{id}` for OIDC, and the OID4VP counterpart) arrive in
 * Task 14 / Task 17. Those route handlers receive the same `sid` cookie
 * and thus recover the pending [AuthRequest] themselves.
 *
 * No CSS, no branding. Ktor HTML DSL's `+"..."` escapes attacker-reachable
 * text by default, so realm names that contain HTML metacharacters render
 * as text. Operator-controlled config is still treated as untrusted for
 * defence in depth (cf. the PLAIN_ERROR_PAGE escape path in OidcError).
 */
internal suspend fun ApplicationCall.respondLoginPage(
    @Suppress("UNUSED_PARAMETER") authReq: AuthRequest,
    @Suppress("UNUSED_PARAMETER") client: ClientConfig,
    realms: Collection<RealmConfig>,
) {
    respondHtml(HttpStatusCode.OK) {
        head {
            meta(charset = "utf-8")
            title { +"Sign in" }
        }
        body {
            h1 { +"Sign in" }
            ul {
                realms.forEach { realm ->
                    li {
                        // href target is Task 14/17's entry point. Building it
                        // here keeps the template self-contained and lets tests
                        // assert on link shape when those tasks land.
                        a(href = "/login/realm/${realm.id}") { +realm.name }
                    }
                }
            }
        }
    }
}
