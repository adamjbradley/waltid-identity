package id.walt.verifyapi

import id.walt.verifyapi.widget.ClientTokenService
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Nested
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Unit tests for ClientTokenService.
 *
 * These tests verify the cryptographic operations and validation logic
 * without requiring database connectivity. Database-dependent tests
 * should be in integration tests.
 */
class ClientTokenServiceTest {

    @Nested
    inner class TokenHashing {

        @Test
        fun `hash is consistent for same token`() {
            val token = "ct_abc123.signature"
            val hash1 = ClientTokenService.hashToken(token)
            val hash2 = ClientTokenService.hashToken(token)
            assertEquals(hash1, hash2, "Hashing the same token should produce identical hashes")
        }

        @Test
        fun `hash is 64 characters for SHA-256`() {
            val hash = ClientTokenService.hashToken("any_token")
            assertEquals(64, hash.length, "SHA-256 hash should be 64 hex characters")
        }

        @Test
        fun `hash contains only lowercase hex characters`() {
            val hash = ClientTokenService.hashToken("ct_testtoken123.sig")
            assertTrue(
                hash.all { it in '0'..'9' || it in 'a'..'f' },
                "Hash should contain only lowercase hex characters"
            )
        }

        @Test
        fun `different tokens produce different hashes`() {
            val hash1 = ClientTokenService.hashToken("token1")
            val hash2 = ClientTokenService.hashToken("token2")
            assertNotEquals(hash1, hash2, "Different tokens should produce different hashes")
        }

        @Test
        fun `known SHA-256 hash value`() {
            // SHA-256 of "test" is known to be:
            // 9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08
            val hash = ClientTokenService.hashToken("test")
            assertEquals(
                "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08",
                hash,
                "Hash of 'test' should match known SHA-256 value"
            )
        }
    }

    @Nested
    inner class PayloadSigning {

        @Test
        fun `signature is consistent for same payload`() {
            val payload = "test_payload_123"
            val sig1 = ClientTokenService.signPayload(payload)
            val sig2 = ClientTokenService.signPayload(payload)
            assertEquals(sig1, sig2, "Signing the same payload should produce identical signatures")
        }

        @Test
        fun `signature is URL-safe base64`() {
            val signature = ClientTokenService.signPayload("test_payload")
            // URL-safe base64 should not contain +, /, or =
            assertFalse(signature.contains('+'), "Signature should not contain '+'")
            assertFalse(signature.contains('/'), "Signature should not contain '/'")
            assertFalse(signature.contains('='), "Signature should not contain '=' (no padding)")
        }

        @Test
        fun `different payloads produce different signatures`() {
            val sig1 = ClientTokenService.signPayload("payload1")
            val sig2 = ClientTokenService.signPayload("payload2")
            assertNotEquals(sig1, sig2, "Different payloads should produce different signatures")
        }

        @Test
        fun `signature has expected length for HMAC-SHA256`() {
            val signature = ClientTokenService.signPayload("any_payload")
            // HMAC-SHA256 produces 32 bytes, base64 encoded without padding = 43 chars
            assertEquals(43, signature.length, "HMAC-SHA256 signature in URL-safe base64 should be 43 characters")
        }
    }

    @Nested
    inner class NonceGeneration {

        @Test
        fun `nonce is 16 characters`() {
            val nonce = ClientTokenService.generateNonce()
            assertEquals(16, nonce.length, "Nonce should be 16 hex characters")
        }

        @Test
        fun `nonce contains only hex characters`() {
            val nonce = ClientTokenService.generateNonce()
            assertTrue(
                nonce.all { it in '0'..'9' || it in 'a'..'f' },
                "Nonce should contain only lowercase hex characters"
            )
        }

        @Test
        fun `nonces are unique`() {
            val nonces = (1..100).map { ClientTokenService.generateNonce() }.toSet()
            assertEquals(100, nonces.size, "Generated nonces should be unique")
        }
    }

    @Nested
    inner class OriginValidation {

        @Test
        fun `exact match allows origin`() {
            assertTrue(
                ClientTokenService.isOriginAllowed(
                    "https://shop.example.com",
                    listOf("https://shop.example.com")
                ),
                "Exact match should allow origin"
            )
        }

        @Test
        fun `exact match rejects non-matching origin`() {
            assertFalse(
                ClientTokenService.isOriginAllowed(
                    "https://other.example.com",
                    listOf("https://shop.example.com")
                ),
                "Non-matching origin should be rejected"
            )
        }

        @Test
        fun `wildcard allows any origin`() {
            assertTrue(
                ClientTokenService.isOriginAllowed(
                    "https://anything.com",
                    listOf("*")
                ),
                "Wildcard should allow any origin"
            )
        }

        @Test
        fun `subdomain wildcard allows matching subdomain`() {
            assertTrue(
                ClientTokenService.isOriginAllowed(
                    "https://shop.example.com",
                    listOf("*.example.com")
                ),
                "Subdomain wildcard should match subdomain"
            )
        }

        @Test
        fun `subdomain wildcard allows nested subdomain`() {
            assertTrue(
                ClientTokenService.isOriginAllowed(
                    "https://api.shop.example.com",
                    listOf("*.example.com")
                ),
                "Subdomain wildcard should match nested subdomain"
            )
        }

        @Test
        fun `subdomain wildcard rejects different domain`() {
            assertFalse(
                ClientTokenService.isOriginAllowed(
                    "https://shop.other.com",
                    listOf("*.example.com")
                ),
                "Subdomain wildcard should reject different domain"
            )
        }

        @Test
        fun `subdomain wildcard allows bare domain`() {
            assertTrue(
                ClientTokenService.isOriginAllowed(
                    "https://example.com",
                    listOf("*.example.com")
                ),
                "Subdomain wildcard should allow bare domain"
            )
        }

        @Test
        fun `multiple allowed origins checks all`() {
            assertTrue(
                ClientTokenService.isOriginAllowed(
                    "https://shop.example.com",
                    listOf("https://other.com", "https://shop.example.com", "https://third.com")
                ),
                "Should match if any origin in list matches"
            )
        }

        @Test
        fun `empty allowed origins rejects all in isOriginAllowed`() {
            // Note: In the service, empty list means "allow all", but isOriginAllowed
            // is called only when the list is not empty
            assertFalse(
                ClientTokenService.isOriginAllowed(
                    "https://shop.example.com",
                    emptyList()
                ),
                "Empty list should reject (service handles empty list differently)"
            )
        }

        @Test
        fun `case sensitive origin matching`() {
            assertFalse(
                ClientTokenService.isOriginAllowed(
                    "https://SHOP.example.com",
                    listOf("https://shop.example.com")
                ),
                "Origin matching should be case sensitive"
            )
        }

        @Test
        fun `port number is part of origin`() {
            assertFalse(
                ClientTokenService.isOriginAllowed(
                    "https://shop.example.com:8080",
                    listOf("https://shop.example.com")
                ),
                "Port number should be part of origin"
            )
        }

        @Test
        fun `same origin with port matches`() {
            assertTrue(
                ClientTokenService.isOriginAllowed(
                    "https://shop.example.com:8080",
                    listOf("https://shop.example.com:8080")
                ),
                "Same origin with port should match"
            )
        }
    }

    @Nested
    inner class TokenLifetimeConstants {

        @Test
        fun `default lifetime is 15 minutes`() {
            assertEquals(
                15 * 60 * 1000L,
                ClientTokenService.DEFAULT_TOKEN_LIFETIME.inWholeMilliseconds,
                "Default token lifetime should be 15 minutes"
            )
        }

        @Test
        fun `max lifetime is 24 hours`() {
            assertEquals(
                24 * 60 * 60 * 1000L,
                ClientTokenService.MAX_TOKEN_LIFETIME.inWholeMilliseconds,
                "Max token lifetime should be 24 hours"
            )
        }

        @Test
        fun `min lifetime is 1 minute`() {
            assertEquals(
                60 * 1000L,
                ClientTokenService.MIN_TOKEN_LIFETIME.inWholeMilliseconds,
                "Min token lifetime should be 1 minute"
            )
        }
    }
}
