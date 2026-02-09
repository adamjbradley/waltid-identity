package id.walt.openid4vp.verifier.rp

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
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class RpAdminControllerTest {

    @TempDir
    lateinit var tempDir: File

    @BeforeEach
    fun setUp() {
        // Reset any previous store instance
        RelyingPartyStore.resetForTesting()
    }

    @AfterEach
    fun tearDown() {
        RelyingPartyStore.resetForTesting()
    }

    private fun createRpJson(
        legalName: String = "Test RP",
        country: String = "AU",
        domain: String = "test.example.com",
        contactEmail: String = "admin@example.com",
        contactAddress: String = "123 Test St",
        privacyPolicyUrl: String = "https://example.com/privacy",
        dataRetentionPeriod: String = "12 months",
        lawfulBasis: String = "CONSENT",
        dpaAcknowledged: Boolean = true
    ) = """{"legalName":"$legalName","country":"$country","domain":"$domain","contactEmail":"$contactEmail","contactAddress":"$contactAddress","privacyPolicyUrl":"$privacyPolicyUrl","dataRetentionPeriod":"$dataRetentionPeriod","lawfulBasis":"$lawfulBasis","dpaAcknowledged":$dpaAcknowledged}"""

    // ===== Feature Disabled Tests (503) =====

    @Test
    fun `GET admin rp returns 503 when feature disabled`() = testApplication {
        install(ContentNegotiation) { json() }
        application { rpAdminRoutes() }

        val response = client.get("/admin/rp")
        assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
        assertTrue(response.bodyAsText().contains("RP Registrar feature is not enabled"))
    }

    @Test
    fun `POST admin rp returns 503 when feature disabled`() = testApplication {
        install(ContentNegotiation) { json() }
        application { rpAdminRoutes() }

        val response = client.post("/admin/rp") {
            contentType(ContentType.Application.Json)
            setBody(createRpJson())
        }
        assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
    }

    @Test
    fun `GET admin rp id returns 503 when feature disabled`() = testApplication {
        install(ContentNegotiation) { json() }
        application { rpAdminRoutes() }

        val response = client.get("/admin/rp/some-id")
        assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
    }

    @Test
    fun `PUT admin rp id returns 503 when feature disabled`() = testApplication {
        install(ContentNegotiation) { json() }
        application { rpAdminRoutes() }

        val response = client.put("/admin/rp/some-id") {
            contentType(ContentType.Application.Json)
            setBody("""{"legalName":"Updated"}""")
        }
        assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
    }

    @Test
    fun `DELETE admin rp id returns 503 when feature disabled`() = testApplication {
        install(ContentNegotiation) { json() }
        application { rpAdminRoutes() }

        val response = client.delete("/admin/rp/some-id")
        assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
    }

    @Test
    fun `POST certificate generate returns 503 when feature disabled`() = testApplication {
        install(ContentNegotiation) { json() }
        application { rpAdminRoutes() }

        val response = client.post("/admin/rp/some-id/certificate/generate")
        assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
    }

    @Test
    fun `POST certificate upload returns 503 when feature disabled`() = testApplication {
        install(ContentNegotiation) { json() }
        application { rpAdminRoutes() }

        val response = client.post("/admin/rp/some-id/certificate/upload") {
            contentType(ContentType.Application.Json)
            setBody("""{"certificatePem":"test"}""")
        }
        assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
    }

    @Test
    fun `GET certificate download returns 503 when feature disabled`() = testApplication {
        install(ContentNegotiation) { json() }
        application { rpAdminRoutes() }

        val response = client.get("/admin/rp/some-id/certificate/download")
        assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
    }

    // ===== Feature Enabled Tests =====

    private fun enableStore() {
        RelyingPartyStore.init(tempDir)
    }

    @Test
    fun `POST admin rp creates RP and returns 201`() = testApplication {
        install(ContentNegotiation) { json() }
        application { rpAdminRoutes() }
        enableStore()

        val response = client.post("/admin/rp") {
            contentType(ContentType.Application.Json)
            setBody(createRpJson())
        }

        assertEquals(HttpStatusCode.Created, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("Test RP"), "Should contain legal name")
        assertTrue(body.contains("test.example.com"), "Should contain domain")
        assertTrue(body.contains("ACTIVE"), "Should have ACTIVE status")
    }

    @Test
    fun `POST admin rp auto-generates clientId from domain`() = testApplication {
        install(ContentNegotiation) { json() }
        application { rpAdminRoutes() }
        enableStore()

        val response = client.post("/admin/rp") {
            contentType(ContentType.Application.Json)
            setBody(createRpJson(domain = "verifier.mycompany.com"))
        }

        assertEquals(HttpStatusCode.Created, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("x509_san_dns:verifier.mycompany.com"), "ClientId should be auto-generated")
    }

    @Test
    fun `GET admin rp returns list of RPs`() = testApplication {
        install(ContentNegotiation) { json() }
        application { rpAdminRoutes() }
        enableStore()

        // Create two RPs
        client.post("/admin/rp") {
            contentType(ContentType.Application.Json)
            setBody(createRpJson(legalName = "RP One", domain = "one.example.com"))
        }
        client.post("/admin/rp") {
            contentType(ContentType.Application.Json)
            setBody(createRpJson(legalName = "RP Two", domain = "two.example.com"))
        }

        val response = client.get("/admin/rp")
        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("RP One"))
        assertTrue(body.contains("RP Two"))
    }

    @Test
    fun `GET admin rp id returns RP detail without privateKeyJwk`() = testApplication {
        install(ContentNegotiation) { json() }
        application { rpAdminRoutes() }
        enableStore()

        val createResponse = client.post("/admin/rp") {
            contentType(ContentType.Application.Json)
            setBody(createRpJson())
        }
        val created = Json.parseToJsonElement(createResponse.bodyAsText()).jsonObject
        val rpId = created["id"]!!.jsonPrimitive.content

        val response = client.get("/admin/rp/$rpId")
        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("Test RP"))
        // RpDetail DTO does not include privateKeyJwk
        assertTrue(!body.contains("privateKeyJwk"), "Detail should not expose private key")
    }

    @Test
    fun `GET admin rp unknown returns 404`() = testApplication {
        install(ContentNegotiation) { json() }
        application { rpAdminRoutes() }
        enableStore()

        val response = client.get("/admin/rp/nonexistent-id")
        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    @Test
    fun `PUT admin rp id updates RP fields`() = testApplication {
        install(ContentNegotiation) { json() }
        application { rpAdminRoutes() }
        enableStore()

        val createResponse = client.post("/admin/rp") {
            contentType(ContentType.Application.Json)
            setBody(createRpJson())
        }
        val created = Json.parseToJsonElement(createResponse.bodyAsText()).jsonObject
        val rpId = created["id"]!!.jsonPrimitive.content

        val response = client.put("/admin/rp/$rpId") {
            contentType(ContentType.Application.Json)
            setBody("""{"legalName":"Updated RP Name","contactEmail":"new@example.com"}""")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("Updated RP Name"))
        assertTrue(body.contains("new@example.com"))
    }

    @Test
    fun `DELETE admin rp id removes RP`() = testApplication {
        install(ContentNegotiation) { json() }
        application { rpAdminRoutes() }
        enableStore()

        val createResponse = client.post("/admin/rp") {
            contentType(ContentType.Application.Json)
            setBody(createRpJson())
        }
        val created = Json.parseToJsonElement(createResponse.bodyAsText()).jsonObject
        val rpId = created["id"]!!.jsonPrimitive.content

        val deleteResponse = client.delete("/admin/rp/$rpId")
        assertEquals(HttpStatusCode.OK, deleteResponse.status)

        // Verify it's gone
        val getResponse = client.get("/admin/rp/$rpId")
        assertEquals(HttpStatusCode.NotFound, getResponse.status)
    }

    @Test
    fun `DELETE admin rp unknown returns 404`() = testApplication {
        install(ContentNegotiation) { json() }
        application { rpAdminRoutes() }
        enableStore()

        val response = client.delete("/admin/rp/nonexistent")
        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    @Test
    fun `POST certificate generate creates cert and updates RP`() = testApplication {
        install(ContentNegotiation) { json() }
        application { rpAdminRoutes() }
        enableStore()

        val createResponse = client.post("/admin/rp") {
            contentType(ContentType.Application.Json)
            setBody(createRpJson())
        }
        val created = Json.parseToJsonElement(createResponse.bodyAsText()).jsonObject
        val rpId = created["id"]!!.jsonPrimitive.content

        val certResponse = client.post("/admin/rp/$rpId/certificate/generate")
        assertEquals(HttpStatusCode.Created, certResponse.status)
        val body = certResponse.bodyAsText()
        assertTrue(body.contains("certificate"), "Should contain certificate info")
        assertTrue(body.contains("x5c"), "Should contain x5c")
        assertTrue(body.contains("fingerprint"), "Should contain fingerprint")
    }

    @Test
    fun `GET certificate download returns 404 when no cert generated`() = testApplication {
        install(ContentNegotiation) { json() }
        application { rpAdminRoutes() }
        enableStore()

        val createResponse = client.post("/admin/rp") {
            contentType(ContentType.Application.Json)
            setBody(createRpJson())
        }
        val created = Json.parseToJsonElement(createResponse.bodyAsText()).jsonObject
        val rpId = created["id"]!!.jsonPrimitive.content

        val response = client.get("/admin/rp/$rpId/certificate/download")
        assertEquals(HttpStatusCode.NotFound, response.status)
        assertTrue(response.bodyAsText().contains("No generated certificate"))
    }

    @Test
    fun `PUT intended-use sets DCQL query`() = testApplication {
        install(ContentNegotiation) { json() }
        application { rpAdminRoutes() }
        enableStore()

        val createResponse = client.post("/admin/rp") {
            contentType(ContentType.Application.Json)
            setBody(createRpJson())
        }
        val created = Json.parseToJsonElement(createResponse.bodyAsText()).jsonObject
        val rpId = created["id"]!!.jsonPrimitive.content

        val response = client.put("/admin/rp/$rpId/intended-use") {
            contentType(ContentType.Application.Json)
            setBody("""{"intendedUse":"Age verification only","dcqlQuery":{"credentials":[{"id":"pid"}]}}""")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("Age verification only"))
        assertTrue(body.contains("credentials"))
    }

    // ===== Validation Tests =====

    @Test
    fun `POST admin rp rejects missing legalName`() = testApplication {
        install(ContentNegotiation) { json() }
        application { rpAdminRoutes() }
        enableStore()

        val response = client.post("/admin/rp") {
            contentType(ContentType.Application.Json)
            setBody(createRpJson(legalName = ""))
        }
        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertTrue(response.bodyAsText().contains("legalName"))
    }

    @Test
    fun `POST admin rp rejects missing domain`() = testApplication {
        install(ContentNegotiation) { json() }
        application { rpAdminRoutes() }
        enableStore()

        val response = client.post("/admin/rp") {
            contentType(ContentType.Application.Json)
            setBody(createRpJson(legalName = "Test", domain = ""))
        }
        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertTrue(response.bodyAsText().contains("domain"))
    }

    @Test
    fun `POST admin rp rejects duplicate domain`() = testApplication {
        install(ContentNegotiation) { json() }
        application { rpAdminRoutes() }
        enableStore()

        // Create first RP
        client.post("/admin/rp") {
            contentType(ContentType.Application.Json)
            setBody(createRpJson(domain = "unique.example.com"))
        }

        // Try to create second with same domain
        val response = client.post("/admin/rp") {
            contentType(ContentType.Application.Json)
            setBody(createRpJson(legalName = "Another RP", domain = "unique.example.com"))
        }
        assertEquals(HttpStatusCode.Conflict, response.status)
        assertTrue(response.bodyAsText().contains("already registered"))
    }

    @Test
    fun `PUT admin rp rejects REVOKED to ACTIVE`() = testApplication {
        install(ContentNegotiation) { json() }
        application { rpAdminRoutes() }
        enableStore()

        // Create and revoke
        val createResponse = client.post("/admin/rp") {
            contentType(ContentType.Application.Json)
            setBody(createRpJson())
        }
        val created = Json.parseToJsonElement(createResponse.bodyAsText()).jsonObject
        val rpId = created["id"]!!.jsonPrimitive.content

        // Set to REVOKED
        client.put("/admin/rp/$rpId") {
            contentType(ContentType.Application.Json)
            setBody("""{"status":"REVOKED"}""")
        }

        // Try to reactivate
        val response = client.put("/admin/rp/$rpId") {
            contentType(ContentType.Application.Json)
            setBody("""{"status":"ACTIVE"}""")
        }
        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertTrue(response.bodyAsText().contains("Cannot reactivate"))
    }
}
