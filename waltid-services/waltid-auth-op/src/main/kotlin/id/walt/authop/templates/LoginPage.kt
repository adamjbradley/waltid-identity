package id.walt.authop.templates

import id.walt.authop.config.ClientConfig
import id.walt.authop.config.RealmConfig
import id.walt.authop.domain.AuthRequest
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.html.respondHtml
import kotlinx.html.a
import kotlinx.html.body
import kotlinx.html.div
import kotlinx.html.h1
import kotlinx.html.head
import kotlinx.html.li
import kotlinx.html.meta
import kotlinx.html.p
import kotlinx.html.title
import kotlinx.html.ul
import kotlinx.html.unsafe

internal suspend fun ApplicationCall.respondLoginPage(
    @Suppress("UNUSED_PARAMETER") authReq: AuthRequest,
    @Suppress("UNUSED_PARAMETER") client: ClientConfig,
    realms: Collection<RealmConfig>,
) {
    respondHtml(HttpStatusCode.OK) {
        head {
            meta(charset = "utf-8")
            meta(name = "viewport", content = "width=device-width, initial-scale=1")
            title { +"Sign in" }
            unsafe {
                +"""<style>
*,*::before,*::after{box-sizing:border-box;margin:0;padding:0}
body{font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,sans-serif;background:#f0f4f8;min-height:100vh;display:flex;align-items:center;justify-content:center}
.card{background:#fff;border-radius:16px;box-shadow:0 4px 24px rgba(0,0,0,.08),0 1px 4px rgba(0,0,0,.05);padding:2.5rem 2rem;width:100%;max-width:400px;margin:1rem}
.brand{display:flex;justify-content:center;margin-bottom:1.75rem}
h1{font-size:1.375rem;font-weight:700;color:#111827;text-align:center;margin-bottom:.375rem}
.sub{font-size:.875rem;color:#6b7280;text-align:center;margin-bottom:1.75rem}
ul{list-style:none;display:flex;flex-direction:column;gap:.625rem}
ul a{display:flex;align-items:center;gap:.75rem;padding:.875rem 1.125rem;border:1.5px solid #e5e7eb;border-radius:10px;text-decoration:none;color:#374151;font-size:.9375rem;font-weight:500;transition:border-color .15s,background .15s,color .15s}
ul a:hover{border-color:#4f46e5;background:#f5f3ff;color:#4f46e5}
ul a::before{content:'';display:inline-block;width:8px;height:8px;border-radius:50%;background:#4f46e5;flex-shrink:0}
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
                h1 { +"Sign in" }
                p(classes = "sub") { +"Choose how you'd like to continue" }
                ul {
                    realms.forEach { realm ->
                        li {
                            a(href = "/login/realm/${realm.id}") { +realm.name }
                        }
                    }
                }
            }
        }
    }
}
