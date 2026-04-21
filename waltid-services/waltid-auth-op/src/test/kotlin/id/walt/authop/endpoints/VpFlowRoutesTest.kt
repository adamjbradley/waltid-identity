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
import id.walt.authop.domain.CapturedCredential
import id.walt.authop.domain.Session
import id.walt.authop.domain.VpSession
import id.walt.authop.domain.VpSessionStatus
import id.walt.authop.jsonClient
import id.walt.authop.module
import id.walt.authop.store.InMemoryAuthRequestStore
import id.walt.authop.store.InMemoryAuthCodeStore
import id.walt.authop.store.InMemorySessionStore
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
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.http.contentType
import io.ktor.http.headersOf
import io.ktor.http.parameters
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
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

    // ==========================================================================
    // Task 18 — verifier-api2 webhook callback
    // ==========================================================================
    //
    // verifier-api2 POSTs a JSON body `{target, event, session}` with
    // `Authorization: Bearer <secret>` back to `/login/realm/{id}/webhook`
    // after running presentation + policy verification.
    //
    // Shape verified against verifier-api2's own code (see vpWebhookRoutes
    // kdoc in VpFlowRoutes.kt for file:line citations).

    /** A webhook secret used across the webhook tests. */
    private val testWebhookSecret = "test-webhook-secret-abcdef0123456789"

    /** Seeds a [VpSession] with the given secret + status. */
    private fun seedVpSession(
        store: InMemoryVpSessionStore,
        sessionId: String = mockSessionId,
        secret: String = testWebhookSecret,
        status: VpSessionStatus = VpSessionStatus.PENDING,
        captured: CapturedCredential? = null,
    ) {
        store.put(
            sessionId,
            VpSession(
                verifierSessionId = sessionId,
                realmId = "vp",
                authRequestId = "sid-abc",
                sessionCookieId = "sid-abc",
                webhookSecret = secret,
                status = status,
                capturedCredential = captured,
            ),
        )
    }

    /**
     * Build a verifier-api2 style webhook envelope. Shape per
     * `KtorSessionUpdate.kt:7-11` + `Verification2Session.kt:33,58,99-100`.
     */
    private fun webhookBody(
        sessionId: String = mockSessionId,
        event: String = "policy_results_available",
        status: String = "SUCCESSFUL",
        withCredentialData: Boolean = true,
    ): String = buildJsonObject {
        put("target", sessionId)
        put("event", event)
        put(
            "session",
            buildJsonObject {
                put("id", sessionId)
                put("status", status)
                if (withCredentialData) {
                    put(
                        "presentedCredentials",
                        buildJsonObject {
                            put(
                                "my_credential",
                                buildJsonArray {
                                    add(
                                        buildJsonObject {
                                            put("format", "jwt_vc_json")
                                            put("credential", "eyJ-stub-credential")
                                        },
                                    )
                                },
                            )
                        },
                    )
                    put(
                        "presentedPresentations",
                        buildJsonObject {
                            put(
                                "my_credential",
                                buildJsonObject {
                                    put("type", "VerifiablePresentation")
                                },
                            )
                        },
                    )
                }
            },
        )
    }.toString()

    private fun ApplicationTestBuilder.moduleForWebhook(
        vpSessions: InMemoryVpSessionStore,
    ) {
        application {
            module(
                testDeps(
                    vpSessionStore = vpSessions,
                    realmRegistry = realmRegistry(vpRealm(dcqlPath = "unused")),
                ),
            )
        }
    }

    @Test
    fun `webhook with correct secret captures credential and marks SUCCESSFUL`() =
        testApplication {
            val vpSessions = InMemoryVpSessionStore(5.minutes)
            seedVpSession(vpSessions)
            moduleForWebhook(vpSessions)

            val r = jsonClient().post("/login/realm/vp/webhook") {
                header(HttpHeaders.Authorization, "Bearer $testWebhookSecret")
                contentType(ContentType.Application.Json)
                setBody(webhookBody())
            }

            assertEquals(HttpStatusCode.OK, r.status)
            val stored = assertNotNull(vpSessions.get(mockSessionId))
            assertEquals(VpSessionStatus.SUCCESSFUL, stored.status)
            val captured = assertNotNull(stored.capturedCredential)
            assertTrue(
                captured.presentedCredentials.containsKey("my_credential"),
                "presentedCredentials must be captured into VpSession",
            )
            assertTrue(
                captured.presentedPresentations.containsKey("my_credential"),
                "presentedPresentations must be captured into VpSession",
            )
        }

    @Test
    fun `webhook with wrong secret returns 401 and does not mutate store`() =
        testApplication {
            val vpSessions = InMemoryVpSessionStore(5.minutes)
            seedVpSession(vpSessions)
            moduleForWebhook(vpSessions)

            val r = jsonClient().post("/login/realm/vp/webhook") {
                header(HttpHeaders.Authorization, "Bearer WRONG-secret")
                contentType(ContentType.Application.Json)
                setBody(webhookBody())
            }

            assertEquals(HttpStatusCode.Unauthorized, r.status)
            val stored = assertNotNull(vpSessions.get(mockSessionId))
            assertEquals(
                VpSessionStatus.PENDING, stored.status,
                "wrong secret must not flip status",
            )
            assertNull(stored.capturedCredential, "wrong secret must not capture credential")
        }

    @Test
    fun `webhook for unknown verifierSessionId returns 401`() =
        testApplication {
            // Oracle-closed design: unknown session and wrong secret both return
            // 401 uniformly. See vpWebhookRoutes kdoc in VpFlowRoutes.kt.
            val vpSessions = InMemoryVpSessionStore(5.minutes)
            moduleForWebhook(vpSessions)

            val r = jsonClient().post("/login/realm/vp/webhook") {
                header(HttpHeaders.Authorization, "Bearer $testWebhookSecret")
                contentType(ContentType.Application.Json)
                setBody(webhookBody(sessionId = "does-not-exist"))
            }

            assertEquals(HttpStatusCode.Unauthorized, r.status)
            // And nothing was created as a side effect.
            assertNull(vpSessions.get("does-not-exist"))
        }

    @Test
    fun `webhook non-policy_results event is 200 but does not capture`() =
        testApplication {
            val vpSessions = InMemoryVpSessionStore(5.minutes)
            seedVpSession(vpSessions)
            moduleForWebhook(vpSessions)

            val r = jsonClient().post("/login/realm/vp/webhook") {
                header(HttpHeaders.Authorization, "Bearer $testWebhookSecret")
                contentType(ContentType.Application.Json)
                // `attempted_presentation` is a real earlier event per
                // SessionEvent.kt — not a terminal one, so we ACK without
                // capturing.
                setBody(webhookBody(event = "attempted_presentation", status = "IN_USE"))
            }

            assertEquals(HttpStatusCode.OK, r.status)
            val stored = assertNotNull(vpSessions.get(mockSessionId))
            assertEquals(
                VpSessionStatus.PENDING, stored.status,
                "non-terminal event must not transition status",
            )
            assertNull(stored.capturedCredential, "non-terminal event must not capture")
        }

    @Test
    fun `webhook UNSUCCESSFUL status captured correctly`() =
        testApplication {
            val vpSessions = InMemoryVpSessionStore(5.minutes)
            seedVpSession(vpSessions)
            moduleForWebhook(vpSessions)

            val r = jsonClient().post("/login/realm/vp/webhook") {
                header(HttpHeaders.Authorization, "Bearer $testWebhookSecret")
                contentType(ContentType.Application.Json)
                setBody(
                    webhookBody(
                        status = "UNSUCCESSFUL",
                        withCredentialData = false,
                    ),
                )
            }

            assertEquals(HttpStatusCode.OK, r.status)
            val stored = assertNotNull(vpSessions.get(mockSessionId))
            assertEquals(
                VpSessionStatus.UNSUCCESSFUL, stored.status,
                "terminal UNSUCCESSFUL must transition status",
            )
            assertNull(
                stored.capturedCredential,
                "UNSUCCESSFUL must not create a CapturedCredential (useless for /complete)",
            )
        }

    @Test
    fun `constant-time compare used for secret`() {
        // Code-inspection test: VpFlowRoutes.kt's webhook handler MUST use
        // java.security.MessageDigest.isEqual for the secret compare — never
        // `==`, `equals`, or `contentEquals`. We assert both the presence of
        // MessageDigest.isEqual and the absence of any use of String `==` on
        // the webhookSecret value. This guards against a future refactor that
        // "simplifies" the compare and reintroduces a timing side channel.
        val src = Files.readString(
            Path.of(
                "src/main/kotlin/id/walt/authop/endpoints/VpFlowRoutes.kt",
            ),
        )
        assertTrue(
            src.contains("MessageDigest.isEqual"),
            "webhook handler must use MessageDigest.isEqual for constant-time secret compare",
        )
        // Defensive pattern check: webhookSecret must never be compared with
        // plain `==` or the Kotlin `==` alias `equals(`. Searching the file as
        // a whole is coarse but effective — any of these tokens adjacent to
        // webhookSecret would be a regression.
        val forbiddenPatterns = listOf(
            "webhookSecret ==",
            "== webhookSecret",
            "webhookSecret.equals(",
            "webhookSecret.contentEquals(",
        )
        for (pattern in forbiddenPatterns) {
            assertTrue(
                !src.contains(pattern),
                "webhook handler must not use '$pattern' — use MessageDigest.isEqual",
            )
        }
    }

    @Test
    fun `webhook missing Authorization header returns 401`() = testApplication {
        val vpSessions = InMemoryVpSessionStore(5.minutes)
        seedVpSession(vpSessions)
        moduleForWebhook(vpSessions)

        val r = jsonClient().post("/login/realm/vp/webhook") {
            contentType(ContentType.Application.Json)
            setBody(webhookBody())
        }
        assertEquals(HttpStatusCode.Unauthorized, r.status)
        val stored = assertNotNull(vpSessions.get(mockSessionId))
        assertEquals(VpSessionStatus.PENDING, stored.status)
    }

    @Test
    fun `webhook with malformed Authorization header returns 401`() = testApplication {
        val vpSessions = InMemoryVpSessionStore(5.minutes)
        seedVpSession(vpSessions)
        moduleForWebhook(vpSessions)

        // Not a Bearer scheme.
        val r1 = jsonClient().post("/login/realm/vp/webhook") {
            header(HttpHeaders.Authorization, "Basic dXNlcjpwYXNz")
            contentType(ContentType.Application.Json)
            setBody(webhookBody())
        }
        assertEquals(HttpStatusCode.Unauthorized, r1.status)

        // Bearer with empty token.
        val r2 = jsonClient().post("/login/realm/vp/webhook") {
            header(HttpHeaders.Authorization, "Bearer ")
            contentType(ContentType.Application.Json)
            setBody(webhookBody())
        }
        assertEquals(HttpStatusCode.Unauthorized, r2.status)
    }

    @Test
    fun `webhook with malformed JSON body returns 400`() = testApplication {
        val vpSessions = InMemoryVpSessionStore(5.minutes)
        seedVpSession(vpSessions)
        moduleForWebhook(vpSessions)

        val r = jsonClient().post("/login/realm/vp/webhook") {
            header(HttpHeaders.Authorization, "Bearer $testWebhookSecret")
            contentType(ContentType.Application.Json)
            setBody("{not-valid-json")
        }
        assertEquals(HttpStatusCode.BadRequest, r.status)
    }

    @Test
    fun `webhook missing session returns 400`() = testApplication {
        val vpSessions = InMemoryVpSessionStore(5.minutes)
        seedVpSession(vpSessions)
        moduleForWebhook(vpSessions)

        val body = buildJsonObject {
            put("target", mockSessionId)
            put("event", "policy_results_available")
            // no `session`
        }.toString()

        val r = jsonClient().post("/login/realm/vp/webhook") {
            header(HttpHeaders.Authorization, "Bearer $testWebhookSecret")
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        assertEquals(HttpStatusCode.BadRequest, r.status)
    }

    @Test
    fun `webhook missing session id returns 400`() = testApplication {
        val vpSessions = InMemoryVpSessionStore(5.minutes)
        seedVpSession(vpSessions)
        moduleForWebhook(vpSessions)

        // session present but without `id`.
        val body = buildJsonObject {
            put("target", mockSessionId)
            put("event", "policy_results_available")
            put(
                "session",
                buildJsonObject {
                    put("status", "SUCCESSFUL")
                },
            )
        }.toString()

        val r = jsonClient().post("/login/realm/vp/webhook") {
            header(HttpHeaders.Authorization, "Bearer $testWebhookSecret")
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        assertEquals(HttpStatusCode.BadRequest, r.status)
    }

    @Test
    fun `webhook captures presentedCredentials and presentedPresentations into VpSession`() =
        testApplication {
            val vpSessions = InMemoryVpSessionStore(5.minutes)
            seedVpSession(vpSessions)
            moduleForWebhook(vpSessions)

            val r = jsonClient().post("/login/realm/vp/webhook") {
                header(HttpHeaders.Authorization, "Bearer $testWebhookSecret")
                contentType(ContentType.Application.Json)
                setBody(webhookBody())
            }
            assertEquals(HttpStatusCode.OK, r.status)

            val stored = assertNotNull(vpSessions.get(mockSessionId))
            val captured = assertNotNull(stored.capturedCredential)

            // presentedCredentials[my_credential] is a JSON array (Map<String, List<DigitalCredential>>).
            val credList = captured.presentedCredentials["my_credential"]
            assertNotNull(credList, "presentedCredentials array for my_credential must round-trip")

            // presentedPresentations[my_credential] is a JSON object (Map<String, VerifiablePresentation>).
            val vpElement = captured.presentedPresentations["my_credential"]
            assertNotNull(vpElement, "presentedPresentations object for my_credential must round-trip")
            assertEquals(
                "VerifiablePresentation",
                vpElement.jsonObject["type"]?.jsonPrimitive?.content,
            )
        }

    @Test
    fun `webhook does not mutate VpSession for wrong secret even when session exists`() =
        testApplication {
            val vpSessions = InMemoryVpSessionStore(5.minutes)
            // Seed with an EXISTING captured credential so we can detect
            // accidental overwrite.
            seedVpSession(
                vpSessions,
                status = VpSessionStatus.PENDING,
                captured = null,
            )
            moduleForWebhook(vpSessions)

            val r = jsonClient().post("/login/realm/vp/webhook") {
                header(HttpHeaders.Authorization, "Bearer attacker-guess")
                contentType(ContentType.Application.Json)
                setBody(webhookBody())
            }

            assertEquals(HttpStatusCode.Unauthorized, r.status)
            val stored = assertNotNull(vpSessions.get(mockSessionId))
            assertEquals(VpSessionStatus.PENDING, stored.status)
            assertNull(stored.capturedCredential)
        }

    @Test
    fun `webhook for SUCCESSFUL status transitions VpSession status to SUCCESSFUL`() =
        testApplication {
            val vpSessions = InMemoryVpSessionStore(5.minutes)
            seedVpSession(vpSessions, status = VpSessionStatus.PENDING)
            moduleForWebhook(vpSessions)

            val r = jsonClient().post("/login/realm/vp/webhook") {
                header(HttpHeaders.Authorization, "Bearer $testWebhookSecret")
                contentType(ContentType.Application.Json)
                setBody(webhookBody(status = "SUCCESSFUL"))
            }

            assertEquals(HttpStatusCode.OK, r.status)
            val stored = assertNotNull(vpSessions.get(mockSessionId))
            assertEquals(VpSessionStatus.SUCCESSFUL, stored.status)
        }

    @Test
    fun `webhook handler source does not log credential body or secret`() {
        // Code-inspection test: the webhook body and Bearer secret are
        // sensitive. We disallow any invocation of a logger that might land
        // them in log output. Since auth-op uses no loggers today, this also
        // guards against a future refactor that adds one.
        val src = Files.readString(
            Path.of(
                "src/main/kotlin/id/walt/authop/endpoints/VpFlowRoutes.kt",
            ),
        )
        // Identify the vpWebhookRoutes region by bracketing markers.
        val start = src.indexOf("fun Route.vpWebhookRoutes(")
        assertTrue(start >= 0, "vpWebhookRoutes must exist")
        val end = src.indexOf("// ---- helpers", start)
        assertTrue(end > start, "webhook region must close before helpers comment")
        val region = src.substring(start, end)

        // Assert absence of logging calls and of any `println(suppliedSecret)`
        // / `println(rawBody)` / `println(body)` leaks. These are blunt
        // patterns but hit the obvious mistakes.
        val forbidden = listOf(
            "println(",
            "logger.",
            "log.info",
            "log.debug",
            "log.warn",
            "log.error",
            "log.trace",
            "System.out",
            "System.err",
        )
        for (pattern in forbidden) {
            assertTrue(
                !region.contains(pattern),
                "webhook handler must not contain '$pattern' — leaks sensitive payload",
            )
        }
    }

    // ==========================================================================
    // Task 19 — /login/realm/{id}/complete
    // ==========================================================================
    //
    // The terminal step of the OID4VP flow. Reads `capturedCredential` from the
    // VpSession (populated by the webhook in Task 18), runs [ClaimMapper] and
    // [SubDerivation], mints an OP-level [Session], hydrates the AuthRequest
    // with the final subject + claims, clears the captured credential, and
    // 302s to `/consent`. Every failure mode has a dedicated test below — the
    // combination matters because /complete is the first point where captured
    // credential data is consumed.

    /**
     * VP realm with a concrete subStrategy + claim mapping suitable for the
     * /complete tests. CLAIM_HASH gives us a deterministic sub with no need
     * for a credentialSubject.id, and the two claim mappings exercise both
     * top-level and nested ($.credentialSubject.*) JSONPath.
     */
    private fun vpRealmWithClaimHash(
        id: String = "vp",
        dcqlPath: String,
    ): RealmConfig = RealmConfig(
        id = id,
        name = "VP Realm",
        method = RealmMethod.OID4VP,
        oidc = null,
        oid4vp = Oid4vpRealmConfig(
            verifierBaseUrl = verifierBase,
            dcqlQueryFile = dcqlPath,
            webhookCallbackPath = "/vp/webhook",
            rpId = null,
        ),
        subStrategy = SubStrategy.CLAIM_HASH,
        claimMapping = mapOf(
            "email" to "$.email",
            "given_name" to "$.given_name",
        ),
        subSourceClaims = listOf("email"),
    )

    /**
     * A credential body whose shape mirrors verifier-api2's
     * `DigitalCredential.credentialData`. The /complete handler's
     * `firstCredentialData` helper picks the first entry's first credential's
     * `credentialData` field — we exercise that exact path.
     */
    private fun presentedCredentialsJson(
        email: String = "alice@example.com",
        givenName: String = "Alice",
    ): JsonObject = buildJsonObject {
        // presentedCredentials is Map<String, List<DigitalCredential>> ⇒
        // { "my_cred": [ {credentialData: {...}, ...} ] }.
        putJsonArray("my_cred") {
            add(
                buildJsonObject {
                    put("format", "jwt_vc_json")
                    // credentialData IS the VC body — ClaimMapper / SubDerivation
                    // operate on this.
                    putJsonObject("credentialData") {
                        put("email", email)
                        put("given_name", givenName)
                    }
                },
            )
        }
    }

    /** Seed a VpSession in [vpSessions] with a captured credential. */
    private fun seedVpSessionForComplete(
        vpSessions: InMemoryVpSessionStore,
        sessionId: String = mockSessionId,
        sid: String = "sid-abc",
        authRequestId: String = "sid-abc",
        realmId: String = "vp",
        status: VpSessionStatus = VpSessionStatus.SUCCESSFUL,
        captured: CapturedCredential? = CapturedCredential(
            presentedCredentials = presentedCredentialsJson(),
            presentedPresentations = JsonObject(emptyMap()),
        ),
    ) {
        vpSessions.put(
            sessionId,
            VpSession(
                verifierSessionId = sessionId,
                realmId = realmId,
                authRequestId = authRequestId,
                sessionCookieId = sid,
                webhookSecret = "unused-for-complete",
                status = status,
                capturedCredential = captured,
            ),
        )
    }

    /** Decode the base64url payload segment of a compact JWS into a JsonObject. */
    private fun jwtPayload(jws: String): JsonObject {
        val segment = jws.split(".")[1]
        val decoded = Base64.getUrlDecoder().decode(segment).decodeToString()
        return Json.parseToJsonElement(decoded) as JsonObject
    }

    /** Assemble deps wired for /complete tests: sessionStore, codeStore, realm, stores. */
    private fun ApplicationTestBuilder.moduleForComplete(
        authRequestStore: InMemoryAuthRequestStore,
        vpSessions: InMemoryVpSessionStore,
        sessionStore: InMemorySessionStore = InMemorySessionStore(5.minutes),
        authCodeStore: InMemoryAuthCodeStore = InMemoryAuthCodeStore(60.seconds),
        realm: RealmConfig,
    ) {
        application {
            module(
                testDeps(
                    config = testConfig(issuer = ourIssuer),
                    authRequestStore = authRequestStore,
                    sessionStore = sessionStore,
                    authCodeStore = authCodeStore,
                    vpSessionStore = vpSessions,
                    realmRegistry = realmRegistry(realm),
                ),
            )
        }
    }

    // ---- Required tests (verbatim names) -------------------------------------

    @Test
    fun `complete with matching sid cookie, SUCCESSFUL status and captured credential mints auth code`(
        @org.junit.jupiter.api.io.TempDir tmp: Path,
    ) = testApplication {
        val authRequests = InMemoryAuthRequestStore(5.minutes).apply {
            put("sid-abc", authRequestFor())
        }
        val vpSessions = InMemoryVpSessionStore(5.minutes).apply {
            seedVpSessionForComplete(this)
        }
        val dcql = writeDcql(tmp)
        moduleForComplete(
            authRequestStore = authRequests,
            vpSessions = vpSessions,
            realm = vpRealmWithClaimHash(dcqlPath = dcql.toString()),
        )

        val r = noFollow().get("/login/realm/vp/complete?verifierSessionId=$mockSessionId") {
            header(HttpHeaders.Cookie, "sid=sid-abc")
        }

        // /complete always 302s to /consent on success (the actual auth code
        // mint happens on the subsequent /consent hop for non-trusted clients,
        // or in /consent's trusted-skip branch for trusted ones). The test
        // name from the plan is about the end-to-end effect; we assert the
        // immediate redirect plus the AuthRequest hydration that enables the
        // code to be minted next hop.
        assertEquals(HttpStatusCode.Found, r.status)
        assertEquals("/consent", r.headers[HttpHeaders.Location])

        // AuthRequest was hydrated with sub + claims (precondition for /consent
        // to mint the auth code).
        val updated = assertNotNull(authRequests.get("sid-abc"))
        assertNotNull(updated.subject, "subject must be set by /complete")
        assertTrue(updated.claims.isNotEmpty(), "claims must be populated by /complete")
    }

    @Test
    fun `complete rejects if sid cookie mismatches stored sessionCookieId`(
        @org.junit.jupiter.api.io.TempDir tmp: Path,
    ) = testApplication {
        val authRequests = InMemoryAuthRequestStore(5.minutes).apply {
            put("sid-abc", authRequestFor())
        }
        val vpSessions = InMemoryVpSessionStore(5.minutes).apply {
            // Session was opened by sid-abc's browser…
            seedVpSessionForComplete(this, sid = "sid-abc")
        }
        val dcql = writeDcql(tmp)
        moduleForComplete(
            authRequestStore = authRequests,
            vpSessions = vpSessions,
            realm = vpRealmWithClaimHash(dcqlPath = dcql.toString()),
        )

        // …but a different browser attempts to redeem.
        val r = noFollow().get("/login/realm/vp/complete?verifierSessionId=$mockSessionId") {
            header(HttpHeaders.Cookie, "sid=sid-attacker")
        }
        assertEquals(HttpStatusCode.Forbidden, r.status)

        // AuthRequest must NOT be hydrated; captured credential must NOT be cleared.
        val unchangedAuth = assertNotNull(authRequests.get("sid-abc"))
        assertNull(unchangedAuth.subject, "subject must remain null on cookie mismatch")
        val unchangedVp = assertNotNull(vpSessions.get(mockSessionId))
        assertNotNull(
            unchangedVp.capturedCredential,
            "captured credential must NOT be cleared on cookie mismatch",
        )
    }

    @Test
    fun `complete rejects if status != SUCCESSFUL`(
        @org.junit.jupiter.api.io.TempDir tmp: Path,
    ) = testApplication {
        val authRequests = InMemoryAuthRequestStore(5.minutes).apply {
            put("sid-abc", authRequestFor())
        }
        val vpSessions = InMemoryVpSessionStore(5.minutes).apply {
            seedVpSessionForComplete(this, status = VpSessionStatus.UNSUCCESSFUL)
        }
        val dcql = writeDcql(tmp)
        moduleForComplete(
            authRequestStore = authRequests,
            vpSessions = vpSessions,
            realm = vpRealmWithClaimHash(dcqlPath = dcql.toString()),
        )

        val r = noFollow().get("/login/realm/vp/complete?verifierSessionId=$mockSessionId") {
            header(HttpHeaders.Cookie, "sid=sid-abc")
        }

        // Per /complete behaviour spec step 6: non-SUCCESSFUL → access_denied
        // redirected back to the RP.
        assertEquals(HttpStatusCode.Found, r.status)
        val loc = Url(assertNotNull(r.headers[HttpHeaders.Location]))
        assertEquals("rp", loc.host)
        assertEquals("/cb", loc.encodedPath)
        assertEquals("access_denied", loc.parameters["error"])
        val desc = assertNotNull(loc.parameters["error_description"])
        assertTrue(
            desc.contains("presentation did not satisfy requirements"),
            "description must call out the policy failure: $desc",
        )

        // AuthRequest must not be hydrated.
        val unchangedAuth = assertNotNull(authRequests.get("sid-abc"))
        assertNull(unchangedAuth.subject)
    }

    @Test
    fun `complete rejects if capturedCredential is null`(
        @org.junit.jupiter.api.io.TempDir tmp: Path,
    ) = testApplication {
        val authRequests = InMemoryAuthRequestStore(5.minutes).apply {
            put("sid-abc", authRequestFor())
        }
        val vpSessions = InMemoryVpSessionStore(5.minutes).apply {
            // SUCCESSFUL but with no captured credential — a webhook-capture
            // invariant violation. /complete must 500, not silently proceed.
            seedVpSessionForComplete(this, captured = null)
        }
        val dcql = writeDcql(tmp)
        moduleForComplete(
            authRequestStore = authRequests,
            vpSessions = vpSessions,
            realm = vpRealmWithClaimHash(dcqlPath = dcql.toString()),
        )

        val r = noFollow().get("/login/realm/vp/complete?verifierSessionId=$mockSessionId") {
            header(HttpHeaders.Cookie, "sid=sid-abc")
        }
        assertEquals(HttpStatusCode.InternalServerError, r.status)

        // AuthRequest must not be hydrated.
        val unchangedAuth = assertNotNull(authRequests.get("sid-abc"))
        assertNull(unchangedAuth.subject)
    }

    @Test
    fun `capturedCredential cleared after successful consumption`(
        @org.junit.jupiter.api.io.TempDir tmp: Path,
    ) = testApplication {
        val authRequests = InMemoryAuthRequestStore(5.minutes).apply {
            put("sid-abc", authRequestFor())
        }
        val vpSessions = InMemoryVpSessionStore(5.minutes).apply {
            seedVpSessionForComplete(this)
        }
        val dcql = writeDcql(tmp)
        moduleForComplete(
            authRequestStore = authRequests,
            vpSessions = vpSessions,
            realm = vpRealmWithClaimHash(dcqlPath = dcql.toString()),
        )

        // Precondition: capturedCredential is populated.
        val before = assertNotNull(vpSessions.get(mockSessionId))
        assertNotNull(before.capturedCredential)

        val r = noFollow().get("/login/realm/vp/complete?verifierSessionId=$mockSessionId") {
            header(HttpHeaders.Cookie, "sid=sid-abc")
        }
        assertEquals(HttpStatusCode.Found, r.status)

        // Post-consumption: capturedCredential is null on the still-present
        // VpSession (status / identity preserved — but the sensitive payload
        // is gone).
        val after = assertNotNull(vpSessions.get(mockSessionId))
        assertNull(
            after.capturedCredential,
            "capturedCredential must be cleared after /complete consumes it",
        )
    }

    @Test
    fun `complete projects cnf_jkt when presented credential carries cnf jwk`(
        @org.junit.jupiter.api.io.TempDir tmp: Path,
    ) = testApplication {
        val authRequests = InMemoryAuthRequestStore(5.minutes).apply {
            put("sid-abc", authRequestFor())
        }
        // Hand-crafted presentedCredentials with a cnf.jwk (RFC 7638 RSA
        // example vector) — exercises the VpFlowRoutes thumbprint path
        // end-to-end through ClaimMapper + finalClaims construction.
        val credsWithCnf = buildJsonObject {
            putJsonArray("my_cred") {
                add(
                    buildJsonObject {
                        put("format", "dc+sd-jwt")
                        putJsonObject("credentialData") {
                            put("email", "alice@example.com")
                            put("given_name", "Alice")
                            putJsonObject("cnf") {
                                putJsonObject("jwk") {
                                    put("kty", "RSA")
                                    put(
                                        "n",
                                        "0vx7agoebGcQSuuPiLJXZptN9nndrQmbXEps2aiAFbWhM78LhWx4cbbf" +
                                            "AAtVT86zwu1RK7aPFFxuhDR1L6tSoc_BJECPebWKRXjBZCiFV4n3oknjhMst" +
                                            "n64tZ_2W-5JsGY4Hc5n9yBXArwl93lqt7_RN5w6Cf0h4QyQ5v-65YGjQR0_F" +
                                            "DW2QvzqY368QQMicAtaSqzs8KJZgnYb9c7d0zgdAZHzu6qMQvRL5hajrn1n9" +
                                            "1CbOpbISD08qNLyrdkt-bFTWhAI4vMQFh6WeZu0fM4lFd2NcRwr3XPksINHa" +
                                            "Q-G_xBniIqbw0Ls1jF44-csFCur-kEgU8awapJzKnqDKgw",
                                    )
                                    put("e", "AQAB")
                                }
                            }
                        }
                    },
                )
            }
        }
        val vpSessions = InMemoryVpSessionStore(5.minutes).apply {
            seedVpSessionForComplete(
                this,
                captured = CapturedCredential(
                    presentedCredentials = credsWithCnf,
                    presentedPresentations = JsonObject(emptyMap()),
                ),
            )
        }
        val dcql = writeDcql(tmp)
        moduleForComplete(
            authRequestStore = authRequests,
            vpSessions = vpSessions,
            realm = vpRealmWithClaimHash(dcqlPath = dcql.toString()),
        )

        val r = noFollow().get("/login/realm/vp/complete?verifierSessionId=$mockSessionId") {
            header(HttpHeaders.Cookie, "sid=sid-abc")
        }
        assertEquals(HttpStatusCode.Found, r.status)

        val updated = assertNotNull(authRequests.get("sid-abc"))
        val cnfJkt = assertNotNull(
            updated.claims["cnf_jkt"]?.jsonPrimitive?.content,
            "cnf_jkt must be present in the hydrated AuthRequest claims",
        )
        // Expected value pinned from RFC 7638 §3.1 final paragraph.
        assertEquals("NzbLsXh8uDCcd-6MNwXF4W_7noWXFZAfHkxZsRGC9Xs", cnfJkt)
    }

    @Test
    fun `complete omits cnf_jkt when credential has no cnf`(
        @org.junit.jupiter.api.io.TempDir tmp: Path,
    ) = testApplication {
        val authRequests = InMemoryAuthRequestStore(5.minutes).apply {
            put("sid-abc", authRequestFor())
        }
        val vpSessions = InMemoryVpSessionStore(5.minutes).apply {
            seedVpSessionForComplete(this) // default fixture has no cnf
        }
        val dcql = writeDcql(tmp)
        moduleForComplete(
            authRequestStore = authRequests,
            vpSessions = vpSessions,
            realm = vpRealmWithClaimHash(dcqlPath = dcql.toString()),
        )

        val r = noFollow().get("/login/realm/vp/complete?verifierSessionId=$mockSessionId") {
            header(HttpHeaders.Cookie, "sid=sid-abc")
        }
        assertEquals(HttpStatusCode.Found, r.status)

        // Unbindable session: no cnf_jkt claim in hydrated AuthRequest.
        val updated = assertNotNull(authRequests.get("sid-abc"))
        assertNull(
            updated.claims["cnf_jkt"],
            "cnf_jkt must not be emitted when the presentation has no cnf.jwk",
        )
    }

    @Test
    fun `id token has acr=urn walt vp and amr=swk`(
        @org.junit.jupiter.api.io.TempDir tmp: Path,
    ) = testApplication {
        // Full drive from /complete through /consent (trusted-skip) and
        // /token so we can decode the minted id_token and assert on its
        // acr / amr claims.
        // PKCE challenge matches TEST_VERIFIER — TokenRoutes enforces this.
        val authRequests = InMemoryAuthRequestStore(5.minutes).apply {
            put(
                "sid-abc",
                authRequestFor().copy(codeChallenge = TokenRoutesTest.TEST_CHALLENGE),
            )
        }
        val vpSessions = InMemoryVpSessionStore(5.minutes).apply {
            seedVpSessionForComplete(this)
        }
        val authCodeStore = InMemoryAuthCodeStore(60.seconds)
        val sessionStore = InMemorySessionStore(5.minutes)
        val trustedClient = testClient(trusted = true).copy(
            allowedRealms = listOf("vp"),
        )
        val dcql = writeDcql(tmp)
        application {
            module(
                testDeps(
                    config = testConfig(issuer = ourIssuer),
                    clientRegistry = ClientRegistry(mapOf("rp1" to trustedClient)),
                    authRequestStore = authRequests,
                    sessionStore = sessionStore,
                    authCodeStore = authCodeStore,
                    vpSessionStore = vpSessions,
                    realmRegistry = realmRegistry(vpRealmWithClaimHash(dcqlPath = dcql.toString())),
                ),
            )
        }
        val http = noFollow()

        // Hop 1: /complete → 302 /consent.
        val r1 = http.get("/login/realm/vp/complete?verifierSessionId=$mockSessionId") {
            header(HttpHeaders.Cookie, "sid=sid-abc")
        }
        assertEquals(HttpStatusCode.Found, r1.status)
        assertEquals("/consent", r1.headers[HttpHeaders.Location])

        // Hop 2: /consent (trusted-skip) → 302 RP with code.
        val r2 = http.get("/consent") { header(HttpHeaders.Cookie, "sid=sid-abc") }
        assertEquals(HttpStatusCode.Found, r2.status)
        val rpRedirect = Url(assertNotNull(r2.headers[HttpHeaders.Location]))
        val authCode = assertNotNull(rpRedirect.parameters["code"])

        // Hop 3: /token exchange.
        val basic = "Basic " + Base64.getEncoder().encodeToString("rp1:secret".toByteArray())
        val r3 = http.submitForm(
            url = "/token",
            formParameters = parameters {
                append("grant_type", "authorization_code")
                append("code", authCode)
                append("redirect_uri", "https://rp/cb")
                append("code_verifier", TokenRoutesTest.TEST_VERIFIER)
            },
        ) { header(HttpHeaders.Authorization, basic) }
        assertEquals(HttpStatusCode.OK, r3.status)
        val tokenBody = Json.parseToJsonElement(r3.bodyAsText()) as JsonObject
        val idToken = assertNotNull(tokenBody["id_token"]?.jsonPrimitive?.content)
        val payload = jwtPayload(idToken)

        // The acr / amr assertions — the whole point of this test.
        assertEquals(
            "urn:walt:vp",
            payload["acr"]?.jsonPrimitive?.content,
            "acr must be urn:walt:vp on VP realms",
        )
        val amr = payload["amr"]?.jsonArray?.map { it.jsonPrimitive.content }
        assertEquals(
            listOf("swk"),
            amr,
            "amr must be [\"swk\"] on VP realms",
        )
    }

    // ---- Additional tests ----------------------------------------------------

    @Test
    fun `complete missing verifierSessionId returns 400`(
        @org.junit.jupiter.api.io.TempDir tmp: Path,
    ) = testApplication {
        val authRequests = InMemoryAuthRequestStore(5.minutes).apply {
            put("sid-abc", authRequestFor())
        }
        val vpSessions = InMemoryVpSessionStore(5.minutes)
        val dcql = writeDcql(tmp)
        moduleForComplete(
            authRequestStore = authRequests,
            vpSessions = vpSessions,
            realm = vpRealmWithClaimHash(dcqlPath = dcql.toString()),
        )

        val r = noFollow().get("/login/realm/vp/complete") {
            header(HttpHeaders.Cookie, "sid=sid-abc")
        }
        assertEquals(HttpStatusCode.BadRequest, r.status)
    }

    @Test
    fun `complete missing sid cookie returns 400`(
        @org.junit.jupiter.api.io.TempDir tmp: Path,
    ) = testApplication {
        val vpSessions = InMemoryVpSessionStore(5.minutes).apply {
            seedVpSessionForComplete(this)
        }
        val dcql = writeDcql(tmp)
        moduleForComplete(
            authRequestStore = InMemoryAuthRequestStore(5.minutes),
            vpSessions = vpSessions,
            realm = vpRealmWithClaimHash(dcqlPath = dcql.toString()),
        )

        val r = noFollow().get("/login/realm/vp/complete?verifierSessionId=$mockSessionId")
        assertEquals(HttpStatusCode.BadRequest, r.status)
    }

    @Test
    fun `complete unknown verifierSessionId returns 400`(
        @org.junit.jupiter.api.io.TempDir tmp: Path,
    ) = testApplication {
        val vpSessions = InMemoryVpSessionStore(5.minutes)
        val dcql = writeDcql(tmp)
        moduleForComplete(
            authRequestStore = InMemoryAuthRequestStore(5.minutes),
            vpSessions = vpSessions,
            realm = vpRealmWithClaimHash(dcqlPath = dcql.toString()),
        )

        val r = noFollow().get("/login/realm/vp/complete?verifierSessionId=nonexistent") {
            header(HttpHeaders.Cookie, "sid=sid-abc")
        }
        assertEquals(HttpStatusCode.BadRequest, r.status)
    }

    @Test
    fun `complete realm mismatch returns 404`(
        @org.junit.jupiter.api.io.TempDir tmp: Path,
    ) = testApplication {
        // VpSession was opened for realm "vp", but the caller hits the URL
        // for a different realm ("other-vp"). Must 404.
        val authRequests = InMemoryAuthRequestStore(5.minutes).apply {
            put("sid-abc", authRequestFor())
        }
        val vpSessions = InMemoryVpSessionStore(5.minutes).apply {
            seedVpSessionForComplete(this, realmId = "vp")
        }
        val dcql = writeDcql(tmp)
        moduleForComplete(
            authRequestStore = authRequests,
            vpSessions = vpSessions,
            realm = vpRealmWithClaimHash(id = "other-vp", dcqlPath = dcql.toString()),
        )

        val r = noFollow().get("/login/realm/other-vp/complete?verifierSessionId=$mockSessionId") {
            header(HttpHeaders.Cookie, "sid=sid-abc")
        }
        assertEquals(HttpStatusCode.NotFound, r.status)
    }

    @Test
    fun `complete creates session with sub derived via claim_hash`(
        @org.junit.jupiter.api.io.TempDir tmp: Path,
    ) = testApplication {
        val authRequests = InMemoryAuthRequestStore(5.minutes).apply {
            put("sid-abc", authRequestFor())
        }
        val vpSessions = InMemoryVpSessionStore(5.minutes).apply {
            seedVpSessionForComplete(this)
        }
        val sessionStore = InMemorySessionStore(5.minutes)
        val dcql = writeDcql(tmp)
        moduleForComplete(
            authRequestStore = authRequests,
            vpSessions = vpSessions,
            sessionStore = sessionStore,
            realm = vpRealmWithClaimHash(dcqlPath = dcql.toString()),
        )

        val r = noFollow().get("/login/realm/vp/complete?verifierSessionId=$mockSessionId") {
            header(HttpHeaders.Cookie, "sid=sid-abc")
        }
        assertEquals(HttpStatusCode.Found, r.status)

        // Session was created keyed by sid.
        val session = assertNotNull(
            sessionStore.get("sid-abc"),
            "Session must be persisted keyed by sid",
        )
        assertEquals("vp", session.realmId)
        assertEquals("urn:walt:vp", session.acr, "acr must be urn:walt:vp")
        assertEquals(listOf("swk"), session.amr, "amr must be [swk]")
        assertNull(session.upstreamIdToken, "VP realms have no upstream id_token")

        // Sub is the CLAIM_HASH of (realmId || email). Re-compute the formula
        // so a regression in SubDerivation shows up here too.
        val md = java.security.MessageDigest.getInstance("SHA-256")
        val hashInput = listOf("vp", "alice@example.com").joinToString("\u0000")
        val expected = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(md.digest(hashInput.toByteArray(Charsets.UTF_8)))
        assertEquals(expected, session.subject, "sub must be CLAIM_HASH(realmId, email)")
    }

    @Test
    fun `complete updates AuthRequest with final claims`(
        @org.junit.jupiter.api.io.TempDir tmp: Path,
    ) = testApplication {
        val authRequests = InMemoryAuthRequestStore(5.minutes).apply {
            put("sid-abc", authRequestFor())
        }
        val vpSessions = InMemoryVpSessionStore(5.minutes).apply {
            seedVpSessionForComplete(this)
        }
        val dcql = writeDcql(tmp)
        moduleForComplete(
            authRequestStore = authRequests,
            vpSessions = vpSessions,
            realm = vpRealmWithClaimHash(dcqlPath = dcql.toString()),
        )

        val r = noFollow().get("/login/realm/vp/complete?verifierSessionId=$mockSessionId") {
            header(HttpHeaders.Cookie, "sid=sid-abc")
        }
        assertEquals(HttpStatusCode.Found, r.status)

        val updated = assertNotNull(authRequests.get("sid-abc"))
        // Mapped claims projected by the realm.
        assertEquals(
            "alice@example.com",
            updated.claims["email"]?.jsonPrimitive?.content,
            "email claim must be projected via ClaimMapper",
        )
        assertEquals(
            "Alice",
            updated.claims["given_name"]?.jsonPrimitive?.content,
            "given_name claim must be projected via ClaimMapper",
        )
        // Namespaced realm claim.
        assertEquals(
            "vp",
            updated.claims["$ourIssuer/realm"]?.jsonPrimitive?.content,
            "realm id must appear under the namespaced claim key",
        )
        // acr / amr stamps.
        assertEquals("urn:walt:vp", updated.claims["acr"]?.jsonPrimitive?.content)
        assertEquals(
            listOf("swk"),
            updated.claims["amr"]?.jsonArray?.map { it.jsonPrimitive.content },
        )
        // Subject was set too (precondition for /consent).
        assertNotNull(updated.subject)
    }

    @Test
    fun `complete returns 302 to consent`(
        @org.junit.jupiter.api.io.TempDir tmp: Path,
    ) = testApplication {
        val authRequests = InMemoryAuthRequestStore(5.minutes).apply {
            put("sid-abc", authRequestFor())
        }
        val vpSessions = InMemoryVpSessionStore(5.minutes).apply {
            seedVpSessionForComplete(this)
        }
        val dcql = writeDcql(tmp)
        moduleForComplete(
            authRequestStore = authRequests,
            vpSessions = vpSessions,
            realm = vpRealmWithClaimHash(dcqlPath = dcql.toString()),
        )

        val r = noFollow().get("/login/realm/vp/complete?verifierSessionId=$mockSessionId") {
            header(HttpHeaders.Cookie, "sid=sid-abc")
        }
        assertEquals(HttpStatusCode.Found, r.status)
        assertEquals("/consent", r.headers[HttpHeaders.Location])
    }

    @Test
    fun `complete with UNSUCCESSFUL status redirects to RP with access_denied`(
        @org.junit.jupiter.api.io.TempDir tmp: Path,
    ) = testApplication {
        val authRequests = InMemoryAuthRequestStore(5.minutes).apply {
            put("sid-abc", authRequestFor())
        }
        val vpSessions = InMemoryVpSessionStore(5.minutes).apply {
            seedVpSessionForComplete(this, status = VpSessionStatus.UNSUCCESSFUL, captured = null)
        }
        val dcql = writeDcql(tmp)
        moduleForComplete(
            authRequestStore = authRequests,
            vpSessions = vpSessions,
            realm = vpRealmWithClaimHash(dcqlPath = dcql.toString()),
        )

        val r = noFollow().get("/login/realm/vp/complete?verifierSessionId=$mockSessionId") {
            header(HttpHeaders.Cookie, "sid=sid-abc")
        }

        assertEquals(HttpStatusCode.Found, r.status)
        val loc = Url(assertNotNull(r.headers[HttpHeaders.Location]))
        assertEquals("rp", loc.host)
        assertEquals("access_denied", loc.parameters["error"])
        assertEquals("round-trip", loc.parameters["state"], "state must be echoed byte-exact")
    }

    // ==========================================================================
    // Phase 5 — End-to-end VP flow: webhook → status → complete → consent → token
    // ==========================================================================

    @Test
    fun `complete end-to-end - webhook fires, status SUCCESSFUL, complete, AuthRequest subject populated`(
        @org.junit.jupiter.api.io.TempDir tmp: Path,
    ) = testApplication {
        // Start with an already-primed AuthRequest and a PENDING VpSession.
        // The webhook will transition it to SUCCESSFUL, then /complete will
        // consume, then /consent (trusted-skip) → /token gives us an id_token.
        // PKCE challenge matches TEST_VERIFIER — TokenRoutes enforces this.
        val authRequests = InMemoryAuthRequestStore(5.minutes).apply {
            put(
                "sid-abc",
                authRequestFor().copy(codeChallenge = TokenRoutesTest.TEST_CHALLENGE),
            )
        }
        val vpSessions = InMemoryVpSessionStore(5.minutes).apply {
            // PENDING, no captured credential — simulates the state immediately
            // after /login/realm/vp ran and before the wallet posted.
            put(
                mockSessionId,
                VpSession(
                    verifierSessionId = mockSessionId,
                    realmId = "vp",
                    authRequestId = "sid-abc",
                    sessionCookieId = "sid-abc",
                    webhookSecret = testWebhookSecret,
                    status = VpSessionStatus.PENDING,
                    capturedCredential = null,
                ),
            )
        }
        val sessionStore = InMemorySessionStore(5.minutes)
        val authCodeStore = InMemoryAuthCodeStore(60.seconds)
        val trustedClient = testClient(trusted = true).copy(
            allowedRealms = listOf("vp"),
        )
        val dcql = writeDcql(tmp)
        application {
            module(
                testDeps(
                    config = testConfig(issuer = ourIssuer),
                    clientRegistry = ClientRegistry(mapOf("rp1" to trustedClient)),
                    authRequestStore = authRequests,
                    sessionStore = sessionStore,
                    authCodeStore = authCodeStore,
                    vpSessionStore = vpSessions,
                    realmRegistry = realmRegistry(vpRealmWithClaimHash(dcqlPath = dcql.toString())),
                ),
            )
        }
        val http = noFollow()

        // Hop 1: wallet completes → verifier-api2 POSTs webhook.
        // Build a webhook body whose `session.presentedCredentials` matches
        // [presentedCredentialsJson] so /complete can map the same claims
        // [seedVpSessionForComplete] would have installed directly.
        val webhookEnvelope = buildJsonObject {
            put("target", mockSessionId)
            put("event", "policy_results_available")
            put(
                "session",
                buildJsonObject {
                    put("id", mockSessionId)
                    put("status", "SUCCESSFUL")
                    put("presentedCredentials", presentedCredentialsJson())
                    put("presentedPresentations", JsonObject(emptyMap()))
                },
            )
        }.toString()

        val rWebhook = jsonClient().post("/login/realm/vp/webhook") {
            header(HttpHeaders.Authorization, "Bearer $testWebhookSecret")
            contentType(ContentType.Application.Json)
            setBody(webhookEnvelope)
        }
        assertEquals(HttpStatusCode.OK, rWebhook.status)

        // Hop 2: the polling QR page sees SUCCESSFUL.
        val rStatus = jsonClient().get(
            "/login/realm/vp/status?verifierSessionId=$mockSessionId",
        ) { header(HttpHeaders.Cookie, "sid=sid-abc") }
        assertEquals(HttpStatusCode.OK, rStatus.status)
        val statusBody = Json.parseToJsonElement(rStatus.bodyAsText()) as JsonObject
        assertEquals("SUCCESSFUL", statusBody["status"]?.jsonPrimitive?.content)

        // Hop 3: /complete consumes the captured credential → 302 /consent.
        val rComplete = http.get(
            "/login/realm/vp/complete?verifierSessionId=$mockSessionId",
        ) { header(HttpHeaders.Cookie, "sid=sid-abc") }
        assertEquals(HttpStatusCode.Found, rComplete.status)
        assertEquals("/consent", rComplete.headers[HttpHeaders.Location])

        // AuthRequest.subject is populated (the plan's explicit assertion).
        val hydrated = assertNotNull(authRequests.get("sid-abc"))
        assertNotNull(hydrated.subject, "AuthRequest.subject must be populated after /complete")
        // Captured credential has been cleared — security invariant.
        val postComplete = assertNotNull(vpSessions.get(mockSessionId))
        assertNull(postComplete.capturedCredential)

        // Hop 4: /consent trusted-skip → 302 RP with code.
        val rConsent = http.get("/consent") { header(HttpHeaders.Cookie, "sid=sid-abc") }
        assertEquals(HttpStatusCode.Found, rConsent.status)
        val rpRedirect = Url(assertNotNull(rConsent.headers[HttpHeaders.Location]))
        assertEquals("rp", rpRedirect.host)
        val authCode = assertNotNull(rpRedirect.parameters["code"])

        // Hop 5: /token exchange → id_token.
        val basic = "Basic " + Base64.getEncoder().encodeToString("rp1:secret".toByteArray())
        val rToken = http.submitForm(
            url = "/token",
            formParameters = parameters {
                append("grant_type", "authorization_code")
                append("code", authCode)
                append("redirect_uri", "https://rp/cb")
                append("code_verifier", TokenRoutesTest.TEST_VERIFIER)
            },
        ) { header(HttpHeaders.Authorization, basic) }
        assertEquals(HttpStatusCode.OK, rToken.status)
        val tokenBody = Json.parseToJsonElement(rToken.bodyAsText()) as JsonObject
        val idToken = assertNotNull(tokenBody["id_token"]?.jsonPrimitive?.content)
        val payload = jwtPayload(idToken)

        // --- The E2E assertions called out in the plan --------------------
        assertEquals(
            "urn:walt:vp",
            payload["acr"]?.jsonPrimitive?.content,
            "acr must be urn:walt:vp end-to-end",
        )
        assertEquals(
            listOf("swk"),
            payload["amr"]?.jsonArray?.map { it.jsonPrimitive.content },
            "amr must be [swk] end-to-end",
        )
        assertEquals(
            "vp",
            payload["$ourIssuer/realm"]?.jsonPrimitive?.content,
            "realm id must appear under the namespaced claim",
        )
        // Mapped claims propagated all the way into the id_token.
        assertEquals(
            "alice@example.com",
            payload["email"]?.jsonPrimitive?.content,
            "mapped email must surface on id_token",
        )
        assertEquals(
            "Alice",
            payload["given_name"]?.jsonPrimitive?.content,
            "mapped given_name must surface on id_token",
        )
        // Subject: the CLAIM_HASH formula again — belt + braces against a
        // regression in SubDerivation or the claim-mapping ordering.
        val md = java.security.MessageDigest.getInstance("SHA-256")
        val hashInput = listOf("vp", "alice@example.com").joinToString("\u0000")
        val expectedSub = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(md.digest(hashInput.toByteArray(Charsets.UTF_8)))
        assertEquals(expectedSub, payload["sub"]?.jsonPrimitive?.content)
    }
}
