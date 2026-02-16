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
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Integration tests that exercise the full multi-tenant lifecycle:
 * register → generate certs → set credentials → issue credential → verify isolation.
 *
 * These tests install ALL three route groups (admin, issuance, OIDC) so that
 * cross-cutting concerns (e.g. admin creates tenant, issuance uses it) are validated.
 */
class IssuerRegistrarIntegrationTest {

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

    private fun createIssuerJson(
        legalName: String = "Integration Bank",
        country: String = "AU",
        domain: String = "integ.example.com",
        contactEmail: String = "admin@integ.example.com"
    ): String = """{"legalName":"$legalName","country":"$country","domain":"$domain","contactEmail":"$contactEmail"}"""

    private val bankCredentialPayload = """
    {
        "BankId": ["VerifiableCredential", "BankId"]
    }
    """.trimIndent()

    // ===== Full Lifecycle =====

    @Test
    fun `full lifecycle - register, generate certs, set credentials, issue JWT`() = testApplication {
        install(ContentNegotiation) { json() }
        application {
            issuerTenantAdminRoutes()
            tenantIssuerRoutes()
            tenantOidcRoutes()
        }
        enableStore()

        // 1. Register tenant
        val registerResponse = client.post("/admin/issuer") {
            contentType(ContentType.Application.Json)
            setBody(createIssuerJson())
        }
        assertEquals(HttpStatusCode.Created, registerResponse.status)
        val registerBody = Json.parseToJsonElement(registerResponse.bodyAsText()).jsonObject
        val tenantId = registerBody["id"]?.jsonPrimitive?.content
        assertNotNull(tenantId, "Tenant ID should be returned")

        // 2. Generate certificates (returns IssuerDetail with x5Chain, signerCertificate)
        val certResponse = client.post("/admin/issuer/$tenantId/certificate/generate")
        assertEquals(HttpStatusCode.Created, certResponse.status)
        val certBody = Json.parseToJsonElement(certResponse.bodyAsText()).jsonObject
        assertTrue(certBody.containsKey("x5Chain"), "Should return x5Chain")
        assertTrue(certBody.containsKey("signerCertificate"), "Should return signerCertificate")

        // 3. Set credential configurations
        val credResponse = client.put("/admin/issuer/$tenantId/credentials") {
            contentType(ContentType.Application.Json)
            setBody(bankCredentialPayload)
        }
        assertEquals(HttpStatusCode.OK, credResponse.status)

        // 4. Verify tenant is ACTIVE with keys
        val detailResponse = client.get("/admin/issuer/$tenantId")
        assertEquals(HttpStatusCode.OK, detailResponse.status)
        val detail = Json.parseToJsonElement(detailResponse.bodyAsText()).jsonObject
        assertEquals("ACTIVE", detail["status"]?.jsonPrimitive?.content)
        assertNotNull(detail["x5Chain"], "Tenant should have x5Chain after cert generation")

        // 5. Check tenant metadata is available
        val metadataResponse = client.get("/issuers/$tenantId/draft13/.well-known/openid-credential-issuer")
        assertEquals(HttpStatusCode.OK, metadataResponse.status)
        val metadata = Json.parseToJsonElement(metadataResponse.bodyAsText()).jsonObject
        assertTrue(
            metadata.containsKey("credential_configurations_supported"),
            "Metadata should have credential configs"
        )

        // 6. Issue JWT credential
        val credConfigs = metadata["credential_configurations_supported"]?.jsonObject
        assertNotNull(credConfigs, "Should have credential configurations")
        val jwtConfigId = credConfigs.keys.firstOrNull { it.contains("BankId") && it.contains("jwt") }
        assertNotNull(jwtConfigId, "Should have a JWT BankId config, found: ${credConfigs.keys}")

        val issueBody = buildJsonObject {
            put("credentialConfigurationId", JsonPrimitive(jwtConfigId))
            put("credentialData", buildJsonObject {
                put("@context", buildJsonArray {
                    add(JsonPrimitive("https://www.w3.org/2018/credentials/v1"))
                })
                put("type", buildJsonArray {
                    add(JsonPrimitive("VerifiableCredential"))
                    add(JsonPrimitive("BankId"))
                })
                put("credentialSubject", buildJsonObject {
                    put("accountId", JsonPrimitive("INTEG-JWT-001"))
                })
            })
        }.toString()

        val issueResponse = client.post("/issuers/$tenantId/openid4vc/jwt/issue") {
            contentType(ContentType.Application.Json)
            setBody(issueBody)
        }
        assertEquals(HttpStatusCode.OK, issueResponse.status)
        assertTrue(
            issueResponse.bodyAsText().contains("openid-credential-offer://"),
            "Should return credential offer URI"
        )
    }

    // ===== Two-Tenant Isolation =====

    @Test
    fun `two tenants have independent metadata and cannot cross-issue`() = testApplication {
        install(ContentNegotiation) { json() }
        application {
            issuerTenantAdminRoutes()
            tenantIssuerRoutes()
            tenantOidcRoutes()
        }
        enableStore()

        // Register tenant A (BankId)
        val regA = client.post("/admin/issuer") {
            contentType(ContentType.Application.Json)
            setBody(createIssuerJson("Bank Alpha", "AU", "alpha.example.com", "a@alpha.com"))
        }
        val idA = Json.parseToJsonElement(regA.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content

        client.post("/admin/issuer/$idA/certificate/generate")
        client.put("/admin/issuer/$idA/credentials") {
            contentType(ContentType.Application.Json)
            setBody("""{"BankId": ["VerifiableCredential", "BankId"]}""")
        }

        // Register tenant B (PayrollCert — different credential type)
        val regB = client.post("/admin/issuer") {
            contentType(ContentType.Application.Json)
            setBody(createIssuerJson("Corp Beta", "US", "beta.example.com", "b@beta.com"))
        }
        val idB = Json.parseToJsonElement(regB.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content

        client.post("/admin/issuer/$idB/certificate/generate")
        client.put("/admin/issuer/$idB/credentials") {
            contentType(ContentType.Application.Json)
            setBody("""{"PayrollCert": ["VerifiableCredential", "PayrollCert"]}""")
        }

        // Verify metadata is independent
        val metaA = Json.parseToJsonElement(
            client.get("/issuers/$idA/draft13/.well-known/openid-credential-issuer").bodyAsText()
        ).jsonObject
        val metaB = Json.parseToJsonElement(
            client.get("/issuers/$idB/draft13/.well-known/openid-credential-issuer").bodyAsText()
        ).jsonObject

        val configsA = metaA["credential_configurations_supported"]!!.jsonObject
        val configsB = metaB["credential_configurations_supported"]!!.jsonObject

        assertTrue(configsA.keys.any { it.contains("BankId") }, "Tenant A should have BankId")
        assertTrue(configsA.keys.none { it.contains("PayrollCert") }, "Tenant A should NOT have PayrollCert")
        assertTrue(configsB.keys.any { it.contains("PayrollCert") }, "Tenant B should have PayrollCert")
        assertTrue(configsB.keys.none { it.contains("BankId") }, "Tenant B should NOT have BankId")

        // Try to issue BankId via tenant B — should fail
        val bankIdConfigId = configsA.keys.first { it.contains("BankId") }
        val crossIssue = client.post("/issuers/$idB/openid4vc/jwt/issue") {
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject {
                put("credentialConfigurationId", JsonPrimitive(bankIdConfigId))
                put("credentialData", buildJsonObject {
                    put("@context", buildJsonArray { add(JsonPrimitive("https://www.w3.org/2018/credentials/v1")) })
                    put("type", buildJsonArray {
                        add(JsonPrimitive("VerifiableCredential"))
                        add(JsonPrimitive("BankId"))
                    })
                    put("credentialSubject", buildJsonObject {
                        put("accountId", JsonPrimitive("CROSS-ISSUE"))
                    })
                })
            }.toString())
        }
        assertTrue(
            crossIssue.status != HttpStatusCode.OK,
            "Cross-tenant issuance should fail, got: ${crossIssue.status}"
        )
    }

    // ===== Suspended Tenant Blocking =====

    @Test
    fun `suspended tenant blocks issuance and metadata`() = testApplication {
        install(ContentNegotiation) { json() }
        application {
            issuerTenantAdminRoutes()
            tenantIssuerRoutes()
            tenantOidcRoutes()
        }
        enableStore()

        // Create and set up tenant
        val reg = client.post("/admin/issuer") {
            contentType(ContentType.Application.Json)
            setBody(createIssuerJson("Suspend Corp", "AU", "suspend.example.com", "s@suspend.com"))
        }
        val tenantId = Json.parseToJsonElement(reg.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content
        client.post("/admin/issuer/$tenantId/certificate/generate")
        client.put("/admin/issuer/$tenantId/credentials") {
            contentType(ContentType.Application.Json)
            setBody("""{"BankId": ["VerifiableCredential", "BankId"]}""")
        }

        // Verify issuance works while ACTIVE
        val metaBefore = client.get("/issuers/$tenantId/draft13/.well-known/openid-credential-issuer")
        assertEquals(HttpStatusCode.OK, metaBefore.status, "Metadata should be available while ACTIVE")

        // Suspend the tenant via admin
        val suspendResponse = client.put("/admin/issuer/$tenantId") {
            contentType(ContentType.Application.Json)
            setBody("""{"status":"SUSPENDED"}""")
        }
        assertEquals(HttpStatusCode.OK, suspendResponse.status)

        // Verify metadata returns 403 for suspended tenant
        val metaAfter = client.get("/issuers/$tenantId/draft13/.well-known/openid-credential-issuer")
        assertEquals(
            HttpStatusCode.Forbidden, metaAfter.status,
            "Metadata should be blocked for suspended tenant"
        )

        // Verify issuance returns 403
        val issueResponse = client.post("/issuers/$tenantId/openid4vc/jwt/issue") {
            contentType(ContentType.Application.Json)
            setBody("""{"credentialConfigurationId":"test","credentialData":{"key":"val"}}""")
        }
        assertEquals(HttpStatusCode.Forbidden, issueResponse.status)
    }

    // ===== Delete Removes Access =====

    @Test
    fun `deleted tenant is no longer accessible`() = testApplication {
        install(ContentNegotiation) { json() }
        application {
            issuerTenantAdminRoutes()
            tenantIssuerRoutes()
            tenantOidcRoutes()
        }
        enableStore()

        // Create tenant
        val reg = client.post("/admin/issuer") {
            contentType(ContentType.Application.Json)
            setBody(createIssuerJson("Delete Corp", "AU", "delete.example.com", "d@delete.com"))
        }
        val tenantId = Json.parseToJsonElement(reg.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content
        client.post("/admin/issuer/$tenantId/certificate/generate")

        // Verify tenant exists
        val before = client.get("/admin/issuer/$tenantId")
        assertEquals(HttpStatusCode.OK, before.status)

        // Delete tenant
        val deleteResponse = client.delete("/admin/issuer/$tenantId")
        assertEquals(HttpStatusCode.OK, deleteResponse.status)

        // Verify admin endpoint returns 404
        val after = client.get("/admin/issuer/$tenantId")
        assertEquals(HttpStatusCode.NotFound, after.status)

        // Verify issuance endpoint returns 404
        val issueResponse = client.post("/issuers/$tenantId/openid4vc/jwt/issue") {
            contentType(ContentType.Application.Json)
            setBody("""{"credentialConfigurationId":"test","credentialData":{"key":"val"}}""")
        }
        assertEquals(HttpStatusCode.NotFound, issueResponse.status)
    }

    // ===== Certificate Regeneration =====

    @Test
    fun `certificate regeneration replaces keys and updates registry`() = testApplication {
        install(ContentNegotiation) { json() }
        application {
            issuerTenantAdminRoutes()
            tenantIssuerRoutes()
            tenantOidcRoutes()
        }
        enableStore()

        // Create tenant and generate initial certs
        val reg = client.post("/admin/issuer") {
            contentType(ContentType.Application.Json)
            setBody(createIssuerJson("Regen Corp", "AU", "regen.example.com", "r@regen.com"))
        }
        val tenantId = Json.parseToJsonElement(reg.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content

        val cert1 = Json.parseToJsonElement(
            client.post("/admin/issuer/$tenantId/certificate/generate").bodyAsText()
        ).jsonObject
        val chain1 = cert1["x5Chain"].toString()

        client.put("/admin/issuer/$tenantId/credentials") {
            contentType(ContentType.Application.Json)
            setBody("""{"BankId": ["VerifiableCredential", "BankId"]}""")
        }

        // Regenerate certificates
        val cert2 = Json.parseToJsonElement(
            client.post("/admin/issuer/$tenantId/certificate/generate").bodyAsText()
        ).jsonObject
        val chain2 = cert2["x5Chain"].toString()

        // x5Chain should be different after regeneration (new keys = new certs)
        assertTrue(chain1 != chain2, "Regenerated x5Chain should differ from original")

        // Issuance should still work with new keys (registry refreshed)
        // Need to re-set credentials because registry is rebuilt
        client.put("/admin/issuer/$tenantId/credentials") {
            contentType(ContentType.Application.Json)
            setBody("""{"BankId": ["VerifiableCredential", "BankId"]}""")
        }

        val metadata = Json.parseToJsonElement(
            client.get("/issuers/$tenantId/draft13/.well-known/openid-credential-issuer").bodyAsText()
        ).jsonObject
        val configs = metadata["credential_configurations_supported"]!!.jsonObject
        val configId = configs.keys.first { it.contains("BankId") && it.contains("jwt") }

        val issueBody = buildJsonObject {
            put("credentialConfigurationId", JsonPrimitive(configId))
            put("credentialData", buildJsonObject {
                put("@context", buildJsonArray { add(JsonPrimitive("https://www.w3.org/2018/credentials/v1")) })
                put("type", buildJsonArray {
                    add(JsonPrimitive("VerifiableCredential"))
                    add(JsonPrimitive("BankId"))
                })
                put("credentialSubject", buildJsonObject {
                    put("accountId", JsonPrimitive("REGEN-001"))
                })
            })
        }.toString()

        val issueResponse = client.post("/issuers/$tenantId/openid4vc/jwt/issue") {
            contentType(ContentType.Application.Json)
            setBody(issueBody)
        }
        assertEquals(HttpStatusCode.OK, issueResponse.status)
        assertTrue(
            issueResponse.bodyAsText().contains("openid-credential-offer://"),
            "Should issue with regenerated keys"
        )
    }
}
