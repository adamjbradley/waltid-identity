package id.walt.openid4vp.verifier.eudi

import id.walt.openid4vp.verifier.data.CrossDeviceFlowSetup
import id.walt.openid4vp.verifier.data.SameDeviceFlowSetup
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull

class EudiSessionSetupBuilderTest {

    @Test
    fun `build cross-device PID mDoc verification session`() {
        val setup = EudiSessionSetupBuilder.pidMdocCrossDevice(
            claims = listOf("family_name", "birth_date")
        )

        assertIs<CrossDeviceFlowSetup>(setup)
        assertNotNull(setup.core.dcqlQuery)
        assertEquals(1, setup.core.dcqlQuery.credentials.size)
        assertEquals("pid_mdoc", setup.core.dcqlQuery.credentials.first().id)
    }

    @Test
    fun `build cross-device PID SD-JWT verification session`() {
        val setup = EudiSessionSetupBuilder.pidSdJwtCrossDevice(
            claims = listOf("family_name")
        )

        assertIs<CrossDeviceFlowSetup>(setup)
        assertNotNull(setup.core.dcqlQuery)
        assertEquals(1, setup.core.dcqlQuery.credentials.size)
        assertEquals("pid_sdjwt", setup.core.dcqlQuery.credentials.first().id)
    }

    @Test
    fun `build cross-device mDL verification session`() {
        val setup = EudiSessionSetupBuilder.mdlCrossDevice(
            claims = listOf("family_name", "driving_privileges")
        )

        assertIs<CrossDeviceFlowSetup>(setup)
        assertNotNull(setup.core.dcqlQuery)
        assertEquals(1, setup.core.dcqlQuery.credentials.size)
        assertEquals("mdl_mdoc", setup.core.dcqlQuery.credentials.first().id)
    }

    @Test
    fun `build same-device PID mDoc verification session`() {
        val setup = EudiSessionSetupBuilder.pidMdocSameDevice(
            claims = listOf("birth_date"),
            walletUrl = "eudi-wallet://authorize",
            successRedirectUri = "https://verifier.example.com/success",
            errorRedirectUri = "https://verifier.example.com/error"
        )

        assertIs<SameDeviceFlowSetup>(setup)
        assertNotNull(setup.core.dcqlQuery)
        assertEquals("eudi-wallet://authorize", setup.urlConfig.urlHost)
    }

    @Test
    fun `cross-device session uses default PID policies`() {
        val setup = EudiSessionSetupBuilder.pidMdocCrossDevice(
            claims = listOf("family_name")
        )

        // Verify default policies are applied
        assertNotNull(setup.core.policies)
        assertNotNull(setup.core.policies.vc_policies)
    }

    @Test
    fun `cross-device session can use custom policies`() {
        val customPolicies = EudiVerificationPolicies.defaultMdlPolicies()
        val setup = EudiSessionSetupBuilder.pidMdocCrossDevice(
            claims = listOf("family_name"),
            policies = customPolicies
        )

        assertEquals(customPolicies, setup.core.policies)
    }

    @Test
    fun `mDL cross-device session uses mDL default policies`() {
        val setup = EudiSessionSetupBuilder.mdlCrossDevice(
            claims = listOf("family_name")
        )

        // Should use mDL policies by default (currently same as PID but will diverge)
        assertNotNull(setup.core.policies)
    }
}
