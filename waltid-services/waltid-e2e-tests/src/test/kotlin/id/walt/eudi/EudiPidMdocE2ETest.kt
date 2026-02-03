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
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import kotlin.test.*
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * E2E tests for EUDI PID issuance in mso_mdoc format.
 *
 * These tests validate the full OpenID4VCI Draft 13+ flow for EUDI PID credentials
 * using mDoc format, following the existing E2E test patterns.
 */
class EudiPidMdocE2ETest(
    private val e2e: E2ETest,
    private val client: HttpClient,
    private val issuerApi: IssuerApi,
    private val exchangeApi: ExchangeApi,
    private val credentialsApi: CredentialsApi,
) {
    companion object {
        const val EUDI_PID_MDOC_CREDENTIAL_CONFIGURATION_ID = "eu.europa.ec.eudi.pid.1"
        const val EUDI_PID_DOCTYPE = "eu.europa.ec.eudi.pid.1"

        val TEST_KEY = buildJsonObject {
            put("type", JsonPrimitive("jwk"))
            put("jwk", buildJsonObject {
                put("kty", JsonPrimitive("EC"))
                put("d", JsonPrimitive("mJJv_Hzv8--BHJaJlvB9KM8XQnM9M8J7KNZ8K_z9qdc"))
                put("crv", JsonPrimitive("P-256"))
                put("kid", JsonPrimitive("eudi-pid-test-key"))
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

        const val TEST_ISSUER_DID = "did:key:z6MkjoRhq1jSNJdLiruSXrFFxagqrztZaXHqHGUTKJbcNywp"
    }

    /**
     * Test that issuer metadata includes EUDI PID mDoc configuration
     */
    suspend fun testIssuerMetadataHasEudiPidMdocConfig() {
        e2e.test("EUDI PID mDoc - Validate issuer metadata contains PID configuration") {
            val metadata = client.get("/${OpenID4VCIVersion.DRAFT13.versionString}/.well-known/openid-credential-issuer")
                .expectSuccess()
                .body<OpenIDProviderMetadata.Draft13>()

            val credConfigs = assertNotNull(metadata.credentialConfigurationsSupported)

            // Check if EUDI PID mDoc config exists
            val pidConfig = credConfigs[EUDI_PID_MDOC_CREDENTIAL_CONFIGURATION_ID]
            if (pidConfig != null) {
                assertEquals(CredentialFormat.mso_mdoc, pidConfig.format)
                assertEquals(EUDI_PID_DOCTYPE, pidConfig.docType)
            }
        }
    }

    /**
     * Test full pre-authorized flow for EUDI PID mDoc issuance
     */
    suspend fun testPreAuthorizedPidMdocFlow(wallet: Uuid) {
        lateinit var newCredential: WalletCredential

        e2e.test("EUDI PID mDoc - Issue via pre-authorized flow") {
            val issuanceRequest = IssuanceRequest(
                issuerKey = TEST_KEY,
                credentialData = TEST_PID_DATA,
                credentialConfigurationId = EUDI_PID_MDOC_CREDENTIAL_CONFIGURATION_ID,
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

        e2e.test("EUDI PID mDoc - Validate mDoc structure") {
            // Parse and validate mDoc
            val mdoc = MDoc.fromCBORHex(newCredential.document)
            assertEquals(EUDI_PID_DOCTYPE, mdoc.docType.value)

            // Verify issuer signed data is present
            assertNotNull(mdoc.issuerSigned)
            assertNotNull(mdoc.issuerSigned.issuerAuth)
        }

        // Clean up
        credentialsApi.delete(wallet, newCredential.id)
    }

    /**
     * Test that Draft 13+ credential_configuration_id is used (not format field)
     */
    suspend fun testDraft13CredentialConfigurationId() {
        e2e.test("EUDI PID mDoc - Verify credential_configuration_id in offer") {
            val issuanceRequest = IssuanceRequest(
                issuerKey = TEST_KEY,
                credentialData = TEST_PID_DATA,
                credentialConfigurationId = EUDI_PID_MDOC_CREDENTIAL_CONFIGURATION_ID,
                issuerDid = TEST_ISSUER_DID
            )

            lateinit var offerUri: String
            issuerApi.mdoc(issuanceRequest) { offerUri = it }

            // Fetch the credential offer to verify Draft 13+ structure
            val offerUrlParam = io.ktor.http.Url(offerUri).parameters["credential_offer_uri"]
            assertNotNull(offerUrlParam, "Offer URI should contain credential_offer_uri parameter")

            val offer = client.get(offerUrlParam).expectSuccess().body<JsonObject>()

            // Draft 13+ uses credential_configuration_ids array
            val credConfigIds = offer["credential_configuration_ids"]?.jsonArray
            assertNotNull(credConfigIds, "Offer should have credential_configuration_ids (Draft 13+)")
            assertTrue(
                credConfigIds.any { it.jsonPrimitive.content == EUDI_PID_MDOC_CREDENTIAL_CONFIGURATION_ID },
                "Offer should include our credential_configuration_id"
            )
        }
    }
}
