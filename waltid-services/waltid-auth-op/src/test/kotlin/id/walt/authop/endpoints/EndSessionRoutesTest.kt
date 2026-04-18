@file:OptIn(ExperimentalTime::class)

package id.walt.authop.endpoints

import id.walt.authop.config.ClientConfig
import id.walt.authop.config.ClientRegistry
import id.walt.authop.config.OidcRealmConfig
import id.walt.authop.config.RealmConfig
import id.walt.authop.config.RealmMethod
import id.walt.authop.config.RealmRegistry
import id.walt.authop.config.TokenEndpointAuthMethod
import id.walt.authop.domain.Session
import id.walt.authop.module
import id.walt.authop.store.InMemoryLogoutFlowStore
import id.walt.authop.store.InMemorySessionStore
import id.walt.authop.store.LogoutFlow
import id.walt.authop.testConfig
import id.walt.authop.testDeps
import id.walt.authop.upstream.OidcClient
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
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.Duration.Companion.minutes

/**
 * Tests for the RP-initiated logout endpoints:
 *  - `GET /end_session`              — entry point; VP path local-only, OIDC
 *    path chains through the upstream's `end_session_endpoint`.
 *  - `GET /end_session/upstream_return` — return leg consuming [LogoutFlow].
 *
 * Fixtures colocated in this file keep the module-level [id.walt.authop.TestFixtures]
 * minimal (this is the only logout consumer) while letting the tests construct
 * clients with specific `postLogoutRedirectUris` patterns (exact vs wildcard).
 */
class EndSessionRoutesTest {

    // ---- Fixtures ----------------------------------------------------------

    private val ourIssuer = "https://auth.example"
    private val upstreamIssuer = "https://upstream.example"

    private fun logoutClient(
        clientId: String = "rp1",
        postLogoutRedirectUris: List<String> = listOf("https://rp.example/after-logout"),
    ): ClientConfig = ClientConfig(
        clientId = clientId,
        clientSecret = "secret",
        tokenEndpointAuthMethod = TokenEndpointAuthMethod.CLIENT_SECRET_BASIC,
        redirectUris = listOf("https://rp.example/cb"),
        postLogoutRedirectUris = postLogoutRedirectUris,
        allowedScopes = listOf("openid"),
        allowedRealms = emptyList(),
        trusted = false,
    )

    private fun oidcRealm(
        id: String = "employees",
        endSessionEndpoint: String? = "$upstreamIssuer/logout",
    ): RealmConfig = RealmConfig(
        id = id,
        name = "Employees",
        method = RealmMethod.OIDC,
        oidc = OidcRealmConfig(
            issuer = upstreamIssuer,
            clientId = "upstream-client",
            clientSecret = "upstream-secret",
            scopes = listOf("openid"),
        ),
        oid4vp = null,
        subStrategy = null,
        claimMapping = emptyMap(),
    )

    private fun vpRealm(id: String = "citizens"): RealmConfig = RealmConfig(
        id = id,
        name = "Citizens",
        method = RealmMethod.OID4VP,
        oidc = null,
        oid4vp = null,  // concrete OID4VP config irrelevant for logout tests
        subStrategy = null,
        claimMapping = emptyMap(),
    )

    /**
     * Upstream OIDC client that serves a discovery document — parameterised by
     * [endSessionEndpoint] so we can simulate both "upstream has logout URL"
     * and "upstream omits it" shapes.
     */
    private fun upstreamOidcClient(
        endSessionEndpoint: String? = "$upstreamIssuer/logout",
    ): OidcClient {
        val discoveryJson = buildJsonObject {
            put("issuer", upstreamIssuer)
            put("authorization_endpoint", "$upstreamIssuer/auth")
            put("token_endpoint", "$upstreamIssuer/token")
            put("userinfo_endpoint", "$upstreamIssuer/userinfo")
            put("jwks_uri", "$upstreamIssuer/jwks.json")
            if (endSessionEndpoint != null) put("end_session_endpoint", endSessionEndpoint)
        }.toString()

        val engine = MockEngine { request ->
            when (request.url.encodedPath) {
                "/.well-known/openid-configuration" -> respond(
                    discoveryJson,
                    HttpStatusCode.OK,
                    headersOf(HttpHeaders.ContentType, "application/json"),
                )
                else -> respond("", HttpStatusCode.NotFound)
            }
        }
        return OidcClient(httpClient = HttpClient(engine))
    }

    /** Build a minimal unsigned-shape JWT whose `aud` decodes to [aud]. */
    private fun tokenHintWithAud(aud: String): String {
        val header = buildJsonObject { put("alg", "none"); put("typ", "JWT") }
        val payload = buildJsonObject {
            put("iss", upstreamIssuer)
            put("aud", aud)
            put("sub", "user-1")
        }
        val enc = Base64.getUrlEncoder().withoutPadding()
        return listOf(header.toString(), payload.toString(), "")
            .joinToString(".") { enc.encodeToString(it.toByteArray(Charsets.UTF_8)) }
    }

    private fun sessionRow(
        sid: String,
        subject: String = "user-1",
        realmId: String = "employees",
        upstreamIdToken: String? = null,
    ): Session = Session(
        sessionId = sid,
        subject = subject,
        realmId = realmId,
        amr = listOf("pwd"),
        acr = "urn:walt:upstream-oidc",
        authTime = Clock.System.now(),
        upstreamIdToken = upstreamIdToken,
    )

    private fun ApplicationTestBuilder.noFollow(): HttpClient = createClient {
        followRedirects = false
    }

    // ========================================================================
    // Plan-required tests (5)
    // ========================================================================

    @Test
    fun `VP session logout clears cookie and redirects to RP`() = testApplication {
        val sessions = InMemorySessionStore(5.minutes).apply {
            // VP session → no upstream id_token.
            put("sid-vp", sessionRow(sid = "sid-vp", realmId = "citizens"))
        }
        application {
            module(
                testDeps(
                    config = testConfig(issuer = ourIssuer),
                    clientRegistry = ClientRegistry(mapOf("rp1" to logoutClient())),
                    realmRegistry = RealmRegistry(mapOf("citizens" to vpRealm())),
                    sessionStore = sessions,
                ),
            )
        }

        val r = noFollow().get(
            "/end_session?client_id=rp1&post_logout_redirect_uri=https%3A%2F%2Frp.example%2Fafter-logout",
        ) {
            header(HttpHeaders.Cookie, "sid=sid-vp")
        }

        assertEquals(HttpStatusCode.Found, r.status)
        assertEquals("https://rp.example/after-logout", r.headers[HttpHeaders.Location])
        assertNull(sessions.get("sid-vp"), "VP session must be removed on logout")

        val setCookie = assertNotNull(r.headers[HttpHeaders.SetCookie])
        assertTrue(
            "sid=" in setCookie && ("Max-Age=0" in setCookie || "Expires" in setCookie),
            "sid cookie must be cleared (Max-Age=0 or Expires in past); got: $setCookie",
        )
    }

    @Test
    fun `OIDC session logout chains upstream then returns to RP`() = testApplication {
        val flows = InMemoryLogoutFlowStore(5.minutes)
        val sessions = InMemorySessionStore(5.minutes).apply {
            put(
                "sid-oidc",
                sessionRow(sid = "sid-oidc", upstreamIdToken = "upstream-id-token"),
            )
        }
        application {
            module(
                testDeps(
                    config = testConfig(issuer = ourIssuer),
                    clientRegistry = ClientRegistry(mapOf("rp1" to logoutClient())),
                    realmRegistry = RealmRegistry(mapOf("employees" to oidcRealm())),
                    sessionStore = sessions,
                    logoutFlowStore = flows,
                    oidcClient = upstreamOidcClient(),
                ),
            )
        }

        // --- leg 1: /end_session → upstream --------------------------------
        val r1 = noFollow().get(
            "/end_session?client_id=rp1&post_logout_redirect_uri=https%3A%2F%2Frp.example%2Fafter-logout&state=rp-csrf",
        ) {
            header(HttpHeaders.Cookie, "sid=sid-oidc")
        }
        assertEquals(HttpStatusCode.Found, r1.status)
        val upstreamUrl = Url(assertNotNull(r1.headers[HttpHeaders.Location]))
        assertEquals("upstream.example", upstreamUrl.host)
        assertEquals("/logout", upstreamUrl.encodedPath)
        assertEquals("upstream-id-token", upstreamUrl.parameters["id_token_hint"])
        assertEquals(
            "$ourIssuer/end_session/upstream_return",
            upstreamUrl.parameters["post_logout_redirect_uri"],
        )
        val upstreamState = assertNotNull(upstreamUrl.parameters["state"])
        assertTrue(upstreamState.isNotBlank())
        // Session NOT yet cleared — we hold it until the upstream return fires.
        assertNotNull(sessions.get("sid-oidc"))

        // --- leg 2: /end_session/upstream_return → RP ----------------------
        val r2 = noFollow().get("/end_session/upstream_return?state=$upstreamState") {
            header(HttpHeaders.Cookie, "sid=sid-oidc")
        }
        assertEquals(HttpStatusCode.Found, r2.status)
        val finalLoc = Url(assertNotNull(r2.headers[HttpHeaders.Location]))
        assertEquals("rp.example", finalLoc.host)
        assertEquals("/after-logout", finalLoc.encodedPath)
        assertEquals("rp-csrf", finalLoc.parameters["state"])

        // Now the session is cleared.
        assertNull(sessions.get("sid-oidc"))
        val setCookie = assertNotNull(r2.headers[HttpHeaders.SetCookie])
        assertTrue("sid=" in setCookie && "Max-Age=0" in setCookie)
    }

    @Test
    fun `end_session rejects unregistered post_logout_redirect_uri`() = testApplication {
        val sessions = InMemorySessionStore(5.minutes).apply {
            put("sid-abc", sessionRow(sid = "sid-abc"))
        }
        application {
            module(
                testDeps(
                    clientRegistry = ClientRegistry(mapOf("rp1" to logoutClient())),
                    realmRegistry = RealmRegistry(mapOf("employees" to oidcRealm())),
                    sessionStore = sessions,
                ),
            )
        }

        val r = noFollow().get(
            "/end_session?client_id=rp1&post_logout_redirect_uri=https%3A%2F%2Fattacker.example%2F",
        ) {
            header(HttpHeaders.Cookie, "sid=sid-abc")
        }

        assertEquals(HttpStatusCode.BadRequest, r.status)
        // Critical: no redirect to the attacker URL.
        assertNull(r.headers[HttpHeaders.Location])
        // Session stays intact — a rejected logout MUST NOT clear the session.
        assertNotNull(sessions.get("sid-abc"))
    }

    @Test
    fun `end_session without session is still a 302 to RP (spec_ no-op)`() = testApplication {
        application {
            module(
                testDeps(
                    clientRegistry = ClientRegistry(mapOf("rp1" to logoutClient())),
                    realmRegistry = RealmRegistry(emptyMap()),
                ),
            )
        }

        // No sid cookie at all.
        val r = noFollow().get(
            "/end_session?client_id=rp1&post_logout_redirect_uri=https%3A%2F%2Frp.example%2Fafter-logout",
        )

        assertEquals(HttpStatusCode.Found, r.status)
        assertEquals("https://rp.example/after-logout", r.headers[HttpHeaders.Location])
    }

    @Test
    fun `upstream_return validates state nonce to prevent CSRF`() = testApplication {
        val flows = InMemoryLogoutFlowStore(5.minutes).apply {
            put(
                "real-state",
                LogoutFlow(
                    sid = "sid-oidc",
                    postLogoutRedirectUri = "https://rp.example/after-logout",
                    rpState = null,
                ),
            )
        }
        val sessions = InMemorySessionStore(5.minutes).apply {
            put("sid-oidc", sessionRow(sid = "sid-oidc", upstreamIdToken = "upstream-id-token"))
        }
        application {
            module(
                testDeps(
                    clientRegistry = ClientRegistry(mapOf("rp1" to logoutClient())),
                    sessionStore = sessions,
                    logoutFlowStore = flows,
                ),
            )
        }

        // Attacker-controlled state → reject, session intact.
        val rAttacker = noFollow().get("/end_session/upstream_return?state=attacker-state")
        assertEquals(HttpStatusCode.BadRequest, rAttacker.status)
        assertNotNull(sessions.get("sid-oidc"), "session must NOT be cleared on CSRF attempt")

        // The real state still works exactly once.
        val rOk = noFollow().get("/end_session/upstream_return?state=real-state")
        assertEquals(HttpStatusCode.Found, rOk.status)

        // And a replay of the real state now fails — single-use.
        val rReplay = noFollow().get("/end_session/upstream_return?state=real-state")
        assertEquals(HttpStatusCode.BadRequest, rReplay.status)
    }

    // ========================================================================
    // Additional tests
    // ========================================================================

    @Test
    fun `end_session without post_logout_redirect_uri returns 400`() = testApplication {
        application {
            module(testDeps(clientRegistry = ClientRegistry(mapOf("rp1" to logoutClient()))))
        }
        val r = noFollow().get("/end_session?client_id=rp1")
        assertEquals(HttpStatusCode.BadRequest, r.status)
        assertNull(r.headers[HttpHeaders.Location])
    }

    @Test
    fun `end_session with missing client_id (and no id_token_hint) returns 400`() = testApplication {
        application {
            module(testDeps(clientRegistry = ClientRegistry(mapOf("rp1" to logoutClient()))))
        }
        val r = noFollow().get(
            "/end_session?post_logout_redirect_uri=https%3A%2F%2Frp.example%2Fafter-logout",
        )
        assertEquals(HttpStatusCode.BadRequest, r.status)
    }

    @Test
    fun `end_session uses id_token_hint to derive client_id`() = testApplication {
        val hint = tokenHintWithAud("rp1")
        val sessions = InMemorySessionStore(5.minutes)
        application {
            module(
                testDeps(
                    clientRegistry = ClientRegistry(mapOf("rp1" to logoutClient())),
                    sessionStore = sessions,
                ),
            )
        }
        val r = noFollow().get(
            "/end_session?id_token_hint=$hint" +
                "&post_logout_redirect_uri=https%3A%2F%2Frp.example%2Fafter-logout",
        )
        // No session cookie → spec no-op → successful 302 to the RP.
        // Passing this path proves the hint-to-client_id derivation worked,
        // because the post_logout URI is registered only on rp1 (the resolved
        // client).
        assertEquals(HttpStatusCode.Found, r.status)
        assertEquals("https://rp.example/after-logout", r.headers[HttpHeaders.Location])
    }

    @Test
    fun `upstream_return without state returns 400`() = testApplication {
        application {
            module(testDeps(clientRegistry = ClientRegistry(mapOf("rp1" to logoutClient()))))
        }
        val r = noFollow().get("/end_session/upstream_return")
        assertEquals(HttpStatusCode.BadRequest, r.status)
    }

    @Test
    fun `upstream_return with unknown state returns 400`() = testApplication {
        application {
            module(testDeps(clientRegistry = ClientRegistry(mapOf("rp1" to logoutClient()))))
        }
        val r = noFollow().get("/end_session/upstream_return?state=does-not-exist")
        assertEquals(HttpStatusCode.BadRequest, r.status)
    }

    @Test
    fun `post_logout_redirect_uri wildcard match accepts matching prefix`() = testApplication {
        val client = logoutClient(
            postLogoutRedirectUris = listOf("https://rp.example/*"),
        )
        val sessions = InMemorySessionStore(5.minutes)
        application {
            module(
                testDeps(
                    clientRegistry = ClientRegistry(mapOf("rp1" to client)),
                    sessionStore = sessions,
                ),
            )
        }
        val target = "https://rp.example/any/deep/path?x=1"
        val r = noFollow().get(
            "/end_session?client_id=rp1&post_logout_redirect_uri=" +
                java.net.URLEncoder.encode(target, Charsets.UTF_8),
        )
        assertEquals(HttpStatusCode.Found, r.status)
        assertEquals(target, r.headers[HttpHeaders.Location])
    }

    @Test
    fun `post_logout_redirect_uri strict match accepts exact URL`() = testApplication {
        val client = logoutClient(
            postLogoutRedirectUris = listOf("https://rp.example/exact"),
        )
        application {
            module(testDeps(clientRegistry = ClientRegistry(mapOf("rp1" to client))))
        }
        val okR = noFollow().get(
            "/end_session?client_id=rp1&post_logout_redirect_uri=" +
                java.net.URLEncoder.encode("https://rp.example/exact", Charsets.UTF_8),
        )
        assertEquals(HttpStatusCode.Found, okR.status)

        // Close but not exact → rejected.
        val nopeR = noFollow().get(
            "/end_session?client_id=rp1&post_logout_redirect_uri=" +
                java.net.URLEncoder.encode("https://rp.example/exact/", Charsets.UTF_8),
        )
        assertEquals(HttpStatusCode.BadRequest, nopeR.status)
    }

    @Test
    fun `OIDC session with discovery missing end_session_endpoint falls back to direct redirect`() = testApplication {
        val flows = InMemoryLogoutFlowStore(5.minutes)
        val sessions = InMemorySessionStore(5.minutes).apply {
            put("sid-oidc", sessionRow(sid = "sid-oidc", upstreamIdToken = "upstream-id-token"))
        }
        application {
            module(
                testDeps(
                    config = testConfig(issuer = ourIssuer),
                    clientRegistry = ClientRegistry(mapOf("rp1" to logoutClient())),
                    realmRegistry = RealmRegistry(mapOf("employees" to oidcRealm())),
                    sessionStore = sessions,
                    logoutFlowStore = flows,
                    // Upstream discovery omits end_session_endpoint.
                    oidcClient = upstreamOidcClient(endSessionEndpoint = null),
                ),
            )
        }
        val r = noFollow().get(
            "/end_session?client_id=rp1&post_logout_redirect_uri=https%3A%2F%2Frp.example%2Fafter-logout",
        ) {
            header(HttpHeaders.Cookie, "sid=sid-oidc")
        }

        assertEquals(HttpStatusCode.Found, r.status)
        assertEquals("https://rp.example/after-logout", r.headers[HttpHeaders.Location])
        // Fallback still clears local session.
        assertNull(sessions.get("sid-oidc"))
        // No LogoutFlow entry should have been stashed.
        // (We can't enumerate without iteration; the absence of a 302 to the
        // upstream URL above is the behavioral signal.)
        val setCookie = assertNotNull(r.headers[HttpHeaders.SetCookie])
        assertTrue("Max-Age=0" in setCookie)
    }

    @Test
    fun `end_session with state param echoes state to RP in final redirect`() = testApplication {
        val sessions = InMemorySessionStore(5.minutes)
        application {
            module(
                testDeps(
                    clientRegistry = ClientRegistry(mapOf("rp1" to logoutClient())),
                    sessionStore = sessions,
                ),
            )
        }
        val r = noFollow().get(
            "/end_session?client_id=rp1" +
                "&post_logout_redirect_uri=https%3A%2F%2Frp.example%2Fafter-logout" +
                "&state=rp-csrf-token",
        )
        assertEquals(HttpStatusCode.Found, r.status)
        val loc = Url(assertNotNull(r.headers[HttpHeaders.Location]))
        assertEquals("rp-csrf-token", loc.parameters["state"])
        assertEquals("rp.example", loc.host)
        assertEquals("/after-logout", loc.encodedPath)
    }

    // Sanity: the OIDC-chain leg also echoes the original RP state on the
    // final redirect (already covered by `OIDC session logout chains upstream
    // then returns to RP`, but a distinct small check gives faster failure
    // isolation if only the upstream-return echo regresses).
    @Test
    fun `upstream_return echoes RP state on final redirect`() = testApplication {
        val flows = InMemoryLogoutFlowStore(5.minutes).apply {
            put(
                "flow-state",
                LogoutFlow(
                    sid = "sid-oidc",
                    postLogoutRedirectUri = "https://rp.example/after-logout",
                    rpState = "original-rp-state",
                ),
            )
        }
        val sessions = InMemorySessionStore(5.minutes).apply {
            put("sid-oidc", sessionRow(sid = "sid-oidc", upstreamIdToken = "upstream-id-token"))
        }
        application {
            module(
                testDeps(
                    clientRegistry = ClientRegistry(mapOf("rp1" to logoutClient())),
                    sessionStore = sessions,
                    logoutFlowStore = flows,
                ),
            )
        }
        val r = noFollow().get("/end_session/upstream_return?state=flow-state")
        assertEquals(HttpStatusCode.Found, r.status)
        val loc = Url(assertNotNull(r.headers[HttpHeaders.Location]))
        assertEquals("original-rp-state", loc.parameters["state"])
    }

    @Test
    fun `clear-cookie attributes mirror original sid cookie shape`() = testApplication {
        val sessions = InMemorySessionStore(5.minutes).apply {
            put("sid-vp", sessionRow(sid = "sid-vp", realmId = "citizens"))
        }
        application {
            module(
                testDeps(
                    clientRegistry = ClientRegistry(mapOf("rp1" to logoutClient())),
                    realmRegistry = RealmRegistry(mapOf("citizens" to vpRealm())),
                    sessionStore = sessions,
                ),
            )
        }
        val r = noFollow().get(
            "/end_session?client_id=rp1&post_logout_redirect_uri=https%3A%2F%2Frp.example%2Fafter-logout",
        ) {
            header(HttpHeaders.Cookie, "sid=sid-vp")
        }
        val setCookie = assertNotNull(r.headers[HttpHeaders.SetCookie])
        assertTrue("HttpOnly" in setCookie, "clear-cookie must carry HttpOnly: $setCookie")
        assertTrue("Path=/" in setCookie, "clear-cookie must carry Path=/: $setCookie")
        assertTrue("SameSite=Lax" in setCookie, "clear-cookie must carry SameSite=Lax: $setCookie")
        // cookieSecure defaults to false in tests.
        assertFalse(
            "Secure" in setCookie.split(";").map { it.trim() },
            "Secure must NOT be set when cookieSecure=false; got: $setCookie",
        )
    }

}
