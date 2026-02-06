package id.walt.verifyapi

import id.walt.verifyapi.auth.ApiKeyAuthProvider
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Unit tests for API key authentication functionality.
 *
 * These tests verify the SHA-256 hashing behavior used for
 * secure API key storage without requiring database connectivity.
 */
class ApiKeyAuthTest {

    @Test
    fun `test API key hash is consistent`() {
        val key = "vfy_test_abc123"
        val hash1 = ApiKeyAuthProvider.hashApiKey(key)
        val hash2 = ApiKeyAuthProvider.hashApiKey(key)
        assertEquals(hash1, hash2, "Hashing the same key should produce identical hashes")
    }

    @Test
    fun `test hash is 64 characters for SHA-256`() {
        val hash = ApiKeyAuthProvider.hashApiKey("any_key")
        assertEquals(64, hash.length, "SHA-256 hash should be 64 hex characters")
    }

    @Test
    fun `test hash contains only lowercase hex characters`() {
        val hash = ApiKeyAuthProvider.hashApiKey("vfy_live_testkey123")
        assertTrue(hash.all { it in '0'..'9' || it in 'a'..'f' }, "Hash should contain only lowercase hex characters")
    }

    @Test
    fun `test different keys produce different hashes`() {
        val hash1 = ApiKeyAuthProvider.hashApiKey("key1")
        val hash2 = ApiKeyAuthProvider.hashApiKey("key2")
        assertNotEquals(hash1, hash2, "Different keys should produce different hashes")
    }

    @Test
    fun `test empty key can be hashed`() {
        val hash = ApiKeyAuthProvider.hashApiKey("")
        assertEquals(64, hash.length, "Empty key should still produce 64-character hash")
    }

    @Test
    fun `test hash is case sensitive`() {
        val hash1 = ApiKeyAuthProvider.hashApiKey("Key")
        val hash2 = ApiKeyAuthProvider.hashApiKey("key")
        assertNotEquals(hash1, hash2, "Hashing should be case sensitive")
    }

    @Test
    fun `test known SHA-256 hash value`() {
        // SHA-256 of "test" is known to be:
        // 9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08
        val hash = ApiKeyAuthProvider.hashApiKey("test")
        assertEquals(
            "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08",
            hash,
            "Hash of 'test' should match known SHA-256 value"
        )
    }
}
