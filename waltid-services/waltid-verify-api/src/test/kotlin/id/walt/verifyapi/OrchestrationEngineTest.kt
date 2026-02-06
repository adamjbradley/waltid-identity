package id.walt.verifyapi

import id.walt.verifyapi.orchestration.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Unit tests for OrchestrationEngine dependency validation and step resolution.
 *
 * These tests verify the orchestration logic without requiring persistence (Valkey).
 * Tests use the OrchestrationEngine's validation methods which throw exceptions
 * for invalid orchestration definitions.
 */
class OrchestrationEngineTest {

    // ============================================================
    // Dependency Validation Tests
    // ============================================================

    @Test
    fun `test valid linear orchestration with dependencies`() {
        val orchestration = OrchestrationDefinition(
            id = "linear-flow",
            name = "Linear Flow",
            steps = listOf(
                OrchestrationStep(id = "step1", template = "template1"),
                OrchestrationStep(id = "step2", template = "template2", dependsOn = listOf("step1")),
                OrchestrationStep(id = "step3", template = "template3", dependsOn = listOf("step2"))
            )
        )

        // Should not throw - validates structure
        val result = OrchestrationEngine.validateOrchestration(orchestration)
        assertTrue(result.isValid, "Linear orchestration should be valid")
    }

    @Test
    fun `test valid parallel orchestration with shared dependency`() {
        val orchestration = OrchestrationDefinition(
            id = "parallel-flow",
            name = "Parallel Flow",
            steps = listOf(
                OrchestrationStep(id = "identity", template = "kyc-basic"),
                OrchestrationStep(id = "address", template = "address-check", dependsOn = listOf("identity")),
                OrchestrationStep(id = "payment", template = "pwa-auth", dependsOn = listOf("identity"))
            )
        )

        val result = OrchestrationEngine.validateOrchestration(orchestration)
        assertTrue(result.isValid, "Parallel orchestration should be valid")
    }

    @Test
    fun `test orchestration with multiple dependencies converging`() {
        val orchestration = OrchestrationDefinition(
            id = "converging-flow",
            name = "Converging Flow",
            steps = listOf(
                OrchestrationStep(id = "step1", template = "template1"),
                OrchestrationStep(id = "step2", template = "template2"),
                OrchestrationStep(id = "final", template = "final", dependsOn = listOf("step1", "step2"))
            )
        )

        val result = OrchestrationEngine.validateOrchestration(orchestration)
        assertTrue(result.isValid, "Converging orchestration should be valid")
    }

    @Test
    fun `test circular dependency detection - direct cycle`() {
        val orchestration = OrchestrationDefinition(
            id = "circular",
            name = "Circular",
            steps = listOf(
                OrchestrationStep(id = "step1", template = "template1", dependsOn = listOf("step2")),
                OrchestrationStep(id = "step2", template = "template2", dependsOn = listOf("step1"))
            )
        )

        val result = OrchestrationEngine.validateOrchestration(orchestration)
        assertTrue(!result.isValid, "Direct circular dependency should be invalid")
        // Note: With all steps having dependencies, "no starting step" is detected first
        // Both errors indicate the orchestration is invalid due to circular dependency pattern
        assertTrue(
            result.error?.contains("Circular") == true || result.error?.contains("no step without dependencies") == true,
            "Error should mention circular dependency or no starting step"
        )
    }

    @Test
    fun `test circular dependency detection - indirect cycle`() {
        val orchestration = OrchestrationDefinition(
            id = "indirect-circular",
            name = "Indirect Circular",
            steps = listOf(
                OrchestrationStep(id = "step1", template = "template1", dependsOn = listOf("step3")),
                OrchestrationStep(id = "step2", template = "template2", dependsOn = listOf("step1")),
                OrchestrationStep(id = "step3", template = "template3", dependsOn = listOf("step2"))
            )
        )

        val result = OrchestrationEngine.validateOrchestration(orchestration)
        assertTrue(!result.isValid, "Indirect circular dependency should be invalid")
    }

    @Test
    fun `test self-referencing step dependency`() {
        val orchestration = OrchestrationDefinition(
            id = "self-ref",
            name = "Self Reference",
            steps = listOf(
                OrchestrationStep(id = "step1", template = "template1", dependsOn = listOf("step1"))
            )
        )

        val result = OrchestrationEngine.validateOrchestration(orchestration)
        assertTrue(!result.isValid, "Self-referencing dependency should be invalid")
    }

    @Test
    fun `test unknown dependency reference`() {
        val orchestration = OrchestrationDefinition(
            id = "unknown-dep",
            name = "Unknown Dependency",
            steps = listOf(
                OrchestrationStep(id = "step1", template = "template1"),
                OrchestrationStep(id = "step2", template = "template2", dependsOn = listOf("nonexistent"))
            )
        )

        val result = OrchestrationEngine.validateOrchestration(orchestration)
        assertTrue(!result.isValid, "Unknown dependency should be invalid")
        assertTrue(result.error?.contains("unknown") == true || result.error?.contains("nonexistent") == true,
            "Error should mention unknown step")
    }

    @Test
    fun `test empty orchestration is invalid`() {
        val orchestration = OrchestrationDefinition(
            id = "empty",
            name = "Empty",
            steps = emptyList()
        )

        val result = OrchestrationEngine.validateOrchestration(orchestration)
        assertTrue(!result.isValid, "Empty orchestration should be invalid")
    }

    @Test
    fun `test orchestration must have at least one step without dependencies`() {
        val orchestration = OrchestrationDefinition(
            id = "all-deps",
            name = "All Dependencies",
            steps = listOf(
                OrchestrationStep(id = "step1", template = "template1", dependsOn = listOf("step2")),
                OrchestrationStep(id = "step2", template = "template2", dependsOn = listOf("step1"))
            )
        )

        val result = OrchestrationEngine.validateOrchestration(orchestration)
        assertTrue(!result.isValid, "Orchestration with no starting step should be invalid")
    }

    // ============================================================
    // Step Resolution Tests
    // ============================================================

    @Test
    fun `test findFirstStep returns step with no dependencies`() {
        val orchestration = OrchestrationDefinition(
            id = "test",
            name = "Test",
            steps = listOf(
                OrchestrationStep(id = "middle", template = "t", dependsOn = listOf("first")),
                OrchestrationStep(id = "first", template = "t"),
                OrchestrationStep(id = "last", template = "t", dependsOn = listOf("middle"))
            )
        )

        val firstStep = OrchestrationEngine.findFirstStep(orchestration)
        assertNotNull(firstStep, "Should find first step")
        assertEquals("first", firstStep.id, "First step should be the one with no dependencies")
    }

    @Test
    fun `test findNextEligibleStep returns correct step after completion`() {
        val orchestration = OrchestrationDefinition(
            id = "test",
            name = "Test",
            steps = listOf(
                OrchestrationStep(id = "step1", template = "t1"),
                OrchestrationStep(id = "step2", template = "t2", dependsOn = listOf("step1")),
                OrchestrationStep(id = "step3", template = "t3", dependsOn = listOf("step2"))
            )
        )

        val completedSteps = mapOf(
            "step1" to StepResult(
                verificationSessionId = "vs_123",
                status = "verified",
                completedAt = System.currentTimeMillis(),
                success = true
            )
        )

        val nextStep = OrchestrationEngine.findNextEligibleStep(orchestration, completedSteps)
        assertNotNull(nextStep, "Should find next step")
        assertEquals("step2", nextStep.id, "Next step should be step2")
    }

    @Test
    fun `test findNextEligibleStep returns null when all complete`() {
        val orchestration = OrchestrationDefinition(
            id = "test",
            name = "Test",
            steps = listOf(
                OrchestrationStep(id = "step1", template = "t1")
            )
        )

        val completedSteps = mapOf(
            "step1" to StepResult(
                verificationSessionId = "vs_123",
                status = "verified",
                completedAt = System.currentTimeMillis(),
                success = true
            )
        )

        val nextStep = OrchestrationEngine.findNextEligibleStep(orchestration, completedSteps)
        assertEquals(null, nextStep, "Should return null when all steps complete")
    }

    @Test
    fun `test findNextEligibleStep skips steps with failed dependencies`() {
        val orchestration = OrchestrationDefinition(
            id = "test",
            name = "Test",
            steps = listOf(
                OrchestrationStep(id = "step1", template = "t1"),
                OrchestrationStep(id = "step2", template = "t2", dependsOn = listOf("step1"))
            )
        )

        val completedSteps = mapOf(
            "step1" to StepResult(
                verificationSessionId = "vs_123",
                status = "failed",
                completedAt = System.currentTimeMillis(),
                success = false
            )
        )

        val nextStep = OrchestrationEngine.findNextEligibleStep(orchestration, completedSteps)
        assertEquals(null, nextStep, "Should return null when dependency failed")
    }

    @Test
    fun `test parallel steps are both eligible after shared dependency completes`() {
        val orchestration = OrchestrationDefinition(
            id = "test",
            name = "Test",
            steps = listOf(
                OrchestrationStep(id = "identity", template = "t1"),
                OrchestrationStep(id = "address", template = "t2", dependsOn = listOf("identity")),
                OrchestrationStep(id = "payment", template = "t3", dependsOn = listOf("identity"))
            )
        )

        val completedSteps = mapOf(
            "identity" to StepResult(
                verificationSessionId = "vs_123",
                status = "verified",
                completedAt = System.currentTimeMillis(),
                success = true
            )
        )

        // First eligible step
        val nextStep = OrchestrationEngine.findNextEligibleStep(orchestration, completedSteps)
        assertNotNull(nextStep, "Should find an eligible step")
        assertTrue(nextStep.id == "address" || nextStep.id == "payment",
            "Next step should be either address or payment")
    }

    // ============================================================
    // Orchestration Status Tests
    // ============================================================

    @Test
    fun `test OrchestrationStatus enum values`() {
        val statuses = OrchestrationStatus.entries
        assertEquals(5, statuses.size, "Should have 5 status values")
        assertTrue(statuses.contains(OrchestrationStatus.PENDING))
        assertTrue(statuses.contains(OrchestrationStatus.IN_PROGRESS))
        assertTrue(statuses.contains(OrchestrationStatus.COMPLETED))
        assertTrue(statuses.contains(OrchestrationStatus.FAILED))
        assertTrue(statuses.contains(OrchestrationStatus.EXPIRED))
    }

    @Test
    fun `test StepResult success flag`() {
        val successResult = StepResult(
            verificationSessionId = "vs_123",
            status = "verified",
            completedAt = System.currentTimeMillis(),
            success = true
        )
        assertTrue(successResult.success)

        val failedResult = StepResult(
            verificationSessionId = "vs_456",
            status = "failed",
            completedAt = System.currentTimeMillis(),
            success = false,
            error = "Verification failed"
        )
        assertTrue(!failedResult.success)
        assertNotNull(failedResult.error)
    }

    // ============================================================
    // Session ID Format Tests
    // ============================================================

    @Test
    fun `test orchestration session ID format`() {
        val sessionId = OrchestrationEngine.generateOrchestrationSessionId()
        assertTrue(sessionId.startsWith("orch_"), "Session ID should start with 'orch_' prefix")
        assertEquals(17, sessionId.length, "Session ID should be 17 characters (orch_ + 12 chars)")
    }

    @Test
    fun `test orchestration session IDs are unique`() {
        val id1 = OrchestrationEngine.generateOrchestrationSessionId()
        val id2 = OrchestrationEngine.generateOrchestrationSessionId()
        assertTrue(id1 != id2, "Session IDs should be unique")
    }
}
