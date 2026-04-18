@file:OptIn(ExperimentalTime::class)

package id.walt.authop.endpoints

import id.walt.authop.config.ClientRegistry
import id.walt.authop.config.OidcRealmConfig
import id.walt.authop.config.RealmConfig
import id.walt.authop.config.RealmMethod
import id.walt.authop.config.RealmRegistry
import id.walt.authop.domain.AuthRequest
import id.walt.authop.module
import id.walt.authop.store.InMemoryAuthRequestStore
import id.walt.authop.store.InMemorySessionStore
import id.walt.authop.store.InMemoryUpstreamFlowStore
import id.walt.authop.store.UpstreamFlow
import id.walt.authop.testClient
import id.walt.authop.testConfig
import id.walt.authop.testDeps
import id.walt.authop.testKey
import id.walt.authop.upstream.OidcClient
import id.walt.crypto.keys.jwk.JWKKey
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.http.headersOf
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlin.time.Duration.Companion.minutes

/**
 * Tests for the OIDC realm adapter endpoints:
 *  - `GET /login/realm/{id}`  — kicks off the upstream OIDC flow.
 *  - `GET /callback/oidc`     — processes the upstream's return leg.
 *
 * Rather than build a layered mock of every collaborator (OidcClient + stores
 * + registries) we use a real [OidcClient] backed by a [MockEngine] that
 * emulates a compliant upstream OP. That exercises the signature / claim
 * verification path end-to-end exactly like `/callback/oidc` drives it in
 * production, keeping tests close to the real call graph.
 */
class OidcCallbackRoutesTest {

    // ---- fixtures -------------------------------------------------------------

    private val issuerUrl = "https://upstream.example"
    private val upstreamClientId = "rp-for-upstream"
    private val upstreamClientSecret = "upstream-secret"
    private val ourIssuer = "https://auth.example"
    private val upstreamKid = "upstream-kid-1"
    private val upstreamKey: JWKKey = testKey()
    private val fixedClock = TestClock(Instant.fromEpochSeconds(1_700_000_000))

    private fun realm(
        id: String = "employees",
        name: String = "Employees",
        method: RealmMethod = RealmMethod.OIDC,
        claimMapping: Map<String, String> = mapOf(
            "sub" to "$.sub",
            "email" to "$.email",
        ),
    ): RealmConfig = RealmConfig(
        id = id,
        name = name,
        method = method,
        oidc = if (method == RealmMethod.OIDC) OidcRealmConfig(
            issuer = issuerUrl,
            clientId = upstreamClientId,
            clientSecret = upstreamClientSecret,
            scopes = listOf("openid", "email"),
        ) else null,
        oid4vp = null,
        subStrategy = null,
        claimMapping = claimMapping,
    )

    private fun realmRegistry(vararg realms: RealmConfig): RealmRegistry =
        RealmRegistry(realms.associateBy { it.id })

    private fun authRequestFor(
        sid: String = "sid-abc",
        clientId: String = "rp1",
    ): AuthRequest = AuthRequest(
        authRequestId = sid,
        clientId = clientId,
        redirectUri = "https://rp/cb",
        scope = listOf("openid"),
        state = "round-trip",
        nonce = null,
        codeChallenge = "XYZ",
        codeChallengeMethod = "S256",
        prompt = null,
        chosenRealmId = null,
        subject = null,
        claims = emptyMap(),
    )

    private fun discoveryJson(): String = buildJsonObject {
        put("issuer", issuerUrl)
        put("authorization_endpoint", "$issuerUrl/auth")
        put("token_endpoint", "$issuerUrl/token")
        put("userinfo_endpoint", "$issuerUrl/userinfo")
        put("jwks_uri", "$issuerUrl/jwks.json")
    }.toString()

    private fun jwksJson(): String = runBlocking {
        val pub = upstreamKey.getPublicKey().exportJWKObject()
        val withKid = JsonObject(pub.toMutableMap().apply { put("kid", JsonPrimitive(upstreamKid)) })
        buildJsonObject { put("keys", buildJsonArray { add(withKid) }) }.toString()
    }

    private fun mintUpstreamIdToken(
        sub: String = "upstream-user-1",
        extra: Map<String, String> = mapOf("email" to "user@upstream.example"),
        nonce: String,
        aud: String = upstreamClientId,
        exp: Long = fixedClock.now().epochSeconds + 3600,
    ): String = runBlocking {
        val payload = buildJsonObject {
            put("iss", issuerUrl)
            put("sub", sub)
            put("aud", aud)
            put("iat", fixedClock.now().epochSeconds)
            put("exp", exp)
            put("nonce", nonce)
            for ((k, v) in extra) put(k, v)
        }
        upstreamKey.signJws(
            payload.toString().encodeToByteArray(),
            mapOf("typ" to JsonPrimitive("JWT"), "kid" to JsonPrimitive(upstreamKid)),
        )
    }

    private fun tokenResponseJson(idToken: String): String = buildJsonObject {
        put("token_type", "Bearer")
        put("id_token", idToken)
        put("access_token", "upstream-at")
        put("refresh_token", "upstream-rt")
        put("expires_in", 3600)
    }.toString()

    /**
     * Build an [OidcClient] that serves compliant discovery/jwks/token
     * endpoints, returning [idToken] for any token request. Errors in the
     * token exchange can be triggered by passing [tokenStatus] != OK.
     */
    private fun upstreamClient(
        idToken: String,
        tokenStatus: HttpStatusCode = HttpStatusCode.OK,
        tokenBody: String? = null,
    ): OidcClient {
        val engine = MockEngine { request ->
            when (request.url.encodedPath) {
                "/.well-known/openid-configuration" -> respond(
                    discoveryJson(),
                    HttpStatusCode.OK,
                    headersOf(HttpHeaders.ContentType, "application/json"),
                )
                "/jwks.json" -> respond(
                    jwksJson(),
                    HttpStatusCode.OK,
                    headersOf(HttpHeaders.ContentType, "application/json"),
                )
                "/token" -> respond(
                    tokenBody ?: tokenResponseJson(idToken),
                    tokenStatus,
                    headersOf(HttpHeaders.ContentType, "application/json"),
                )
                else -> respond("", HttpStatusCode.NotFound)
            }
        }
        return OidcClient(httpClient = HttpClient(engine), clock = fixedClock)
    }

    private fun ApplicationTestBuilder.noFollow(): HttpClient = createClient {
        followRedirects = false
    }

    // ==========================================================================
    // /login/realm/{id} — OIDC realm kickoff
    // ==========================================================================

    @Test
    fun `GET login realm id for OIDC realm redirects to upstream authorize URL`() = testApplication {
        val store = InMemoryAuthRequestStore(5.minutes).apply {
            put("sid-abc", authRequestFor())
        }
        val flows = InMemoryUpstreamFlowStore(5.minutes)
        application {
            module(
                testDeps(
                    config = testConfig(issuer = ourIssuer),
                    authRequestStore = store,
                    upstreamFlowStore = flows,
                    realmRegistry = realmRegistry(realm()),
                    oidcClient = upstreamClient(idToken = "unused"),
                ),
            )
        }

        val r = noFollow().get("/login/realm/employees") {
            header(HttpHeaders.Cookie, "sid=sid-abc")
        }

        assertEquals(HttpStatusCode.Found, r.status)
        val loc = assertNotNull(r.headers[HttpHeaders.Location])
        val url = Url(loc)
        assertEquals("upstream.example", url.host)
        assertEquals("/auth", url.encodedPath)
        assertEquals(upstreamClientId, url.parameters["client_id"])
        assertEquals("code", url.parameters["response_type"])
        assertEquals("$ourIssuer/callback/oidc", url.parameters["redirect_uri"])
        assertEquals("openid email", url.parameters["scope"])
    }

    @Test
    fun `upstream URL contains state and nonce`() = testApplication {
        val store = InMemoryAuthRequestStore(5.minutes).apply {
            put("sid-abc", authRequestFor())
        }
        val flows = InMemoryUpstreamFlowStore(5.minutes)
        application {
            module(
                testDeps(
                    config = testConfig(issuer = ourIssuer),
                    authRequestStore = store,
                    upstreamFlowStore = flows,
                    realmRegistry = realmRegistry(realm()),
                    oidcClient = upstreamClient(idToken = "unused"),
                ),
            )
        }

        val r = noFollow().get("/login/realm/employees") {
            header(HttpHeaders.Cookie, "sid=sid-abc")
        }

        val url = Url(assertNotNull(r.headers[HttpHeaders.Location]))
        val state = assertNotNull(url.parameters["state"])
        val nonce = assertNotNull(url.parameters["nonce"])
        assertTrue(state.isNotBlank())
        assertTrue(nonce.isNotBlank())
        assertFalse(state == nonce, "state and nonce must be distinct random values")

        // Kickoff persisted the flow keyed by the state we just saw.
        val flow = flows.consume(state)
        assertNotNull(flow)
        assertEquals("sid-abc", flow.authRequestId)
        assertEquals("employees", flow.realmId)
        assertEquals(nonce, flow.upstreamNonce)

        // AuthRequest was stamped with chosenRealmId.
        val updated = assertNotNull(store.get("sid-abc"))
        assertEquals("employees", updated.chosenRealmId)
    }

    @Test
    fun `unknown realm returns 400`() = testApplication {
        val store = InMemoryAuthRequestStore(5.minutes).apply {
            put("sid-abc", authRequestFor())
        }
        application {
            module(
                testDeps(
                    authRequestStore = store,
                    realmRegistry = realmRegistry(realm()),
                ),
            )
        }

        val r = noFollow().get("/login/realm/does-not-exist") {
            header(HttpHeaders.Cookie, "sid=sid-abc")
        }

        assertEquals(HttpStatusCode.BadRequest, r.status)
    }

    @Test
    fun `realm not in client allowed_realms returns 400`() = testApplication {
        val store = InMemoryAuthRequestStore(5.minutes).apply {
            put("sid-abc", authRequestFor())
        }
        val filtered = testClient(clientId = "rp1").copy(
            allowedRealms = listOf("employees"),
        )
        application {
            module(
                testDeps(
                    authRequestStore = store,
                    clientRegistry = ClientRegistry(mapOf("rp1" to filtered)),
                    realmRegistry = realmRegistry(
                        realm(id = "employees"),
                        realm(id = "citizens", name = "Citizens"),
                    ),
                ),
            )
        }

        val r = noFollow().get("/login/realm/citizens") {
            header(HttpHeaders.Cookie, "sid=sid-abc")
        }

        assertEquals(HttpStatusCode.BadRequest, r.status)
    }

    // ==========================================================================
    // /callback/oidc — upstream return leg
    // ==========================================================================

    /**
     * Seed the system into the state that exists at the instant the user agent
     * is bounced back to `/callback/oidc`: AuthRequest stored under `sid`,
     * UpstreamFlow stored under a known `state`.
     */
    private fun primedCallback(
        flows: InMemoryUpstreamFlowStore,
        store: InMemoryAuthRequestStore,
        state: String,
        nonce: String,
        sid: String = "sid-abc",
        realmId: String = "employees",
    ) {
        store.put(sid, authRequestFor(sid = sid))
        flows.put(
            state,
            UpstreamFlow(
                authRequestId = sid,
                realmId = realmId,
                upstreamNonce = nonce,
                issuer = issuerUrl,
                clientId = upstreamClientId,
                clientSecret = upstreamClientSecret,
                createdAt = Clock.System.now(),
            ),
        )
    }

    @Test
    fun `successful callback exchanges code creates session and redirects to consent`() = testApplication {
        val flows = InMemoryUpstreamFlowStore(5.minutes)
        val store = InMemoryAuthRequestStore(5.minutes)
        val sessions = InMemorySessionStore(5.minutes)
        val nonce = "upstream-nonce-1"
        primedCallback(flows, store, state = "state-abc", nonce = nonce)

        val idToken = mintUpstreamIdToken(sub = "upstream-user-1", nonce = nonce)
        application {
            module(
                testDeps(
                    config = testConfig(issuer = ourIssuer),
                    authRequestStore = store,
                    upstreamFlowStore = flows,
                    sessionStore = sessions,
                    realmRegistry = realmRegistry(realm()),
                    oidcClient = upstreamClient(idToken = idToken),
                ),
            )
        }

        val r = noFollow().get("/callback/oidc?code=c1&state=state-abc") {
            header(HttpHeaders.Cookie, "sid=sid-abc")
        }

        assertEquals(HttpStatusCode.Found, r.status)
        assertEquals("/consent", r.headers[HttpHeaders.Location])
        // Session must exist under sid.
        val session = assertNotNull(sessions.get("sid-abc"))
        assertEquals("upstream-user-1", session.subject)
        assertEquals("employees", session.realmId)
        assertEquals("urn:walt:upstream-oidc", session.acr)
    }

    @Test
    fun `callback with invalid state returns 400`() = testApplication {
        val flows = InMemoryUpstreamFlowStore(5.minutes)
        val store = InMemoryAuthRequestStore(5.minutes).apply {
            put("sid-abc", authRequestFor())
        }
        application {
            module(
                testDeps(
                    authRequestStore = store,
                    upstreamFlowStore = flows,
                    realmRegistry = realmRegistry(realm()),
                    oidcClient = upstreamClient(idToken = "unused"),
                ),
            )
        }

        val r = noFollow().get("/callback/oidc?code=c1&state=does-not-exist") {
            header(HttpHeaders.Cookie, "sid=sid-abc")
        }

        assertEquals(HttpStatusCode.BadRequest, r.status)
    }

    @Test
    fun `callback with mismatched sid cookie returns 400`() = testApplication {
        val flows = InMemoryUpstreamFlowStore(5.minutes)
        val store = InMemoryAuthRequestStore(5.minutes)
        val nonce = "n1"
        primedCallback(flows, store, state = "state-abc", nonce = nonce, sid = "sid-original")

        val idToken = mintUpstreamIdToken(nonce = nonce)
        application {
            module(
                testDeps(
                    authRequestStore = store,
                    upstreamFlowStore = flows,
                    realmRegistry = realmRegistry(realm()),
                    oidcClient = upstreamClient(idToken = idToken),
                ),
            )
        }

        val r = noFollow().get("/callback/oidc?code=c1&state=state-abc") {
            // Attacker ships a DIFFERENT sid cookie than the one bound to state.
            header(HttpHeaders.Cookie, "sid=sid-attacker")
        }

        assertEquals(HttpStatusCode.BadRequest, r.status)
        // And the flow MUST have been consumed (single-use), so a retry with
        // the correct cookie also fails.
        assertNull(flows.consume("state-abc"))
    }

    @Test
    fun `callback upstream error redirects to RP with access_denied`() = testApplication {
        val flows = InMemoryUpstreamFlowStore(5.minutes)
        val store = InMemoryAuthRequestStore(5.minutes)
        val nonce = "n-err"
        primedCallback(flows, store, state = "state-abc", nonce = nonce)

        // Upstream returns invalid_grant — OidcClient throws, handler maps
        // that to access_denied back to the RP.
        application {
            module(
                testDeps(
                    authRequestStore = store,
                    upstreamFlowStore = flows,
                    realmRegistry = realmRegistry(realm()),
                    oidcClient = upstreamClient(
                        idToken = "unused",
                        tokenStatus = HttpStatusCode.BadRequest,
                        tokenBody = """{"error":"invalid_grant"}""",
                    ),
                ),
            )
        }

        val r = noFollow().get("/callback/oidc?code=bad-code&state=state-abc") {
            header(HttpHeaders.Cookie, "sid=sid-abc")
        }

        assertEquals(HttpStatusCode.Found, r.status)
        val url = Url(assertNotNull(r.headers[HttpHeaders.Location]))
        assertEquals("rp", url.host)
        assertEquals("/cb", url.encodedPath)
        assertEquals("access_denied", url.parameters["error"])
        assertEquals("round-trip", url.parameters["state"])
        val desc = assertNotNull(url.parameters["error_description"])
        assertTrue(desc.startsWith("upstream:"), "description should carry upstream code; got: $desc")
    }

    @Test
    fun `session stores upstream id_token for future logout`() = testApplication {
        val flows = InMemoryUpstreamFlowStore(5.minutes)
        val store = InMemoryAuthRequestStore(5.minutes)
        val sessions = InMemorySessionStore(5.minutes)
        val nonce = "n-logout"
        primedCallback(flows, store, state = "state-abc", nonce = nonce)

        val idToken = mintUpstreamIdToken(nonce = nonce)
        application {
            module(
                testDeps(
                    authRequestStore = store,
                    upstreamFlowStore = flows,
                    sessionStore = sessions,
                    realmRegistry = realmRegistry(realm()),
                    oidcClient = upstreamClient(idToken = idToken),
                ),
            )
        }

        noFollow().get("/callback/oidc?code=c1&state=state-abc") {
            header(HttpHeaders.Cookie, "sid=sid-abc")
        }

        val session = assertNotNull(sessions.get("sid-abc"))
        assertEquals(
            idToken,
            session.upstreamIdToken,
            "upstream id_token must be stored on the session for Task 20 RP-initiated logout",
        )
    }

    @Test
    fun `AuthRequest updated with subject and claims after callback`() = testApplication {
        val flows = InMemoryUpstreamFlowStore(5.minutes)
        val store = InMemoryAuthRequestStore(5.minutes)
        val sessions = InMemorySessionStore(5.minutes)
        val nonce = "n-claims"
        primedCallback(flows, store, state = "state-abc", nonce = nonce)

        val idToken = mintUpstreamIdToken(
            sub = "upstream-user-42",
            nonce = nonce,
            extra = mapOf("email" to "u42@upstream.example"),
        )
        application {
            module(
                testDeps(
                    authRequestStore = store,
                    upstreamFlowStore = flows,
                    sessionStore = sessions,
                    realmRegistry = realmRegistry(realm()),
                    oidcClient = upstreamClient(idToken = idToken),
                ),
            )
        }

        noFollow().get("/callback/oidc?code=c1&state=state-abc") {
            header(HttpHeaders.Cookie, "sid=sid-abc")
        }

        val updated = assertNotNull(store.get("sid-abc"))
        assertEquals("upstream-user-42", updated.subject)
        assertEquals("employees", updated.chosenRealmId)
        // Mapped claims: sub + email (from claimMapping above). Plus the
        // callback-injected trio: namespaced `realm`, `acr`, `amr` — these
        // mirror what the callback stamps on [Session] and flow through to
        // the minted id_token via AuthCode.claims → JwtIssuer.mintIdToken.
        assertEquals(
            "upstream-user-42",
            updated.claims["sub"]?.jsonPrimitive?.content,
            "mapped sub must be on AuthRequest for /consent → /token",
        )
        assertEquals(
            "u42@upstream.example",
            updated.claims["email"]?.jsonPrimitive?.content,
        )
        assertEquals(
            "employees",
            updated.claims["$ourIssuer/realm"]?.jsonPrimitive?.content,
            "realm id must be projected as a namespaced custom claim (design doc rule)",
        )
        assertEquals(
            "urn:walt:upstream-oidc",
            updated.claims["acr"]?.jsonPrimitive?.content,
            "acr must be propagated from Session into AuthRequest.claims",
        )
        val amr = assertNotNull(updated.claims["amr"]) as JsonArray
        assertEquals(
            listOf("pwd"),
            amr.map { (it as JsonPrimitive).content },
            "amr must be propagated from Session into AuthRequest.claims",
        )
    }

    // ---- Minimal fixed clock (matches OidcClientTest pattern) ----------------

    private class TestClock(private val instant: Instant) : Clock {
        override fun now(): Instant = instant
    }
}
