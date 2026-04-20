package id.walt.authop.endpoints

import id.walt.authop.config.ClientRegistry
import id.walt.authop.domain.AuthRequest
import id.walt.authop.module
import id.walt.authop.store.InMemoryAuthCodeStore
import id.walt.authop.store.InMemoryAuthRequestStore
import id.walt.authop.store.InMemoryCsrfTokenStore
import id.walt.authop.testClient
import id.walt.authop.testDeps
import io.ktor.client.HttpClient
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.http.parameters
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Tests for `GET /consent` and `POST /consent`.
 *
 * Shape mirrors [LoginRoutesTest]: seed the AuthRequestStore under a known
 * `sid`, hit the endpoint with a matching cookie, assert on observable HTTP
 * output (status, Location, body). Every test uses `followRedirects = false`
 * so the 302 to the RP on success is directly observable.
 *
 * The [primedAuthReq] helper mirrors the pattern described in the Task 10
 * spec — it creates a store pre-populated with an [AuthRequest] whose
 * `subject` is non-null (i.e. post-login, pre-consent state).
 */
class ConsentRoutesTest {

    private fun ApplicationTestBuilder.noFollowClient(): HttpClient = createClient {
        followRedirects = false
    }

    /**
     * Build a store + matching ClientRegistry representing a user who has
     * completed login and is now at the /consent step. [trusted] flips the
     * RP's `trusted` flag so callers can test both the skip and the
     * render-consent branches with the same helper.
     */
    private data class Primed(
        val store: InMemoryAuthRequestStore,
        val clientRegistry: ClientRegistry,
        val sid: String,
    )

    private fun primedAuthReq(
        trusted: Boolean,
        subject: String? = "user1",
        state: String? = "round-trip",
        sid: String = "sid-abc",
        clientId: String = "rp1",
        scope: List<String> = listOf("openid"),
    ): Primed {
        val store = InMemoryAuthRequestStore(5.minutes).apply {
            put(
                sid,
                AuthRequest(
                    authRequestId = sid,
                    clientId = clientId,
                    redirectUri = "https://rp/cb",
                    scope = scope,
                    state = state,
                    nonce = null,
                    codeChallenge = "XYZ",
                    codeChallengeMethod = "S256",
                    prompt = null,
                    chosenRealmId = "employees",
                    subject = subject,
                    claims = emptyMap(),
                ),
            )
        }
        val client = testClient(clientId = clientId, trusted = trusted)
        return Primed(store, ClientRegistry(mapOf(clientId to client)), sid)
    }

    // -- Required tests (names verbatim) --------------------------------------

    @Test
    fun `trusted client skips consent and mints auth code directly`() = testApplication {
        val primed = primedAuthReq(trusted = true)
        val codes = InMemoryAuthCodeStore(60.seconds)
        application {
            module(
                testDeps(
                    authRequestStore = primed.store,
                    clientRegistry = primed.clientRegistry,
                    authCodeStore = codes,
                ),
            )
        }

        val r = noFollowClient().get("/consent") {
            header(HttpHeaders.Cookie, "sid=${primed.sid}")
        }

        // Trusted branch: NO HTML page — straight 302 back to the RP.
        assertEquals(HttpStatusCode.Found, r.status)
        val loc = assertNotNull(r.headers[HttpHeaders.Location])
        val url = Url(loc)
        assertEquals("rp", url.host)
        assertEquals("/cb", url.encodedPath)
        val code = assertNotNull(url.parameters["code"], "code param required; got: $loc")
        assertEquals("round-trip", url.parameters["state"])

        // And the code is actually stored (single-use — consume returns it once).
        val stored = assertNotNull(codes.consume(code), "auth code must be persisted")
        assertEquals("user1", stored.subject)
        assertEquals("rp1", stored.clientId)

        // AuthRequest was consumed — a second /consent call on the same sid
        // would 400 on lookup.
        assertNull(primed.store.get(primed.sid))
    }

    @Test
    fun `non-trusted client renders consent page with CSRF token`() = testApplication {
        val primed = primedAuthReq(trusted = false, scope = listOf("openid", "profile"))
        application {
            module(
                testDeps(
                    authRequestStore = primed.store,
                    clientRegistry = primed.clientRegistry,
                ),
            )
        }

        val r = noFollowClient().get("/consent") {
            header(HttpHeaders.Cookie, "sid=${primed.sid}")
        }

        assertEquals(HttpStatusCode.OK, r.status)
        val body = r.bodyAsText()
        assertTrue("Authorize rp1" in body, "client_id must appear in header; body: $body")
        assertTrue("Verify your identity" in body, "openid scope description required; body: $body")
        assertTrue(
            "Access your profile information" in body,
            "profile scope description required; body: $body",
        )
        // Hidden csrf_token input must be present. We don't pin the regex too
        // tightly — just confirm the field + some non-empty value is there.
        assertTrue(
            Regex("""name=['"]csrf_token['"]""").containsMatchIn(body),
            "csrf_token input required; body: $body",
        )
        // And the AuthRequest must still be present (we haven't minted a code yet).
        assertNotNull(primed.store.get(primed.sid))
    }

    @Test
    fun `POST without matching CSRF token rejected`() = testApplication {
        val primed = primedAuthReq(trusted = false)
        val csrf = InMemoryCsrfTokenStore(10.minutes)
        // Don't call issue(): there's simply no token on record for this sid.
        application {
            module(
                testDeps(
                    authRequestStore = primed.store,
                    clientRegistry = primed.clientRegistry,
                    csrfTokenStore = csrf,
                ),
            )
        }

        val r = noFollowClient().submitForm(
            url = "/consent",
            formParameters = parameters {
                append("csrf_token", "not-the-real-token")
                append("decision", "accept")
            },
        ) {
            header(HttpHeaders.Cookie, "sid=${primed.sid}")
        }

        assertEquals(HttpStatusCode.Forbidden, r.status)
        assertTrue("csrf_token" in r.bodyAsText())
        // And the AuthRequest is untouched — the forged POST MUST NOT
        // terminate the request state.
        assertNotNull(primed.store.get(primed.sid))
    }

    @Test
    fun `user denying consent redirects with access_denied`() = testApplication {
        val primed = primedAuthReq(trusted = false)
        val csrf = InMemoryCsrfTokenStore(10.minutes)
        // Simulate the GET /consent having run — we issue a token for the sid.
        val token = csrf.issue(primed.sid)
        application {
            module(
                testDeps(
                    authRequestStore = primed.store,
                    clientRegistry = primed.clientRegistry,
                    csrfTokenStore = csrf,
                ),
            )
        }

        val r = noFollowClient().submitForm(
            url = "/consent",
            formParameters = parameters {
                append("csrf_token", token)
                append("decision", "deny")
            },
        ) {
            header(HttpHeaders.Cookie, "sid=${primed.sid}")
        }

        assertEquals(HttpStatusCode.Found, r.status)
        val loc = assertNotNull(r.headers[HttpHeaders.Location])
        val url = Url(loc)
        assertEquals("rp", url.host)
        assertEquals("access_denied", url.parameters["error"])
        assertEquals("round-trip", url.parameters["state"])
    }

    // -- Additional coverage --------------------------------------------------

    @Test
    fun `GET consent without sid returns 400`() = testApplication {
        application { module(testDeps()) }
        val r = noFollowClient().get("/consent")

        assertEquals(HttpStatusCode.BadRequest, r.status)
        assertTrue("missing sid cookie" in r.bodyAsText())
    }

    @Test
    fun `GET consent with no AuthRequest returns 400`() = testApplication {
        application { module(testDeps()) }

        val r = noFollowClient().get("/consent") {
            header(HttpHeaders.Cookie, "sid=does-not-exist")
        }

        assertEquals(HttpStatusCode.BadRequest, r.status)
        assertTrue("auth request not found or expired" in r.bodyAsText())
    }

    @Test
    fun `GET consent with AuthRequest that has null subject returns 400`() = testApplication {
        // subject=null => reached /consent without /login completing. That's
        // a state-machine violation; the endpoint refuses rather than
        // silently redirecting, to flush bugs in the routing order.
        val primed = primedAuthReq(trusted = false, subject = null)
        application {
            module(
                testDeps(
                    authRequestStore = primed.store,
                    clientRegistry = primed.clientRegistry,
                ),
            )
        }

        val r = noFollowClient().get("/consent") {
            header(HttpHeaders.Cookie, "sid=${primed.sid}")
        }

        assertEquals(HttpStatusCode.BadRequest, r.status)
        assertTrue("login not completed" in r.bodyAsText())
    }

    @Test
    fun `accept decision mints code, deletes AuthRequest, redirects with code and state`() = testApplication {
        val primed = primedAuthReq(trusted = false)
        val csrf = InMemoryCsrfTokenStore(10.minutes)
        val codes = InMemoryAuthCodeStore(60.seconds)
        val token = csrf.issue(primed.sid)
        application {
            module(
                testDeps(
                    authRequestStore = primed.store,
                    clientRegistry = primed.clientRegistry,
                    csrfTokenStore = csrf,
                    authCodeStore = codes,
                ),
            )
        }

        val r = noFollowClient().submitForm(
            url = "/consent",
            formParameters = parameters {
                append("csrf_token", token)
                append("decision", "accept")
            },
        ) {
            header(HttpHeaders.Cookie, "sid=${primed.sid}")
        }

        assertEquals(HttpStatusCode.Found, r.status)
        val url = Url(assertNotNull(r.headers[HttpHeaders.Location]))
        val code = assertNotNull(url.parameters["code"])
        assertEquals("round-trip", url.parameters["state"])

        val stored = assertNotNull(codes.consume(code))
        assertEquals("user1", stored.subject)
        assertEquals("rp1", stored.clientId)
        assertEquals("https://rp/cb", stored.redirectUri)
        assertEquals("XYZ", stored.codeChallenge)

        // AuthRequest is gone — single-use flow complete.
        assertNull(primed.store.get(primed.sid))
    }

    @Test
    fun `CSRF token is single-use`() = testApplication {
        val primed = primedAuthReq(trusted = false)
        val csrf = InMemoryCsrfTokenStore(10.minutes)
        val token = csrf.issue(primed.sid)
        // Seed a fresh AuthRequest on a second sid so the second POST has
        // something to look up — the AuthRequest itself is also single-use,
        // so re-posting the same sid would first fail on AuthRequest lookup.
        primed.store.put(
            "sid-abc-2",
            AuthRequest(
                authRequestId = "sid-abc-2",
                clientId = "rp1",
                redirectUri = "https://rp/cb",
                scope = listOf("openid"),
                state = "round-trip",
                nonce = null,
                codeChallenge = "XYZ",
                codeChallengeMethod = "S256",
                prompt = null,
                chosenRealmId = "employees",
                subject = "user1",
                claims = emptyMap(),
            ),
        )
        application {
            module(
                testDeps(
                    authRequestStore = primed.store,
                    clientRegistry = primed.clientRegistry,
                    csrfTokenStore = csrf,
                ),
            )
        }

        // First POST succeeds (consumes the token for sid-abc).
        val first = noFollowClient().submitForm(
            url = "/consent",
            formParameters = parameters {
                append("csrf_token", token)
                append("decision", "accept")
            },
        ) {
            header(HttpHeaders.Cookie, "sid=${primed.sid}")
        }
        assertEquals(HttpStatusCode.Found, first.status)

        // Now re-issue a token for the second sid (simulating a separate
        // GET), but try the POST with the OLD (consumed) token instead.
        csrf.issue("sid-abc-2") // store has some other token now
        val replay = noFollowClient().submitForm(
            url = "/consent",
            formParameters = parameters {
                append("csrf_token", token) // reused from first request
                append("decision", "accept")
            },
        ) {
            header(HttpHeaders.Cookie, "sid=sid-abc-2")
        }
        assertEquals(HttpStatusCode.Forbidden, replay.status)
    }

    @Test
    fun `access_denied error echoes state parameter byte-exact`() = testApplication {
        // Exotic state: spaces, +, /, &, and Unicode. formUrlEncode must
        // produce output that round-trips cleanly through Url(...).parameters.
        val exoticState = "foo bar+baz/qux&zap=Ω"
        val primed = primedAuthReq(trusted = false, state = exoticState)
        val csrf = InMemoryCsrfTokenStore(10.minutes)
        val token = csrf.issue(primed.sid)
        application {
            module(
                testDeps(
                    authRequestStore = primed.store,
                    clientRegistry = primed.clientRegistry,
                    csrfTokenStore = csrf,
                ),
            )
        }

        val r = noFollowClient().submitForm(
            url = "/consent",
            formParameters = parameters {
                append("csrf_token", token)
                append("decision", "deny")
            },
        ) {
            header(HttpHeaders.Cookie, "sid=${primed.sid}")
        }

        assertEquals(HttpStatusCode.Found, r.status)
        val url = Url(assertNotNull(r.headers[HttpHeaders.Location]))
        assertEquals("access_denied", url.parameters["error"])
        // Parsed state must equal the original byte-exact — ktor's Url decodes.
        assertEquals(exoticState, url.parameters["state"])
        assertFalse("access_denied" == url.parameters["state"], "sanity check: state != error")
    }
}
