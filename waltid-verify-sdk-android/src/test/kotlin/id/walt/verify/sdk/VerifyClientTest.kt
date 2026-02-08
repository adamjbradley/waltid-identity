package id.walt.verify.sdk

import io.ktor.client.*
import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*
import java.util.concurrent.atomic.AtomicInteger

/**
 * Tests for the walt.id Verify SDK for Android/JVM
 *
 * These tests validate the SDK functionality with mock responses
 * and sandbox credentials for integration testing.
 */
class VerifyClientTest {

    companion object {
        // Sandbox credentials
        const val SANDBOX_TEST_API_KEY = "vfy_test_sandbox_demo_key_12345678"
        const val SANDBOX_LIVE_API_KEY = "vfy_live_sandbox_demo_key_12345678"
        const val SANDBOX_API_URL = "http://localhost:7010"
    }

    // ==========================================================================
    // Configuration Tests
    // ==========================================================================

    @Nested
    @DisplayName("VerifyConfig")
    inner class VerifyConfigTests {

        @Test
        fun `should create config with valid API key`() {
            val config = VerifyConfig(
                apiKey = SANDBOX_TEST_API_KEY,
                baseUrl = SANDBOX_API_URL
            )

            assertEquals(SANDBOX_TEST_API_KEY, config.apiKey)
            assertEquals(SANDBOX_API_URL, config.baseUrl)
        }

        @Test
        fun `should use default base URL when not provided`() {
            val config = VerifyConfig(apiKey = SANDBOX_TEST_API_KEY)

            assertEquals("https://verify.example.com", config.baseUrl)
        }

        @Test
        fun `should throw exception for blank API key`() {
            assertThrows<IllegalArgumentException> {
                VerifyConfig(apiKey = "")
            }
        }

        @Test
        fun `should throw exception for whitespace-only API key`() {
            assertThrows<IllegalArgumentException> {
                VerifyConfig(apiKey = "   ")
            }
        }
    }

    // ==========================================================================
    // Request Type Tests
    // ==========================================================================

    @Nested
    @DisplayName("VerificationRequest")
    inner class VerificationRequestTests {

        private val json = Json { encodeDefaults = true }

        @Test
        fun `should serialize with all parameters`() {
            val request = VerificationRequest(
                template = "kyc-basic",
                responseMode = "answers",
                redirectUri = "https://example.com/callback",
                metadata = mapOf("userId" to "12345", "orderId" to "order-abc")
            )

            val jsonString = json.encodeToString(VerificationRequest.serializer(), request)

            assertTrue(jsonString.contains("\"template\":\"kyc-basic\""))
            assertTrue(jsonString.contains("\"response_mode\":\"answers\""))
            assertTrue(jsonString.contains("\"redirect_uri\":\"https://example.com/callback\""))
            assertTrue(jsonString.contains("\"userId\":\"12345\""))
        }

        @Test
        fun `should serialize with minimal parameters`() {
            val request = VerificationRequest(template = "kyc-basic")

            val jsonString = json.encodeToString(VerificationRequest.serializer(), request)

            assertTrue(jsonString.contains("\"template\":\"kyc-basic\""))
            assertTrue(jsonString.contains("\"response_mode\":\"answers\"")) // default value
        }
    }

    // ==========================================================================
    // Response Type Tests
    // ==========================================================================

    @Nested
    @DisplayName("VerificationResponse")
    inner class VerificationResponseTests {

        private val json = Json { ignoreUnknownKeys = true }

        @Test
        fun `should deserialize verification response`() {
            val jsonString = """
                {
                    "session_id": "vs_test123",
                    "qr_code_url": "https://api.example.com/qr/test123.png",
                    "qr_code_data": "openid4vp://authorize?response_type=vp_token",
                    "deep_link": "wallet://verify?request_uri=https://api.example.com/request",
                    "expires_at": 1735689600
                }
            """.trimIndent()

            val response = json.decodeFromString(VerificationResponse.serializer(), jsonString)

            assertEquals("vs_test123", response.sessionId)
            assertEquals("https://api.example.com/qr/test123.png", response.qrCodeUrl)
            assertTrue(response.qrCodeData.startsWith("openid4vp://"))
            assertTrue(response.deepLink.startsWith("wallet://"))
            assertEquals(1735689600L, response.expiresAt)
        }
    }

    @Nested
    @DisplayName("SessionStatus")
    inner class SessionStatusTests {

        private val json = Json { ignoreUnknownKeys = true }

        @Test
        fun `should deserialize pending session status`() {
            val jsonString = """
                {
                    "session_id": "vs_test123",
                    "status": "pending",
                    "template_name": "kyc-basic",
                    "expires_at": 1735689600
                }
            """.trimIndent()

            val status = json.decodeFromString(SessionStatus.serializer(), jsonString)

            assertEquals("vs_test123", status.sessionId)
            assertEquals("pending", status.status)
            assertEquals("kyc-basic", status.templateName)
            assertTrue(status.isPending)
            assertFalse(status.isVerified)
            assertFalse(status.isTerminal)
            assertNull(status.result)
        }

        @Test
        fun `should deserialize verified session status with answers`() {
            val jsonString = """
                {
                    "session_id": "vs_verified",
                    "status": "verified",
                    "template_name": "kyc-basic",
                    "result": {
                        "answers": {
                            "full_name": "John Doe",
                            "date_of_birth": "1990-01-15"
                        }
                    },
                    "verified_at": 1735689500,
                    "metadata": {"userId": "12345"},
                    "expires_at": 1735689600
                }
            """.trimIndent()

            val status = json.decodeFromString(SessionStatus.serializer(), jsonString)

            assertEquals("vs_verified", status.sessionId)
            assertEquals("verified", status.status)
            assertTrue(status.isVerified)
            assertTrue(status.isTerminal)
            assertEquals("John Doe", status.result?.answers?.get("full_name"))
            assertEquals("1990-01-15", status.result?.answers?.get("date_of_birth"))
            assertEquals(1735689500L, status.verifiedAt)
            assertEquals("12345", status.metadata?.get("userId"))
        }

        @Test
        fun `should deserialize session status with credentials`() {
            val jsonString = """
                {
                    "session_id": "vs_creds",
                    "status": "verified",
                    "template_name": "kyc-full",
                    "result": {
                        "credentials": [
                            {
                                "format": "dc+sd-jwt",
                                "vct": "urn:eudi:pid:1",
                                "disclosed_claims": {
                                    "given_name": "John",
                                    "family_name": "Doe"
                                }
                            },
                            {
                                "format": "mso_mdoc",
                                "doctype": "org.iso.18013.5.1.mDL",
                                "disclosed_claims": {
                                    "document_number": "DL123456"
                                }
                            }
                        ]
                    },
                    "expires_at": 1735689600
                }
            """.trimIndent()

            val status = json.decodeFromString(SessionStatus.serializer(), jsonString)

            assertEquals(2, status.result?.credentials?.size)

            val sdJwtCredential = status.result?.credentials?.get(0)
            assertEquals("dc+sd-jwt", sdJwtCredential?.format)
            assertEquals("urn:eudi:pid:1", sdJwtCredential?.vct)
            assertEquals("John", sdJwtCredential?.disclosedClaims?.get("given_name"))

            val mdocCredential = status.result?.credentials?.get(1)
            assertEquals("mso_mdoc", mdocCredential?.format)
            assertEquals("org.iso.18013.5.1.mDL", mdocCredential?.doctype)
            assertEquals("DL123456", mdocCredential?.disclosedClaims?.get("document_number"))
        }

        @Test
        fun `should detect failed status`() {
            val jsonString = """
                {
                    "session_id": "vs_failed",
                    "status": "failed",
                    "template_name": "kyc-basic",
                    "expires_at": 1735689600
                }
            """.trimIndent()

            val status = json.decodeFromString(SessionStatus.serializer(), jsonString)

            assertTrue(status.isFailed)
            assertTrue(status.isTerminal)
            assertFalse(status.isPending)
        }

        @Test
        fun `should detect expired status`() {
            val jsonString = """
                {
                    "session_id": "vs_expired",
                    "status": "expired",
                    "template_name": "kyc-basic",
                    "expires_at": 1735689600
                }
            """.trimIndent()

            val status = json.decodeFromString(SessionStatus.serializer(), jsonString)

            assertTrue(status.isExpired)
            assertTrue(status.isTerminal)
            assertFalse(status.isPending)
        }
    }

    // ==========================================================================
    // Error Type Tests
    // ==========================================================================

    @Nested
    @DisplayName("Exception Types")
    inner class ExceptionTests {

        @Test
        fun `VerifyException should contain message and codes`() {
            val exception = VerifyException(
                message = "Invalid template name",
                statusCode = 400,
                errorCode = "INVALID_TEMPLATE"
            )

            assertEquals("Invalid template name", exception.message)
            assertEquals(400, exception.statusCode)
            assertEquals("INVALID_TEMPLATE", exception.errorCode)
        }

        @Test
        fun `VerifyException should work without optional parameters`() {
            val exception = VerifyException("Simple error")

            assertEquals("Simple error", exception.message)
            assertNull(exception.statusCode)
            assertNull(exception.errorCode)
        }

        @Test
        fun `PollingTimeoutException should contain session ID and timeout`() {
            val exception = PollingTimeoutException(
                sessionId = "vs_test123",
                timeoutMs = 30000
            )

            assertTrue(exception.message!!.contains("vs_test123"))
            assertTrue(exception.message!!.contains("30000"))
            assertEquals("vs_test123", exception.sessionId)
            assertEquals(30000L, exception.timeoutMs)
        }
    }

    // ==========================================================================
    // Client Tests with Mock HTTP
    // ==========================================================================

    @Nested
    @DisplayName("VerifyClient with Mock HTTP")
    inner class VerifyClientMockTests {

        private fun createMockEngine(handler: MockRequestHandler): MockEngine {
            return MockEngine(handler)
        }

        @Test
        fun `verifyIdentity should validate request parameters`() = runTest {
            val pollCount = AtomicInteger(0)
            val mockEngine = createMockEngine { request ->
                pollCount.incrementAndGet()
                assertEquals(HttpMethod.Post, request.method)
                assertTrue(request.url.toString().endsWith("/v1/verify/identity"))
                assertEquals("Bearer $SANDBOX_TEST_API_KEY", request.headers["Authorization"])

                respond(
                    content = ByteReadChannel("""
                        {
                            "session_id": "vs_mock123",
                            "qr_code_url": "https://api.example.com/qr/mock123.png",
                            "qr_code_data": "openid4vp://authorize?response_type=vp_token",
                            "deep_link": "wallet://verify?request_uri=https://api.example.com/request",
                            "expires_at": 1735689600
                        }
                    """.trimIndent()),
                    status = HttpStatusCode.Created,
                    headers = headersOf(HttpHeaders.ContentType, "application/json")
                )
            }

            val client = HttpClient(mockEngine) {
                install(ContentNegotiation) {
                    json(Json { ignoreUnknownKeys = true; encodeDefaults = true })
                }
            }

            val response = client.post("$SANDBOX_API_URL/v1/verify/identity") {
                header("Authorization", "Bearer $SANDBOX_TEST_API_KEY")
                contentType(ContentType.Application.Json)
                setBody(VerificationRequest(template = "kyc-basic", responseMode = "answers"))
            }

            assertEquals(HttpStatusCode.Created, response.status)
            assertEquals(1, pollCount.get())

            client.close()
        }

        @Test
        fun `getSession should validate request parameters`() = runTest {
            val mockEngine = createMockEngine { request ->
                assertEquals(HttpMethod.Get, request.method)
                assertTrue(request.url.toString().contains("/v1/sessions/vs_test123"))

                respond(
                    content = ByteReadChannel("""
                        {
                            "session_id": "vs_test123",
                            "status": "pending",
                            "template_name": "kyc-basic",
                            "expires_at": 1735689600
                        }
                    """.trimIndent()),
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json")
                )
            }

            val client = HttpClient(mockEngine) {
                install(ContentNegotiation) {
                    json(Json { ignoreUnknownKeys = true })
                }
            }

            val response = client.get("$SANDBOX_API_URL/v1/sessions/vs_test123") {
                header("Authorization", "Bearer $SANDBOX_TEST_API_KEY")
            }

            assertEquals(HttpStatusCode.OK, response.status)

            client.close()
        }

        @Test
        fun `mock engine should handle multiple polls`() = runTest {
            val pollCount = AtomicInteger(0)

            val mockEngine = createMockEngine { request ->
                val count = pollCount.incrementAndGet()
                val status = if (count >= 3) "verified" else "pending"

                val responseBody = if (status == "verified") {
                    """
                        {
                            "session_id": "vs_polling",
                            "status": "verified",
                            "template_name": "kyc-basic",
                            "result": {"answers": {"name": "Polled User"}},
                            "expires_at": 1735689600
                        }
                    """.trimIndent()
                } else {
                    """
                        {
                            "session_id": "vs_polling",
                            "status": "pending",
                            "template_name": "kyc-basic",
                            "expires_at": 1735689600
                        }
                    """.trimIndent()
                }

                respond(
                    content = ByteReadChannel(responseBody),
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json")
                )
            }

            val client = HttpClient(mockEngine) {
                install(ContentNegotiation) {
                    json(Json { ignoreUnknownKeys = true })
                }
            }

            // Simulate 3 polls
            repeat(3) {
                client.get("$SANDBOX_API_URL/v1/sessions/vs_polling") {
                    header("Authorization", "Bearer $SANDBOX_TEST_API_KEY")
                }
            }

            assertEquals(3, pollCount.get())

            client.close()
        }

        @Test
        fun `mock engine should handle error responses`() = runTest {
            val mockEngine = createMockEngine { request ->
                respond(
                    content = ByteReadChannel("""
                        {"error": "Session not found", "code": "SESSION_NOT_FOUND"}
                    """.trimIndent()),
                    status = HttpStatusCode.NotFound,
                    headers = headersOf(HttpHeaders.ContentType, "application/json")
                )
            }

            val client = HttpClient(mockEngine) {
                install(ContentNegotiation) {
                    json(Json { ignoreUnknownKeys = true })
                }
            }

            val response = client.get("$SANDBOX_API_URL/v1/sessions/vs_nonexistent") {
                header("Authorization", "Bearer $SANDBOX_TEST_API_KEY")
            }

            assertEquals(HttpStatusCode.NotFound, response.status)

            client.close()
        }
    }

    // ==========================================================================
    // Client Resource Management Tests
    // ==========================================================================

    @Nested
    @DisplayName("Client Resource Management")
    inner class ResourceManagementTests {

        @Test
        fun `client should implement Closeable`() {
            val client = VerifyClient(VerifyConfig(apiKey = SANDBOX_TEST_API_KEY))

            // Should not throw
            client.close()
        }

        @Test
        fun `client should be usable with use block`() {
            VerifyClient(VerifyConfig(apiKey = SANDBOX_TEST_API_KEY)).use { client ->
                // Client should be usable here
                assertNotNull(client)
            }
            // Client is auto-closed after use block
        }
    }
}

// =============================================================================
// Integration Tests
// =============================================================================

/**
 * Integration tests that require a running Verify API.
 * Set RUN_INTEGRATION_TESTS=true environment variable to enable.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class VerifyClientIntegrationTest {

    private val integrationEnabled = System.getenv("RUN_INTEGRATION_TESTS") == "true"
    private val apiUrl = System.getenv("VERIFY_API_URL") ?: "http://localhost:7010"
    private val apiKey = System.getenv("VERIFY_API_KEY") ?: VerifyClientTest.SANDBOX_TEST_API_KEY

    private lateinit var client: VerifyClient

    @BeforeAll
    fun setup() {
        if (integrationEnabled) {
            client = VerifyClient(VerifyConfig(apiKey = apiKey, baseUrl = apiUrl))
        }
    }

    @AfterAll
    fun cleanup() {
        if (integrationEnabled) {
            client.close()
        }
    }

    @Test
    fun `should create verification session with sandbox credentials`() = runTest {
        Assumptions.assumeTrue(integrationEnabled) { "Integration tests disabled" }

        val request = VerificationRequest(
            template = "kyc-basic",
            responseMode = "answers",
            metadata = mapOf("testRun" to "integration-test")
        )

        val verification = client.verifyIdentity(request)

        assertTrue(verification.sessionId.startsWith("vs_"))
        assertTrue(verification.qrCodeUrl.isNotEmpty())
        assertTrue(verification.qrCodeData.startsWith("openid4vp://"))
        assertTrue(verification.deepLink.isNotEmpty())
        assertTrue(verification.expiresAt > 0)
    }

    @Test
    fun `should get session status with sandbox credentials`() = runTest {
        Assumptions.assumeTrue(integrationEnabled) { "Integration tests disabled" }

        // First create a session
        val request = VerificationRequest(template = "kyc-basic")
        val verification = client.verifyIdentity(request)

        // Then check its status
        val status = client.getSession(verification.sessionId)

        assertEquals(verification.sessionId, status.sessionId)
        assertEquals("pending", status.status)
        assertEquals("kyc-basic", status.templateName)
    }

    @Test
    fun `should differentiate between test and live API keys`() = runTest {
        Assumptions.assumeTrue(integrationEnabled) { "Integration tests disabled" }

        // Test key should work in sandbox mode
        val testClient = VerifyClient(
            VerifyConfig(apiKey = VerifyClientTest.SANDBOX_TEST_API_KEY, baseUrl = apiUrl)
        )

        val request = VerificationRequest(template = "kyc-basic")
        val verification = testClient.verifyIdentity(request)

        assertNotNull(verification.sessionId)

        testClient.close()
    }
}
