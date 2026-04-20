package id.walt.authop.endpoints

import id.walt.authop.config.ClientRegistry
import id.walt.authop.module
import id.walt.authop.store.InMemoryAuthRequestStore
import id.walt.authop.testClient
import id.walt.authop.testConfig
import id.walt.authop.testDeps
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.http.setCookie
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/**
 * End-to-end tests for `GET /authorize`.
 *
 * The Ktor test client follows redirects by default, which would hide
 * the 302 response we are asserting on. Each test uses [noFollowClient]
 * so `Location` headers are observable. The `sid` cookie is read via
 * `HttpResponse.setCookie()` (from `io.ktor.http`), which parses
 * `Set-Cookie` into a typed list.
 */
class AuthorizeRoutesTest {

    /** Happy-path authorize URL template. Tests vary one parameter at a time. */
    private fun authUrl(
        clientId: String = "rp1",
        redirectUri: String = "https://rp/cb",
        responseType: String = "code",
        scope: String = "openid",
        state: String? = "s",
        codeChallenge: String? = "XYZ",
        codeChallengeMethod: String? = "S256",
        responseMode: String? = null,
    ): String = buildString {
        append("/authorize?")
        append("client_id=").append(clientId)
        append("&redirect_uri=").append(java.net.URLEncoder.encode(redirectUri, Charsets.UTF_8))
        append("&response_type=").append(responseType)
        append("&scope=").append(java.net.URLEncoder.encode(scope, Charsets.UTF_8))
        if (state != null) append("&state=").append(state)
        if (codeChallenge != null) append("&code_challenge=").append(codeChallenge)
        if (codeChallengeMethod != null) append("&code_challenge_method=").append(codeChallengeMethod)
        if (responseMode != null) append("&response_mode=").append(responseMode)
    }

    private fun ApplicationTestBuilder.noFollowClient(): HttpClient = createClient {
        followRedirects = false
    }

    // -- Required-byte-match tests ---------------------------------------------

    @Test
    fun `invalid client_id returns 400 without redirect`() = testApplication {
        application { module(testDeps()) }
        val r = noFollowClient().get(authUrl(clientId = "unknown"))

        assertEquals(HttpStatusCode.BadRequest, r.status)
        assertNull(r.headers[HttpHeaders.Location], "must not redirect for unknown client")
        val body = r.bodyAsText()
        assertTrue(
            "unauthorized_client" in body,
            "plain error page must include the error code; got: $body",
        )
    }

    @Test
    fun `valid request creates auth request and redirects to login with cookie`() = testApplication {
        val store = InMemoryAuthRequestStore(5.minutes)
        application { module(testDeps(authRequestStore = store)) }

        val r = noFollowClient().get(
            "/authorize?client_id=rp1&redirect_uri=https://rp/cb&response_type=code" +
                "&scope=openid&state=s&code_challenge=XYZ&code_challenge_method=S256",
        )

        assertEquals(HttpStatusCode.Found, r.status)
        assertEquals("/login", r.headers[HttpHeaders.Location])

        val sidCookie = assertNotNull(
            r.setCookie().firstOrNull { it.name == "sid" },
            "sid cookie must be set on successful /authorize",
        )
        // The cookie value equals the authRequestId — look it up in the store.
        val stored = assertNotNull(
            store.get(sidCookie.value),
            "AuthRequest must be persisted under the sid cookie value",
        )
        assertEquals("rp1", stored.clientId)
        assertEquals("https://rp/cb", stored.redirectUri)
        assertEquals(listOf("openid"), stored.scope)
        assertEquals("s", stored.state)
        assertEquals("XYZ", stored.codeChallenge)
        assertEquals("S256", stored.codeChallengeMethod)
    }

    @Test
    fun `unsupported response_type redirects with error`() = testApplication {
        application { module(testDeps()) }
        val r = noFollowClient().get(authUrl(responseType = "token"))

        assertEquals(HttpStatusCode.Found, r.status)
        val url = Url(assertNotNull(r.headers[HttpHeaders.Location]))
        assertEquals("rp", url.host)
        assertEquals("unsupported_response_type", url.parameters["error"])
        assertEquals("s", url.parameters["state"])
    }

    @Test
    fun `response_mode fragment rejected`() = testApplication {
        application { module(testDeps()) }
        val r = noFollowClient().get(authUrl(responseMode = "fragment"))

        assertEquals(HttpStatusCode.Found, r.status)
        val url = Url(assertNotNull(r.headers[HttpHeaders.Location]))
        assertEquals("unsupported_response_type", url.parameters["error"])
        assertEquals("s", url.parameters["state"])
    }

    @Test
    fun `missing code_challenge rejected`() = testApplication {
        application { module(testDeps()) }
        val r = noFollowClient().get(authUrl(codeChallenge = null))

        assertEquals(HttpStatusCode.Found, r.status)
        val url = Url(assertNotNull(r.headers[HttpHeaders.Location]))
        assertEquals("invalid_request", url.parameters["error"])
        assertEquals("missing code_challenge", url.parameters["error_description"])
        assertEquals("s", url.parameters["state"])
    }

    // -- Additional coverage --------------------------------------------------

    @Test
    fun `unregistered redirect_uri returns 400 without redirect`() = testApplication {
        application { module(testDeps()) }
        val r = noFollowClient().get(authUrl(redirectUri = "https://evil/cb"))

        assertEquals(HttpStatusCode.BadRequest, r.status)
        assertNull(r.headers[HttpHeaders.Location], "unregistered redirect must never be used as Location")
        val body = r.bodyAsText()
        assertTrue("invalid_request" in body, "page must include error code; got: $body")
        assertTrue("redirect_uri not registered" in body, "page must name the problem; got: $body")
    }

    @Test
    fun `missing openid scope returns invalid_scope error`() = testApplication {
        application { module(testDeps()) }
        val r = noFollowClient().get(authUrl(scope = "profile"))

        assertEquals(HttpStatusCode.Found, r.status)
        val url = Url(assertNotNull(r.headers[HttpHeaders.Location]))
        assertEquals("invalid_scope", url.parameters["error"])
        assertEquals("s", url.parameters["state"])
    }

    @Test
    fun `scope not allowed for client returns invalid_scope error`() = testApplication {
        // Client only permits `openid`; request asks for `openid profile`.
        val restrictedClient = testClient(allowedScopes = listOf("openid"))
        val deps = testDeps(
            clientRegistry = ClientRegistry(mapOf(restrictedClient.clientId to restrictedClient)),
        )
        application { module(deps) }

        val r = noFollowClient().get(authUrl(scope = "openid profile"))

        assertEquals(HttpStatusCode.Found, r.status)
        val url = Url(assertNotNull(r.headers[HttpHeaders.Location]))
        assertEquals("invalid_scope", url.parameters["error"])
        val desc = assertNotNull(url.parameters["error_description"])
        assertTrue("profile" in desc, "error_description must name the disallowed scope; got: $desc")
    }

    @Test
    fun `missing required params returns 400 without redirect`() = testApplication {
        application { module(testDeps()) }
        // Missing both redirect_uri and response_type.
        val r = noFollowClient().get("/authorize?client_id=rp1")

        assertEquals(HttpStatusCode.BadRequest, r.status)
        assertNull(r.headers[HttpHeaders.Location])
        val body = r.bodyAsText()
        assertTrue("invalid_request" in body, "page must include error code; got: $body")
    }

    @Test
    fun `state parameter stored on AuthRequest and carried through redirect`() = testApplication {
        val store = InMemoryAuthRequestStore(5.minutes)
        application { module(testDeps(authRequestStore = store)) }

        val r = noFollowClient().get(authUrl(state = "round-trip-value"))
        assertEquals(HttpStatusCode.Found, r.status)
        assertEquals("/login", r.headers[HttpHeaders.Location])

        val sid = assertNotNull(r.setCookie().firstOrNull { it.name == "sid" }).value
        val stored = assertNotNull(store.get(sid))
        assertEquals("round-trip-value", stored.state)
    }

    @Test
    fun `sid cookie value equals authRequestId`() = testApplication {
        val store = InMemoryAuthRequestStore(5.minutes)
        application { module(testDeps(authRequestStore = store)) }

        val r = noFollowClient().get(authUrl())
        assertEquals(HttpStatusCode.Found, r.status)

        val sid = assertNotNull(r.setCookie().firstOrNull { it.name == "sid" }).value
        // The stored AuthRequest's authRequestId field must equal the cookie value.
        val stored = assertNotNull(store.get(sid), "store lookup by sid must find the AuthRequest")
        assertEquals(sid, stored.authRequestId, "cookie value and authRequestId must be the same string")
    }

    @Test
    fun `sid cookie has HttpOnly and SameSite=Lax and matching Path`() = testApplication {
        application { module(testDeps()) }
        val r = noFollowClient().get(authUrl())
        assertEquals(HttpStatusCode.Found, r.status)

        val rawSetCookie = assertNotNull(
            r.headers[HttpHeaders.SetCookie],
            "Set-Cookie header must be present",
        )
        assertTrue("HttpOnly" in rawSetCookie, "cookie must set HttpOnly flag; got: $rawSetCookie")
        assertTrue(
            "SameSite=Lax" in rawSetCookie,
            "cookie must set SameSite=Lax; got: $rawSetCookie",
        )
        assertTrue("Path=/" in rawSetCookie, "cookie must set Path=/ ; got: $rawSetCookie")
        // Secure is driven by config.cookieSecure (default false for tests).
        assertFalse(
            "Secure" in rawSetCookie.split(";").map { it.trim() },
            "Secure must NOT be set when cookieSecure=false; got: $rawSetCookie",
        )
    }

    @Test
    fun `sid cookie Secure flag honours config cookieSecure=true`() = testApplication {
        application { module(testDeps(config = testConfig(cookieSecure = true))) }
        val r = noFollowClient().get(authUrl())
        val rawSetCookie = assertNotNull(r.headers[HttpHeaders.SetCookie])
        assertTrue(
            rawSetCookie.split(";").map { it.trim() }.any { it == "Secure" },
            "Secure flag must appear when cookieSecure=true; got: $rawSetCookie",
        )
    }

    @Test
    fun `code_challenge_method other than S256 is rejected with invalid_request`() = testApplication {
        application { module(testDeps()) }
        val r = noFollowClient().get(authUrl(codeChallengeMethod = "plain"))

        assertEquals(HttpStatusCode.Found, r.status)
        val url = Url(assertNotNull(r.headers[HttpHeaders.Location]))
        assertEquals("invalid_request", url.parameters["error"])
        assertEquals("code_challenge_method must be S256", url.parameters["error_description"])
    }

    @Test
    fun `same client with different redirect_uri in valid list both work`() = testApplication {
        val multiClient = testClient(
            redirectUris = listOf("https://rp/cb", "https://rp/cb2"),
        )
        val deps = testDeps(
            clientRegistry = ClientRegistry(mapOf(multiClient.clientId to multiClient)),
        )
        application { module(deps) }

        val r1 = noFollowClient().get(authUrl(redirectUri = "https://rp/cb"))
        assertEquals(HttpStatusCode.Found, r1.status)
        assertEquals("/login", r1.headers[HttpHeaders.Location])

        val r2 = noFollowClient().get(authUrl(redirectUri = "https://rp/cb2"))
        assertEquals(HttpStatusCode.Found, r2.status)
        assertEquals("/login", r2.headers[HttpHeaders.Location])
    }

    /**
     * Defensive: ensure that the `sid` set on the successful path is not
     * also set on error paths (plain 400s should not mint cookies — there's
     * no pending login to bind). Guards against a refactor that moves the
     * cookie write before validation.
     */
    @Test
    fun `error paths do not set sid cookie`() = testApplication {
        application { module(testDeps()) }
        val r = noFollowClient().get(authUrl(clientId = "unknown"))
        assertEquals(HttpStatusCode.BadRequest, r.status)
        val cookie = r.setCookie().firstOrNull { it.name == "sid" }
        assertNull(cookie, "no sid cookie must be set when validation fails before the auth request is persisted")
    }
}
