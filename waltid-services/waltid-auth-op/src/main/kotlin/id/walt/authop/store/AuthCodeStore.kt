package id.walt.authop.store

import com.github.benmanes.caffeine.cache.Caffeine
import com.github.benmanes.caffeine.cache.Ticker
import id.walt.authop.domain.AuthCode
import kotlin.time.Duration
import kotlin.time.toJavaDuration

/**
 * Store for single-use, short-lived OAuth2 authorization codes.
 *
 * The defining contract is [consume]: a code is valid at most once. Re-reads
 * must return null. This mirrors RFC 6749 section 4.1.2 — authorization codes
 * MUST be short-lived and single-use.
 *
 * The interface exists so a Valkey-backed implementation can drop in later
 * without touching the token endpoint. (For Valkey: `GETDEL` gives single-use
 * + expiration semantics in one round trip.)
 */
interface AuthCodeStore {
    fun put(code: String, value: AuthCode)

    /** Returns the value and atomically removes it (single-use). Returns null if absent or expired. */
    fun consume(code: String): AuthCode?
}

/**
 * In-memory Caffeine-backed [AuthCodeStore]. TTL is enforced by Caffeine via
 * `expireAfterWrite`; the [ticker] parameter exists so tests can pass a
 * [com.github.benmanes.caffeine.cache.Ticker] that advances with a test clock.
 */
class InMemoryAuthCodeStore(
    ttl: Duration,
    ticker: Ticker = Ticker.systemTicker()
) : AuthCodeStore {
    private val cache = Caffeine.newBuilder()
        .ticker(ticker)
        .expireAfterWrite(ttl.toJavaDuration())
        .build<String, AuthCode>()

    override fun put(code: String, value: AuthCode) {
        cache.put(code, value)
    }

    override fun consume(code: String): AuthCode? {
        // asMap().remove is atomic w.r.t. other cache mutators.
        return cache.asMap().remove(code)
    }
}
