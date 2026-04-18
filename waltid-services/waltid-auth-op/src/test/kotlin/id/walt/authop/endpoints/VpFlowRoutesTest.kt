@file:OptIn(ExperimentalTime::class)

package id.walt.authop.endpoints

import id.walt.authop.config.ClientRegistry
import id.walt.authop.config.Oid4vpRealmConfig
import id.walt.authop.config.OidcRealmConfig
import id.walt.authop.config.RealmConfig
import id.walt.authop.config.RealmMethod
import id.walt.authop.config.RealmRegistry
import id.walt.authop.config.SubStrategy
import id.walt.authop.domain.AuthRequest
import id.walt.authop.domain.VpSession
import id.walt.authop.domain.VpSessionStatus
import id.walt.authop.jsonClient
import id.walt.authop.module
import id.walt.authop.store.InMemoryAuthRequestStore
import id.walt.authop.store.InMemoryVpSessionStore
import id.walt.authop.testClient
import id.walt.authop.testConfig
import id.walt.authop.testDeps
import id.walt.authop.upstream.Verifier2Client
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.http.headersOf
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.ExperimentalTime

/**
 * Tests for Task 17's VP flow routes:
 *  - `GET /login/realm/{id}` for an OID4VP realm — kickoff + QR page
 *  - `GET /login/realm/{id}/status` — cookie-bound status polling
 *
 * Tests exercise the real [Verifier2Client] via a [MockEngine]-backed
 * [HttpClient] that returns a deterministic
 * `VerificationSessionCreationResponse`. This matches the
 * `OidcCallbackRoutesTest` pattern of "real client, fake transport".
 */
class VpFlowRoutesTest {

    private val ourIssuer = "https://auth.example"
    private val verifierBase = "https://verifier.example"

    /** Deterministic session id returned by the mock verifier. */
    private val mockSessionId = "vp-sess-xyz"
    private val mockBootstrap = "openid4vp://boot?foo=bar"
    private val mockFull = "openid4vp://full?foo=bar"

    // ---- fixtures -------------------------------------------------------------

    private fun authRequestFor(
        sid: String = "sid-abc",
        clientId: String = "rp1",
        activeVpSessionId: String? = null,
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
        activeVpSessionId = activeVpSessionId,
    )

    private fun writeDcql(tmp: Path): Path {
        val path = tmp.resolve("dcql.json")
        Files.writeString(
            path,
            buildJsonObject {
                put("credentials", kotlinx.serialization.json.buildJsonArray { })
            }.toString(),
        )
        return path
    }

    private fun vpRealm(
        id: String = "vp",
        dcqlPath: String,
        rpId: String? = null,
    ): RealmConfig = RealmConfig(
        id = id,
        name = "VP Realm",
        method = RealmMethod.OID4VP,
        oidc = null,
        oid4vp = Oid4vpRealmConfig(
            verifierBaseUrl = verifierBase,
            dcqlQueryFile = dcqlPath,
            webhookCallbackPath = "/vp/webhook",
            rpId = rpId,
        ),
        subStrategy = SubStrategy.EPHEMERAL,
    )

    private fun realmRegistry(vararg realms: RealmConfig): RealmRegistry =
        RealmRegistry(realms.associateBy { it.id })

    /**
     * [Verifier2Client] whose MockEngine returns a hard-coded
     * `VerificationSessionCreationResponse`. Captures request body / headers
     * for tests that need to assert what we sent upstream.
     */
    private data class CapturedRequest(
        var method: String? = null,
        var path: String? = null,
        var query: String? = null,
        var body: String? = null,
    )

    private fun mockVerifierClient(
        sessionId: String = mockSessionId,
        bootstrap: String? = mockBootstrap,
        full: String = mockFull,
        captured: CapturedRequest = CapturedRequest(),
    ): Verifier2Client {
        val engine = MockEngine { request ->
            captured.method = request.method.value
            captured.path = request.url.encodedPath
            captured.query = request.url.encodedQuery.ifEmpty { null }
            captured.body = String(request.body.toByteArray())
            val response = buildJsonObject {
                put("sessionId", sessionId)
                if (bootstrap != null) put("bootstrapAuthorizationRequestUrl", bootstrap)
                put("fullAuthorizationRequestUrl", full)
            }.toString()
            respond(
                response,
                HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        return Verifier2Client(httpClient = HttpClient(engine))
    }

    private fun ApplicationTestBuilder.noFollow(): HttpClient = createClient {
        followRedirects = false
    }

    // ==========================================================================
    // Required tests (verbatim names from plan)
    // ==========================================================================

    @Test
    fun `selecting VP realm renders QR page and creates VpSession`(
        @org.junit.jupiter.api.io.TempDir tmp: Path,
    ) = testApplication {
        val store = InMemoryAuthRequestStore(5.minutes).apply {
            put("sid-abc", authRequestFor())
        }
        val vpSessions = InMemoryVpSessionStore(5.minutes)
        val dcql = writeDcql(tmp)
        application {
            module(
                testDeps(
                    config = testConfig(issuer = ourIssuer),
                    authRequestStore = store,
                    vpSessionStore = vpSessions,
                    realmRegistry = realmRegistry(vpRealm(dcqlPath = dcql.toString())),
                    verifier2Client = mockVerifierClient(),
                ),
            )
        }

        val r = noFollow().get("/login/realm/vp") {
            header(HttpHeaders.Cookie, "sid=sid-abc")
        }

        assertEquals(HttpStatusCode.OK, r.status)
        val body = r.bodyAsText()
        // QR page embeds the bootstrap URL (verifier payload) and the
        // verifier session id in data attributes tests can assert on.
        assertTrue(
            body.contains(mockBootstrap),
            "QR page must embed bootstrap authorization URL; body was:\n$body",
        )
        assertTrue(
            body.contains("data-verifier-session-id=\"$mockSessionId\""),
            "QR page must embed verifierSessionId as data attribute",
        )
        // VpSession was persisted keyed by the verifier's session id.
        val stored = assertNotNull(vpSessions.get(mockSessionId))
        assertEquals("vp", stored.realmId)
        assertEquals("sid-abc", stored.authRequestId)
        assertEquals("sid-abc", stored.sessionCookieId)
        assertEquals(VpSessionStatus.PENDING, stored.status)
        // AuthRequest was stamped with the active VP session id.
        val updated = assertNotNull(store.get("sid-abc"))
        assertEquals(mockSessionId, updated.activeVpSessionId)
        assertEquals("vp", updated.chosenRealmId)
    }

    @Test
    fun `status endpoint reports PENDING until webhook fires`(
        @org.junit.jupiter.api.io.TempDir tmp: Path,
    ) = testApplication {
        val store = InMemoryAuthRequestStore(5.minutes).apply {
            put("sid-abc", authRequestFor())
        }
        val vpSessions = InMemoryVpSessionStore(5.minutes).apply {
            put(
                mockSessionId,
                VpSession(
                    verifierSessionId = mockSessionId,
                    realmId = "vp",
                    authRequestId = "sid-abc",
                    sessionCookieId = "sid-abc",
                    webhookSecret = "secret",
                    status = VpSessionStatus.PENDING,
                    capturedCredential = null,
                ),
            )
        }
        val dcql = writeDcql(tmp)
        application {
            module(
                testDeps(
                    config = testConfig(issuer = ourIssuer),
                    authRequestStore = store,
                    vpSessionStore = vpSessions,
                    realmRegistry = realmRegistry(vpRealm(dcqlPath = dcql.toString())),
                ),
            )
        }

        val r = jsonClient().get("/login/realm/vp/status?verifierSessionId=$mockSessionId") {
            header(HttpHeaders.Cookie, "sid=sid-abc")
        }

        assertEquals(HttpStatusCode.OK, r.status)
        val body: JsonObject = r.body()
        assertEquals("PENDING", body["status"]?.jsonPrimitive?.content)

        // Simulate the webhook firing by flipping status.
        vpSessions.update(mockSessionId) { it.copy(status = VpSessionStatus.SUCCESSFUL) }

        val r2 = jsonClient().get("/login/realm/vp/status?verifierSessionId=$mockSessionId") {
            header(HttpHeaders.Cookie, "sid=sid-abc")
        }
        assertEquals(HttpStatusCode.OK, r2.status)
        val body2: JsonObject = r2.body()
        assertEquals("SUCCESSFUL", body2["status"]?.jsonPrimitive?.content)
    }

    @Test
    fun `status endpoint rejects requests whose sid cookie mismatches stored sessionCookieId`(
        @org.junit.jupiter.api.io.TempDir tmp: Path,
    ) = testApplication {
        val vpSessions = InMemoryVpSessionStore(5.minutes).apply {
            put(
                mockSessionId,
                VpSession(
                    verifierSessionId = mockSessionId,
                    realmId = "vp",
                    authRequestId = "sid-abc",
                    // Session was opened by sid-abc's browser…
                    sessionCookieId = "sid-abc",
                    webhookSecret = "secret",
                    status = VpSessionStatus.PENDING,
                    capturedCredential = null,
                ),
            )
        }
        val dcql = writeDcql(tmp)
        application {
            module(
                testDeps(
                    config = testConfig(issuer = ourIssuer),
                    vpSessionStore = vpSessions,
                    realmRegistry = realmRegistry(vpRealm(dcqlPath = dcql.toString())),
                ),
            )
        }

        // …but the attacker polls from a different browser (different sid).
        val r = noFollow().get("/login/realm/vp/status?verifierSessionId=$mockSessionId") {
            header(HttpHeaders.Cookie, "sid=sid-attacker")
        }
        assertEquals(HttpStatusCode.Forbidden, r.status)

        // Missing cookie entirely is also forbidden (bound session requires sid).
        val r2 = noFollow().get("/login/realm/vp/status?verifierSessionId=$mockSessionId")
        assertEquals(HttpStatusCode.Forbidden, r2.status)
    }

    @Test
    fun `recovery - refreshing page for an already-SUCCESSFUL session redirects to complete`(
        @org.junit.jupiter.api.io.TempDir tmp: Path,
    ) = testApplication {
        val store = InMemoryAuthRequestStore(5.minutes).apply {
            // AuthRequest carries the already-minted VP session id.
            put("sid-abc", authRequestFor(activeVpSessionId = mockSessionId))
        }
        val vpSessions = InMemoryVpSessionStore(5.minutes).apply {
            put(
                mockSessionId,
                VpSession(
                    verifierSessionId = mockSessionId,
                    realmId = "vp",
                    authRequestId = "sid-abc",
                    sessionCookieId = "sid-abc",
                    webhookSecret = "secret",
                    status = VpSessionStatus.SUCCESSFUL,
                    capturedCredential = null,
                ),
            )
        }
        val dcql = writeDcql(tmp)
        application {
            module(
                testDeps(
                    config = testConfig(issuer = ourIssuer),
                    authRequestStore = store,
                    vpSessionStore = vpSessions,
                    realmRegistry = realmRegistry(vpRealm(dcqlPath = dcql.toString())),
                    // If the kickoff helper contacts the verifier we'll know
                    // the recovery branch didn't fire: the MockEngine below
                    // returns 404, which would surface as a 400 from kickoff.
                    verifier2Client = Verifier2Client(
                        httpClient = HttpClient(MockEngine { respond("no", HttpStatusCode.NotFound) }),
                    ),
                ),
            )
        }

        val r = noFollow().get("/login/realm/vp") {
            header(HttpHeaders.Cookie, "sid=sid-abc")
        }

        assertEquals(HttpStatusCode.Found, r.status)
        val loc = assertNotNull(r.headers[HttpHeaders.Location])
        val url = Url("https://local$loc")
        assertEquals("/login/realm/vp/complete", url.encodedPath)
        assertEquals(mockSessionId, url.parameters["verifierSessionId"])
    }

    // ==========================================================================
    // Additional tests
    // ==========================================================================

    @Test
    fun `status endpoint returns 404 for unknown verifierSessionId`(
        @org.junit.jupiter.api.io.TempDir tmp: Path,
    ) = testApplication {
        val dcql = writeDcql(tmp)
        application {
            module(
                testDeps(
                    realmRegistry = realmRegistry(vpRealm(dcqlPath = dcql.toString())),
                ),
            )
        }

        val r = noFollow().get("/login/realm/vp/status?verifierSessionId=does-not-exist") {
            header(HttpHeaders.Cookie, "sid=sid-abc")
        }
        assertEquals(HttpStatusCode.NotFound, r.status)
    }

    @Test
    fun `status endpoint returns 400 when verifierSessionId param is missing`(
        @org.junit.jupiter.api.io.TempDir tmp: Path,
    ) = testApplication {
        val dcql = writeDcql(tmp)
        application {
            module(
                testDeps(
                    realmRegistry = realmRegistry(vpRealm(dcqlPath = dcql.toString())),
                ),
            )
        }

        val r = noFollow().get("/login/realm/vp/status") {
            header(HttpHeaders.Cookie, "sid=sid-abc")
        }
        assertEquals(HttpStatusCode.BadRequest, r.status)
    }

    @Test
    fun `QR page contains bootstrap URL and verifier session id`(
        @org.junit.jupiter.api.io.TempDir tmp: Path,
    ) = testApplication {
        val store = InMemoryAuthRequestStore(5.minutes).apply {
            put("sid-abc", authRequestFor())
        }
        val dcql = writeDcql(tmp)
        application {
            module(
                testDeps(
                    config = testConfig(issuer = ourIssuer),
                    authRequestStore = store,
                    realmRegistry = realmRegistry(vpRealm(dcqlPath = dcql.toString())),
                    verifier2Client = mockVerifierClient(),
                ),
            )
        }

        val r = noFollow().get("/login/realm/vp") {
            header(HttpHeaders.Cookie, "sid=sid-abc")
        }
        assertEquals(HttpStatusCode.OK, r.status)
        val body = r.bodyAsText()
        // Bootstrap URL (the QR payload) must be on the page.
        assertTrue(body.contains(mockBootstrap), "bootstrap URL must be rendered")
        // Deep link too.
        assertTrue(body.contains(mockFull), "same-device deep link must be rendered")
        // Verifier session id and status URL exposed as data attrs.
        assertTrue(body.contains("data-verifier-session-id=\"$mockSessionId\""))
        assertTrue(body.contains("/login/realm/vp/status?verifierSessionId=$mockSessionId"))
    }

    @Test
    fun `VP realm kickoff stores webhook secret and session cookie id on VpSession`(
        @org.junit.jupiter.api.io.TempDir tmp: Path,
    ) = testApplication {
        val store = InMemoryAuthRequestStore(5.minutes).apply {
            put("sid-abc", authRequestFor())
        }
        val vpSessions = InMemoryVpSessionStore(5.minutes)
        val dcql = writeDcql(tmp)
        val captured = CapturedRequest()
        application {
            module(
                testDeps(
                    config = testConfig(issuer = ourIssuer),
                    authRequestStore = store,
                    vpSessionStore = vpSessions,
                    realmRegistry = realmRegistry(vpRealm(dcqlPath = dcql.toString())),
                    verifier2Client = mockVerifierClient(captured = captured),
                ),
            )
        }

        noFollow().get("/login/realm/vp") {
            header(HttpHeaders.Cookie, "sid=sid-abc")
        }

        val stored = assertNotNull(vpSessions.get(mockSessionId))
        assertTrue(stored.webhookSecret.isNotBlank(), "webhook secret must be minted")
        assertNotEquals("secret", stored.webhookSecret, "webhook secret must be a fresh random value")
        assertEquals("sid-abc", stored.sessionCookieId, "session cookie must bind to caller's sid")
        // Webhook URL passed to verifier2 must be our canonical issuer + path.
        val sentBody = assertNotNull(captured.body)
        assertTrue(
            sentBody.contains("$ourIssuer/vp/webhook"),
            "verifier-api2 must receive absolute webhook URL; body=$sentBody",
        )
        // Shared webhook secret must travel to verifier-api2.
        assertTrue(
            sentBody.contains(stored.webhookSecret),
            "webhook secret must be transmitted to verifier-api2",
        )
    }

    @Test
    fun `unknown realm returns 400 at login realm id`(
        @org.junit.jupiter.api.io.TempDir tmp: Path,
    ) = testApplication {
        val store = InMemoryAuthRequestStore(5.minutes).apply {
            put("sid-abc", authRequestFor())
        }
        val dcql = writeDcql(tmp)
        application {
            module(
                testDeps(
                    authRequestStore = store,
                    realmRegistry = realmRegistry(vpRealm(dcqlPath = dcql.toString())),
                ),
            )
        }

        val r = noFollow().get("/login/realm/does-not-exist") {
            header(HttpHeaders.Cookie, "sid=sid-abc")
        }
        assertEquals(HttpStatusCode.BadRequest, r.status)
    }

    @Test
    fun `realm not in client allowedRealms returns 400`(
        @org.junit.jupiter.api.io.TempDir tmp: Path,
    ) = testApplication {
        val store = InMemoryAuthRequestStore(5.minutes).apply {
            put("sid-abc", authRequestFor())
        }
        val filtered = testClient(clientId = "rp1").copy(
            allowedRealms = listOf("other-realm"),
        )
        val dcql = writeDcql(tmp)
        application {
            module(
                testDeps(
                    authRequestStore = store,
                    clientRegistry = ClientRegistry(mapOf("rp1" to filtered)),
                    realmRegistry = realmRegistry(
                        vpRealm(dcqlPath = dcql.toString()),
                        RealmConfig(
                            id = "other-realm",
                            name = "Other",
                            method = RealmMethod.OIDC,
                            oidc = OidcRealmConfig(
                                issuer = "https://other.example",
                                clientId = "o",
                                clientSecret = "s",
                            ),
                        ),
                    ),
                ),
            )
        }

        val r = noFollow().get("/login/realm/vp") {
            header(HttpHeaders.Cookie, "sid=sid-abc")
        }
        assertEquals(HttpStatusCode.BadRequest, r.status)
    }
}
