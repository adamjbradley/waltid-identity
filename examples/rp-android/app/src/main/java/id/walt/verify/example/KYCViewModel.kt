package id.walt.verify.example

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import id.walt.verify.sdk.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Represents a verification step in the KYC flow.
 */
data class VerificationStep(
    val id: String,
    val title: String,
    val description: String,
    val template: String,
    val icon: String
)

/**
 * State for a single verification step.
 */
sealed class StepState {
    data object Idle : StepState()
    data object Loading : StepState()
    data class WaitingForScan(val verification: VerificationResponse) : StepState()
    data class Polling(val sessionId: String, val status: String) : StepState()
    data class Success(val result: SessionResult?) : StepState()
    data class Failed(val message: String) : StepState()
}

/**
 * ViewModel for the KYC onboarding flow.
 *
 * Manages multi-step verification using the walt.id Verify SDK.
 * Each step uses a different verification template to collect
 * different identity claims.
 */
class KYCViewModel : ViewModel() {

    // Initialize the Verify client
    // In production, inject this via dependency injection
    private val verifyClient = VerifyClient(
        VerifyConfig(
            apiKey = BuildConfig.VERIFY_API_KEY,
            baseUrl = BuildConfig.VERIFY_API_URL
        )
    )

    /**
     * The verification steps in the KYC flow.
     * Each step uses a different template to collect specific claims.
     */
    val verificationSteps = listOf(
        VerificationStep(
            id = "identity",
            title = "Identity Verification",
            description = "Verify your basic identity information using your government-issued ID credential.",
            template = "kyc-basic",
            icon = "person"
        ),
        VerificationStep(
            id = "document",
            title = "Document Verification",
            description = "Share your driver's license or national ID document.",
            template = "document-check",
            icon = "badge"
        ),
        VerificationStep(
            id = "address",
            title = "Address Verification",
            description = "Confirm your current residential address.",
            template = "address-proof",
            icon = "home"
        )
    )

    // State for each step
    private val _stepStates = MutableStateFlow<Map<Int, StepState>>(emptyMap())
    val stepStates: StateFlow<Map<Int, StepState>> = _stepStates.asStateFlow()

    // Collected results from all steps
    private val _collectedResults = MutableStateFlow<Map<String, SessionResult>>(emptyMap())

    /**
     * Get the current state for a specific step.
     */
    fun getStepState(stepIndex: Int): StepState {
        return _stepStates.value[stepIndex] ?: StepState.Idle
    }

    /**
     * Start verification for a specific step.
     */
    fun startVerification(stepIndex: Int) {
        val step = verificationSteps.getOrNull(stepIndex) ?: return

        viewModelScope.launch {
            updateStepState(stepIndex, StepState.Loading)

            try {
                // Create verification request
                val request = VerificationRequest(
                    template = step.template,
                    responseMode = "answers",
                    metadata = mapOf(
                        "step" to step.id,
                        "flow" to "kyc-onboarding"
                    )
                )

                // Start the verification session
                val verification = verifyClient.verifyIdentity(request)
                updateStepState(stepIndex, StepState.WaitingForScan(verification))

            } catch (e: VerifyException) {
                updateStepState(stepIndex, StepState.Failed(
                    "Failed to start verification: ${e.message}"
                ))
            } catch (e: Exception) {
                updateStepState(stepIndex, StepState.Failed(
                    "Unexpected error: ${e.message}"
                ))
            }
        }
    }

    /**
     * Start polling for verification completion.
     */
    fun startPolling(stepIndex: Int, sessionId: String) {
        val step = verificationSteps.getOrNull(stepIndex) ?: return

        viewModelScope.launch {
            try {
                // Poll with status updates
                val result = verifyClient.pollSessionWithUpdates(
                    sessionId = sessionId,
                    intervalMs = 2000,
                    timeoutMs = 120000
                ) { status ->
                    updateStepState(stepIndex, StepState.Polling(sessionId, status.status))
                }

                when {
                    result.isVerified -> {
                        // Store the result
                        _collectedResults.update { current ->
                            current + (step.id to (result.result ?: SessionResult(null, null)))
                        }
                        updateStepState(stepIndex, StepState.Success(result.result))
                    }
                    result.isFailed -> {
                        updateStepState(stepIndex, StepState.Failed("Verification failed"))
                    }
                    result.isExpired -> {
                        updateStepState(stepIndex, StepState.Failed("Session expired"))
                    }
                }

            } catch (e: PollingTimeoutException) {
                updateStepState(stepIndex, StepState.Failed(
                    "Verification timed out. Please try again."
                ))
            } catch (e: VerifyException) {
                updateStepState(stepIndex, StepState.Failed(
                    "Verification error: ${e.message}"
                ))
            } catch (e: Exception) {
                updateStepState(stepIndex, StepState.Failed(
                    "Unexpected error: ${e.message}"
                ))
            }
        }
    }

    /**
     * Retry verification for a step.
     */
    fun retryStep(stepIndex: Int) {
        updateStepState(stepIndex, StepState.Idle)
        startVerification(stepIndex)
    }

    /**
     * Reset the entire flow.
     */
    fun resetFlow() {
        _stepStates.update { emptyMap() }
        _collectedResults.update { emptyMap() }
    }

    /**
     * Get all verified data from completed steps.
     */
    fun getVerifiedData(): Map<String, Map<String, String>> {
        return _collectedResults.value.mapValues { (_, result) ->
            result.answers ?: emptyMap()
        }
    }

    private fun updateStepState(stepIndex: Int, state: StepState) {
        _stepStates.update { current ->
            current + (stepIndex to state)
        }
    }

    override fun onCleared() {
        super.onCleared()
        verifyClient.close()
    }
}
