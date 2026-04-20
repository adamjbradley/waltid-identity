@file:OptIn(ExperimentalTime::class)

package id.walt.authop.store

import com.github.benmanes.caffeine.cache.Caffeine
import com.github.benmanes.caffeine.cache.Ticker
import kotlin.time.Duration
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlin.time.toJavaDuration

/**
 * One in-flight upstream-OIDC kick-off, keyed in the store by the random
 * `state` we mint and send to the upstream in the authorize URL.
 *
 * The upstream echoes `state` back on the `/callback/oidc` leg; we use it as
 * the store key to rehydrate everything we need to finish the dance:
 *  - [authRequestId] — binds this kickoff to the pending [id.walt.authop.domain.AuthRequest]
 *    (and, via the `sid` cookie, to the user agent we started the flow on).
 *  - [realmId] — looked up in [id.walt.authop.config.RealmRegistry] to fetch
 *    realm-specific config (claim mapping, scopes).
 *  - [upstreamNonce] — passed to
 *    [id.walt.authop.upstream.OidcClient.exchangeCode] as `expectedNonce` so
 *    the upstream ID token's `nonce` claim is verified (OIDC Core §3.1.3.7 #11).
 *  - [issuer] / [clientId] / [clientSecret] — rehydrate discovery + auth the
 *    code-exchange call. These are read from the realm config at kickoff and
 *    stashed here so that later reads go through the same state-bound value
 *    even if the realm registry was reloaded mid-flight.
 *  - [createdAt] — debugging / metrics. The cache TTL handles expiry.
 *
 * **Secrets:** [clientSecret] is stored in-memory only and never logged (we
 * never log [UpstreamFlow] values; callers should follow suit).
 */
data class UpstreamFlow(
    val authRequestId: String,
    val realmId: String,
    val upstreamNonce: String,
    val issuer: String,
    val clientId: String,
    val clientSecret: String,
    val createdAt: Instant,
)

/**
 * Single-use store for in-flight upstream-OIDC state.
 *
 * The `state` parameter we mint is the key. [consume] is atomic: the entry
 * is returned AND removed in a single operation, preventing state-replay
 * attacks where an attacker who obtained a valid state could trigger the
 * callback twice. A resubmit of a callback URL lands on the null-return
 * branch and is rejected by the handler with a 400.
 */
interface UpstreamFlowStore {
    fun put(state: String, flow: UpstreamFlow)
    fun consume(state: String): UpstreamFlow?
}

/**
 * Caffeine-backed in-memory implementation. TTL is `expireAfterWrite` — once
 * we've minted state at kickoff, the user has [ttl] to complete the upstream
 * flow and hit `/callback/oidc`. 10 min is the default from the plan and
 * matches the AuthRequest TTL (hitting /callback/oidc after AuthRequest
 * expiry would 400 anyway, so a longer upstream TTL buys us nothing).
 */
class InMemoryUpstreamFlowStore(
    ttl: Duration,
    ticker: Ticker = Ticker.systemTicker(),
) : UpstreamFlowStore {
    private val cache = Caffeine.newBuilder()
        .ticker(ticker)
        .expireAfterWrite(ttl.toJavaDuration())
        .build<String, UpstreamFlow>()

    override fun put(state: String, flow: UpstreamFlow) {
        cache.put(state, flow)
    }

    /**
     * Atomic get-and-remove. `asMap().remove(key)` returns the prior value
     * and evicts the entry — concurrent calls for the same state cannot both
     * see the value.
     */
    override fun consume(state: String): UpstreamFlow? =
        cache.asMap().remove(state)
}
