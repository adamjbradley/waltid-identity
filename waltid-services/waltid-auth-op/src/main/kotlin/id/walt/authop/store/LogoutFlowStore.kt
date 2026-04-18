package id.walt.authop.store

import com.github.benmanes.caffeine.cache.Caffeine
import com.github.benmanes.caffeine.cache.Ticker
import kotlin.time.Duration
import kotlin.time.toJavaDuration

/**
 * One in-flight RP-initiated-logout kick-off, keyed in the store by the random
 * `state` nonce we mint and forward to the upstream OP as `state=`.
 *
 * An OIDC-realm logout cannot complete in a single hop: we need to bounce the
 * user agent through the upstream's `end_session_endpoint` (to invalidate the
 * upstream session), then catch the return leg, clear our own `sid` cookie, and
 * finally redirect the browser to the RP's `post_logout_redirect_uri`. This
 * record is what we rehydrate on the return leg.
 *
 *  - [sid] — the session cookie value to clear (and the key under which the
 *    [SessionStore] entry sits). Bound here so the return leg is idempotent:
 *    the cookie we invalidate is the one the user started with.
 *  - [postLogoutRedirectUri] — the **already-validated** RP redirect URI. We
 *    validate on entry (matches `client.postLogoutRedirectUris`, wildcard-aware)
 *    and stash the raw value here so the return leg just echoes it; a stored
 *    URL is never re-validated.
 *  - [rpState] — optional `state` parameter the RP forwarded on `/end_session`.
 *    When present, echoed back on the final redirect so the RP can round-trip
 *    CSRF state. Null when the RP didn't send one.
 *
 * No secrets (no id_token, no client_secret) live on this record — those stay
 * on [Session] / [id.walt.authop.config.OidcRealmConfig].
 */
data class LogoutFlow(
    val sid: String,
    val postLogoutRedirectUri: String,
    val rpState: String?,
)

/**
 * Single-use store for in-flight RP-initiated-logout state, keyed by the
 * upstream-facing `state` nonce.
 *
 * [consume] is atomic: the entry is returned AND removed in a single
 * operation, preventing state-replay on the `/end_session/upstream_return`
 * leg. A replayed return URL lands on the null branch and is rejected by
 * the handler with a plain 400.
 *
 * Mirrors [UpstreamFlowStore]'s shape by design — logout and upstream-login
 * are symmetric flows.
 */
interface LogoutFlowStore {
    fun put(state: String, flow: LogoutFlow)
    fun consume(state: String): LogoutFlow?
}

/**
 * Caffeine-backed in-memory implementation. TTL is `expireAfterWrite` — once
 * we mint state at `/end_session`, the user has [ttl] to complete the upstream
 * end-session flow and hit `/end_session/upstream_return`. 5 min is the plan
 * default: RP-initiated logout is a single redirect away from completing, so
 * a short TTL is safe and bounds the at-rest state we hold.
 */
class InMemoryLogoutFlowStore(
    ttl: Duration,
    ticker: Ticker = Ticker.systemTicker(),
) : LogoutFlowStore {
    private val cache = Caffeine.newBuilder()
        .ticker(ticker)
        .expireAfterWrite(ttl.toJavaDuration())
        .build<String, LogoutFlow>()

    override fun put(state: String, flow: LogoutFlow) {
        cache.put(state, flow)
    }

    /**
     * Atomic get-and-remove. `asMap().remove(key)` returns the prior value
     * and evicts the entry — concurrent calls for the same state cannot both
     * see the value.
     */
    override fun consume(state: String): LogoutFlow? =
        cache.asMap().remove(state)
}
