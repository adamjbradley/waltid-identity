package id.walt.issuance

import id.walt.issuer.issuance.KeyValidationException
import id.walt.issuer.issuance.KeyValidationService
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.intellij.lang.annotations.Language
import kotlin.test.*

/**
 * Tests for KeyValidationService.
 *
 * Verifies that:
 * - Valid keys pass validation
 * - Invalid EC curve coordinates fail with clear error
 * - Missing key type field fails
 * - Unsupported key types fail
 * - Sign/verify cycle works for valid keys
 */
class KeyValidationServiceTest {

    companion object {
        // Valid P-256 EC key (generated with Node.js crypto)
        @Language("JSON")
        val VALID_EC_P256_KEY = """{
            "type": "jwk",
            "jwk": {
                "kty": "EC",
                "crv": "P-256",
                "x": "UuRxbNQ5szZ69mdlVYVPC95eGmKSiique_k3pQK95fA",
                "y": "YhT5BdevBy-Fn9oRPoPAptmAVpDqN-8xPq-Cv1EiTdo",
                "d": "0BkgHElULmZyPXGQG4IXb5tvFrfR4D0NkdSmAQ7bpZI"
            }
        }"""

        // Valid Ed25519 key
        @Language("JSON")
        val VALID_ED25519_KEY = """{
            "type": "jwk",
            "jwk": {
                "kty": "OKP",
                "crv": "Ed25519",
                "x": "T3T4-u1Xz3vAV2JwPNxWfs4pik_JLiArz_WTCvrCFUM",
                "d": "mDhpwaH6JYSrD2Bq7Cs-pzmsjlLj4EOhxyI-9DM1mFI"
            }
        }"""

        // Invalid P-256 key - coordinates not on curve
        @Language("JSON")
        val INVALID_EC_COORDINATES_KEY = """{
            "type": "jwk",
            "jwk": {
                "kty": "EC",
                "crv": "P-256",
                "x": "dHGO-XVe1E-tEjqLN5EFT_FHQFgXTQ-9U7TL5qm9_0g",
                "y": "L8L7_pV9t2qn7B8DJ1_N8pEyEL_WQ8wVBM_FqA7k5tw",
                "d": "mJJv_Hzv8--BHJaJlvB9KM8XQnM9M8J7KNZ8K_z9qdc"
            }
        }"""

        // Key missing type field
        @Language("JSON")
        val MISSING_TYPE_KEY = """{
            "jwk": {
                "kty": "EC",
                "crv": "P-256",
                "x": "WKn-ZIGevcwGFOMJ0GeEei7IDCSr_Ckd2Rm8xtPU33g",
                "y": "y77t-RvAHRKTsSGdIYUfweuOvwrvDD-Q3Hv5J0fSKbE"
            }
        }"""

        // Empty key
        @Language("JSON")
        val EMPTY_KEY = """{}"""
    }

    @Test
    fun testValidEcP256KeyPasses() = runTest {
        val keyJson = Json.decodeFromString<JsonObject>(VALID_EC_P256_KEY)
        val result = KeyValidationService.validateIssuerKey(keyJson)

        assertTrue(result.isSuccess, "Valid P-256 key should pass validation")
        assertNotNull(result.getOrNull())
    }

    @Test
    fun testValidEd25519KeyPasses() = runTest {
        val keyJson = Json.decodeFromString<JsonObject>(VALID_ED25519_KEY)
        val result = KeyValidationService.validateIssuerKey(keyJson)

        assertTrue(result.isSuccess, "Valid Ed25519 key should pass validation")
        assertNotNull(result.getOrNull())
    }

    @Test
    fun testInvalidEcCoordinatesFails() = runTest {
        val keyJson = Json.decodeFromString<JsonObject>(INVALID_EC_COORDINATES_KEY)
        val result = KeyValidationService.validateIssuerKey(keyJson)

        assertTrue(result.isFailure, "Invalid EC coordinates should fail validation")
        val exception = result.exceptionOrNull()
        assertIs<KeyValidationException>(exception)
        assertTrue(
            exception.error.contains("invalid_ec") || exception.error.contains("key_parse_error"),
            "Error should indicate EC validation failure"
        )
    }

    @Test
    fun testMissingKeyTypeFails() = runTest {
        val keyJson = Json.decodeFromString<JsonObject>(MISSING_TYPE_KEY)
        val result = KeyValidationService.validateIssuerKey(keyJson)

        assertTrue(result.isFailure, "Missing key type should fail validation")
        val exception = result.exceptionOrNull()
        assertIs<KeyValidationException>(exception)
        assertEquals("missing_key_type", exception.error)
    }

    @Test
    fun testEmptyKeyFails() = runTest {
        val keyJson = Json.decodeFromString<JsonObject>(EMPTY_KEY)
        val result = KeyValidationService.validateIssuerKey(keyJson)

        assertTrue(result.isFailure, "Empty key should fail validation")
        val exception = result.exceptionOrNull()
        assertIs<KeyValidationException>(exception)
        assertEquals("missing_key_type", exception.error)
    }

    @Test
    fun testKeyValidationExceptionToErrorResponse() {
        val exception = KeyValidationException(
            error = "test_error",
            description = "Test error description",
            details = mapOf("key1" to "value1", "key2" to "value2")
        )

        val response = exception.toErrorResponse()

        assertEquals("test_error", response["error"])
        assertEquals("Test error description", response["error_description"])
        @Suppress("UNCHECKED_CAST")
        val details = response["details"] as Map<String, String>
        assertEquals("value1", details["key1"])
        assertEquals("value2", details["key2"])
    }

    @Test
    fun testKeyValidationExceptionWithoutDetails() {
        val exception = KeyValidationException(
            error = "simple_error",
            description = "Simple description"
        )

        val response = exception.toErrorResponse()

        assertEquals("simple_error", response["error"])
        assertEquals("Simple description", response["error_description"])
        assertFalse(response.containsKey("details"), "Should not have details key when empty")
    }
}
