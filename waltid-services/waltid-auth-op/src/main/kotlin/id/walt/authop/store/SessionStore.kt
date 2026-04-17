package id.walt.authop.store

import com.github.benmanes.caffeine.cache.Caffeine
import com.github.benmanes.caffeine.cache.Ticker
import id.walt.authop.domain.Session
import kotlin.time.Duration
import kotlin.time.toJavaDuration

/**
 * Store for logged-in user sessions, keyed by the `sid` cookie value.
 *
 * No [update] method — sessions are never modified in place; logout calls [remove].
 * If session extension is ever added, prefer writing a new [Session] with a new
 * `sessionId` and rotating the cookie (avoids session-fixation traps).
 */
interface SessionStore {
    fun put(id: String, value: Session)
    fun get(id: String): Session?
    fun remove(id: String)
}

class InMemorySessionStore(
    ttl: Duration,
    ticker: Ticker = Ticker.systemTicker()
) : SessionStore {
    private val cache = Caffeine.newBuilder()
        .ticker(ticker)
        .expireAfterWrite(ttl.toJavaDuration())
        .build<String, Session>()

    override fun put(id: String, value: Session) {
        cache.put(id, value)
    }

    override fun get(id: String): Session? = cache.getIfPresent(id)

    override fun remove(id: String) {
        cache.invalidate(id)
    }
}
