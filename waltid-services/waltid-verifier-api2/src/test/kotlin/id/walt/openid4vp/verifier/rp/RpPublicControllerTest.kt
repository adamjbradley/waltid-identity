package id.walt.openid4vp.verifier.rp

import id.walt.commons.config.ConfigManager
import id.walt.openid4vp.verifier.OSSVerifier2ServiceConfig
import id.walt.verifier.openid.models.authorization.ClientMetadata
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RpPublicControllerTest {

    @TempDir
    lateinit var tempDir: File

    // Self-signed test certificate (base64 DER) — generated once for tests
    private val testCertDer: String by lazy {
        val generated = RpCertificateService.generateCertificate("Test Cert", "test.example.com")
        generated.x5c.first()
    }

    @BeforeEach
    fun setUp() {
        RelyingPartyStore.resetForTesting()
        ConfigManager.preclear()
    }

    @AfterEach
    fun tearDown() {
        RelyingPartyStore.resetForTesting()
        ConfigManager.preclear()
    }

    private fun preloadVerifierConfig(x5c: List<String>? = null) {
        ConfigManager.preloadAndRegisterConfig(
            "verifier-service", OSSVerifier2ServiceConfig(
                clientId = "verifier2",
                clientMetadata = ClientMetadata(clientName = "Test Verifier"),
                urlPrefix = "http://localhost:7003/verification-session",
                urlHost = "openid4vp://authorize",
                x5c = x5c
            )
        )
        ConfigManager.loadConfigs()
    }

    private fun enableStoreAndCreateRp(
        legalName: String = "Test RP",
        domain: String = "test.example.com",
        status: RpStatus = RpStatus.ACTIVE,
        withCert: Boolean = true
    ): RelyingParty {
        RelyingPartyStore.init(tempDir)
        val generated = if (withCert) RpCertificateService.generateCertificate(legalName, domain) else null
        val rp = RelyingParty(
            id = java.util.UUID.randomUUID().toString(),
            legalName = legalName,
            domain = domain,
            country = "AU",
            contactEmail = "test@example.com",
            clientId = "x509_san_dns:$domain",
            certificate = generated?.certInfo,
            privateKeyJwk = generated?.privateKeyJwk,
            x5c = generated?.x5c,
            status = status,
            createdAt = java.time.Instant.now().toString(),
            updatedAt = java.time.Instant.now().toString()
        )
        RelyingPartyStore.instanceOrNull()!!.save(rp)
        return rp
    }

    // ===== PEM Response Tests =====

    @Test
    fun `returns PEM bundle with verifier cert when RP feature disabled`() = testApplication {
        install(ContentNegotiation) { json() }
        application { rpPublicRoutes() }
        preloadVerifierConfig(x5c = listOf(testCertDer))

        val response = client.get("/.well-known/rp-certificates")
        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("-----BEGIN CERTIFICATE-----"), "Should contain PEM header")
        assertTrue(body.contains("-----END CERTIFICATE-----"), "Should contain PEM footer")
    }

    @Test
    fun `returns empty when no certs available`() = testApplication {
        install(ContentNegotiation) { json() }
        application { rpPublicRoutes() }
        preloadVerifierConfig(x5c = null)

        val response = client.get("/.well-known/rp-certificates")
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("", response.bodyAsText())
    }

    @Test
    fun `returns PEM bundle with active RP certs and verifier cert`() = testApplication {
        install(ContentNegotiation) { json() }
        application { rpPublicRoutes() }
        preloadVerifierConfig(x5c = listOf(testCertDer))

        enableStoreAndCreateRp(legalName = "RP Alpha", domain = "alpha.example.com")
        enableStoreAndCreateRp(legalName = "RP Beta", domain = "beta.example.com")

        val response = client.get("/.well-known/rp-certificates")
        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()

        // Should contain 3 PEM blocks: 2 RPs + 1 verifier
        val certCount = Regex("-----BEGIN CERTIFICATE-----").findAll(body).count()
        assertEquals(3, certCount, "Should have 3 certificates (2 RP + 1 verifier)")
    }

    @Test
    fun `excludes SUSPENDED and REVOKED RPs`() = testApplication {
        install(ContentNegotiation) { json() }
        application { rpPublicRoutes() }
        preloadVerifierConfig(x5c = null)

        enableStoreAndCreateRp(legalName = "Active RP", domain = "active.example.com", status = RpStatus.ACTIVE)
        enableStoreAndCreateRp(legalName = "Suspended RP", domain = "suspended.example.com", status = RpStatus.SUSPENDED)
        enableStoreAndCreateRp(legalName = "Revoked RP", domain = "revoked.example.com", status = RpStatus.REVOKED)

        val response = client.get("/.well-known/rp-certificates")
        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()

        // Should contain only 1 PEM block (the ACTIVE RP)
        val certCount = Regex("-----BEGIN CERTIFICATE-----").findAll(body).count()
        assertEquals(1, certCount, "Should have only 1 certificate (active RP only)")
    }

    @Test
    fun `excludes RPs without certificates`() = testApplication {
        install(ContentNegotiation) { json() }
        application { rpPublicRoutes() }
        preloadVerifierConfig(x5c = null)

        enableStoreAndCreateRp(legalName = "With Cert", domain = "cert.example.com", withCert = true)
        enableStoreAndCreateRp(legalName = "No Cert", domain = "nocert.example.com", withCert = false)

        val response = client.get("/.well-known/rp-certificates")
        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()

        val certCount = Regex("-----BEGIN CERTIFICATE-----").findAll(body).count()
        assertEquals(1, certCount, "Should have only 1 certificate (RP with cert only)")
    }

    @Test
    fun `PEM does not contain private key material`() = testApplication {
        install(ContentNegotiation) { json() }
        application { rpPublicRoutes() }
        preloadVerifierConfig(x5c = listOf(testCertDer))

        enableStoreAndCreateRp()

        val response = client.get("/.well-known/rp-certificates")
        val body = response.bodyAsText()

        assertFalse(body.contains("PRIVATE KEY"), "PEM should not contain private key")
        assertFalse(body.contains("privateKeyJwk"), "PEM should not contain privateKeyJwk")
    }

    // ===== JSON Response Tests =====

    @Test
    fun `returns JSON with Accept application json`() = testApplication {
        install(ContentNegotiation) { json() }
        application { rpPublicRoutes() }
        preloadVerifierConfig(x5c = listOf(testCertDer))

        enableStoreAndCreateRp(legalName = "JSON RP", domain = "json.example.com")

        val response = client.get("/.well-known/rp-certificates") {
            accept(ContentType.Application.Json)
        }
        assertEquals(HttpStatusCode.OK, response.status)
        val json = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        val certs = json["certificates"]!!.jsonArray
        val count = json["count"]!!.jsonPrimitive.int

        assertEquals(2, count, "Should have 2 certificates (1 RP + 1 verifier)")
        assertEquals(2, certs.size)

        // Check RP entry has expected fields
        val rpEntry = certs.first { it.jsonObject["source"]?.jsonPrimitive?.content == "rp" }.jsonObject
        assertTrue(rpEntry.containsKey("domain"), "RP entry should have domain")
        assertTrue(rpEntry.containsKey("rpId"), "RP entry should have rpId")
        assertTrue(rpEntry.containsKey("fingerprint"), "RP entry should have fingerprint")
        assertEquals("json.example.com", rpEntry["domain"]!!.jsonPrimitive.content)

        // Check verifier entry
        val verifierEntry = certs.first { it.jsonObject["source"]?.jsonPrimitive?.content == "verifier" }.jsonObject
        assertTrue(verifierEntry.containsKey("subject"), "Verifier entry should have subject")
        assertTrue(verifierEntry.containsKey("fingerprint"), "Verifier entry should have fingerprint")
    }

    // ===== ETag Caching Tests =====

    @Test
    fun `includes ETag header in response`() = testApplication {
        install(ContentNegotiation) { json() }
        application { rpPublicRoutes() }
        preloadVerifierConfig(x5c = listOf(testCertDer))

        val response = client.get("/.well-known/rp-certificates")
        val etag = response.headers[HttpHeaders.ETag]
        assertTrue(etag != null && etag.isNotEmpty(), "Should include ETag header")
    }

    @Test
    fun `returns 304 Not Modified with matching If-None-Match`() = testApplication {
        install(ContentNegotiation) { json() }
        application { rpPublicRoutes() }
        preloadVerifierConfig(x5c = listOf(testCertDer))

        // First request to get ETag
        val first = client.get("/.well-known/rp-certificates")
        val etag = first.headers[HttpHeaders.ETag]!!

        // Second request with If-None-Match
        val second = client.get("/.well-known/rp-certificates") {
            header(HttpHeaders.IfNoneMatch, etag)
        }
        assertEquals(HttpStatusCode.NotModified, second.status)
    }
}
