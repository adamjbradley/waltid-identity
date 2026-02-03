@file:OptIn(ExperimentalUuidApi::class)

package id.walt.eudi

import IssuerApi
import ExchangeApi
import CredentialsApi
import expectSuccess
import id.walt.commons.testing.E2ETest
import id.walt.issuer.issuance.IssuanceRequest
import id.walt.mdoc.doc.MDoc
import id.walt.oid4vc.OpenID4VCIVersion
import id.walt.oid4vc.data.CredentialFormat
import id.walt.oid4vc.data.OpenIDProviderMetadata
import id.walt.webwallet.db.models.WalletCredential
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import kotlinx.serialization.json.*
import kotlin.test.*
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * E2E tests for EUDI mDL (Mobile Driving License) issuance in mso_mdoc format.
 *
 * These tests validate the full OpenID4VCI Draft 13+ flow for ISO 18013-5 mDL credentials.
 */
class EudiMdlE2ETest(
    private val e2e: E2ETest,
    private val client: HttpClient,
    private val issuerApi: IssuerApi,
    private val exchangeApi: ExchangeApi,
    private val credentialsApi: CredentialsApi,
) {
    companion object {
        const val MDL_CREDENTIAL_CONFIGURATION_ID = "org.iso.18013.5.1.mDL"
        const val MDL_DOCTYPE = "org.iso.18013.5.1.mDL"
        const val MDL_NAMESPACE = "org.iso.18013.5.1"

        val TEST_KEY = buildJsonObject {
            put("type", JsonPrimitive("jwk"))
            put("jwk", buildJsonObject {
                put("kty", JsonPrimitive("EC"))
                put("d", JsonPrimitive("mJJv_Hzv8--BHJaJlvB9KM8XQnM9M8J7KNZ8K_z9qdc"))
                put("crv", JsonPrimitive("P-256"))
                put("kid", JsonPrimitive("eudi-mdl-test-key"))
                put("x", JsonPrimitive("dHGO-XVe1E-tEjqLN5EFT_FHQFgXTQ-9U7TL5qm9_0g"))
                put("y", JsonPrimitive("L8L7_pV9t2qn7B8DJ1_N8pEyEL_WQ8wVBM_FqA7k5tw"))
            })
        }

        val TEST_MDL_DATA = buildJsonObject {
            put("family_name", JsonPrimitive("MUSTERMANN"))
            put("given_name", JsonPrimitive("ERIKA"))
            put("birth_date", JsonPrimitive("1984-01-26"))
            put("issue_date", JsonPrimitive("2023-01-15"))
            put("expiry_date", JsonPrimitive("2033-01-15"))
            put("issuing_country", JsonPrimitive("DE"))
            put("issuing_authority", JsonPrimitive("Kraftfahrt-Bundesamt"))
            put("document_number", JsonPrimitive("T22000129"))
            put("portrait", JsonNull)
            put("driving_privileges", buildJsonArray {
                add(buildJsonObject {
                    put("vehicle_category_code", JsonPrimitive("B"))
                    put("issue_date", JsonPrimitive("2023-01-15"))
                    put("expiry_date", JsonPrimitive("2033-01-15"))
                })
            })
        }

        const val TEST_ISSUER_DID = "did:key:z6MkjoRhq1jSNJdLiruSXrFFxagqrztZaXHqHGUTKJbcNywp"
    }

    /**
     * Test that issuer metadata includes mDL configuration per ISO 18013-5
     */
    suspend fun testIssuerMetadataHasMdlConfig() {
        e2e.test("EUDI mDL - Validate issuer metadata contains mDL configuration") {
            val metadata = client.get("/${OpenID4VCIVersion.DRAFT13.versionString}/.well-known/openid-credential-issuer")
                .expectSuccess()
                .body<OpenIDProviderMetadata.Draft13>()

            val credConfigs = assertNotNull(metadata.credentialConfigurationsSupported)

            // Check for mDL configuration
            val mdlConfig = credConfigs[MDL_CREDENTIAL_CONFIGURATION_ID]
            if (mdlConfig != null) {
                assertEquals(CredentialFormat.mso_mdoc, mdlConfig.format)
                assertEquals(MDL_DOCTYPE, mdlConfig.docType)

                // Verify cryptographic binding is cose_key (required for mDoc)
                assertNotNull(mdlConfig.cryptographicBindingMethodsSupported)
                assertTrue(
                    mdlConfig.cryptographicBindingMethodsSupported!!.contains("cose_key"),
                    "mDL should support cose_key binding"
                )
            }
        }
    }

    /**
     * Test full pre-authorized flow for mDL issuance
     */
    suspend fun testPreAuthorizedMdlFlow(wallet: Uuid) {
        lateinit var newCredential: WalletCredential

        e2e.test("EUDI mDL - Issue via pre-authorized flow") {
            val issuanceRequest = IssuanceRequest(
                issuerKey = TEST_KEY,
                credentialData = TEST_MDL_DATA,
                credentialConfigurationId = MDL_CREDENTIAL_CONFIGURATION_ID,
                issuerDid = TEST_ISSUER_DID
            )

            // Issue credential via issuer API
            lateinit var offerUri: String
            issuerApi.mdoc(issuanceRequest) { offerUri = it }

            // Verify offer URI format (Draft 13+)
            assertTrue(offerUri.startsWith("openid-credential-offer://"))
            assertTrue(offerUri.contains("credential_offer_uri"))

            // Wallet claims the credential
            exchangeApi.resolveCredentialOffer(wallet, offerUri)
            exchangeApi.useOfferRequest(wallet, offerUri, 1) {
                newCredential = it.first()
            }

            // Validate credential format
            assertEquals(CredentialFormat.mso_mdoc, newCredential.format)
            assertNull(newCredential.disclosures, "mDoc should not have SD-JWT disclosures")
        }

        e2e.test("EUDI mDL - Validate mDoc structure per ISO 18013-5") {
            // Parse mDoc
            val mdoc = MDoc.fromCBORHex(newCredential.document)

            // Verify doc type is mDL
            assertEquals(MDL_DOCTYPE, mdoc.docType.value)

            // Verify issuer signed data is present
            assertNotNull(mdoc.issuerSigned)
            assertNotNull(mdoc.issuerSigned.issuerAuth)

            // Verify namespace data is present
            val namespaces = mdoc.issuerSigned.nameSpaces
            assertNotNull(namespaces, "mDL should have namespace data")

            // mDL uses org.iso.18013.5.1 namespace
            val mdlNamespace = namespaces[MDL_NAMESPACE]
            if (mdlNamespace != null) {
                assertTrue(mdlNamespace.isNotEmpty(), "mDL namespace should have signed items")
            }
        }

        e2e.test("EUDI mDL - Verify issuer authentication structure") {
            val mdoc = MDoc.fromCBORHex(newCredential.document)

            // Verify COSE_Sign1 structure for issuer auth
            val issuerAuth = mdoc.issuerSigned.issuerAuth
            assertNotNull(issuerAuth, "Issuer auth should be present")
            assertNotNull(issuerAuth?.payload, "Issuer auth should have payload")
        }

        // Clean up
        credentialsApi.delete(wallet, newCredential.id)
    }

    /**
     * Test that credential configuration includes correct docType
     */
    suspend fun testMdlDocTypeInOffer() {
        e2e.test("EUDI mDL - Verify docType in credential offer") {
            val issuanceRequest = IssuanceRequest(
                issuerKey = TEST_KEY,
                credentialData = TEST_MDL_DATA,
                credentialConfigurationId = MDL_CREDENTIAL_CONFIGURATION_ID,
                issuerDid = TEST_ISSUER_DID
            )

            lateinit var offerUri: String
            issuerApi.mdoc(issuanceRequest) { offerUri = it }

            // Fetch the credential offer
            val offerUrlParam = io.ktor.http.Url(offerUri).parameters["credential_offer_uri"]
            assertNotNull(offerUrlParam)

            val offer = client.get(offerUrlParam).expectSuccess().body<JsonObject>()

            // Verify credential_configuration_ids contains mDL
            val credConfigIds = offer["credential_configuration_ids"]?.jsonArray
            assertNotNull(credConfigIds)
            assertTrue(
                credConfigIds.any { it.jsonPrimitive.content == MDL_CREDENTIAL_CONFIGURATION_ID },
                "Offer should include mDL credential_configuration_id"
            )
        }
    }
}
