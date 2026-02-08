package id.walt.commons.trust

import id.walt.commons.config.ConfigManager
import id.walt.commons.config.TrustListConfig
import id.walt.trust.TrustService
import io.klogging.noCoLogger

private val log = noCoLogger("TrustListServiceFactory")

object TrustListServiceFactory {
    private var cached: TrustService? = null

    fun getServiceOrNull(): TrustService? {
        cached?.let { return it }
        return try {
            val config = ConfigManager.getConfig<TrustListConfig>()
            if (!config.enabled) {
                log.debug { "Trust lists feature is not enabled in config" }
                return null
            }
            CompositeTrustService(config).also { cached = it }
        } catch (e: Exception) {
            log.warn { "TrustService not available: ${e.message}" }
            null
        }
    }

    fun reset() {
        cached = null
    }
}
