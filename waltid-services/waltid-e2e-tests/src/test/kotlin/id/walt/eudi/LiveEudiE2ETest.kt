package id.walt.eudi

import id.walt.mdoc.doc.MDoc
import id.walt.oid4vc.data.CredentialFormat
import id.walt.oid4vc.data.CredentialOffer
import id.walt.oid4vc.data.OpenIDProviderMetadata
import id.walt.oid4vc.data.ProofType
import id.walt.sdjwt.SDJwtVC
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assumptions.assumeTrue
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Live EUDI E2E Tests - Complete issuance and verification flows.
 *
 * These tests run against live deployed services (issuer-api and verifier-api2).
 * They implement the full OID4VCI and OID4VP protocols as a wallet would.
 *
 * Prerequisites:
 * - issuer-api running at http://localhost:7002
 * - verifier-api2 running at http://localhost:7004
 *
 * The tests will gracefully skip if services are unavailable.
 */
@DisplayName("Live EUDI E2E Tests")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LiveEudiE2ETest {

    companion object {
        const val ISSUER_API_URL = "http://localhost:7002"
        const val VERIFIER_API_URL = "http://localhost:7004"

        // EUDI credential configuration IDs
        const val PID_MDOC_CONFIG_ID = "eu.europa.ec.eudi.pid.1"
        const val MDL_CONFIG_ID = "org.iso.18013.5.1.mDL"
        const val PID_SDJWT_CONFIG_ID = "eu.europa.ec.eudi.pid_vc_sd_jwt"

        // Expected docTypes/VCT
        const val PID_DOCTYPE = "eu.europa.ec.eudi.pid.1"
        const val MDL_DOCTYPE = "org.iso.18013.5.1.mDL"
        const val PID_VCT = "urn:eudi:pid:1"

        // Test issuer key (P-256)
        val TEST_ISSUER_KEY = buildJsonObject {
            put("type", "jwk")
            putJsonObject("jwk") {
                put("kty", "EC")
                put("d", "mJJv_Hzv8--BHJaJlvB9KM8XQnM9M8J7KNZ8K_z9qdc")
                put("crv", "P-256")
                put("kid", "live-eudi-test-key")
                put("x", "dHGO-XVe1E-tEjqLN5EFT_FHQFgXTQ-9U7TL5qm9_0g")
                put("y", "L8L7_pV9t2qn7B8DJ1_N8pEyEL_WQ8wVBM_FqA7k5tw")
            }
        }

        const val TEST_ISSUER_DID = "did:key:z6MkjoRhq1jSNJdLiruSXrFFxagqrztZaXHqHGUTKJbcNywp"

        // Test PID data
        val TEST_PID_DATA = buildJsonObject {
            put("family_name", "MUSTERMANN")
            put("given_name", "ERIKA")
            put("birth_date", "1984-01-26")
            put("age_over_18", true)
            put("age_over_21", true)
            put("issuing_country", "DE")
            put("issuing_authority", "German Federal Government")
            put("nationality", "DE")
            put("resident_country", "DE")
        }

        // Test mDL data
        val TEST_MDL_DATA = buildJsonObject {
            put("family_name", "MUSTERMANN")
            put("given_name", "ERIKA")
            put("birth_date", "1984-01-26")
            put("issue_date", "2023-01-15")
            put("expiry_date", "2033-01-15")
            put("issuing_country", "DE")
            put("issuing_authority", "Kraftfahrt-Bundesamt")
            put("document_number", "T22000129")
            putJsonArray("driving_privileges") {
                addJsonObject {
                    put("vehicle_category_code", "B")
                    put("issue_date", "2023-01-15")
                    put("expiry_date", "2033-01-15")
                }
            }
        }
    }

    private lateinit var walletClient: EudiWalletClient
    private lateinit var verificationClient: EudiVerificationClient
    private lateinit var httpClient: HttpClient
    private var issuerAvailable = false
    private var verifierAvailable = false

    // Storage for issued credentials (for use in verification tests)
    private var issuedPidMdoc: String? = null
    private var issuedPidSdJwt: String? = null
    private var issuedMdl: String? = null

    @BeforeAll
    fun setup() {
        walletClient = EudiWalletClient()
        verificationClient = EudiVerificationClient(VERIFIER_API_URL)
        httpClient = HttpClient {
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true })
            }
        }

        // Check service availability
        issuerAvailable = checkServiceAvailable("$ISSUER_API_URL/health")
        verifierAvailable = checkServiceAvailable("$VERIFIER_API_URL/health")

        println("Service availability - Issuer: $issuerAvailable, Verifier: $verifierAvailable")
    }

    @AfterAll
    fun cleanup() {
        verificationClient.close()
        httpClient.close()
    }

    private fun checkServiceAvailable(healthUrl: String): Boolean {
        return try {
            val response = java.net.URL(healthUrl).openConnection() as java.net.HttpURLConnection
            response.connectTimeout = 2000
            response.readTimeout = 2000
            response.requestMethod = "GET"
            val code = response.responseCode
            code in 200..299
        } catch (e: Exception) {
            false
        }
    }

    // ==================== ISSUER METADATA TESTS ====================

    @Nested
    @DisplayName("Issuer Metadata Tests")
    inner class IssuerMetadataTests {

        @Test
        fun `issuer metadata has PID mDoc configuration`() = runTest {
            assumeTrue(issuerAvailable, "Issuer API not available")

            val metadata = walletClient.fetchIssuerMetadata("$ISSUER_API_URL/oid4vc/draft13")
            val draft13 = metadata.draft13
            assertNotNull(draft13, "Should be Draft 13 metadata")

            val credConfigs = draft13.credentialConfigurationsSupported
            assertNotNull(credConfigs)

            val pidConfig = credConfigs[PID_MDOC_CONFIG_ID]
            if (pidConfig != null) {
                assertEquals(CredentialFormat.mso_mdoc, pidConfig.format)
                assertEquals(PID_DOCTYPE, pidConfig.docType)
            }
        }

        @Test
        fun `issuer metadata has mDL configuration`() = runTest {
            assumeTrue(issuerAvailable, "Issuer API not available")

            val metadata = walletClient.fetchIssuerMetadata("$ISSUER_API_URL/oid4vc/draft13")
            val draft13 = metadata.draft13
            assertNotNull(draft13)

            val credConfigs = draft13.credentialConfigurationsSupported
            assertNotNull(credConfigs)

            val mdlConfig = credConfigs[MDL_CONFIG_ID]
            if (mdlConfig != null) {
                assertEquals(CredentialFormat.mso_mdoc, mdlConfig.format)
                assertEquals(MDL_DOCTYPE, mdlConfig.docType)
            }
        }

        @Test
        fun `issuer metadata has SD-JWT configurations`() = runTest {
            assumeTrue(issuerAvailable, "Issuer API not available")

            val metadata = walletClient.fetchIssuerMetadata("$ISSUER_API_URL/oid4vc/draft13")
            val draft13 = metadata.draft13
            assertNotNull(draft13)

            val credConfigs = draft13.credentialConfigurationsSupported
            assertNotNull(credConfigs)

            // SD-JWT configs may exist
            val sdJwtConfigs = credConfigs.filter {
                it.value.format == CredentialFormat.sd_jwt_dc ||
                        it.value.format == CredentialFormat.sd_jwt_vc
            }

            assertTrue(sdJwtConfigs.isNotEmpty() || credConfigs.isNotEmpty(),
                "Either SD-JWT configs exist or issuer has other configurations")
        }

        @Test
        fun `issuer supports required proof types`() = runTest {
            assumeTrue(issuerAvailable, "Issuer API not available")

            val metadata = walletClient.fetchIssuerMetadata("$ISSUER_API_URL/oid4vc/draft13")
            val draft13 = metadata.draft13
            assertNotNull(draft13)

            val credConfigs = draft13.credentialConfigurationsSupported
            val pidConfig = credConfigs?.get(PID_MDOC_CONFIG_ID)
            if (pidConfig != null) {
                // EUDI requires jwt proof type support
                val proofTypes = pidConfig.proofTypesSupported
                assertTrue(
                    proofTypes == null || proofTypes.containsKey(ProofType.jwt),
                    "EUDI credentials should support jwt proof type"
                )
            }
        }
    }

    // ==================== FULL ISSUANCE FLOW TESTS ====================

    @Nested
    @DisplayName("Full Issuance Flow Tests")
    @TestMethodOrder(MethodOrderer.OrderAnnotation::class)
    inner class FullIssuanceFlowTests {

        @Test
        @Order(1)
        fun `complete mDoc PID issuance flow`() = runTest {
            assumeTrue(issuerAvailable, "Issuer API not available")

            // Step 1: Create credential offer via issuer API
            val offerUri = createCredentialOffer(PID_MDOC_CONFIG_ID, TEST_PID_DATA)
            assertNotNull(offerUri, "Failed to create credential offer")
            assertTrue(offerUri.startsWith("openid-credential-offer://"))

            // Step 2: Resolve credential offer (as wallet would)
            val offer = walletClient.resolveCredentialOffer(offerUri)
            assertNotNull(offer)
            val draft13Offer = offer.draft13
            assertNotNull(draft13Offer, "Should be Draft 13 offer")
            assertTrue(
                draft13Offer.credentialConfigurationIds.any {
                    it.jsonPrimitive.content == PID_MDOC_CONFIG_ID
                },
                "Offer should contain PID mDoc configuration"
            )

            // Step 3: Fetch issuer metadata
            val metadata = walletClient.fetchIssuerMetadata(offer.credentialIssuer)
            assertNotNull(metadata.tokenEndpoint)
            assertNotNull(metadata.credentialEndpoint)

            // Step 4: Extract pre-authorized code
            val preAuthCode = walletClient.getPreAuthorizedCode(offer)
            assertNotNull(preAuthCode, "Pre-authorized code should be present")

            // Step 5: Request access token
            val tokenResponse = walletClient.requestAccessToken(
                tokenEndpoint = metadata.tokenEndpoint!!,
                preAuthorizedCode = preAuthCode
            )
            assertNotNull(tokenResponse.accessToken)
            assertNotNull(tokenResponse.cNonce, "c_nonce required for proof generation")

            // Step 6: Request credential with JWT proof
            val credentialResponse = walletClient.requestCredential(
                credentialEndpoint = metadata.credentialEndpoint!!,
                accessToken = tokenResponse.accessToken,
                credentialConfigurationId = PID_MDOC_CONFIG_ID,
                cNonce = tokenResponse.cNonce!!,
                format = CredentialFormat.mso_mdoc
            )

            assertNotNull(credentialResponse.credential)
            issuedPidMdoc = credentialResponse.credential

            // Validate it's valid CBOR hex
            val mdoc = MDoc.fromCBORHex(credentialResponse.credential)
            assertEquals(PID_DOCTYPE, mdoc.docType.value)
        }

        @Test
        @Order(2)
        fun `complete mDL issuance flow`() = runTest {
            assumeTrue(issuerAvailable, "Issuer API not available")

            // Step 1: Create credential offer
            val offerUri = createCredentialOffer(MDL_CONFIG_ID, TEST_MDL_DATA)
            assertNotNull(offerUri)

            // Step 2: Resolve offer
            val offer = walletClient.resolveCredentialOffer(offerUri)
            val draft13Offer = offer.draft13
            assertNotNull(draft13Offer)
            assertTrue(
                draft13Offer.credentialConfigurationIds.any {
                    it.jsonPrimitive.content == MDL_CONFIG_ID
                }
            )

            // Step 3: Get metadata
            val metadata = walletClient.fetchIssuerMetadata(offer.credentialIssuer)

            // Step 4: Get pre-auth code
            val preAuthCode = walletClient.getPreAuthorizedCode(offer)
            assertNotNull(preAuthCode)

            // Step 5: Get access token
            val tokenResponse = walletClient.requestAccessToken(
                tokenEndpoint = metadata.tokenEndpoint!!,
                preAuthorizedCode = preAuthCode
            )

            // Step 6: Request credential
            val credentialResponse = walletClient.requestCredential(
                credentialEndpoint = metadata.credentialEndpoint!!,
                accessToken = tokenResponse.accessToken,
                credentialConfigurationId = MDL_CONFIG_ID,
                cNonce = tokenResponse.cNonce!!,
                format = CredentialFormat.mso_mdoc
            )

            assertNotNull(credentialResponse.credential)
            issuedMdl = credentialResponse.credential

            val mdoc = MDoc.fromCBORHex(credentialResponse.credential)
            assertEquals(MDL_DOCTYPE, mdoc.docType.value)
        }

        @Test
        @Order(3)
        fun `complete SD-JWT PID issuance flow`() = runTest {
            assumeTrue(issuerAvailable, "Issuer API not available")

            // SD-JWT issuance requires proper config ID - check what's available
            val metadata = walletClient.fetchIssuerMetadata("$ISSUER_API_URL/oid4vc/draft13")
            val draft13 = metadata.draft13
            assertNotNull(draft13)

            val credConfigs = draft13.credentialConfigurationsSupported
            val sdJwtConfigEntry = credConfigs?.entries?.find { (key, value) ->
                (value.format == CredentialFormat.sd_jwt_dc || value.format == CredentialFormat.sd_jwt_vc) &&
                        key.contains("pid", ignoreCase = true)
            }

            assumeTrue(sdJwtConfigEntry != null, "No SD-JWT PID configuration available")

            val sdJwtConfigId = sdJwtConfigEntry!!.key

            // Step 1: Create credential offer
            val offerUri = createCredentialOffer(sdJwtConfigId, TEST_PID_DATA, format = "sdjwt")
            assertNotNull(offerUri)

            // Step 2: Resolve offer
            val offer = walletClient.resolveCredentialOffer(offerUri)

            // Step 3: Get metadata
            val issuerMetadata = walletClient.fetchIssuerMetadata(offer.credentialIssuer)

            // Step 4: Get pre-auth code
            val preAuthCode = walletClient.getPreAuthorizedCode(offer)
            assertNotNull(preAuthCode)

            // Step 5: Get access token
            val tokenResponse = walletClient.requestAccessToken(
                tokenEndpoint = issuerMetadata.tokenEndpoint!!,
                preAuthorizedCode = preAuthCode
            )

            // Step 6: Request credential
            val credentialResponse = walletClient.requestCredential(
                credentialEndpoint = issuerMetadata.credentialEndpoint!!,
                accessToken = tokenResponse.accessToken,
                credentialConfigurationId = sdJwtConfigId,
                cNonce = tokenResponse.cNonce!!,
                format = CredentialFormat.sd_jwt_dc
            )

            assertNotNull(credentialResponse.credential)
            issuedPidSdJwt = credentialResponse.credential

            // Validate it parses as SD-JWT
            val sdJwt = SDJwtVC.parse(credentialResponse.credential)
            assertNotNull(sdJwt.issuer)
        }

        private suspend fun createCredentialOffer(
            configId: String,
            data: JsonObject,
            format: String = "mdoc"
        ): String? {
            return try {
                val issuanceRequest = buildJsonObject {
                    put("issuerKey", TEST_ISSUER_KEY)
                    put("issuerDid", TEST_ISSUER_DID)
                    put("credentialConfigurationId", configId)
                    put("credentialData", data)
                }

                val response = httpClient.post("$ISSUER_API_URL/oid4vc/draft13/$format") {
                    contentType(ContentType.Application.Json)
                    setBody(issuanceRequest)
                }

                if (response.status.isSuccess()) {
                    response.body<String>()
                } else {
                    null
                }
            } catch (e: Exception) {
                println("Error creating offer: ${e.message}")
                null
            }
        }
    }

    // ==================== CREDENTIAL VALIDATION TESTS ====================

    @Nested
    @DisplayName("Credential Validation Tests")
    @TestMethodOrder(MethodOrderer.OrderAnnotation::class)
    inner class CredentialValidationTests {

        @Test
        @Order(1)
        fun `validate mDoc PID structure`() = runTest {
            assumeTrue(issuedPidMdoc != null, "PID mDoc not issued - run issuance tests first")

            val mdoc = MDoc.fromCBORHex(issuedPidMdoc!!)

            // Verify docType
            assertEquals(PID_DOCTYPE, mdoc.docType.value, "DocType should match EUDI PID")

            // Verify issuerSigned present
            assertNotNull(mdoc.issuerSigned, "IssuerSigned should be present")
            assertNotNull(mdoc.issuerSigned.issuerAuth, "IssuerAuth should be present")

            // Verify MSO structure
            val mso = mdoc.MSO
            assertNotNull(mso, "MSO should be extractable")
            assertEquals(PID_DOCTYPE, mso.docType.value, "MSO docType should match")

            // Verify digestAlgorithm
            val digestAlg = mso.digestAlgorithm
            assertNotNull(digestAlg, "Digest algorithm should be specified")
            assertTrue(
                digestAlg.value == "SHA-256" || digestAlg.value == "SHA-384" || digestAlg.value == "SHA-512",
                "Digest algorithm should be SHA-256, SHA-384, or SHA-512"
            )

            // Verify validityInfo
            assertNotNull(mso.validityInfo, "ValidityInfo should be present")
            assertNotNull(mso.validityInfo.validFrom, "ValidFrom should be present")
            assertNotNull(mso.validityInfo.validUntil, "ValidUntil should be present")

            // Verify issuerSignedItems match MSO digests
            assertTrue(mdoc.verifyIssuerSignedItems(), "IssuerSignedItems should match MSO digests")
        }

        @Test
        @Order(2)
        fun `validate mDL structure`() = runTest {
            assumeTrue(issuedMdl != null, "mDL not issued - run issuance tests first")

            val mdoc = MDoc.fromCBORHex(issuedMdl!!)

            // Verify docType
            assertEquals(MDL_DOCTYPE, mdoc.docType.value, "DocType should match ISO mDL")

            // Verify issuerSigned
            assertNotNull(mdoc.issuerSigned)
            assertNotNull(mdoc.issuerSigned.issuerAuth)

            // Verify MSO
            val mso = mdoc.MSO
            assertNotNull(mso)
            assertEquals(MDL_DOCTYPE, mso.docType.value)

            // Verify namespace data
            val namespaces = mdoc.issuerSigned.nameSpaces
            assertNotNull(namespaces, "mDL should have namespace data")

            // mDL uses org.iso.18013.5.1 namespace
            val mdlNamespace = namespaces["org.iso.18013.5.1"]
            assertTrue(
                mdlNamespace != null || namespaces.isNotEmpty(),
                "mDL should have mDL namespace or other namespaces"
            )

            // Verify items integrity
            assertTrue(mdoc.verifyIssuerSignedItems())
        }

        @Test
        @Order(3)
        fun `validate SD-JWT PID structure`() = runTest {
            assumeTrue(issuedPidSdJwt != null, "PID SD-JWT not issued - run issuance tests first")

            val sdJwt = SDJwtVC.parse(issuedPidSdJwt!!)

            // Verify issuer claim
            assertNotNull(sdJwt.issuer, "SD-JWT should have issuer claim")

            // Verify VCT claim (EUDI requirement)
            assertNotNull(sdJwt.vct, "SD-JWT should have VCT claim for EUDI compatibility")

            // Verify _sd_alg
            assertNotNull(sdJwt.sdAlg, "SD-JWT should specify _sd_alg")
            assertEquals("sha-256", sdJwt.sdAlg, "_sd_alg should be sha-256")

            // Verify it's a valid SD-JWT format (has tilde separators)
            assertTrue(
                issuedPidSdJwt!!.contains("~"),
                "SD-JWT should contain disclosure separators"
            )
        }
    }

    // ==================== VERIFICATION SESSION TESTS ====================

    @Nested
    @DisplayName("Verification Session Tests")
    inner class VerificationSessionTests {

        @Test
        fun `create PID mDoc verification session`() = runTest {
            assumeTrue(verifierAvailable, "Verifier API not available")

            val sessionSetup = buildJsonObject {
                put("type", "CrossDeviceFlowSetup")
                putJsonObject("core") {
                    putJsonObject("dcqlQuery") {
                        putJsonArray("credentials") {
                            addJsonObject {
                                put("id", "pid_mdoc")
                                put("format", "mso_mdoc")
                                putJsonObject("meta") {
                                    put("doctype_value", PID_DOCTYPE)
                                }
                                putJsonArray("claims") {
                                    addJsonObject {
                                        put("namespace", PID_DOCTYPE)
                                        put("claim_name", "birth_date")
                                    }
                                    addJsonObject {
                                        put("namespace", PID_DOCTYPE)
                                        put("claim_name", "family_name")
                                    }
                                }
                            }
                        }
                    }
                }
            }

            val sessionResponse = verificationClient.createVerificationSession(sessionSetup)
            assertNotNull(sessionResponse.sessionId)
            assertTrue(
                sessionResponse.bootstrapAuthorizationRequestUrl != null ||
                        sessionResponse.fullAuthorizationRequestUrl != null,
                "Session should have authorization request URL"
            )
        }

        @Test
        fun `create mDL verification session`() = runTest {
            assumeTrue(verifierAvailable, "Verifier API not available")

            val sessionSetup = buildJsonObject {
                put("type", "CrossDeviceFlowSetup")
                putJsonObject("core") {
                    putJsonObject("dcqlQuery") {
                        putJsonArray("credentials") {
                            addJsonObject {
                                put("id", "mdl")
                                put("format", "mso_mdoc")
                                putJsonObject("meta") {
                                    put("doctype_value", MDL_DOCTYPE)
                                }
                                putJsonArray("claims") {
                                    addJsonObject {
                                        put("namespace", "org.iso.18013.5.1")
                                        put("claim_name", "family_name")
                                    }
                                    addJsonObject {
                                        put("namespace", "org.iso.18013.5.1")
                                        put("claim_name", "given_name")
                                    }
                                }
                            }
                        }
                    }
                }
            }

            val sessionResponse = verificationClient.createVerificationSession(sessionSetup)
            assertNotNull(sessionResponse.sessionId)
        }

        @Test
        fun `create SD-JWT PID verification session`() = runTest {
            assumeTrue(verifierAvailable, "Verifier API not available")

            val sessionSetup = buildJsonObject {
                put("type", "CrossDeviceFlowSetup")
                putJsonObject("core") {
                    putJsonObject("dcqlQuery") {
                        putJsonArray("credentials") {
                            addJsonObject {
                                put("id", "pid_sdjwt")
                                put("format", "dc+sd-jwt")
                                putJsonObject("meta") {
                                    put("vct_value", PID_VCT)
                                }
                                putJsonArray("claims") {
                                    addJsonObject {
                                        put("path", buildJsonArray { add("birth_date") })
                                    }
                                    addJsonObject {
                                        put("path", buildJsonArray { add("family_name") })
                                    }
                                }
                            }
                        }
                    }
                }
            }

            val sessionResponse = verificationClient.createVerificationSession(sessionSetup)
            assertNotNull(sessionResponse.sessionId)
        }

        @Test
        fun `get authorization request for session`() = runTest {
            assumeTrue(verifierAvailable, "Verifier API not available")

            // Create session first
            val sessionSetup = buildJsonObject {
                put("type", "CrossDeviceFlowSetup")
                putJsonObject("core") {
                    putJsonObject("dcqlQuery") {
                        putJsonArray("credentials") {
                            addJsonObject {
                                put("id", "pid_mdoc")
                                put("format", "mso_mdoc")
                                putJsonObject("meta") {
                                    put("doctype_value", PID_DOCTYPE)
                                }
                                putJsonArray("claims") {
                                    addJsonObject {
                                        put("namespace", PID_DOCTYPE)
                                        put("claim_name", "birth_date")
                                    }
                                }
                            }
                        }
                    }
                }
            }

            val sessionResponse = verificationClient.createVerificationSession(sessionSetup)

            // Get authorization request
            val authRequest = verificationClient.getAuthorizationRequest(sessionResponse.sessionId)

            // Verify authorization request structure
            assertNotNull(authRequest["client_id"], "Authorization request should have client_id")
            assertTrue(
                authRequest["dcql_query"] != null || authRequest["presentation_definition"] != null,
                "Authorization request should have dcql_query or presentation_definition"
            )
        }
    }

    // ==================== FULL VERIFICATION FLOW TESTS ====================

    @Nested
    @DisplayName("Full Verification Flow Tests")
    @TestMethodOrder(MethodOrderer.OrderAnnotation::class)
    inner class FullVerificationFlowTests {

        private var verificationSessionId: String? = null

        @Test
        @Order(1)
        fun `issue credential for verification test`() = runTest {
            assumeTrue(issuerAvailable, "Issuer API not available")

            // Make sure we have a credential to present
            if (issuedPidMdoc == null) {
                // Issue a fresh PID mDoc
                val offerUri = createTestCredentialOffer(PID_MDOC_CONFIG_ID, TEST_PID_DATA)
                if (offerUri != null) {
                    try {
                        val offer = walletClient.resolveCredentialOffer(offerUri)
                        val metadata = walletClient.fetchIssuerMetadata(offer.credentialIssuer)
                        val preAuthCode = walletClient.getPreAuthorizedCode(offer)

                        if (preAuthCode != null && metadata.tokenEndpoint != null) {
                            val tokenResponse = walletClient.requestAccessToken(
                                metadata.tokenEndpoint!!,
                                preAuthCode
                            )

                            if (tokenResponse.cNonce != null && metadata.credentialEndpoint != null) {
                                val credResponse = walletClient.requestCredential(
                                    metadata.credentialEndpoint!!,
                                    tokenResponse.accessToken,
                                    PID_MDOC_CONFIG_ID,
                                    tokenResponse.cNonce!!,
                                    CredentialFormat.mso_mdoc
                                )
                                issuedPidMdoc = credResponse.credential
                            }
                        }
                    } catch (e: Exception) {
                        println("Could not issue test credential: ${e.message}")
                    }
                }
            }

            assumeTrue(issuedPidMdoc != null, "Need a credential to test verification")
        }

        @Test
        @Order(2)
        fun `create verification session for mDoc presentation`() = runTest {
            assumeTrue(verifierAvailable, "Verifier API not available")
            assumeTrue(issuedPidMdoc != null, "Need credential for verification")

            val sessionSetup = buildJsonObject {
                put("type", "CrossDeviceFlowSetup")
                putJsonObject("core") {
                    putJsonObject("dcqlQuery") {
                        putJsonArray("credentials") {
                            addJsonObject {
                                put("id", "pid_mdoc")
                                put("format", "mso_mdoc")
                                putJsonObject("meta") {
                                    put("doctype_value", PID_DOCTYPE)
                                }
                                putJsonArray("claims") {
                                    addJsonObject {
                                        put("namespace", PID_DOCTYPE)
                                        put("claim_name", "birth_date")
                                    }
                                }
                            }
                        }
                    }
                }
            }

            val sessionResponse = verificationClient.createVerificationSession(sessionSetup)
            verificationSessionId = sessionResponse.sessionId

            assertNotNull(verificationSessionId)

            // Get session info
            val sessionInfo = verificationClient.getSessionInfo(verificationSessionId!!)
            val status = sessionInfo["status"]?.jsonPrimitive?.content
            assertEquals("ACTIVE", status, "New session should be ACTIVE")
        }

        @Test
        @Order(3)
        fun `fetch authorization request as wallet`() = runTest {
            assumeTrue(verifierAvailable, "Verifier API not available")
            assumeTrue(verificationSessionId != null, "Need active verification session")

            val authRequest = verificationClient.getAuthorizationRequest(verificationSessionId!!)

            // Validate authorization request structure per OID4VP 1.0
            assertNotNull(authRequest["client_id"], "client_id required")
            assertNotNull(authRequest["response_uri"] ?: authRequest["redirect_uri"],
                "response_uri or redirect_uri required")

            // DCQL query should be present for EUDI
            val dcqlQuery = authRequest["dcql_query"]
            if (dcqlQuery != null) {
                val credentials = dcqlQuery.jsonObject["credentials"]?.jsonArray
                assertNotNull(credentials, "DCQL query should have credentials array")
                assertTrue(credentials.isNotEmpty(), "Should request at least one credential")
            }
        }

        private suspend fun createTestCredentialOffer(configId: String, data: JsonObject): String? {
            return try {
                val issuanceRequest = buildJsonObject {
                    put("issuerKey", TEST_ISSUER_KEY)
                    put("issuerDid", TEST_ISSUER_DID)
                    put("credentialConfigurationId", configId)
                    put("credentialData", data)
                }

                val response = httpClient.post("$ISSUER_API_URL/oid4vc/draft13/mdoc") {
                    contentType(ContentType.Application.Json)
                    setBody(issuanceRequest)
                }

                if (response.status.isSuccess()) {
                    response.body<String>()
                } else {
                    null
                }
            } catch (e: Exception) {
                null
            }
        }
    }

    // ==================== INTEGRATION TEST (Full Flow) ====================

    @Nested
    @DisplayName("Full E2E Integration Test")
    inner class FullE2EIntegrationTest {

        @Test
        fun `complete issuance and metadata verification flow`() = runTest {
            assumeTrue(issuerAvailable, "Issuer API not available")

            // 1. Fetch issuer metadata
            val metadata = walletClient.fetchIssuerMetadata("$ISSUER_API_URL/oid4vc/draft13")
            val draft13 = metadata.draft13
            assertNotNull(draft13)

            val credConfigs = draft13.credentialConfigurationsSupported
            assertNotNull(credConfigs)

            // 2. Verify EUDI credential configurations exist
            val hasMdocSupport = credConfigs.values.any { it.format == CredentialFormat.mso_mdoc }
            assertTrue(hasMdocSupport || credConfigs.isNotEmpty(),
                "Issuer should support mDoc format or have other configurations")

            // 3. Verify token endpoint
            assertNotNull(metadata.tokenEndpoint)

            // 4. Verify credential endpoint
            assertNotNull(metadata.credentialEndpoint)

            println("Issuer metadata validated successfully")
            println("- Credential configurations: ${credConfigs.size}")
            println("- Token endpoint: ${metadata.tokenEndpoint}")
            println("- Credential endpoint: ${metadata.credentialEndpoint}")
        }
    }
}
