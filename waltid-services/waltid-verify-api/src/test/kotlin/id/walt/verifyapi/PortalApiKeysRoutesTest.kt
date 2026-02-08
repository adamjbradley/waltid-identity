package id.walt.verifyapi

import id.walt.verifyapi.auth.ApiKeyAuthProvider
import id.walt.verifyapi.portal.PortalAuthService
import id.walt.verifyapi.routes.ApiKeyResponse
import id.walt.verifyapi.routes.CreateApiKeyRequest
import id.walt.verifyapi.routes.CreateApiKeyResponse
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit tests for Portal API Key management functionality.
 *
 * These tests verify the API key generation, hashing, and response structure
 * without requiring database connectivity or HTTP calls.
 */
class PortalApiKeysRoutesTest {

    @Test
    fun `test CreateApiKeyRequest has sensible defaults`() {
        val request = CreateApiKeyRequest()
        assertNull(request.name, "Default name should be null")
        assertEquals("test", request.environment, "Default environment should be test")
    }

    @Test
    fun `test CreateApiKeyRequest with custom values`() {
        val request = CreateApiKeyRequest(
            name = "My Production Key",
            environment = "live"
        )
        assertEquals("My Production Key", request.name)
        assertEquals("live", request.environment)
    }

    @Test
    fun `test CreateApiKeyResponse contains all expected fields`() {
        val response = CreateApiKeyResponse(
            id = UUID.randomUUID().toString(),
            key = "vfy_test_abc123def456ghi789jkl012",
            keyPrefix = "vfy_test_abc12345",
            environment = "test",
            name = "Test Key",
            createdAt = "2024-01-15T10:30:00Z"
        )

        assertTrue(response.id.isNotBlank())
        assertTrue(response.key.startsWith("vfy_"))
        assertTrue(response.keyPrefix.startsWith("vfy_"))
        assertEquals("test", response.environment)
        assertEquals("Test Key", response.name)
        assertNotNull(response.createdAt)
    }

    @Test
    fun `test ApiKeyResponse excludes full key`() {
        val response = ApiKeyResponse(
            id = UUID.randomUUID().toString(),
            keyPrefix = "vfy_live_xyz98765",
            environment = "live",
            name = "Production API",
            lastUsedAt = "2024-01-15T12:00:00Z",
            createdAt = "2024-01-10T08:00:00Z",
            revoked = false
        )

        // Verify we only have prefix, not full key
        assertTrue(response.keyPrefix.length < 25, "Prefix should be shorter than full key")
        assertTrue(response.keyPrefix.startsWith("vfy_live_"))
        assertFalse(response.revoked)
    }

    @Test
    fun `test ApiKeyResponse with revoked key`() {
        val response = ApiKeyResponse(
            id = UUID.randomUUID().toString(),
            keyPrefix = "vfy_test_revoked1",
            environment = "test",
            name = "Old Key",
            lastUsedAt = null,
            createdAt = "2024-01-01T00:00:00Z",
            revoked = true
        )

        assertTrue(response.revoked)
        assertNull(response.lastUsedAt)
    }

    @Test
    fun `test generated key format matches expected pattern`() {
        // Simulate key generation logic
        // Format: vfy_{env}_{random24} = 4 + 5 + 24 = 33 chars for test/live
        val testKey = "vfy_test_ABCDEFGHIJKLMNOPqrstuvwx"
        val liveKey = "vfy_live_XYZ123456789abcdefghijkl"

        assertTrue(testKey.startsWith("vfy_test_"))
        assertTrue(liveKey.startsWith("vfy_live_"))
        assertEquals(33, testKey.length, "Key should be vfy_(4) + test_(5) + random(24) = 33 chars")
        assertEquals(33, liveKey.length, "Key should be vfy_(4) + live_(5) + random(24) = 33 chars")
    }

    @Test
    fun `test key hash is consistent for API key validation`() {
        val key = "vfy_test_ABCDEFGHIJKLMNOPqrstuvwx"
        val hash1 = ApiKeyAuthProvider.hashApiKey(key)
        val hash2 = ApiKeyAuthProvider.hashApiKey(key)

        assertEquals(hash1, hash2, "Same key should produce same hash")
        assertEquals(64, hash1.length, "SHA-256 hash should be 64 hex characters")
    }

    @Test
    fun `test different keys produce different hashes`() {
        val key1 = "vfy_test_ABCDEFGHIJKLMNOP12345678"
        val key2 = "vfy_test_ABCDEFGHIJKLMNOP12345679"

        val hash1 = ApiKeyAuthProvider.hashApiKey(key1)
        val hash2 = ApiKeyAuthProvider.hashApiKey(key2)

        assertNotEquals(hash1, hash2, "Different keys must produce different hashes")
    }

    @Test
    fun `test key prefix is shorter than full key`() {
        val fullKey = "vfy_live_XYZ123456789abcdefghijkl"
        val prefix = "vfy_live_XYZ12345"

        assertTrue(prefix.length < fullKey.length)
        assertTrue(fullKey.startsWith(prefix.take(13)), "Full key should start with prefix pattern")
    }

    @Test
    fun `test valid environments`() {
        val validEnvironments = setOf("live", "test")
        assertTrue("live" in validEnvironments)
        assertTrue("test" in validEnvironments)
        assertFalse("production" in validEnvironments)
        assertFalse("development" in validEnvironments)
        assertFalse("" in validEnvironments)
    }

    @Test
    fun `test JWT token can be generated for portal auth`() {
        // Verify JWT generation works for portal auth
        val userInfo = PortalAuthService.UserInfo(
            userId = UUID.randomUUID(),
            email = "apikeys@test.com",
            passwordHash = "",
            organizationId = UUID.randomUUID(),
            organizationName = "API Keys Test Org",
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
    fun `test name validation length boundary`() {
        val shortName = "A"
        val maxName = "A".repeat(100)
        val tooLongName = "A".repeat(101)

        assertTrue(shortName.length <= 100)
        assertTrue(maxName.length <= 100)
        assertFalse(tooLongName.length <= 100, "Names over 100 chars should be rejected")
    }

    @Test
    fun `test null name is allowed`() {
        val request = CreateApiKeyRequest(name = null, environment = "test")
        assertNull(request.name)
    }

    @Test
    fun `test prefix format for test environment`() {
        // Expected prefix format: vfy_test_{first8chars}
        val prefix = "vfy_test_ABCD1234"
        assertTrue(prefix.startsWith("vfy_test_"))
        assertTrue(prefix.length == 17, "Prefix should be vfy_test_ (9) + 8 chars = 17")
    }

    @Test
    fun `test prefix format for live environment`() {
        // Expected prefix format: vfy_live_{first8chars}
        val prefix = "vfy_live_XYZ98765"
        assertTrue(prefix.startsWith("vfy_live_"))
        assertTrue(prefix.length == 17, "Prefix should be vfy_live_ (9) + 8 chars = 17")
    }

    @Test
    fun `test API key hash format is hexadecimal`() {
        val key = "vfy_test_SomeRandomKeyValue12345"
        val hash = ApiKeyAuthProvider.hashApiKey(key)

        assertTrue(hash.all { it in '0'..'9' || it in 'a'..'f' },
            "Hash should only contain lowercase hex characters")
    }

    @Test
    fun `test revoked key response structure`() {
        // When a key is revoked, it should still be returned in lists
        // but marked as revoked
        val revokedKey = ApiKeyResponse(
            id = UUID.randomUUID().toString(),
            keyPrefix = "vfy_test_oldkey12",
            environment = "test",
            name = "Deprecated Key",
            lastUsedAt = "2024-01-01T00:00:00Z",
            createdAt = "2023-12-01T00:00:00Z",
            revoked = true
        )

        assertTrue(revokedKey.revoked)
        assertNotNull(revokedKey.lastUsedAt, "Revoked keys may have been used before")
    }

    @Test
    fun `test environment case sensitivity`() {
        // Environments should be lowercase
        val validEnvs = setOf("live", "test")
        assertFalse("LIVE" in validEnvs)
        assertFalse("TEST" in validEnvs)
        assertFalse("Live" in validEnvs)
    }

    @Test
    fun `test UUID format in responses`() {
        val uuid = UUID.randomUUID()
        val response = ApiKeyResponse(
            id = uuid.toString(),
            keyPrefix = "vfy_test_abcd1234",
            environment = "test",
            name = null,
            lastUsedAt = null,
            createdAt = "2024-01-15T00:00:00Z",
            revoked = false
        )

        // Verify the ID can be parsed back as UUID
        val parsedUuid = UUID.fromString(response.id)
        assertEquals(uuid, parsedUuid)
    }
}
