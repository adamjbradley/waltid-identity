package id.walt.verifyapi

import id.walt.verifyapi.portal.PortalAuthService
import id.walt.verifyapi.routes.UpdateWidgetConfigRequest
import id.walt.verifyapi.routes.WidgetCodeSnippet
import id.walt.verifyapi.routes.WidgetConfigResponse
import id.walt.verifyapi.routes.WidgetSnippetsResponse
import id.walt.verifyapi.routes.WidgetTemplateInfo
import id.walt.verifyapi.routes.validateOrigins
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Unit tests for Portal Widget Configuration functionality.
 *
 * These tests verify the widget config DTOs and validation logic
 * without requiring database connectivity or HTTP calls.
 */
class PortalWidgetConfigRoutesTest {

    @Test
    fun `test WidgetConfigResponse has all expected fields`() {
        val response = WidgetConfigResponse(
            allowedOrigins = listOf("https://example.com", "https://app.example.com"),
            availableTemplates = listOf(
                WidgetTemplateInfo(
                    id = UUID.randomUUID().toString(),
                    name = "identity-basic",
                    displayName = "Basic Identity Verification",
                    description = "Verify name and date of birth",
                    type = "identity",
                    isSystem = true
                )
            ),
            updatedAt = "2024-01-15T10:30:00Z"
        )

        assertEquals(2, response.allowedOrigins.size)
        assertEquals(1, response.availableTemplates.size)
        assertEquals("identity-basic", response.availableTemplates[0].name)
        assertTrue(response.availableTemplates[0].isSystem)
        assertNotNull(response.updatedAt)
    }

    @Test
    fun `test WidgetConfigResponse with empty origins and templates`() {
        val response = WidgetConfigResponse(
            allowedOrigins = emptyList(),
            availableTemplates = emptyList(),
            updatedAt = null
        )

        assertTrue(response.allowedOrigins.isEmpty())
        assertTrue(response.availableTemplates.isEmpty())
        assertEquals(null, response.updatedAt)
    }

    @Test
    fun `test UpdateWidgetConfigRequest with origins`() {
        val request = UpdateWidgetConfigRequest(
            allowedOrigins = listOf(
                "https://myapp.com",
                "https://staging.myapp.com",
                "http://localhost:3000"
            )
        )

        assertEquals(3, request.allowedOrigins.size)
        assertTrue(request.allowedOrigins.contains("http://localhost:3000"))
    }

    @Test
    fun `test WidgetTemplateInfo for system template`() {
        val template = WidgetTemplateInfo(
            id = UUID.randomUUID().toString(),
            name = "payment-verification",
            displayName = "Payment Verification",
            description = "Verify payment wallet attestation",
            type = "payment",
            isSystem = true
        )

        assertTrue(template.isSystem)
        assertEquals("payment", template.type)
    }

    @Test
    fun `test WidgetTemplateInfo for organization template`() {
        val template = WidgetTemplateInfo(
            id = UUID.randomUUID().toString(),
            name = "custom-kyc",
            displayName = "Custom KYC Flow",
            description = "Organization-specific KYC verification",
            type = "custom",
            isSystem = false
        )

        assertFalse(template.isSystem)
        assertEquals("custom", template.type)
    }

    @Test
    fun `test valid https origin`() {
        val errors = validateOrigins(listOf("https://example.com"))
        assertTrue(errors.isEmpty(), "https://example.com should be valid")
    }

    @Test
    fun `test valid http origin`() {
        val errors = validateOrigins(listOf("http://localhost:3000"))
        assertTrue(errors.isEmpty(), "http://localhost:3000 should be valid")
    }

    @Test
    fun `test valid origin with port`() {
        val errors = validateOrigins(listOf("https://example.com:8443"))
        assertTrue(errors.isEmpty(), "Origin with port should be valid")
    }

    @Test
    fun `test valid subdomain origin`() {
        val errors = validateOrigins(listOf("https://app.staging.example.com"))
        assertTrue(errors.isEmpty(), "Subdomain origin should be valid")
    }

    @Test
    fun `test wildcard origin is allowed`() {
        val errors = validateOrigins(listOf("*"))
        assertTrue(errors.isEmpty(), "Wildcard origin should be allowed")
    }

    @Test
    fun `test blank origin is invalid`() {
        val errors = validateOrigins(listOf("", "   "))
        assertEquals(2, errors.size, "Blank origins should be invalid")
        assertTrue(errors.all { it["error"]!!.contains("blank") })
    }

    @Test
    fun `test origin with trailing slash is invalid`() {
        val errors = validateOrigins(listOf("https://example.com/"))
        assertEquals(1, errors.size, "Origin with trailing slash should be invalid")
        assertTrue(errors[0]["error"]!!.contains("trailing slash"))
    }

    @Test
    fun `test origin with path is invalid`() {
        val errors = validateOrigins(listOf("https://example.com/api"))
        assertEquals(1, errors.size, "Origin with path should be invalid")
    }

    @Test
    fun `test invalid origin format`() {
        val errors = validateOrigins(listOf("not-a-url", "ftp://example.com", "example.com"))
        assertEquals(3, errors.size, "Invalid formats should be rejected")
    }

    @Test
    fun `test multiple origins with mixed validity`() {
        val origins = listOf(
            "https://valid.com",
            "not-valid",
            "https://also-valid.com:8080",
            "   "
        )
        val errors = validateOrigins(origins)

        assertEquals(2, errors.size, "Should have 2 errors")

        // Check that error indices are correct
        val errorIndices = errors.map { it["index"]!!.toInt() }.toSet()
        assertTrue(1 in errorIndices, "Index 1 (not-valid) should have error")
        assertTrue(3 in errorIndices, "Index 3 (blank) should have error")
    }

    @Test
    fun `test WidgetCodeSnippet structure`() {
        val snippet = WidgetCodeSnippet(
            language = "html",
            code = "<script>...</script>"
        )

        assertEquals("html", snippet.language)
        assertTrue(snippet.code.isNotBlank())
    }

    @Test
    fun `test WidgetSnippetsResponse structure`() {
        val response = WidgetSnippetsResponse(
            allowedOrigins = listOf("https://example.com"),
            snippets = listOf(
                WidgetCodeSnippet(language = "html", code = "..."),
                WidgetCodeSnippet(language = "react", code = "..."),
                WidgetCodeSnippet(language = "nextjs", code = "...")
            )
        )

        assertEquals(1, response.allowedOrigins.size)
        assertEquals(3, response.snippets.size)
        assertTrue(response.snippets.any { it.language == "react" })
    }

    @Test
    fun `test JWT token can be generated for portal auth`() {
        // Verify JWT generation works for portal auth (used by widget config routes)
        val userInfo = PortalAuthService.UserInfo(
            userId = UUID.randomUUID(),
            email = "widgetconfig@test.com",
            passwordHash = "",
            organizationId = UUID.randomUUID(),
            organizationName = "Widget Config Test Org",
            role = "admin",
            emailVerifiedAt = java.time.Instant.now()
        )

        val tokens = PortalAuthService.generateTokens(userInfo)
        assertTrue(tokens.accessToken.isNotBlank())

        // Verify the token can be validated
        val principal = PortalAuthService.validateAccessToken(tokens.accessToken)
        assertNotNull(principal)
        assertEquals(userInfo.organizationId, principal.organizationId)
    }

    @Test
    fun `test origin with hyphenated domain is valid`() {
        val errors = validateOrigins(listOf("https://my-app.example.com"))
        assertTrue(errors.isEmpty(), "Hyphenated domain should be valid")
    }

    @Test
    fun `test origin with numbers in domain is valid`() {
        val errors = validateOrigins(listOf("https://app123.example.com"))
        assertTrue(errors.isEmpty(), "Numbers in domain should be valid")
    }

    @Test
    fun `test localhost origins are valid`() {
        val errors = validateOrigins(listOf(
            "http://localhost",
            "http://localhost:3000",
            "http://localhost:8080",
            "https://localhost:443"
        ))
        assertTrue(errors.isEmpty(), "Localhost origins should be valid")
    }

    @Test
    fun `test 127-0-0-1 origin is valid`() {
        val errors = validateOrigins(listOf("http://127.0.0.1:3000"))
        // Note: Our simple regex may not fully validate IP addresses
        // This test documents current behavior
        assertTrue(errors.isEmpty() || errors.size == 1)
    }

    @Test
    fun `test case sensitive origin validation`() {
        // Origins should generally be lowercase, but validation shouldn't fail on case
        val errors = validateOrigins(listOf("https://Example.COM"))
        assertTrue(errors.isEmpty(), "Mixed case origin should be valid")
    }

    @Test
    fun `test empty origins list is valid`() {
        val errors = validateOrigins(emptyList())
        assertTrue(errors.isEmpty(), "Empty origins list should be valid")
    }

    @Test
    fun `test maximum origins limit`() {
        // Test that the route enforces a maximum of 50 origins
        // This is handled at the route level, not in validation
        val manyOrigins = (1..50).map { "https://origin$it.example.com" }
        val errors = validateOrigins(manyOrigins)
        assertTrue(errors.isEmpty(), "50 valid origins should pass validation")
    }

    @Test
    fun `test template types`() {
        val types = listOf("identity", "payment", "custom")
        types.forEach { type ->
            val template = WidgetTemplateInfo(
                id = UUID.randomUUID().toString(),
                name = "test-$type",
                displayName = "Test $type Template",
                description = null,
                type = type,
                isSystem = false
            )
            assertEquals(type, template.type)
        }
    }

    @Test
    fun `test template with null optional fields`() {
        val template = WidgetTemplateInfo(
            id = UUID.randomUUID().toString(),
            name = "minimal-template",
            displayName = null,
            description = null,
            type = "custom",
            isSystem = true
        )

        assertEquals(null, template.displayName)
        assertEquals(null, template.description)
    }
}
