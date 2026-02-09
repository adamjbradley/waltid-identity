package id.walt.etsi.tsl

import id.walt.etsi.tsl.config.TslConfig
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import io.ktor.utils.io.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path
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

    // -- Disk cache tests --

    @Test
    fun `test TslDiskCache serialization round-trip`() {
        val tsl = TslParser.parse(SAMPLE_TSL_XML)
        val cache = TslDiskCache(
            savedAt = "2026-01-01T00:00:00Z",
            lotl = null,
            memberStateTls = mapOf("XX" to tsl),
            customTslUrls = mapOf("XX" to "https://example.com/tsl.xml")
        )

        val json = Json { ignoreUnknownKeys = true }
        val serialized = json.encodeToString(TslDiskCache.serializer(), cache)
        val deserialized = json.decodeFromString<TslDiskCache>(serialized)

        assertEquals(cache.savedAt, deserialized.savedAt)
        assertNull(deserialized.lotl)
        assertEquals(1, deserialized.memberStateTls.size)
        assertEquals("XX", deserialized.memberStateTls["XX"]?.schemeTerritory)
        assertEquals(1, deserialized.memberStateTls["XX"]?.trustServiceProviders?.size)
        assertEquals("XX", deserialized.customTslUrls.keys.first())
    }

    @Test
    fun `test saveToDiskCache creates cache file after refresh`(@TempDir tempDir: Path) = runTest {
        val cacheDir = tempDir.resolve("tsl-cache").toString()
        val customUrl = "https://custom.example.com/tsl.xml"
        val config = TslConfig(
            lotlUrl = "https://lotl.example.com/lotl.xml",
            validateSignatures = false,
            additionalTslUrls = mapOf("XX" to customUrl),
            cacheDir = cacheDir
        )
        val httpClient = mockHttpClient(customUrl)
        val service = EtsiTrustListService(config, httpClient)

        service.refresh()

        val cacheFile = File(cacheDir, "tsl-cache.json")
        assertTrue(cacheFile.exists(), "Cache file should be created after refresh")
        assertTrue(cacheFile.length() > 0, "Cache file should not be empty")

        // Verify the cache content is valid JSON
        val json = Json { ignoreUnknownKeys = true }
        val cache = json.decodeFromString<TslDiskCache>(cacheFile.readText())
        assertNotNull(cache.savedAt)
        assertEquals(1, cache.memberStateTls.size)
        assertTrue(cache.memberStateTls.containsKey("XX"))

        httpClient.close()
    }

    @Test
    fun `test loadFromDiskCache restores state`(@TempDir tempDir: Path) = runTest {
        val cacheDir = tempDir.resolve("tsl-cache").toString()
        val customUrl = "https://custom.example.com/tsl.xml"
        val httpClient = mockHttpClient(customUrl)

        // Service 1: refresh to populate and save cache
        val config1 = TslConfig(
            lotlUrl = "https://lotl.example.com/lotl.xml",
            validateSignatures = false,
            additionalTslUrls = mapOf("XX" to customUrl),
            cacheDir = cacheDir
        )
        val service1 = EtsiTrustListService(config1, httpClient)
        service1.refresh()
        assertTrue(service1.isHealthy())
        assertEquals(1, service1.getAllTrustedProviders().size)

        // Service 2: load from disk cache (no network)
        val config2 = TslConfig(
            lotlUrl = "https://invalid.example.com/should-not-be-called.xml",
            validateSignatures = false,
            cacheDir = cacheDir
        )
        val httpClient2 = HttpClient(CIO)
        val service2 = EtsiTrustListService(config2, httpClient2)

        assertFalse(service2.isHealthy())
        service2.loadFromDiskCache()

        assertTrue(service2.isHealthy())
        val providers = service2.getAllTrustedProviders()
        assertEquals(1, providers.size)
        assertEquals("Test Provider", providers[0].name)
        assertNotNull(service2.getCachedMemberStateTl("XX"))
        assertEquals(mapOf("XX" to customUrl), service2.getCustomTslUrls())

        httpClient.close()
        httpClient2.close()
    }

    @Test
    fun `test loadFromDiskCache handles missing file gracefully`(@TempDir tempDir: Path) {
        val cacheDir = tempDir.resolve("nonexistent").toString()
        val config = TslConfig(
            lotlUrl = "https://invalid.example.com/lotl.xml",
            validateSignatures = false,
            cacheDir = cacheDir
        )
        val httpClient = HttpClient(CIO)
        val service = EtsiTrustListService(config, httpClient)

        // Should not throw, should remain unhealthy
        service.loadFromDiskCache()
        assertFalse(service.isHealthy())
        assertNull(service.getCachedLotl())

        httpClient.close()
    }

    @Test
    fun `test loadFromDiskCache handles corrupt file gracefully`(@TempDir tempDir: Path) {
        val cacheDir = tempDir.resolve("tsl-cache").toString()
        File(cacheDir).mkdirs()
        File(cacheDir, "tsl-cache.json").writeText("{ invalid json {{}")

        val config = TslConfig(
            lotlUrl = "https://invalid.example.com/lotl.xml",
            validateSignatures = false,
            cacheDir = cacheDir
        )
        val httpClient = HttpClient(CIO)
        val service = EtsiTrustListService(config, httpClient)

        // Should not throw, should remain unhealthy
        service.loadFromDiskCache()
        assertFalse(service.isHealthy())

        httpClient.close()
    }

    @Test
    fun `test no disk cache when cacheDir is null`() = runTest {
        val customUrl = "https://custom.example.com/tsl.xml"
        val config = TslConfig(
            lotlUrl = "https://lotl.example.com/lotl.xml",
            validateSignatures = false,
            additionalTslUrls = mapOf("XX" to customUrl),
            cacheDir = null
        )
        val httpClient = mockHttpClient(customUrl)
        val service = EtsiTrustListService(config, httpClient)

        // refresh should work fine without caching
        service.refresh()
        assertTrue(service.isHealthy())
        assertEquals(1, service.getAllTrustedProviders().size)

        // loadFromDiskCache should be a no-op
        service.loadFromDiskCache()
        assertTrue(service.isHealthy())

        httpClient.close()
    }

    @Test
    fun `test addCustomTsl saves cache to disk`(@TempDir tempDir: Path) = runTest {
        val cacheDir = tempDir.resolve("tsl-cache").toString()
        val customUrl = "https://custom.example.com/tsl.xml"
        val config = TslConfig(
            lotlUrl = "https://lotl.example.com/lotl.xml",
            validateSignatures = false,
            cacheDir = cacheDir
        )
        val httpClient = mockHttpClient(customUrl)
        val service = EtsiTrustListService(config, httpClient)

        service.refresh()
        val cacheFile = File(cacheDir, "tsl-cache.json")
        assertTrue(cacheFile.exists())
        val sizeAfterRefresh = cacheFile.length()

        // Adding a custom TSL should update the cache file
        service.addCustomTsl("XX", customUrl)
        assertTrue(cacheFile.length() > sizeAfterRefresh, "Cache file should grow after adding custom TSL")

        // Verify custom TSL is in the cache
        val json = Json { ignoreUnknownKeys = true }
        val cache = json.decodeFromString<TslDiskCache>(cacheFile.readText())
        assertEquals(customUrl, cache.customTslUrls["XX"])
        assertTrue(cache.memberStateTls.containsKey("XX"))

        httpClient.close()
    }

    @Test
    fun `test removeCustomTsl saves cache to disk`(@TempDir tempDir: Path) = runTest {
        val cacheDir = tempDir.resolve("tsl-cache").toString()
        val customUrl = "https://custom.example.com/tsl.xml"
        val config = TslConfig(
            lotlUrl = "https://lotl.example.com/lotl.xml",
            validateSignatures = false,
            additionalTslUrls = mapOf("XX" to customUrl),
            cacheDir = cacheDir
        )
        val httpClient = mockHttpClient(customUrl)
        val service = EtsiTrustListService(config, httpClient)

        service.refresh()
        val cacheFile = File(cacheDir, "tsl-cache.json")
        assertTrue(cacheFile.exists())

        // Remove and check the cache is updated
        service.removeCustomTsl("XX")

        val json = Json { ignoreUnknownKeys = true }
        val cache = json.decodeFromString<TslDiskCache>(cacheFile.readText())
        assertFalse(cache.customTslUrls.containsKey("XX"))
        assertFalse(cache.memberStateTls.containsKey("XX"))

        httpClient.close()
    }
}
