package id.walt.verifyapi.webhook

import id.walt.verifyapi.db.VerifyWebhooks
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.*
import io.ktor.client.engine.okhttp.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

private val logger = KotlinLogging.logger {}

/**
 * Webhook event payload sent to registered webhook endpoints.
 */
@Serializable
data class WebhookPayload(
    val event: String,
    val timestamp: String,
    val data: WebhookData
)

/**
 * Data included in webhook payloads.
 */
@Serializable
data class WebhookData(
    val sessionId: String,
    val template: String,
    val status: String,
    val result: Map<String, String>? = null,
    val metadata: Map<String, String>? = null
)

/**
 * Webhook dispatcher for notifying relying parties of verification events.
 *
 * Features:
 * - Asynchronous delivery (non-blocking)
 * - HMAC-SHA256 signature for payload verification
 * - Exponential backoff retry on failure
 * - Event filtering based on webhook configuration
 *
 * Signature verification on the receiving end:
 * 1. Extract X-Verify-Timestamp and X-Verify-Signature headers
 * 2. Compute HMAC-SHA256 of "{timestamp}.{body}" using webhook secret
 * 3. Compare computed signature with X-Verify-Signature header
 */
object WebhookDispatcher {

    private val client = HttpClient(OkHttp) {
        install(HttpTimeout) {
            requestTimeoutMillis = 30_000
            connectTimeoutMillis = 10_000
            socketTimeoutMillis = 30_000
        }
    }

    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private const val MAX_RETRIES = 3
    private const val BASE_DELAY_MS = 1000L

    /**
     * Dispatches a webhook event asynchronously.
     * This method returns immediately and delivers the webhook in the background.
     *
     * @param organizationId The organization whose webhooks to notify
     * @param event The event type (e.g., "verification.completed", "verification.failed")
     * @param sessionId The verification session ID
     * @param template The template name used for verification
     * @param status The current verification status
     * @param result Optional verification result data
     * @param metadata Optional custom metadata
     */
    fun dispatchAsync(
        organizationId: UUID,
        event: String,
        sessionId: String,
        template: String,
        status: String,
        result: Map<String, String>? = null,
        metadata: Map<String, String>? = null
    ) {
        scope.launch {
            try {
                dispatch(organizationId, event, sessionId, template, status, result, metadata)
            } catch (e: Exception) {
                logger.error(e) { "Unhandled exception in webhook dispatch for org=$organizationId event=$event" }
            }
        }
    }

    /**
     * Internal dispatch method that fetches webhooks and delivers payloads.
     */
    private suspend fun dispatch(
        organizationId: UUID,
        event: String,
        sessionId: String,
        template: String,
        status: String,
        result: Map<String, String>?,
        metadata: Map<String, String>?
    ) {
        val webhooks = transaction {
            VerifyWebhooks.selectAll()
                .where {
                    (VerifyWebhooks.organizationId eq organizationId) and
                            (VerifyWebhooks.enabled eq true)
                }
                .filter { row ->
                    val events = json.decodeFromString<List<String>>(row[VerifyWebhooks.events])
                    events.contains(event) || events.contains("*")
                }
                .map { row ->
                    WebhookConfig(
                        url = row[VerifyWebhooks.url],
                        secret = row[VerifyWebhooks.secret]
                    )
                }
        }

        if (webhooks.isEmpty()) {
            logger.debug { "No webhooks configured for org=$organizationId event=$event" }
            return
        }

        val payload = WebhookPayload(
            event = event,
            timestamp = java.time.Instant.now().toString(),
            data = WebhookData(
                sessionId = sessionId,
                template = template,
                status = status,
                result = result,
                metadata = metadata
            )
        )
        val body = json.encodeToString(payload)

        logger.info { "Dispatching webhook event=$event to ${webhooks.size} endpoint(s) for org=$organizationId" }

        webhooks.forEach { webhook ->
            deliverWithRetry(webhook, body, event)
        }
    }

    /**
     * Delivers a webhook payload with exponential backoff retry.
     *
     * Retry delays: 2s, 4s, 8s (exponential backoff with base 1s, factor 2)
     */
    private suspend fun deliverWithRetry(webhook: WebhookConfig, body: String, event: String) {
        var attempt = 0
        while (attempt < MAX_RETRIES) {
            try {
                val timestamp = System.currentTimeMillis() / 1000
                val signature = sign(webhook.secret, "$timestamp.$body")

                val response = client.post(webhook.url) {
                    contentType(ContentType.Application.Json)
                    header("X-Verify-Signature", signature)
                    header("X-Verify-Timestamp", timestamp.toString())
                    header("X-Verify-Event", event)
                    setBody(body)
                }

                if (response.status.isSuccess()) {
                    logger.debug { "Webhook delivered successfully to ${webhook.url} (attempt ${attempt + 1})" }
                    return
                }

                logger.warn { "Webhook delivery failed with status ${response.status} to ${webhook.url} (attempt ${attempt + 1})" }
            } catch (e: Exception) {
                logger.warn(e) { "Webhook delivery error to ${webhook.url} (attempt ${attempt + 1})" }
            }

            attempt++
            if (attempt < MAX_RETRIES) {
                val delayMs = BASE_DELAY_MS * (1 shl attempt) // 2s, 4s, 8s
                logger.debug { "Retrying webhook delivery to ${webhook.url} in ${delayMs}ms" }
                delay(delayMs)
            }
        }

        logger.error { "Webhook delivery failed after $MAX_RETRIES attempts to ${webhook.url}" }
    }

    /**
     * Generates an HMAC-SHA256 signature for webhook payload verification.
     *
     * The signature is computed over "{timestamp}.{body}" where:
     * - timestamp: Unix epoch seconds when the request was sent
     * - body: The JSON payload body
     *
     * @param secret The webhook secret key
     * @param data The data to sign (timestamp.body)
     * @return Hex-encoded HMAC-SHA256 signature
     */
    private fun sign(secret: String, data: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return mac.doFinal(data.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    /**
     * Internal configuration for a webhook endpoint.
     */
    private data class WebhookConfig(
        val url: String,
        val secret: String
    )

    /**
     * Shuts down the webhook dispatcher gracefully.
     * Should be called when the application is stopping.
     */
    fun shutdown() {
        logger.info { "Shutting down webhook dispatcher..." }
        scope.cancel()
        client.close()
    }
}
