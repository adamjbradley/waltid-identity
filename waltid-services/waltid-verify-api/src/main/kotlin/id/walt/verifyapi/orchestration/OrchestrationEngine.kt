package id.walt.verifyapi.orchestration

import id.walt.commons.persistence.ConfiguredPersistence
import id.walt.verifyapi.session.SessionStatus
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID
import kotlin.time.Duration.Companion.minutes

private val logger = KotlinLogging.logger {}

/**
 * Defines a multi-step verification orchestration.
 *
 * Orchestrations allow chaining multiple verification steps together,
 * such as identity verification followed by payment authorization.
 */
@Serializable
data class OrchestrationDefinition(
    /** Unique identifier for this orchestration definition */
    val id: String,
    /** Human-readable name */
    val name: String,
    /** Ordered list of verification steps */
    val steps: List<OrchestrationStep>,
    /** Actions to take when orchestration completes */
    val onComplete: OnComplete? = null
)

/**
 * A single step in an orchestration flow.
 */
@Serializable
data class OrchestrationStep(
    /** Unique identifier for this step within the orchestration */
    val id: String,
    /** Step type: "identity" for credential verification, "payment" for payment auth */
    val type: String,
    /** Name of the verification template to use for this step */
    val template: String,
    /** List of step IDs that must complete before this step can run */
    val dependsOn: List<String> = emptyList()
)

/**
 * Actions to execute when an orchestration completes.
 */
@Serializable
data class OnComplete(
    /** Webhook URL to POST results to */
    val webhook: String? = null,
    /** URL to redirect the user to on completion */
    val redirect: String? = null
)

/**
 * An active orchestration session tracking progress through steps.
 */
@Serializable
data class OrchestrationSession(
    /** Unique session ID in format orch_xxxxxxxxxxxx */
    val id: String,
    /** ID of the orchestration definition being executed */
    val orchestrationId: String,
    /** Organization ID that owns this session */
    val organizationId: String,
    /** ID of the step currently waiting for completion, null if orchestration is complete */
    val currentStepId: String?,
    /** Map of completed step IDs to their results */
    val completedSteps: Map<String, StepResult> = emptyMap(),
    /** Current status of the orchestration */
    val status: OrchestrationStatus,
    /** Epoch millis when session was created */
    val createdAt: Long,
    /** Epoch millis when session expires */
    val expiresAt: Long,
    /** Optional metadata from the requesting application */
    val metadata: Map<String, String>? = null
)

/**
 * Status of an orchestration session.
 */
@Serializable
enum class OrchestrationStatus {
    /** Orchestration is in progress, waiting for current step to complete */
    IN_PROGRESS,
    /** All steps completed successfully */
    COMPLETED,
    /** One or more steps failed */
    FAILED
}

/**
 * Result of a completed orchestration step.
 */
@Serializable
data class StepResult(
    /** ID of the verification session that completed this step */
    val verificationSessionId: String,
    /** Final status of the step */
    val status: String,
    /** Epoch millis when the step completed */
    val completedAt: Long,
    /** Extracted result data from the verification */
    val result: Map<String, String>? = null
)

/**
 * Engine for executing multi-step verification orchestrations.
 *
 * The orchestration engine:
 * - Creates orchestration sessions from definitions
 * - Tracks progress through steps with dependency resolution
 * - Persists state in Valkey/Redis for durability
 * - Determines next eligible steps based on dependency graph
 *
 * Step execution flow:
 * 1. Start orchestration - finds first step with no dependencies
 * 2. On step completion - marks step done, finds next eligible step
 * 3. Continue until all steps complete or a step fails
 *
 * Example orchestration:
 * ```
 * {
 *   "id": "identity-then-payment",
 *   "name": "Identity + Payment",
 *   "steps": [
 *     { "id": "identity", "type": "identity", "template": "kyc-basic" },
 *     { "id": "payment", "type": "payment", "template": "pwa-auth", "dependsOn": ["identity"] }
 *   ]
 * }
 * ```
 */
object OrchestrationEngine {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val sessionTtl = 30.minutes

    private val sessionPersistence = ConfiguredPersistence(
        discriminator = "verify:orchestration",
        defaultExpiration = sessionTtl,
        encoding = { session: OrchestrationSession -> json.encodeToString(session) },
        decoding = { data: String -> json.decodeFromString<OrchestrationSession>(data) }
    )

    /**
     * Generate a unique orchestration session ID in the format orch_xxxxxxxxxxxx
     */
    private fun generateSessionId(): String {
        val uuid = UUID.randomUUID().toString().replace("-", "").take(12)
        return "orch_$uuid"
    }

    /**
     * Start a new orchestration session.
     *
     * Creates a session and identifies the first step to execute (one with no dependencies).
     *
     * @param orchestration The orchestration definition to execute
     * @param organizationId Organization initiating the orchestration
     * @param metadata Optional metadata from the requesting application
     * @return The created orchestration session
     * @throws IllegalArgumentException if the orchestration has no steps or has circular dependencies
     */
    fun startOrchestration(
        orchestration: OrchestrationDefinition,
        organizationId: UUID,
        metadata: Map<String, String>?
    ): OrchestrationSession {
        require(orchestration.steps.isNotEmpty()) { "Orchestration must have at least one step" }

        // Validate no circular dependencies
        validateDependencies(orchestration)

        val sessionId = generateSessionId()
        val now = System.currentTimeMillis()

        // Find first step with no dependencies
        val firstStep = orchestration.steps.firstOrNull { it.dependsOn.isEmpty() }
            ?: throw IllegalArgumentException("Orchestration has no step without dependencies (circular dependency detected)")

        val session = OrchestrationSession(
            id = sessionId,
            orchestrationId = orchestration.id,
            organizationId = organizationId.toString(),
            currentStepId = firstStep.id,
            status = OrchestrationStatus.IN_PROGRESS,
            createdAt = now,
            expiresAt = now + sessionTtl.inWholeMilliseconds,
            metadata = metadata
        )

        sessionPersistence[sessionId] = session
        logger.info { "Started orchestration $sessionId (${orchestration.name}) with step ${firstStep.id} for org $organizationId" }

        return session
    }

    /**
     * Retrieve an orchestration session by ID.
     *
     * @param sessionId The session ID (orch_xxx format)
     * @return The session if found, null otherwise
     */
    fun getSession(sessionId: String): OrchestrationSession? {
        return sessionPersistence[sessionId]
    }

    /**
     * Update an existing orchestration session.
     *
     * @param session The updated session data
     * @return The updated session
     */
    fun updateSession(session: OrchestrationSession): OrchestrationSession {
        sessionPersistence[session.id] = session
        logger.debug { "Updated orchestration session ${session.id} to status ${session.status}" }
        return session
    }

    /**
     * Mark a step as completed and advance to the next step.
     *
     * This method:
     * 1. Records the step result
     * 2. Checks if all steps are complete
     * 3. If not complete, finds the next eligible step (all dependencies satisfied)
     * 4. Updates the orchestration status accordingly
     *
     * @param sessionId The orchestration session ID
     * @param stepId The step that completed
     * @param verificationSessionId The verification session ID that completed the step
     * @param status The verification status
     * @param result Optional result data from the verification
     * @param orchestration The orchestration definition (needed for dependency resolution)
     * @return The updated session, or null if session not found
     */
    fun completeStep(
        sessionId: String,
        stepId: String,
        verificationSessionId: String,
        status: SessionStatus,
        result: Map<String, String>?,
        orchestration: OrchestrationDefinition
    ): OrchestrationSession? {
        val session = getSession(sessionId)
        if (session == null) {
            logger.warn { "Orchestration session $sessionId not found for step completion" }
            return null
        }

        if (session.status != OrchestrationStatus.IN_PROGRESS) {
            logger.warn { "Cannot complete step on orchestration $sessionId with status ${session.status}" }
            return session
        }

        val stepResult = StepResult(
            verificationSessionId = verificationSessionId,
            status = status.name.lowercase(),
            completedAt = System.currentTimeMillis(),
            result = result
        )

        val updatedCompleted = session.completedSteps + (stepId to stepResult)

        // Check completion conditions
        val allComplete = orchestration.steps.all { it.id in updatedCompleted }
        val anyFailed = updatedCompleted.values.any { it.status == "failed" }

        // Find next eligible step if not done
        val nextStep = if (!allComplete && !anyFailed) {
            findNextEligibleStep(orchestration, updatedCompleted)
        } else null

        val newStatus = when {
            anyFailed -> OrchestrationStatus.FAILED
            allComplete -> OrchestrationStatus.COMPLETED
            else -> OrchestrationStatus.IN_PROGRESS
        }

        val updatedSession = session.copy(
            completedSteps = updatedCompleted,
            currentStepId = nextStep?.id,
            status = newStatus
        )

        sessionPersistence[sessionId] = updatedSession

        logger.info {
            "Orchestration $sessionId step $stepId completed (${status.name}), " +
                    "status: $newStatus" +
                    (nextStep?.let { ", next: ${it.id}" } ?: ", no more steps")
        }

        return updatedSession
    }

    /**
     * Check if a session exists.
     *
     * @param sessionId The session ID
     * @return true if the session exists
     */
    fun sessionExists(sessionId: String): Boolean {
        return sessionPersistence.contains(sessionId)
    }

    /**
     * Delete an orchestration session.
     *
     * @param sessionId The session ID to delete
     */
    fun deleteSession(sessionId: String) {
        sessionPersistence.remove(sessionId)
        logger.debug { "Deleted orchestration session $sessionId" }
    }

    /**
     * Find the next eligible step that can be executed.
     *
     * A step is eligible if:
     * - It hasn't been completed yet
     * - All its dependencies have been completed
     *
     * @param orchestration The orchestration definition
     * @param completedSteps Map of completed step IDs to results
     * @return The next eligible step, or null if none available
     */
    private fun findNextEligibleStep(
        orchestration: OrchestrationDefinition,
        completedSteps: Map<String, StepResult>
    ): OrchestrationStep? {
        return orchestration.steps.firstOrNull { step ->
            step.id !in completedSteps &&
                    step.dependsOn.all { depId -> depId in completedSteps }
        }
    }

    /**
     * Validate that the orchestration has no circular dependencies.
     *
     * Uses depth-first search to detect cycles in the dependency graph.
     *
     * @param orchestration The orchestration to validate
     * @throws IllegalArgumentException if circular dependencies are detected
     */
    private fun validateDependencies(orchestration: OrchestrationDefinition) {
        val stepIds = orchestration.steps.map { it.id }.toSet()

        // Check all dependencies reference valid steps
        orchestration.steps.forEach { step ->
            step.dependsOn.forEach { depId ->
                require(depId in stepIds) {
                    "Step ${step.id} depends on unknown step $depId"
                }
            }
        }

        // Check for cycles using DFS
        val visited = mutableSetOf<String>()
        val inStack = mutableSetOf<String>()

        fun hasCycle(stepId: String): Boolean {
            if (stepId in inStack) return true
            if (stepId in visited) return false

            visited.add(stepId)
            inStack.add(stepId)

            val step = orchestration.steps.find { it.id == stepId }!!
            for (depId in step.dependsOn) {
                if (hasCycle(depId)) return true
            }

            inStack.remove(stepId)
            return false
        }

        orchestration.steps.forEach { step ->
            if (hasCycle(step.id)) {
                throw IllegalArgumentException("Circular dependency detected involving step ${step.id}")
            }
        }
    }
}
