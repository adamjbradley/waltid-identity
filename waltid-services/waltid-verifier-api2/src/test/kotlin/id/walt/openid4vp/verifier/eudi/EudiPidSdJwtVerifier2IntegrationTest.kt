package id.walt.openid4vp.verifier.eudi

import id.walt.dcql.models.CredentialFormat
import id.walt.openid4vp.verifier.eudi.EudiSessionSetupBuilder
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Integration test for EUDI PID SD-JWT verification flow.
 *
 * Tests session setup using EudiSessionSetupBuilder to verify
 * DCQL query construction for PID SD-JWT credentials (dc+sd-jwt format).
 */
class EudiPidSdJwtVerifier2IntegrationTest {

    @Test
    fun `verify PID SD-JWT cross-device session setup with family_name claim`() = runTest {
        // Create verification session setup using the builder
        val setup = EudiSessionSetupBuilder.pidSdJwtCrossDevice(
            claims = listOf("family_name")
        )

        // Verify session setup structure
        assertNotNull(setup.core.dcqlQuery, "DCQL query should not be null")
        assertNotNull(setup.core.dcqlQuery.credentials, "Credentials list should not be null")
        assertEquals(1, setup.core.dcqlQuery.credentials.size, "Should have exactly one credential query")

        // Verify credential query
        val credentialQuery = setup.core.dcqlQuery.credentials.first()
        assertEquals("pid_sdjwt", credentialQuery.id, "Credential ID should be 'pid_sdjwt'")
        assertEquals(CredentialFormat.DC_SD_JWT, credentialQuery.format, "Format should be DC_SD_JWT")

        // Verify claims (SD-JWT claims don't have namespace prefix)
        assertNotNull(credentialQuery.claims, "Claims should not be null")
        assertEquals(1, credentialQuery.claims!!.size, "Should have exactly one claim")
        val claimPath = credentialQuery.claims!!.first().path
        assertEquals(listOf("family_name"), claimPath, "Claim path should be just the claim name for SD-JWT")
    }

    @Test
    fun `verify PID SD-JWT cross-device session setup with multiple claims`() = runTest {
        // Create verification session setup with multiple claims
        val setup = EudiSessionSetupBuilder.pidSdJwtCrossDevice(
            claims = listOf("family_name", "given_name", "birth_date", "nationality")
        )

        // Verify session setup structure
        val credentialQuery = setup.core.dcqlQuery.credentials.first()
        assertEquals("pid_sdjwt", credentialQuery.id)
        assertEquals(CredentialFormat.DC_SD_JWT, credentialQuery.format)

        // Verify all claims are included
        val claims = credentialQuery.claims!!
        assertEquals(4, claims.size, "Should have four claims")

        // Verify each claim has correct path (no namespace prefix for SD-JWT)
        val expectedClaims = listOf("family_name", "given_name", "birth_date", "nationality")
        claims.forEachIndexed { index, claim ->
            assertEquals(1, claim.path.size, "SD-JWT claim path should have single element")
            assertEquals(expectedClaims[index], claim.path.first(), "Claim name should match")
        }
    }

    @Test
    fun `verify PID SD-JWT session setup includes default policies`() = runTest {
        // Create verification session setup
        val setup = EudiSessionSetupBuilder.pidSdJwtCrossDevice(
            claims = listOf("family_name")
        )

        // Verify policies are included (default policies from EudiVerificationPolicies)
        assertNotNull(setup.core.policies, "Policies should not be null")
        assertNotNull(setup.core.policies.vc_policies, "VC policies should not be null")
    }

    @Test
    fun `verify PID SD-JWT same-device session setup`() = runTest {
        // Create same-device verification session setup
        val setup = EudiSessionSetupBuilder.pidSdJwtSameDevice(
            claims = listOf("family_name", "given_name"),
            walletUrl = "eudi-wallet://authorize",
            successRedirectUri = "https://verifier.example.com/success",
            errorRedirectUri = "https://verifier.example.com/error"
        )

        // Verify session setup structure
        assertEquals("pid_sdjwt", setup.core.dcqlQuery.credentials.first().id)
        assertEquals(CredentialFormat.DC_SD_JWT, setup.core.dcqlQuery.credentials.first().format)

        // Verify URL config
        assertEquals("eudi-wallet://authorize", setup.urlConfig.urlHost)

        // Verify redirects
        assertNotNull(setup.redirects)
        assertEquals("https://verifier.example.com/success", setup.redirects.successRedirectUri)
        assertEquals("https://verifier.example.com/error", setup.redirects.errorRedirectUri)
    }

    @Test
    fun `verify PID SD-JWT format differs from mDoc in claim paths`() = runTest {
        // SD-JWT format uses simple claim names without namespace
        val sdJwtSetup = EudiSessionSetupBuilder.pidSdJwtCrossDevice(
            claims = listOf("birth_date")
        )

        // mDoc format uses namespaced claim paths
        val mdocSetup = EudiSessionSetupBuilder.pidMdocCrossDevice(
            claims = listOf("birth_date")
        )

        // SD-JWT: path is ["birth_date"]
        val sdJwtClaimPath = sdJwtSetup.core.dcqlQuery.credentials.first().claims!!.first().path
        assertEquals(1, sdJwtClaimPath.size, "SD-JWT claim path should have 1 element")
        assertEquals("birth_date", sdJwtClaimPath.first())

        // mDoc: path is ["eu.europa.ec.eudi.pid.1", "birth_date"]
        val mdocClaimPath = mdocSetup.core.dcqlQuery.credentials.first().claims!!.first().path
        assertEquals(2, mdocClaimPath.size, "mDoc claim path should have 2 elements")
        assertEquals("eu.europa.ec.eudi.pid.1", mdocClaimPath.first())
        assertEquals("birth_date", mdocClaimPath.last())
    }
}
