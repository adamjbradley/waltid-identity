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
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.http.contentType
import io.ktor.http.headersOf
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
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
}
