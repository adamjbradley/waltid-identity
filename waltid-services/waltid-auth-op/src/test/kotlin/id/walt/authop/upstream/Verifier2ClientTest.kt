@file:OptIn(ExperimentalTime::class)

package id.walt.authop.upstream

import id.walt.authop.domain.VpSessionStatus
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.plugins.HttpTimeout
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.ExperimentalTime

/**
 * Tests the verifier-api2 upstream client with a `MockEngine`-backed HTTP
 * client. We don't mint anything — verifier-api2 speaks plain JSON and the
 * shapes are stable (see VerificationSessionSetupData.kt / KtorSessionNotifications.kt
 * references in [Verifier2Client]'s KDoc).
 */
class Verifier2ClientTest {

    private val verifierBaseUrl = "https://verifier2.example"
    private val webhookUrl = "https://auth.example/vp/webhook"
    private val webhookSecret = "wh-secret-xyz"
    private val dcqlQuery: JsonObject = buildJsonObject {
        put("credentials", buildJsonArray {
            add(buildJsonObject {
                put("id", "my_credential")
                put("format", "jwt_vc_json")
            })
        })
    }

    // --- happy path: createSession wire format ---------------------------------

    @Test
    fun `createSession posts to correct path with dcql and webhook registration`() = runTest {
        var capturedMethod: HttpMethod? = null
        var capturedPath: String? = null
        var capturedQuery: String? = null
        var capturedContentType: ContentType? = null
        var capturedBody: String? = null

        val engine = MockEngine { request ->
            capturedMethod = request.method
            capturedPath = request.url.encodedPath
            capturedQuery = request.url.encodedQuery.ifEmpty { null }
            // In Ktor client, Content-Type rides on the OutgoingContent body
            // (io.ktor.http.content.OutgoingContent#contentType), not the request
            // header bag — MockEngine's request.headers returns null for it.
            capturedContentType = request.body.contentType
            capturedBody = String(request.body.toByteArray())
            respond(
                content = successBody(sessionId = "sess-42"),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val client = Verifier2Client(httpClient = HttpClient(engine))

        val result = client.createSession(
            verifierBaseUrl = verifierBaseUrl,
            dcqlQuery = dcqlQuery,
            webhookUrl = webhookUrl,
            webhookSecret = webhookSecret,
        )

        // Return values ---------------------------------------------------------
        assertEquals("sess-42", result.sessionId)
        assertEquals("openid4vp://authz?full", result.fullAuthorizationRequestUrl)
        assertEquals("openid4vp://authz?qr", result.bootstrapAuthorizationRequestUrl)
        assertNull(result.creationTarget)

        // Wire format -----------------------------------------------------------
        assertEquals(HttpMethod.Post, capturedMethod)
        assertEquals("/verification-session/create", capturedPath)
        assertNull(capturedQuery, "no rpId → no query string")
        assertNotNull(capturedContentType)
        assertEquals(
            ContentType.Application.Json.contentType, capturedContentType!!.contentType,
            "expected application/json Content-Type, got $capturedContentType",
        )
        assertEquals(
            ContentType.Application.Json.contentSubtype, capturedContentType!!.contentSubtype,
        )

        val body = assertNotNull(capturedBody)
        val parsed = Json.parseToJsonElement(body).jsonObject

        // Polymorphic discriminator (VerificationSessionSetupData.kt:77).
        assertEquals("cross_device", parsed["flow_type"]?.jsonPrimitive?.content)

        // Cross-device nests the GeneralFlowConfig under SerialName "core_flow"
        // (VerificationSessionSetupData.kt:94-95). Not "core".
        val coreFlow = parsed["core_flow"]?.jsonObject ?: error("missing core_flow: $body")

        // DCQL field must be the snake-case "dcql_query" (VerificationSessionSetupData.kt:28-29).
        val forwardedDcql = coreFlow["dcql_query"]?.jsonObject ?: error("missing dcql_query: $body")
        assertEquals(dcqlQuery, forwardedDcql, "DCQL must be forwarded verbatim")

        // Webhook registration — notifications.webhook.{url, bearer_token}
        // (KtorSessionNotifications.kt:8, 12, 21; bearerToken's SerialName is
        // "bearer_token").
        val notifications = coreFlow["notifications"]?.jsonObject
            ?: error("missing notifications: $body")
        val webhook = notifications["webhook"]?.jsonObject
            ?: error("missing notifications.webhook: $body")
        assertEquals(webhookUrl, webhook["url"]?.jsonPrimitive?.content)
        assertEquals(webhookSecret, webhook["bearer_token"]?.jsonPrimitive?.content)
    }

    // --- rpId query param ------------------------------------------------------

    @Test
    fun `createSession with rpId includes it as a query param`() = runTest {
        var capturedQuery: String? = null
        val engine = MockEngine { request ->
            capturedQuery = request.url.encodedQuery
            respond(
                content = successBody(sessionId = "s-1"),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val client = Verifier2Client(httpClient = HttpClient(engine))

        client.createSession(
            verifierBaseUrl = verifierBaseUrl,
            dcqlQuery = dcqlQuery,
            webhookUrl = webhookUrl,
            webhookSecret = webhookSecret,
            rpId = "rp-123",
        )

        assertEquals("rpId=rp-123", capturedQuery)
    }

    // --- 4xx surfacing ---------------------------------------------------------

    @Test
    fun `createSession surfaces verifier 4xx as domain error`() = runTest {
        val engine = MockEngine { _ ->
            respond(
                content = """{"error":"invalid_dcql","detail":"bad query"}""",
                status = HttpStatusCode.BadRequest,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val client = Verifier2Client(httpClient = HttpClient(engine))

        val ex = assertFailsWith<Verifier2ClientException> {
            client.createSession(
                verifierBaseUrl = verifierBaseUrl,
                dcqlQuery = dcqlQuery,
                webhookUrl = webhookUrl,
                webhookSecret = webhookSecret,
            )
        }
        assertEquals("verifier_session_create_failed", ex.code)
        assertTrue(
            ex.message?.contains("400") == true,
            "exception should surface HTTP code for operators, got: ${ex.message}",
        )
        assertTrue(
            ex.message?.contains("invalid_dcql") == true,
            "exception should surface upstream error body (truncated) for operators",
        )
    }

    // --- timeout hardening -----------------------------------------------------

    /**
     * Uses `runBlocking` (not `runTest`) because [HttpTimeout] runs on a real
     * wall clock — the virtual test dispatcher would skip the delay without
     * firing the timeout. Mirrors OidcClientTest's timeout test.
     */
    @Test
    fun `createSession surfaces timeout as upstream_timeout`() = runBlocking {
        val stallingEngine = MockEngine { _ ->
            delay(2_000)
            respond("{}", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val tightClient = HttpClient(stallingEngine) {
            install(HttpTimeout) {
                requestTimeoutMillis = 50
                connectTimeoutMillis = 50
                socketTimeoutMillis = 50
            }
        }
        val client = Verifier2Client(httpClient = tightClient)

        val ex = assertFailsWith<Verifier2ClientException> {
            client.createSession(
                verifierBaseUrl = verifierBaseUrl,
                dcqlQuery = dcqlQuery,
                webhookUrl = webhookUrl,
                webhookSecret = webhookSecret,
            )
        }
        assertEquals("upstream_timeout", ex.code)
        assertTrue(
            ex.message?.contains("timed out") == true,
            "exception message should describe the timeout for operator debugging",
        )
    }

    // --- malformed response ----------------------------------------------------

    @Test
    fun `createSession response missing sessionId fails cleanly`() = runTest {
        val engine = MockEngine { _ ->
            respond(
                // No "sessionId" key — verifier-api2 contract violation.
                content = """{"fullAuthorizationRequestUrl":"openid4vp://foo"}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val client = Verifier2Client(httpClient = HttpClient(engine))

        val ex = assertFailsWith<Verifier2ClientException> {
            client.createSession(
                verifierBaseUrl = verifierBaseUrl,
                dcqlQuery = dcqlQuery,
                webhookUrl = webhookUrl,
                webhookSecret = webhookSecret,
            )
        }
        assertEquals("verifier_session_create_failed", ex.code)
        assertTrue(
            ex.message?.contains("sessionId") == true,
            "exception should name the missing field so operators can debug verifier-api2 regressions",
        )
    }

    // --- getSessionInfo --------------------------------------------------------

    @Test
    fun `getSessionInfo returns status from verifier info endpoint`() = runTest {
        var capturedPath: String? = null
        val engine = MockEngine { request ->
            capturedPath = request.url.encodedPath
            respond(
                content = """{"id":"sess-7","status":"IN_USE","attempted":true}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val client = Verifier2Client(httpClient = HttpClient(engine))

        val info = client.getSessionInfo(verifierBaseUrl, "sess-7")

        assertEquals("/verification-session/sess-7/info", capturedPath)
        assertEquals("sess-7", info.sessionId)
        // IN_USE maps to PENDING — recovery-path only cares about terminal states.
        assertEquals(VpSessionStatus.PENDING, info.status)
    }

    @Test
    fun `getSessionInfo handles missing session as 404`() = runTest {
        val engine = MockEngine { _ ->
            respond("not found", HttpStatusCode.NotFound)
        }
        val client = Verifier2Client(httpClient = HttpClient(engine))

        val ex = assertFailsWith<Verifier2ClientException> {
            client.getSessionInfo(verifierBaseUrl, "does-not-exist")
        }
        assertEquals("verifier_session_not_found", ex.code)
    }

    // --- helpers ---------------------------------------------------------------

    private fun successBody(
        sessionId: String,
        full: String = "openid4vp://authz?full",
        bootstrap: String? = "openid4vp://authz?qr",
        creationTarget: String? = null,
    ): String = buildJsonObject {
        put("sessionId", sessionId)
        if (bootstrap != null) put("bootstrapAuthorizationRequestUrl", bootstrap)
        put("fullAuthorizationRequestUrl", full)
        if (creationTarget != null) put("creationTarget", creationTarget)
    }.toString()
}
