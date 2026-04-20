package id.walt.authop.store

import com.github.benmanes.caffeine.cache.Caffeine
import com.github.benmanes.caffeine.cache.Ticker
import java.security.SecureRandom
import java.util.Base64
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.toJavaDuration

/**
 * Store for per-`sid` CSRF tokens guarding the `/consent` POST.
 *
 * The consent page embeds a hidden `csrf_token` field produced by [issue]; the
 * subsequent POST presents that value and the server calls [validate]. The
 * contract is deliberately single-use — [validate] consumes the token so a
 * replayed form submission fails. This mirrors the single-use semantics of
 * [AuthCodeStore.consume]: one-time capabilities live at most one exchange.
 *
 * The interface exists for the same reason as the other stores — a Valkey
 * backing can drop in later without changing the consent endpoint. (For Valkey:
 * `GETDEL` again gives single-use + TTL in one round trip.)
 */
interface CsrfTokenStore {
    /** Mint a fresh token bound to [sid] and persist it until TTL or [validate]. */
    fun issue(sid: String): String

    /**
     * Returns true iff a token was stored for [sid] and equals [token].
     * On a successful match the token is consumed (single-use); subsequent
     * [validate] calls for the same [sid] return false.
     */
    fun validate(sid: String, token: String): Boolean
}

/**
 * In-memory Caffeine-backed [CsrfTokenStore]. TTL defaults to 10 minutes —
 * long enough to survive a user reading the consent page at human pace, short
 * enough that an abandoned session doesn't leave a live token dangling.
 *
 * Token generation is [SecureRandom] → 32 bytes → Base64URL (no padding),
 * giving 256 bits of entropy. That's overkill for a form-submission CSRF
 * defence but matches the `authRequestId` generator for consistency.
 */
class InMemoryCsrfTokenStore(
    ttl: Duration = 10.minutes,
    ticker: Ticker = Ticker.systemTicker(),
) : CsrfTokenStore {

    private val cache = Caffeine.newBuilder()
        .ticker(ticker)
        .expireAfterWrite(ttl.toJavaDuration())
        .build<String, String>()

    private val secureRandom = SecureRandom()

    override fun issue(sid: String): String {
        val bytes = ByteArray(32)
        secureRandom.nextBytes(bytes)
        val token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        cache.put(sid, token)
        return token
    }

    override fun validate(sid: String, token: String): Boolean {
        // asMap().remove is atomic w.r.t. concurrent mutators; two validate
        // calls cannot both observe the same token as valid.
        val existing = cache.asMap().remove(sid) ?: return false
        return existing == token
    }
}
