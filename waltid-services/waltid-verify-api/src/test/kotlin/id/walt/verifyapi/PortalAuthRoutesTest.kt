package id.walt.verifyapi

import id.walt.verifyapi.portal.portalAuthRoutes
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.routing.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertContains
import kotlin.test.assertTrue

/**
 * Integration tests for Portal Authentication Routes.
 *
 * These tests verify the HTTP routing and request/response validation
 * for the `/portal/auth/` endpoints. Full integration tests with
 * database connectivity would require a test database setup.
 */
class PortalAuthRoutesTest {

    /**
     * Configures a minimal test application for testing portal auth routes.
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

        routing {
            portalAuthRoutes()
        }
    }

    // ============================================================
    // Signup Endpoint Tests
    // ============================================================

    @Test
    fun `test signup endpoint requires valid request body`() = testApplication {
        application {
            testModule()
        }

        val response = client.post("/portal/auth/signup") {
            contentType(ContentType.Application.Json)
            setBody("""{}""")
        }
        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `test signup endpoint validates email format`() = testApplication {
        application {
            testModule()
        }

        val response = client.post("/portal/auth/signup") {
            contentType(ContentType.Application.Json)
            setBody("""{"email":"invalid-email","password":"password123","organization_name":"Test Org"}""")
        }
        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertContains(response.bodyAsText(), "email")
    }

    @Test
    fun `test signup endpoint validates password length`() = testApplication {
        application {
            testModule()
        }

        val response = client.post("/portal/auth/signup") {
            contentType(ContentType.Application.Json)
            setBody("""{"email":"test@example.com","password":"short","organization_name":"Test Org"}""")
        }
        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertContains(response.bodyAsText().lowercase(), "password")
    }

    @Test
    fun `test signup endpoint validates organization name`() = testApplication {
        application {
            testModule()
        }

        val response = client.post("/portal/auth/signup") {
            contentType(ContentType.Application.Json)
            setBody("""{"email":"test@example.com","password":"password123","organization_name":""}""")
        }
        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertContains(response.bodyAsText().lowercase(), "organization")
    }

    @Test
    fun `test signup endpoint only accepts POST`() = testApplication {
        application {
            testModule()
        }

        // GET should return 404 or 405
        val getResponse = client.get("/portal/auth/signup")
        assertTrue(
            getResponse.status == HttpStatusCode.NotFound ||
            getResponse.status == HttpStatusCode.MethodNotAllowed,
            "GET should return NotFound or MethodNotAllowed"
        )

        // PUT should return 404 or 405
        val putResponse = client.put("/portal/auth/signup") {
            contentType(ContentType.Application.Json)
            setBody("""{}""")
        }
        assertTrue(
            putResponse.status == HttpStatusCode.NotFound ||
            putResponse.status == HttpStatusCode.MethodNotAllowed,
            "PUT should return NotFound or MethodNotAllowed"
        )
    }

    // ============================================================
    // Login Endpoint Tests
    // ============================================================

    @Test
    fun `test login endpoint requires valid request body`() = testApplication {
        application {
            testModule()
        }

        val response = client.post("/portal/auth/login") {
            contentType(ContentType.Application.Json)
            setBody("""{}""")
        }
        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `test login endpoint validates email format`() = testApplication {
        application {
            testModule()
        }

        val response = client.post("/portal/auth/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"email":"not-an-email","password":"password123"}""")
        }
        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertContains(response.bodyAsText(), "email")
    }

    @Test
    fun `test login endpoint requires password`() = testApplication {
        application {
            testModule()
        }

        val response = client.post("/portal/auth/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"email":"test@example.com","password":""}""")
        }
        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertContains(response.bodyAsText().lowercase(), "password")
    }

    @Test
    fun `test login endpoint only accepts POST`() = testApplication {
        application {
            testModule()
        }

        val getResponse = client.get("/portal/auth/login")
        assertTrue(
            getResponse.status == HttpStatusCode.NotFound ||
            getResponse.status == HttpStatusCode.MethodNotAllowed,
            "GET should return NotFound or MethodNotAllowed"
        )
    }

    // ============================================================
    // Refresh Endpoint Tests
    // ============================================================

    @Test
    fun `test refresh endpoint requires valid request body`() = testApplication {
        application {
            testModule()
        }

        val response = client.post("/portal/auth/refresh") {
            contentType(ContentType.Application.Json)
            setBody("""{}""")
        }
        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `test refresh endpoint requires refresh token`() = testApplication {
        application {
            testModule()
        }

        val response = client.post("/portal/auth/refresh") {
            contentType(ContentType.Application.Json)
            setBody("""{"refresh_token":""}""")
        }
        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertContains(response.bodyAsText(), "token")
    }

    @Test
    fun `test refresh endpoint rejects invalid token`() = testApplication {
        application {
            testModule()
        }

        val response = client.post("/portal/auth/refresh") {
            contentType(ContentType.Application.Json)
            setBody("""{"refresh_token":"invalid.token.here"}""")
        }
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `test refresh endpoint only accepts POST`() = testApplication {
        application {
            testModule()
        }

        val getResponse = client.get("/portal/auth/refresh")
        assertTrue(
            getResponse.status == HttpStatusCode.NotFound ||
            getResponse.status == HttpStatusCode.MethodNotAllowed,
            "GET should return NotFound or MethodNotAllowed"
        )
    }

    // ============================================================
    // Password Reset Endpoint Tests
    // ============================================================

    @Test
    fun `test password reset endpoint requires valid request body`() = testApplication {
        application {
            testModule()
        }

        val response = client.post("/portal/auth/password-reset") {
            contentType(ContentType.Application.Json)
            setBody("""{}""")
        }
        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `test password reset endpoint validates email format`() = testApplication {
        application {
            testModule()
        }

        val response = client.post("/portal/auth/password-reset") {
            contentType(ContentType.Application.Json)
            setBody("""{"email":"invalid-email"}""")
        }
        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertContains(response.bodyAsText(), "email")
    }

    @Test
    fun `test password reset endpoint returns success for valid email`() = testApplication {
        application {
            testModule()
        }

        val response = client.post("/portal/auth/password-reset") {
            contentType(ContentType.Application.Json)
            setBody("""{"email":"test@example.com"}""")
        }
        // Should return 200 OK even if user doesn't exist (to prevent email enumeration)
        assertEquals(HttpStatusCode.OK, response.status)
    }

    @Test
    fun `test password reset endpoint only accepts POST`() = testApplication {
        application {
            testModule()
        }

        val getResponse = client.get("/portal/auth/password-reset")
        assertTrue(
            getResponse.status == HttpStatusCode.NotFound ||
            getResponse.status == HttpStatusCode.MethodNotAllowed,
            "GET should return NotFound or MethodNotAllowed"
        )
    }

    // ============================================================
    // Password Reset Confirm Endpoint Tests
    // ============================================================

    @Test
    fun `test password reset confirm endpoint requires valid request body`() = testApplication {
        application {
            testModule()
        }

        val response = client.post("/portal/auth/password-reset/confirm") {
            contentType(ContentType.Application.Json)
            setBody("""{}""")
        }
        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `test password reset confirm endpoint requires token`() = testApplication {
        application {
            testModule()
        }

        val response = client.post("/portal/auth/password-reset/confirm") {
            contentType(ContentType.Application.Json)
            setBody("""{"token":"","new_password":"password123"}""")
        }
        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertContains(response.bodyAsText(), "token")
    }

    @Test
    fun `test password reset confirm endpoint validates password`() = testApplication {
        application {
            testModule()
        }

        val response = client.post("/portal/auth/password-reset/confirm") {
            contentType(ContentType.Application.Json)
            setBody("""{"token":"some-token","new_password":"short"}""")
        }
        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertContains(response.bodyAsText().lowercase(), "password")
    }

    @Test
    fun `test password reset confirm endpoint rejects invalid token`() = testApplication {
        application {
            testModule()
        }

        val response = client.post("/portal/auth/password-reset/confirm") {
            contentType(ContentType.Application.Json)
            setBody("""{"token":"invalid-token","new_password":"password123"}""")
        }
        // Currently stubbed, so always returns invalid token
        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertContains(response.bodyAsText(), "token")
    }

    // ============================================================
    // Response Format Tests
    // ============================================================

    @Test
    fun `test error responses have correct format`() = testApplication {
        application {
            testModule()
        }

        val response = client.post("/portal/auth/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"email":"invalid","password":"test"}""")
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        val body = response.bodyAsText()
        assertContains(body, "error")
        assertContains(body, "message")
    }

    @Test
    fun `test password reset success response has correct format`() = testApplication {
        application {
            testModule()
        }

        val response = client.post("/portal/auth/password-reset") {
            contentType(ContentType.Application.Json)
            setBody("""{"email":"test@example.com"}""")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertContains(body, "message")
    }

    // ============================================================
    // Edge Cases
    // ============================================================

    @Test
    fun `test endpoints handle malformed JSON gracefully`() = testApplication {
        application {
            testModule()
        }

        val response = client.post("/portal/auth/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"email":"test@example.com", invalid json}""")
        }
        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `test endpoints handle empty body gracefully`() = testApplication {
        application {
            testModule()
        }

        val response = client.post("/portal/auth/login") {
            contentType(ContentType.Application.Json)
            setBody("")
        }
        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `test email normalization to lowercase`() = testApplication {
        application {
            testModule()
        }

        // Test with uppercase email - should be normalized
        // Since we don't have DB, we can only verify validation passes
        val response = client.post("/portal/auth/password-reset") {
            contentType(ContentType.Application.Json)
            setBody("""{"email":"TEST@EXAMPLE.COM"}""")
        }
        assertEquals(HttpStatusCode.OK, response.status)
    }
}
