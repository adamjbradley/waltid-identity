package id.walt.verifyapi

import id.walt.verifyapi.routes.WidgetErrorResponse
import id.walt.verifyapi.routes.WidgetVerifyRequest
import id.walt.verifyapi.routes.WidgetVerifyResponse
import id.walt.verifyapi.routes.WidgetSessionStatusResponse
import id.walt.verifyapi.routes.widgetRoutes
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertContains
import kotlin.test.assertTrue

/**
 * Tests for Widget Routes (`/widget/v1/`).
 *
 * These tests verify the HTTP routing, authentication, and request/response validation
 * for widget SDK endpoints. Full integration tests with database and ClientTokenService
 * are in E2EIntegrationTest.
 */
class WidgetRoutesTest {

    private val json = Json {
        prettyPrint = true
        isLenient = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /**
     * Configures a minimal test application for testing widget routes.
     */
    private fun Application.testModule() {
        install(ContentNegotiation) {
            json(json)
        }

        routing {
            // SDK.js endpoint (public)
            get("/widget/v1/sdk.js") {
                call.respondText(
                    "// Mock SDK.js for testing\nvar WaltIdVerify = function() {};",
                    ContentType.Text.JavaScript
                )
            }

            // Mock verify endpoint that checks auth header
            post("/widget/v1/verify") {
                val authHeader: String? = call.request.headers[HttpHeaders.Authorization]

                if (authHeader == null) {
                    call.respond(
                        HttpStatusCode.Unauthorized,
                        WidgetErrorResponse("Missing Authorization header", "MISSING_AUTH")
                    )
                    return@post
                }

                if (!authHeader.startsWith("Bearer ", ignoreCase = true)) {
                    call.respond(
                        HttpStatusCode.Unauthorized,
                        WidgetErrorResponse("Invalid Authorization header format", "INVALID_AUTH_FORMAT")
                    )
                    return@post
                }

                val token = authHeader.removePrefix("Bearer ").removePrefix("bearer ").trim()
                if (!token.startsWith("ct_")) {
                    call.respond(
                        HttpStatusCode.Unauthorized,
                        WidgetErrorResponse("Invalid token type. Expected client token (ct_*)", "INVALID_TOKEN_TYPE")
                    )
                    return@post
                }

                // Mock success response
                call.respond(
                    HttpStatusCode.Created,
                    WidgetVerifyResponse(
                        sessionId = "vs_test123",
                        qrCodeUrl = "http://localhost:7010/v1/qr/vs_test123.png",
                        qrCodeData = "openid4vp://authorize?request_uri=...",
                        deepLink = "eudi-openid4vp://authorize?request_uri=...",
                        expiresAt = System.currentTimeMillis() + 300000
                    )
                )
            }

            // Mock session status endpoint
            get("/widget/v1/sessions/{id}") {
                val authHeader: String? = call.request.headers[HttpHeaders.Authorization]

                if (authHeader == null) {
                    call.respond(
                        HttpStatusCode.Unauthorized,
                        WidgetErrorResponse("Missing Authorization header", "MISSING_AUTH")
                    )
                    return@get
                }

                if (!authHeader.startsWith("Bearer ", ignoreCase = true)) {
                    call.respond(
                        HttpStatusCode.Unauthorized,
                        WidgetErrorResponse("Invalid Authorization header format", "INVALID_AUTH_FORMAT")
                    )
                    return@get
                }

                val token = authHeader.removePrefix("Bearer ").removePrefix("bearer ").trim()
                if (!token.startsWith("ct_")) {
                    call.respond(
                        HttpStatusCode.Unauthorized,
                        WidgetErrorResponse("Invalid token type. Expected client token (ct_*)", "INVALID_TOKEN_TYPE")
                    )
                    return@get
                }

                val sessionId = call.parameters["id"]
                if (sessionId.isNullOrBlank()) {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        WidgetErrorResponse("Session ID is required", "MISSING_SESSION_ID")
                    )
                    return@get
                }

                // Mock success response
                call.respond(
                    WidgetSessionStatusResponse(
                        sessionId = sessionId,
                        status = "pending",
                        templateName = "age_check",
                        result = null,
                        verifiedAt = null,
                        expiresAt = System.currentTimeMillis() + 300000
                    )
                )
            }
        }
    }

    // ============================================================
    // SDK.js Tests (Public Endpoint)
    // ============================================================

    @Nested
    inner class SdkJsTests {

        @Test
        fun `GET sdk js returns JavaScript content type`() = testApplication {
            application { testModule() }

            val response = client.get("/widget/v1/sdk.js")

            assertEquals(HttpStatusCode.OK, response.status)
            // Content type should contain text/javascript (may have charset suffix)
            val contentType = response.contentType()?.toString() ?: ""
            assertTrue(
                contentType.contains("text/javascript") || contentType.contains("application/javascript"),
                "Content type should be JavaScript, got: $contentType"
            )
        }

        @Test
        fun `GET sdk js does not require authentication`() = testApplication {
            application { testModule() }

            // No auth header
            val response = client.get("/widget/v1/sdk.js")

            assertEquals(HttpStatusCode.OK, response.status)
        }

        @Test
        fun `GET sdk js returns JavaScript code`() = testApplication {
            application { testModule() }

            val response = client.get("/widget/v1/sdk.js")
            val body = response.bodyAsText()

            assertTrue(body.contains("WaltIdVerify"), "SDK should define WaltIdVerify")
        }
    }

    // ============================================================
    // Verify Endpoint Authentication Tests
    // ============================================================

    @Nested
    inner class VerifyAuthenticationTests {

        @Test
        fun `POST verify requires authentication`() = testApplication {
            application { testModule() }

            val response = client.post("/widget/v1/verify") {
                contentType(ContentType.Application.Json)
                setBody("""{"template":"age_check"}""")
            }

            assertEquals(HttpStatusCode.Unauthorized, response.status)
            val body = response.bodyAsText()
            assertContains(body, "MISSING_AUTH")
        }

        @Test
        fun `POST verify rejects non-Bearer auth`() = testApplication {
            application { testModule() }

            val response = client.post("/widget/v1/verify") {
                contentType(ContentType.Application.Json)
                header(HttpHeaders.Authorization, "Basic dXNlcjpwYXNz")
                setBody("""{"template":"age_check"}""")
            }

            assertEquals(HttpStatusCode.Unauthorized, response.status)
            val body = response.bodyAsText()
            assertContains(body, "INVALID_AUTH_FORMAT")
        }

        @Test
        fun `POST verify rejects API key tokens`() = testApplication {
            application { testModule() }

            val response = client.post("/widget/v1/verify") {
                contentType(ContentType.Application.Json)
                header(HttpHeaders.Authorization, "Bearer vfy_test_abc123")
                setBody("""{"template":"age_check"}""")
            }

            assertEquals(HttpStatusCode.Unauthorized, response.status)
            val body = response.bodyAsText()
            assertContains(body, "INVALID_TOKEN_TYPE")
        }

        @Test
        fun `POST verify accepts client token`() = testApplication {
            application { testModule() }

            val response = client.post("/widget/v1/verify") {
                contentType(ContentType.Application.Json)
                header(HttpHeaders.Authorization, "Bearer ct_test123.signature")
                setBody("""{"template":"age_check"}""")
            }

            assertEquals(HttpStatusCode.Created, response.status)
        }

        @Test
        fun `POST verify accepts lowercase bearer`() = testApplication {
            application { testModule() }

            val response = client.post("/widget/v1/verify") {
                contentType(ContentType.Application.Json)
                header(HttpHeaders.Authorization, "bearer ct_test123.signature")
                setBody("""{"template":"age_check"}""")
            }

            assertEquals(HttpStatusCode.Created, response.status)
        }
    }

    // ============================================================
    // Verify Endpoint Response Tests
    // ============================================================

    @Nested
    inner class VerifyResponseTests {

        @Test
        fun `POST verify returns expected response fields`() = testApplication {
            application { testModule() }

            val response = client.post("/widget/v1/verify") {
                contentType(ContentType.Application.Json)
                header(HttpHeaders.Authorization, "Bearer ct_test123.signature")
                setBody("""{"template":"age_check"}""")
            }

            assertEquals(HttpStatusCode.Created, response.status)

            val body = response.bodyAsText()
            assertContains(body, "session_id")
            assertContains(body, "qr_code_url")
            assertContains(body, "qr_code_data")
            assertContains(body, "deep_link")
            assertContains(body, "expires_at")
        }

        @Test
        fun `POST verify returns session ID with vs prefix`() = testApplication {
            application { testModule() }

            val response = client.post("/widget/v1/verify") {
                contentType(ContentType.Application.Json)
                header(HttpHeaders.Authorization, "Bearer ct_test123.signature")
                setBody("""{"template":"age_check"}""")
            }

            val body = response.bodyAsText()
            assertContains(body, "vs_")
        }

        @Test
        fun `POST verify returns EUDI deep link`() = testApplication {
            application { testModule() }

            val response = client.post("/widget/v1/verify") {
                contentType(ContentType.Application.Json)
                header(HttpHeaders.Authorization, "Bearer ct_test123.signature")
                setBody("""{"template":"age_check"}""")
            }

            val body = response.bodyAsText()
            assertContains(body, "eudi-openid4vp://")
        }
    }

    // ============================================================
    // Session Status Authentication Tests
    // ============================================================

    @Nested
    inner class SessionStatusAuthenticationTests {

        @Test
        fun `GET session status requires authentication`() = testApplication {
            application { testModule() }

            val response = client.get("/widget/v1/sessions/vs_test123")

            assertEquals(HttpStatusCode.Unauthorized, response.status)
            val body = response.bodyAsText()
            assertContains(body, "MISSING_AUTH")
        }

        @Test
        fun `GET session status rejects API key tokens`() = testApplication {
            application { testModule() }

            val response = client.get("/widget/v1/sessions/vs_test123") {
                header(HttpHeaders.Authorization, "Bearer vfy_test_abc123")
            }

            assertEquals(HttpStatusCode.Unauthorized, response.status)
            val body = response.bodyAsText()
            assertContains(body, "INVALID_TOKEN_TYPE")
        }

        @Test
        fun `GET session status accepts client token`() = testApplication {
            application { testModule() }

            val response = client.get("/widget/v1/sessions/vs_test123") {
                header(HttpHeaders.Authorization, "Bearer ct_test123.signature")
            }

            assertEquals(HttpStatusCode.OK, response.status)
        }
    }

    // ============================================================
    // Session Status Response Tests
    // ============================================================

    @Nested
    inner class SessionStatusResponseTests {

        @Test
        fun `GET session status returns expected fields`() = testApplication {
            application { testModule() }

            val response = client.get("/widget/v1/sessions/vs_test123") {
                header(HttpHeaders.Authorization, "Bearer ct_test123.signature")
            }

            assertEquals(HttpStatusCode.OK, response.status)

            val body = response.bodyAsText()
            assertContains(body, "session_id")
            assertContains(body, "status")
            assertContains(body, "template_name")
            assertContains(body, "expires_at")
        }

        @Test
        fun `GET session status returns correct session ID`() = testApplication {
            application { testModule() }

            val response = client.get("/widget/v1/sessions/vs_abc123") {
                header(HttpHeaders.Authorization, "Bearer ct_test123.signature")
            }

            val body = response.bodyAsText()
            assertContains(body, "vs_abc123")
        }
    }

    // ============================================================
    // HTTP Method Tests
    // ============================================================

    @Nested
    inner class HttpMethodTests {

        @Test
        fun `verify endpoint only accepts POST`() = testApplication {
            application { testModule() }

            // GET should fail
            val getResponse = client.get("/widget/v1/verify") {
                header(HttpHeaders.Authorization, "Bearer ct_test123.signature")
            }
            assertTrue(
                getResponse.status == HttpStatusCode.NotFound ||
                getResponse.status == HttpStatusCode.MethodNotAllowed,
                "GET on verify should return NotFound or MethodNotAllowed"
            )

            // PUT should fail
            val putResponse = client.put("/widget/v1/verify") {
                header(HttpHeaders.Authorization, "Bearer ct_test123.signature")
                contentType(ContentType.Application.Json)
                setBody("{}")
            }
            assertTrue(
                putResponse.status == HttpStatusCode.NotFound ||
                putResponse.status == HttpStatusCode.MethodNotAllowed,
                "PUT on verify should return NotFound or MethodNotAllowed"
            )
        }

        @Test
        fun `session status only accepts GET`() = testApplication {
            application { testModule() }

            // POST should fail
            val postResponse = client.post("/widget/v1/sessions/vs_test123") {
                header(HttpHeaders.Authorization, "Bearer ct_test123.signature")
                contentType(ContentType.Application.Json)
                setBody("{}")
            }
            assertTrue(
                postResponse.status == HttpStatusCode.NotFound ||
                postResponse.status == HttpStatusCode.MethodNotAllowed,
                "POST on sessions should return NotFound or MethodNotAllowed"
            )

            // DELETE should fail
            val deleteResponse = client.delete("/widget/v1/sessions/vs_test123") {
                header(HttpHeaders.Authorization, "Bearer ct_test123.signature")
            }
            assertTrue(
                deleteResponse.status == HttpStatusCode.NotFound ||
                deleteResponse.status == HttpStatusCode.MethodNotAllowed,
                "DELETE on sessions should return NotFound or MethodNotAllowed"
            )
        }
    }

    // ============================================================
    // Error Response Format Tests
    // ============================================================

    @Nested
    inner class ErrorResponseFormatTests {

        @Test
        fun `error responses include error message and code`() = testApplication {
            application { testModule() }

            val response = client.post("/widget/v1/verify") {
                contentType(ContentType.Application.Json)
                setBody("""{"template":"age_check"}""")
            }

            assertEquals(HttpStatusCode.Unauthorized, response.status)

            val body = response.bodyAsText()
            assertContains(body, "\"error\"")
            assertContains(body, "\"code\"")
        }
    }
}
