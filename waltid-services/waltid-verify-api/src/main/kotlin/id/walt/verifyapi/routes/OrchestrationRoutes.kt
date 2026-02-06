package id.walt.verifyapi.routes

import id.walt.verifyapi.auth.ApiKeyPrincipal
import id.walt.verifyapi.db.VerifyOrchestrations
import id.walt.verifyapi.orchestration.*
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
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.time.Instant
import java.util.UUID

private val logger = KotlinLogging.logger {}
private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

/**
 * Request to create a new orchestration definition.
 */
@Serializable
data class CreateOrchestrationRequest(
    /** Unique name for the orchestration */
    val name: String,
    /** Optional description */
    val description: String? = null,
    /** Ordered list of verification steps */
    val steps: List<OrchestrationStepRequest>,
    /** Actions to perform on completion */
    val onComplete: OnComplete? = null
)

/**
 * Step definition for creating an orchestration.
 */
@Serializable
data class OrchestrationStepRequest(
    /** Unique identifier for this step within the orchestration */
    val id: String,
    /** Step type: "identity" for credential verification, "payment" for payment auth */
    val type: String = "identity",
    /** Name of the verification template to use for this step */
    val template: String,
    /** List of step IDs that must complete before this step can run */
    val dependsOn: List<String> = emptyList()
)

/**
 * Response for orchestration listing.
 */
@Serializable
data class OrchestrationListResponse(
    /** Unique orchestration ID */
    val id: String,
    /** Orchestration name */
    val name: String,
    /** Number of steps in the orchestration */
    val stepCount: Int,
    /** Optional description */
    val description: String?
)

/**
 * Full orchestration details response.
 */
@Serializable
data class OrchestrationResponse(
    /** Unique orchestration ID */
    val id: String,
    /** Orchestration name */
    val name: String,
    /** Optional description */
    val description: String?,
    /** Steps in the orchestration */
    val steps: List<OrchestrationStep>,
    /** Completion actions */
    val onComplete: OnComplete?
)

/**
 * Request to start an orchestration session.
 */
@Serializable
data class StartOrchestrationRequest(
    /** Optional metadata to attach to the session */
    val metadata: Map<String, String>? = null
)

/**
 * Response for orchestration session status.
 */
@Serializable
data class OrchestrationSessionResponse(
    /** Unique orchestration session ID */
    val orchestrationSessionId: String,
    /** ID of the current step (null if completed) */
    val currentStep: String?,
    /** Current status: in_progress, completed, failed */
    val status: String,
    /** List of completed step IDs */
    val completedSteps: List<String>,
    /** Information about the current verification */
    val verification: VerificationInfo? = null,
    /** Template for the current step */
    val currentTemplate: String? = null
)

/**
 * Information about the current verification step.
 */
@Serializable
data class VerificationInfo(
    /** Verification session ID */
    val sessionId: String,
    /** Template being used */
    val template: String,
    /** URL for the QR code (if applicable) */
    val qrCodeUrl: String? = null
)

/**
 * Configure orchestration management routes under /v1/orchestrations.
 *
 * Provides endpoints for:
 * - Creating orchestration definitions
 * - Listing orchestrations
 * - Starting orchestration sessions
 * - Getting orchestration session status
 */
fun Route.orchestrationRoutes() {
    authenticate("api-key") {
        route("/v1/orchestrations") {
            /**
             * GET /v1/orchestrations
             *
             * List all orchestrations for the authenticated organization.
             */
            get {
                val principal = call.principal<ApiKeyPrincipal>()!!
                logger.debug { "Listing orchestrations for organization: ${principal.organizationId}" }

                val orchestrations = transaction {
                    VerifyOrchestrations.selectAll()
                        .where { VerifyOrchestrations.organizationId eq principal.organizationId }
                        .map { row ->
                            val steps: List<OrchestrationStep> = json.decodeFromString(row[VerifyOrchestrations.steps])
                            OrchestrationListResponse(
                                id = row[VerifyOrchestrations.id].value.toString(),
                                name = row[VerifyOrchestrations.name],
                                stepCount = steps.size,
                                description = row[VerifyOrchestrations.description]
                            )
                        }
                }

                call.respond(orchestrations)
            }

            /**
             * POST /v1/orchestrations
             *
             * Create a new orchestration definition.
             * The orchestration defines a multi-step verification flow.
             */
            post {
                val principal = call.principal<ApiKeyPrincipal>()!!
                val request = call.receive<CreateOrchestrationRequest>()

                logger.debug { "Creating orchestration '${request.name}' for organization: ${principal.organizationId}" }

                // Validate name format
                if (!request.name.matches(Regex("^[a-z][a-z0-9_-]*$"))) {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        mapOf("error" to "Orchestration name must be lowercase, start with letter, contain only letters/numbers/underscores/hyphens")
                    )
                    return@post
                }

                // Validate at least one step
                if (request.steps.isEmpty()) {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        mapOf("error" to "At least one step is required")
                    )
                    return@post
                }

                // Validate step IDs are unique
                val stepIds = request.steps.map { it.id }
                if (stepIds.size != stepIds.distinct().size) {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        mapOf("error" to "Step IDs must be unique")
                    )
                    return@post
                }

                // Validate dependencies reference existing steps
                for (step in request.steps) {
                    for (depId in step.dependsOn) {
                        if (depId !in stepIds) {
                            call.respond(
                                HttpStatusCode.BadRequest,
                                mapOf("error" to "Step '${step.id}' depends on unknown step '$depId'")
                            )
                            return@post
                        }
                    }
                }

                // Convert to OrchestrationStep objects
                val steps = request.steps.map { step ->
                    OrchestrationStep(
                        id = step.id,
                        type = step.type,
                        template = step.template,
                        dependsOn = step.dependsOn
                    )
                }

                try {
                    val orchestrationId = transaction {
                        // Check if orchestration with same name already exists
                        val existing = VerifyOrchestrations.selectAll()
                            .where {
                                (VerifyOrchestrations.organizationId eq principal.organizationId) and
                                        (VerifyOrchestrations.name eq request.name)
                            }
                            .count()

                        if (existing > 0) {
                            throw IllegalStateException("Orchestration with name '${request.name}' already exists")
                        }

                        val now = Instant.now()
                        VerifyOrchestrations.insert {
                            it[organizationId] = principal.organizationId
                            it[name] = request.name
                            it[description] = request.description
                            it[this.steps] = json.encodeToString(steps)
                            it[onComplete] = request.onComplete?.let { oc -> json.encodeToString(oc) }
                            it[createdAt] = now
                            it[updatedAt] = now
                        } get VerifyOrchestrations.id
                    }

                    logger.info { "Created orchestration '${request.name}' (${orchestrationId.value}) for organization: ${principal.organizationId}" }

                    call.respond(
                        HttpStatusCode.Created,
                        mapOf(
                            "id" to orchestrationId.value.toString(),
                            "name" to request.name,
                            "message" to "Orchestration created successfully"
                        )
                    )
                } catch (e: IllegalStateException) {
                    call.respond(
                        HttpStatusCode.Conflict,
                        mapOf("error" to (e.message ?: "Orchestration already exists"))
                    )
                }
            }

            /**
             * GET /v1/orchestrations/{id}
             *
             * Get details of a specific orchestration.
             */
            get("/{id}") {
                val principal = call.principal<ApiKeyPrincipal>()!!
                val orchestrationId = call.parameters["id"]?.let {
                    runCatching { UUID.fromString(it) }.getOrNull()
                }

                if (orchestrationId == null) {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        mapOf("error" to "Invalid orchestration ID format")
                    )
                    return@get
                }

                val orchestration = transaction {
                    VerifyOrchestrations.selectAll()
                        .where {
                            (VerifyOrchestrations.id eq orchestrationId) and
                                    (VerifyOrchestrations.organizationId eq principal.organizationId)
                        }
                        .singleOrNull()
                        ?.let { row ->
                            val steps: List<OrchestrationStep> = json.decodeFromString(row[VerifyOrchestrations.steps])
                            val onComplete: OnComplete? = row[VerifyOrchestrations.onComplete]?.let { json.decodeFromString(it) }
                            OrchestrationResponse(
                                id = row[VerifyOrchestrations.id].value.toString(),
                                name = row[VerifyOrchestrations.name],
                                description = row[VerifyOrchestrations.description],
                                steps = steps,
                                onComplete = onComplete
                            )
                        }
                }

                if (orchestration == null) {
                    call.respond(
                        HttpStatusCode.NotFound,
                        mapOf("error" to "Orchestration not found")
                    )
                    return@get
                }

                call.respond(orchestration)
            }

            /**
             * POST /v1/orchestrations/{id}/sessions
             *
             * Start a new orchestration session.
             * This initiates the multi-step verification flow.
             */
            post("/{id}/sessions") {
                val principal = call.principal<ApiKeyPrincipal>()!!
                val orchestrationId = call.parameters["id"]?.let {
                    runCatching { UUID.fromString(it) }.getOrNull()
                }

                if (orchestrationId == null) {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        mapOf("error" to "Invalid orchestration ID format")
                    )
                    return@post
                }

                val request = runCatching { call.receive<StartOrchestrationRequest>() }
                    .getOrElse { StartOrchestrationRequest() }

                // Load orchestration definition from database
                val orchestration = transaction {
                    VerifyOrchestrations.selectAll()
                        .where {
                            (VerifyOrchestrations.id eq orchestrationId) and
                                    (VerifyOrchestrations.organizationId eq principal.organizationId)
                        }
                        .singleOrNull()?.let { row ->
                            val steps: List<OrchestrationStep> = json.decodeFromString(row[VerifyOrchestrations.steps])
                            val onComplete: OnComplete? = row[VerifyOrchestrations.onComplete]?.let { json.decodeFromString(it) }
                            OrchestrationDefinition(
                                id = row[VerifyOrchestrations.id].value.toString(),
                                name = row[VerifyOrchestrations.name],
                                steps = steps,
                                onComplete = onComplete
                            )
                        }
                }

                if (orchestration == null) {
                    call.respond(
                        HttpStatusCode.NotFound,
                        mapOf("error" to "Orchestration not found")
                    )
                    return@post
                }

                // Start the orchestration session
                val session = OrchestrationEngine.startOrchestration(
                    orchestration = orchestration,
                    organizationId = principal.organizationId,
                    metadata = request.metadata
                )

                val currentStep = orchestration.steps.find { it.id == session.currentStepId }

                logger.info { "Started orchestration session ${session.id} for orchestration ${orchestration.id}" }

                call.respond(
                    HttpStatusCode.Created,
                    OrchestrationSessionResponse(
                        orchestrationSessionId = session.id,
                        currentStep = session.currentStepId,
                        status = session.status.name.lowercase(),
                        completedSteps = session.completedSteps.keys.toList(),
                        currentTemplate = currentStep?.template,
                        verification = currentStep?.let {
                            VerificationInfo(
                                sessionId = "pending",
                                template = it.template
                            )
                        }
                    )
                )
            }

            /**
             * GET /v1/orchestrations/sessions/{session_id}
             *
             * Get the status of an orchestration session.
             */
            get("/sessions/{session_id}") {
                val principal = call.principal<ApiKeyPrincipal>()!!
                val sessionId = call.parameters["session_id"]

                if (sessionId.isNullOrBlank()) {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        mapOf("error" to "Missing session_id")
                    )
                    return@get
                }

                val session = OrchestrationEngine.getSession(sessionId)

                if (session == null) {
                    call.respond(
                        HttpStatusCode.NotFound,
                        mapOf("error" to "Session not found")
                    )
                    return@get
                }

                // Verify the session belongs to this organization
                if (session.organizationId != principal.organizationId.toString()) {
                    call.respond(
                        HttpStatusCode.NotFound,
                        mapOf("error" to "Session not found")
                    )
                    return@get
                }

                call.respond(
                    OrchestrationSessionResponse(
                        orchestrationSessionId = session.id,
                        currentStep = session.currentStepId,
                        status = session.status.name.lowercase(),
                        completedSteps = session.completedSteps.keys.toList()
                    )
                )
            }
        }
    }
}
