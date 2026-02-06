package id.walt.pwa

import id.walt.issuer.psp.FundingSourceType
import id.walt.issuer.psp.MockPspAdapter
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for MockPspAdapter implementation.
 */
class MockPspAdapterTest {

    private val adapter = MockPspAdapter()

    @Test
    fun testResolveFundingSources() = runTest {
        val sources = adapter.resolveFundingSources("test-user", null)

        assertTrue(sources.isNotEmpty(), "Should return at least one funding source")

        // Check we have both card and account types
        val cardSources = sources.filter { it.type == FundingSourceType.CARD }
        val accountSources = sources.filter { it.type == FundingSourceType.ACCOUNT }

        assertTrue(cardSources.isNotEmpty(), "Should have at least one card source")
        assertTrue(accountSources.isNotEmpty(), "Should have at least one account source")
    }

    @Test
    fun testResolveFundingSourcesWithAttestation() = runTest {
        val sources = adapter.resolveFundingSources("test-user", "wallet-provider-issuer")

        assertTrue(sources.isNotEmpty(), "Should return funding sources regardless of attestation issuer")
    }

    @Test
    fun testValidateFundingSourceValid() = runTest {
        // Get a valid source first
        val sources = adapter.resolveFundingSources("test-user", null)
        val validId = sources.first().credentialIdentifier

        val isValid = adapter.validateFundingSource(validId)

        assertTrue(isValid, "Known funding source should be valid")
    }

    @Test
    fun testValidateFundingSourceInvalid() = runTest {
        val isValid = adapter.validateFundingSource("non-existent-id")

        assertFalse(isValid, "Unknown funding source should be invalid")
    }

    @Test
    fun testGetFundingSourceExists() = runTest {
        val sources = adapter.resolveFundingSources("test-user", null)
        val expectedId = sources.first().credentialIdentifier

        val source = adapter.getFundingSource(expectedId)

        assertNotNull(source, "Should find existing funding source")
        assertEquals(expectedId, source.credentialIdentifier)
    }

    @Test
    fun testGetFundingSourceNotExists() = runTest {
        val source = adapter.getFundingSource("non-existent-id")

        assertNull(source, "Should return null for non-existent funding source")
    }

    @Test
    fun testFundingSourcesHaveRequiredFields() = runTest {
        val sources = adapter.resolveFundingSources("test-user", null)

        sources.forEach { source ->
            assertTrue(source.credentialIdentifier.isNotBlank(), "credentialIdentifier should not be blank")
            assertNotNull(source.type, "type should not be null")

            when (source.type) {
                FundingSourceType.CARD -> {
                    assertNotNull(source.panLastFour, "Card should have panLastFour")
                    assertNotNull(source.iin, "Card should have iin")
                }
                FundingSourceType.ACCOUNT -> {
                    assertNotNull(source.ibanLastFour, "Account should have ibanLastFour")
                    assertNotNull(source.bic, "Account should have bic")
                }
                FundingSourceType.ANY -> {
                    // No specific requirements
                }
            }
        }
    }
}
