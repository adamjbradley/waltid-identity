package id.walt.oid4vc

import id.walt.oid4vc.responses.CredentialResponse
import kotlinx.serialization.json.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for Draft 13+ credential response normalization.
 *
 * Draft 13+ issuers return `{"credentials": [{"credential": "eyJ..."}], ...}` while
 * older drafts return `{"credential": "eyJ...", ...}`. The normalization layer in
 * [OpenID4VCI.normalizeDraft13CredentialResponse] bridges this gap so that
 * [CredentialResponse.fromJSON] always receives the flat format it expects.
 */
class Draft13CredentialResponseTest {

    // -------------------------------------------------------------------------
    // Older-draft (flat format) — should pass through unchanged
    // -------------------------------------------------------------------------

    @Test
    fun testOlderDraftFlatCredentialPassesThrough() {
        val json = buildJsonObject {
            put("format", "jwt_vc_json")
            put("credential", "eyJhbGciOi...header.payload.signature")
            put("c_nonce", "nonce-123")
            put("c_nonce_expires_in", 300)
        }

        val normalized = OpenID4VCI.normalizeDraft13CredentialResponse(json)

        // Should be the exact same object (identity check — no copy made)
        assertTrue(json === normalized, "Flat-format JSON should pass through without copying")
    }

    @Test
    fun testOlderDraftFlatCredentialDeserializes() {
        val json = buildJsonObject {
            put("format", "jwt_vc_json")
            put("credential", "eyJhbGciOi...header.payload.signature")
            put("c_nonce", "nonce-123")
            put("c_nonce_expires_in", 300)
        }

        val response = CredentialResponse.fromJSON(json)

        assertTrue(response.isSuccess)
        assertNotNull(response.credential)
        assertEquals("eyJhbGciOi...header.payload.signature", response.credential!!.jsonPrimitive.content)
        assertEquals("nonce-123", response.cNonce)
    }

    // -------------------------------------------------------------------------
    // Draft 13+ credentials array with nested JsonObject
    // -------------------------------------------------------------------------

    @Test
    fun testDraft13CredentialsArrayWithNestedObject() {
        val json = buildJsonObject {
            put("credentials", buildJsonArray {
                add(buildJsonObject { put("credential", "eyJ...nested-jwt") })
            })
            put("c_nonce", "draft13-nonce")
            put("c_nonce_expires_in", 300)
        }

        val normalized = OpenID4VCI.normalizeDraft13CredentialResponse(json)

        assertEquals("eyJ...nested-jwt", normalized["credential"]?.jsonPrimitive?.content)
        assertEquals("draft13-nonce", normalized["c_nonce"]?.jsonPrimitive?.content)
        assertEquals(300, normalized["c_nonce_expires_in"]?.jsonPrimitive?.int)
        // credentials array should still be present (copied through)
        assertNotNull(normalized["credentials"])
    }

    @Test
    fun testDraft13CredentialsArrayDeserializesToCredentialResponse() {
        val jwt = "eyJhbGciOiJFUzI1NiJ9.eyJpc3MiOiJodHRwczovL2lzc3Vlci5leGFtcGxlLmNvbSJ9.signature"
        val json = buildJsonObject {
            put("format", "jwt_vc_json")
            put("credentials", buildJsonArray {
                add(buildJsonObject { put("credential", jwt) })
            })
            put("c_nonce", "abc123")
            put("c_nonce_expires_in", 600)
        }

        val normalized = OpenID4VCI.normalizeDraft13CredentialResponse(json)
        val response = CredentialResponse.fromJSON(normalized)

        assertTrue(response.isSuccess)
        assertNotNull(response.credential)
        assertEquals(jwt, response.credential!!.jsonPrimitive.content)
        assertEquals("abc123", response.cNonce)
    }

    // -------------------------------------------------------------------------
    // Draft 13+ credentials array with JsonPrimitive (raw JWT string)
    // -------------------------------------------------------------------------

    @Test
    fun testDraft13CredentialsArrayWithPrimitiveString() {
        val jwt = "eyJ...raw-jwt-string"
        val json = buildJsonObject {
            put("format", "jwt_vc_json")
            put("credentials", buildJsonArray {
                add(JsonPrimitive(jwt))
            })
            put("c_nonce", "prim-nonce")
            put("c_nonce_expires_in", 120)
        }

        val normalized = OpenID4VCI.normalizeDraft13CredentialResponse(json)

        assertEquals(jwt, normalized["credential"]?.jsonPrimitive?.content)
    }

    @Test
    fun testDraft13PrimitiveStringDeserializesToCredentialResponse() {
        val jwt = "eyJ...raw-jwt-string"
        val json = buildJsonObject {
            put("format", "jwt_vc_json")
            put("credentials", buildJsonArray {
                add(JsonPrimitive(jwt))
            })
            put("c_nonce", "prim-nonce")
            put("c_nonce_expires_in", 120)
        }

        val normalized = OpenID4VCI.normalizeDraft13CredentialResponse(json)
        val response = CredentialResponse.fromJSON(normalized)

        assertTrue(response.isSuccess)
        assertEquals(jwt, response.credential!!.jsonPrimitive.content)
    }

    // -------------------------------------------------------------------------
    // SD-JWT credentials (must preserve trailing ~)
    // -------------------------------------------------------------------------

    @Test
    fun testDraft13SdJwtCredentialPreservesTrailingTilde() {
        val sdJwt = "eyJhbGciOiJFUzI1NiJ9.eyJpc3MiOiJodHRwczovL2lzc3Vlci5leGFtcGxlLmNvbSJ9.sig~disclosure1~disclosure2~"
        val json = buildJsonObject {
            put("format", "dc+sd-jwt")
            put("credentials", buildJsonArray {
                add(buildJsonObject { put("credential", sdJwt) })
            })
            put("c_nonce", "sd-nonce")
            put("c_nonce_expires_in", 300)
        }

        val normalized = OpenID4VCI.normalizeDraft13CredentialResponse(json)
        val response = CredentialResponse.fromJSON(normalized)

        assertTrue(response.isSuccess)
        val credentialContent = response.credential!!.jsonPrimitive.content
        assertTrue(credentialContent.contains("~"), "SD-JWT should contain ~ separator")
        assertTrue(credentialContent.endsWith("~"), "SD-JWT should end with ~")
    }

    @Test
    fun testOlderDraftSdJwtCredential() {
        val sdJwt = "eyJ...header.payload.sig~disc1~disc2~"
        val json = buildJsonObject {
            put("format", "dc+sd-jwt")
            put("credential", sdJwt)
            put("c_nonce", "old-sd-nonce")
            put("c_nonce_expires_in", 300)
        }

        // Flat format should pass through unchanged
        val normalized = OpenID4VCI.normalizeDraft13CredentialResponse(json)
        assertTrue(json === normalized)

        val response = CredentialResponse.fromJSON(normalized)
        assertTrue(response.isSuccess)
        assertTrue(response.credential!!.jsonPrimitive.content.endsWith("~"))
    }

    // -------------------------------------------------------------------------
    // EUDI PID SD-JWT (dc+sd-jwt format, VCT urn:eudi:pid:1)
    // -------------------------------------------------------------------------

    @Test
    fun testEudiPidSdJwtDraft13Response() {
        val eudiSdJwt = "eyJhbGciOiJFUzI1NiIsInR5cCI6InZjK3NkLWp3dCJ9.eyJ2Y3QiOiJ1cm46ZXVkaTpwaWQ6MSJ9.sig~disc_given_name~disc_family_name~"
        val json = buildJsonObject {
            put("format", "dc+sd-jwt")
            put("credentials", buildJsonArray {
                add(buildJsonObject { put("credential", eudiSdJwt) })
            })
            put("c_nonce", "eudi-nonce")
            put("c_nonce_expires_in", 300)
        }

        val normalized = OpenID4VCI.normalizeDraft13CredentialResponse(json)
        val response = CredentialResponse.fromJSON(normalized)

        assertTrue(response.isSuccess)
        assertNotNull(response.credential)
        val content = response.credential!!.jsonPrimitive.content
        assertTrue(content.startsWith("eyJ"), "Should be a JWT")
        assertTrue(content.contains("~"), "Should contain SD-JWT disclosures")
        assertTrue(content.endsWith("~"), "Should end with trailing ~")
    }

    // -------------------------------------------------------------------------
    // EUDI PID mDoc (mso_mdoc format)
    // -------------------------------------------------------------------------

    @Test
    fun testEudiPidMdocDraft13Response() {
        val mdocBase64 = "omdkb2NUeXBlcmV1LmV1cm9wYS5lYy5ldWRpLnBpZC4x..."
        val json = buildJsonObject {
            put("format", "mso_mdoc")
            put("credentials", buildJsonArray {
                add(buildJsonObject { put("credential", mdocBase64) })
            })
            put("c_nonce", "mdoc-nonce")
            put("c_nonce_expires_in", 300)
        }

        val normalized = OpenID4VCI.normalizeDraft13CredentialResponse(json)
        val response = CredentialResponse.fromJSON(normalized)

        assertTrue(response.isSuccess)
        assertNotNull(response.credential)
        assertEquals(mdocBase64, response.credential!!.jsonPrimitive.content)
    }

    @Test
    fun testEudiMdocOlderDraftResponse() {
        val mdocBase64 = "omdkb2NUeXBlcmV1LmV1cm9wYS5lYy5ldWRpLnBpZC4x..."
        val json = buildJsonObject {
            put("format", "mso_mdoc")
            put("credential", mdocBase64)
            put("c_nonce", "mdoc-nonce-flat")
            put("c_nonce_expires_in", 300)
        }

        // Flat format passes through unchanged
        val normalized = OpenID4VCI.normalizeDraft13CredentialResponse(json)
        assertTrue(json === normalized)

        val response = CredentialResponse.fromJSON(normalized)
        assertTrue(response.isSuccess)
        assertEquals(mdocBase64, response.credential!!.jsonPrimitive.content)
    }

    // -------------------------------------------------------------------------
    // Non-EUDI credential (e.g., AlpsTourReservation) — the original crash case
    // -------------------------------------------------------------------------

    @Test
    fun testNonEudiCredentialDraft13DoesNotCrash() {
        val jwt = "eyJhbGciOiJFZERTQSJ9.eyJ2YyI6eyJ0eXBlIjpbIlZlcmlmaWFibGVDcmVkZW50aWFsIiwiQWxwc1RvdXJSZXNlcnZhdGlvbiJdfX0.signature"
        val json = buildJsonObject {
            put("credentials", buildJsonArray {
                add(buildJsonObject { put("credential", jwt) })
            })
            put("format", "jwt_vc_json")
            put("c_nonce", "tour-nonce")
            put("c_nonce_expires_in", 300)
        }

        val normalized = OpenID4VCI.normalizeDraft13CredentialResponse(json)
        val response = CredentialResponse.fromJSON(normalized)

        // This was the NPE crash scenario — credential should now be extracted
        assertTrue(response.isSuccess, "Non-EUDI Draft 13+ credential should be successful")
        assertNotNull(response.credential, "Credential should be extracted from credentials array")
        assertEquals(jwt, response.credential!!.jsonPrimitive.content)
        assertEquals("tour-nonce", response.cNonce)
    }

    // -------------------------------------------------------------------------
    // Deferred issuance — transaction_id → acceptance_token mapping
    // -------------------------------------------------------------------------

    @Test
    fun testDraft13TransactionIdMapsToAcceptanceToken() {
        val json = buildJsonObject {
            put("transaction_id", "txn-abc-123")
            put("c_nonce", "deferred-nonce")
            put("c_nonce_expires_in", 300)
        }

        val normalized = OpenID4VCI.normalizeDraft13CredentialResponse(json)

        assertEquals("txn-abc-123", normalized["acceptance_token"]?.jsonPrimitive?.content)
        // Original transaction_id should still be present
        assertEquals("txn-abc-123", normalized["transaction_id"]?.jsonPrimitive?.content)
    }

    @Test
    fun testDraft13DeferredIssuanceDeserializes() {
        val json = buildJsonObject {
            put("transaction_id", "txn-abc-123")
            put("c_nonce", "deferred-nonce")
            put("c_nonce_expires_in", 300)
        }

        val normalized = OpenID4VCI.normalizeDraft13CredentialResponse(json)
        val response = CredentialResponse.fromJSON(normalized)

        assertTrue(response.isDeferred, "Should be a deferred response")
        assertEquals("txn-abc-123", response.acceptanceToken)
    }

    @Test
    fun testOlderDraftAcceptanceTokenNotOverwritten() {
        val json = buildJsonObject {
            put("acceptance_token", "existing-token")
            put("transaction_id", "txn-should-not-overwrite")
            put("c_nonce", "nonce")
            put("c_nonce_expires_in", 300)
        }

        val normalized = OpenID4VCI.normalizeDraft13CredentialResponse(json)

        // acceptance_token should keep its original value since it already existed
        assertEquals("existing-token", normalized["acceptance_token"]?.jsonPrimitive?.content)
    }

    // -------------------------------------------------------------------------
    // Combined: credentials array + transaction_id
    // -------------------------------------------------------------------------

    @Test
    fun testDraft13CredentialsAndTransactionIdTogether() {
        val jwt = "eyJ...combined-jwt"
        val json = buildJsonObject {
            put("format", "jwt_vc_json")
            put("credentials", buildJsonArray {
                add(buildJsonObject { put("credential", jwt) })
            })
            put("transaction_id", "txn-combined")
            put("c_nonce", "combined-nonce")
            put("c_nonce_expires_in", 300)
        }

        val normalized = OpenID4VCI.normalizeDraft13CredentialResponse(json)

        assertEquals(jwt, normalized["credential"]?.jsonPrimitive?.content)
        assertEquals("txn-combined", normalized["acceptance_token"]?.jsonPrimitive?.content)
    }

    // -------------------------------------------------------------------------
    // Edge cases
    // -------------------------------------------------------------------------

    @Test
    fun testEmptyCredentialsArrayDoesNotPromote() {
        val json = buildJsonObject {
            put("format", "jwt_vc_json")
            put("credentials", buildJsonArray { })
            put("c_nonce", "empty-nonce")
            put("c_nonce_expires_in", 300)
        }

        val normalized = OpenID4VCI.normalizeDraft13CredentialResponse(json)

        // No credential to promote from empty array
        assertNull(normalized["credential"], "Should not have credential when credentials array is empty")
    }

    @Test
    fun testBothCredentialAndCredentialsPreservesFlat() {
        val flatJwt = "eyJ...flat-credential"
        val arrayJwt = "eyJ...array-credential"
        val json = buildJsonObject {
            put("format", "jwt_vc_json")
            put("credential", flatJwt)
            put("credentials", buildJsonArray {
                add(buildJsonObject { put("credential", arrayJwt) })
            })
            put("c_nonce", "both-nonce")
            put("c_nonce_expires_in", 300)
        }

        val normalized = OpenID4VCI.normalizeDraft13CredentialResponse(json)

        // When both credential and credentials exist, the flat credential takes precedence
        assertEquals(flatJwt, normalized["credential"]?.jsonPrimitive?.content,
            "Existing flat credential should not be overwritten by credentials array")
    }

    @Test
    fun testCredentialsArrayWithMultipleEntriesUsesFirst() {
        val json = buildJsonObject {
            put("format", "jwt_vc_json")
            put("credentials", buildJsonArray {
                add(buildJsonObject { put("credential", "eyJ...first") })
                add(buildJsonObject { put("credential", "eyJ...second") })
            })
            put("c_nonce", "multi-nonce")
            put("c_nonce_expires_in", 300)
        }

        val normalized = OpenID4VCI.normalizeDraft13CredentialResponse(json)

        assertEquals("eyJ...first", normalized["credential"]?.jsonPrimitive?.content,
            "Should extract first credential from multi-element array")
    }

    @Test
    fun testErrorResponsePassesThrough() {
        val json = buildJsonObject {
            put("error", "invalid_request")
            put("error_description", "Something went wrong")
        }

        val normalized = OpenID4VCI.normalizeDraft13CredentialResponse(json)

        // Error responses have neither credentials nor transaction_id
        assertTrue(json === normalized, "Error response should pass through unchanged")

        val response = CredentialResponse.fromJSON(normalized)
        assertEquals("invalid_request", response.error)
        assertEquals("Something went wrong", response.errorDescription)
    }

    @Test
    fun testCustomParametersPreserved() {
        val json = buildJsonObject {
            put("format", "jwt_vc_json")
            put("credentials", buildJsonArray {
                add(buildJsonObject { put("credential", "eyJ...custom") })
            })
            put("c_nonce", "custom-nonce")
            put("c_nonce_expires_in", 300)
            put("custom_field", "custom-value")
        }

        val normalized = OpenID4VCI.normalizeDraft13CredentialResponse(json)

        assertEquals("custom-value", normalized["custom_field"]?.jsonPrimitive?.content,
            "Custom parameters should be preserved through normalization")
    }

    @Test
    fun testCredentialsArrayObjectWithoutNestedCredentialKey() {
        // Some issuers may return the full credential object without a nested "credential" key
        val credentialObj = buildJsonObject {
            put("type", "VerifiableCredential")
            put("issuer", "did:example:issuer")
        }
        val json = buildJsonObject {
            put("format", "jwt_vc_json")
            put("credentials", buildJsonArray {
                add(credentialObj)
            })
            put("c_nonce", "obj-nonce")
            put("c_nonce_expires_in", 300)
        }

        val normalized = OpenID4VCI.normalizeDraft13CredentialResponse(json)

        // When the array element is a JsonObject without a "credential" key, the whole object is promoted
        val promoted = normalized["credential"]?.jsonObject
        assertNotNull(promoted, "Should promote the full object as credential")
        assertEquals("VerifiableCredential", promoted["type"]?.jsonPrimitive?.content)
    }
}
