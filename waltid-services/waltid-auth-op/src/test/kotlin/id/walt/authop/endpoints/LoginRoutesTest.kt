@file:OptIn(ExperimentalTime::class)

package id.walt.authop.endpoints

import id.walt.authop.config.ClientRegistry
import id.walt.authop.config.OidcRealmConfig
import id.walt.authop.config.RealmConfig
import id.walt.authop.config.RealmMethod
import id.walt.authop.config.RealmRegistry
import id.walt.authop.domain.AuthRequest
import id.walt.authop.domain.Session
import id.walt.authop.module
import id.walt.authop.store.InMemoryAuthRequestStore
import id.walt.authop.store.InMemorySessionStore
import id.walt.authop.testClient
import id.walt.authop.testDeps
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.Duration.Companion.minutes

/**
 * Tests for `GET /login`.
 *
 * Every test follows the same pattern: seed stores with an [AuthRequest]
 * (and optionally a [Session]) under a known `sid`, then hit `/login`
 * with a `Cookie: sid=…` header. The default test client has
 * `followRedirects = false` so 302s are observable and /consent's absence
 * (Task 10) doesn't leak into assertions.
 */
class LoginRoutesTest {

    private fun ApplicationTestBuilder.noFollowClient(): HttpClient = createClient {
        followRedirects = false
    }

    /** Shorthand for the standard auth request used in happy-path tests. */
    private fun fixtureAuthRequest(
        sid: String = "sid-abc",
        prompt: String? = null,
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
        prompt = prompt,
        chosenRealmId = null,
        subject = null,
        claims = emptyMap(),
    )

    private fun fixtureSession(realmId: String = "employees", sub: String = "user-42") = Session(
        sessionId = "s-abc",
        subject = sub,
        realmId = realmId,
        amr = listOf("pwd"),
        acr = null,
        authTime = Clock.System.now(),
        upstreamIdToken = null,
    )

    private fun realm(id: String, name: String = id): RealmConfig = RealmConfig(
        id = id,
        name = name,
        method = RealmMethod.OIDC,
        oidc = OidcRealmConfig(
            issuer = "https://upstream.example/$id",
            clientId = "c",
            clientSecret = "s",
        ),
    )

    private fun realmRegistry(vararg realms: RealmConfig): RealmRegistry =
        RealmRegistry(realms.associateBy { it.id })

    // -- Spec-required tests (names verbatim, except the documented adjust) --

    @Test
    fun `login page lists configured realms`() = testApplication {
        val store = InMemoryAuthRequestStore(5.minutes).apply {
            put("sid-abc", fixtureAuthRequest())
        }
        application {
            module(
                testDeps(
                    authRequestStore = store,
                    realmRegistry = realmRegistry(
                        realm("employees", "Employees"),
                        realm("citizens", "Citizens"),
                    ),
                ),
            )
        }

        val r = noFollowClient().get("/login") {
            header(HttpHeaders.Cookie, "sid=sid-abc")
        }

        assertEquals(HttpStatusCode.OK, r.status)
        val body = r.bodyAsText()
        assertTrue("Employees" in body, "realm name 'Employees' must appear; body: $body")
        assertTrue("Citizens" in body, "realm name 'Citizens' must appear; body: $body")
    }

    @Test
    fun `prompt=none without session redirects with login_required`() = testApplication {
        val store = InMemoryAuthRequestStore(5.minutes).apply {
            put("sid-abc", fixtureAuthRequest(prompt = "none"))
        }
        application {
            module(testDeps(authRequestStore = store, realmRegistry = realmRegistry(realm("r"))))
        }

        val r = noFollowClient().get("/login") {
            header(HttpHeaders.Cookie, "sid=sid-abc")
        }

        assertEquals(HttpStatusCode.Found, r.status)
        val loc = assertNotNull(r.headers[HttpHeaders.Location])
        assertTrue("error=login_required" in loc, "Location must carry login_required; got: $loc")
        val url = Url(loc)
        assertEquals("rp", url.host, "must redirect to the RP's redirect_uri; got: $loc")
        assertEquals("round-trip", url.parameters["state"], "state must be echoed")
    }

    /**
     * Task 9 adjustment (documented in the plan): the spec test name
     * `prompt=none with session returns code silently` presumes the
     * /consent + /token machinery, which is Task 10/11. Here we assert
     * the intermediate observable — a 302 to /consent with the sid
     * carried across — and leave the code-mint assertion to Task 10.
     */
    @Test
    fun `prompt=none with session redirects to consent`() = testApplication {
        val store = InMemoryAuthRequestStore(5.minutes).apply {
            put("sid-abc", fixtureAuthRequest(prompt = "none"))
        }
        val sessions = InMemorySessionStore(5.minutes).apply {
            put("sid-abc", fixtureSession())
        }
        application {
            module(
                testDeps(
                    authRequestStore = store,
                    sessionStore = sessions,
                    realmRegistry = realmRegistry(realm("r")),
                ),
            )
        }

        val r = noFollowClient().get("/login") {
            header(HttpHeaders.Cookie, "sid=sid-abc")
        }

        assertEquals(HttpStatusCode.Found, r.status)
        assertEquals("/consent", r.headers[HttpHeaders.Location])
        // The AuthRequest must now carry the session's subject.
        val updated = assertNotNull(store.get("sid-abc"))
        assertEquals("user-42", updated.subject)
        assertEquals("employees", updated.chosenRealmId)
    }

    @Test
    fun `prompt=login forces re-auth even with session`() = testApplication {
        val store = InMemoryAuthRequestStore(5.minutes).apply {
            put("sid-abc", fixtureAuthRequest(prompt = "login"))
        }
        val sessions = InMemorySessionStore(5.minutes).apply {
            put("sid-abc", fixtureSession())
        }
        application {
            module(
                testDeps(
                    authRequestStore = store,
                    sessionStore = sessions,
                    realmRegistry = realmRegistry(
                        realm("employees", "Employees"),
                        realm("citizens", "Citizens"),
                    ),
                ),
            )
        }

        val r = noFollowClient().get("/login") {
            header(HttpHeaders.Cookie, "sid=sid-abc")
        }

        // Despite the live session, we must render the picker — not redirect.
        assertEquals(HttpStatusCode.OK, r.status)
        val body = r.bodyAsText()
        assertTrue("Employees" in body)
        assertTrue("Citizens" in body)
        // And the AuthRequest must NOT have been hydrated with the session
        // subject (that's consent's job once re-auth completes).
        val current = assertNotNull(store.get("sid-abc"))
        assertEquals(null, current.subject)
    }

    @Test
    fun `allowed_realms on client filters visible realms`() = testApplication {
        val store = InMemoryAuthRequestStore(5.minutes).apply {
            put("sid-abc", fixtureAuthRequest())
        }
        val filteredClient = testClient(
            clientId = "rp1",
            allowedScopes = listOf("openid"),
        ).copy(allowedRealms = listOf("employees"))
        application {
            module(
                testDeps(
                    authRequestStore = store,
                    clientRegistry = ClientRegistry(mapOf(filteredClient.clientId to filteredClient)),
                    realmRegistry = realmRegistry(
                        realm("employees", "Employees"),
                        realm("citizens", "Citizens"),
                    ),
                ),
            )
        }

        val r = noFollowClient().get("/login") {
            header(HttpHeaders.Cookie, "sid=sid-abc")
        }

        assertEquals(HttpStatusCode.OK, r.status)
        val body = r.bodyAsText()
        assertTrue("Employees" in body, "allowed realm must be visible; body: $body")
        assertFalse("Citizens" in body, "disallowed realm must not be visible; body: $body")
    }

    // -- Additional coverage --------------------------------------------------

    @Test
    fun `no sid cookie returns 400`() = testApplication {
        application { module(testDeps()) }
        val r = noFollowClient().get("/login")

        assertEquals(HttpStatusCode.BadRequest, r.status)
        val body = r.bodyAsText()
        assertTrue("invalid_request" in body)
        assertTrue("missing sid cookie" in body)
    }

    @Test
    fun `expired or unknown authRequestId returns 400`() = testApplication {
        application { module(testDeps()) }

        val r = noFollowClient().get("/login") {
            header(HttpHeaders.Cookie, "sid=does-not-exist")
        }

        assertEquals(HttpStatusCode.BadRequest, r.status)
        val body = r.bodyAsText()
        assertTrue("invalid_request" in body)
        assertTrue("auth request not found or expired" in body)
    }

    @Test
    fun `default prompt with session redirects to consent (SSO)`() = testApplication {
        val store = InMemoryAuthRequestStore(5.minutes).apply {
            put("sid-abc", fixtureAuthRequest(prompt = null))
        }
        val sessions = InMemorySessionStore(5.minutes).apply {
            put("sid-abc", fixtureSession(sub = "user-99"))
        }
        application {
            module(
                testDeps(
                    authRequestStore = store,
                    sessionStore = sessions,
                    realmRegistry = realmRegistry(realm("employees")),
                ),
            )
        }

        val r = noFollowClient().get("/login") {
            header(HttpHeaders.Cookie, "sid=sid-abc")
        }

        assertEquals(HttpStatusCode.Found, r.status)
        assertEquals("/consent", r.headers[HttpHeaders.Location])
        assertEquals("user-99", assertNotNull(store.get("sid-abc")).subject)
    }

    @Test
    fun `default prompt without session renders login page`() = testApplication {
        val store = InMemoryAuthRequestStore(5.minutes).apply {
            put("sid-abc", fixtureAuthRequest())
        }
        application {
            module(
                testDeps(
                    authRequestStore = store,
                    realmRegistry = realmRegistry(realm("employees", "Employees")),
                ),
            )
        }

        val r = noFollowClient().get("/login") {
            header(HttpHeaders.Cookie, "sid=sid-abc")
        }

        assertEquals(HttpStatusCode.OK, r.status)
        assertTrue("Employees" in r.bodyAsText())
    }

    @Test
    fun `login_required error redirects back to RP with state echoed`() = testApplication {
        val store = InMemoryAuthRequestStore(5.minutes).apply {
            put("sid-abc", fixtureAuthRequest(prompt = "none"))
        }
        application {
            module(testDeps(authRequestStore = store, realmRegistry = realmRegistry(realm("r"))))
        }

        val r = noFollowClient().get("/login") {
            header(HttpHeaders.Cookie, "sid=sid-abc")
        }

        assertEquals(HttpStatusCode.Found, r.status)
        val url = Url(assertNotNull(r.headers[HttpHeaders.Location]))
        assertEquals("rp", url.host)
        assertEquals("/cb", url.encodedPath)
        assertEquals("login_required", url.parameters["error"])
        assertEquals("round-trip", url.parameters["state"])
    }

    @Test
    fun `realm names are HTML-escaped`() = testApplication {
        val store = InMemoryAuthRequestStore(5.minutes).apply {
            put("sid-abc", fixtureAuthRequest())
        }
        application {
            module(
                testDeps(
                    authRequestStore = store,
                    realmRegistry = realmRegistry(
                        realm("evil", "<script>alert(1)</script>"),
                    ),
                ),
            )
        }

        val r = noFollowClient().get("/login") {
            header(HttpHeaders.Cookie, "sid=sid-abc")
        }

        assertEquals(HttpStatusCode.OK, r.status)
        val body = r.bodyAsText()
        // Raw tags must not appear; the escaped form must.
        assertFalse(
            "<script>alert(1)</script>" in body,
            "raw script tag must never appear in rendered HTML; body: $body",
        )
        assertTrue(
            "&lt;script&gt;" in body,
            "escaped realm name must appear; body: $body",
        )
    }
}
