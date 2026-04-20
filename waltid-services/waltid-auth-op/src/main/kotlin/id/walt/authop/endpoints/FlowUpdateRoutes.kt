@file:OptIn(ExperimentalTime::class)

package id.walt.authop.endpoints

import id.walt.authop.AuthOpDeps
import id.walt.authop.domain.FlowStepUpdate
import id.walt.authop.templates.respondFlowDemoPage
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytesWriter
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.utils.io.writeStringUtf8
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Duration as JDuration
import kotlin.time.ExperimentalTime

/**
 * Callback + progress-stream endpoints for the n8n-driven "flow updates"
 * demo surface.
 *
 * ## Three endpoints
 *
 * - `POST /api/flow-kickoff` — mints a server-side flow session (UUID), binds
 *   an empty [id.walt.authop.store.FlowSession] into the store, and returns
 *   the sessionId + the SSE URL. The browser calls this before firing the
 *   workflow webhook so both sides agree on the id.
 *
 * - `POST /api/flow-updates` — authenticated callback from the workflow
 *   engine (n8n). Each workflow step ends with a POST here carrying
 *   `FlowStepUpdate`. We validate the shared secret via constant-time
 *   compare, look up the session, emit the update into its [SharedFlow],
 *   and return 202. **Block-until-ack**: n8n's HTTP Request node waits
 *   synchronously; a 4xx/5xx fails the step and therefore the workflow,
 *   giving us loud back-pressure instead of silent loss.
 *
 * - `GET /api/flow-stream?sessionId=…` — Server-Sent Events stream that the
 *   demo page (or any real RP UI) subscribes to with `EventSource`. The
 *   [id.walt.authop.store.FlowSession.updates] replay buffer means a late
 *   subscriber still sees the events that arrived before it connected — no
 *   race between kickoff and EventSource open.
 *
 * ## Feature toggle
 *
 * If [AuthOpDeps.flowUpdateStore] or [AuthOpDeps.config.flowCallbackSecret]
 * is null, every route here returns 404. Keeps the feature absence clean
 * rather than half-wired (same convention as passkey routes when passkey
 * config is missing).
 *
 * ## Security
 *
 * - Shared-secret header (`X-Flow-Callback-Secret`) on the callback endpoint
 *   prevents the internet from writing bogus "everything is fine" updates
 *   into someone else's flow. Compared constant-time via
 *   [MessageDigest.isEqual]. 32+ hex chars is expected.
 * - SSE stream is keyed on the server-minted UUID, no separate secret. The
 *   UUID's 122 bits of entropy makes it practically unguessable — the
 *   share-by-link model. Flow sessions TTL to 10 min; a leaked id is only
 *   useful for that window.
 * - [Kickoff] takes no auth intentionally. Anyone can mint a session; they
 *   just get a fresh UUID with no upstream workflow attached. The damage
 *   ceiling is memory bloat, bounded by the cache's TTL.
 */
fun Route.flowUpdateRoutes(deps: AuthOpDeps) {
    val store = deps.flowUpdateStore
    val secret = deps.config.flowCallbackSecret
    // Feature off — register stub 404s so the routes aren't silently absent.
    // (Returning 404 over 501 mirrors the passkey convention.)
    if (store == null || secret == null) {
        post("/api/flow-kickoff") { call.respond(HttpStatusCode.NotFound, "flow-updates feature disabled") }
        post("/api/flow-updates") { call.respond(HttpStatusCode.NotFound, "flow-updates feature disabled") }
        post("/api/flow-demo-fire") { call.respond(HttpStatusCode.NotFound, "flow-updates feature disabled") }
        get("/api/flow-stream") { call.respond(HttpStatusCode.NotFound, "flow-updates feature disabled") }
        get("/flow-demo") { call.respond(HttpStatusCode.NotFound, "flow-updates feature disabled") }
        return
    }

    // -------------------------------------------------------------------------
    // GET /flow-demo — self-contained HTML demo page (local verification only)
    // -------------------------------------------------------------------------
    get("/flow-demo") {
        call.respondFlowDemoPage()
    }

    val secretBytes = secret.toByteArray(StandardCharsets.UTF_8)

    // -------------------------------------------------------------------------
    // POST /api/flow-kickoff
    // -------------------------------------------------------------------------
    post("/api/flow-kickoff") {
        val body = runCatching { call.receive<KickoffRequest>() }.getOrElse { KickoffRequest() }
        val session = store.create(context = body.context)
        call.respond(
            HttpStatusCode.OK,
            KickoffResponse(
                sessionId = session.sessionId,
                streamUrl = "/api/flow-stream?sessionId=${session.sessionId}",
            ),
        )
    }

    // -------------------------------------------------------------------------
    // POST /api/flow-updates
    // -------------------------------------------------------------------------
    post("/api/flow-updates") {
        val supplied = call.request.headers["X-Flow-Callback-Secret"]
        if (supplied == null) {
            call.respondText(
                text = "missing X-Flow-Callback-Secret",
                status = HttpStatusCode.Unauthorized,
            )
            return@post
        }
        val suppliedBytes = supplied.toByteArray(StandardCharsets.UTF_8)
        // MessageDigest.isEqual is constant-time only when the two arrays are
        // the same length. Short-circuit on length mismatch would leak length
        // info, which here is already public (we defined the secret length),
        // so it's safe.
        if (suppliedBytes.size != secretBytes.size || !MessageDigest.isEqual(suppliedBytes, secretBytes)) {
            call.respondText(
                text = "invalid callback secret",
                status = HttpStatusCode.Unauthorized,
            )
            return@post
        }

        val update = try {
            call.receive<FlowStepUpdate>()
        } catch (_: Exception) {
            call.respondText(
                text = "invalid FlowStepUpdate JSON",
                status = HttpStatusCode.BadRequest,
            )
            return@post
        }

        val session = store.get(update.sessionId)
        if (session == null) {
            // Explicit 404 — tells n8n the sessionId was bogus or the session
            // expired, which is a meaningful signal (something is wrong in
            // the upstream kickoff) rather than silently acking.
            call.respondText(
                text = "unknown or expired sessionId",
                status = HttpStatusCode.NotFound,
            )
            return@post
        }

        val accepted = session.tryEmit(update)
        if (!accepted) {
            // Should be unreachable given our buffer sizing; surface loudly
            // if it ever happens so we find out rather than losing events.
            call.respondText(
                text = "flow session buffer full",
                status = HttpStatusCode.ServiceUnavailable,
            )
            return@post
        }

        call.respond(HttpStatusCode.Accepted, EmptyAck())
    }

    // -------------------------------------------------------------------------
    // POST /api/flow-demo-fire  (server-side proxy to the n8n webhook)
    //
    // The demo page is served by auth-op (origin A). The n8n webhook lives on
    // origin B. A browser `fetch(B)` would need n8n to send CORS headers —
    // which it doesn't by default and which we'd rather not flip on for this
    // surface. Easier: auth-op's JS calls back to origin A, and auth-op
    // performs the cross-origin POST server-side. It's also a better mirror
    // of how a real RP would call n8n — from a trusted backend, not the
    // browser.
    //
    // The webhook URL is hardcoded here because this endpoint is demo-only.
    // If we ever want to fire arbitrary workflows we'll move it to config.
    // -------------------------------------------------------------------------
    post("/api/flow-demo-fire") {
        val body = runCatching { call.receive<DemoFireRequest>() }.getOrElse {
            call.respondText(
                text = "invalid DemoFireRequest JSON",
                status = HttpStatusCode.BadRequest,
            )
            return@post
        }
        if (store.get(body.sessionId) == null) {
            call.respondText(
                text = "unknown or expired sessionId",
                status = HttpStatusCode.NotFound,
            )
            return@post
        }

        val payload = Json.encodeToString(
            DemoFirePayload.serializer(),
            DemoFirePayload(
                sessionId = body.sessionId,
                customerRef = body.customerRef ?: "cust_001",
                channel = body.channel ?: "demo",
            ),
        )
        val req = HttpRequest.newBuilder()
            .uri(URI.create(DEMO_WEBHOOK_URL))
            .timeout(JDuration.ofSeconds(30))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(payload))
            .build()

        val httpResponse: HttpResponse<String> = try {
            withContext(Dispatchers.IO) {
                DEMO_HTTP_CLIENT.send(req, HttpResponse.BodyHandlers.ofString())
            }
        } catch (e: Exception) {
            call.respond(
                HttpStatusCode.BadGateway,
                buildJsonObject {
                    put("error", "webhook_call_failed")
                    put("detail", e.message ?: e.javaClass.simpleName)
                },
            )
            return@post
        }

        // Mirror upstream status so the browser can show "workflow failed"
        // distinct from "callback failed". Body is forwarded as-is.
        call.respondText(
            text = httpResponse.body() ?: "",
            contentType = ContentType.Application.Json,
            status = HttpStatusCode.fromValue(httpResponse.statusCode()),
        )
    }

    // -------------------------------------------------------------------------
    // GET /api/flow-stream?sessionId=...
    // -------------------------------------------------------------------------
    get("/api/flow-stream") {
        val sessionId = call.request.queryParameters["sessionId"]
        if (sessionId.isNullOrBlank()) {
            call.respondText(
                text = "sessionId query parameter is required",
                status = HttpStatusCode.BadRequest,
            )
            return@get
        }
        val session = store.get(sessionId)
        if (session == null) {
            call.respondText(
                text = "unknown or expired sessionId",
                status = HttpStatusCode.NotFound,
            )
            return@get
        }

        // SSE response. `X-Accel-Buffering: no` disables any reverse-proxy
        // buffering (Caddy doesn't buffer, but Cloudflare tunnels and future
        // proxies might) — without this, events can pool for several seconds
        // before flushing.
        call.response.headers.append("Cache-Control", "no-cache")
        call.response.headers.append("X-Accel-Buffering", "no")
        call.respondBytesWriter(contentType = ContentType.Text.EventStream) {
            coroutineScope {
                // 15s heartbeat. Comment lines in SSE (`: ...`) are ignored by
                // EventSource but keep the socket warm against idle-timeout
                // middleboxes.
                val heartbeat = launch {
                    while (isActive) {
                        delay(HEARTBEAT_INTERVAL_MS)
                        val written = runCatching {
                            writeStringUtf8(": heartbeat\n\n")
                            flush()
                        }.isSuccess
                        // If the write failed the client is gone; stop looping.
                        // `cancel()` here is ambiguous because `this` is the
                        // ByteWriteChannel inside `respondBytesWriter`, not the
                        // coroutine scope — `break` is clearer anyway.
                        if (!written) break
                    }
                }
                try {
                    session.updates.collect { update ->
                        val json = SSE_JSON.encodeToString(FlowStepUpdate.serializer(), update)
                        // Strip newlines defensively — SSE framing would break
                        // if an encoded value ever embedded a literal `\n\n`.
                        // Our schema's strings don't currently, but cheap insurance.
                        val safe = json.replace("\n", " ")
                        writeStringUtf8("data: $safe\n\n")
                        flush()
                    }
                } catch (_: CancellationException) {
                    // Client disconnected or session expired — normal path.
                } finally {
                    heartbeat.cancel()
                }
            }
        }
    }
}

private const val HEARTBEAT_INTERVAL_MS = 15_000L

/**
 * Lenient JSON config for SSE payloads — `explicitNulls = false` keeps the
 * wire payload small by omitting the `error` / `result` fields when absent,
 * which reduces bytes on each event and is easier to read during manual
 * inspection.
 */
private val SSE_JSON = Json {
    encodeDefaults = false
    explicitNulls = false
}

@Serializable
private data class KickoffRequest(
    val context: JsonElement? = null,
)

@Serializable
private data class KickoffResponse(
    val sessionId: String,
    val streamUrl: String,
)

@Serializable
private class EmptyAck

@Serializable
private data class DemoFireRequest(
    val sessionId: String,
    val customerRef: String? = null,
    val channel: String? = null,
)

@Serializable
private data class DemoFirePayload(
    val sessionId: String,
    val customerRef: String,
    val channel: String,
)

/**
 * Hardcoded n8n webhook URL for the demo. Uses the internal docker-compose
 * service DNS so the call stays inside the private network — faster than
 * round-tripping through Cloudflare, and avoids any TLS cert fuss.
 */
private const val DEMO_WEBHOOK_URL = "http://n8n:5678/webhook/customer-aggregate"

/**
 * A single JDK HttpClient reused across demo-fire calls. JDK-native so we
 * don't drag in an extra Ktor client (auth-op already composes fine without
 * one here).
 *
 * **Pinned to HTTP/1.1.** The JDK's default is to attempt HTTP/2 upgrade via
 * ALPN; n8n's plain-HTTP listener doesn't negotiate HTTP/2, and the client
 * hangs until request timeout (~30s) before falling back. Forcing 1.1
 * sidesteps the whole negotiation and gives us sub-second calls.
 */
private val DEMO_HTTP_CLIENT: HttpClient = HttpClient.newBuilder()
    .connectTimeout(JDuration.ofSeconds(5))
    .version(HttpClient.Version.HTTP_1_1)
    .build()
