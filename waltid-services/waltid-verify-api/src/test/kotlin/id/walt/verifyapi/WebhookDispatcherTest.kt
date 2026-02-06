package id.walt.verifyapi

import id.walt.verifyapi.webhook.WebhookDispatcher
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Unit tests for WebhookDispatcher HMAC signature generation.
 *
 * These tests verify the HMAC-SHA256 signature generation used for
 * webhook payload verification without requiring network connectivity.
 */
class WebhookDispatcherTest {

    @Test
    fun `test signature is consistent`() {
        val secret = "test_secret"
        val data = "1234567890.{\"test\":true}"
        val sig1 = WebhookDispatcher.sign(secret, data)
        val sig2 = WebhookDispatcher.sign(secret, data)
        assertEquals(sig1, sig2, "Signing the same data with same secret should produce identical signatures")
    }

    @Test
    fun `test signature is 64 characters`() {
        val sig = WebhookDispatcher.sign("secret", "data")
        assertEquals(64, sig.length, "HMAC-SHA256 signature should be 64 hex characters")
    }

    @Test
    fun `test signature contains only lowercase hex characters`() {
        val sig = WebhookDispatcher.sign("webhook_secret_123", "1234567890.{\"event\":\"test\"}")
        assertTrue(sig.all { it in '0'..'9' || it in 'a'..'f' }, "Signature should contain only lowercase hex characters")
    }

    @Test
    fun `test different secrets produce different signatures`() {
        val data = "same_data"
        val sig1 = WebhookDispatcher.sign("secret1", data)
        val sig2 = WebhookDispatcher.sign("secret2", data)
        assertNotEquals(sig1, sig2, "Different secrets should produce different signatures")
    }

    @Test
    fun `test different data produces different signatures`() {
        val secret = "same_secret"
        val sig1 = WebhookDispatcher.sign(secret, "data1")
        val sig2 = WebhookDispatcher.sign(secret, "data2")
        assertNotEquals(sig1, sig2, "Different data should produce different signatures")
    }

    @Test
    fun `test signature format matches webhook payload pattern`() {
        val secret = "whsec_test123"
        val timestamp = "1704067200"
        val body = """{"event":"verification.completed","sessionId":"vs_abc123"}"""
        val data = "$timestamp.$body"

        val sig = WebhookDispatcher.sign(secret, data)

        assertEquals(64, sig.length, "Signature should be 64 characters")
        assertTrue(sig.all { it in '0'..'9' || it in 'a'..'f' }, "Signature should be hex encoded")
    }

    @Test
    fun `test empty data can be signed`() {
        val sig = WebhookDispatcher.sign("secret", "")
        assertEquals(64, sig.length, "Empty data should still produce 64-character signature")
    }

    @Test
    fun `test unicode characters in data are handled correctly`() {
        val sig = WebhookDispatcher.sign("secret", "data with unicode: \u00e9\u00e8\u00ea")
        assertEquals(64, sig.length, "Unicode data should still produce 64-character signature")
    }

    @Test
    fun `test signature is case sensitive for secret`() {
        val data = "test_data"
        val sig1 = WebhookDispatcher.sign("Secret", data)
        val sig2 = WebhookDispatcher.sign("secret", data)
        assertNotEquals(sig1, sig2, "Signing should be case sensitive for secret")
    }

    @Test
    fun `test signature is case sensitive for data`() {
        val secret = "test_secret"
        val sig1 = WebhookDispatcher.sign(secret, "Data")
        val sig2 = WebhookDispatcher.sign(secret, "data")
        assertNotEquals(sig1, sig2, "Signing should be case sensitive for data")
    }
}
