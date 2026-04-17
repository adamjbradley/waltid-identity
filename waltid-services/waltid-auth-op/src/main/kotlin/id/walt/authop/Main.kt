package id.walt.authop

import id.walt.authop.config.AuthOpServiceConfig
import id.walt.authop.endpoints.discoveryRoutes
import id.walt.authop.tokens.KeyProvider
import id.walt.commons.ServiceConfiguration
import id.walt.commons.ServiceInitialization
import id.walt.commons.ServiceMain
import id.walt.commons.config.ConfigManager
import id.walt.commons.web.WebService
import id.walt.crypto.keys.jwk.JWKKey
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.*
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.runBlocking
import java.nio.file.Paths

/**
 * Runtime dependencies resolved during [ServiceInitialization.init] and consumed
 * by [Application.runtimeModule]. They live in a singleton rather than being
 * threaded through `Application.attributes` so the `WebService(Application::*)`
 * bridge — which expects a zero-arg extension — stays simple.
 *
 * Tests never touch this object; they call [module] directly with their own
 * dependencies (see `TestFixtures.kt`).
 */
internal object AuthOpRuntime {
    @Volatile
    var config: AuthOpServiceConfig? = null

    @Volatile
    var signingKey: JWKKey? = null

    fun requireConfig(): AuthOpServiceConfig =
        config ?: error("AuthOpServiceConfig not initialised — ServiceInitialization.init should set this")

    fun requireSigningKey(): JWKKey =
        signingKey ?: error("Signing key not initialised — ServiceInitialization.init should set this")
}

suspend fun main(args: Array<String>) {
    ServiceMain(
        ServiceConfiguration("auth-op"),
        ServiceInitialization(
            features = AuthOpFeatureCatalog,
            init = {
                // Configs are loaded by the commons framework before init runs.
                val cfg = ConfigManager.getConfig<AuthOpServiceConfig>()
                AuthOpRuntime.config = cfg
                // Load-or-generate the signing key. `loadOrCreate` is suspending,
                // but init is a plain lambda — runBlocking is the repo-standard
                // bridge (see CIProvider for an analogous use).
                AuthOpRuntime.signingKey = runBlocking {
                    KeyProvider.loadOrCreate(Paths.get(cfg.signingKeyPath))
                }
            },
            run = WebService(Application::runtimeModule).run(),
        )
    ).main(args)
}

/**
 * Production module. Reads the runtime-resolved config + signing key from
 * [AuthOpRuntime] and delegates to the testable [module] overload.
 *
 * Kept separate from [module] so tests can bypass the runtime singleton and
 * pass explicit fixtures.
 */
fun Application.runtimeModule() {
    module(AuthOpRuntime.requireConfig(), AuthOpRuntime.requireSigningKey())
}

/**
 * Testable module. Receives the service config + signing key directly so tests
 * can construct an application with synthetic values without mutating global
 * state.
 */
fun Application.module(config: AuthOpServiceConfig, signingKey: JWKKey) {
    // Server-side JSON content negotiation so routes that call `call.respond(jsonObject)`
    // serialise correctly. The production bridge (`WebService.webServiceModule`) also
    // calls `configureSerialization()`, but that only runs via `WebService` — the
    // direct `module(...)` entry point used by tests needs its own install. Idempotent:
    // a duplicate install would throw, but `WebService` wraps `module` rather than
    // calling it under its own content-negotiation, so there is no double-install.
    if (pluginOrNull(ContentNegotiation) == null) {
        install(ContentNegotiation) { json() }
    }
    routing {
        get("/health") { call.respondText("ok") }
        discoveryRoutes(config, signingKey)
    }
}
