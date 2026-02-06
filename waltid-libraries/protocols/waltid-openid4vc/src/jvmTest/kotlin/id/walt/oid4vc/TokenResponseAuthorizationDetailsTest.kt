package id.walt.oid4vc

import id.walt.oid4vc.data.AuthorizationDetails
import id.walt.oid4vc.definitions.OPENID_CREDENTIAL_AUTHORIZATION_TYPE
import id.walt.oid4vc.responses.TokenResponse
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.seconds

/**
 * Tests for TokenResponse with authorization_details (EWC RFC007 PWA support).
 */
class TokenResponseAuthorizationDetailsTest {

    @Test
    fun testTokenResponseWithAuthorizationDetails() {
        val authDetails = listOf(
            AuthorizationDetails(
                type = OPENID_CREDENTIAL_AUTHORIZATION_TYPE,
                credentialConfigurationId = "PaymentWalletAttestation",
                credentialIdentifiers = listOf("pwa_visa_1234", "pwa_sepa_5678")
            )
        )

        val response = TokenResponse.success(
            accessToken = "test-access-token",
            tokenType = "bearer",
            expiresIn = 3600,
            cNonce = "test-nonce",
            cNonceExpiresIn = 300.seconds,
            authorizationDetails = authDetails
        )

        assertNotNull(response.authorizationDetails, "authorization_details should be present")
        assertEquals(1, response.authorizationDetails!!.size)

        val detail = response.authorizationDetails!![0]
        assertEquals(OPENID_CREDENTIAL_AUTHORIZATION_TYPE, detail.type)
        assertEquals("PaymentWalletAttestation", detail.credentialConfigurationId)
        assertEquals(listOf("pwa_visa_1234", "pwa_sepa_5678"), detail.credentialIdentifiers)
    }

    @Test
    fun testTokenResponseWithoutAuthorizationDetails() {
        val response = TokenResponse.success(
            accessToken = "test-access-token",
            tokenType = "bearer",
            expiresIn = 3600
        )

        assertNull(response.authorizationDetails, "authorization_details should be null when not provided")
    }

    @Test
    fun testTokenResponseSerializationWithAuthorizationDetails() {
        val authDetails = listOf(
            AuthorizationDetails(
                type = OPENID_CREDENTIAL_AUTHORIZATION_TYPE,
                credentialConfigurationId = "PaymentWalletAttestation",
                credentialIdentifiers = listOf("pwa_visa_1234")
            )
        )

        val response = TokenResponse.success(
            accessToken = "test-access-token",
            tokenType = "bearer",
            authorizationDetails = authDetails
        )

        val json = response.toJSON()

        // Check authorization_details is serialized
        val authDetailsJson = json["authorization_details"]?.jsonArray
        assertNotNull(authDetailsJson, "authorization_details should be in JSON")
        assertEquals(1, authDetailsJson.size)

        val detailJson = authDetailsJson[0].jsonObject
        assertEquals(OPENID_CREDENTIAL_AUTHORIZATION_TYPE, detailJson["type"]?.jsonPrimitive?.content)
        assertEquals("PaymentWalletAttestation", detailJson["credential_configuration_id"]?.jsonPrimitive?.content)

        val credIds = detailJson["credential_identifiers"]?.jsonArray
        assertNotNull(credIds)
        assertEquals("pwa_visa_1234", credIds[0].jsonPrimitive.content)
    }

    @Test
    fun testTokenResponseDeserializationWithAuthorizationDetails() {
        val jsonStr = """
        {
            "access_token": "test-token",
            "token_type": "bearer",
            "expires_in": 3600,
            "authorization_details": [
                {
                    "type": "openid_credential",
                    "credential_configuration_id": "PaymentWalletAttestation",
                    "credential_identifiers": ["pwa_visa_1234", "pwa_mastercard_5678"]
                }
            ]
        }
        """.trimIndent()

        val json = Json { ignoreUnknownKeys = true }
        val parsed = json.parseToJsonElement(jsonStr).jsonObject
        val response = TokenResponse.fromJSON(parsed)

        assertEquals("test-token", response.accessToken)
        assertNotNull(response.authorizationDetails)
        assertEquals(1, response.authorizationDetails!!.size)

        val detail = response.authorizationDetails!![0]
        assertEquals("PaymentWalletAttestation", detail.credentialConfigurationId)
        assertEquals(2, detail.credentialIdentifiers?.size)
        assertEquals("pwa_visa_1234", detail.credentialIdentifiers!![0])
        assertEquals("pwa_mastercard_5678", detail.credentialIdentifiers!![1])
    }

    @Test
    fun testTokenResponseToHttpParametersWithAuthorizationDetails() {
        val authDetails = listOf(
            AuthorizationDetails(
                type = OPENID_CREDENTIAL_AUTHORIZATION_TYPE,
                credentialConfigurationId = "PaymentWalletAttestation",
                credentialIdentifiers = listOf("pwa_visa_1234")
            )
        )

        val response = TokenResponse.success(
            accessToken = "test-access-token",
            tokenType = "bearer",
            authorizationDetails = authDetails
        )

        val params = response.toHttpParameters()

        assertNotNull(params["authorization_details"], "authorization_details should be in HTTP parameters")
        val authDetailsParam = params["authorization_details"]!![0]
        // The parameter should be a JSON string
        assert(authDetailsParam.contains("credential_configuration_id"))
        assert(authDetailsParam.contains("PaymentWalletAttestation"))
        assert(authDetailsParam.contains("credential_identifiers"))
    }

    @Test
    fun testAuthorizationDetailsWithCredentialConfigurationId() {
        val detail = AuthorizationDetails(
            type = OPENID_CREDENTIAL_AUTHORIZATION_TYPE,
            credentialConfigurationId = "TestCredential",
            credentialIdentifiers = listOf("id1", "id2", "id3")
        )

        assertEquals("TestCredential", detail.credentialConfigurationId)
        assertEquals(3, detail.credentialIdentifiers?.size)

        val json = detail.toJSON()
        assertEquals("TestCredential", json["credential_configuration_id"]?.jsonPrimitive?.content)

        val credIds = json["credential_identifiers"]?.jsonArray
        assertNotNull(credIds)
        assertEquals(3, credIds.size)
    }
}
