package id.walt.openid4vp.verifier.eudi

import id.walt.dcql.models.CredentialFormat
import id.walt.openid4vp.verifier.eudi.EudiSessionSetupBuilder
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Integration test for EUDI mDL (Mobile Driving License) verification flow.
 *
 * Tests session setup using EudiSessionSetupBuilder to verify
 * DCQL query construction for mDL mso_mdoc credentials.
 *
 * mDL uses:
 * - Doctype: org.iso.18013.5.1.mDL
 * - Namespace: org.iso.18013.5.1
 */
class EudiMdlVerifier2IntegrationTest {

    @Test
    fun `verify mDL presentation with driving privileges`() = runTest {
        val setup = EudiSessionSetupBuilder.mdlCrossDevice(
            claims = listOf("family_name", "driving_privileges")
        )

        // Verify credential query structure
        assertNotNull(setup.core.dcqlQuery, "DCQL query should not be null")
        assertNotNull(setup.core.dcqlQuery.credentials, "Credentials list should not be null")
        assertEquals(1, setup.core.dcqlQuery.credentials.size, "Should have exactly one credential query")

        // Verify credential ID and format
        val credentialQuery = setup.core.dcqlQuery.credentials.first()
        assertEquals("mdl_mdoc", credentialQuery.id, "Credential ID should be 'mdl_mdoc'")
        assertEquals(CredentialFormat.MSO_MDOC, credentialQuery.format, "Format should be MSO_MDOC")
    }

    @Test
    fun `verify mDL cross-device session setup with single claim`() = runTest {
        // Create verification session setup using the builder
        val setup = EudiSessionSetupBuilder.mdlCrossDevice(
            claims = listOf("family_name")
        )

        // Verify session setup structure
        assertNotNull(setup.core.dcqlQuery, "DCQL query should not be null")
        assertNotNull(setup.core.dcqlQuery.credentials, "Credentials list should not be null")
        assertEquals(1, setup.core.dcqlQuery.credentials.size, "Should have exactly one credential query")

        // Verify credential query
        val credentialQuery = setup.core.dcqlQuery.credentials.first()
        assertEquals("mdl_mdoc", credentialQuery.id, "Credential ID should be 'mdl_mdoc'")
        assertEquals(CredentialFormat.MSO_MDOC, credentialQuery.format, "Format should be MSO_MDOC")

        // Verify claims use mDL namespace (different from PID!)
        assertNotNull(credentialQuery.claims, "Claims should not be null")
        assertEquals(1, credentialQuery.claims!!.size, "Should have exactly one claim")
        val claimPath = credentialQuery.claims!!.first().path
        assertEquals(listOf("org.iso.18013.5.1", "family_name"), claimPath, "Claim path should include mDL namespace and claim name")
    }

    @Test
    fun `verify mDL cross-device session setup with multiple claims`() = runTest {
        // Create verification session setup with multiple mDL-specific claims
        val setup = EudiSessionSetupBuilder.mdlCrossDevice(
            claims = listOf("family_name", "given_name", "birth_date", "driving_privileges", "issue_date", "expiry_date")
        )

        // Verify session setup structure
        val credentialQuery = setup.core.dcqlQuery.credentials.first()
        assertEquals("mdl_mdoc", credentialQuery.id)
        assertEquals(CredentialFormat.MSO_MDOC, credentialQuery.format)

        // Verify all claims are included
        val claims = credentialQuery.claims!!
        assertEquals(6, claims.size, "Should have six claims")

        // Verify each claim has correct mDL namespace prefix
        val expectedClaims = listOf("family_name", "given_name", "birth_date", "driving_privileges", "issue_date", "expiry_date")
        claims.forEachIndexed { index, claim ->
            assertEquals("org.iso.18013.5.1", claim.path.first(), "First path element should be mDL namespace")
            assertEquals(expectedClaims[index], claim.path.last(), "Claim name should match")
        }
    }

    @Test
    fun `verify mDL session setup includes default policies`() = runTest {
        // Create verification session setup
        val setup = EudiSessionSetupBuilder.mdlCrossDevice(
            claims = listOf("driving_privileges")
        )

        // Verify policies are included (default mDL policies from EudiVerificationPolicies)
        assertNotNull(setup.core.policies, "Policies should not be null")
        assertNotNull(setup.core.policies.vc_policies, "VC policies should not be null")
    }

    @Test
    fun `verify mDL same-device session setup`() = runTest {
        // Create same-device verification session setup
        val setup = EudiSessionSetupBuilder.mdlSameDevice(
            claims = listOf("family_name", "driving_privileges"),
            walletUrl = "eudi-wallet://authorize",
            successRedirectUri = "https://verifier.example.com/success",
            errorRedirectUri = "https://verifier.example.com/error"
        )

        // Verify session setup structure
        assertEquals("mdl_mdoc", setup.core.dcqlQuery.credentials.first().id)
        assertEquals(CredentialFormat.MSO_MDOC, setup.core.dcqlQuery.credentials.first().format)

        // Verify URL config
        assertEquals("eudi-wallet://authorize", setup.urlConfig.urlHost)

        // Verify redirects
        assertNotNull(setup.redirects)
        assertEquals("https://verifier.example.com/success", setup.redirects.successRedirectUri)
        assertEquals("https://verifier.example.com/error", setup.redirects.errorRedirectUri)
    }

    @Test
    fun `verify mDL namespace differs from PID namespace`() = runTest {
        // Create mDL session
        val mdlSetup = EudiSessionSetupBuilder.mdlCrossDevice(
            claims = listOf("family_name")
        )

        // Verify mDL uses ISO namespace (org.iso.18013.5.1), not EUDI PID namespace
        val mdlClaim = mdlSetup.core.dcqlQuery.credentials.first().claims!!.first()
        assertEquals("org.iso.18013.5.1", mdlClaim.path.first(), "mDL should use ISO namespace")

        // Note: PID uses "eu.europa.ec.eudi.pid.1" namespace
        // This test ensures we're not accidentally using PID namespace for mDL
    }
}
