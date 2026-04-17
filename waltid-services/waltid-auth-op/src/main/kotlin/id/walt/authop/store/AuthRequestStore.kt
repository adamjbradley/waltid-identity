package id.walt.authop.store

import com.github.benmanes.caffeine.cache.Caffeine
import com.github.benmanes.caffeine.cache.Ticker
import id.walt.authop.domain.AuthRequest
import kotlin.time.Duration
import kotlin.time.toJavaDuration

/**
 * Store for in-flight OIDC authorization requests.
 *
 * Entries mutate as the user proceeds: realm choice is written after `/choose_realm`,
 * subject + claims after login completes. Rather than expose raw mutability on the
 * [AuthRequest] value, the store offers [update] for copy-and-replace transforms —
 * this keeps values immutable everywhere else and avoids racing with concurrent
 * mutations on a Caffeine-cached instance.
 */
interface AuthRequestStore {
    fun put(id: String, value: AuthRequest)
    fun get(id: String): AuthRequest?

    /**
     * Atomically applies [transform] to the current value.
     *
     * Returns the new value, or null if the entry is absent (or expired).
     * If [transform] itself throws, the entry is left unchanged.
     */
    fun update(id: String, transform: (AuthRequest) -> AuthRequest): AuthRequest?

    fun remove(id: String)
}

class InMemoryAuthRequestStore(
    ttl: Duration,
    ticker: Ticker = Ticker.systemTicker()
) : AuthRequestStore {
    private val cache = Caffeine.newBuilder()
        .ticker(ticker)
        .expireAfterWrite(ttl.toJavaDuration())
        .build<String, AuthRequest>()

    override fun put(id: String, value: AuthRequest) {
        cache.put(id, value)
    }

    override fun get(id: String): AuthRequest? = cache.getIfPresent(id)

    override fun update(id: String, transform: (AuthRequest) -> AuthRequest): AuthRequest? {
        // ConcurrentMap#compute is atomic in Caffeine's backing map, so concurrent
        // updates don't lose writes. Returning null from the remapping function would
        // delete the entry; we only want to skip when the entry is already absent.
        return cache.asMap().compute(id) { _, existing ->
            if (existing == null) null else transform(existing)
        }
    }

    override fun remove(id: String) {
        cache.invalidate(id)
    }
}
