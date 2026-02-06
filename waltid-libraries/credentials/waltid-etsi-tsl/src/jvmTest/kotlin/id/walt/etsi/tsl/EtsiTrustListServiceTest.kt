package id.walt.etsi.tsl

import id.walt.etsi.tsl.config.TslConfig
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

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
}
