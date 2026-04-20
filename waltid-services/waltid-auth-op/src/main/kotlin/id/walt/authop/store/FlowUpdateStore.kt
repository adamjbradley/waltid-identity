package id.walt.authop.store

import com.github.benmanes.caffeine.cache.Caffeine
import com.github.benmanes.caffeine.cache.Ticker
import id.walt.authop.domain.FlowStepUpdate
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.serialization.json.JsonElement
import java.util.UUID
import kotlin.time.Duration
import kotlin.time.toJavaDuration

/**
 * One live flow-update session. Owns the [MutableSharedFlow] that callback
 * POSTs emit into and SSE clients collect from. The replay buffer means a
 * browser that connects after the first callback still sees everything in
 * order — removes the race between kickoff and subscribe.
 *
 * @property sessionId the server-minted id, also the key in [FlowUpdateStore].
 * @property context optional pass-through JSON provided at kickoff (e.g. a
 *   realm id or RP session correlation id the UI wants to display).
 */
class FlowSession(
    val sessionId: String,
    val context: JsonElement? = null,
) {
    private val _updates = MutableSharedFlow<FlowStepUpdate>(
        replay = REPLAY_CAPACITY,
        extraBufferCapacity = 0,
    )
    val updates: SharedFlow<FlowStepUpdate> = _updates.asSharedFlow()

    /**
     * Non-suspending emit. Returns false if the replay buffer is full AND a
     * slow subscriber is blocking — but with `extraBufferCapacity = 0` and a
     * 16-slot replay, tryEmit can only fail if >16 events have not yet been
     * collected and replay slots are all occupied. For our workflow shape
     * (4 callbacks total) that will never happen, so we accept tryEmit's
     * simpler ergonomics.
     */
    fun tryEmit(update: FlowStepUpdate): Boolean = _updates.tryEmit(update)

    companion object {
        /**
         * Replay slots per session. Sized to comfortably hold a longer
         * workflow's history (e.g. 10 steps) so late-connecting browsers
         * still catch up on everything in order. Memory is tiny — each slot
         * is a small data class reference.
         */
        const val REPLAY_CAPACITY = 16
    }
}

/**
 * Store for in-flight flow-update sessions, keyed by server-minted UUID.
 *
 * No update semantics — [FlowSession.tryEmit] mutates the contained
 * [MutableSharedFlow] directly. The store only manages lifecycle
 * (create / get / expire).
 *
 * Sessions evict after [Duration] of inactivity (last write). Once evicted,
 * subsequent callbacks 404 and any still-connected SSE stream sees its
 * collect loop complete cleanly when the writer drops.
 */
interface FlowUpdateStore {
    /**
     * Create and register a new session with a freshly-minted UUID id.
     * Returns the [FlowSession] so the caller can forward the id to clients.
     */
    fun create(context: JsonElement? = null): FlowSession

    fun get(id: String): FlowSession?

    /**
     * Drop a session explicitly. Currently unused — sessions expire via the
     * cache TTL — but exposed for tests and a possible future
     * `POST /api/flow-cancel` endpoint.
     */
    fun remove(id: String)
}

class InMemoryFlowUpdateStore(
    ttl: Duration,
    ticker: Ticker = Ticker.systemTicker(),
) : FlowUpdateStore {
    private val cache = Caffeine.newBuilder()
        .ticker(ticker)
        .expireAfterWrite(ttl.toJavaDuration())
        .build<String, FlowSession>()

    override fun create(context: JsonElement?): FlowSession {
        val id = UUID.randomUUID().toString()
        val session = FlowSession(sessionId = id, context = context)
        cache.put(id, session)
        return session
    }

    override fun get(id: String): FlowSession? = cache.getIfPresent(id)

    override fun remove(id: String) {
        cache.invalidate(id)
    }
}
