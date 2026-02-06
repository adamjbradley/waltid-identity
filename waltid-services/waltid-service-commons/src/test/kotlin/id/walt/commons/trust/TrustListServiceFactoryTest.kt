package id.walt.commons.trust

import id.walt.commons.config.ConfigManager
import id.walt.commons.config.TrustListConfig
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame

class TrustListServiceFactoryTest {

    @BeforeEach
    fun setUp() {
        TrustListServiceFactory.reset()
        ConfigManager.preclear()
    }

    @AfterEach
    fun tearDown() {
        TrustListServiceFactory.reset()
        ConfigManager.preclear()
    }

    @Test
    fun `getServiceOrNull returns null when config not loaded`() {
        val service = TrustListServiceFactory.getServiceOrNull()

        assertNull(service, "Should return null when TrustListConfig is not registered in ConfigManager")
    }

    @Test
    fun `getServiceOrNull returns null when config disabled`() {
        ConfigManager.preloadAndRegisterConfig("trust-list", TrustListConfig(enabled = false))
        ConfigManager.loadConfigs()

        val service = TrustListServiceFactory.getServiceOrNull()

        assertNull(service, "Should return null when trust list feature is disabled")
    }

    @Test
    fun `getServiceOrNull returns service when config enabled`() {
        ConfigManager.preloadAndRegisterConfig("trust-list", TrustListConfig(enabled = true))
        ConfigManager.loadConfigs()

        val service = TrustListServiceFactory.getServiceOrNull()

        assertNotNull(service, "Should return a TrustService when config is enabled")
    }

    @Test
    fun `getServiceOrNull returns cached instance on subsequent calls`() {
        ConfigManager.preloadAndRegisterConfig("trust-list", TrustListConfig(enabled = true))
        ConfigManager.loadConfigs()

        val first = TrustListServiceFactory.getServiceOrNull()
        val second = TrustListServiceFactory.getServiceOrNull()

        assertNotNull(first)
        assertNotNull(second)
        assertSame(first, second, "Subsequent calls should return the same cached instance")
    }

    @Test
    fun `reset clears cache so next call re-evaluates`() {
        ConfigManager.preloadAndRegisterConfig("trust-list", TrustListConfig(enabled = true))
        ConfigManager.loadConfigs()

        val first = TrustListServiceFactory.getServiceOrNull()
        assertNotNull(first)

        TrustListServiceFactory.reset()

        val second = TrustListServiceFactory.getServiceOrNull()
        assertNotNull(second)

        assert(first !== second) { "After reset, a new instance should be created" }
    }

    @Test
    fun `reset does not throw when no cached service exists`() {
        TrustListServiceFactory.reset()
        TrustListServiceFactory.reset()

        assertNull(TrustListServiceFactory.getServiceOrNull(), "Should still return null after double reset with no config")
    }
}
