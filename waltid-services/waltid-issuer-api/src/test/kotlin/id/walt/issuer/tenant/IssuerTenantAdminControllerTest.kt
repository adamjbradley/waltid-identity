package id.walt.issuer.tenant

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Document
import org.w3c.dom.NodeList
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class IssuerTenantAdminControllerTest {

    @TempDir
    lateinit var tempDir: File

    @BeforeEach
    fun setUp() {
        IssuerTenantStore.resetForTesting()
    }

    @AfterEach
    fun tearDown() {
        IssuerTenantStore.resetForTesting()
    }

    private fun createIssuerJson(
        legalName: String = "Test Issuer",
        country: String = "AU",
        domain: String = "issuer.example.com",
        contactEmail: String = "admin@example.com",
        contactAddress: String? = "123 Test St"
    ): String {
        val addressPart = if (contactAddress != null) ""","contactAddress":"$contactAddress"""" else ""
        return """{"legalName":"$legalName","country":"$country","domain":"$domain","contactEmail":"$contactEmail"$addressPart}"""
    }

    private fun enableStore() {
        IssuerTenantStore.init(tempDir.absolutePath)
    }

    private fun parseXml(xml: String): Document {
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        return factory.newDocumentBuilder().parse(ByteArrayInputStream(xml.toByteArray()))
    }

    private fun Document.getElementsByTagNameNS(localName: String): NodeList {
        return this.getElementsByTagNameNS("*", localName)
    }

    // ===== Feature Disabled Tests (503) =====

    @Test
    fun `GET admin issuer returns 503 when feature disabled`() = testApplication {
        install(ContentNegotiation) { json() }
        application { issuerTenantAdminRoutes() }

        val response = client.get("/admin/issuer")
        assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
        assertTrue(response.bodyAsText().contains("not enabled"))
    }

    @Test
    fun `POST admin issuer returns 503 when feature disabled`() = testApplication {
        install(ContentNegotiation) { json() }
        application { issuerTenantAdminRoutes() }

        val response = client.post("/admin/issuer") {
            contentType(ContentType.Application.Json)
            setBody(createIssuerJson())
        }
        assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
    }

    @Test
    fun `GET admin issuer id returns 503 when feature disabled`() = testApplication {
        install(ContentNegotiation) { json() }
        application { issuerTenantAdminRoutes() }

        val response = client.get("/admin/issuer/some-id")
        assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
    }

    @Test
    fun `PUT admin issuer id returns 503 when feature disabled`() = testApplication {
        install(ContentNegotiation) { json() }
        application { issuerTenantAdminRoutes() }

        val response = client.put("/admin/issuer/some-id") {
            contentType(ContentType.Application.Json)
            setBody("""{"legalName":"Updated"}""")
        }
        assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
    }

    @Test
    fun `DELETE admin issuer id returns 503 when feature disabled`() = testApplication {
        install(ContentNegotiation) { json() }
        application { issuerTenantAdminRoutes() }

        val response = client.delete("/admin/issuer/some-id")
        assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
    }

    @Test
    fun `POST certificate generate returns 503 when feature disabled`() = testApplication {
        install(ContentNegotiation) { json() }
        application { issuerTenantAdminRoutes() }

        val response = client.post("/admin/issuer/some-id/certificate/generate")
        assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
    }

    @Test
    fun `POST certificate upload returns 503 when feature disabled`() = testApplication {
        install(ContentNegotiation) { json() }
        application { issuerTenantAdminRoutes() }

        val response = client.post("/admin/issuer/some-id/certificate/upload") {
            contentType(ContentType.Application.Json)
            setBody("""{"issuerKeyJwk":{},"x5Chain":[]}""")
        }
        assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
    }

    @Test
    fun `GET certificate download returns 503 when feature disabled`() = testApplication {
        install(ContentNegotiation) { json() }
        application { issuerTenantAdminRoutes() }

        val response = client.get("/admin/issuer/some-id/certificate/download")
        assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
    }

    @Test
    fun `PUT credentials returns 503 when feature disabled`() = testApplication {
        install(ContentNegotiation) { json() }
        application { issuerTenantAdminRoutes() }

        val response = client.put("/admin/issuer/some-id/credentials") {
            contentType(ContentType.Application.Json)
            setBody("{}")
        }
        assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
    }

    // ===== Feature Enabled: CRUD Tests =====

    @Test
    fun `POST admin issuer creates tenant and returns 201`() = testApplication {
        install(ContentNegotiation) { json() }
        application { issuerTenantAdminRoutes() }
        enableStore()

        val response = client.post("/admin/issuer") {
            contentType(ContentType.Application.Json)
            setBody(createIssuerJson())
        }

        assertEquals(HttpStatusCode.Created, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("Test Issuer"), "Should contain legal name")
        assertTrue(body.contains("issuer.example.com"), "Should contain domain")
        assertTrue(body.contains("ACTIVE"), "Should have ACTIVE status")
        assertTrue(body.contains("AU"), "Should contain country")
    }

    @Test
    fun `POST admin issuer uppercases country`() = testApplication {
        install(ContentNegotiation) { json() }
        application { issuerTenantAdminRoutes() }
        enableStore()

        val response = client.post("/admin/issuer") {
            contentType(ContentType.Application.Json)
            setBody(createIssuerJson(country = "au"))
        }

        assertEquals(HttpStatusCode.Created, response.status)
        val parsed = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals("AU", parsed["country"]?.jsonPrimitive?.content)
    }

    @Test
    fun `GET admin issuer returns list of tenants`() = testApplication {
        install(ContentNegotiation) { json() }
        application { issuerTenantAdminRoutes() }
        enableStore()

        client.post("/admin/issuer") {
            contentType(ContentType.Application.Json)
            setBody(createIssuerJson(legalName = "Issuer One", domain = "one.example.com"))
        }
        client.post("/admin/issuer") {
            contentType(ContentType.Application.Json)
            setBody(createIssuerJson(legalName = "Issuer Two", domain = "two.example.com"))
        }

        val response = client.get("/admin/issuer")
        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("Issuer One"))
        assertTrue(body.contains("Issuer Two"))
    }

    @Test
    fun `GET admin issuer returns empty list when no tenants`() = testApplication {
        install(ContentNegotiation) { json() }
        application { issuerTenantAdminRoutes() }
        enableStore()

        val response = client.get("/admin/issuer")
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("[]", response.bodyAsText().trim())
    }

    @Test
    fun `GET admin issuer id returns tenant detail`() = testApplication {
        install(ContentNegotiation) { json() }
        application { issuerTenantAdminRoutes() }
        enableStore()

        val createResponse = client.post("/admin/issuer") {
            contentType(ContentType.Application.Json)
            setBody(createIssuerJson())
        }
        val created = Json.parseToJsonElement(createResponse.bodyAsText()).jsonObject
        val issuerId = created["id"]!!.jsonPrimitive.content

        val response = client.get("/admin/issuer/$issuerId")
        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("Test Issuer"))
        assertTrue(body.contains("issuer.example.com"))
        assertTrue(body.contains("admin@example.com"))
    }

    @Test
    fun `GET admin issuer unknown returns 404`() = testApplication {
        install(ContentNegotiation) { json() }
        application { issuerTenantAdminRoutes() }
        enableStore()

        val response = client.get("/admin/issuer/nonexistent-id")
        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    @Test
    fun `PUT admin issuer id updates tenant fields`() = testApplication {
        install(ContentNegotiation) { json() }
        application { issuerTenantAdminRoutes() }
        enableStore()

        val createResponse = client.post("/admin/issuer") {
            contentType(ContentType.Application.Json)
            setBody(createIssuerJson())
        }
        val created = Json.parseToJsonElement(createResponse.bodyAsText()).jsonObject
        val issuerId = created["id"]!!.jsonPrimitive.content

        val response = client.put("/admin/issuer/$issuerId") {
            contentType(ContentType.Application.Json)
            setBody("""{"legalName":"Updated Issuer Name","contactEmail":"new@example.com"}""")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("Updated Issuer Name"))
        assertTrue(body.contains("new@example.com"))
    }

    @Test
    fun `DELETE admin issuer id removes tenant`() = testApplication {
        install(ContentNegotiation) { json() }
        application { issuerTenantAdminRoutes() }
        enableStore()

        val createResponse = client.post("/admin/issuer") {
            contentType(ContentType.Application.Json)
            setBody(createIssuerJson())
        }
        val created = Json.parseToJsonElement(createResponse.bodyAsText()).jsonObject
        val issuerId = created["id"]!!.jsonPrimitive.content

        val deleteResponse = client.delete("/admin/issuer/$issuerId")
        assertEquals(HttpStatusCode.OK, deleteResponse.status)

        val getResponse = client.get("/admin/issuer/$issuerId")
        assertEquals(HttpStatusCode.NotFound, getResponse.status)
    }

    @Test
    fun `DELETE admin issuer unknown returns 404`() = testApplication {
        install(ContentNegotiation) { json() }
        application { issuerTenantAdminRoutes() }
        enableStore()

        val response = client.delete("/admin/issuer/nonexistent")
        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    // ===== Certificate Management Tests =====

    @Test
    fun `POST certificate generate creates cert and updates tenant`() = testApplication {
        install(ContentNegotiation) { json() }
        application { issuerTenantAdminRoutes() }
        enableStore()

        val createResponse = client.post("/admin/issuer") {
            contentType(ContentType.Application.Json)
            setBody(createIssuerJson())
        }
        val created = Json.parseToJsonElement(createResponse.bodyAsText()).jsonObject
        val issuerId = created["id"]!!.jsonPrimitive.content

        val certResponse = client.post("/admin/issuer/$issuerId/certificate/generate")
        assertEquals(HttpStatusCode.Created, certResponse.status)
        val body = certResponse.bodyAsText()
        assertTrue(body.contains("iacaCertificate"), "Should contain IACA certificate info")
        assertTrue(body.contains("signerCertificate"), "Should contain signer certificate info")
        assertTrue(body.contains("x5Chain"), "Should contain x5Chain")
    }

    @Test
    fun `POST certificate generate for unknown tenant returns 404`() = testApplication {
        install(ContentNegotiation) { json() }
        application { issuerTenantAdminRoutes() }
        enableStore()

        val response = client.post("/admin/issuer/nonexistent/certificate/generate")
        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    @Test
    fun `GET certificate download returns cert data after generation`() = testApplication {
        install(ContentNegotiation) { json() }
        application { issuerTenantAdminRoutes() }
        enableStore()

        val createResponse = client.post("/admin/issuer") {
            contentType(ContentType.Application.Json)
            setBody(createIssuerJson())
        }
        val created = Json.parseToJsonElement(createResponse.bodyAsText()).jsonObject
        val issuerId = created["id"]!!.jsonPrimitive.content

        // Generate cert first
        client.post("/admin/issuer/$issuerId/certificate/generate")

        val response = client.get("/admin/issuer/$issuerId/certificate/download")
        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("x5c"), "Should contain x5c array")
        assertTrue(body.contains("issuerKey"), "Should contain issuer key")
        assertTrue(body.contains("iacaCertificate"), "Should contain IACA cert info")
        assertTrue(body.contains("signerCertificate"), "Should contain signer cert info")
    }

    @Test
    fun `GET certificate download returns 404 when no cert generated`() = testApplication {
        install(ContentNegotiation) { json() }
        application { issuerTenantAdminRoutes() }
        enableStore()

        val createResponse = client.post("/admin/issuer") {
            contentType(ContentType.Application.Json)
            setBody(createIssuerJson())
        }
        val created = Json.parseToJsonElement(createResponse.bodyAsText()).jsonObject
        val issuerId = created["id"]!!.jsonPrimitive.content

        val response = client.get("/admin/issuer/$issuerId/certificate/download")
        assertEquals(HttpStatusCode.NotFound, response.status)
        assertTrue(response.bodyAsText().contains("No generated certificate"))
    }

    @Test
    fun `POST certificate upload with valid cert updates tenant`() = testApplication {
        install(ContentNegotiation) { json() }
        application { issuerTenantAdminRoutes() }
        enableStore()

        val createResponse = client.post("/admin/issuer") {
            contentType(ContentType.Application.Json)
            setBody(createIssuerJson())
        }
        val created = Json.parseToJsonElement(createResponse.bodyAsText()).jsonObject
        val issuerId = created["id"]!!.jsonPrimitive.content

        // Generate real certs first to get valid base64 DER data
        val generated = IssuerCertificateService.generateCertificates("Test Issuer", "AU")

        val uploadBody = """{"issuerKeyJwk":${generated.issuerKeyJwk},"x5Chain":["${generated.x5Chain[0]}","${generated.x5Chain[1]}"],"ciTokenKeyJwk":${generated.ciTokenKeyJwk}}"""

        val response = client.post("/admin/issuer/$issuerId/certificate/upload") {
            contentType(ContentType.Application.Json)
            setBody(uploadBody)
        }
        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("signerCertificate"), "Should contain signer certificate info")
        assertTrue(body.contains("iacaCertificate"), "Should contain IACA certificate info")
    }

    @Test
    fun `POST certificate upload with invalid data returns 400`() = testApplication {
        install(ContentNegotiation) { json() }
        application { issuerTenantAdminRoutes() }
        enableStore()

        val createResponse = client.post("/admin/issuer") {
            contentType(ContentType.Application.Json)
            setBody(createIssuerJson())
        }
        val created = Json.parseToJsonElement(createResponse.bodyAsText()).jsonObject
        val issuerId = created["id"]!!.jsonPrimitive.content

        val response = client.post("/admin/issuer/$issuerId/certificate/upload") {
            contentType(ContentType.Application.Json)
            setBody("""{"issuerKeyJwk":{},"x5Chain":["not-valid-base64!!"]}""")
        }
        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertTrue(response.bodyAsText().contains("Invalid certificate data"))
    }

    // ===== Credential Configuration Tests =====

    @Test
    fun `PUT credentials updates tenant credential config`() = testApplication {
        install(ContentNegotiation) { json() }
        application { issuerTenantAdminRoutes() }
        enableStore()

        val createResponse = client.post("/admin/issuer") {
            contentType(ContentType.Application.Json)
            setBody(createIssuerJson())
        }
        val created = Json.parseToJsonElement(createResponse.bodyAsText()).jsonObject
        val issuerId = created["id"]!!.jsonPrimitive.content

        val credConfig = """{"eu.europa.ec.eudi.pid.1":{"format":"mso_mdoc","docType":"eu.europa.ec.eudi.pid.1"}}"""
        val response = client.put("/admin/issuer/$issuerId/credentials") {
            contentType(ContentType.Application.Json)
            setBody(credConfig)
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("eu.europa.ec.eudi.pid.1"), "Should contain the credential config ID")
        assertTrue(body.contains("mso_mdoc"), "Should contain the format")
    }

    @Test
    fun `PUT credentials for unknown tenant returns 404`() = testApplication {
        install(ContentNegotiation) { json() }
        application { issuerTenantAdminRoutes() }
        enableStore()

        val response = client.put("/admin/issuer/nonexistent/credentials") {
            contentType(ContentType.Application.Json)
            setBody("""{"test":{"format":"mso_mdoc"}}""")
        }
        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    // ===== Validation Tests =====

    @Test
    fun `POST admin issuer rejects blank legalName`() = testApplication {
        install(ContentNegotiation) { json() }
        application { issuerTenantAdminRoutes() }
        enableStore()

        val response = client.post("/admin/issuer") {
            contentType(ContentType.Application.Json)
            setBody(createIssuerJson(legalName = ""))
        }
        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertTrue(response.bodyAsText().contains("legalName"))
    }

    @Test
    fun `POST admin issuer rejects blank country`() = testApplication {
        install(ContentNegotiation) { json() }
        application { issuerTenantAdminRoutes() }
        enableStore()

        val response = client.post("/admin/issuer") {
            contentType(ContentType.Application.Json)
            setBody(createIssuerJson(country = ""))
        }
        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertTrue(response.bodyAsText().contains("country"))
    }

    @Test
    fun `POST admin issuer rejects blank domain`() = testApplication {
        install(ContentNegotiation) { json() }
        application { issuerTenantAdminRoutes() }
        enableStore()

        val response = client.post("/admin/issuer") {
            contentType(ContentType.Application.Json)
            setBody(createIssuerJson(domain = ""))
        }
        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertTrue(response.bodyAsText().contains("domain"))
    }

    @Test
    fun `POST admin issuer rejects blank contactEmail`() = testApplication {
        install(ContentNegotiation) { json() }
        application { issuerTenantAdminRoutes() }
        enableStore()

        val response = client.post("/admin/issuer") {
            contentType(ContentType.Application.Json)
            setBody(createIssuerJson(contactEmail = ""))
        }
        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertTrue(response.bodyAsText().contains("contactEmail"))
    }

    @Test
    fun `POST admin issuer rejects duplicate domain`() = testApplication {
        install(ContentNegotiation) { json() }
        application { issuerTenantAdminRoutes() }
        enableStore()

        client.post("/admin/issuer") {
            contentType(ContentType.Application.Json)
            setBody(createIssuerJson(domain = "unique.example.com"))
        }

        val response = client.post("/admin/issuer") {
            contentType(ContentType.Application.Json)
            setBody(createIssuerJson(legalName = "Another Issuer", domain = "unique.example.com"))
        }
        assertEquals(HttpStatusCode.Conflict, response.status)
        assertTrue(response.bodyAsText().contains("already registered"))
    }

    // ===== Lifecycle Tests =====

    @Test
    fun `PUT admin issuer can suspend tenant`() = testApplication {
        install(ContentNegotiation) { json() }
        application { issuerTenantAdminRoutes() }
        enableStore()

        val createResponse = client.post("/admin/issuer") {
            contentType(ContentType.Application.Json)
            setBody(createIssuerJson())
        }
        val created = Json.parseToJsonElement(createResponse.bodyAsText()).jsonObject
        val issuerId = created["id"]!!.jsonPrimitive.content

        val response = client.put("/admin/issuer/$issuerId") {
            contentType(ContentType.Application.Json)
            setBody("""{"status":"SUSPENDED"}""")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.bodyAsText().contains("SUSPENDED"))
    }

    @Test
    fun `PUT admin issuer can reactivate suspended tenant`() = testApplication {
        install(ContentNegotiation) { json() }
        application { issuerTenantAdminRoutes() }
        enableStore()

        val createResponse = client.post("/admin/issuer") {
            contentType(ContentType.Application.Json)
            setBody(createIssuerJson())
        }
        val created = Json.parseToJsonElement(createResponse.bodyAsText()).jsonObject
        val issuerId = created["id"]!!.jsonPrimitive.content

        // Suspend
        client.put("/admin/issuer/$issuerId") {
            contentType(ContentType.Application.Json)
            setBody("""{"status":"SUSPENDED"}""")
        }

        // Reactivate
        val response = client.put("/admin/issuer/$issuerId") {
            contentType(ContentType.Application.Json)
            setBody("""{"status":"ACTIVE"}""")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.bodyAsText().contains("ACTIVE"))
    }

    @Test
    fun `PUT admin issuer rejects REVOKED to ACTIVE`() = testApplication {
        install(ContentNegotiation) { json() }
        application { issuerTenantAdminRoutes() }
        enableStore()

        val createResponse = client.post("/admin/issuer") {
            contentType(ContentType.Application.Json)
            setBody(createIssuerJson())
        }
        val created = Json.parseToJsonElement(createResponse.bodyAsText()).jsonObject
        val issuerId = created["id"]!!.jsonPrimitive.content

        // Revoke
        client.put("/admin/issuer/$issuerId") {
            contentType(ContentType.Application.Json)
            setBody("""{"status":"REVOKED"}""")
        }

        // Try to reactivate
        val response = client.put("/admin/issuer/$issuerId") {
            contentType(ContentType.Application.Json)
            setBody("""{"status":"ACTIVE"}""")
        }
        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertTrue(response.bodyAsText().contains("Cannot reactivate"))
    }

    // ===== LOTL / TSL Endpoint Tests =====

    @Test
    fun `GET lotl xml returns LOTL with country pointers`() = testApplication {
        install(ContentNegotiation) { json() }
        application { issuerTenantAdminRoutes() }
        enableStore()

        // Register 2 AU issuers + 1 IN issuer, generate certs for each
        val au1 = client.post("/admin/issuer") {
            contentType(ContentType.Application.Json)
            setBody(createIssuerJson(legalName = "AU Issuer One", country = "AU", domain = "au1.example.com"))
        }
        val au1Id = Json.parseToJsonElement(au1.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content
        client.post("/admin/issuer/$au1Id/certificate/generate")

        val au2 = client.post("/admin/issuer") {
            contentType(ContentType.Application.Json)
            setBody(createIssuerJson(legalName = "AU Issuer Two", country = "AU", domain = "au2.example.com"))
        }
        val au2Id = Json.parseToJsonElement(au2.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content
        client.post("/admin/issuer/$au2Id/certificate/generate")

        val india = client.post("/admin/issuer") {
            contentType(ContentType.Application.Json)
            setBody(createIssuerJson(legalName = "India Issuer", country = "IN", domain = "in.example.com"))
        }
        val inId = Json.parseToJsonElement(india.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content
        client.post("/admin/issuer/$inId/certificate/generate")

        val response = client.get("/admin/issuer/lotl.xml")
        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("TrustServiceStatusList"), "Should be valid TSL XML")
        assertTrue(body.contains("PointersToOtherTSL"), "Should contain pointers section")
        assertTrue(body.contains("tsl/AU.xml"), "Should have AU country pointer")
        assertTrue(body.contains("tsl/IN.xml"), "Should have IN country pointer")
        assertTrue(body.contains("List of Trusted Lists"), "Should have LOTL title")
    }

    @Test
    fun `GET lotl xml returns empty pointers when no issuers have certs`() = testApplication {
        install(ContentNegotiation) { json() }
        application { issuerTenantAdminRoutes() }
        enableStore()

        // Register an issuer without generating certs
        client.post("/admin/issuer") {
            contentType(ContentType.Application.Json)
            setBody(createIssuerJson())
        }

        val response = client.get("/admin/issuer/lotl.xml")
        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("TrustServiceStatusList"), "Should be valid TSL XML")
        assertTrue(body.contains("PointersToOtherTSL"), "Should contain pointers section")
        assertTrue(!body.contains("OtherTSLPointer"), "Should have no country pointers")
    }

    @Test
    fun `GET lotl xml returns 503 when feature disabled`() = testApplication {
        install(ContentNegotiation) { json() }
        application { issuerTenantAdminRoutes() }

        val response = client.get("/admin/issuer/lotl.xml")
        assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
    }

    @Test
    fun `GET tsl country xml returns providers for that country`() = testApplication {
        install(ContentNegotiation) { json() }
        application { issuerTenantAdminRoutes() }
        enableStore()

        // Register 2 AU issuers with certs
        val au1 = client.post("/admin/issuer") {
            contentType(ContentType.Application.Json)
            setBody(createIssuerJson(legalName = "AU Issuer One", country = "AU", domain = "au1.example.com"))
        }
        val au1Id = Json.parseToJsonElement(au1.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content
        client.post("/admin/issuer/$au1Id/certificate/generate")

        val au2 = client.post("/admin/issuer") {
            contentType(ContentType.Application.Json)
            setBody(createIssuerJson(legalName = "AU Issuer Two", country = "AU", domain = "au2.example.com"))
        }
        val au2Id = Json.parseToJsonElement(au2.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content
        client.post("/admin/issuer/$au2Id/certificate/generate")

        val response = client.get("/admin/issuer/tsl/AU.xml")
        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("TrustServiceStatusList"), "Should be valid TSL XML")
        assertTrue(body.contains("AU Issuer One"), "Should contain first AU issuer")
        assertTrue(body.contains("AU Issuer Two"), "Should contain second AU issuer")
        assertTrue(body.contains("X509Certificate"), "Should contain certificate data")
        assertTrue(body.contains("SchemeTerritory>AU"), "Should have AU territory")
    }

    @Test
    fun `GET tsl country xml is case insensitive`() = testApplication {
        install(ContentNegotiation) { json() }
        application { issuerTenantAdminRoutes() }
        enableStore()

        val au = client.post("/admin/issuer") {
            contentType(ContentType.Application.Json)
            setBody(createIssuerJson(legalName = "AU Issuer", country = "AU", domain = "au.example.com"))
        }
        val auId = Json.parseToJsonElement(au.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content
        client.post("/admin/issuer/$auId/certificate/generate")

        val response = client.get("/admin/issuer/tsl/au.xml")
        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.bodyAsText().contains("AU Issuer"))
    }

    @Test
    fun `GET tsl country xml returns 404 for unknown country`() = testApplication {
        install(ContentNegotiation) { json() }
        application { issuerTenantAdminRoutes() }
        enableStore()

        val response = client.get("/admin/issuer/tsl/ZZ.xml")
        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    @Test
    fun `GET tsl country xml excludes suspended issuers`() = testApplication {
        install(ContentNegotiation) { json() }
        application { issuerTenantAdminRoutes() }
        enableStore()

        // Register 2 AU issuers with certs
        val au1 = client.post("/admin/issuer") {
            contentType(ContentType.Application.Json)
            setBody(createIssuerJson(legalName = "AU Active", country = "AU", domain = "au-active.example.com"))
        }
        val au1Id = Json.parseToJsonElement(au1.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content
        client.post("/admin/issuer/$au1Id/certificate/generate")

        val au2 = client.post("/admin/issuer") {
            contentType(ContentType.Application.Json)
            setBody(createIssuerJson(legalName = "AU Suspended", country = "AU", domain = "au-suspended.example.com"))
        }
        val au2Id = Json.parseToJsonElement(au2.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content
        client.post("/admin/issuer/$au2Id/certificate/generate")

        // Suspend second issuer
        client.put("/admin/issuer/$au2Id") {
            contentType(ContentType.Application.Json)
            setBody("""{"status":"SUSPENDED"}""")
        }

        val response = client.get("/admin/issuer/tsl/AU.xml")
        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("AU Active"), "Should contain active issuer")
        assertTrue(!body.contains("AU Suspended"), "Should NOT contain suspended issuer")
    }

    @Test
    fun `GET tsl country xml returns 503 when feature disabled`() = testApplication {
        install(ContentNegotiation) { json() }
        application { issuerTenantAdminRoutes() }

        val response = client.get("/admin/issuer/tsl/AU.xml")
        assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
    }

    // ===== Summary Response Tests =====

    @Test
    fun `GET admin issuer list returns summary format with hasCertificate`() = testApplication {
        install(ContentNegotiation) { json() }
        application { issuerTenantAdminRoutes() }
        enableStore()

        val createResponse = client.post("/admin/issuer") {
            contentType(ContentType.Application.Json)
            setBody(createIssuerJson())
        }
        val created = Json.parseToJsonElement(createResponse.bodyAsText()).jsonObject
        val issuerId = created["id"]!!.jsonPrimitive.content

        // Before cert generation
        val listBefore = client.get("/admin/issuer")
        assertTrue(listBefore.bodyAsText().contains("\"hasCertificate\":false"))

        // Generate cert
        client.post("/admin/issuer/$issuerId/certificate/generate")

        // After cert generation
        val listAfter = client.get("/admin/issuer")
        assertTrue(listAfter.bodyAsText().contains("\"hasCertificate\":true"))
    }

    // ===== LOTL XML Structure Tests =====

    @Test
    fun `LOTL has correct root element and namespace`() = testApplication {
        install(ContentNegotiation) { json() }
        application { issuerTenantAdminRoutes() }
        enableStore()

        // Register an issuer with cert
        val au = client.post("/admin/issuer") {
            contentType(ContentType.Application.Json)
            setBody(createIssuerJson(legalName = "AU Issuer", country = "AU", domain = "au.example.com"))
        }
        val auId = Json.parseToJsonElement(au.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content
        client.post("/admin/issuer/$auId/certificate/generate")

        val response = client.get("/admin/issuer/lotl.xml")
        assertEquals(HttpStatusCode.OK, response.status)
        val doc = parseXml(response.bodyAsText())
        assertEquals("TrustServiceStatusList", doc.documentElement.localName)
        assertEquals("http://uri.etsi.org/02231/v2#", doc.documentElement.namespaceURI)
    }

    @Test
    fun `LOTL contains SchemeInformation`() = testApplication {
        install(ContentNegotiation) { json() }
        application { issuerTenantAdminRoutes() }
        enableStore()

        val au = client.post("/admin/issuer") {
            contentType(ContentType.Application.Json)
            setBody(createIssuerJson(legalName = "AU Issuer", country = "AU", domain = "au.example.com"))
        }
        val auId = Json.parseToJsonElement(au.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content
        client.post("/admin/issuer/$auId/certificate/generate")

        val response = client.get("/admin/issuer/lotl.xml")
        val doc = parseXml(response.bodyAsText())

        val schemeInfo = doc.getElementsByTagNameNS("SchemeInformation")
        assertTrue(schemeInfo.length > 0, "Should contain SchemeInformation element")

        val operatorName = doc.getElementsByTagNameNS("SchemeOperatorName")
        assertTrue(operatorName.length > 0, "Should contain SchemeOperatorName")

        val listIssueDate = doc.getElementsByTagNameNS("ListIssueDateTime")
        assertTrue(listIssueDate.length > 0, "Should contain ListIssueDateTime")
    }

    @Test
    fun `LOTL pointers have TSLLocation with xml suffix`() = testApplication {
        install(ContentNegotiation) { json() }
        application { issuerTenantAdminRoutes() }
        enableStore()

        val au = client.post("/admin/issuer") {
            contentType(ContentType.Application.Json)
            setBody(createIssuerJson(legalName = "AU Issuer", country = "AU", domain = "au.example.com"))
        }
        val auId = Json.parseToJsonElement(au.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content
        client.post("/admin/issuer/$auId/certificate/generate")

        val response = client.get("/admin/issuer/lotl.xml")
        val doc = parseXml(response.bodyAsText())

        val tslLocations = doc.getElementsByTagNameNS("TSLLocation")
        assertTrue(tslLocations.length > 0, "Should have at least one TSLLocation")
        for (i in 0 until tslLocations.length) {
            val url = tslLocations.item(i).textContent
            assertTrue(url.endsWith(".xml"), "TSLLocation should end with .xml: $url")
        }
    }

    @Test
    fun `LOTL pointers have SchemeTerritory matching registered countries`() = testApplication {
        install(ContentNegotiation) { json() }
        application { issuerTenantAdminRoutes() }
        enableStore()

        // Register AU and IN issuers
        val au = client.post("/admin/issuer") {
            contentType(ContentType.Application.Json)
            setBody(createIssuerJson(legalName = "AU Issuer", country = "AU", domain = "au.example.com"))
        }
        val auId = Json.parseToJsonElement(au.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content
        client.post("/admin/issuer/$auId/certificate/generate")

        val india = client.post("/admin/issuer") {
            contentType(ContentType.Application.Json)
            setBody(createIssuerJson(legalName = "IN Issuer", country = "IN", domain = "in.example.com"))
        }
        val inId = Json.parseToJsonElement(india.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content
        client.post("/admin/issuer/$inId/certificate/generate")

        val response = client.get("/admin/issuer/lotl.xml")
        val doc = parseXml(response.bodyAsText())

        // Find SchemeTerritory elements within pointer section
        val territories = doc.getElementsByTagNameNS("SchemeTerritory")
        val countrySet = mutableSetOf<String>()
        for (i in 0 until territories.length) {
            countrySet.add(territories.item(i).textContent.trim())
        }
        assertTrue(countrySet.contains("AU"), "Should have AU territory")
        assertTrue(countrySet.contains("IN"), "Should have IN territory")
    }

    // ===== TSL XML Structure Tests =====

    @Test
    fun `TSL has correct root element`() = testApplication {
        install(ContentNegotiation) { json() }
        application { issuerTenantAdminRoutes() }
        enableStore()

        val au = client.post("/admin/issuer") {
            contentType(ContentType.Application.Json)
            setBody(createIssuerJson(legalName = "AU Issuer", country = "AU", domain = "au.example.com"))
        }
        val auId = Json.parseToJsonElement(au.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content
        client.post("/admin/issuer/$auId/certificate/generate")

        val response = client.get("/admin/issuer/tsl/AU.xml")
        assertEquals(HttpStatusCode.OK, response.status)
        val doc = parseXml(response.bodyAsText())
        assertEquals("TrustServiceStatusList", doc.documentElement.localName)
        assertEquals("http://uri.etsi.org/02231/v2#", doc.documentElement.namespaceURI)
    }

    @Test
    fun `TSL contains TrustServiceProvider entries`() = testApplication {
        install(ContentNegotiation) { json() }
        application { issuerTenantAdminRoutes() }
        enableStore()

        val au = client.post("/admin/issuer") {
            contentType(ContentType.Application.Json)
            setBody(createIssuerJson(legalName = "AU Issuer", country = "AU", domain = "au.example.com"))
        }
        val auId = Json.parseToJsonElement(au.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content
        client.post("/admin/issuer/$auId/certificate/generate")

        val response = client.get("/admin/issuer/tsl/AU.xml")
        val doc = parseXml(response.bodyAsText())

        val providers = doc.getElementsByTagNameNS("TrustServiceProvider")
        assertTrue(providers.length > 0, "Should have at least one TrustServiceProvider")

        val tspNames = doc.getElementsByTagNameNS("TSPName")
        assertTrue(tspNames.length > 0, "Each provider should have TSPName")

        val tspServices = doc.getElementsByTagNameNS("TSPServices")
        assertTrue(tspServices.length > 0, "Each provider should have TSPServices")
    }

    @Test
    fun `TSL X509Certificate is valid Base64`() = testApplication {
        install(ContentNegotiation) { json() }
        application { issuerTenantAdminRoutes() }
        enableStore()

        val au = client.post("/admin/issuer") {
            contentType(ContentType.Application.Json)
            setBody(createIssuerJson(legalName = "AU Issuer", country = "AU", domain = "au.example.com"))
        }
        val auId = Json.parseToJsonElement(au.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content
        client.post("/admin/issuer/$auId/certificate/generate")

        val response = client.get("/admin/issuer/tsl/AU.xml")
        val doc = parseXml(response.bodyAsText())

        val certs = doc.getElementsByTagNameNS("X509Certificate")
        assertTrue(certs.length > 0, "Should contain X509Certificate elements")
        for (i in 0 until certs.length) {
            val certContent = certs.item(i).textContent.trim()
            assertTrue(certContent.isNotBlank(), "Certificate content should not be blank")
            // Verify it's valid Base64
            val decoded = java.util.Base64.getDecoder().decode(certContent)
            assertTrue(decoded.isNotEmpty(), "Should decode to non-empty byte array")
        }
    }

    @Test
    fun `TSL has X509SubjectName for each provider`() = testApplication {
        install(ContentNegotiation) { json() }
        application { issuerTenantAdminRoutes() }
        enableStore()

        val au = client.post("/admin/issuer") {
            contentType(ContentType.Application.Json)
            setBody(createIssuerJson(legalName = "AU Issuer", country = "AU", domain = "au.example.com"))
        }
        val auId = Json.parseToJsonElement(au.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content
        client.post("/admin/issuer/$auId/certificate/generate")

        val response = client.get("/admin/issuer/tsl/AU.xml")
        val doc = parseXml(response.bodyAsText())

        val subjectNames = doc.getElementsByTagNameNS("X509SubjectName")
        assertTrue(subjectNames.length > 0, "Should have X509SubjectName elements")
        for (i in 0 until subjectNames.length) {
            val name = subjectNames.item(i).textContent.trim()
            assertTrue(name.isNotBlank(), "X509SubjectName should not be blank")
        }
    }
}
