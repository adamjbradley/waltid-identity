package id.walt.verifyapi.orchestration

import kotlinx.serialization.Serializable

/**
 * A step in an orchestration flow.
 * Each step defines a verification template to execute and conditions for progression.
 */
@Serializable
data class OrchestrationStep(
    /** Unique identifier for this step within the orchestration */
    val id: String,
    /** Step type: "identity" for credential verification, "payment" for payment auth */
    val type: String = "identity",
    /** Name of the verification template to execute */
    val template: String,
    /** Human-readable name for the step */
    val name: String? = null,
    /** List of step IDs that must complete before this step can run */
    val dependsOn: List<String> = emptyList(),
    /** Condition to evaluate for advancing to the next step */
    val condition: StepCondition? = null,
    /** ID of the next step (if not using conditions) */
    val nextStep: String? = null,
    /** Whether this is the final step (default: determined by nextStep being null) */
    val terminal: Boolean = false
)

/**
 * Condition for advancing to the next step.
 */
@Serializable
data class StepCondition(
    /** Type of condition: "claim_equals", "claim_exists", "always" */
    val type: String,
    /** Claim path to evaluate */
    val claim: String? = null,
    /** Expected value for "claim_equals" condition */
    val value: String? = null,
    /** Step to advance to if condition is true */
    val onTrue: String? = null,
    /** Step to advance to if condition is false */
    val onFalse: String? = null
)

/**
 * Actions to perform when orchestration completes.
 */
@Serializable
data class OnComplete(
    /** Webhook URL to notify on completion */
    val webhook: String? = null,
    /** URL to redirect the user to on completion */
    val redirect: String? = null,
    /** Data to include in the completion webhook */
    val includeData: List<String>? = null
)

/**
 * Complete orchestration definition.
 *
 * Example orchestration for identity + payment flow:
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
@Serializable
data class OrchestrationDefinition(
    /** Unique identifier */
    val id: String,
    /** Name of the orchestration */
    val name: String,
    /** Ordered list of steps */
    val steps: List<OrchestrationStep>,
    /** Actions to perform on completion */
    val onComplete: OnComplete? = null
)

/**
 * Status of an orchestration session.
 */
@Serializable
enum class OrchestrationStatus {
    /** Session created but not yet started */
    PENDING,
    /** Orchestration is in progress, waiting for current step to complete */
    IN_PROGRESS,
    /** All steps completed successfully */
    COMPLETED,
    /** One or more steps failed */
    FAILED,
    /** Session expired before completion */
    EXPIRED
}

/**
 * Runtime state of an orchestration session.
 */
@Serializable
data class OrchestrationSession(
    /** Unique session identifier in format orch_xxxxxxxxxxxx */
    val id: String,
    /** Reference to the orchestration definition */
    val orchestrationId: String,
    /** Organization that owns this session */
    val organizationId: String,
    /** ID of the current step being executed, null if completed */
    val currentStepId: String?,
    /** Current status */
    val status: OrchestrationStatus,
    /** Map of completed step IDs to their results */
    val completedSteps: Map<String, StepResult> = emptyMap(),
    /** Epoch millis when session was created */
    val createdAt: Long,
    /** Epoch millis when session expires */
    val expiresAt: Long,
    /** Custom metadata */
    val metadata: Map<String, String>? = null
)

/**
 * Result of a completed step.
 */
@Serializable
data class StepResult(
    /** ID of the verification session that completed this step */
    val verificationSessionId: String,
    /** Final status of the step (verified, failed, etc.) */
    val status: String,
    /** Epoch millis when the step completed */
    val completedAt: Long,
    /** Whether the step succeeded */
    val success: Boolean = true,
    /** Extracted claim values */
    val claims: Map<String, String>? = null,
    /** Error message if failed */
    val error: String? = null
)
