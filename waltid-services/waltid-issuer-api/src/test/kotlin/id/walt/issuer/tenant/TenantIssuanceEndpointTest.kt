package id.walt.issuer.tenant

import id.walt.commons.config.ConfigManager
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
import kotlin.test.assertTrue

class TenantIssuanceEndpointTest {

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
        legalName: String = "Issuance Test Bank",
        country: String = "AU",
        domain: String = "issue.example.com",
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

    private fun findCredConfigKey(tenant: IssuerTenant, contains: String): String {
        val provider = IssuerTenantRegistry.getOrCreate(tenant)
        return provider.metadata.credentialConfigurationsSupported!!.keys
            .first { it.contains(contains) }
    }

    private fun buildJwtIssuanceBody(credConfigId: String): String = buildJsonObject {
        put("credentialConfigurationId", JsonPrimitive(credConfigId))
        put("credentialData", buildJsonObject {
            put("@context", buildJsonArray {
                add(JsonPrimitive("https://www.w3.org/2018/credentials/v1"))
            })
            put("type", buildJsonArray {
                add(JsonPrimitive("VerifiableCredential"))
                add(JsonPrimitive("BankId"))
            })
            put("credentialSubject", buildJsonObject {
                put("accountId", JsonPrimitive("TEST-JWT-001"))
            })
        })
    }.toString()

    private fun buildSdJwtIssuanceBody(credConfigId: String): String = buildJsonObject {
        put("credentialConfigurationId", JsonPrimitive(credConfigId))
        put("credentialData", buildJsonObject {
            put("accountId", JsonPrimitive("TEST-SDJWT-001"))
            put("given_name", JsonPrimitive("Test"))
            put("family_name", JsonPrimitive("User"))
        })
        put("selectiveDisclosure", buildJsonObject {
            put("fields", buildJsonObject {
                put("accountId", buildJsonObject { put("sd", JsonPrimitive(true)) })
                put("given_name", buildJsonObject { put("sd", JsonPrimitive(true)) })
            })
        })
    }.toString()

    private fun buildMdocIssuanceBody(credConfigId: String): String = buildJsonObject {
        put("credentialConfigurationId", JsonPrimitive(credConfigId))
        put("mdocData", buildJsonObject {
            put("org.iso.18013.5.1", buildJsonObject {
                put("given_name", JsonPrimitive("Test"))
                put("family_name", JsonPrimitive("User"))
                put("birth_date", JsonPrimitive("1990-01-01"))
            })
        })
    }.toString()

    // ===== JWT Issuance =====

    @Nested
    inner class JwtIssuance {

        @Test
        fun `jwt issue returns credential offer URI for valid request`() = testApplication {
            install(ContentNegotiation) { json() }
            application { tenantIssuerRoutes() }
            enableStore()

            val tenant = createTenantWithCerts(domain = "jwt.example.com")
            val credConfigId = findCredConfigKey(tenant, "BankId_jwt_vc_json")

            val response = client.post("/issuers/${tenant.id}/openid4vc/jwt/issue") {
                contentType(ContentType.Application.Json)
                setBody(buildJwtIssuanceBody(credConfigId))
            }

            assertEquals(HttpStatusCode.OK, response.status)
            val body = response.bodyAsText()
            assertTrue(body.contains("openid-credential-offer://"), "Should return credential offer URI, got: ${body.take(200)}")
        }

        @Test
        fun `jwt issue returns 404 for unknown tenant`() = testApplication {
            install(ContentNegotiation) { json() }
            application { tenantIssuerRoutes() }
            enableStore()

            val response = client.post("/issuers/nonexistent/openid4vc/jwt/issue") {
                contentType(ContentType.Application.Json)
                setBody("""{"credentialConfigurationId":"test","credentialData":{"key":"val"}}""")
            }
            assertEquals(HttpStatusCode.NotFound, response.status)
        }

        @Test
        fun `jwt issue returns 403 for suspended tenant`() = testApplication {
            install(ContentNegotiation) { json() }
            application { tenantIssuerRoutes() }
            enableStore()

            val tenant = createTenantWithCerts(domain = "jwt-suspended.example.com")
            val store = IssuerTenantStore.instanceOrNull()!!
            store.save(tenant.copy(status = IssuerTenantStatus.SUSPENDED, updatedAt = "2026-01-02T00:00:00Z"))

            val response = client.post("/issuers/${tenant.id}/openid4vc/jwt/issue") {
                contentType(ContentType.Application.Json)
                setBody("""{"credentialConfigurationId":"test","credentialData":{"key":"val"}}""")
            }
            assertEquals(HttpStatusCode.Forbidden, response.status)
        }
    }

    // ===== SD-JWT Issuance =====

    @Nested
    inner class SdJwtIssuance {

        @Test
        fun `sdjwt issue returns credential offer URI for valid request`() = testApplication {
            install(ContentNegotiation) { json() }
            application { tenantIssuerRoutes() }
            enableStore()

            val tenant = createTenantWithCerts(domain = "sdjwt.example.com")
            // SD-JWT uses dc+sd-jwt or vc+sd-jwt format variant
            val credConfigId = findCredConfigKey(tenant, "BankId")

            val response = client.post("/issuers/${tenant.id}/openid4vc/sdjwt/issue") {
                contentType(ContentType.Application.Json)
                setBody(buildSdJwtIssuanceBody(credConfigId))
            }

            assertEquals(HttpStatusCode.OK, response.status)
            val body = response.bodyAsText()
            assertTrue(body.contains("openid-credential-offer://"), "Should return credential offer URI, got: ${body.take(200)}")
        }

        @Test
        fun `sdjwt issue returns 404 for unknown tenant`() = testApplication {
            install(ContentNegotiation) { json() }
            application { tenantIssuerRoutes() }
            enableStore()

            val response = client.post("/issuers/nonexistent/openid4vc/sdjwt/issue") {
                contentType(ContentType.Application.Json)
                setBody("""{"credentialConfigurationId":"test","credentialData":{"key":"val"}}""")
            }
            assertEquals(HttpStatusCode.NotFound, response.status)
        }
    }

    // ===== mDoc Issuance =====

    @Nested
    inner class MdocIssuance {

        @Test
        fun `mdoc issue returns credential offer URI for valid request`() = testApplication {
            install(ContentNegotiation) { json() }
            application { tenantIssuerRoutes() }
            enableStore()

            // Use an mDoc credential config
            val mdocCredConfig: Map<String, JsonElement> = mapOf(
                "org.iso.18013.5.1.mDL" to buildJsonObject {
                    put("format", JsonPrimitive("mso_mdoc"))
                    put("docType", JsonPrimitive("org.iso.18013.5.1.mDL"))
                    put("cryptographic_binding_methods_supported", buildJsonArray {
                        add(JsonPrimitive("cose_key"))
                    })
                    put("credential_signing_alg_values_supported", buildJsonArray {
                        add(JsonPrimitive("ES256"))
                    })
                    put("proof_types_supported", buildJsonObject {
                        put("cwt", buildJsonObject {
                            put("proof_signing_alg_values_supported", buildJsonArray {
                                add(JsonPrimitive("ES256"))
                            })
                        })
                    })
                }
            )

            val tenant = createTenantWithCerts(
                domain = "mdoc.example.com",
                credentialConfigs = mdocCredConfig
            )

            val credConfigId = findCredConfigKey(tenant, "mDL")

            val response = client.post("/issuers/${tenant.id}/openid4vc/mdoc/issue") {
                contentType(ContentType.Application.Json)
                setBody(buildMdocIssuanceBody(credConfigId))
            }

            assertEquals(HttpStatusCode.OK, response.status)
            val body = response.bodyAsText()
            assertTrue(body.contains("openid-credential-offer://"), "Should return credential offer URI, got: ${body.take(200)}")
        }

        @Test
        fun `mdoc issue returns 404 for unknown tenant`() = testApplication {
            install(ContentNegotiation) { json() }
            application { tenantIssuerRoutes() }
            enableStore()

            val response = client.post("/issuers/nonexistent/openid4vc/mdoc/issue") {
                contentType(ContentType.Application.Json)
                setBody("""{"credentialConfigurationId":"test","mdocData":{"ns":{"field":"val"}}}""")
            }
            assertEquals(HttpStatusCode.NotFound, response.status)
        }
    }

    // ===== Cross-Tenant Isolation =====

    @Nested
    inner class CrossTenantIssuance {

        @Test
        fun `tenant A credential config not accessible via tenant B issuance endpoint`() = testApplication {
            install(ContentNegotiation) { json() }
            application { tenantIssuerRoutes() }
            enableStore()

            val tenantA = createTenantWithCerts("Bank A", "AU", "banka-issue.example.com")
            val tenantB = createTenantWithCerts("Bank B", "US", "bankb-issue.example.com",
                credentialConfigs = mapOf(
                    "PayrollCert" to JsonArray(listOf(JsonPrimitive("VerifiableCredential"), JsonPrimitive("PayrollCert")))
                )
            )

            // Try to issue BankId via tenant B (which only has PayrollCert)
            val credConfigId = findCredConfigKey(tenantA, "BankId_jwt_vc_json")

            val response = client.post("/issuers/${tenantB.id}/openid4vc/jwt/issue") {
                contentType(ContentType.Application.Json)
                setBody(buildJwtIssuanceBody(credConfigId))
            }

            // Should fail — BankId is not a valid credential config for tenant B
            assertTrue(
                response.status != HttpStatusCode.OK,
                "Cross-tenant credential config should not work, got: ${response.status}"
            )
        }
    }

    // ===== Key Enrichment =====

    @Nested
    inner class KeyEnrichment {

        @Test
        fun `issuance works without explicit issuerKey in request body`() = testApplication {
            install(ContentNegotiation) { json() }
            application { tenantIssuerRoutes() }
            enableStore()

            val tenant = createTenantWithCerts(domain = "enrich.example.com")
            val credConfigId = findCredConfigKey(tenant, "BankId_jwt_vc_json")

            // Request body does NOT include issuerKey — should be enriched from tenant
            val body = buildJsonObject {
                put("credentialConfigurationId", JsonPrimitive(credConfigId))
                put("credentialData", buildJsonObject {
                    put("@context", buildJsonArray {
                        add(JsonPrimitive("https://www.w3.org/2018/credentials/v1"))
                    })
                    put("type", buildJsonArray {
                        add(JsonPrimitive("VerifiableCredential"))
                        add(JsonPrimitive("BankId"))
                    })
                    put("credentialSubject", buildJsonObject {
                        put("accountId", JsonPrimitive("ENRICH-001"))
                    })
                })
            }.toString()

            val response = client.post("/issuers/${tenant.id}/openid4vc/jwt/issue") {
                contentType(ContentType.Application.Json)
                setBody(body)
            }

            assertEquals(HttpStatusCode.OK, response.status)
            assertTrue(
                response.bodyAsText().contains("openid-credential-offer://"),
                "Should return credential offer URI (key enriched from tenant)"
            )
        }

        @Test
        fun `issuance with tenant without key returns 403`() = testApplication {
            install(ContentNegotiation) { json() }
            application { tenantIssuerRoutes() }
            enableStore()

            val store = IssuerTenantStore.instanceOrNull()!!
            val tenantNoKeys = IssuerTenant(
                id = java.util.UUID.randomUUID().toString(),
                legalName = "No Keys Corp",
                country = "AU",
                domain = "nokeys-issue.example.com",
                contactEmail = "admin@nokeys-issue.example.com",
                status = IssuerTenantStatus.ACTIVE,
                createdAt = "2026-01-01T00:00:00Z",
                updatedAt = "2026-01-01T00:00:00Z"
            )
            store.save(tenantNoKeys)

            val response = client.post("/issuers/${tenantNoKeys.id}/openid4vc/jwt/issue") {
                contentType(ContentType.Application.Json)
                setBody("""{"credentialConfigurationId":"test","credentialData":{"key":"val"}}""")
            }
            assertEquals(HttpStatusCode.Forbidden, response.status)
        }
    }
}
