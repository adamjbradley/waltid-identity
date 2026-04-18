@file:OptIn(ExperimentalTime::class)

package id.walt.authop.e2e

import id.walt.authop.AuthOpDeps
import id.walt.authop.config.ClientConfig
import id.walt.authop.config.ClientRegistry
import id.walt.authop.config.OidcRealmConfig
import id.walt.authop.config.RealmConfig
import id.walt.authop.config.RealmMethod
import id.walt.authop.config.RealmRegistry
import id.walt.authop.config.TokenEndpointAuthMethod
import id.walt.authop.endpoints.TokenRoutesTest
import id.walt.authop.module
import id.walt.authop.testConfig
import id.walt.authop.testDeps
import id.walt.authop.upstream.OidcClient
import id.walt.crypto.keys.jwk.JWKKey
import id.walt.crypto.keys.KeyType
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.http.headersOf
import io.ktor.http.parameters
import io.ktor.http.setCookie
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.ExperimentalTime

/**
 * Full OIDC-realm flow, end-to-end, with a mocked upstream IdP:
 *
 *   `/authorize` → `/login` → `/login/realm/{id}` → (mock upstream) →
 *   `/callback/oidc` → `/consent` (or trusted-skip) → `/token` → `/userinfo`
 *
 * Every hop is driven manually via the Ktor test HTTP client with
 * `followRedirects = false` so each 302 is observable at assertion time.
 * Upstream HTTP (discovery / JWKS / token) is stubbed via a [MockEngine].
 *
 * ### Nonce plumbing
 *
 * `/login/realm/{id}` mints a fresh upstream nonce and stashes it in the
 * [id.walt.authop.store.UpstreamFlow]. A real upstream would echo that value
 * in the returned ID token; our mock must do the same. We capture the nonce
 * from the 302 Location on the kickoff leg into a mutable reference the
 * MockEngine's `/token` handler reads lazily.
 */
class OidcFlowE2ETest {

    // --- Fixtures --------------------------------------------------------------

    private val upstreamIssuer = "https://keycloak.test"
    private val upstreamClientId = "auth-op-upstream"
    private val upstreamClientSecret = "upstream-secret"
    private val upstreamKid = "upstream-kid-1"
    private val upstreamKey: JWKKey = runBlocking { JWKKey.generate(KeyType.RSA) }

    private val ourIssuer = "https://auth.theaustraliahack.com"

    private val rpClientId = "rp1"
    private val rpClientSecret = "rp-secret"
    private val rpRedirectUri = "https://rp.test/cb"
    private val rpNonce = "rp-original-nonce"
    private val rpState = "rp-original-state"

    private fun employeesRealm(): RealmConfig = RealmConfig(
        id = "employees",
        name = "Employees",
        method = RealmMethod.OIDC,
        oidc = OidcRealmConfig(
            issuer = upstreamIssuer,
            clientId = upstreamClientId,
            clientSecret = upstreamClientSecret,
            scopes = listOf("openid", "email", "profile"),
        ),
        claimMapping = mapOf(
            "sub" to "$.sub",
            "email" to "$.email",
            "name" to "$.name",
        ),
    )

    private fun rpClient(trusted: Boolean): ClientConfig = ClientConfig(
        clientId = rpClientId,
        clientSecret = rpClientSecret,
        tokenEndpointAuthMethod = TokenEndpointAuthMethod.CLIENT_SECRET_BASIC,
        redirectUris = listOf(rpRedirectUri),
        allowedScopes = listOf("openid", "profile", "email"),
        allowedRealms = listOf("employees"),
        trusted = trusted,
    )

    // --- Upstream mock ---------------------------------------------------------

    private fun discoveryJson(): String = buildJsonObject {
        put("issuer", upstreamIssuer)
        put("authorization_endpoint", "$upstreamIssuer/auth")
        put("token_endpoint", "$upstreamIssuer/token")
        put("userinfo_endpoint", "$upstreamIssuer/userinfo")
        put("jwks_uri", "$upstreamIssuer/jwks.json")
    }.toString()

    private fun jwksJson(): String = runBlocking {
        val pub = upstreamKey.getPublicKey().exportJWKObject()
        val withKid = JsonObject(pub.toMutableMap().apply { put("kid", JsonPrimitive(upstreamKid)) })
        buildJsonObject { put("keys", buildJsonArray { add(withKid) }) }.toString()
    }

    /** Sign an upstream ID token. [nonce] must match what auth-op captured. */
    private fun mintUpstreamIdToken(
        sub: String,
        nonce: String,
        extraClaims: Map<String, String>,
    ): String = runBlocking {
        val now = System.currentTimeMillis() / 1000
        val payload = buildJsonObject {
            put("iss", upstreamIssuer)
            put("sub", sub)
            put("aud", upstreamClientId)
            put("iat", now)
            put("exp", now + 3600)
            put("nonce", nonce)
            for ((k, v) in extraClaims) put(k, v)
        }
        upstreamKey.signJws(
            payload.toString().encodeToByteArray(),
            mapOf("typ" to JsonPrimitive("JWT"), "kid" to JsonPrimitive(upstreamKid)),
        )
    }

    /**
     * [OidcClient] whose MockEngine serves discovery + jwks statically and
     * serves /token lazily from [nonceSource] + [claims]. When [tokenFailure]
     * is non-null, /token returns that `(status, body)` pair instead.
     */
    private fun mockedOidcClient(
        nonceSource: () -> String?,
        sub: String = "user-abc",
        claims: Map<String, String> = mapOf(
            "email" to "alice@example.com",
            "name" to "Alice Liddell",
        ),
        tokenFailure: Pair<HttpStatusCode, String>? = null,
    ): OidcClient {
        val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")
        val engine = MockEngine { request ->
            when (request.url.encodedPath) {
                "/.well-known/openid-configuration" -> respond(discoveryJson(), HttpStatusCode.OK, jsonHeaders)
                "/jwks.json" -> respond(jwksJson(), HttpStatusCode.OK, jsonHeaders)
                "/token" -> {
                    if (tokenFailure != null) {
                        respond(tokenFailure.second, tokenFailure.first, jsonHeaders)
                    } else {
                        val nonce = nonceSource()
                            ?: error("test bug: nonce not captured before /token invoked")
                        val idToken = mintUpstreamIdToken(sub, nonce, claims)
                        val body = buildJsonObject {
                            put("token_type", "Bearer")
                            put("id_token", idToken)
                            put("access_token", "upstream-at")
                            put("expires_in", 3600)
                        }.toString()
                        respond(body, HttpStatusCode.OK, jsonHeaders)
                    }
                }
                else -> respond("", HttpStatusCode.NotFound)
            }
        }
        return OidcClient(httpClient = HttpClient(engine))
    }

    // --- Wiring ----------------------------------------------------------------

    private fun ApplicationTestBuilder.noFollow(): HttpClient = createClient {
        followRedirects = false
    }

    private fun buildDeps(trusted: Boolean, oidcClient: OidcClient): AuthOpDeps = testDeps(
        config = testConfig(issuer = ourIssuer),
        clientRegistry = ClientRegistry(mapOf(rpClientId to rpClient(trusted))),
        realmRegistry = RealmRegistry(mapOf("employees" to employeesRealm())),
        oidcClient = oidcClient,
    )

    private fun rpAuthorizeUrl(): String = buildString {
        append("/authorize?")
        append("client_id=").append(rpClientId)
        append("&redirect_uri=").append(java.net.URLEncoder.encode(rpRedirectUri, Charsets.UTF_8))
        append("&response_type=code")
        append("&scope=").append(java.net.URLEncoder.encode("openid profile email", Charsets.UTF_8))
        append("&state=").append(rpState)
        append("&nonce=").append(rpNonce)
        append("&code_challenge=").append(TokenRoutesTest.TEST_CHALLENGE)
        append("&code_challenge_method=S256")
    }

    /** Decode the base64url payload segment of a compact JWS into a JsonObject. */
    private fun jwtPayload(jws: String): JsonObject {
        val segment = jws.split(".")[1]
        val decoded = Base64.getUrlDecoder().decode(segment).decodeToString()
        return Json.parseToJsonElement(decoded) as JsonObject
    }

    private fun basicAuth(id: String, secret: String): String =
        "Basic " + Base64.getEncoder().encodeToString("$id:$secret".toByteArray())

    /**
     * Result of the upstream-side walk (legs 1..4): sid cookie value + the
     * upstream state the kickoff URL advertised. The shared helper centralises
     * the identical setup the 3 tests each need to run.
     */
    private data class FlowState(val sid: String, val upstreamState: String)

    /**
     * Drive `/authorize` → `/login` → `/login/realm/employees`, capturing the
     * sid cookie and the state/nonce on the upstream-authorize redirect. The
     * [captureNonce] callback is invoked with the captured nonce so callers
     * (typically a `var nonce` assignment) can plumb it into the MockEngine.
     */
    private suspend fun HttpClient.walkToUpstreamReturn(
        captureNonce: (String) -> Unit,
    ): FlowState {
        val r1 = get(rpAuthorizeUrl())
        assertEquals(HttpStatusCode.Found, r1.status)
        assertEquals("/login", r1.headers[HttpHeaders.Location])
        val sid = assertNotNull(r1.setCookie().firstOrNull { it.name == "sid" }).value

        val r2 = get("/login") { header(HttpHeaders.Cookie, "sid=$sid") }
        assertEquals(HttpStatusCode.OK, r2.status)
        assertTrue("Employees" in r2.bodyAsText())

        val r3 = get("/login/realm/employees") { header(HttpHeaders.Cookie, "sid=$sid") }
        assertEquals(HttpStatusCode.Found, r3.status)
        val upstreamUrl = Url(assertNotNull(r3.headers[HttpHeaders.Location]))
        assertEquals("keycloak.test", upstreamUrl.host)
        assertEquals("/auth", upstreamUrl.encodedPath)
        val upstreamState = assertNotNull(upstreamUrl.parameters["state"])
        captureNonce(assertNotNull(upstreamUrl.parameters["nonce"]))
        return FlowState(sid, upstreamState)
    }

    // ==========================================================================
    // 1. Happy path — trusted client (skips consent page)
    // ==========================================================================

    @Test
    fun `full OIDC realm flow produces valid tokens`() = testApplication {
        var capturedUpstreamNonce: String? = null
        val oidcClient = mockedOidcClient(nonceSource = { capturedUpstreamNonce })
        val deps = buildDeps(trusted = true, oidcClient = oidcClient)
        application { module(deps) }
        val http = noFollow()

        val (sid, upstreamState) = http.walkToUpstreamReturn { capturedUpstreamNonce = it }
        val cookie = "sid=$sid"

        // Leg 4: upstream → /callback/oidc.
        val r4 = http.get("/callback/oidc?code=upstream-code&state=$upstreamState") {
            header(HttpHeaders.Cookie, cookie)
        }
        assertEquals(HttpStatusCode.Found, r4.status)
        assertEquals("/consent", r4.headers[HttpHeaders.Location])

        // Leg 5: trusted-skip /consent → RP with code.
        val r5 = http.get("/consent") { header(HttpHeaders.Cookie, cookie) }
        assertEquals(HttpStatusCode.Found, r5.status)
        val rpRedirect = Url(assertNotNull(r5.headers[HttpHeaders.Location]))
        assertEquals("rp.test", rpRedirect.host)
        assertEquals("/cb", rpRedirect.encodedPath)
        val authCode = assertNotNull(rpRedirect.parameters["code"])
        assertEquals(rpState, rpRedirect.parameters["state"], "RP's original state echoed")

        // Leg 6: RP POST /token.
        val r6 = http.submitForm(
            url = "/token",
            formParameters = parameters {
                append("grant_type", "authorization_code")
                append("code", authCode)
                append("redirect_uri", rpRedirectUri)
                append("code_verifier", TokenRoutesTest.TEST_VERIFIER)
            },
        ) { header(HttpHeaders.Authorization, basicAuth(rpClientId, rpClientSecret)) }
        assertEquals(HttpStatusCode.OK, r6.status)
        val tokenBody = Json.parseToJsonElement(r6.bodyAsText()) as JsonObject
        val accessToken = assertNotNull(tokenBody["access_token"]?.jsonPrimitive?.content)
        val idToken = assertNotNull(tokenBody["id_token"]?.jsonPrimitive?.content)
        assertEquals("Bearer", tokenBody["token_type"]?.jsonPrimitive?.content)
        assertNotNull(tokenBody["expires_in"])

        // Assert on id_token payload.
        val idPayload = jwtPayload(idToken)
        assertEquals(ourIssuer, idPayload["iss"]?.jsonPrimitive?.content)
        assertEquals("user-abc", idPayload["sub"]?.jsonPrimitive?.content)
        assertEquals(rpClientId, idPayload["aud"]?.jsonPrimitive?.content)
        assertEquals(rpNonce, idPayload["nonce"]?.jsonPrimitive?.content, "RP's nonce, NOT upstream's")
        assertEquals("alice@example.com", idPayload["email"]?.jsonPrimitive?.content)
        assertEquals("Alice Liddell", idPayload["name"]?.jsonPrimitive?.content)
        assertEquals("employees", idPayload["realm"]?.jsonPrimitive?.content)

        // `acr` is stamped onto the [Session] by the callback but NOT
        // propagated onto AuthRequest.claims → it doesn't reach the id_token
        // via the current Task 10/11 wiring. Assert it via the store so the
        // realm adapter's responsibility is still covered; id_token.acr
        // propagation is a gap for a later hardening task. Task 15 is
        // pure-test; we assert observed behaviour.
        val session = assertNotNull(deps.sessionStore.get(sid))
        assertEquals("urn:walt:upstream-oidc", session.acr)
        assertEquals("employees", session.realmId)
        assertEquals("user-abc", session.subject)

        // Leg 7: /userinfo with the access token.
        val r7 = http.get("/userinfo") {
            header(HttpHeaders.Authorization, "Bearer $accessToken")
        }
        assertEquals(HttpStatusCode.OK, r7.status)
        val userinfo = Json.parseToJsonElement(r7.bodyAsText()) as JsonObject
        // Only `sub` surfaces: TokenRoutes mints access_token with
        // `claims=emptyMap()`, so profile/email claims never enter the
        // access-token payload and thus don't appear at /userinfo.
        assertEquals(setOf("sub"), userinfo.keys)
        assertEquals("user-abc", userinfo["sub"]?.jsonPrimitive?.content)
    }

    // ==========================================================================
    // 2. Non-trusted client — consent page renders, POST completes the flow
    // ==========================================================================

    @Test
    fun `non-trusted client shows consent page on E2E OIDC flow`() = testApplication {
        var capturedUpstreamNonce: String? = null
        val oidcClient = mockedOidcClient(nonceSource = { capturedUpstreamNonce })
        val deps = buildDeps(trusted = false, oidcClient = oidcClient)
        application { module(deps) }
        val http = noFollow()

        val (sid, upstreamState) = http.walkToUpstreamReturn { capturedUpstreamNonce = it }
        val cookie = "sid=$sid"

        val r4 = http.get("/callback/oidc?code=upstream-code&state=$upstreamState") {
            header(HttpHeaders.Cookie, cookie)
        }
        assertEquals(HttpStatusCode.Found, r4.status)
        assertEquals("/consent", r4.headers[HttpHeaders.Location])

        // GET /consent renders HTML (non-trusted branch).
        val rGet = http.get("/consent") { header(HttpHeaders.Cookie, cookie) }
        assertEquals(HttpStatusCode.OK, rGet.status, "non-trusted GET /consent renders HTML")
        val consentBody = rGet.bodyAsText()
        assertTrue("Authorize $rpClientId" in consentBody)
        val csrfToken = Regex("""name=['"]csrf_token['"]\s+value=['"]([^'"]+)['"]""")
            .find(consentBody)
            ?.groupValues?.get(1)
            ?: error("csrf_token hidden field missing: $consentBody")
        assertTrue(csrfToken.isNotBlank())

        // POST /consent decision=accept → 302 to RP with code.
        val rPost = http.submitForm(
            url = "/consent",
            formParameters = parameters {
                append("csrf_token", csrfToken)
                append("decision", "accept")
            },
        ) { header(HttpHeaders.Cookie, cookie) }
        assertEquals(HttpStatusCode.Found, rPost.status)
        val rpRedirect = Url(assertNotNull(rPost.headers[HttpHeaders.Location]))
        val authCode = assertNotNull(rpRedirect.parameters["code"])
        assertEquals(rpState, rpRedirect.parameters["state"])

        // Exchange the code — proves the full flow round-trips post-consent.
        val rToken = http.submitForm(
            url = "/token",
            formParameters = parameters {
                append("grant_type", "authorization_code")
                append("code", authCode)
                append("redirect_uri", rpRedirectUri)
                append("code_verifier", TokenRoutesTest.TEST_VERIFIER)
            },
        ) { header(HttpHeaders.Authorization, basicAuth(rpClientId, rpClientSecret)) }
        assertEquals(HttpStatusCode.OK, rToken.status)
        val body = Json.parseToJsonElement(rToken.bodyAsText()) as JsonObject
        val idToken = assertNotNull(body["id_token"]?.jsonPrimitive?.content)
        val payload = jwtPayload(idToken)
        assertEquals("user-abc", payload["sub"]?.jsonPrimitive?.content)
        assertEquals(rpNonce, payload["nonce"]?.jsonPrimitive?.content)
        assertEquals("employees", payload["realm"]?.jsonPrimitive?.content)
        assertEquals("alice@example.com", payload["email"]?.jsonPrimitive?.content)
        val session = assertNotNull(deps.sessionStore.get(sid))
        assertEquals("urn:walt:upstream-oidc", session.acr)
    }

    // ==========================================================================
    // 3. Upstream /token 400 → /callback redirects RP with access_denied
    // ==========================================================================

    @Test
    fun `upstream error redirects back to RP with access_denied`() = testApplication {
        val oidcClient = mockedOidcClient(
            // Never read — /token fails before the mock mints anything.
            nonceSource = { "unused" },
            tokenFailure = HttpStatusCode.BadRequest to """{"error":"invalid_grant"}""",
        )
        val deps = buildDeps(trusted = true, oidcClient = oidcClient)
        application { module(deps) }
        val http = noFollow()

        val (sid, upstreamState) = http.walkToUpstreamReturn { /* nonce unused */ }
        val cookie = "sid=$sid"

        val r4 = http.get("/callback/oidc?code=bad-code&state=$upstreamState") {
            header(HttpHeaders.Cookie, cookie)
        }
        assertEquals(HttpStatusCode.Found, r4.status)
        val rpRedirect = Url(assertNotNull(r4.headers[HttpHeaders.Location]))
        assertEquals("rp.test", rpRedirect.host)
        assertEquals("/cb", rpRedirect.encodedPath)
        assertEquals("access_denied", rpRedirect.parameters["error"])
        assertEquals(rpState, rpRedirect.parameters["state"])
        val desc = assertNotNull(rpRedirect.parameters["error_description"])
        assertTrue(desc.startsWith("upstream:"), "description should start 'upstream:'; got: $desc")

        // No session was created — a failed upstream exchange must NOT leave
        // a half-authenticated session behind.
        assertNull(deps.sessionStore.get(sid))
    }
}
