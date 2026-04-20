package id.walt.authop.store

import com.github.benmanes.caffeine.cache.Caffeine
import com.github.benmanes.caffeine.cache.Ticker
import id.walt.authop.domain.VpSession
import kotlin.time.Duration
import kotlin.time.toJavaDuration

/**
 * Store for in-flight verifiable-presentation sessions proxied to verifier-api2.
 *
 * Unlike [AuthCodeStore] there is no `consume` — the `/complete` handler explicitly
 * [remove]s after attaching the captured credential to the auth request. That
 * keeps the webhook-processing path (which needs to [update] without removing)
 * distinct from the completion path.
 */
interface VpSessionStore {
    fun put(id: String, value: VpSession)
    fun get(id: String): VpSession?
    fun update(id: String, transform: (VpSession) -> VpSession): VpSession?
    fun remove(id: String)
}

class InMemoryVpSessionStore(
    ttl: Duration,
    ticker: Ticker = Ticker.systemTicker()
) : VpSessionStore {
    private val cache = Caffeine.newBuilder()
        .ticker(ticker)
        .expireAfterWrite(ttl.toJavaDuration())
        .build<String, VpSession>()

    override fun put(id: String, value: VpSession) {
        cache.put(id, value)
    }

    override fun get(id: String): VpSession? = cache.getIfPresent(id)

    override fun update(id: String, transform: (VpSession) -> VpSession): VpSession? {
        return cache.asMap().compute(id) { _, existing ->
            if (existing == null) null else transform(existing)
        }
    }

    override fun remove(id: String) {
        cache.invalidate(id)
    }
}
