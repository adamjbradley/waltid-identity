package id.walt.issuer.tenant

import id.walt.commons.config.ConfigManager
import id.walt.issuer.issuance.IssuanceRequest
import id.walt.oid4vc.data.AuthenticationMethod
import id.walt.oid4vc.data.GrantType
import id.walt.testConfigs
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.*
import org.junit.jupiter.api.*
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

class TenantOidcProtocolTest {

    companion object {
        @JvmStatic
        @BeforeAll
        fun initConfig() {
            ConfigManager.testWithConfigs(testConfigs)
        }
    }

    @TempDir
    lateinit var tempDir: File

    @BeforeEach
    fun setUp() {
        IssuerTenantStore.resetForTesting()
        IssuerTenantRegistry.resetForTesting()
    }

    @AfterEach
    fun tearDown() {
        IssuerTenantStore.resetForTesting()
        IssuerTenantRegistry.resetForTesting()
    }

    private fun enableStore() {
        IssuerTenantStore.init(tempDir.absolutePath)
    }

    private val bankCredentialConfig: Map<String, JsonElement> = mapOf(
        "BankId" to JsonArray(listOf(JsonPrimitive("VerifiableCredential"), JsonPrimitive("BankId")))
    )

    private fun createTenantWithCerts(
        legalName: String = "Protocol Test Bank",
        country: String = "AU",
        domain: String = "proto.example.com",
        credentialConfigs: Map<String, JsonElement> = bankCredentialConfig
    ): IssuerTenant {
        val store = IssuerTenantStore.instanceOrNull()!!
        val certs = IssuerCertificateService.generateCertificates(legalName, country)
        val wrappedCiTokenKey = JsonObject(mapOf(
            "type" to JsonPrimitive("jwk"),
            "jwk" to certs.ciTokenKeyJwk
        )).toString()
        val tenant = IssuerTenant(
            id = java.util.UUID.randomUUID().toString(),
            legalName = legalName,
            country = country,
            domain = domain,
            contactEmail = "admin@$domain",
            issuerKey = certs.issuerKeyJwk,
            x5Chain = certs.x5Chain,
            iacaCertificate = certs.iacaCertInfo,
            signerCertificate = certs.signerCertInfo,
            ciTokenKey = wrappedCiTokenKey,
            credentialConfigurations = credentialConfigs,
            status = IssuerTenantStatus.ACTIVE,
            createdAt = "2026-01-01T00:00:00Z",
            updatedAt = "2026-01-01T00:00:00Z"
        )
        store.save(tenant)
        return tenant
    }

    private fun createSessionAndGetPreAuthCode(tenant: IssuerTenant): String {
        val provider = IssuerTenantRegistry.getOrCreate(tenant)
        val tokenKey = IssuerTenantRegistry.getTokenKey(tenant)
        val bankIdKey = provider.metadata.credentialConfigurationsSupported!!.keys
            .first { it.contains("BankId") }

        val session = provider.initializeCredentialOffer(
            issuanceRequests = listOf(
                IssuanceRequest(
                    issuerKey = tenant.issuerKey,
                    credentialData = JsonObject(mapOf(
                        "@context" to JsonArray(listOf(JsonPrimitive("https://www.w3.org/2018/credentials/v1"))),
                        "type" to JsonArray(listOf(JsonPrimitive("VerifiableCredential"), JsonPrimitive("BankId"))),
                        "credentialSubject" to JsonObject(mapOf(
                            "accountId" to JsonPrimitive("PROTO-001")
                        ))
                    )),
                    credentialConfigurationId = bankIdKey,
                    authenticationMethod = AuthenticationMethod.PRE_AUTHORIZED,
                )
            ),
            expiresIn = 5.minutes,
            tokenKey = tokenKey
        )

        // Extract pre-authorized code from the credential offer
        val offer = session.credentialOffer!!
        val grants = offer.grants
        val preAuth = grants[GrantType.pre_authorized_code.value]
            ?: throw IllegalStateException("No pre-authorized_code grant in offer")
        return preAuth.preAuthorizedCode
            ?: throw IllegalStateException("No pre-authorized_code value")
    }

    // ===== JWKS Endpoint =====

    @Nested
    inner class JwksEndpoint {

        @Test
        fun `jwks returns keys for active tenant`() = testApplication {
            install(ContentNegotiation) { json() }
            application { tenantOidcRoutes() }
            enableStore()

            val tenant = createTenantWithCerts()
            val response = client.get("/issuers/${tenant.id}/draft13/jwks")
            assertEquals(HttpStatusCode.OK, response.status)

            val bodyText = response.bodyAsText()
            assertTrue(bodyText.isNotBlank(), "JWKS response should not be empty")
        }

        @Test
        fun `jwks returns 404 for unknown tenant`() = testApplication {
            install(ContentNegotiation) { json() }
            application { tenantOidcRoutes() }
            enableStore()

            val response = client.get("/issuers/nonexistent/draft13/jwks")
            assertEquals(HttpStatusCode.NotFound, response.status)
        }
    }

    // ===== OAuth Authorization Server Metadata =====

    @Nested
    inner class OAuthMetadata {

        @Test
        fun `oauth-authorization-server returns tenant-scoped metadata`() = testApplication {
            install(ContentNegotiation) { json() }
            application { tenantOidcRoutes() }
            enableStore()

            val tenant = createTenantWithCerts()
            val response = client.get("/issuers/${tenant.id}/draft13/.well-known/oauth-authorization-server")
            assertEquals(HttpStatusCode.OK, response.status)

            val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            val issuer = body["issuer"]?.jsonPrimitive?.content
            assertNotNull(issuer)
            assertTrue(issuer.contains("/issuers/${tenant.id}"), "OAuth AS metadata should be tenant-scoped")
        }

        @Test
        fun `openid-configuration contains token and credential endpoints`() = testApplication {
            install(ContentNegotiation) { json() }
            application { tenantOidcRoutes() }
            enableStore()

            val tenant = createTenantWithCerts()
            val response = client.get("/issuers/${tenant.id}/draft13/.well-known/openid-configuration")
            assertEquals(HttpStatusCode.OK, response.status)

            val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            val tokenEndpoint = body["token_endpoint"]?.jsonPrimitive?.content
            assertNotNull(tokenEndpoint, "Should have token_endpoint")
            assertTrue(tokenEndpoint.contains("/issuers/${tenant.id}"), "Token endpoint should be tenant-scoped")
        }
    }

    // ===== Credential Offer Resolution =====

    @Nested
    inner class CredentialOfferEndpoint {

        @Test
        fun `credentialOffer returns offer for valid session`() = testApplication {
            install(ContentNegotiation) { json() }
            application { tenantOidcRoutes() }
            enableStore()

            val tenant = createTenantWithCerts()
            val provider = IssuerTenantRegistry.getOrCreate(tenant)
            val bankIdKey = provider.metadata.credentialConfigurationsSupported!!.keys
                .first { it.contains("BankId") }

            val session = provider.initializeCredentialOffer(
                issuanceRequests = listOf(
                    IssuanceRequest(
                        issuerKey = tenant.issuerKey,
                        credentialData = JsonObject(mapOf(
                            "@context" to JsonArray(listOf(JsonPrimitive("https://www.w3.org/2018/credentials/v1"))),
                            "type" to JsonArray(listOf(JsonPrimitive("VerifiableCredential"), JsonPrimitive("BankId"))),
                            "credentialSubject" to JsonObject(mapOf("id" to JsonPrimitive("test")))
                        )),
                        credentialConfigurationId = bankIdKey,
                        authenticationMethod = AuthenticationMethod.NONE,
                    )
                ),
                expiresIn = 5.minutes
            )

            val response = client.get("/issuers/${tenant.id}/draft13/credentialOffer?id=${session.id}")
            assertEquals(HttpStatusCode.OK, response.status)

            val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            assertNotNull(body["credential_issuer"], "Offer should contain credential_issuer")
            assertNotNull(body["credential_configuration_ids"], "Offer should contain credential_configuration_ids")
        }

        @Test
        fun `credentialOffer returns 404 for invalid session`() = testApplication {
            install(ContentNegotiation) { json() }
            application { tenantOidcRoutes() }
            enableStore()

            val tenant = createTenantWithCerts()
            val response = client.get("/issuers/${tenant.id}/draft13/credentialOffer?id=nonexistent-session")
            assertEquals(HttpStatusCode.NotFound, response.status)
        }

        @Test
        fun `credentialOffer returns 400 when id parameter missing`() = testApplication {
            install(ContentNegotiation) { json() }
            application { tenantOidcRoutes() }
            enableStore()

            val tenant = createTenantWithCerts()
            val response = client.get("/issuers/${tenant.id}/draft13/credentialOffer")
            assertEquals(HttpStatusCode.BadRequest, response.status)
        }
    }

    // ===== Token Endpoint =====

    @Nested
    inner class TokenEndpoint {

        @Test
        fun `token endpoint returns access token for valid pre-authorized code`() = testApplication {
            install(ContentNegotiation) { json() }
            application { tenantOidcRoutes() }
            enableStore()

            val tenant = createTenantWithCerts()
            val preAuthCode = createSessionAndGetPreAuthCode(tenant)

            val response = client.post("/issuers/${tenant.id}/draft13/token") {
                contentType(ContentType.Application.FormUrlEncoded)
                setBody("grant_type=urn:ietf:params:oauth:grant-type:pre-authorized_code&pre-authorized_code=$preAuthCode")
            }

            assertEquals(HttpStatusCode.OK, response.status)
            val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            assertNotNull(body["access_token"], "Token response should contain access_token")
            assertNotNull(body["token_type"], "Token response should contain token_type")
        }

        @Test
        fun `token endpoint rejects invalid grant type`() = testApplication {
            install(ContentNegotiation) { json() }
            application { tenantOidcRoutes() }
            enableStore()

            val tenant = createTenantWithCerts()

            val response = client.post("/issuers/${tenant.id}/draft13/token") {
                contentType(ContentType.Application.FormUrlEncoded)
                setBody("grant_type=invalid_grant&code=invalid")
            }

            // Invalid grant type returns 400 or 500 (no global exception handler in test)
            assertTrue(
                response.status != HttpStatusCode.OK,
                "Invalid grant type should not return 200, got: ${response.status}"
            )
        }

        @Test
        fun `token endpoint returns 404 for unknown tenant`() = testApplication {
            install(ContentNegotiation) { json() }
            application { tenantOidcRoutes() }
            enableStore()

            val response = client.post("/issuers/nonexistent/draft13/token") {
                contentType(ContentType.Application.FormUrlEncoded)
                setBody("grant_type=urn:ietf:params:oauth:grant-type:pre-authorized_code&pre-authorized_code=test")
            }

            assertEquals(HttpStatusCode.NotFound, response.status)
        }

        @Test
        fun `token signed with tenant key is isolated from other tenants`() = testApplication {
            install(ContentNegotiation) { json() }
            application { tenantOidcRoutes() }
            enableStore()

            val tenantA = createTenantWithCerts("Bank A", "AU", "banka.example.com")
            val tenantB = createTenantWithCerts("Bank B", "US", "bankb.example.com")

            val preAuthCodeA = createSessionAndGetPreAuthCode(tenantA)
            val preAuthCodeB = createSessionAndGetPreAuthCode(tenantB)

            val responseA = client.post("/issuers/${tenantA.id}/draft13/token") {
                contentType(ContentType.Application.FormUrlEncoded)
                setBody("grant_type=urn:ietf:params:oauth:grant-type:pre-authorized_code&pre-authorized_code=$preAuthCodeA")
            }
            val responseB = client.post("/issuers/${tenantB.id}/draft13/token") {
                contentType(ContentType.Application.FormUrlEncoded)
                setBody("grant_type=urn:ietf:params:oauth:grant-type:pre-authorized_code&pre-authorized_code=$preAuthCodeB")
            }

            assertEquals(HttpStatusCode.OK, responseA.status)
            assertEquals(HttpStatusCode.OK, responseB.status)

            val tokenA = Json.parseToJsonElement(responseA.bodyAsText()).jsonObject["access_token"]?.jsonPrimitive?.content
            val tokenB = Json.parseToJsonElement(responseB.bodyAsText()).jsonObject["access_token"]?.jsonPrimitive?.content

            assertNotNull(tokenA)
            assertNotNull(tokenB)
            assertTrue(tokenA != tokenB, "Different tenants should produce different access tokens")
        }
    }

    // ===== Credential Endpoint =====

    @Nested
    inner class CredentialEndpoint {

        @Test
        fun `credential endpoint returns 401 without authorization header`() = testApplication {
            install(ContentNegotiation) { json() }
            application { tenantOidcRoutes() }
            enableStore()

            val tenant = createTenantWithCerts()
            val response = client.post("/issuers/${tenant.id}/draft13/credential") {
                contentType(ContentType.Application.Json)
                setBody("""{"format":"jwt_vc_json"}""")
            }

            assertEquals(HttpStatusCode.Unauthorized, response.status)
        }

        @Test
        fun `credential endpoint rejects invalid token`() = testApplication {
            install(ContentNegotiation) { json() }
            application { tenantOidcRoutes() }
            enableStore()

            val tenant = createTenantWithCerts()
            val response = client.post("/issuers/${tenant.id}/draft13/credential") {
                contentType(ContentType.Application.Json)
                header(HttpHeaders.Authorization, "Bearer invalid-token-value")
                setBody("""{"format":"jwt_vc_json"}""")
            }

            // Invalid JWT token causes verification error (401, 400, or 500 without global handler)
            assertTrue(
                response.status != HttpStatusCode.OK,
                "Invalid token should not return 200, got ${response.status}"
            )
        }

        @Test
        fun `credential endpoint returns 401 with empty bearer`() = testApplication {
            install(ContentNegotiation) { json() }
            application { tenantOidcRoutes() }
            enableStore()

            val tenant = createTenantWithCerts()
            val response = client.post("/issuers/${tenant.id}/draft13/credential") {
                contentType(ContentType.Application.Json)
                header(HttpHeaders.Authorization, "Bearer ")
                setBody("""{"format":"jwt_vc_json"}""")
            }

            assertEquals(HttpStatusCode.Unauthorized, response.status)
        }
    }

    // ===== PAR Endpoint =====

    @Nested
    inner class PAREndpoint {

        @Test
        fun `par returns 404 for unknown tenant`() = testApplication {
            install(ContentNegotiation) { json() }
            application { tenantOidcRoutes() }
            enableStore()

            val response = client.post("/issuers/nonexistent/draft13/par") {
                contentType(ContentType.Application.FormUrlEncoded)
                setBody("response_type=code&client_id=test")
            }

            assertEquals(HttpStatusCode.NotFound, response.status)
        }

        @Test
        fun `par returns 403 for suspended tenant`() = testApplication {
            install(ContentNegotiation) { json() }
            application { tenantOidcRoutes() }
            enableStore()

            val tenant = createTenantWithCerts(domain = "par-suspended.example.com")
            val store = IssuerTenantStore.instanceOrNull()!!
            store.save(tenant.copy(status = IssuerTenantStatus.SUSPENDED, updatedAt = "2026-01-02T00:00:00Z"))

            val response = client.post("/issuers/${tenant.id}/draft13/par") {
                contentType(ContentType.Application.FormUrlEncoded)
                setBody("response_type=code&client_id=test")
            }

            assertEquals(HttpStatusCode.Forbidden, response.status)
        }
    }

    // ===== Direct Post Endpoint =====

    @Nested
    inner class DirectPostEndpoint {

        @Test
        fun `direct_post returns 400 when state is missing`() = testApplication {
            install(ContentNegotiation) { json() }
            application { tenantOidcRoutes() }
            enableStore()

            val tenant = createTenantWithCerts(domain = "dp.example.com")
            val response = client.post("/issuers/${tenant.id}/draft13/direct_post") {
                contentType(ContentType.Application.FormUrlEncoded)
                setBody("id_token=some-token")
            }

            assertEquals(HttpStatusCode.BadRequest, response.status)
        }

        @Test
        fun `direct_post returns 400 when both id_token and vp_token are missing`() = testApplication {
            install(ContentNegotiation) { json() }
            application { tenantOidcRoutes() }
            enableStore()

            val tenant = createTenantWithCerts(domain = "dp2.example.com")
            val response = client.post("/issuers/${tenant.id}/draft13/direct_post") {
                contentType(ContentType.Application.FormUrlEncoded)
                setBody("state=some-state")
            }

            assertEquals(HttpStatusCode.BadRequest, response.status)
        }

        @Test
        fun `direct_post returns 404 for unknown tenant`() = testApplication {
            install(ContentNegotiation) { json() }
            application { tenantOidcRoutes() }
            enableStore()

            val response = client.post("/issuers/nonexistent/draft13/direct_post") {
                contentType(ContentType.Application.FormUrlEncoded)
                setBody("state=test&id_token=test")
            }

            assertEquals(HttpStatusCode.NotFound, response.status)
        }
    }

    // ===== Credential Deferred Endpoint =====

    @Nested
    inner class CredentialDeferredEndpoint {

        @Test
        fun `credential_deferred returns 401 without authorization`() = testApplication {
            install(ContentNegotiation) { json() }
            application { tenantOidcRoutes() }
            enableStore()

            val tenant = createTenantWithCerts(domain = "deferred.example.com")
            val response = client.post("/issuers/${tenant.id}/draft13/credential_deferred")
            assertEquals(HttpStatusCode.Unauthorized, response.status)
        }

        @Test
        fun `credential_deferred rejects invalid token`() = testApplication {
            install(ContentNegotiation) { json() }
            application { tenantOidcRoutes() }
            enableStore()

            val tenant = createTenantWithCerts(domain = "deferred2.example.com")
            val response = client.post("/issuers/${tenant.id}/draft13/credential_deferred") {
                header(HttpHeaders.Authorization, "Bearer invalid-token")
            }
            // Invalid JWT causes verification error (401 or 500 without global exception handler)
            assertTrue(
                response.status != HttpStatusCode.OK,
                "Invalid token should not return 200, got ${response.status}"
            )
        }
    }

    // ===== Batch Credential Endpoint =====

    @Nested
    inner class BatchCredentialEndpoint {

        @Test
        fun `batch_credential returns 401 without authorization`() = testApplication {
            install(ContentNegotiation) { json() }
            application { tenantOidcRoutes() }
            enableStore()

            val tenant = createTenantWithCerts(domain = "batch.example.com")
            val response = client.post("/issuers/${tenant.id}/draft13/batch_credential") {
                contentType(ContentType.Application.Json)
                setBody("""{"credential_requests":[]}""")
            }
            assertEquals(HttpStatusCode.Unauthorized, response.status)
        }
    }

    // ===== Full Token Flow =====

    @Nested
    inner class FullPreAuthFlow {

        @Test
        fun `pre-auth flow produces valid access token that can reach credential endpoint`() = testApplication {
            install(ContentNegotiation) { json() }
            application { tenantOidcRoutes() }
            enableStore()

            val tenant = createTenantWithCerts(domain = "fullflow.example.com")
            val preAuthCode = createSessionAndGetPreAuthCode(tenant)

            // Step 1: Exchange pre-auth code for access token
            val tokenResponse = client.post("/issuers/${tenant.id}/draft13/token") {
                contentType(ContentType.Application.FormUrlEncoded)
                setBody("grant_type=urn:ietf:params:oauth:grant-type:pre-authorized_code&pre-authorized_code=$preAuthCode")
            }

            assertEquals(HttpStatusCode.OK, tokenResponse.status)
            val tokenBody = Json.parseToJsonElement(tokenResponse.bodyAsText()).jsonObject
            val accessToken = tokenBody["access_token"]?.jsonPrimitive?.content
            assertNotNull(accessToken, "Should receive access_token")

            val cNonce = tokenBody["c_nonce"]?.jsonPrimitive?.content
            // c_nonce may or may not be present depending on implementation

            // Step 2: Use access token to call credential endpoint
            val provider = IssuerTenantRegistry.getOrCreate(tenant)
            val bankIdKey = provider.metadata.credentialConfigurationsSupported!!.keys
                .first { it.contains("BankId") }

            val credentialResponse = client.post("/issuers/${tenant.id}/draft13/credential") {
                contentType(ContentType.Application.Json)
                header(HttpHeaders.Authorization, "Bearer $accessToken")
                setBody(buildJsonObject {
                    put("credential_configuration_id", JsonPrimitive(bankIdKey))
                }.toString())
            }

            // The credential endpoint should accept the token (200 or 400 for missing proof, but NOT 401)
            assertTrue(
                credentialResponse.status != HttpStatusCode.Unauthorized,
                "Valid access token should not return 401, got: ${credentialResponse.status}"
            )
        }
    }
}
