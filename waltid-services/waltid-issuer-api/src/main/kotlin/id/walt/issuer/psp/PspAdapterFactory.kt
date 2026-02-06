package id.walt.issuer.psp

import id.walt.commons.config.ConfigManager
import io.github.oshai.kotlinlogging.KotlinLogging

/**
 * Factory for creating PSP adapter instances based on configuration.
 */
object PspAdapterFactory {

    private val log = KotlinLogging.logger { }

    private var cachedAdapter: PspAdapter? = null

    /**
     * Get the configured PSP adapter instance.
     *
     * Returns a cached instance if available, or creates a new one based on config.
     * The adapter type is determined by the pspAdapterType config value:
     * - "mock": Returns MockPspAdapter for testing
     * - Fully qualified class name: Attempts to instantiate the specified class
     */
    fun getAdapter(): PspAdapter {
        cachedAdapter?.let { return it }

        val config = try {
            ConfigManager.getConfig<PwaConfig>()
        } catch (e: Exception) {
            log.warn { "PWA config not available, using MockPspAdapter: ${e.message}" }
            return MockPspAdapter().also { cachedAdapter = it }
        }

        val adapter = when (config.pspAdapterType.lowercase()) {
            "mock" -> {
                log.info { "Using MockPspAdapter for PWA" }
                MockPspAdapter()
            }
            else -> {
                log.info { "Attempting to load PSP adapter: ${config.pspAdapterType}" }
                try {
                    val clazz = Class.forName(config.pspAdapterType)
                    val instance = clazz.getDeclaredConstructor().newInstance()
                    if (instance is PspAdapter) {
                        instance
                    } else {
                        log.error { "Class ${config.pspAdapterType} does not implement PspAdapter, falling back to MockPspAdapter" }
                        MockPspAdapter()
                    }
                } catch (e: Exception) {
                    log.error(e) { "Failed to instantiate PSP adapter ${config.pspAdapterType}, falling back to MockPspAdapter" }
                    MockPspAdapter()
                }
            }
        }

        cachedAdapter = adapter
        return adapter
    }

    /**
     * Clear the cached adapter. Useful for testing.
     */
    fun clearCache() {
        cachedAdapter = null
    }
}
