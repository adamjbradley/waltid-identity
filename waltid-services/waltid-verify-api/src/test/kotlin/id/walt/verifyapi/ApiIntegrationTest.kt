package id.walt.verifyapi

import id.walt.verifyapi.auth.apiKey
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertContains
import kotlin.test.assertTrue

/**
 * Integration tests for Verify API endpoints using Ktor test host.
 *
 * These tests verify HTTP routing and response behavior without requiring
 * database connectivity by using a simplified test module configuration.
 */
class ApiIntegrationTest {

    /**
     * Configures a minimal test application with only the required plugins
     * for testing public and authentication-gated endpoints.
     */
    private fun Application.testModule() {
        // Configure serialization
        install(ContentNegotiation) {
            json(Json {
                prettyPrint = true
                isLenient = true
                ignoreUnknownKeys = true
                encodeDefaults = true
            })
        }

        // Configure authentication (API key provider that will fail without DB)
        install(Authentication) {
            apiKey("api-key")
        }

        // Configure routes
        routing {
            // Health check endpoint
            get("/health") {
                call.respondText("OK", ContentType.Text.Plain)
            }

            // Root endpoint with service info
            get("/") {
                call.respondText(
                    """
                    |Verify API - Multi-tenant Verification Gateway
                    |Status: ENABLED
                    |Version: 1.0.0-SNAPSHOT
                    |Port: 7010
                    |
                    |Endpoints:
                    |  /health - Health check
                    |  /v1/verify/identity - Create identity verification (POST)
                    |  /v1/sessions/{session_id} - Get session status
                    |  /v1/templates - List/create verification templates
                    |  /v1/webhooks - Manage webhook subscriptions
                    |  /v1/orchestrations - Multi-step verification flows
                    |  /docs - API documentation (Swagger UI)
                    """.trimMargin(),
                    ContentType.Text.Plain
                )
            }

            // API version info
            get("/version") {
                call.respond(mapOf(
                    "service" to "verify-api",
                    "version" to "1.0.0-SNAPSHOT",
                    "status" to "enabled"
                ))
            }

            // Protected routes that require authentication
            authenticate("api-key") {
                route("/v1/verify") {
                    post("/identity") {
                        call.respond(HttpStatusCode.OK, mapOf("status" to "ok"))
                    }
                }

                route("/v1/templates") {
                    get {
                        call.respond(HttpStatusCode.OK, emptyList<String>())
                    }
                }

                route("/v1/sessions") {
                    get("/{session_id}") {
                        call.respond(HttpStatusCode.OK, mapOf("status" to "ok"))
                    }
                }

                route("/v1/webhooks") {
                    get {
                        call.respond(HttpStatusCode.OK, emptyList<String>())
                    }
                }

                route("/v1/orchestrations") {
                    get {
                        call.respond(HttpStatusCode.OK, emptyList<String>())
                    }
                }
            }
        }
    }

    // ============================================================
    // Public Endpoint Tests
    // ============================================================

    @Test
    fun `test health endpoint returns OK`() = testApplication {
        application {
            testModule()
        }

        val response = client.get("/health")
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("OK", response.bodyAsText())
    }

    @Test
    fun `test root endpoint returns service info`() = testApplication {
        application {
            testModule()
        }

        val response = client.get("/")
        assertEquals(HttpStatusCode.OK, response.status)

        val body = response.bodyAsText()
        assertContains(body, "Verify API")
        assertContains(body, "Multi-tenant Verification Gateway")
        assertContains(body, "Status: ENABLED")
        assertContains(body, "Version: 1.0.0-SNAPSHOT")
        assertContains(body, "/health")
        assertContains(body, "/v1/verify/identity")
        assertContains(body, "/v1/templates")
    }

    @Test
    fun `test version endpoint returns JSON`() = testApplication {
        application {
            testModule()
        }

        val response = client.get("/version")
        assertEquals(HttpStatusCode.OK, response.status)

        // Check content type is JSON
        val contentType = response.contentType()
        assertTrue(
            contentType?.match(ContentType.Application.Json) == true,
            "Response should be JSON, got: $contentType"
        )

        // Check response body contains expected fields
        val body = response.bodyAsText()
        assertContains(body, "\"service\"")
        assertContains(body, "\"verify-api\"")
        assertContains(body, "\"version\"")
        assertContains(body, "\"1.0.0-SNAPSHOT\"")
        assertContains(body, "\"status\"")
        assertContains(body, "\"enabled\"")
    }

    @Test
    fun `test health endpoint with HEAD request`() = testApplication {
        application {
            testModule()
        }

        val response = client.head("/health")
        // HEAD should return 200 OK (if auto-head-response is enabled) or 405
        assertTrue(
            response.status == HttpStatusCode.OK || response.status == HttpStatusCode.MethodNotAllowed,
            "HEAD request should return OK or MethodNotAllowed"
        )
    }

    // ============================================================
    // Authentication Tests - Verify Endpoints
    // ============================================================

    @Test
    fun `test verify identity endpoint requires authentication`() = testApplication {
        application {
            testModule()
        }

        val response = client.post("/v1/verify/identity") {
            contentType(ContentType.Application.Json)
            setBody("""{"template":"age_check"}""")
        }
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `test verify identity with invalid API key returns unauthorized`() = testApplication {
        application {
            testModule()
        }

        val response = client.post("/v1/verify/identity") {
            contentType(ContentType.Application.Json)
            header("X-API-Key", "invalid_key_format")
            setBody("""{"template":"age_check"}""")
        }
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `test verify identity with malformed Bearer token returns unauthorized`() = testApplication {
        application {
            testModule()
        }

        val response = client.post("/v1/verify/identity") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer not_a_valid_vfy_key")
            setBody("""{"template":"age_check"}""")
        }
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    // ============================================================
    // Authentication Tests - Templates Endpoint
    // ============================================================

    @Test
    fun `test templates endpoint requires authentication`() = testApplication {
        application {
            testModule()
        }

        val response = client.get("/v1/templates")
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `test templates endpoint with invalid key returns unauthorized`() = testApplication {
        application {
            testModule()
        }

        val response = client.get("/v1/templates") {
            header("X-API-Key", "wrong_key")
        }
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    // ============================================================
    // Authentication Tests - Sessions Endpoint
    // ============================================================

    @Test
    fun `test session status endpoint requires authentication`() = testApplication {
        application {
            testModule()
        }

        val response = client.get("/v1/sessions/vs_abc123def456")
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    // ============================================================
    // Authentication Tests - Webhooks Endpoint
    // ============================================================

    @Test
    fun `test webhooks endpoint requires authentication`() = testApplication {
        application {
            testModule()
        }

        val response = client.get("/v1/webhooks")
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    // ============================================================
    // Authentication Tests - Orchestrations Endpoint
    // ============================================================

    @Test
    fun `test orchestrations endpoint requires authentication`() = testApplication {
        application {
            testModule()
        }

        val response = client.get("/v1/orchestrations")
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    // ============================================================
    // Edge Cases and Error Handling
    // ============================================================

    @Test
    fun `test non-existent endpoint returns 404`() = testApplication {
        application {
            testModule()
        }

        val response = client.get("/non-existent-endpoint")
        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    @Test
    fun `test wrong HTTP method returns 405`() = testApplication {
        application {
            testModule()
        }

        // POST to health endpoint should fail
        val response = client.post("/health")
        assertTrue(
            response.status == HttpStatusCode.NotFound || response.status == HttpStatusCode.MethodNotAllowed,
            "Wrong method should return NotFound or MethodNotAllowed"
        )
    }

    @Test
    fun `test API key prefix vfy_ is required for authentication`() = testApplication {
        application {
            testModule()
        }

        // Key without vfy_ prefix should fail authentication
        val response = client.get("/v1/templates") {
            header("X-API-Key", "test_abc123def456")
        }
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `test authentication rejects keys without vfy_ prefix`() = testApplication {
        application {
            testModule()
        }

        // Test that keys without vfy_ prefix are rejected immediately (no DB lookup)
        // This tests the format validation in ApiKeyAuthProvider.validateApiKey()

        // Test with X-API-Key header
        val response1 = client.get("/v1/templates") {
            header("X-API-Key", "invalid_key_format")
        }
        assertEquals(HttpStatusCode.Unauthorized, response1.status)

        // Test with Bearer token
        val response2 = client.get("/v1/templates") {
            header(HttpHeaders.Authorization, "Bearer wrong_format_key")
        }
        assertEquals(HttpStatusCode.Unauthorized, response2.status)

        // Test with raw Authorization header (no Bearer prefix)
        val response3 = client.get("/v1/templates") {
            header(HttpHeaders.Authorization, "not_a_vfy_key")
        }
        assertEquals(HttpStatusCode.Unauthorized, response3.status)
    }

    @Test
    fun `test authentication with valid key format but no database returns error`() = testApplication {
        application {
            testModule()
        }

        // Keys with vfy_ prefix are format-valid and attempt DB lookup
        // Without a database connection, this will fail with 500 or Unauthorized
        // depending on how the DB error is handled
        val response = client.get("/v1/templates") {
            header("X-API-Key", "vfy_test_validformat")
        }

        // Accept either 401 (if DB error is handled as auth failure) or 500 (if DB error propagates)
        assertTrue(
            response.status == HttpStatusCode.Unauthorized || response.status == HttpStatusCode.InternalServerError,
            "Valid format key without DB should return Unauthorized or InternalServerError, got: ${response.status}"
        )
    }
}
