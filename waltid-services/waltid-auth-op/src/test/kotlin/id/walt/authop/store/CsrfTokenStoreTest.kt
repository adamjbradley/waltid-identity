package id.walt.authop.store

import com.github.benmanes.caffeine.cache.Ticker
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Focused tests for [InMemoryCsrfTokenStore].
 *
 * Coverage mirrors the [AuthCodeStore] contract: unique token per issue, match
 * + consume, TTL expiry via injected [Ticker]. The consent endpoint tests
 * cover the HTTP-level wiring — these only assert the store's own invariants.
 */
private class CsrfTestTicker : Ticker {
    private var offset: Long = 0L
    override fun read(): Long = offset
    fun advance(d: Duration) {
        offset += d.inWholeNanoseconds
    }
}

class CsrfTokenStoreTest {

    @Test
    fun `issue returns unique tokens`() {
        val store = InMemoryCsrfTokenStore(ttl = 60.seconds, ticker = CsrfTestTicker())
        // Issue across many sids and confirm the values are distinct.
        val tokens = (0 until 16).map { i -> store.issue("sid-$i") }
        assertEquals(tokens.toSet().size, tokens.size, "tokens must be unique; got: $tokens")
        // And even re-issuing on the same sid yields a fresh token (the
        // previous one is overwritten — the store's own generator is
        // seeded with SecureRandom so collisions are negligibly unlikely).
        val a = store.issue("sid-x")
        val b = store.issue("sid-x")
        assertNotEquals(a, b, "re-issue on same sid must return a fresh token")
    }

    @Test
    fun `validate matches then invalidates`() {
        val store = InMemoryCsrfTokenStore(ttl = 60.seconds, ticker = CsrfTestTicker())
        val token = store.issue("sid-1")
        assertTrue(store.validate("sid-1", token), "first validate with correct token must succeed")
        // Single-use: the token is consumed on the successful match.
        assertFalse(store.validate("sid-1", token), "second validate must fail (token consumed)")
    }

    @Test
    fun `validate after TTL returns false`() {
        val ticker = CsrfTestTicker()
        val store = InMemoryCsrfTokenStore(ttl = 60.seconds, ticker = ticker)
        val token = store.issue("sid-1")
        ticker.advance(61.seconds)
        assertFalse(store.validate("sid-1", token), "expired token must not validate")
    }

    @Test
    fun `validate with unknown sid returns false`() {
        val store = InMemoryCsrfTokenStore(ttl = 60.seconds, ticker = CsrfTestTicker())
        // No issue() call for this sid — nothing on record.
        assertFalse(store.validate("never-seen", "some-token"))
    }
}
