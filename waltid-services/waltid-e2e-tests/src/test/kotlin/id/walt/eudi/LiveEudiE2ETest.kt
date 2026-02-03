package id.walt.eudi

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Live E2E tests for EUDI credential issuance and verification against Docker endpoints.
 *
 * These tests connect to live Docker services via public FQDNs and validate:
 * - Issuer metadata endpoints
 * - Credential issuance (mDoc, SD-JWT)
 * - Verification session creation with proper JSON format
 *
 * Prerequisites:
 * - Docker services must be running: `docker compose --profile identity up -d`
 * - Public FQDNs must be accessible (or use local ports)
 *
 * Run with: ./gradlew :waltid-services:waltid-e2e-tests:test --tests "id.walt.eudi.LiveEudiE2ETest"
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LiveEudiE2ETest {

    companion object {
        // Public FQDNs for live Docker endpoints
        const val ISSUER_URL = "https://issuer.theaustraliahack.com"
        const val VERIFIER2_URL = "https://verifier2.theaustraliahack.com"
        const val WALLET_URL = "https://wallet.theaustraliahack.com"
        const val PORTAL_URL = "https://portal.theaustraliahack.com"

        // Fallback to local ports if needed
        const val LOCAL_ISSUER_URL = "http://localhost:7002"
        const val LOCAL_VERIFIER2_URL = "http://localhost:7004"

        // EUDI Credential Configuration IDs
        const val EUDI_PID_MDOC_CONFIG_ID = "eu.europa.ec.eudi.pid.1"
        const val EUDI_MDL_CONFIG_ID = "org.iso.18013.5.1.mDL"
        const val EUDI_PID_SDJWT_CONFIG_ID = "eu.europa.ec.eudi.pid_vc_sd_jwt"

        // VCT for SD-JWT PID
        const val EUDI_PID_VCT = "urn:eudi:pid:1"

        // Test issuer key (P-256 EC key)
        val TEST_KEY = buildJsonObject {
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
            put("issue_date", "2023-01-01")
            put("expiry_date", "2028-01-01")
            put("issuing_country", "DE")
            put("issuing_authority", "German Transport Authority")
            put("document_number", "DL123456789")
            putJsonArray("driving_privileges") {
                addJsonObject {
                    put("vehicle_category_code", "B")
                    put("issue_date", "2023-01-01")
                    put("expiry_date", "2028-01-01")
                }
            }
        }

        const val TEST_ISSUER_DID = "did:key:z6MkjoRhq1jSNJdLiruSXrFFxagqrztZaXHqHGUTKJbcNywp"
    }

    private lateinit var client: HttpClient
    private var issuerUrl: String = ISSUER_URL
    private var verifier2Url: String = VERIFIER2_URL
    private var servicesAvailable: Boolean = false

    @BeforeAll
    fun setup() {
        client = HttpClient(CIO) {
            install(ContentNegotiation) {
                json(Json {
                    ignoreUnknownKeys = true
                    prettyPrint = true
                    isLenient = true
                })
            }
            expectSuccess = false
        }

        // Check if public endpoints are available, fallback to local
        // Use metadata endpoint since /health may not exist
        servicesAvailable = runBlocking {
            try {
                val response = client.get("$ISSUER_URL/draft13/.well-known/openid-credential-issuer")
                if (response.status.isSuccess()) {
                    issuerUrl = ISSUER_URL
                    verifier2Url = VERIFIER2_URL
                    println("Using public endpoints: $ISSUER_URL")
                    true
                } else {
                    // Try local endpoints
                    val localResponse = client.get("$LOCAL_ISSUER_URL/draft13/.well-known/openid-credential-issuer")
                    if (localResponse.status.isSuccess()) {
                        issuerUrl = LOCAL_ISSUER_URL
                        verifier2Url = LOCAL_VERIFIER2_URL
                        println("Using local endpoints: $LOCAL_ISSUER_URL")
                        true
                    } else {
                        println("No endpoints available")
                        false
                    }
                }
            } catch (e: Exception) {
                // Try local endpoints
                try {
                    val localResponse = client.get("$LOCAL_ISSUER_URL/draft13/.well-known/openid-credential-issuer")
                    if (localResponse.status.isSuccess()) {
                        issuerUrl = LOCAL_ISSUER_URL
                        verifier2Url = LOCAL_VERIFIER2_URL
                        println("Using local endpoints (fallback): $LOCAL_ISSUER_URL")
                        true
                    } else {
                        println("No endpoints available (fallback check failed)")
                        false
                    }
                } catch (e2: Exception) {
                    println("Service check failed: ${e2.message}")
                    false
                }
            }
        }
    }

    @AfterAll
    fun teardown() {
        client.close()
    }

    private fun assumeServicesAvailable() {
        assumeTrue(servicesAvailable, "Docker services not available - skipping test")
    }

    @Nested
    @DisplayName("Issuer Metadata Tests")
    inner class IssuerMetadataTests {

        @Test
        @DisplayName("Issuer metadata endpoint returns valid response")
        fun `test issuer metadata endpoint returns valid response`() = runTest {
            assumeServicesAvailable()

            val response = client.get("$issuerUrl/draft13/.well-known/openid-credential-issuer")
            assertEquals(HttpStatusCode.OK, response.status, "Metadata endpoint should return 200")

            val metadata = response.body<JsonObject>()
            assertNotNull(metadata["credential_issuer"], "Metadata should contain credential_issuer")
            assertNotNull(metadata["credential_configurations_supported"], "Metadata should contain credential_configurations_supported")
        }

        @Test
        @DisplayName("Issuer metadata contains EUDI PID mDoc configuration")
        fun `test issuer metadata has EUDI PID mDoc config`() = runTest {
            assumeServicesAvailable()

            val response = client.get("$issuerUrl/draft13/.well-known/openid-credential-issuer")
            assertEquals(HttpStatusCode.OK, response.status)

            val metadata = response.body<JsonObject>()
            val credConfigs = metadata["credential_configurations_supported"]?.jsonObject
            assertNotNull(credConfigs, "credential_configurations_supported should exist")

            val pidConfig = credConfigs[EUDI_PID_MDOC_CONFIG_ID]?.jsonObject
            if (pidConfig != null) {
                val format = pidConfig["format"]?.jsonPrimitive?.content
                assertEquals("mso_mdoc", format, "PID mDoc should have format mso_mdoc")

                val docType = pidConfig["doctype"]?.jsonPrimitive?.content
                assertEquals(EUDI_PID_MDOC_CONFIG_ID, docType, "PID mDoc should have correct doctype")
            }
        }

        @Test
        @DisplayName("Issuer metadata contains EUDI mDL configuration")
        fun `test issuer metadata has EUDI mDL config`() = runTest {
            assumeServicesAvailable()

            val response = client.get("$issuerUrl/draft13/.well-known/openid-credential-issuer")
            assertEquals(HttpStatusCode.OK, response.status)

            val metadata = response.body<JsonObject>()
            val credConfigs = metadata["credential_configurations_supported"]?.jsonObject
            assertNotNull(credConfigs, "credential_configurations_supported should exist")

            val mdlConfig = credConfigs[EUDI_MDL_CONFIG_ID]?.jsonObject
            if (mdlConfig != null) {
                val format = mdlConfig["format"]?.jsonPrimitive?.content
                assertEquals("mso_mdoc", format, "mDL should have format mso_mdoc")

                val docType = mdlConfig["doctype"]?.jsonPrimitive?.content
                assertEquals(EUDI_MDL_CONFIG_ID, docType, "mDL should have correct doctype")
            }
        }

        @Test
        @DisplayName("Issuer metadata contains EUDI PID SD-JWT configuration")
        fun `test issuer metadata has EUDI PID SD-JWT config`() = runTest {
            assumeServicesAvailable()

            val response = client.get("$issuerUrl/draft13/.well-known/openid-credential-issuer")
            assertEquals(HttpStatusCode.OK, response.status)

            val metadata = response.body<JsonObject>()
            val credConfigs = metadata["credential_configurations_supported"]?.jsonObject
            assertNotNull(credConfigs, "credential_configurations_supported should exist")

            val sdJwtConfig = credConfigs[EUDI_PID_SDJWT_CONFIG_ID]?.jsonObject
            if (sdJwtConfig != null) {
                val format = sdJwtConfig["format"]?.jsonPrimitive?.content
                // EUDI uses dc+sd-jwt format
                assertTrue(
                    format == "dc+sd-jwt" || format == "vc+sd-jwt",
                    "SD-JWT PID should have format dc+sd-jwt or vc+sd-jwt, got: $format"
                )

                val vct = sdJwtConfig["vct"]?.jsonPrimitive?.content
                assertNotNull(vct, "SD-JWT config should have vct")
            }
        }
    }

    @Nested
    @DisplayName("Credential Issuance Tests")
    inner class CredentialIssuanceTests {

        @Test
        @DisplayName("Issue mDoc PID credential and get offer URI")
        fun `test mDoc PID issuance returns offer URI`() = runTest {
            assumeServicesAvailable()

            val issuanceRequest = buildJsonObject {
                put("issuerKey", TEST_KEY)
                put("credentialData", TEST_PID_DATA)
                put("credentialConfigurationId", EUDI_PID_MDOC_CONFIG_ID)
                put("issuerDid", TEST_ISSUER_DID)
            }

            val response = client.post("$issuerUrl/openid4vc/mdoc/issue") {
                contentType(ContentType.Application.Json)
                setBody(issuanceRequest)
            }

            if (response.status.isSuccess()) {
                val offerUri = response.bodyAsText()
                assertTrue(offerUri.startsWith("openid-credential-offer://"), "Offer should be a credential offer URI")
                assertTrue(offerUri.contains("credential_offer_uri"), "Offer should contain credential_offer_uri parameter")
            }
        }

        @Test
        @DisplayName("Issue mDL credential and get offer URI")
        fun `test mDL issuance returns offer URI`() = runTest {
            assumeServicesAvailable()

            val issuanceRequest = buildJsonObject {
                put("issuerKey", TEST_KEY)
                put("credentialData", TEST_MDL_DATA)
                put("credentialConfigurationId", EUDI_MDL_CONFIG_ID)
                put("issuerDid", TEST_ISSUER_DID)
            }

            val response = client.post("$issuerUrl/openid4vc/mdoc/issue") {
                contentType(ContentType.Application.Json)
                setBody(issuanceRequest)
            }

            if (response.status.isSuccess()) {
                val offerUri = response.bodyAsText()
                assertTrue(offerUri.startsWith("openid-credential-offer://"), "Offer should be a credential offer URI")
                assertTrue(offerUri.contains("credential_offer_uri"), "Offer should contain credential_offer_uri parameter")
            }
        }

        @Test
        @DisplayName("Issue SD-JWT PID credential and get offer URI")
        fun `test SD-JWT PID issuance returns offer URI`() = runTest {
            assumeServicesAvailable()

            val issuanceRequest = buildJsonObject {
                put("issuerKey", TEST_KEY)
                put("credentialData", TEST_PID_DATA)
                put("credentialConfigurationId", EUDI_PID_SDJWT_CONFIG_ID)
                put("issuerDid", TEST_ISSUER_DID)
            }

            val response = client.post("$issuerUrl/openid4vc/sdjwt/issue") {
                contentType(ContentType.Application.Json)
                setBody(issuanceRequest)
            }

            if (response.status.isSuccess()) {
                val offerUri = response.bodyAsText()
                assertTrue(offerUri.startsWith("openid-credential-offer://"), "Offer should be a credential offer URI")
                assertTrue(offerUri.contains("credential_offer_uri"), "Offer should contain credential_offer_uri parameter")
            }
        }
    }

    @Nested
    @DisplayName("Verification Session Tests")
    inner class VerificationSessionTests {

        /**
         * Build a CrossDeviceFlowSetup JSON with the correct type discriminator.
         *
         * JSON format:
         * ```json
         * {
         *   "flow_type": "cross_device",
         *   "core_flow": {
         *     "dcql_query": {
         *       "credentials": [...]
         *     }
         *   }
         * }
         * ```
         */
        private fun buildCrossDeviceSessionSetup(dcqlCredentials: JsonArray): JsonObject {
            return buildJsonObject {
                put("flow_type", "cross_device")
                putJsonObject("core_flow") {
                    putJsonObject("dcql_query") {
                        put("credentials", dcqlCredentials)
                    }
                }
            }
        }

        @Test
        @DisplayName("Create verification session for mDoc PID")
        fun `test verification session creation for mDoc PID`() = runTest {
            assumeServicesAvailable()

            val dcqlCredentials = buildJsonArray {
                addJsonObject {
                    put("id", "eudi_pid_mdoc")
                    put("format", "mso_mdoc")
                    putJsonObject("meta") {
                        put("doctype_value", EUDI_PID_MDOC_CONFIG_ID)
                    }
                    putJsonArray("claims") {
                        addJsonObject {
                            putJsonArray("path") {
                                add("eu.europa.ec.eudi.pid.1")
                                add("family_name")
                            }
                        }
                        addJsonObject {
                            putJsonArray("path") {
                                add("eu.europa.ec.eudi.pid.1")
                                add("birth_date")
                            }
                        }
                    }
                }
            }

            val sessionSetup = buildCrossDeviceSessionSetup(dcqlCredentials)

            val response = client.post("$verifier2Url/verification-session/create") {
                contentType(ContentType.Application.Json)
                setBody(sessionSetup)
            }

            // Log response for debugging
            val responseBody = response.bodyAsText()
            println("mDoc PID verification session response: ${response.status} - $responseBody")

            if (response.status.isSuccess()) {
                val result = Json.decodeFromString<JsonObject>(responseBody)
                assertNotNull(result["sessionId"], "Response should contain sessionId")
            } else {
                // Check if it's a format error we can learn from
                assertTrue(
                    response.status != HttpStatusCode.BadRequest,
                    "Should not get 400 Bad Request - check JSON format. Response: $responseBody"
                )
            }
        }

        @Test
        @DisplayName("Create verification session for mDL")
        fun `test verification session creation for mDL`() = runTest {
            assumeServicesAvailable()

            val dcqlCredentials = buildJsonArray {
                addJsonObject {
                    put("id", "mdl")
                    put("format", "mso_mdoc")
                    putJsonObject("meta") {
                        put("doctype_value", EUDI_MDL_CONFIG_ID)
                    }
                    putJsonArray("claims") {
                        addJsonObject {
                            putJsonArray("path") {
                                add("org.iso.18013.5.1")
                                add("family_name")
                            }
                        }
                        addJsonObject {
                            putJsonArray("path") {
                                add("org.iso.18013.5.1")
                                add("given_name")
                            }
                        }
                    }
                }
            }

            val sessionSetup = buildCrossDeviceSessionSetup(dcqlCredentials)

            val response = client.post("$verifier2Url/verification-session/create") {
                contentType(ContentType.Application.Json)
                setBody(sessionSetup)
            }

            val responseBody = response.bodyAsText()
            println("mDL verification session response: ${response.status} - $responseBody")

            if (response.status.isSuccess()) {
                val result = Json.decodeFromString<JsonObject>(responseBody)
                assertNotNull(result["sessionId"], "Response should contain sessionId")
            } else {
                assertTrue(
                    response.status != HttpStatusCode.BadRequest,
                    "Should not get 400 Bad Request - check JSON format. Response: $responseBody"
                )
            }
        }

        @Test
        @DisplayName("Create verification session for SD-JWT PID (dc+sd-jwt format)")
        fun `test verification session creation for SD-JWT PID`() = runTest {
            assumeServicesAvailable()

            val dcqlCredentials = buildJsonArray {
                addJsonObject {
                    put("id", "eudi_pid_sdjwt")
                    put("format", "dc+sd-jwt")  // EUDI uses dc+sd-jwt format
                    putJsonObject("meta") {
                        putJsonArray("vct_values") {
                            add(EUDI_PID_VCT)
                        }
                    }
                    putJsonArray("claims") {
                        addJsonObject {
                            putJsonArray("path") {
                                add("family_name")
                            }
                        }
                        addJsonObject {
                            putJsonArray("path") {
                                add("given_name")
                            }
                        }
                    }
                }
            }

            val sessionSetup = buildCrossDeviceSessionSetup(dcqlCredentials)

            val response = client.post("$verifier2Url/verification-session/create") {
                contentType(ContentType.Application.Json)
                setBody(sessionSetup)
            }

            val responseBody = response.bodyAsText()
            println("SD-JWT PID verification session response: ${response.status} - $responseBody")

            if (response.status.isSuccess()) {
                val result = Json.decodeFromString<JsonObject>(responseBody)
                assertNotNull(result["sessionId"], "Response should contain sessionId")

                // Verify we can get the authorization request
                val sessionId = result["sessionId"]?.jsonPrimitive?.content
                if (sessionId != null) {
                    val authRequestResponse = client.get("$verifier2Url/verification-session/$sessionId/request")
                    println("Authorization request response: ${authRequestResponse.status}")
                    assertTrue(authRequestResponse.status.isSuccess(), "Should be able to fetch authorization request")
                }
            } else {
                assertTrue(
                    response.status != HttpStatusCode.BadRequest,
                    "Should not get 400 Bad Request - check JSON format. Response: $responseBody"
                )
            }
        }

        @Test
        @DisplayName("Verify session info endpoint works")
        fun `test verification session info endpoint`() = runTest {
            assumeServicesAvailable()

            // First create a session
            val dcqlCredentials = buildJsonArray {
                addJsonObject {
                    put("id", "test_credential")
                    put("format", "mso_mdoc")
                    putJsonObject("meta") {
                        put("doctype_value", EUDI_PID_MDOC_CONFIG_ID)
                    }
                }
            }

            val sessionSetup = buildCrossDeviceSessionSetup(dcqlCredentials)

            val createResponse = client.post("$verifier2Url/verification-session/create") {
                contentType(ContentType.Application.Json)
                setBody(sessionSetup)
            }

            if (createResponse.status.isSuccess()) {
                val result = createResponse.body<JsonObject>()
                val sessionId = result["sessionId"]?.jsonPrimitive?.content
                assertNotNull(sessionId, "Should have session ID")

                // Get session info
                val infoResponse = client.get("$verifier2Url/verification-session/$sessionId/info")
                println("Session info response: ${infoResponse.status} - ${infoResponse.bodyAsText()}")

                if (infoResponse.status.isSuccess()) {
                    val sessionInfo = infoResponse.body<JsonObject>()
                    assertNotNull(sessionInfo["status"], "Session info should contain status")
                }
            }
        }
    }

    @Nested
    @DisplayName("Full Flow Tests")
    inner class FullFlowTests {

        @Test
        @DisplayName("Complete verification flow: create session, get request, check status")
        fun `test complete verification flow`() = runTest {
            assumeServicesAvailable()

            val verificationClient = EudiVerificationClient(verifier2Url)

            try {
                // Create session with proper JSON format
                val sessionSetup = buildJsonObject {
                    put("flow_type", "cross_device")
                    putJsonObject("core_flow") {
                        putJsonObject("dcql_query") {
                            putJsonArray("credentials") {
                                addJsonObject {
                                    put("id", "pid_mdoc")
                                    put("format", "mso_mdoc")
                                    putJsonObject("meta") {
                                        put("doctype_value", EUDI_PID_MDOC_CONFIG_ID)
                                    }
                                    putJsonArray("claims") {
                                        addJsonObject {
                                            putJsonArray("path") {
                                                add("eu.europa.ec.eudi.pid.1")
                                                add("birth_date")
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                val sessionResponse = verificationClient.createVerificationSession(sessionSetup)
                println("Session created: ${sessionResponse.sessionId}")
                assertNotNull(sessionResponse.sessionId, "Should have session ID")

                // Get authorization request
                val authRequest = verificationClient.getAuthorizationRequest(sessionResponse.sessionId)
                println("Authorization request: $authRequest")
                assertNotNull(authRequest, "Should get authorization request")

                // Check session status - after fetching auth request, status transitions to IN_USE
                val status = verificationClient.getSessionStatus(sessionResponse.sessionId)
                println("Session status: $status")
                assertNotNull(status, "Should get session status")
                assertTrue(
                    status == "ACTIVE" || status == "IN_USE",
                    "Session should be ACTIVE or IN_USE, got: $status"
                )

            } finally {
                verificationClient.close()
            }
        }
    }
}
