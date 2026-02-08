package id.walt.verifyapi.orchestration

import id.walt.commons.persistence.ConfiguredPersistence
import id.walt.verifyapi.session.SessionStatus
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID
import kotlin.time.Duration.Companion.minutes

private val logger = KotlinLogging.logger {}

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
 * ```json
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
     * Public method for generating orchestration session IDs (for testing).
     */
    internal fun generateOrchestrationSessionId(): String = generateSessionId()

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
     * @param claims Optional claim data from the verification
     * @param orchestration The orchestration definition (needed for dependency resolution)
     * @return The updated session, or null if session not found
     */
    fun completeStep(
        sessionId: String,
        stepId: String,
        verificationSessionId: String,
        status: SessionStatus,
        claims: Map<String, String>?,
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

        val success = status == SessionStatus.VERIFIED
        val stepResult = StepResult(
            verificationSessionId = verificationSessionId,
            status = status.name.lowercase(),
            completedAt = System.currentTimeMillis(),
            success = success,
            claims = claims,
            error = if (!success) "Verification failed with status: ${status.name}" else null
        )

        val updatedCompleted = session.completedSteps + (stepId to stepResult)

        // Check completion conditions
        val allComplete = orchestration.steps.all { it.id in updatedCompleted }
        val anyFailed = updatedCompleted.values.any { !it.success }

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
     * - All its dependencies have been completed successfully
     *
     * @param orchestration The orchestration definition
     * @param completedSteps Map of completed step IDs to results
     * @return The next eligible step, or null if none available
     */
    internal fun findNextEligibleStep(
        orchestration: OrchestrationDefinition,
        completedSteps: Map<String, StepResult>
    ): OrchestrationStep? {
        return orchestration.steps.firstOrNull { step ->
            step.id !in completedSteps &&
                    step.dependsOn.all { depId ->
                        val depResult = completedSteps[depId]
                        depResult != null && depResult.success
                    }
        }
    }

    /**
     * Find the first step that can be executed (no dependencies).
     *
     * @param orchestration The orchestration definition
     * @return The first eligible step, or null if all steps have dependencies
     */
    internal fun findFirstStep(orchestration: OrchestrationDefinition): OrchestrationStep? {
        return orchestration.steps.firstOrNull { it.dependsOn.isEmpty() }
    }

    /**
     * Result of orchestration validation.
     */
    data class ValidationResult(
        val isValid: Boolean,
        val error: String? = null
    )

    /**
     * Validate an orchestration definition.
     *
     * Checks:
     * - At least one step exists
     * - All dependency references are valid
     * - No circular dependencies exist
     * - At least one step has no dependencies (starting point)
     *
     * @param orchestration The orchestration to validate
     * @return ValidationResult with success status and error message if invalid
     */
    internal fun validateOrchestration(orchestration: OrchestrationDefinition): ValidationResult {
        // Check for empty orchestration
        if (orchestration.steps.isEmpty()) {
            return ValidationResult(false, "Orchestration must have at least one step")
        }

        val stepIds = orchestration.steps.map { it.id }.toSet()

        // Check all dependencies reference valid steps
        for (step in orchestration.steps) {
            for (depId in step.dependsOn) {
                if (depId !in stepIds) {
                    return ValidationResult(false, "Step ${step.id} depends on unknown step $depId")
                }
                if (depId == step.id) {
                    return ValidationResult(false, "Step ${step.id} cannot depend on itself (self-reference)")
                }
            }
        }

        // Check for at least one step without dependencies
        val hasStartingStep = orchestration.steps.any { it.dependsOn.isEmpty() }
        if (!hasStartingStep) {
            return ValidationResult(false, "Orchestration has no step without dependencies (no starting point)")
        }

        // Check for circular dependencies using DFS
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

        for (step in orchestration.steps) {
            if (hasCycle(step.id)) {
                return ValidationResult(false, "Circular dependency detected involving step ${step.id}")
            }
        }

        return ValidationResult(true)
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
