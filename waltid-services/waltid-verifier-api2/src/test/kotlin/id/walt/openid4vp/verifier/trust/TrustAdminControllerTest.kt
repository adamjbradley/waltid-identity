package id.walt.openid4vp.verifier.trust

import id.walt.commons.trust.TrustListServiceFactory
import id.walt.trust.TrustService
import id.walt.trust.TrustServiceStatus
import id.walt.trust.TrustSource
import id.walt.trust.TrustSourceStatus
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.testing.*
import io.mockk.*
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TrustAdminControllerTest {

    private lateinit var mockTrustService: TrustService

    @BeforeEach
    fun setUp() {
        mockkObject(TrustListServiceFactory)
        mockTrustService = mockk<TrustService>()
    }

    @AfterEach
    fun tearDown() {
        unmockkAll()
    }

    // -- GET /admin/trust/status --

    @Test
    fun `test GET status returns 503 when feature disabled`() = testApplication {
        install(ContentNegotiation) { json() }
        application { trustAdminRoutes() }

        every { TrustListServiceFactory.getServiceOrNull() } returns null

        val response = client.get("/admin/trust/status")

        assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("Trust lists feature is not enabled"), "Response should contain error message")
    }

    @Test
    fun `test GET status returns status when enabled`() = testApplication {
        install(ContentNegotiation) { json() }
        application { trustAdminRoutes() }

        val now = kotlin.time.Clock.System.now()
        val expectedStatus = TrustServiceStatus(
            healthy = true,
            sources = mapOf(
                TrustSource.ETSI_TL to TrustSourceStatus(
                    enabled = true,
                    healthy = true,
                    lastUpdate = now,
                    entryCount = 42
                ),
                TrustSource.OPENID_FEDERATION to TrustSourceStatus(
                    enabled = false,
                    healthy = false,
                    entryCount = 0
                )
            ),
            lastUpdate = now
        )

        every { TrustListServiceFactory.getServiceOrNull() } returns mockTrustService
        coEvery { mockTrustService.getStatus() } returns expectedStatus

        val response = client.get("/admin/trust/status")

        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("\"healthy\":true"), "Response should contain healthy field")
        assertTrue(body.contains("etsi_tl"), "Response should contain etsi_tl source")
        assertTrue(body.contains("openid_federation"), "Response should contain openid_federation source")
    }

    // -- PUT /admin/trust/etsi --

    @Test
    fun `test PUT etsi returns 503 when disabled`() = testApplication {
        install(ContentNegotiation) { json() }
        application { trustAdminRoutes() }

        every { TrustListServiceFactory.getServiceOrNull() } returns null

        val response = client.put("/admin/trust/etsi") {
            contentType(ContentType.Application.Json)
            setBody("""{"enabled": true}""")
        }

        assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("Trust lists feature is not enabled"), "Response should contain error message")
    }

    @Test
    fun `test PUT etsi toggles source enabled`() = testApplication {
        install(ContentNegotiation) { json() }
        application { trustAdminRoutes() }

        every { TrustListServiceFactory.getServiceOrNull() } returns mockTrustService
        coEvery { mockTrustService.setEnabled(TrustSource.ETSI_TL, true) } just Runs

        val response = client.put("/admin/trust/etsi") {
            contentType(ContentType.Application.Json)
            setBody("""{"enabled": true}""")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("etsi_tl"), "Response should contain source name")
        assertTrue(body.contains("\"enabled\":\"true\""), "Response should confirm enabled is true")

        coVerify { mockTrustService.setEnabled(TrustSource.ETSI_TL, true) }
    }

    @Test
    fun `test PUT etsi toggles source disabled`() = testApplication {
        install(ContentNegotiation) { json() }
        application { trustAdminRoutes() }

        every { TrustListServiceFactory.getServiceOrNull() } returns mockTrustService
        coEvery { mockTrustService.setEnabled(TrustSource.ETSI_TL, false) } just Runs

        val response = client.put("/admin/trust/etsi") {
            contentType(ContentType.Application.Json)
            setBody("""{"enabled": false}""")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("etsi_tl"), "Response should contain source name")
        assertTrue(body.contains("\"enabled\":\"false\""), "Response should confirm enabled is false")

        coVerify { mockTrustService.setEnabled(TrustSource.ETSI_TL, false) }
    }

    // -- PUT /admin/trust/federation --

    @Test
    fun `test PUT federation returns 503 when disabled`() = testApplication {
        install(ContentNegotiation) { json() }
        application { trustAdminRoutes() }

        every { TrustListServiceFactory.getServiceOrNull() } returns null

        val response = client.put("/admin/trust/federation") {
            contentType(ContentType.Application.Json)
            setBody("""{"enabled": true}""")
        }

        assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("Trust lists feature is not enabled"), "Response should contain error message")
    }

    @Test
    fun `test PUT federation toggles source enabled`() = testApplication {
        install(ContentNegotiation) { json() }
        application { trustAdminRoutes() }

        every { TrustListServiceFactory.getServiceOrNull() } returns mockTrustService
        coEvery { mockTrustService.setEnabled(TrustSource.OPENID_FEDERATION, true) } just Runs

        val response = client.put("/admin/trust/federation") {
            contentType(ContentType.Application.Json)
            setBody("""{"enabled": true}""")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("openid_federation"), "Response should contain source name")
        assertTrue(body.contains("\"enabled\":\"true\""), "Response should confirm enabled is true")

        coVerify { mockTrustService.setEnabled(TrustSource.OPENID_FEDERATION, true) }
    }

    @Test
    fun `test PUT federation toggles source disabled`() = testApplication {
        install(ContentNegotiation) { json() }
        application { trustAdminRoutes() }

        every { TrustListServiceFactory.getServiceOrNull() } returns mockTrustService
        coEvery { mockTrustService.setEnabled(TrustSource.OPENID_FEDERATION, false) } just Runs

        val response = client.put("/admin/trust/federation") {
            contentType(ContentType.Application.Json)
            setBody("""{"enabled": false}""")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("openid_federation"), "Response should contain source name")
        assertTrue(body.contains("\"enabled\":\"false\""), "Response should confirm enabled is false")

        coVerify { mockTrustService.setEnabled(TrustSource.OPENID_FEDERATION, false) }
    }

    // -- POST /admin/trust/refresh --

    @Test
    fun `test POST refresh returns 503 when disabled`() = testApplication {
        install(ContentNegotiation) { json() }
        application { trustAdminRoutes() }

        every { TrustListServiceFactory.getServiceOrNull() } returns null

        val response = client.post("/admin/trust/refresh")

        assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("Trust lists feature is not enabled"), "Response should contain error message")
    }

    @Test
    fun `test POST refresh returns status when enabled`() = testApplication {
        install(ContentNegotiation) { json() }
        application { trustAdminRoutes() }

        val now = kotlin.time.Clock.System.now()
        val expectedStatus = TrustServiceStatus(
            healthy = true,
            sources = mapOf(
                TrustSource.ETSI_TL to TrustSourceStatus(
                    enabled = true,
                    healthy = true,
                    lastUpdate = now,
                    entryCount = 100
                )
            ),
            lastUpdate = now
        )

        every { TrustListServiceFactory.getServiceOrNull() } returns mockTrustService
        coEvery { mockTrustService.getStatus() } returns expectedStatus

        val response = client.post("/admin/trust/refresh")

        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("\"healthy\":true"), "Response should contain healthy field")
        assertTrue(body.contains("etsi_tl"), "Response should contain etsi_tl source")
        assertTrue(body.contains("100"), "Response should contain entry count")
    }
}
