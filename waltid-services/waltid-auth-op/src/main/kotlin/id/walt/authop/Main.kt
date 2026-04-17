package id.walt.authop

import id.walt.commons.ServiceConfiguration
import id.walt.commons.ServiceInitialization
import id.walt.commons.ServiceMain
import id.walt.commons.web.WebService
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.json.Json

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
    // WebService (from waltid-service-commons) installs ContentNegotiation via
    // configureSerialization() before invoking this module, so only install it
    // when running standalone (e.g. from `testApplication { application { module() } }`).
    if (pluginOrNull(ContentNegotiation) == null) {
        install(ContentNegotiation) {
            json(Json {
                ignoreUnknownKeys = true
                explicitNulls = false
                encodeDefaults = true
            })
        }
    }
    routing {
        get("/health") { call.respondText("ok") }
    }
}
