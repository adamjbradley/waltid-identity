@file:OptIn(ExperimentalUuidApi::class)

import id.walt.commons.testing.E2ETest
import id.walt.issuer.issuance.IssuanceRequest
import id.walt.oid4vc.data.CredentialFormat
import id.walt.oid4vc.util.JwtUtils
import id.walt.w3c.schemes.JwsSignatureScheme
import id.walt.webwallet.db.models.WalletCredential
import io.ktor.client.*
import kotlinx.serialization.json.*
import kotlin.test.*
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * E2E tests validating the Draft 13+ credential response normalization path
 * across all three credential formats: JWT, SD-JWT, and mDoc.
 *
 * These tests exercise the complete flow: issuer returns Draft 13+ response ->
 * OpenID4VCI.sendCredentialRequest() normalizes -> CredentialOfferProcessor processes ->
 * wallet stores credential. This covers the original crash scenario (non-EUDI credentials)
 * that was fixed in PR #17.
 */
class Draft13IssuanceE2ETest(
    private val e2e: E2ETest,
    private val client: HttpClient,
    private val issuerApi: IssuerApi,
    private val exchangeApi: ExchangeApi,
    private val credentialsApi: CredentialsApi,
) {
    companion object {
        val TEST_KEY = buildJsonObject {
            put("type", JsonPrimitive("jwk"))
            put("jwk", buildJsonObject {
                put("kty", JsonPrimitive("EC"))
                put("d", JsonPrimitive("mJJv_Hzv8--BHJaJlvB9KM8XQnM9M8J7KNZ8K_z9qdc"))
                put("crv", JsonPrimitive("P-256"))
                put("kid", JsonPrimitive("draft13-test-key"))
                put("x", JsonPrimitive("dHGO-XVe1E-tEjqLN5EFT_FHQFgXTQ-9U7TL5qm9_0g"))
                put("y", JsonPrimitive("L8L7_pV9t2qn7B8DJ1_N8pEyEL_WQ8wVBM_FqA7k5tw"))
            })
        }

        val OPEN_BADGE_DATA = buildJsonObject {
            put("@context", buildJsonArray {
                add(JsonPrimitive("https://www.w3.org/2018/credentials/v1"))
                add(JsonPrimitive("https://purl.imsglobal.org/spec/ob/v3p0/context.json"))
            })
            put("type", buildJsonArray {
                add(JsonPrimitive("VerifiableCredential"))
                add(JsonPrimitive("OpenBadgeCredential"))
            })
            put("name", JsonPrimitive("Draft 13 Test Badge"))
            put("credentialSubject", buildJsonObject {
                put("type", buildJsonArray { add(JsonPrimitive("AchievementSubject")) })
                put("achievement", buildJsonObject {
                    put("id", JsonPrimitive("urn:uuid:draft13-test"))
                    put("type", buildJsonArray { add(JsonPrimitive("Achievement")) })
                    put("name", JsonPrimitive("Draft 13+ Normalization Test"))
                    put("criteria", buildJsonObject {
                        put("narrative", JsonPrimitive("Tests Draft 13+ credential response format handling"))
                    })
                })
            })
        }

        const val TEST_ISSUER_DID = "did:key:z6MkjoRhq1jSNJdLiruSXrFFxagqrztZaXHqHGUTKJbcNywp"
    }

    /**
     * Test JWT credential issuance and claim via Draft 13+ flow.
     * Validates that a standard JWT credential (non-EUDI) can be issued and claimed
     * through the Draft 13+ normalization path without errors.
     */
    suspend fun testJwtCredentialIssuanceAndClaim(wallet: Uuid) {
        lateinit var newCredential: WalletCredential

        e2e.test("Draft 13+ JWT - Issue and claim OpenBadgeCredential") {
            val issuanceRequest = IssuanceRequest(
                issuerKey = TEST_KEY,
                credentialData = OPEN_BADGE_DATA,
                credentialConfigurationId = "OpenBadgeCredential_jwt_vc_json",
                issuerDid = TEST_ISSUER_DID
            )

            lateinit var offerUri: String
            issuerApi.jwt(issuanceRequest) { offerUri = it }

            // Verify Draft 13+ offer format
            assertTrue(offerUri.startsWith("openid-credential-offer://"))

            // Claim credential
            exchangeApi.resolveCredentialOffer(wallet, offerUri)
            exchangeApi.useOfferRequest(wallet, offerUri, 1) {
                newCredential = it.first()
            }

            // Validate JWT structure
            assertNotNull(newCredential.document)
            val payload = JwtUtils.parseJWTPayload(newCredential.document)
            assertContains(payload.keys, JwsSignatureScheme.JwsOption.VC)
        }

        // Clean up
        credentialsApi.delete(wallet, newCredential.id)
    }

    /**
     * Test SD-JWT credential issuance and claim via Draft 13+ flow.
     * Validates that SD-JWT format is preserved through the normalization path,
     * including trailing tilde and disclosures.
     */
    suspend fun testSdJwtCredentialIssuanceAndClaim(wallet: Uuid) {
        lateinit var newCredential: WalletCredential

        e2e.test("Draft 13+ SD-JWT - Issue and claim OpenBadgeCredential") {
            val issuanceRequest = IssuanceRequest(
                issuerKey = TEST_KEY,
                credentialData = OPEN_BADGE_DATA,
                credentialConfigurationId = "OpenBadgeCredential_jwt_vc_json",
                issuerDid = TEST_ISSUER_DID,
                selectiveDisclosure = id.walt.sdjwt.SDMap(mapOf(
                    "credentialSubject" to id.walt.sdjwt.SDField(true)
                ))
            )

            lateinit var offerUri: String
            issuerApi.sdjwt(issuanceRequest) { offerUri = it }

            // Claim credential
            exchangeApi.resolveCredentialOffer(wallet, offerUri)
            exchangeApi.useOfferRequest(wallet, offerUri, 1) {
                newCredential = it.first()
            }

            // Validate SD-JWT structure: document should contain tildes (disclosures)
            assertNotNull(newCredential.document)
            assertTrue(
                newCredential.document.contains("~"),
                "SD-JWT credential should contain disclosure separators"
            )
            assertTrue(
                newCredential.document.endsWith("~"),
                "SD-JWT credential should end with trailing tilde"
            )
        }

        // Clean up
        credentialsApi.delete(wallet, newCredential.id)
    }

    /**
     * Test mDoc credential issuance and claim via Draft 13+ flow.
     * Validates that mDoc (CBOR) format is preserved through the normalization path.
     */
    suspend fun testMdocCredentialIssuanceAndClaim(wallet: Uuid) {
        lateinit var newCredential: WalletCredential

        e2e.test("Draft 13+ mDoc - Issue and claim PID mDoc credential") {
            val pidData = buildJsonObject {
                put("family_name", JsonPrimitive("DRAFT13"))
                put("given_name", JsonPrimitive("TEST"))
                put("birth_date", JsonPrimitive("1990-01-01"))
                put("age_over_18", JsonPrimitive(true))
                put("issuing_country", JsonPrimitive("DE"))
                put("issuing_authority", JsonPrimitive("Test Authority"))
            }

            val issuanceRequest = IssuanceRequest(
                issuerKey = TEST_KEY,
                credentialData = pidData,
                credentialConfigurationId = "eu.europa.ec.eudi.pid.1",
                issuerDid = TEST_ISSUER_DID
            )

            lateinit var offerUri: String
            issuerApi.mdoc(issuanceRequest) { offerUri = it }

            // Claim credential
            exchangeApi.resolveCredentialOffer(wallet, offerUri)
            exchangeApi.useOfferRequest(wallet, offerUri, 1) {
                newCredential = it.first()
            }

            // Validate mDoc format
            assertEquals(CredentialFormat.mso_mdoc, newCredential.format)
            assertNotNull(newCredential.document)
            assertNull(newCredential.disclosures, "mDoc should not have SD-JWT disclosures")
        }

        // Clean up
        credentialsApi.delete(wallet, newCredential.id)
    }
}
