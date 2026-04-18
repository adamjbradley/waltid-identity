package id.walt.authop

import id.walt.authop.config.AuthOpServiceConfig
import id.walt.authop.config.ClientConfig
import id.walt.authop.config.ClientRegistry
import id.walt.authop.config.RealmRegistry
import id.walt.authop.config.TokenEndpointAuthMethod
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
import id.walt.authop.upstream.OidcClient
import id.walt.authop.upstream.Verifier2Client
import id.walt.crypto.keys.KeyType
import id.walt.crypto.keys.jwk.JWKKey
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.ApplicationTestBuilder
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Shared test fixtures for auth-op endpoint tests.
 *
 * - [testConfig] — [AuthOpServiceConfig] with tunable issuer.
 * - [testKey] — fresh RSA [JWKKey] (no filesystem side-effects).
 * - [testDeps] — full [AuthOpDeps] with sensible defaults; tests override
 *   only the fields they need via named args.
 * - [toStringList] — small helper for decoding `JsonArray` primitive-string
 *   values (discovery metadata lists like `response_types_supported`).
 * - [jsonClient] — ktor test-client builder with JSON content negotiation.
 */
fun testConfig(
    issuer: String = "https://auth.example",
    signingKeyPath: String = "build/tmp/test-signing-key.json",
    cookieSecure: Boolean = false,
): AuthOpServiceConfig = AuthOpServiceConfig(
    issuer = issuer,
    signingKeyPath = signingKeyPath,
    cookieSecure = cookieSecure,
)

/** Generate a fresh RSA signing key for tests. Blocks briefly — fine for JUnit. */
fun testKey(): JWKKey = runBlocking { JWKKey.generate(KeyType.RSA) }

/**
 * Default test client — a single trusted RP with the two test scopes and one
 * registered redirect URI. Tests that need different shapes construct their own
 * `ClientRegistry` via the public ctor and pass it into [testDeps].
 */
fun testClient(
    clientId: String = "rp1",
    redirectUris: List<String> = listOf("https://rp/cb"),
    allowedScopes: List<String> = listOf("openid", "profile", "email"),
    trusted: Boolean = false,
): ClientConfig = ClientConfig(
    clientId = clientId,
    clientSecret = "secret",
    tokenEndpointAuthMethod = TokenEndpointAuthMethod.CLIENT_SECRET_BASIC,
    redirectUris = redirectUris,
    postLogoutRedirectUris = emptyList(),
    allowedScopes = allowedScopes,
    allowedRealms = emptyList(),
    trusted = trusted,
)

/**
 * Test `AuthOpDeps` with defaults: a single registered client `rp1`, an empty
 * realm registry, and a fresh in-memory [AuthRequestStore]. Override any field
 * via the named argument.
 *
 * The default TTL (5 minutes) is well over anything a unit test exercises, so
 * expiration never fires unless a specific test injects a test ticker.
 */
fun testDeps(
    config: AuthOpServiceConfig = testConfig(),
    signingKey: JWKKey = testKey(),
    clientRegistry: ClientRegistry = ClientRegistry(mapOf("rp1" to testClient())),
    realmRegistry: RealmRegistry = RealmRegistry(emptyMap()),
    authRequestStore: AuthRequestStore = InMemoryAuthRequestStore(5.minutes),
    sessionStore: SessionStore = InMemorySessionStore(5.minutes),
    authCodeStore: AuthCodeStore = InMemoryAuthCodeStore(60.seconds),
    csrfTokenStore: CsrfTokenStore = InMemoryCsrfTokenStore(10.minutes),
    upstreamFlowStore: UpstreamFlowStore = InMemoryUpstreamFlowStore(10.minutes),
    vpSessionStore: VpSessionStore = InMemoryVpSessionStore(10.minutes),
    jwtIssuer: JwtIssuer = JwtIssuer(signingKey, config.canonicalIssuer, 1.hours),
    oidcClient: OidcClient = mockOidcClient(),
    verifier2Client: Verifier2Client = mockVerifier2Client(),
): AuthOpDeps = AuthOpDeps(
    config = config,
    signingKey = signingKey,
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

/**
 * Default [OidcClient] used by test fixtures that don't exercise the upstream
 * flow. The MockEngine returns 404 for every request, so any unexpected call
 * fails loudly instead of hitting a real upstream. Tests that DO exercise
 * `/login/realm/{id}` or `/callback/oidc` pass their own preconfigured
 * [OidcClient] built on top of a MockEngine with matching responses.
 */
fun mockOidcClient(): OidcClient = OidcClient(
    httpClient = HttpClient(MockEngine { respond("not found", HttpStatusCode.NotFound) }),
)

/**
 * Default [Verifier2Client] used by test fixtures that don't exercise the VP
 * flow. The MockEngine returns 404 for every request, so any unexpected call
 * fails loudly. Tests that exercise `/login/realm/{id}` for an OID4VP realm
 * pass their own preconfigured [Verifier2Client].
 */
fun mockVerifier2Client(): Verifier2Client = Verifier2Client(
    httpClient = HttpClient(MockEngine { respond("not found", HttpStatusCode.NotFound) }),
)

/**
 * Variant of [testDeps] that lets a test provide a pre-built
 * [AuthRequestStore] with a custom TTL (e.g. for TTL-expiry tests).
 */
@Suppress("unused")
fun testDepsWithStoreTtl(ttl: Duration): AuthOpDeps =
    testDeps(authRequestStore = InMemoryAuthRequestStore(ttl))

/**
 * Decode a `JsonArray` of strings to `List<String>`. Throws on non-primitive
 * or non-string entries — that's intentional, metadata violations should be loud.
 */
fun JsonElement.toStringList(): List<String> =
    (this as JsonArray).jsonArray.map { it.jsonPrimitive.content }

/**
 * Build a ktor test [HttpClient] with JSON content negotiation installed so
 * `response.body<JsonObject>()` decodes automatically.
 */
fun ApplicationTestBuilder.jsonClient(): HttpClient = createClient {
    install(ContentNegotiation) { json() }
}
