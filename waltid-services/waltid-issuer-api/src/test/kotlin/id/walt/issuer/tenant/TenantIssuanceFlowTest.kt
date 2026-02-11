package id.walt.issuer.tenant

import id.walt.commons.config.ConfigManager
import id.walt.issuer.config.CredentialTypeConfig
import id.walt.issuer.config.OIDCIssuerServiceConfig
import id.walt.issuer.issuance.IssuanceRequest
import id.walt.oid4vc.OpenID4VCIVersion
import id.walt.oid4vc.data.AuthenticationMethod
import id.walt.oid4vc.data.CredentialFormat
import id.walt.oid4vc.data.CredentialSupported
import id.walt.testConfigs
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.*
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.*
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

class TenantIssuanceFlowTest {

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

    private fun createTenantWithCerts(
        legalName: String,
        country: String,
        domain: String,
        credentialConfigs: Map<String, JsonElement> = emptyMap()
    ): IssuerTenant {
        val store = IssuerTenantStore.instanceOrNull()!!
        val certs = IssuerCertificateService.generateCertificates(legalName, country)
        // Wrap ciTokenKey in {"type":"jwk","jwk":{...}} format expected by KeyManager.resolveSerializedKey
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

    // -- Credential configs for test tenants --

    private val bankCredentialConfig: Map<String, JsonElement> = mapOf(
        "BankId" to JsonArray(listOf(JsonPrimitive("VerifiableCredential"), JsonPrimitive("BankId")))
    )

    private val universityCredentialConfig: Map<String, JsonElement> = mapOf(
        "UniversityDegree" to JsonArray(
            listOf(JsonPrimitive("VerifiableCredential"), JsonPrimitive("UniversityDegree"))
        )
    )

    // ===== Category B: CIProvider Creation & Metadata =====

    @Nested
    inner class CIProviderCreation {

        @Test
        fun `tenant CIProvider has correct baseUrl containing tenant ID`() {
            enableStore()
            val tenant = createTenantWithCerts("Test Bank", "AU", "bank.example.com", bankCredentialConfig)
            val provider = IssuerTenantRegistry.getOrCreate(tenant)

            val globalBaseUrl = ConfigManager.getConfig<OIDCIssuerServiceConfig>().baseUrl
            val expectedBase = "$globalBaseUrl/issuers/${tenant.id}/${OpenID4VCIVersion.DRAFT13.versionString}"
            assertEquals(expectedBase, provider.baseUrl)
        }

        @Test
        fun `tenant CIProvider metadata contains only tenant credential types`() {
            enableStore()
            val tenant = createTenantWithCerts("Test Bank", "AU", "bank.example.com", bankCredentialConfig)
            val provider = IssuerTenantRegistry.getOrCreate(tenant)

            val supported = provider.metadata.credentialConfigurationsSupported!!
            // Bank config "BankId" array expands into format-specific entries (jwt_vc_json, jwt_vc, dc+sd-jwt, etc.)
            assertTrue(supported.isNotEmpty(), "Should have credential configurations")
            assertTrue(
                supported.keys.any { it.contains("BankId") },
                "Should contain BankId-derived credential types, got: ${supported.keys}"
            )
            assertTrue(
                supported.keys.none { it.contains("UniversityDegree") },
                "Should NOT contain UniversityDegree"
            )
        }

        @Test
        fun `tenant CIProvider metadata issuer URL contains tenant path`() {
            enableStore()
            val tenant = createTenantWithCerts("Test Bank", "AU", "bank.example.com", bankCredentialConfig)
            val provider = IssuerTenantRegistry.getOrCreate(tenant)

            assertTrue(
                provider.metadata.issuer!!.contains("/issuers/${tenant.id}"),
                "Metadata issuer should contain tenant path, got: ${provider.metadata.issuer}"
            )
        }

        @Test
        fun `tenant CIProvider is cached and reused`() {
            enableStore()
            val tenant = createTenantWithCerts("Test Bank", "AU", "bank.example.com", bankCredentialConfig)

            val provider1 = IssuerTenantRegistry.getOrCreate(tenant)
            val provider2 = IssuerTenantRegistry.getOrCreate(tenant)
            assertTrue(provider1 === provider2, "Same CIProvider instance should be returned")
            assertEquals(1, IssuerTenantRegistry.providerCount())
        }

        @Test
        fun `invalidate clears cached CIProvider`() {
            enableStore()
            val tenant = createTenantWithCerts("Test Bank", "AU", "bank.example.com", bankCredentialConfig)

            val provider1 = IssuerTenantRegistry.getOrCreate(tenant)
            assertEquals(1, IssuerTenantRegistry.providerCount())

            IssuerTenantRegistry.invalidate(tenant.id)
            assertEquals(0, IssuerTenantRegistry.providerCount())

            val provider2 = IssuerTenantRegistry.getOrCreate(tenant)
            assertTrue(provider1 !== provider2, "New CIProvider instance should be created after invalidation")
        }
    }

    // ===== Category C: Tenant Isolation =====

    @Nested
    inner class TenantIsolation {

        @Test
        fun `two tenants have different metadata`() {
            enableStore()
            val bankTenant = createTenantWithCerts("Bank Corp", "AU", "bank.example.com", bankCredentialConfig)
            val uniTenant = createTenantWithCerts("State University", "US", "uni.example.com", universityCredentialConfig)

            val bankProvider = IssuerTenantRegistry.getOrCreate(bankTenant)
            val uniProvider = IssuerTenantRegistry.getOrCreate(uniTenant)

            val bankTypes = bankProvider.metadata.credentialConfigurationsSupported!!.keys
            val uniTypes = uniProvider.metadata.credentialConfigurationsSupported!!.keys

            // Bank has BankId types, not UniversityDegree
            assertTrue(bankTypes.any { it.contains("BankId") })
            assertTrue(bankTypes.none { it.contains("UniversityDegree") })

            // University has UniversityDegree types, not BankId
            assertTrue(uniTypes.any { it.contains("UniversityDegree") })
            assertTrue(uniTypes.none { it.contains("BankId") })
        }

        @Test
        fun `two tenants have different issuer URLs`() {
            enableStore()
            val bankTenant = createTenantWithCerts("Bank Corp", "AU", "bank.example.com", bankCredentialConfig)
            val uniTenant = createTenantWithCerts("State University", "US", "uni.example.com", universityCredentialConfig)

            val bankProvider = IssuerTenantRegistry.getOrCreate(bankTenant)
            val uniProvider = IssuerTenantRegistry.getOrCreate(uniTenant)

            assertNotEquals(bankProvider.metadata.issuer, uniProvider.metadata.issuer)
            assertTrue(bankProvider.metadata.issuer!!.contains(bankTenant.id))
            assertTrue(uniProvider.metadata.issuer!!.contains(uniTenant.id))
        }

        @Test
        fun `two tenants have different token signing keys`() = runBlocking {
            enableStore()
            val bankTenant = createTenantWithCerts("Bank Corp", "AU", "bank.example.com", bankCredentialConfig)
            val uniTenant = createTenantWithCerts("State University", "US", "uni.example.com", universityCredentialConfig)

            val bankKey = IssuerTenantRegistry.getTokenKey(bankTenant)
            val uniKey = IssuerTenantRegistry.getTokenKey(uniTenant)

            assertNotEquals(
                bankKey.getKeyId(),
                uniKey.getKeyId(),
                "Different tenants should have different token signing keys"
            )
        }

        @Test
        fun `two tenants have independent CIProvider instances`() {
            enableStore()
            val bankTenant = createTenantWithCerts("Bank Corp", "AU", "bank.example.com", bankCredentialConfig)
            val uniTenant = createTenantWithCerts("State University", "US", "uni.example.com", universityCredentialConfig)

            val bankProvider = IssuerTenantRegistry.getOrCreate(bankTenant)
            val uniProvider = IssuerTenantRegistry.getOrCreate(uniTenant)

            assertTrue(bankProvider !== uniProvider, "Each tenant should get a distinct CIProvider instance")
            assertEquals(2, IssuerTenantRegistry.providerCount())
        }

        @Test
        fun `tenant metadata does not leak into other tenant`() {
            enableStore()
            val bankTenant = createTenantWithCerts("Bank Corp", "AU", "bank.example.com", bankCredentialConfig)
            val uniTenant = createTenantWithCerts("State University", "US", "uni.example.com", universityCredentialConfig)

            val bankProvider = IssuerTenantRegistry.getOrCreate(bankTenant)
            val uniProvider = IssuerTenantRegistry.getOrCreate(uniTenant)

            // Serialize to JSON and verify no cross-contamination
            val bankMetaJson = bankProvider.metadata.toJSON().toString()
            val uniMetaJson = uniProvider.metadata.toJSON().toString()

            assertTrue(bankMetaJson.contains(bankTenant.id), "Bank metadata should reference bank tenant ID")
            assertTrue(!bankMetaJson.contains(uniTenant.id), "Bank metadata should NOT reference uni tenant ID")
            assertTrue(uniMetaJson.contains(uniTenant.id), "Uni metadata should reference uni tenant ID")
            assertTrue(!uniMetaJson.contains(bankTenant.id), "Uni metadata should NOT reference bank tenant ID")
        }
    }

    // ===== Category D: Credential Offer Initialization =====

    @Nested
    inner class CredentialOfferFlow {

        @Test
        fun `tenant CIProvider can initialize a credential offer`() {
            enableStore()
            val tenant = createTenantWithCerts("Test Bank", "AU", "bank.example.com", bankCredentialConfig)
            val provider = IssuerTenantRegistry.getOrCreate(tenant)

            // Find a BankId credential config key
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
                                "accountId" to JsonPrimitive("1234567890")
                            ))
                        )),
                        credentialConfigurationId = bankIdKey,
                        authenticationMethod = AuthenticationMethod.NONE,
                    )
                ),
                expiresIn = 5.minutes
            )

            assertNotNull(session, "Session should be created")
            assertNotNull(session.id, "Session should have an ID")
            assertNotNull(session.credentialOffer, "Session should have a credential offer")
        }

        @Test
        fun `tenant metadata issuer contains tenant-scoped URL`() {
            enableStore()
            val tenant = createTenantWithCerts("Test Bank", "AU", "bank.example.com", bankCredentialConfig)
            val provider = IssuerTenantRegistry.getOrCreate(tenant)

            // The CIProvider metadata.issuer contains the tenant-scoped URL
            val issuer = provider.metadata.issuer
            assertNotNull(issuer, "Metadata should have issuer")
            assertTrue(
                issuer.contains("/issuers/${tenant.id}"),
                "Metadata issuer should contain tenant path, got: $issuer"
            )

            // The credential offer URL endpoints (token, credential) are also tenant-scoped
            val tokenEndpoint = provider.metadata.tokenEndpoint
            assertNotNull(tokenEndpoint)
            assertTrue(tokenEndpoint.contains("/issuers/${tenant.id}"))
        }

        @Test
        fun `session created on tenant provider is retrievable`() {
            enableStore()
            val tenant = createTenantWithCerts("Test Bank", "AU", "bank.example.com", bankCredentialConfig)
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

            val retrieved = provider.getSession(session.id)
            assertNotNull(retrieved, "Session should be retrievable from the same provider")
            assertEquals(session.id, retrieved.id)
        }

        @Test
        fun `session created on tenant A is not accessible from tenant B`() {
            enableStore()
            val tenantA = createTenantWithCerts("Bank A", "AU", "banka.example.com", bankCredentialConfig)
            val tenantB = createTenantWithCerts("Bank B", "US", "bankb.example.com", bankCredentialConfig)

            val providerA = IssuerTenantRegistry.getOrCreate(tenantA)
            val providerB = IssuerTenantRegistry.getOrCreate(tenantB)

            val bankIdKey = providerA.metadata.credentialConfigurationsSupported!!.keys
                .first { it.contains("BankId") }

            val session = providerA.initializeCredentialOffer(
                issuanceRequests = listOf(
                    IssuanceRequest(
                        issuerKey = tenantA.issuerKey,
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

            // Session is on provider A
            val fromA = providerA.getSession(session.id)
            assertNotNull(fromA, "Session should be accessible from tenant A's provider")

            // Session should NOT be on provider B (different CIProvider instance, different session store)
            val fromB = providerB.getSession(session.id)
            assertNull(fromB, "Session should NOT be accessible from tenant B's provider")
        }
    }

    // ===== Category E: Route Guards (HTTP-level) =====

    @Nested
    inner class OidcRouteGuards {

        @Test
        fun `unknown tenant returns 404`() = testApplication {
            install(ContentNegotiation) { json() }
            application {
                tenantOidcRoutes()
            }
            enableStore()

            val response = client.get("/issuers/nonexistent-id/draft13/.well-known/openid-credential-issuer")
            assertEquals(HttpStatusCode.NotFound, response.status)
            assertTrue(response.bodyAsText().contains("Unknown issuer"))
        }

        @Test
        fun `suspended tenant returns 403`() = testApplication {
            install(ContentNegotiation) { json() }
            application {
                tenantOidcRoutes()
            }
            enableStore()

            val tenant = createTenantWithCerts("Suspended Bank", "AU", "suspended.example.com", bankCredentialConfig)
            val store = IssuerTenantStore.instanceOrNull()!!
            store.save(tenant.copy(status = IssuerTenantStatus.SUSPENDED, updatedAt = "2026-01-02T00:00:00Z"))

            val response = client.get("/issuers/${tenant.id}/draft13/.well-known/openid-credential-issuer")
            assertEquals(HttpStatusCode.Forbidden, response.status)
            assertTrue(response.bodyAsText().contains("SUSPENDED"))
        }

        @Test
        fun `revoked tenant returns 403`() = testApplication {
            install(ContentNegotiation) { json() }
            application {
                tenantOidcRoutes()
            }
            enableStore()

            val tenant = createTenantWithCerts("Revoked Bank", "AU", "revoked.example.com", bankCredentialConfig)
            val store = IssuerTenantStore.instanceOrNull()!!
            store.save(tenant.copy(status = IssuerTenantStatus.REVOKED, updatedAt = "2026-01-02T00:00:00Z"))

            val response = client.get("/issuers/${tenant.id}/draft13/.well-known/openid-credential-issuer")
            assertEquals(HttpStatusCode.Forbidden, response.status)
            assertTrue(response.bodyAsText().contains("REVOKED"))
        }

        @Test
        fun `tenant without keys returns 403`() = testApplication {
            install(ContentNegotiation) { json() }
            application {
                tenantOidcRoutes()
            }
            enableStore()

            val store = IssuerTenantStore.instanceOrNull()!!
            val tenantNoKeys = IssuerTenant(
                id = java.util.UUID.randomUUID().toString(),
                legalName = "No Keys Bank",
                country = "AU",
                domain = "nokeys.example.com",
                contactEmail = "admin@nokeys.example.com",
                status = IssuerTenantStatus.ACTIVE,
                createdAt = "2026-01-01T00:00:00Z",
                updatedAt = "2026-01-01T00:00:00Z"
            )
            store.save(tenantNoKeys)

            val response = client.get("/issuers/${tenantNoKeys.id}/draft13/.well-known/openid-credential-issuer")
            assertEquals(HttpStatusCode.Forbidden, response.status)
            assertTrue(response.bodyAsText().contains("no signing keys"))
        }

        @Test
        fun `active tenant with keys returns metadata`() = testApplication {
            install(ContentNegotiation) { json() }
            application {
                tenantOidcRoutes()
            }
            enableStore()

            val tenant = createTenantWithCerts("Good Bank", "AU", "good.example.com", bankCredentialConfig)

            val response = client.get("/issuers/${tenant.id}/draft13/.well-known/openid-credential-issuer")
            assertEquals(HttpStatusCode.OK, response.status)

            val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            assertNotNull(body["credential_configurations_supported"], "Should have credential_configurations_supported")
            val issuer = body["credential_issuer"]?.jsonPrimitive?.content
            assertNotNull(issuer)
            assertTrue(issuer.contains("/issuers/${tenant.id}"), "Issuer URL should contain tenant ID")
        }
    }

    // ===== Category F: Issuance Route Guards =====

    @Nested
    inner class IssuanceRouteGuards {

        @Test
        fun `unknown tenant returns 404 on issuance`() = testApplication {
            install(ContentNegotiation) { json() }
            application {
                tenantIssuerRoutes()
            }
            enableStore()

            val response = client.post("/issuers/nonexistent-id/openid4vc/sdjwt/issue") {
                contentType(ContentType.Application.Json)
                setBody("{}")
            }
            assertEquals(HttpStatusCode.NotFound, response.status)
        }

        @Test
        fun `suspended tenant returns 403 on issuance`() = testApplication {
            install(ContentNegotiation) { json() }
            application {
                tenantIssuerRoutes()
            }
            enableStore()

            val tenant = createTenantWithCerts("Suspended Bank", "AU", "suspended2.example.com", bankCredentialConfig)
            val store = IssuerTenantStore.instanceOrNull()!!
            store.save(tenant.copy(status = IssuerTenantStatus.SUSPENDED, updatedAt = "2026-01-02T00:00:00Z"))

            val response = client.post("/issuers/${tenant.id}/openid4vc/sdjwt/issue") {
                contentType(ContentType.Application.Json)
                setBody("{}")
            }
            assertEquals(HttpStatusCode.Forbidden, response.status)
        }

        @Test
        fun `tenant without keys returns 403 on issuance`() = testApplication {
            install(ContentNegotiation) { json() }
            application {
                tenantIssuerRoutes()
            }
            enableStore()

            val store = IssuerTenantStore.instanceOrNull()!!
            val tenantNoKeys = IssuerTenant(
                id = java.util.UUID.randomUUID().toString(),
                legalName = "No Keys Bank",
                country = "AU",
                domain = "nokeys2.example.com",
                contactEmail = "admin@nokeys2.example.com",
                status = IssuerTenantStatus.ACTIVE,
                createdAt = "2026-01-01T00:00:00Z",
                updatedAt = "2026-01-01T00:00:00Z"
            )
            store.save(tenantNoKeys)

            val response = client.post("/issuers/${tenantNoKeys.id}/openid4vc/sdjwt/issue") {
                contentType(ContentType.Application.Json)
                setBody("{}")
            }
            assertEquals(HttpStatusCode.Forbidden, response.status)
        }
    }

    // ===== Category G: Metadata Endpoint Isolation (HTTP-level) =====

    @Nested
    inner class MetadataEndpointIsolation {

        @Test
        fun `two tenants return different metadata via HTTP`() = testApplication {
            install(ContentNegotiation) { json() }
            application {
                tenantOidcRoutes()
            }
            enableStore()

            val bankTenant = createTenantWithCerts("Bank Corp", "AU", "bank2.example.com", bankCredentialConfig)
            val uniTenant = createTenantWithCerts("State Uni", "US", "uni2.example.com", universityCredentialConfig)

            val bankResponse = client.get("/issuers/${bankTenant.id}/draft13/.well-known/openid-credential-issuer")
            val uniResponse = client.get("/issuers/${uniTenant.id}/draft13/.well-known/openid-credential-issuer")

            assertEquals(HttpStatusCode.OK, bankResponse.status)
            assertEquals(HttpStatusCode.OK, uniResponse.status)

            val bankMeta = Json.parseToJsonElement(bankResponse.bodyAsText()).jsonObject
            val uniMeta = Json.parseToJsonElement(uniResponse.bodyAsText()).jsonObject

            val bankCreds = bankMeta["credential_configurations_supported"]?.jsonObject?.keys ?: emptySet()
            val uniCreds = uniMeta["credential_configurations_supported"]?.jsonObject?.keys ?: emptySet()

            assertTrue(bankCreds.any { it.contains("BankId") }, "Bank should have BankId, got: $bankCreds")
            assertTrue(bankCreds.none { it.contains("UniversityDegree") }, "Bank should NOT have UniversityDegree")
            assertTrue(uniCreds.any { it.contains("UniversityDegree") }, "Uni should have UniversityDegree, got: $uniCreds")
            assertTrue(uniCreds.none { it.contains("BankId") }, "Uni should NOT have BankId")

            // Verify issuer URLs are different
            val bankIssuer = bankMeta["credential_issuer"]?.jsonPrimitive?.content
            val uniIssuer = uniMeta["credential_issuer"]?.jsonPrimitive?.content
            assertNotEquals(bankIssuer, uniIssuer)
            assertTrue(bankIssuer!!.contains(bankTenant.id))
            assertTrue(uniIssuer!!.contains(uniTenant.id))
        }

        @Test
        fun `openid-configuration returns tenant-scoped config`() = testApplication {
            install(ContentNegotiation) { json() }
            application {
                tenantOidcRoutes()
            }
            enableStore()

            val tenant = createTenantWithCerts("Test Bank", "AU", "bank3.example.com", bankCredentialConfig)

            val response = client.get("/issuers/${tenant.id}/draft13/.well-known/openid-configuration")
            assertEquals(HttpStatusCode.OK, response.status)

            val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            val issuer = body["issuer"]?.jsonPrimitive?.content
            assertNotNull(issuer)
            assertTrue(issuer.contains("/issuers/${tenant.id}"), "OpenID config issuer should contain tenant ID")
        }
    }
}
