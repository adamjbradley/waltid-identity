package id.walt.authop

import id.walt.commons.ServiceConfiguration
import id.walt.commons.ServiceInitialization
import id.walt.commons.ServiceMain
import id.walt.commons.web.WebService
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

suspend fun main(args: Array<String>) {
    ServiceMain(
        ServiceConfiguration("auth-op"),
        ServiceInitialization(
            features = AuthOpFeatureCatalog,
            init = {
                // Empty for now — future tasks will wire OIDC stores / key services here.
            },
            run = WebService(Application::module).run()
        )
    ).main(args)
}

fun Application.module() {
    routing {
        get("/health") { call.respondText("ok") }
    }
}
