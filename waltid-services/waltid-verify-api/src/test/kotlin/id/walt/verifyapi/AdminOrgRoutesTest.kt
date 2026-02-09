package id.walt.verifyapi

import id.walt.verifyapi.portal.PortalAuthService
import id.walt.verifyapi.routes.OrgRpResponse
import id.walt.verifyapi.routes.SetRpIdRequest
import id.walt.verifyapi.routes.adminOrgRoutes
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.testing.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for Admin Organization RP routes and DTOs.
 *
 * These tests verify:
 * - DTO serialization (SetRpIdRequest, OrgRpResponse)
 * - Admin endpoint authentication requirements
 * - Request validation behavior
 */
class AdminOrgRoutesTest {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = true
    }

    // ============================================================
    // DTO Serialization Tests
    // ============================================================

    @Test
    fun `test SetRpIdRequest serialization`() {
        val request = SetRpIdRequest(rpId = "03b25ab0-84a6-4574-8f10-c0e18e7f93ed")
        val serialized = json.encodeToString(request)
        val deserialized = json.decodeFromString<SetRpIdRequest>(serialized)

        assertEquals("03b25ab0-84a6-4574-8f10-c0e18e7f93ed", deserialized.rpId)
    }

    @Test
    fun `test SetRpIdRequest with domain-style rpId`() {
        val request = SetRpIdRequest(rpId = "rp.theaustraliahack.com")
        val serialized = json.encodeToString(request)
        val deserialized = json.decodeFromString<SetRpIdRequest>(serialized)

        assertEquals("rp.theaustraliahack.com", deserialized.rpId)
    }

    @Test
    fun `test OrgRpResponse with rpId`() {
        val response = OrgRpResponse(
            organizationId = UUID.randomUUID().toString(),
            rpId = "rp-id-123"
        )
        val serialized = json.encodeToString(response)
        val deserialized = json.decodeFromString<OrgRpResponse>(serialized)

        assertNotNull(deserialized.organizationId)
        assertEquals("rp-id-123", deserialized.rpId)
    }

    @Test
    fun `test OrgRpResponse with null rpId`() {
        val orgId = UUID.randomUUID().toString()
        val response = OrgRpResponse(
            organizationId = orgId,
            rpId = null
        )
        val serialized = json.encodeToString(response)
        val deserialized = json.decodeFromString<OrgRpResponse>(serialized)

        assertEquals(orgId, deserialized.organizationId)
        assertNull(deserialized.rpId)
    }

    @Test
    fun `test OrgRpResponse UUID format in organizationId`() {
        val uuid = UUID.randomUUID()
        val response = OrgRpResponse(
            organizationId = uuid.toString(),
            rpId = "test-rp"
        )

        val parsedUuid = UUID.fromString(response.organizationId)
        assertEquals(uuid, parsedUuid)
    }

    // ============================================================
    // Route Authentication Tests
    // ============================================================

    /**
     * Configures a minimal test application with JWT auth and admin org routes.
     * Does NOT configure a database, so actual DB operations will fail — we only
     * test auth and validation at the HTTP layer.
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
            jwt("portal-jwt") {
                realm = PortalAuthService.jwtConfig.realm
                verifier(
                    com.auth0.jwt.JWT.require(
                        com.auth0.jwt.algorithms.Algorithm.HMAC256(PortalAuthService.jwtConfig.secret)
                    )
                        .withIssuer(PortalAuthService.jwtConfig.issuer)
                        .withAudience(PortalAuthService.jwtConfig.audience)
                        .withClaim("type", "access")
                        .build()
                )
                validate { credential ->
                    val userId = credential.payload.subject
                    val email = credential.payload.getClaim("email").asString()
                    val orgId = credential.payload.getClaim("org_id").asString()
                    val orgName = credential.payload.getClaim("org_name").asString()
                    val role = credential.payload.getClaim("role").asString()
                    val tokenType = credential.payload.getClaim("type").asString()

                    if (userId != null && email != null && orgId != null && orgName != null && role != null) {
                        try {
                            id.walt.verifyapi.portal.PortalUserPrincipal(
                                userId = UUID.fromString(userId),
                                email = email,
                                organizationId = UUID.fromString(orgId),
                                organizationName = orgName,
                                role = role,
                                tokenType = tokenType ?: "access"
                            )
                        } catch (e: Exception) {
                            null
                        }
                    } else null
                }
                challenge { _, _ ->
                    call.respondText(
                        """{"error":"invalid_token"}""",
                        contentType = ContentType.Application.Json,
                        status = HttpStatusCode.Unauthorized
                    )
                }
            }
        }

        routing {
            adminOrgRoutes()
        }
    }

    /**
     * Generate a valid admin JWT token for testing.
     */
    private fun generateAdminToken(orgId: UUID = UUID.randomUUID()): String {
        val userInfo = PortalAuthService.UserInfo(
            userId = UUID.randomUUID(),
            email = "admin@test.com",
            passwordHash = "",
            organizationId = orgId,
            organizationName = "Test Org",
            role = "admin",
            emailVerifiedAt = java.time.Instant.now()
        )
        return PortalAuthService.generateTokens(userInfo).accessToken
    }

    /**
     * Generate a valid viewer (non-admin) JWT token for testing.
     */
    private fun generateViewerToken(orgId: UUID = UUID.randomUUID()): String {
        val userInfo = PortalAuthService.UserInfo(
            userId = UUID.randomUUID(),
            email = "viewer@test.com",
            passwordHash = "",
            organizationId = orgId,
            organizationName = "Test Org",
            role = "viewer",
            emailVerifiedAt = java.time.Instant.now()
        )
        return PortalAuthService.generateTokens(userInfo).accessToken
    }

    @Test
    fun `test GET rp endpoint requires authentication`() = testApplication {
        application { testModule() }

        val orgId = UUID.randomUUID()
        val response = client.get("/v1/admin/organizations/$orgId/rp")
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `test PUT rp endpoint requires authentication`() = testApplication {
        application { testModule() }

        val orgId = UUID.randomUUID()
        val response = client.put("/v1/admin/organizations/$orgId/rp") {
            contentType(ContentType.Application.Json)
            setBody("""{"rpId":"test-rp"}""")
        }
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `test DELETE rp endpoint requires authentication`() = testApplication {
        application { testModule() }

        val orgId = UUID.randomUUID()
        val response = client.delete("/v1/admin/organizations/$orgId/rp")
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `test GET rp endpoint requires admin role`() = testApplication {
        application { testModule() }

        val orgId = UUID.randomUUID()
        val token = generateViewerToken(orgId)
        val response = client.get("/v1/admin/organizations/$orgId/rp") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        assertEquals(HttpStatusCode.Forbidden, response.status)
        assertTrue(response.bodyAsText().contains("Admin role required"))
    }

    @Test
    fun `test PUT rp endpoint requires admin role`() = testApplication {
        application { testModule() }

        val orgId = UUID.randomUUID()
        val token = generateViewerToken(orgId)
        val response = client.put("/v1/admin/organizations/$orgId/rp") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{"rpId":"test-rp"}""")
        }
        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    @Test
    fun `test DELETE rp endpoint requires admin role`() = testApplication {
        application { testModule() }

        val orgId = UUID.randomUUID()
        val token = generateViewerToken(orgId)
        val response = client.delete("/v1/admin/organizations/$orgId/rp") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    @Test
    fun `test PUT rp endpoint rejects invalid orgId format`() = testApplication {
        application { testModule() }

        val token = generateAdminToken()
        val response = client.put("/v1/admin/organizations/not-a-uuid/rp") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{"rpId":"test-rp"}""")
        }
        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertTrue(response.bodyAsText().contains("Invalid organization ID format"))
    }

    @Test
    fun `test GET rp endpoint rejects invalid orgId format`() = testApplication {
        application { testModule() }

        val token = generateAdminToken()
        val response = client.get("/v1/admin/organizations/not-a-uuid/rp") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertTrue(response.bodyAsText().contains("Invalid organization ID format"))
    }

    @Test
    fun `test DELETE rp endpoint rejects invalid orgId format`() = testApplication {
        application { testModule() }

        val token = generateAdminToken()
        val response = client.delete("/v1/admin/organizations/not-a-uuid/rp") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertTrue(response.bodyAsText().contains("Invalid organization ID format"))
    }

    @Test
    fun `test PUT rp endpoint rejects invalid JWT token`() = testApplication {
        application { testModule() }

        val orgId = UUID.randomUUID()
        val response = client.put("/v1/admin/organizations/$orgId/rp") {
            header(HttpHeaders.Authorization, "Bearer invalid.token.here")
            contentType(ContentType.Application.Json)
            setBody("""{"rpId":"test-rp"}""")
        }
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `test non-existent endpoint under admin returns 404`() = testApplication {
        application { testModule() }

        val response = client.get("/v1/admin/organizations/something/unknown")
        assertTrue(
            response.status == HttpStatusCode.NotFound || response.status == HttpStatusCode.Unauthorized,
            "Non-existent admin endpoint should return NotFound or Unauthorized"
        )
    }

    // ============================================================
    // JSON Validation Tests
    // ============================================================

    @Test
    fun `test SetRpIdRequest JSON round-trip`() {
        val original = SetRpIdRequest(rpId = "uuid-rp-id-value")
        val jsonStr = json.encodeToString(original)

        assertTrue(jsonStr.contains("\"rpId\""), "JSON should contain rpId field")
        assertTrue(jsonStr.contains("uuid-rp-id-value"), "JSON should contain rpId value")

        val parsed = json.decodeFromString<SetRpIdRequest>(jsonStr)
        assertEquals(original.rpId, parsed.rpId)
    }

    @Test
    fun `test OrgRpResponse JSON round-trip`() {
        val orgId = UUID.randomUUID().toString()
        val original = OrgRpResponse(organizationId = orgId, rpId = "my-rp")
        val jsonStr = json.encodeToString(original)

        assertTrue(jsonStr.contains("\"organizationId\""), "JSON should contain organizationId field")
        assertTrue(jsonStr.contains("\"rpId\""), "JSON should contain rpId field")

        val parsed = json.decodeFromString<OrgRpResponse>(jsonStr)
        assertEquals(original.organizationId, parsed.organizationId)
        assertEquals(original.rpId, parsed.rpId)
    }

    @Test
    fun `test SetRpIdRequest with special characters in rpId`() {
        val request = SetRpIdRequest(rpId = "rp.example.com/path?q=1")
        val serialized = json.encodeToString(request)
        val deserialized = json.decodeFromString<SetRpIdRequest>(serialized)
        assertEquals("rp.example.com/path?q=1", deserialized.rpId)
    }

    @Test
    fun `test JWT token generation for admin produces valid token`() {
        val orgId = UUID.randomUUID()
        val token = generateAdminToken(orgId)

        assertTrue(token.isNotBlank(), "Token should not be blank")
        assertTrue(token.count { it == '.' } == 2, "JWT should have 3 parts separated by dots")

        val principal = PortalAuthService.validateAccessToken(token)
        assertNotNull(principal, "Token should be validated successfully")
        assertEquals(orgId, principal.organizationId)
        assertEquals("admin", principal.role)
    }

    @Test
    fun `test JWT token generation for viewer produces valid token`() {
        val orgId = UUID.randomUUID()
        val token = generateViewerToken(orgId)

        val principal = PortalAuthService.validateAccessToken(token)
        assertNotNull(principal)
        assertEquals(orgId, principal.organizationId)
        assertEquals("viewer", principal.role)
    }
}
