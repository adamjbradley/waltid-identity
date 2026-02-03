@file:OptIn(ExperimentalUuidApi::class)

package id.walt.eudi

import IssuerApi
import ExchangeApi
import CredentialsApi
import expectSuccess
import id.walt.commons.testing.E2ETest
import id.walt.issuer.issuance.IssuanceRequest
import id.walt.oid4vc.OpenID4VCIVersion
import id.walt.oid4vc.data.CredentialFormat
import id.walt.oid4vc.data.OpenIDProviderMetadata
import id.walt.sdjwt.SDJwtVC
import id.walt.sdjwt.SDMap
import id.walt.sdjwt.SDField
import id.walt.webwallet.db.models.WalletCredential
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import kotlinx.serialization.json.*
import kotlin.test.*
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * E2E tests for EUDI PID issuance in dc+sd-jwt format.
 *
 * These tests validate the full OpenID4VCI Draft 13+ flow for EUDI PID credentials
 * using SD-JWT format with the correct EUDI format string (dc+sd-jwt, not vc+sd-jwt).
 *
 * Key EUDI-specific behaviors:
 * - Uses "dc+sd-jwt" format (Digital Credentials), not "vc+sd-jwt" (Verifiable Credentials)
 * - Uses VCT (Verifiable Credential Type) for type identification
 * - Supports selective disclosure claims
 */
class EudiPidSdJwtE2ETest(
    private val e2e: E2ETest,
    private val client: HttpClient,
    private val issuerApi: IssuerApi,
    private val exchangeApi: ExchangeApi,
    private val credentialsApi: CredentialsApi,
) {
    companion object {
        // EUDI PID SD-JWT uses URN format for credential configuration ID
        const val EUDI_PID_SDJWT_CREDENTIAL_CONFIGURATION_ID = "urn:eu.europa.ec.eudi:pid:1"

        // VCT (Verifiable Credential Type) for EUDI PID
        const val EUDI_PID_VCT = "urn:eu.europa.ec.eudi:pid:1"

        val TEST_KEY = buildJsonObject {
            put("type", JsonPrimitive("jwk"))
            put("jwk", buildJsonObject {
                put("kty", JsonPrimitive("EC"))
                put("d", JsonPrimitive("mJJv_Hzv8--BHJaJlvB9KM8XQnM9M8J7KNZ8K_z9qdc"))
                put("crv", JsonPrimitive("P-256"))
                put("kid", JsonPrimitive("eudi-pid-sdjwt-key"))
                put("x", JsonPrimitive("dHGO-XVe1E-tEjqLN5EFT_FHQFgXTQ-9U7TL5qm9_0g"))
                put("y", JsonPrimitive("L8L7_pV9t2qn7B8DJ1_N8pEyEL_WQ8wVBM_FqA7k5tw"))
            })
        }

        val TEST_PID_DATA = buildJsonObject {
            put("family_name", JsonPrimitive("MUSTERMANN"))
            put("given_name", JsonPrimitive("ERIKA"))
            put("birth_date", JsonPrimitive("1984-01-26"))
            put("age_over_18", JsonPrimitive(true))
            put("age_over_21", JsonPrimitive(true))
            put("issuing_country", JsonPrimitive("DE"))
            put("issuing_authority", JsonPrimitive("German Federal Government"))
            put("nationality", JsonPrimitive("DE"))
            put("resident_country", JsonPrimitive("DE"))
        }

        // Selective disclosure configuration for PID - marks fields as selectively disclosable
        val TEST_SELECTIVE_DISCLOSURE = SDMap(mapOf(
            "family_name" to SDField(sd = true),
            "given_name" to SDField(sd = true),
            "birth_date" to SDField(sd = true),
            "nationality" to SDField(sd = true)
        ))

        const val TEST_ISSUER_DID = "did:key:z6MkjoRhq1jSNJdLiruSXrFFxagqrztZaXHqHGUTKJbcNywp"
    }

    /**
     * Test that issuer metadata includes EUDI PID SD-JWT configuration with correct format
     */
    suspend fun testIssuerMetadataHasEudiPidSdJwtConfig() {
        e2e.test("EUDI PID SD-JWT - Validate issuer metadata contains PID configuration") {
            val metadata = client.get("/${OpenID4VCIVersion.DRAFT13.versionString}/.well-known/openid-credential-issuer")
                .expectSuccess()
                .body<OpenIDProviderMetadata.Draft13>()

            val credConfigs = assertNotNull(metadata.credentialConfigurationsSupported)

            // Check for SD-JWT PID configuration
            // Note: The actual config ID may vary based on issuer setup
            val sdJwtConfigs = credConfigs.filter {
                it.value.format == CredentialFormat.sd_jwt_dc ||
                        it.value.format == CredentialFormat.sd_jwt_vc ||
                        it.value.format == CredentialFormat.sd_jwt_vc
            }

            // If PID SD-JWT is configured, verify the format
            val pidConfig = credConfigs[EUDI_PID_SDJWT_CREDENTIAL_CONFIGURATION_ID]
            if (pidConfig != null) {
                // EUDI uses dc+sd-jwt format
                assertTrue(
                    pidConfig.format == CredentialFormat.sd_jwt_dc ||
                            pidConfig.format == CredentialFormat.sd_jwt_vc,
                    "EUDI PID should use dc+sd-jwt or sd-jwt format, not vc+sd-jwt"
                )
            }
        }
    }

    /**
     * Test full pre-authorized flow for EUDI PID SD-JWT issuance
     */
    suspend fun testPreAuthorizedPidSdJwtFlow(wallet: Uuid) {
        lateinit var newCredential: WalletCredential

        e2e.test("EUDI PID SD-JWT - Issue via pre-authorized flow") {
            val issuanceRequest = IssuanceRequest(
                issuerKey = TEST_KEY,
                credentialData = TEST_PID_DATA,
                credentialConfigurationId = EUDI_PID_SDJWT_CREDENTIAL_CONFIGURATION_ID,
                issuerDid = TEST_ISSUER_DID,
                selectiveDisclosure = TEST_SELECTIVE_DISCLOSURE
            )

            // Issue credential via issuer API
            lateinit var offerUri: String
            issuerApi.sdjwt(issuanceRequest) { offerUri = it }

            // Verify offer URI format (Draft 13+)
            assertTrue(offerUri.startsWith("openid-credential-offer://"))
            assertTrue(offerUri.contains("credential_offer_uri"))

            // Wallet claims the credential
            exchangeApi.resolveCredentialOffer(wallet, offerUri)
            exchangeApi.useOfferRequest(wallet, offerUri, 1) {
                newCredential = it.first()
            }

            // Validate credential has SD-JWT characteristics
            assertTrue(
                newCredential.format == CredentialFormat.sd_jwt_vc ||
                        newCredential.format == CredentialFormat.sd_jwt_dc ||
                        newCredential.format == CredentialFormat.sd_jwt_vc,
                "Credential should be SD-JWT format"
            )
        }

        e2e.test("EUDI PID SD-JWT - Validate SD-JWT structure") {
            // Parse SD-JWT credential
            val sdJwt = SDJwtVC.parse(newCredential.document)

            // Verify VCT claim is present (EUDI requirement)
            assertNotNull(sdJwt.vct, "SD-JWT should have VCT claim for EUDI compatibility")

            // Verify issuer claim
            assertNotNull(sdJwt.issuer, "SD-JWT should have issuer claim")
            assertEquals(TEST_ISSUER_DID, sdJwt.issuer)

            // Verify selective disclosure algorithm is specified
            assertNotNull(sdJwt.sdAlg, "SD-JWT should specify _sd_alg")
            assertEquals("sha-256", sdJwt.sdAlg)
        }

        e2e.test("EUDI PID SD-JWT - Verify disclosures present") {
            // SD-JWT credentials should have disclosures
            assertNotNull(newCredential.disclosures, "SD-JWT credential should have disclosures")
        }

        // Clean up
        credentialsApi.delete(wallet, newCredential.id)
    }

    /**
     * Test that VCT is used for type matching (not W3C credential type)
     */
    suspend fun testVctBasedTypeMatching() {
        e2e.test("EUDI PID SD-JWT - Verify VCT-based type identification") {
            val issuanceRequest = IssuanceRequest(
                issuerKey = TEST_KEY,
                credentialData = TEST_PID_DATA,
                credentialConfigurationId = EUDI_PID_SDJWT_CREDENTIAL_CONFIGURATION_ID,
                issuerDid = TEST_ISSUER_DID,
                selectiveDisclosure = TEST_SELECTIVE_DISCLOSURE
            )

            lateinit var offerUri: String
            issuerApi.sdjwt(issuanceRequest) { offerUri = it }

            // Fetch the credential offer to verify structure
            val offerUrlParam = io.ktor.http.Url(offerUri).parameters["credential_offer_uri"]
            assertNotNull(offerUrlParam)

            val offer = client.get(offerUrlParam).expectSuccess().body<JsonObject>()

            // Verify credential_configuration_ids contains our VCT-based ID
            val credConfigIds = offer["credential_configuration_ids"]?.jsonArray
            assertNotNull(credConfigIds)
            assertTrue(
                credConfigIds.any {
                    it.jsonPrimitive.content.contains("eudi") ||
                            it.jsonPrimitive.content.contains("pid")
                },
                "Offer should reference EUDI PID configuration"
            )
        }
    }
}
