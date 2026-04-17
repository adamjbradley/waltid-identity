package id.walt.authop.errors

import id.walt.authop.domain.AuthRequest
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.server.application.Application
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Dispatcher-only tests. No `/authorize` wiring yet (that's Task 8) — each
 * test mounts a stub route that invokes [respondOidcError] with a specific
 * [OidcError] and asserts on the wire-level response.
 *
 * Redirect following is disabled so we can observe the 302 + Location
 * header instead of chasing through to a (nonexistent) RP endpoint.
 */
class OidcErrorDispatchTest {

    private fun authRequest(
        redirectUri: String = "https://rp.example/cb",
        state: String? = "xyz",
    ) = AuthRequest(
        authRequestId = "ar1",
        clientId = "rp",
        redirectUri = redirectUri,
        scope = listOf("openid"),
        state = state,
        nonce = null,
        codeChallenge = "",
        codeChallengeMethod = "S256",
        prompt = null,
        chosenRealmId = null,
        subject = null,
        claims = emptyMap(),
    )

    // NB: deliberately no ContentNegotiation install here — the JSON_BODY
    // dispatcher branch must render `application/json` on its own. If this
    // ever gets re-added to make a test pass, Fix 2's guarantee is broken.
    private fun Application.testRoutes() {
        routing {
            get("/plain-unknown-client") {
                call.respondOidcError(OidcError.UnknownClient("nope"))
            }
            // Attacker-controlled clientId echoed through the PLAIN_ERROR_PAGE branch.
            // Guards Fix 1: every interpolated value must be HTML-escaped.
            get("/plain-unknown-client-xss") {
                val clientId = call.request.queryParameters["client_id"] ?: ""
                call.respondOidcError(OidcError.UnknownClient(clientId))
            }
            get("/plain-unregistered-redirect") {
                call.respondOidcError(OidcError.UnregisteredRedirectUri("https://evil"))
            }
            get("/invalid-request-no-desc") {
                call.respondOidcError(OidcError.InvalidRequest(), authRequest())
            }
            get("/invalid-request-with-desc") {
                call.respondOidcError(OidcError.InvalidRequest("missing code_challenge"), authRequest())
            }
            get("/invalid-request-null-authreq") {
                call.respondOidcError(OidcError.InvalidRequest(), authReq = null)
            }
            get("/access-denied-with-reason") {
                call.respondOidcError(
                    OidcError.AccessDenied("presentation did not satisfy requirements"),
                    authRequest(),
                )
            }
            get("/unsupported-response-type") {
                call.respondOidcError(OidcError.UnsupportedResponseType, authRequest())
            }
            get("/login-required-no-state") {
                call.respondOidcError(OidcError.LoginRequired, authRequest(state = null))
            }
            // Roundtrip stub — reads `state` verbatim from query so tests can feed
            // arbitrary values (incl. special characters, Unicode) into the
            // REDIRECT_TO_RP path and assert byte-exact round-trip.
            get("/redirect-err") {
                val state = call.request.queryParameters["state"]
                call.respondOidcError(
                    OidcError.InvalidRequest("missing code_challenge"),
                    authRequest(state = state),
                )
            }
            get("/invalid-client") {
                call.respondOidcError(OidcError.InvalidClient)
            }
            get("/invalid-grant") {
                call.respondOidcError(OidcError.InvalidGrant)
            }
            get("/invalid-token") {
                call.respondOidcError(OidcError.InvalidToken)
            }
        }
    }

    // Ktor's default test client follows redirects; disable for this suite.
    private fun io.ktor.server.testing.ApplicationTestBuilder.noFollowClient() = createClient {
        followRedirects = false
    }

    @Test
    fun `plain error page for unknown client is 400 with no Location header`() = testApplication {
        application { testRoutes() }
        val r = noFollowClient().get("/plain-unknown-client")

        assertEquals(HttpStatusCode.BadRequest, r.status)
        assertNull(r.headers[HttpHeaders.Location])
        val body = r.bodyAsText()
        assertTrue("unauthorized_client" in body, "error code must appear in HTML body")
        // Apostrophes are HTML-escaped (see Fix 1 / htmlEscape), so the raw description
        // appears as `client &#39;nope&#39; is not registered` in the response body.
        assertTrue(
            "client &#39;nope&#39; is not registered" in body,
            "description must appear (HTML-escaped) in HTML body; got: $body",
        )
    }

    @Test
    fun `plain error page for unregistered redirect_uri never redirects`() = testApplication {
        application { testRoutes() }
        val r = noFollowClient().get("/plain-unregistered-redirect")

        assertEquals(HttpStatusCode.BadRequest, r.status)
        assertNull(r.headers[HttpHeaders.Location])
    }

    @Test
    fun `invalid_request redirects to RP with error and state echoed byte-exact`() = testApplication {
        application { testRoutes() }
        val r = noFollowClient().get("/invalid-request-no-desc")

        assertEquals(HttpStatusCode.Found, r.status)
        val location = assertNotNull(r.headers[HttpHeaders.Location], "Location header required on 302")

        val url = Url(location)
        assertEquals("rp.example", url.host)
        assertEquals("/cb", url.segments.joinToString("/", prefix = "/"))
        assertEquals("invalid_request", url.parameters["error"])
        assertEquals("xyz", url.parameters["state"])
        // No description was supplied — none should appear in the query.
        assertNull(url.parameters["error_description"])
    }

    @Test
    fun `invalid_request with description echoes error_description param`() = testApplication {
        application { testRoutes() }
        val r = noFollowClient().get("/invalid-request-with-desc")

        assertEquals(HttpStatusCode.Found, r.status)
        val url = Url(assertNotNull(r.headers[HttpHeaders.Location]))
        assertEquals("invalid_request", url.parameters["error"])
        assertEquals("missing code_challenge", url.parameters["error_description"])
        assertEquals("xyz", url.parameters["state"])
    }

    @Test
    fun `REDIRECT_TO_RP without AuthRequest throws IllegalArgumentException`() = testApplication {
        application { testRoutes() }
        // Ktor's StatusPages isn't installed on this stub — the thrown exception
        // surfaces as a 500. We assert the status is not a successful redirect,
        // i.e. the dispatcher refused to silently produce a wrong response.
        val r = try {
            noFollowClient().get("/invalid-request-null-authreq")
        } catch (e: Throwable) {
            // Some ktor test configurations propagate the exception instead of
            // mapping it to 500. Either outcome confirms the assertion.
            assertTrue(
                e is IllegalArgumentException || e.cause is IllegalArgumentException,
                "Expected IllegalArgumentException, got ${e::class.simpleName}: ${e.message}",
            )
            return@testApplication
        }
        assertEquals(HttpStatusCode.InternalServerError, r.status)
        assertNull(r.headers[HttpHeaders.Location], "Must not redirect when AuthRequest is missing")
    }

    @Test
    fun `access_denied redirects with description preserved`() = testApplication {
        application { testRoutes() }
        val r = noFollowClient().get("/access-denied-with-reason")

        assertEquals(HttpStatusCode.Found, r.status)
        val url = Url(assertNotNull(r.headers[HttpHeaders.Location]))
        assertEquals("access_denied", url.parameters["error"])
        assertEquals("presentation did not satisfy requirements", url.parameters["error_description"])
        assertEquals("xyz", url.parameters["state"])
    }

    @Test
    fun `unsupported_response_type redirects with error code and no description`() = testApplication {
        application { testRoutes() }
        val r = noFollowClient().get("/unsupported-response-type")

        assertEquals(HttpStatusCode.Found, r.status)
        val url = Url(assertNotNull(r.headers[HttpHeaders.Location]))
        assertEquals("unsupported_response_type", url.parameters["error"])
        assertNull(url.parameters["error_description"])
        assertEquals("xyz", url.parameters["state"])
    }

    @Test
    fun `login_required redirects and omits state when AuthRequest has none`() = testApplication {
        application { testRoutes() }
        val r = noFollowClient().get("/login-required-no-state")

        assertEquals(HttpStatusCode.Found, r.status)
        val url = Url(assertNotNull(r.headers[HttpHeaders.Location]))
        assertEquals("login_required", url.parameters["error"])
        assertNull(url.parameters["state"], "state must not appear when authReq.state is null")
    }

    @Test
    fun `invalid_client JSON body includes WWW-Authenticate Basic header and 401 status`() = testApplication {
        application { testRoutes() }
        val r = noFollowClient().get("/invalid-client")

        assertEquals(HttpStatusCode.Unauthorized, r.status)
        assertEquals("Basic", r.headers[HttpHeaders.WWWAuthenticate])
        val body = r.bodyAsText()
        assertTrue(body.contains("\"error\""), "body must be JSON with 'error' field: $body")
        assertTrue(body.contains("invalid_client"), "body must contain error code: $body")
    }

    @Test
    fun `invalid_grant JSON body is 400 with no WWW-Authenticate header`() = testApplication {
        application { testRoutes() }
        val r = noFollowClient().get("/invalid-grant")

        assertEquals(HttpStatusCode.BadRequest, r.status)
        assertNull(r.headers[HttpHeaders.WWWAuthenticate])
        val body = r.bodyAsText()
        assertTrue(body.contains("invalid_grant"), "body must contain error code: $body")
    }

    @Test
    fun `invalid_token JSON body includes Bearer WWW-Authenticate header and 401 status`() = testApplication {
        application { testRoutes() }
        val r = noFollowClient().get("/invalid-token")

        assertEquals(HttpStatusCode.Unauthorized, r.status)
        val wwwAuth = assertNotNull(r.headers[HttpHeaders.WWWAuthenticate])
        assertTrue(wwwAuth.startsWith("Bearer"), "Must be Bearer challenge: $wwwAuth")
        assertTrue(wwwAuth.contains("""error="invalid_token""""), "Must include error token: $wwwAuth")
        val body = r.bodyAsText()
        assertTrue(body.contains("invalid_token"), "body must contain error code: $body")
    }

    // Fix 1 — Regression guard for reflected XSS in PLAIN_ERROR_PAGE.
    // clientId is attacker-controlled (echoed from ?client_id=…); any script
    // tags or angle brackets must be rendered as escaped entities, never
    // interpreted as HTML by the browser.
    @Test
    fun `unknown_client HTML-escapes attacker-controlled clientId to prevent XSS`() = testApplication {
        application { testRoutes() }
        // URL-encoded payload: <script>alert(1)</script>
        val r = noFollowClient().get(
            "/plain-unknown-client-xss?client_id=%3Cscript%3Ealert%281%29%3C%2Fscript%3E",
        )

        assertEquals(HttpStatusCode.BadRequest, r.status)
        val body = r.bodyAsText()
        // Positive: escaped form must appear.
        assertTrue("&lt;script&gt;" in body, "description must be HTML-escaped; body was: $body")
        assertTrue("&lt;/script&gt;" in body, "closing tag must be HTML-escaped; body was: $body")
        // Negative: raw tag must NOT appear anywhere in the response body.
        assertFalse(
            "<script>" in body,
            "raw <script> tag must not be present in HTML body; would allow XSS. body was: $body",
        )
        assertFalse(
            "</script>" in body,
            "raw </script> tag must not be present in HTML body. body was: $body",
        )
    }

    // Fix 3 — State must round-trip byte-exact through the REDIRECT_TO_RP branch,
    // including characters that are both URL-reserved (&, =, space) and
    // non-ASCII (Unicode). The dispatcher uses parametersOf + formUrlEncode
    // precisely to guarantee this; Url.parameters parses symmetrically.
    @Test
    fun `state parameter roundtrips byte-exact with special characters`() = testApplication {
        application { testRoutes() }
        val trickyState = "hello world & evil=1 & unicode=café"
        // Percent-encode each component manually so ampersands don't split into
        // separate query params. The key property under test is the dispatcher
        // encoding, not the client's — so we send it cleanly and inspect the
        // Location header the dispatcher produced.
        val encoded = trickyState
            .replace("%", "%25")
            .replace("&", "%26")
            .replace("=", "%3D")
            .replace(" ", "%20")
        val r = noFollowClient().get("/redirect-err?state=$encoded")

        assertEquals(HttpStatusCode.Found, r.status)
        val location = assertNotNull(r.headers[HttpHeaders.Location], "Location header required on 302")
        val url = Url(location)
        assertEquals(
            trickyState,
            url.parameters["state"],
            "state must survive round-trip through formUrlEncode with special characters intact",
        )
    }
}
