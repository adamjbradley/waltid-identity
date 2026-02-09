package id.walt.verifyapi

import id.walt.verifyapi.service.Verifier2Client
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Unit tests for Verifier2Client session request building.
 *
 * These tests verify the conditional JSON body construction and URL building
 * for the two modes: global config fallback (rpId=null) vs registered RP (rpId set).
 */
class Verifier2ClientTest {

    private val sampleDcqlQuery = buildJsonObject {
        putJsonArray("credentials") {
            addJsonObject {
                put("id", "pid")
                put("format", "dc+sd-jwt")
                putJsonObject("meta") {
                    putJsonArray("vct_values") { add("urn:eudi:pid:1") }
                }
                putJsonArray("claims") {
                    addJsonObject { putJsonArray("path") { add("age_over_18") } }
                }
            }
        }
    }

    // ============================================================
    // URL Building Tests
    // ============================================================

    @Test
    fun `test buildSessionUrl without rpId returns base URL`() {
        val url = Verifier2Client.buildSessionUrl(null)
        assertTrue(url.endsWith("/verification-session/create"), "URL should end with /verification-session/create")
        assertFalse(url.contains("rpId"), "URL should not contain rpId parameter")
    }

    @Test
    fun `test buildSessionUrl with rpId appends query parameter`() {
        val rpId = "03b25ab0-84a6-4574-8f10-c0e18e7f93ed"
        val url = Verifier2Client.buildSessionUrl(rpId)
        assertTrue(url.contains("?rpId=$rpId"), "URL should contain ?rpId= query parameter")
        assertTrue(url.endsWith("?rpId=$rpId"), "rpId should be at the end of the URL")
    }

    @Test
    fun `test buildSessionUrl with rpId preserves base path`() {
        val url = Verifier2Client.buildSessionUrl("some-rp-id")
        assertTrue(url.contains("/verification-session/create"), "URL should contain the base path")
    }

    // ============================================================
    // Request Body Tests - Global Config Fallback (rpId = null)
    // ============================================================

    @Test
    fun `test request body without rpId includes clientId`() {
        val body = Verifier2Client.buildSessionRequestBody(sampleDcqlQuery, null)
        val coreFlow = body["core_flow"]!!.jsonObject

        assertTrue("clientId" in coreFlow, "core_flow should contain clientId when rpId is null")
        val clientId = coreFlow["clientId"]!!.jsonPrimitive.content
        assertTrue(clientId.startsWith("x509_san_dns:"), "clientId should have x509_san_dns prefix")
    }

    @Test
    fun `test request body without rpId includes signing key`() {
        val body = Verifier2Client.buildSessionRequestBody(sampleDcqlQuery, null)
        val coreFlow = body["core_flow"]!!.jsonObject

        assertTrue("key" in coreFlow, "core_flow should contain key when rpId is null")
        val key = coreFlow["key"]!!.jsonObject
        assertEquals("jwk", key["type"]!!.jsonPrimitive.content, "Key type should be jwk")
        assertTrue("jwk" in key, "Key should contain jwk object")

        val jwk = key["jwk"]!!.jsonObject
        assertEquals("EC", jwk["kty"]!!.jsonPrimitive.content, "Key type should be EC")
        assertEquals("P-256", jwk["crv"]!!.jsonPrimitive.content, "Curve should be P-256")
        assertTrue("d" in jwk, "JWK should include private key component 'd'")
    }

    @Test
    fun `test request body without rpId includes x5c`() {
        val body = Verifier2Client.buildSessionRequestBody(sampleDcqlQuery, null)
        val coreFlow = body["core_flow"]!!.jsonObject

        assertTrue("x5c" in coreFlow, "core_flow should contain x5c when rpId is null")
        val x5c = coreFlow["x5c"]!!.jsonArray
        assertTrue(x5c.isNotEmpty(), "x5c should contain at least one certificate")
        assertTrue(x5c[0].jsonPrimitive.content.startsWith("MIIB"), "x5c should contain a base64-encoded certificate")
    }

    // ============================================================
    // Request Body Tests - Registered RP (rpId set)
    // ============================================================

    @Test
    fun `test request body with rpId excludes clientId`() {
        val body = Verifier2Client.buildSessionRequestBody(sampleDcqlQuery, "rp-123")
        val coreFlow = body["core_flow"]!!.jsonObject

        assertFalse("clientId" in coreFlow, "core_flow should NOT contain clientId when rpId is set")
    }

    @Test
    fun `test request body with rpId excludes signing key`() {
        val body = Verifier2Client.buildSessionRequestBody(sampleDcqlQuery, "rp-123")
        val coreFlow = body["core_flow"]!!.jsonObject

        assertFalse("key" in coreFlow, "core_flow should NOT contain key when rpId is set")
    }

    @Test
    fun `test request body with rpId excludes x5c`() {
        val body = Verifier2Client.buildSessionRequestBody(sampleDcqlQuery, "rp-123")
        val coreFlow = body["core_flow"]!!.jsonObject

        assertFalse("x5c" in coreFlow, "core_flow should NOT contain x5c when rpId is set")
    }

    // ============================================================
    // Request Body Tests - Common Fields (both modes)
    // ============================================================

    @Test
    fun `test request body always includes flow_type`() {
        val bodyWithoutRp = Verifier2Client.buildSessionRequestBody(sampleDcqlQuery, null)
        val bodyWithRp = Verifier2Client.buildSessionRequestBody(sampleDcqlQuery, "rp-123")

        assertEquals("cross_device", bodyWithoutRp["flow_type"]!!.jsonPrimitive.content)
        assertEquals("cross_device", bodyWithRp["flow_type"]!!.jsonPrimitive.content)
    }

    @Test
    fun `test request body always includes signed_request true`() {
        val bodyWithoutRp = Verifier2Client.buildSessionRequestBody(sampleDcqlQuery, null)
        val bodyWithRp = Verifier2Client.buildSessionRequestBody(sampleDcqlQuery, "rp-123")

        assertTrue(bodyWithoutRp["core_flow"]!!.jsonObject["signed_request"]!!.jsonPrimitive.boolean)
        assertTrue(bodyWithRp["core_flow"]!!.jsonObject["signed_request"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun `test request body always includes dcql_query`() {
        val bodyWithoutRp = Verifier2Client.buildSessionRequestBody(sampleDcqlQuery, null)
        val bodyWithRp = Verifier2Client.buildSessionRequestBody(sampleDcqlQuery, "rp-123")

        val queryWithoutRp = bodyWithoutRp["core_flow"]!!.jsonObject["dcql_query"]!!.jsonObject
        val queryWithRp = bodyWithRp["core_flow"]!!.jsonObject["dcql_query"]!!.jsonObject

        assertNotNull(queryWithoutRp["credentials"])
        assertNotNull(queryWithRp["credentials"])
        assertEquals(sampleDcqlQuery.toString(), queryWithoutRp.toString())
        assertEquals(sampleDcqlQuery.toString(), queryWithRp.toString())
    }

    @Test
    fun `test request body has core_flow object`() {
        val body = Verifier2Client.buildSessionRequestBody(sampleDcqlQuery, null)
        assertTrue("core_flow" in body, "Body should contain core_flow")
        assertTrue(body["core_flow"] is JsonObject, "core_flow should be a JSON object")
    }

    // ============================================================
    // DTO Tests
    // ============================================================

    @Test
    fun `test VerificationSessionResponse serialization`() {
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        val response = Verifier2Client.VerificationSessionResponse(
            sessionId = "session-123",
            bootstrapAuthorizationRequestUrl = "openid4vp://authorize?request_uri=https://example.com/request/123",
            fullAuthorizationRequestUrl = "https://example.com/full/123"
        )

        val serialized = json.encodeToString(Verifier2Client.VerificationSessionResponse.serializer(), response)
        val deserialized = json.decodeFromString(Verifier2Client.VerificationSessionResponse.serializer(), serialized)

        assertEquals("session-123", deserialized.sessionId)
        assertTrue(deserialized.bootstrapAuthorizationRequestUrl.startsWith("openid4vp://"))
        assertEquals("https://example.com/full/123", deserialized.fullAuthorizationRequestUrl)
    }

    @Test
    fun `test VerificationSessionResponse with null fullAuthorizationRequestUrl`() {
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        val response = Verifier2Client.VerificationSessionResponse(
            sessionId = "session-456",
            bootstrapAuthorizationRequestUrl = "openid4vp://authorize?request_uri=https://example.com/request/456"
        )

        val serialized = json.encodeToString(Verifier2Client.VerificationSessionResponse.serializer(), response)
        val deserialized = json.decodeFromString(Verifier2Client.VerificationSessionResponse.serializer(), serialized)

        assertEquals("session-456", deserialized.sessionId)
        assertEquals(null, deserialized.fullAuthorizationRequestUrl)
    }

    @Test
    fun `test SessionInfoResponse serialization`() {
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        val response = Verifier2Client.SessionInfoResponse(
            id = "info-123",
            status = "SUCCESSFUL",
            attempted = true,
            presentedCredentials = buildJsonObject {
                putJsonArray("urn:eudi:pid:1") {
                    addJsonObject {
                        put("format", "dc+sd-jwt")
                    }
                }
            }
        )

        val serialized = json.encodeToString(Verifier2Client.SessionInfoResponse.serializer(), response)
        val deserialized = json.decodeFromString(Verifier2Client.SessionInfoResponse.serializer(), serialized)

        assertEquals("info-123", deserialized.id)
        assertEquals("SUCCESSFUL", deserialized.status)
        assertTrue(deserialized.attempted)
        assertNotNull(deserialized.presentedCredentials)
    }

    // ============================================================
    // Edge Cases
    // ============================================================

    @Test
    fun `test request body with empty DCQL query`() {
        val emptyQuery = buildJsonObject { }
        val body = Verifier2Client.buildSessionRequestBody(emptyQuery, "rp-123")
        val coreFlow = body["core_flow"]!!.jsonObject

        assertNotNull(coreFlow["dcql_query"])
        assertEquals("{}", coreFlow["dcql_query"].toString())
    }

    @Test
    fun `test request body mode difference is only clientId key x5c`() {
        val bodyWithRp = Verifier2Client.buildSessionRequestBody(sampleDcqlQuery, "rp-123")
        val bodyWithoutRp = Verifier2Client.buildSessionRequestBody(sampleDcqlQuery, null)

        val keysWithRp = bodyWithRp["core_flow"]!!.jsonObject.keys
        val keysWithoutRp = bodyWithoutRp["core_flow"]!!.jsonObject.keys

        // Without RP has extra keys: clientId, key, x5c
        val diff = keysWithoutRp - keysWithRp
        assertEquals(setOf("clientId", "key", "x5c"), diff,
            "The only difference should be clientId, key, and x5c")

        // With RP should be a subset
        assertTrue(keysWithRp.all { it in keysWithoutRp },
            "All keys in rpId mode should also exist in global mode")
    }
}
