package id.walt.openid4vp.verifier.trust

import id.walt.commons.trust.TrustListServiceFactory
import id.walt.trust.TrustService
import id.walt.trust.TrustServiceStatus
import id.walt.trust.TrustSource
import id.walt.trust.TrustSourceStatus
import id.walt.trust.models.TrustServiceEntry
import id.walt.trust.models.TrustServiceList
import id.walt.trust.models.TrustServiceProvider
import id.walt.trust.models.TslPointer
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

    // -- GET /admin/trust/lotl --

    @Test
    fun `test GET lotl returns 503 when feature disabled`() = testApplication {
        install(ContentNegotiation) { json() }
        application { trustAdminRoutes() }

        every { TrustListServiceFactory.getServiceOrNull() } returns null

        val response = client.get("/admin/trust/lotl")

        assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
    }

    @Test
    fun `test GET lotl returns 404 when no LOTL cached`() = testApplication {
        install(ContentNegotiation) { json() }
        application { trustAdminRoutes() }

        every { TrustListServiceFactory.getServiceOrNull() } returns mockTrustService
        every { mockTrustService.getLotl() } returns null

        val response = client.get("/admin/trust/lotl")

        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    @Test
    fun `test GET lotl returns LOTL overview with member states`() = testApplication {
        install(ContentNegotiation) { json() }
        application { trustAdminRoutes() }

        val lotl = TrustServiceList(
            schemeTerritory = "EU",
            schemeOperatorName = "European Commission",
            sequenceNumber = 312,
            pointers = listOf(
                TslPointer(location = "https://tsl.at/tsl.xml", schemeTerritory = "AT"),
                TslPointer(location = "https://tsl.de/tsl.xml", schemeTerritory = "DE")
            )
        )
        val memberStateTls = mapOf(
            "AT" to TrustServiceList(
                schemeTerritory = "AT",
                schemeOperatorName = "AT Authority",
                trustServiceProviders = listOf(
                    TrustServiceProvider(name = "A-Trust", trustServices = listOf(
                        TrustServiceEntry(serviceType = "CA/QC", serviceName = "Cert1", currentStatus = TrustServiceEntry.STATUS_GRANTED),
                        TrustServiceEntry(serviceType = "TSA", serviceName = "Timestamp1", currentStatus = TrustServiceEntry.STATUS_GRANTED)
                    ))
                )
            )
        )

        every { TrustListServiceFactory.getServiceOrNull() } returns mockTrustService
        every { mockTrustService.getLotl() } returns lotl
        every { mockTrustService.getMemberStateTls() } returns memberStateTls

        val response = client.get("/admin/trust/lotl")

        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("\"schemeTerritory\":\"EU\""), "Should contain EU territory")
        assertTrue(body.contains("European Commission"), "Should contain operator name")
        assertTrue(body.contains("\"country\":\"AT\""), "Should contain AT member state")
        assertTrue(body.contains("\"providerCount\":1"), "AT should have 1 provider")
        assertTrue(body.contains("\"serviceCount\":2"), "AT should have 2 services")
        assertTrue(body.contains("\"country\":\"DE\""), "Should contain DE member state")
        assertTrue(body.contains("\"healthy\":false"), "DE should be unhealthy (no TSL cached)")
    }

    // -- GET /admin/trust/lotl/{country} --

    @Test
    fun `test GET lotl country returns 503 when feature disabled`() = testApplication {
        install(ContentNegotiation) { json() }
        application { trustAdminRoutes() }

        every { TrustListServiceFactory.getServiceOrNull() } returns null

        val response = client.get("/admin/trust/lotl/AT")

        assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
    }

    @Test
    fun `test GET lotl country returns 404 for unknown country`() = testApplication {
        install(ContentNegotiation) { json() }
        application { trustAdminRoutes() }

        every { TrustListServiceFactory.getServiceOrNull() } returns mockTrustService
        every { mockTrustService.getMemberStateTl("XX") } returns null

        val response = client.get("/admin/trust/lotl/XX")

        assertEquals(HttpStatusCode.NotFound, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("XX"), "Should mention the country code")
    }

    @Test
    fun `test GET lotl country returns TSL detail with providers and services`() = testApplication {
        install(ContentNegotiation) { json() }
        application { trustAdminRoutes() }

        val tsl = TrustServiceList(
            schemeTerritory = "AT",
            schemeOperatorName = "Rundfunk und Telekom",
            sequenceNumber = 65,
            trustServiceProviders = listOf(
                TrustServiceProvider(
                    name = "A-Trust Gesellschaft",
                    tradeName = "A-Trust",
                    trustServices = listOf(
                        TrustServiceEntry(
                            serviceType = TrustServiceEntry.TYPE_CA_QC,
                            serviceName = "a]sign Premium Sig",
                            currentStatus = TrustServiceEntry.STATUS_GRANTED
                        ),
                        TrustServiceEntry(
                            serviceType = TrustServiceEntry.TYPE_TSA,
                            serviceName = "a]sign TSA",
                            currentStatus = TrustServiceEntry.STATUS_WITHDRAWN
                        )
                    )
                )
            )
        )

        every { TrustListServiceFactory.getServiceOrNull() } returns mockTrustService
        every { mockTrustService.getMemberStateTl("AT") } returns tsl

        val response = client.get("/admin/trust/lotl/AT")

        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("A-Trust Gesellschaft"), "Should contain provider name")
        assertTrue(body.contains("A-Trust"), "Should contain trade name")
        assertTrue(body.contains("a]sign Premium Sig"), "Should contain service name")
        assertTrue(body.contains("\"serviceTypeLabel\":\"CA/QC\""), "Should contain human-readable type")
        assertTrue(body.contains("\"status\":\"granted\""), "Should contain human-readable status")
        assertTrue(body.contains("\"status\":\"withdrawn\""), "Should contain withdrawn status")
        assertTrue(body.contains("\"isQualified\":true"), "CA/QC should be qualified")
    }

    // -- GET /admin/trust/search --

    @Test
    fun `test GET search returns 503 when feature disabled`() = testApplication {
        install(ContentNegotiation) { json() }
        application { trustAdminRoutes() }

        every { TrustListServiceFactory.getServiceOrNull() } returns null

        val response = client.get("/admin/trust/search?q=test")

        assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
    }

    @Test
    fun `test GET search returns matching providers`() = testApplication {
        install(ContentNegotiation) { json() }
        application { trustAdminRoutes() }

        val providers = listOf(
            TrustServiceProvider(
                name = "A-Trust",
                country = "AT",
                trustServices = listOf(
                    TrustServiceEntry(
                        serviceType = TrustServiceEntry.TYPE_CA_QC,
                        serviceName = "Cert Service",
                        currentStatus = TrustServiceEntry.STATUS_GRANTED
                    )
                )
            )
        )

        every { TrustListServiceFactory.getServiceOrNull() } returns mockTrustService
        coEvery { mockTrustService.searchProviders(
            query = "trust",
            country = null,
            status = null,
            serviceType = null,
            limit = 50,
            offset = 0
        ) } returns providers

        val response = client.get("/admin/trust/search?q=trust")

        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("A-Trust"), "Should contain matching provider")
        assertTrue(body.contains("\"total\":1"), "Should have total count")
        assertTrue(body.contains("\"query\":\"trust\""), "Should echo back query")
    }

    @Test
    fun `test GET search with filters passes them through`() = testApplication {
        install(ContentNegotiation) { json() }
        application { trustAdminRoutes() }

        every { TrustListServiceFactory.getServiceOrNull() } returns mockTrustService
        coEvery { mockTrustService.searchProviders(
            query = "test",
            country = "DE",
            status = "granted",
            serviceType = "CA/QC",
            limit = 10,
            offset = 5
        ) } returns emptyList()

        val response = client.get("/admin/trust/search?q=test&country=DE&status=granted&type=CA/QC&limit=10&offset=5")

        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("\"total\":0"), "Should have zero results")
        assertTrue(body.contains("\"country\":\"DE\""), "Should echo back country filter")
    }
}
