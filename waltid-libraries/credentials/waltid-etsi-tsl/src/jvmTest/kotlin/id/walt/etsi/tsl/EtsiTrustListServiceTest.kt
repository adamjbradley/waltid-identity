package id.walt.etsi.tsl

import id.walt.etsi.tsl.config.TslConfig
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import io.ktor.utils.io.*
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.test.*

class EtsiTrustListServiceTest {

    @Test
    fun `test isHealthy returns false initially`() {
        val config = TslConfig(
            lotlUrl = "https://invalid.example.com/nonexistent-lotl.xml",
            validateSignatures = false
        )
        val httpClient = HttpClient(CIO)
        val service = EtsiTrustListService(config, httpClient)

        assertFalse(service.isHealthy())

        httpClient.close()
    }

    @Test
    fun `test getAllTrustedProviders returns empty after failed refresh`() = runTest {
        val config = TslConfig(
            lotlUrl = "https://invalid.example.com/nonexistent-lotl.xml",
            validateSignatures = false
        )
        val httpClient = HttpClient(CIO)
        val service = EtsiTrustListService(config, httpClient)

        // The refresh will fail because the URL is unreachable,
        // but getAllTrustedProviders should handle it gracefully
        val providers = service.getAllTrustedProviders()
        assertTrue(providers.isEmpty())

        // After a failed refresh, health should still be false
        assertFalse(service.isHealthy())

        httpClient.close()
    }

    @Test
    fun `test isHealthy remains false after failed refresh`() = runTest {
        val config = TslConfig(
            lotlUrl = "https://invalid.example.com/nonexistent-lotl.xml",
            validateSignatures = false
        )
        val httpClient = HttpClient(CIO)
        val service = EtsiTrustListService(config, httpClient)

        // Manually call refresh which should fail gracefully
        service.refresh()

        assertFalse(service.isHealthy())

        httpClient.close()
    }

    @Test
    fun `test service created with custom member states config`() {
        val config = TslConfig(
            lotlUrl = "https://invalid.example.com/lotl.xml",
            memberStates = listOf("DE", "FR", "AT"),
            validateSignatures = false
        )
        val httpClient = HttpClient(CIO)
        val service = EtsiTrustListService(config, httpClient)

        // Service should be constructable with custom config
        assertFalse(service.isHealthy())

        httpClient.close()
    }

    @Test
    fun `test service implements EtsiTrustListProvider interface`() {
        val config = TslConfig(
            lotlUrl = "https://invalid.example.com/lotl.xml",
            validateSignatures = false
        )
        val httpClient = HttpClient(CIO)
        val service = EtsiTrustListService(config, httpClient)

        // Verify the service is an instance of the provider interface
        assertTrue(service is EtsiTrustListProvider)

        httpClient.close()
    }

    @Test
    fun `test multiple refresh calls are idempotent when failing`() = runTest {
        val config = TslConfig(
            lotlUrl = "https://invalid.example.com/nonexistent-lotl.xml",
            validateSignatures = false
        )
        val httpClient = HttpClient(CIO)
        val service = EtsiTrustListService(config, httpClient)

        // Multiple refreshes should all fail gracefully
        service.refresh()
        service.refresh()
        service.refresh()

        assertFalse(service.isHealthy())
        val providers = service.getAllTrustedProviders()
        assertTrue(providers.isEmpty())

        httpClient.close()
    }

    @Test
    fun `test getCachedLotl returns null before refresh`() {
        val config = TslConfig(
            lotlUrl = "https://invalid.example.com/nonexistent-lotl.xml",
            validateSignatures = false
        )
        val httpClient = HttpClient(CIO)
        val service = EtsiTrustListService(config, httpClient)

        assertNull(service.getCachedLotl())

        httpClient.close()
    }

    @Test
    fun `test getCachedMemberStateTls returns empty map before refresh`() {
        val config = TslConfig(
            lotlUrl = "https://invalid.example.com/nonexistent-lotl.xml",
            validateSignatures = false
        )
        val httpClient = HttpClient(CIO)
        val service = EtsiTrustListService(config, httpClient)

        assertTrue(service.getCachedMemberStateTls().isEmpty())

        httpClient.close()
    }

    @Test
    fun `test getCachedMemberStateTl returns null for unknown country`() {
        val config = TslConfig(
            lotlUrl = "https://invalid.example.com/nonexistent-lotl.xml",
            validateSignatures = false
        )
        val httpClient = HttpClient(CIO)
        val service = EtsiTrustListService(config, httpClient)

        assertNull(service.getCachedMemberStateTl("XX"))

        httpClient.close()
    }

    // -- Custom TSL tests --

    companion object {
        val SAMPLE_TSL_XML = """
            <?xml version="1.0" encoding="UTF-8"?>
            <TrustServiceStatusList xmlns="http://uri.etsi.org/02231/v2#">
                <SchemeInformation>
                    <TSLSequenceNumber>1</TSLSequenceNumber>
                    <SchemeOperatorName><Name xml:lang="en">Test Authority</Name></SchemeOperatorName>
                    <SchemeTerritory>XX</SchemeTerritory>
                    <ListIssueDateTime>2026-01-01T00:00:00Z</ListIssueDateTime>
                </SchemeInformation>
                <TrustServiceProviderList>
                    <TrustServiceProvider>
                        <TSPInformation>
                            <TSPName><Name xml:lang="en">Test Provider</Name></TSPName>
                        </TSPInformation>
                        <TSPServices>
                            <TSPService>
                                <ServiceInformation>
                                    <ServiceTypeIdentifier>http://uri.etsi.org/TrstSvc/Svctype/CA/QC</ServiceTypeIdentifier>
                                    <ServiceName><Name xml:lang="en">Test Service</Name></ServiceName>
                                    <ServiceDigitalIdentity>
                                        <DigitalId><X509SubjectName>CN=test.example.com</X509SubjectName></DigitalId>
                                    </ServiceDigitalIdentity>
                                    <ServiceStatus>http://uri.etsi.org/TrstSvc/TrustedList/Svcstatus/granted</ServiceStatus>
                                    <StatusStartingTime>2026-01-01T00:00:00Z</StatusStartingTime>
                                </ServiceInformation>
                            </TSPService>
                        </TSPServices>
                    </TrustServiceProvider>
                </TrustServiceProviderList>
            </TrustServiceStatusList>
        """.trimIndent()

        val SAMPLE_LOTL_XML = """
            <?xml version="1.0" encoding="UTF-8"?>
            <TrustServiceStatusList xmlns="http://uri.etsi.org/02231/v2#">
                <SchemeInformation>
                    <TSLSequenceNumber>1</TSLSequenceNumber>
                    <SchemeOperatorName><Name xml:lang="en">EU LOTL</Name></SchemeOperatorName>
                    <SchemeTerritory>EU</SchemeTerritory>
                    <ListIssueDateTime>2026-01-01T00:00:00Z</ListIssueDateTime>
                </SchemeInformation>
            </TrustServiceStatusList>
        """.trimIndent()

        fun mockHttpClient(customTslUrl: String): HttpClient {
            return HttpClient(MockEngine) {
                engine {
                    addHandler { request ->
                        when (request.url.toString()) {
                            "https://lotl.example.com/lotl.xml" -> respond(
                                content = ByteReadChannel(SAMPLE_LOTL_XML),
                                status = HttpStatusCode.OK,
                                headers = headersOf(HttpHeaders.ContentType, "application/xml")
                            )
                            customTslUrl -> respond(
                                content = ByteReadChannel(SAMPLE_TSL_XML),
                                status = HttpStatusCode.OK,
                                headers = headersOf(HttpHeaders.ContentType, "application/xml")
                            )
                            else -> respondError(HttpStatusCode.NotFound)
                        }
                    }
                }
            }
        }
    }

    @Test
    fun `test additionalTslUrls loaded during refresh`() = runTest {
        val customUrl = "https://custom.example.com/tsl.xml"
        val config = TslConfig(
            lotlUrl = "https://lotl.example.com/lotl.xml",
            validateSignatures = false,
            additionalTslUrls = mapOf("XX" to customUrl)
        )
        val httpClient = mockHttpClient(customUrl)
        val service = EtsiTrustListService(config, httpClient)

        service.refresh()

        assertTrue(service.isHealthy())
        val providers = service.getAllTrustedProviders()
        assertEquals(1, providers.size)
        assertEquals("XX", providers[0].country)
        assertEquals("Test Provider", providers[0].name)

        val tsl = service.getCachedMemberStateTl("XX")
        assertNotNull(tsl)
        assertEquals("XX", tsl.schemeTerritory)

        httpClient.close()
    }

    @Test
    fun `test addCustomTsl adds providers at runtime`() = runTest {
        val customUrl = "https://custom.example.com/tsl.xml"
        val config = TslConfig(
            lotlUrl = "https://lotl.example.com/lotl.xml",
            validateSignatures = false
        )
        val httpClient = mockHttpClient(customUrl)
        val service = EtsiTrustListService(config, httpClient)

        // Initial refresh with no custom TSLs
        service.refresh()
        assertTrue(service.isHealthy())
        assertTrue(service.getAllTrustedProviders().isEmpty())

        // Add custom TSL at runtime
        val tsl = service.addCustomTsl("XX", customUrl)
        assertEquals("XX", tsl.schemeTerritory)
        assertEquals(1, tsl.trustServiceProviders.size)

        // Verify providers include the custom country
        val providers = service.getAllTrustedProviders()
        assertEquals(1, providers.size)
        assertEquals("XX", providers[0].country)

        // Verify custom TSL URLs are tracked
        val urls = service.getCustomTslUrls()
        assertEquals(mapOf("XX" to customUrl), urls)

        httpClient.close()
    }

    @Test
    fun `test removeCustomTsl removes providers`() = runTest {
        val customUrl = "https://custom.example.com/tsl.xml"
        val config = TslConfig(
            lotlUrl = "https://lotl.example.com/lotl.xml",
            validateSignatures = false,
            additionalTslUrls = mapOf("XX" to customUrl)
        )
        val httpClient = mockHttpClient(customUrl)
        val service = EtsiTrustListService(config, httpClient)

        service.refresh()
        assertEquals(1, service.getAllTrustedProviders().size)

        // Remove the custom TSL
        val removed = service.removeCustomTsl("XX")
        assertTrue(removed)
        assertTrue(service.getAllTrustedProviders().isEmpty())
        assertNull(service.getCachedMemberStateTl("XX"))
        assertTrue(service.getCustomTslUrls().isEmpty())

        // Removing again returns false
        assertFalse(service.removeCustomTsl("XX"))

        httpClient.close()
    }

    @Test
    fun `test getCustomTslUrls returns configured and runtime URLs`() = runTest {
        val customUrl = "https://custom.example.com/tsl.xml"
        val config = TslConfig(
            lotlUrl = "https://lotl.example.com/lotl.xml",
            validateSignatures = false,
            additionalTslUrls = mapOf("XX" to customUrl)
        )
        val httpClient = mockHttpClient(customUrl)
        val service = EtsiTrustListService(config, httpClient)

        val urls = service.getCustomTslUrls()
        assertEquals(1, urls.size)
        assertEquals(customUrl, urls["XX"])

        httpClient.close()
    }
}
