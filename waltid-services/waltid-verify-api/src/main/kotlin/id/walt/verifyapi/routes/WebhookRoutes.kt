package id.walt.verifyapi.routes

import id.walt.verifyapi.auth.ApiKeyPrincipal
import id.walt.verifyapi.db.VerifyWebhooks
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.http.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.security.SecureRandom
import java.time.Instant
import java.util.UUID

private val logger = KotlinLogging.logger {}

/**
 * Request to create a new webhook subscription.
 */
@Serializable
data class CreateWebhookRequest(
    /** URL to receive webhook events (must be HTTPS) */
    val url: String,
    /** List of event types to subscribe to */
    val events: List<String>,
    /** Optional secret for HMAC signature verification (auto-generated if not provided) */
    val secret: String? = null
)

/**
 * Response containing webhook details.
 */
@Serializable
data class WebhookResponse(
    /** Unique webhook ID */
    val id: String,
    /** URL receiving webhook events */
    val url: String,
    /** Subscribed event types */
    val events: List<String>,
    /** Whether the webhook is enabled */
    val enabled: Boolean,
    /** Secret for signature verification (only returned on creation) */
    val secret: String? = null
)

private val json = Json { encodeDefaults = true }

/**
 * Valid webhook event types.
 */
private val VALID_EVENTS = setOf(
    "verification.completed",
    "verification.failed",
    "verification.expired",
    "orchestration.step_completed",
    "orchestration.completed",
    "*"  // Wildcard for all events
)

/**
 * Configure webhook management routes under /v1/webhooks.
 *
 * Provides endpoints for creating, listing, and deleting webhooks.
 * Webhooks are scoped to the authenticated organization.
 */
fun Route.webhookRoutes() {
    authenticate("api-key") {
        route("/v1/webhooks") {
            /**
             * GET /v1/webhooks
             *
             * List all webhooks for the authenticated organization.
             */
            get {
                val principal = call.principal<ApiKeyPrincipal>()!!
                logger.debug { "Listing webhooks for organization: ${principal.organizationId}" }

                val webhooks = transaction {
                    VerifyWebhooks.selectAll()
                        .where { VerifyWebhooks.organizationId eq principal.organizationId }
                        .map { row ->
                            WebhookResponse(
                                id = row[VerifyWebhooks.id].value.toString(),
                                url = row[VerifyWebhooks.url],
                                events = json.decodeFromString(row[VerifyWebhooks.events]),
                                enabled = row[VerifyWebhooks.enabled]
                            )
                        }
                }

                call.respond(webhooks)
            }

            /**
             * POST /v1/webhooks
             *
             * Create a new webhook subscription.
             * The webhook URL must use HTTPS.
             * Returns the created webhook with the secret (only time secret is returned).
             */
            post {
                val principal = call.principal<ApiKeyPrincipal>()!!
                val request = call.receive<CreateWebhookRequest>()

                logger.debug { "Creating webhook for organization: ${principal.organizationId}" }

                // Validate URL uses HTTPS
                if (!request.url.startsWith("https://")) {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        mapOf("error" to "Webhook URL must use HTTPS")
                    )
                    return@post
                }

                // Validate URL is well-formed
                try {
                    java.net.URI(request.url)
                } catch (e: Exception) {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        mapOf("error" to "Invalid webhook URL format")
                    )
                    return@post
                }

                // Validate events
                if (request.events.isEmpty()) {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        mapOf("error" to "At least one event type must be specified")
                    )
                    return@post
                }

                val invalidEvents = request.events.filter { it !in VALID_EVENTS }
                if (invalidEvents.isNotEmpty()) {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        mapOf(
                            "error" to "Invalid event types: ${invalidEvents.joinToString(", ")}",
                            "valid_events" to VALID_EVENTS.toList()
                        )
                    )
                    return@post
                }

                val secret = request.secret ?: generateSecret()

                val webhookId = transaction {
                    val now = Instant.now()
                    VerifyWebhooks.insert {
                        it[organizationId] = principal.organizationId
                        it[url] = request.url
                        it[VerifyWebhooks.secret] = secret
                        it[events] = json.encodeToString(request.events)
                        it[enabled] = true
                        it[createdAt] = now
                        it[updatedAt] = now
                    } get VerifyWebhooks.id
                }

                logger.info { "Created webhook ${webhookId.value} for organization: ${principal.organizationId}" }

                call.respond(
                    HttpStatusCode.Created,
                    WebhookResponse(
                        id = webhookId.value.toString(),
                        url = request.url,
                        events = request.events,
                        enabled = true,
                        secret = secret  // Only returned on creation
                    )
                )
            }

            /**
             * GET /v1/webhooks/{id}
             *
             * Get a specific webhook by ID.
             */
            get("/{id}") {
                val principal = call.principal<ApiKeyPrincipal>()!!
                val webhookId = call.parameters["id"]?.let {
                    runCatching { UUID.fromString(it) }.getOrNull()
                }

                if (webhookId == null) {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        mapOf("error" to "Invalid webhook ID format")
                    )
                    return@get
                }

                val webhook = transaction {
                    VerifyWebhooks.selectAll()
                        .where {
                            (VerifyWebhooks.id eq webhookId) and
                            (VerifyWebhooks.organizationId eq principal.organizationId)
                        }
                        .singleOrNull()
                        ?.let { row ->
                            WebhookResponse(
                                id = row[VerifyWebhooks.id].value.toString(),
                                url = row[VerifyWebhooks.url],
                                events = json.decodeFromString(row[VerifyWebhooks.events]),
                                enabled = row[VerifyWebhooks.enabled]
                            )
                        }
                }

                if (webhook == null) {
                    call.respond(
                        HttpStatusCode.NotFound,
                        mapOf("error" to "Webhook not found")
                    )
                    return@get
                }

                call.respond(webhook)
            }

            /**
             * DELETE /v1/webhooks/{id}
             *
             * Delete a webhook subscription.
             */
            delete("/{id}") {
                val principal = call.principal<ApiKeyPrincipal>()!!
                val webhookId = call.parameters["id"]?.let {
                    runCatching { UUID.fromString(it) }.getOrNull()
                }

                if (webhookId == null) {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        mapOf("error" to "Invalid webhook ID format")
                    )
                    return@delete
                }

                val deleted = transaction {
                    VerifyWebhooks.deleteWhere {
                        (VerifyWebhooks.id eq webhookId) and
                        (organizationId eq principal.organizationId)
                    }
                }

                if (deleted > 0) {
                    logger.info { "Deleted webhook $webhookId for organization: ${principal.organizationId}" }
                    call.respond(HttpStatusCode.NoContent)
                } else {
                    call.respond(
                        HttpStatusCode.NotFound,
                        mapOf("error" to "Webhook not found")
                    )
                }
            }
        }
    }
}

/**
 * Generate a cryptographically secure random secret for webhook signature verification.
 */
private fun generateSecret(): String {
    val bytes = ByteArray(32)
    SecureRandom().nextBytes(bytes)
    return "whsec_" + bytes.joinToString("") { "%02x".format(it) }
}
