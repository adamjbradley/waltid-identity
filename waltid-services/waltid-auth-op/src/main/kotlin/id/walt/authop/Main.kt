package id.walt.authop

import id.walt.authop.config.AuthOpServiceConfig
import id.walt.authop.config.ClientRegistry
import id.walt.authop.config.RealmRegistry
import id.walt.authop.endpoints.authorizeRoutes
import id.walt.authop.endpoints.consentRoutes
import id.walt.authop.endpoints.discoveryRoutes
import id.walt.authop.endpoints.loginRoutes
import id.walt.authop.endpoints.oidcCallbackRoutes
import id.walt.authop.endpoints.tokenRoutes
import id.walt.authop.endpoints.userInfoRoutes
import id.walt.authop.endpoints.vpStatusRoutes
import id.walt.authop.endpoints.vpWebhookRoutes
import id.walt.authop.store.AuthCodeStore
import id.walt.authop.store.AuthRequestStore
import id.walt.authop.store.CsrfTokenStore
import id.walt.authop.store.InMemoryAuthCodeStore
import id.walt.authop.store.InMemoryAuthRequestStore
import id.walt.authop.store.InMemoryCsrfTokenStore
import id.walt.authop.store.InMemorySessionStore
import id.walt.authop.store.InMemoryUpstreamFlowStore
import id.walt.authop.store.InMemoryVpSessionStore
import id.walt.authop.store.SessionStore
import id.walt.authop.store.UpstreamFlowStore
import id.walt.authop.store.VpSessionStore
import id.walt.authop.tokens.JwtIssuer
import id.walt.authop.tokens.KeyProvider
import id.walt.authop.upstream.OidcClient
import id.walt.authop.upstream.Verifier2Client
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
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Runtime dependencies resolved during [ServiceInitialization.init] and consumed
 * by [Application.runtimeModule]. They live in a singleton rather than being
 * threaded through `Application.attributes` so the `WebService(Application::*)`
 * bridge — which expects a zero-arg extension — stays simple.
 *
 * Tests never touch this object; they call [module] directly with their own
 * [AuthOpDeps] (see `TestFixtures.kt`).
 */
internal object AuthOpRuntime {
    @Volatile
    var deps: AuthOpDeps? = null

    fun requireDeps(): AuthOpDeps =
        deps ?: error("AuthOpDeps not initialised — ServiceInitialization.init should set this")
}

/**
 * Default TTL for in-flight [id.walt.authop.domain.AuthRequest] entries. The
 * design doc §State fixes this at 10 min (the human-pacing upper bound for
 * completing a login — realm pick + upstream auth + consent).
 */
private val AUTH_REQUEST_TTL = 10.minutes

/**
 * Default TTL for authenticated user [id.walt.authop.domain.Session] entries.
 * One hour matches a typical browser-session SSO window — long enough to
 * survive tab navigation and an upstream re-auth round-trip, short enough
 * that an abandoned browser doesn't keep a live session indefinitely.
 * Tunable via config in a later task if real deployments need something else.
 */
private val SESSION_TTL = 1.hours

/**
 * Default TTL for issued [id.walt.authop.domain.AuthCode] entries. The design
 * doc §State fixes this at 60s and flags single-use via [AuthCodeStore.consume];
 * keeping the window tight shrinks the replay surface between `/authorize` and
 * the RP's `/token` exchange.
 */
private val AUTH_CODE_TTL = 60.seconds

/**
 * Default TTL for CSRF tokens on the `/consent` page. 10 min matches the
 * AuthRequest TTL — if the user sits on the consent page past AuthRequest
 * expiry, submitting the form would 400 on AuthRequest lookup anyway, so the
 * CSRF TTL never needs to be longer.
 */
private val CSRF_TTL = 10.minutes

/**
 * Default TTL for upstream-OIDC kickoff state. The user has this long from
 * choosing a realm to completing the upstream flow and hitting `/callback/oidc`.
 * 10 min matches the AuthRequest TTL — a longer window buys nothing because
 * the callback would fail on AuthRequest lookup anyway.
 */
private val UPSTREAM_FLOW_TTL = 10.minutes

/**
 * Default TTL for in-flight VP sessions held on the verifier-api2 side.
 * 10 min matches the AuthRequest TTL — same human-pacing bound applies: the
 * user scans a QR code, opens the wallet, consents, and presents. Past this
 * window the AuthRequest is gone anyway, so a longer VpSession TTL buys
 * nothing.
 */
private val VP_SESSION_TTL = 10.minutes

suspend fun main(args: Array<String>) {
    ServiceMain(
        ServiceConfiguration("auth-op"),
        ServiceInitialization(
            features = AuthOpFeatureCatalog,
            init = {
                // Configs are loaded by the commons framework before init runs.
                val cfg = ConfigManager.getConfig<AuthOpServiceConfig>()
                // Load-or-generate the signing key. `loadOrCreate` is suspending,
                // but init is a plain lambda — runBlocking is the repo-standard
                // bridge (see CIProvider for an analogous use).
                val key = runBlocking {
                    KeyProvider.loadOrCreate(Paths.get(cfg.signingKeyPath))
                }
                // Registries are read-once snapshots from HOCON files colocated
                // with auth-op.conf. Paths hardcoded for v1 — if/when we need
                // per-deployment overrides they'll move onto AuthOpServiceConfig.
                val clientRegistry = ClientRegistry.load("config/clients.conf")
                val realmRegistry = RealmRegistry.load("config/realms.conf")
                val authRequestStore: AuthRequestStore = InMemoryAuthRequestStore(AUTH_REQUEST_TTL)
                val sessionStore: SessionStore = InMemorySessionStore(SESSION_TTL)
                val authCodeStore: AuthCodeStore = InMemoryAuthCodeStore(AUTH_CODE_TTL)
                val csrfTokenStore: CsrfTokenStore = InMemoryCsrfTokenStore(CSRF_TTL)
                val upstreamFlowStore: UpstreamFlowStore = InMemoryUpstreamFlowStore(UPSTREAM_FLOW_TTL)
                val vpSessionStore: VpSessionStore = InMemoryVpSessionStore(VP_SESSION_TTL)
                // Single OidcClient per process — the internal Caffeine caches
                // (discovery + JWKS) are most useful when shared across realm
                // kickoffs / callbacks. HttpClient is the production default
                // (OkHttp + ContentNegotiation/json) via the companion.
                val oidcClient = OidcClient()
                // Verifier2Client has no caches to share; we still keep a single
                // instance so the OkHttp client's connection pool is reused across
                // VP kickoffs.
                val verifier2Client = Verifier2Client()
                // JwtIssuer is constructed once at startup with the canonical
                // issuer string (byte-exact match with discovery metadata) and
                // the loaded signing key. Token TTL is fixed at 1h here — OIDC
                // Core's recommendation for ID tokens; access tokens share it
                // for v1. Tunable via config in a later task if needed.
                val jwtIssuer = JwtIssuer(
                    key = key,
                    iss = cfg.canonicalIssuer,
                    lifetime = 1.hours,
                )

                AuthOpRuntime.deps = AuthOpDeps(
                    config = cfg,
                    signingKey = key,
                    clientRegistry = clientRegistry,
                    realmRegistry = realmRegistry,
                    authRequestStore = authRequestStore,
                    sessionStore = sessionStore,
                    authCodeStore = authCodeStore,
                    csrfTokenStore = csrfTokenStore,
                    upstreamFlowStore = upstreamFlowStore,
                    vpSessionStore = vpSessionStore,
                    jwtIssuer = jwtIssuer,
                    oidcClient = oidcClient,
                    verifier2Client = verifier2Client,
                )
            },
            run = WebService(Application::runtimeModule).run(),
        )
    ).main(args)
}

/**
 * Production module. Reads the runtime-resolved [AuthOpDeps] from
 * [AuthOpRuntime] and delegates to the testable [module] overload.
 *
 * Kept separate from [module] so tests can bypass the runtime singleton and
 * pass explicit fixtures.
 */
fun Application.runtimeModule() {
    module(AuthOpRuntime.requireDeps())
}

/**
 * Testable module. Receives the full [AuthOpDeps] so tests can construct an
 * application with synthetic values without mutating global state.
 *
 * The dep container grows over time (Task 9 adds sessions, Task 11 adds a
 * JwtIssuer, Task 14 adds upstream HTTP clients, etc.) but this function
 * signature stays 1-arg.
 */
fun Application.module(deps: AuthOpDeps) {
    // Server-side JSON content negotiation so routes that call `call.respond(jsonObject)`
    // serialise correctly. `WebService.webServiceModule` installs ContentNegotiation via
    // `configureSerialization()` before invoking this module, so in production the guard
    // skips the duplicate (a second `install(ContentNegotiation)` would throw). Tests
    // call `application { module(...) }` directly — bypassing WebService — so the guard's
    // install runs to give the test a JSON-capable server.
    if (pluginOrNull(ContentNegotiation) == null) {
        install(ContentNegotiation) { json() }
    }
    routing {
        get("/health") { call.respondText("ok") }
        discoveryRoutes(deps.config, deps.signingKey)
        authorizeRoutes(deps.clientRegistry, deps.realmRegistry, deps.authRequestStore, deps.config)
        loginRoutes(deps)
        oidcCallbackRoutes(deps)
        vpStatusRoutes(deps)
        vpWebhookRoutes(deps)
        consentRoutes(deps)
        tokenRoutes(deps)
        userInfoRoutes(deps)
    }
}
