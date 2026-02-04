package id.walt.issuer.issuance

import id.walt.crypto.keys.Key
import id.walt.crypto.keys.KeyManager
import id.walt.crypto.keys.KeyType
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

private val logger = KotlinLogging.logger {}

/**
 * Service for validating issuer keys before they are stored in issuance sessions.
 *
 * Prevents invalid keys from corrupting the JWKS endpoint and provides clear
 * error messages when key validation fails.
 */
object KeyValidationService {

    /**
     * Validates an issuer key JSON object.
     *
     * Performs the following checks:
     * 1. Key can be parsed and resolved by KeyManager
     * 2. For EC keys: curve coordinates are valid points on the curve
     * 3. Key type matches the declared algorithm
     * 4. Key can perform sign/verify operations
     *
     * @param keyJson The issuer key as a JsonObject (with "type" field)
     * @return Result.success if valid, Result.failure with descriptive error if invalid
     */
    suspend fun validateIssuerKey(keyJson: JsonObject): Result<Key> {
        val keyType = keyJson["type"]?.jsonPrimitive?.content

        if (keyType == null) {
            return Result.failure(KeyValidationException(
                error = "missing_key_type",
                description = "Key JSON must contain a 'type' field",
                details = mapOf("provided_fields" to keyJson.keys.joinToString())
            ))
        }

        // Step 1: Try to parse and resolve the key
        val key = try {
            KeyManager.resolveSerializedKey(keyJson)
        } catch (e: Exception) {
            val errorMessage = e.message ?: "Unknown error"

            // Provide specific error details for common EC key issues
            val (error, description, details) = when {
                errorMessage.contains("coordinates are not on the", ignoreCase = true) -> {
                    val curve = extractCurveFromError(errorMessage) ?: extractCurveFromKey(keyJson)
                    Triple(
                        "invalid_ec_coordinates",
                        "EC key validation failed: coordinates not on $curve curve",
                        mapOf(
                            "key_type" to "EC",
                            "curve" to (curve ?: "unknown"),
                            "validation_step" to "curve_coordinates"
                        )
                    )
                }
                errorMessage.contains("Invalid EC JWK", ignoreCase = true) -> {
                    Triple(
                        "invalid_ec_key",
                        "EC key validation failed: $errorMessage",
                        mapOf(
                            "key_type" to "EC",
                            "validation_step" to "key_parsing"
                        )
                    )
                }
                errorMessage.contains("Invalid RSA", ignoreCase = true) -> {
                    Triple(
                        "invalid_rsa_key",
                        "RSA key validation failed: $errorMessage",
                        mapOf(
                            "key_type" to "RSA",
                            "validation_step" to "key_parsing"
                        )
                    )
                }
                else -> {
                    Triple(
                        "key_parse_error",
                        "Failed to parse key: $errorMessage",
                        mapOf(
                            "key_backend" to keyType,
                            "validation_step" to "key_parsing"
                        )
                    )
                }
            }

            return Result.failure(KeyValidationException(error, description, details))
        }

        // Step 2: Verify key type matches expected algorithm for signing
        val keyTypeResult = validateKeyTypeForSigning(key)
        if (keyTypeResult.isFailure) {
            return Result.failure(keyTypeResult.exceptionOrNull()!!)
        }

        // Step 3: Test sign/verify cycle to ensure key is usable
        val signVerifyResult = testSignVerify(key)
        if (signVerifyResult.isFailure) {
            return Result.failure(signVerifyResult.exceptionOrNull()!!)
        }

        val keyId = key.getKeyId()
        logger.debug { "Key validation successful: $keyId" }
        return Result.success(key)
    }

    /**
     * Validates that the key type is suitable for credential signing.
     */
    private suspend fun validateKeyTypeForSigning(key: Key): Result<Unit> {
        val keyType = key.keyType

        // Ensure key type is one of the supported signing algorithms
        val supportedTypes = setOf(
            KeyType.Ed25519,
            KeyType.secp256r1,  // P-256 / ES256
            KeyType.secp384r1,  // P-384 / ES384
            KeyType.secp521r1,  // P-521 / ES512
            KeyType.secp256k1,  // ES256K
            KeyType.RSA         // RS256, RS384, RS512
        )

        if (keyType !in supportedTypes) {
            return Result.failure(KeyValidationException(
                error = "unsupported_key_type",
                description = "Key type '$keyType' is not supported for credential signing",
                details = mapOf(
                    "key_type" to keyType.name,
                    "supported_types" to supportedTypes.map { it.name }.joinToString(),
                    "validation_step" to "key_type_check"
                )
            ))
        }

        return Result.success(Unit)
    }

    /**
     * Tests that the key can perform sign and verify operations.
     */
    private suspend fun testSignVerify(key: Key): Result<Unit> {
        val testData = "key-validation-test".encodeToByteArray()

        return try {
            // Only test if key has private component (can sign)
            if (key.hasPrivateKey) {
                val signature = key.signRaw(testData) as ByteArray
                val publicKey = key.getPublicKey()
                val verified = publicKey.verifyRaw(signature, testData)

                if (!verified.isSuccess) {
                    return Result.failure(KeyValidationException(
                        error = "sign_verify_failed",
                        description = "Key sign/verify cycle failed: signature verification returned false",
                        details = mapOf(
                            "key_type" to key.keyType.name,
                            "validation_step" to "sign_verify_test"
                        )
                    ))
                }
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(KeyValidationException(
                error = "sign_verify_error",
                description = "Key sign/verify test failed: ${e.message}",
                details = mapOf(
                    "key_type" to key.keyType.name,
                    "validation_step" to "sign_verify_test",
                    "error_class" to (e::class.simpleName ?: "Unknown")
                )
            ))
        }
    }

    /**
     * Extracts curve name from error messages like "coordinates are not on the P-256 curve"
     */
    private fun extractCurveFromError(errorMessage: String): String? {
        val curvePatterns = listOf("P-256", "P-384", "P-521", "secp256k1")
        return curvePatterns.find { errorMessage.contains(it, ignoreCase = true) }
    }

    /**
     * Extracts curve from key JSON if available
     */
    private fun extractCurveFromKey(keyJson: JsonObject): String? {
        val jwk = keyJson["jwk"]?.jsonObject ?: return null
        return jwk["crv"]?.jsonPrimitive?.content
    }
}

/**
 * Exception thrown when key validation fails.
 * Contains structured error information for API responses.
 */
class KeyValidationException(
    val error: String,
    val description: String,
    val details: Map<String, String> = emptyMap()
) : Exception(description) {

    fun toErrorResponse(): Map<String, Any> = buildMap {
        put("error", error)
        put("error_description", description)
        if (details.isNotEmpty()) {
            put("details", details)
        }
    }
}
