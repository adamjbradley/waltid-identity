package id.walt.verifyapi

import id.walt.verifyapi.orchestration.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Unit tests for orchestration data types and serialization.
 *
 * These tests verify that orchestration types can be correctly serialized
 * and deserialized for storage and API communication.
 */
class OrchestrationTypesTest {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = true
    }

    // ============================================================
    // OrchestrationStep Tests
    // ============================================================

    @Test
    fun `test OrchestrationStep serialization with defaults`() {
        val step = OrchestrationStep(
            id = "step1",
            template = "kyc-basic"
        )

        val serialized = json.encodeToString(step)
        assertTrue(serialized.contains("\"id\""), "Should contain id field")
        assertTrue(serialized.contains("\"step1\""), "Should contain step ID value")
        assertTrue(serialized.contains("\"template\""), "Should contain template field")

        val deserialized = json.decodeFromString<OrchestrationStep>(serialized)
        assertEquals(step.id, deserialized.id)
        assertEquals(step.template, deserialized.template)
        assertEquals("identity", deserialized.type, "Default type should be 'identity'")
        assertTrue(deserialized.dependsOn.isEmpty(), "Default dependsOn should be empty")
    }

    @Test
    fun `test OrchestrationStep with dependencies`() {
        val step = OrchestrationStep(
            id = "payment",
            type = "payment",
            template = "pwa-auth",
            name = "Payment Authorization",
            dependsOn = listOf("identity", "address")
        )

        val serialized = json.encodeToString(step)
        val deserialized = json.decodeFromString<OrchestrationStep>(serialized)

        assertEquals("payment", deserialized.id)
        assertEquals("payment", deserialized.type)
        assertEquals("pwa-auth", deserialized.template)
        assertEquals("Payment Authorization", deserialized.name)
        assertEquals(2, deserialized.dependsOn.size)
        assertTrue(deserialized.dependsOn.contains("identity"))
        assertTrue(deserialized.dependsOn.contains("address"))
    }

    @Test
    fun `test OrchestrationStep with condition`() {
        val step = OrchestrationStep(
            id = "conditional",
            template = "template",
            condition = StepCondition(
                type = "claim_equals",
                claim = "age_over_18",
                value = "true",
                onTrue = "next_step",
                onFalse = "reject"
            )
        )

        val serialized = json.encodeToString(step)
        val deserialized = json.decodeFromString<OrchestrationStep>(serialized)

        assertNotNull(deserialized.condition)
        assertEquals("claim_equals", deserialized.condition?.type)
        assertEquals("age_over_18", deserialized.condition?.claim)
        assertEquals("true", deserialized.condition?.value)
    }

    // ============================================================
    // StepCondition Tests
    // ============================================================

    @Test
    fun `test StepCondition types`() {
        val claimEquals = StepCondition(
            type = "claim_equals",
            claim = "country",
            value = "US"
        )
        assertEquals("claim_equals", claimEquals.type)

        val claimExists = StepCondition(
            type = "claim_exists",
            claim = "email"
        )
        assertEquals("claim_exists", claimExists.type)

        val always = StepCondition(
            type = "always",
            onTrue = "next"
        )
        assertEquals("always", always.type)
    }

    // ============================================================
    // OrchestrationDefinition Tests
    // ============================================================

    @Test
    fun `test OrchestrationDefinition serialization`() {
        val orchestration = OrchestrationDefinition(
            id = "kyc-flow",
            name = "KYC Verification Flow",
            steps = listOf(
                OrchestrationStep(id = "identity", template = "kyc-basic"),
                OrchestrationStep(id = "address", template = "address-verify", dependsOn = listOf("identity"))
            )
        )

        val serialized = json.encodeToString(orchestration)
        val deserialized = json.decodeFromString<OrchestrationDefinition>(serialized)

        assertEquals("kyc-flow", deserialized.id)
        assertEquals("KYC Verification Flow", deserialized.name)
        assertEquals(2, deserialized.steps.size)
    }

    @Test
    fun `test OrchestrationDefinition with onComplete`() {
        val orchestration = OrchestrationDefinition(
            id = "test",
            name = "Test",
            steps = listOf(OrchestrationStep(id = "step1", template = "t")),
            onComplete = OnComplete(
                webhook = "https://example.com/webhook",
                redirect = "https://example.com/done",
                includeData = listOf("claims", "metadata")
            )
        )

        val serialized = json.encodeToString(orchestration)
        val deserialized = json.decodeFromString<OrchestrationDefinition>(serialized)

        assertNotNull(deserialized.onComplete)
        assertEquals("https://example.com/webhook", deserialized.onComplete?.webhook)
        assertEquals("https://example.com/done", deserialized.onComplete?.redirect)
        assertEquals(2, deserialized.onComplete?.includeData?.size)
    }

    // ============================================================
    // OrchestrationSession Tests
    // ============================================================

    @Test
    fun `test OrchestrationSession serialization`() {
        val session = OrchestrationSession(
            id = "orch_abc123def456",
            orchestrationId = "kyc-flow",
            organizationId = "org-123",
            currentStepId = "step1",
            status = OrchestrationStatus.IN_PROGRESS,
            createdAt = 1704067200000,
            expiresAt = 1704069000000,
            metadata = mapOf("userId" to "user-123")
        )

        val serialized = json.encodeToString(session)
        val deserialized = json.decodeFromString<OrchestrationSession>(serialized)

        assertEquals("orch_abc123def456", deserialized.id)
        assertEquals("kyc-flow", deserialized.orchestrationId)
        assertEquals(OrchestrationStatus.IN_PROGRESS, deserialized.status)
        assertEquals("step1", deserialized.currentStepId)
        assertEquals("user-123", deserialized.metadata?.get("userId"))
    }

    @Test
    fun `test OrchestrationSession with completed steps`() {
        val session = OrchestrationSession(
            id = "orch_test",
            orchestrationId = "flow",
            organizationId = "org",
            currentStepId = "step2",
            status = OrchestrationStatus.IN_PROGRESS,
            completedSteps = mapOf(
                "step1" to StepResult(
                    verificationSessionId = "vs_123",
                    status = "verified",
                    completedAt = 1704067200000,
                    success = true,
                    claims = mapOf("name" to "John Doe")
                )
            ),
            createdAt = 1704067000000,
            expiresAt = 1704070000000
        )

        val serialized = json.encodeToString(session)
        val deserialized = json.decodeFromString<OrchestrationSession>(serialized)

        assertEquals(1, deserialized.completedSteps.size)
        val stepResult = deserialized.completedSteps["step1"]
        assertNotNull(stepResult)
        assertTrue(stepResult.success)
        assertEquals("John Doe", stepResult.claims?.get("name"))
    }

    // ============================================================
    // StepResult Tests
    // ============================================================

    @Test
    fun `test StepResult success case`() {
        val result = StepResult(
            verificationSessionId = "vs_success123",
            status = "verified",
            completedAt = System.currentTimeMillis(),
            success = true,
            claims = mapOf(
                "full_name" to "Jane Smith",
                "date_of_birth" to "1990-01-15"
            )
        )

        val serialized = json.encodeToString(result)
        val deserialized = json.decodeFromString<StepResult>(serialized)

        assertTrue(deserialized.success)
        assertEquals("vs_success123", deserialized.verificationSessionId)
        assertEquals("verified", deserialized.status)
        assertEquals(null, deserialized.error)
        assertEquals("Jane Smith", deserialized.claims?.get("full_name"))
    }

    @Test
    fun `test StepResult failure case`() {
        val result = StepResult(
            verificationSessionId = "vs_fail123",
            status = "failed",
            completedAt = System.currentTimeMillis(),
            success = false,
            error = "Credential verification failed: signature invalid"
        )

        val serialized = json.encodeToString(result)
        val deserialized = json.decodeFromString<StepResult>(serialized)

        assertTrue(!deserialized.success)
        assertEquals("failed", deserialized.status)
        assertNotNull(deserialized.error)
        assertTrue(deserialized.error!!.contains("signature invalid"))
    }

    // ============================================================
    // OnComplete Tests
    // ============================================================

    @Test
    fun `test OnComplete with all fields`() {
        val onComplete = OnComplete(
            webhook = "https://api.example.com/verification-complete",
            redirect = "https://app.example.com/success",
            includeData = listOf("claims", "metadata", "session_id")
        )

        val serialized = json.encodeToString(onComplete)
        val deserialized = json.decodeFromString<OnComplete>(serialized)

        assertEquals("https://api.example.com/verification-complete", deserialized.webhook)
        assertEquals("https://app.example.com/success", deserialized.redirect)
        assertEquals(3, deserialized.includeData?.size)
    }

    @Test
    fun `test OnComplete with null fields`() {
        val onComplete = OnComplete(
            webhook = null,
            redirect = "https://example.com/done"
        )

        val serialized = json.encodeToString(onComplete)
        val deserialized = json.decodeFromString<OnComplete>(serialized)

        assertEquals(null, deserialized.webhook)
        assertEquals("https://example.com/done", deserialized.redirect)
    }

    // ============================================================
    // Full Flow JSON Example Tests
    // ============================================================

    @Test
    fun `test parsing real-world orchestration JSON`() {
        val jsonString = """
        {
            "id": "identity-then-payment",
            "name": "Identity + Payment Flow",
            "steps": [
                {
                    "id": "identity",
                    "type": "identity",
                    "template": "kyc-basic",
                    "name": "Identity Verification"
                },
                {
                    "id": "payment",
                    "type": "payment",
                    "template": "pwa-auth",
                    "name": "Payment Authorization",
                    "dependsOn": ["identity"]
                }
            ],
            "onComplete": {
                "webhook": "https://api.merchant.com/verification-done",
                "redirect": "https://checkout.merchant.com/success"
            }
        }
        """.trimIndent()

        val orchestration = json.decodeFromString<OrchestrationDefinition>(jsonString)

        assertEquals("identity-then-payment", orchestration.id)
        assertEquals("Identity + Payment Flow", orchestration.name)
        assertEquals(2, orchestration.steps.size)

        val identityStep = orchestration.steps[0]
        assertEquals("identity", identityStep.id)
        assertEquals("identity", identityStep.type)
        assertEquals("kyc-basic", identityStep.template)
        assertTrue(identityStep.dependsOn.isEmpty())

        val paymentStep = orchestration.steps[1]
        assertEquals("payment", paymentStep.id)
        assertEquals("payment", paymentStep.type)
        assertEquals(1, paymentStep.dependsOn.size)
        assertEquals("identity", paymentStep.dependsOn[0])

        assertNotNull(orchestration.onComplete)
    }
}
