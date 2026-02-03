package id.walt.openid4vp.verifier.eudi

import id.walt.dcql.models.CredentialFormat
import id.walt.openid4vp.verifier.eudi.EudiSessionSetupBuilder
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Integration test for EUDI PID mDoc verification flow.
 *
 * Tests session setup using EudiSessionSetupBuilder to verify
 * DCQL query construction for PID mDoc credentials.
 */
class EudiPidMdocVerifier2IntegrationTest {

    @Test
    fun `verify PID mDoc cross-device session setup with birth_date claim`() = runTest {
        // Create verification session setup using the builder
        val setup = EudiSessionSetupBuilder.pidMdocCrossDevice(
            claims = listOf("birth_date")
        )

        // Verify session setup structure
        assertNotNull(setup.core.dcqlQuery, "DCQL query should not be null")
        assertNotNull(setup.core.dcqlQuery.credentials, "Credentials list should not be null")
        assertEquals(1, setup.core.dcqlQuery.credentials.size, "Should have exactly one credential query")

        // Verify credential query
        val credentialQuery = setup.core.dcqlQuery.credentials.first()
        assertEquals("pid_mdoc", credentialQuery.id, "Credential ID should be 'pid_mdoc'")
        assertEquals(CredentialFormat.MSO_MDOC, credentialQuery.format, "Format should be MSO_MDOC")

        // Verify claims
        assertNotNull(credentialQuery.claims, "Claims should not be null")
        assertEquals(1, credentialQuery.claims!!.size, "Should have exactly one claim")
        val claimPath = credentialQuery.claims!!.first().path
        assertEquals(listOf("eu.europa.ec.eudi.pid.1", "birth_date"), claimPath, "Claim path should include namespace and claim name")
    }

    @Test
    fun `verify PID mDoc cross-device session setup with multiple claims`() = runTest {
        // Create verification session setup with multiple claims
        val setup = EudiSessionSetupBuilder.pidMdocCrossDevice(
            claims = listOf("family_name", "given_name", "birth_date", "nationality")
        )

        // Verify session setup structure
        val credentialQuery = setup.core.dcqlQuery.credentials.first()
        assertEquals("pid_mdoc", credentialQuery.id)
        assertEquals(CredentialFormat.MSO_MDOC, credentialQuery.format)

        // Verify all claims are included
        val claims = credentialQuery.claims!!
        assertEquals(4, claims.size, "Should have four claims")

        // Verify each claim has correct namespace prefix
        val expectedClaims = listOf("family_name", "given_name", "birth_date", "nationality")
        claims.forEachIndexed { index, claim ->
            assertEquals("eu.europa.ec.eudi.pid.1", claim.path.first(), "First path element should be PID namespace")
            assertEquals(expectedClaims[index], claim.path.last(), "Claim name should match")
        }
    }

    @Test
    fun `verify PID mDoc session setup includes default policies`() = runTest {
        // Create verification session setup
        val setup = EudiSessionSetupBuilder.pidMdocCrossDevice(
            claims = listOf("birth_date")
        )

        // Verify policies are included (default policies from EudiVerificationPolicies)
        assertNotNull(setup.core.policies, "Policies should not be null")
        assertNotNull(setup.core.policies.vc_policies, "VC policies should not be null")
    }

    @Test
    fun `verify PID mDoc same-device session setup`() = runTest {
        // Create same-device verification session setup
        val setup = EudiSessionSetupBuilder.pidMdocSameDevice(
            claims = listOf("family_name", "birth_date"),
            walletUrl = "eudi-wallet://authorize",
            successRedirectUri = "https://verifier.example.com/success",
            errorRedirectUri = "https://verifier.example.com/error"
        )

        // Verify session setup structure
        assertEquals("pid_mdoc", setup.core.dcqlQuery.credentials.first().id)
        assertEquals(CredentialFormat.MSO_MDOC, setup.core.dcqlQuery.credentials.first().format)

        // Verify URL config
        assertEquals("eudi-wallet://authorize", setup.urlConfig.urlHost)

        // Verify redirects
        assertNotNull(setup.redirects)
        assertEquals("https://verifier.example.com/success", setup.redirects.successRedirectUri)
        assertEquals("https://verifier.example.com/error", setup.redirects.errorRedirectUri)
    }
}
