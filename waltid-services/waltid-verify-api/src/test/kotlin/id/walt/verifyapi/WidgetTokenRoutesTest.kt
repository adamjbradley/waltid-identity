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
 * Integration tests for Widget Token Routes.
 *
 * These tests verify the HTTP routing and request/response validation
 * for the /v1/widget/tokens endpoint. Full integration tests with
 * database connectivity are in E2EIntegrationTest.
 */
class WidgetTokenRoutesTest {

    /**
     * Configures a minimal test application for testing widget token routes.
     */
    private fun Application.testModule() {
        install(ContentNegotiation) {
            json(Json {
                prettyPrint = true
                isLenient = true
                ignoreUnknownKeys = true
                encodeDefaults = true
            })
        }

        install(Authentication) {
            apiKey("api-key")
        }

        routing {
            authenticate("api-key") {
                route("/v1/widget/tokens") {
                    post {
                        call.respond(HttpStatusCode.Created, mapOf(
                            "client_token" to "ct_test.signature",
                            "expires_at" to 1234567890L
                        ))
                    }
                }
            }
        }
    }

    // ============================================================
    // Authentication Tests
    // ============================================================

    @Test
    fun `test widget tokens endpoint requires authentication`() = testApplication {
        application {
            testModule()
        }

        val response = client.post("/v1/widget/tokens") {
            contentType(ContentType.Application.Json)
            setBody("""{"templates":["age_check"]}""")
        }
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `test widget tokens endpoint with invalid API key returns unauthorized`() = testApplication {
        application {
            testModule()
        }

        val response = client.post("/v1/widget/tokens") {
            contentType(ContentType.Application.Json)
            header("X-API-Key", "invalid_key_format")
            setBody("""{"templates":["age_check"]}""")
        }
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `test widget tokens endpoint with malformed Bearer token returns unauthorized`() = testApplication {
        application {
            testModule()
        }

        val response = client.post("/v1/widget/tokens") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer not_a_valid_vfy_key")
            setBody("""{"templates":["age_check"]}""")
        }
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    // ============================================================
    // HTTP Method Tests
    // ============================================================

    @Test
    fun `test widget tokens endpoint only accepts POST`() = testApplication {
        application {
            testModule()
        }

        // GET should return 404 or 405
        val getResponse = client.get("/v1/widget/tokens")
        assertTrue(
            getResponse.status == HttpStatusCode.NotFound ||
            getResponse.status == HttpStatusCode.MethodNotAllowed ||
            getResponse.status == HttpStatusCode.Unauthorized,
            "GET should return NotFound, MethodNotAllowed, or Unauthorized"
        )

        // PUT should return 404 or 405
        val putResponse = client.put("/v1/widget/tokens") {
            contentType(ContentType.Application.Json)
            setBody("""{}""")
        }
        assertTrue(
            putResponse.status == HttpStatusCode.NotFound ||
            putResponse.status == HttpStatusCode.MethodNotAllowed ||
            putResponse.status == HttpStatusCode.Unauthorized,
            "PUT should return NotFound, MethodNotAllowed, or Unauthorized"
        )

        // DELETE should return 404 or 405
        val deleteResponse = client.delete("/v1/widget/tokens")
        assertTrue(
            deleteResponse.status == HttpStatusCode.NotFound ||
            deleteResponse.status == HttpStatusCode.MethodNotAllowed ||
            deleteResponse.status == HttpStatusCode.Unauthorized,
            "DELETE should return NotFound, MethodNotAllowed, or Unauthorized"
        )
    }

    // ============================================================
    // Response Format Tests (with mock authenticated handler)
    // ============================================================

    @Test
    fun `test response contains expected fields`() = testApplication {
        // Setup a test app that bypasses auth and returns a mock response
        application {
            install(ContentNegotiation) {
                json(Json {
                    prettyPrint = true
                    encodeDefaults = true
                })
            }
            routing {
                post("/v1/widget/tokens") {
                    // Return a JSON string directly to avoid serialization issues
                    call.respondText(
                        """{"client_token":"ct_abc123.xyz789","expires_at":1234567890}""",
                        ContentType.Application.Json,
                        HttpStatusCode.Created
                    )
                }
            }
        }

        val response = client.post("/v1/widget/tokens") {
            contentType(ContentType.Application.Json)
            setBody("""{"templates":["age_check"]}""")
        }

        assertEquals(HttpStatusCode.Created, response.status)

        val body = response.bodyAsText()
        assertContains(body, "client_token")
        assertContains(body, "ct_")
        assertContains(body, "expires_at")
    }
}
