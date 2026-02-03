package id.walt.openid4vp.verifier.eudi

import id.walt.policies2.vc.policies.CredentialSignaturePolicy
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class EudiVerificationPoliciesTest {

    @Test
    fun `default PID policies include signature verification`() {
        val policies = EudiVerificationPolicies.defaultPidPolicies()

        assertNotNull(policies)
        val vcPolicies = policies.vc_policies
        assertNotNull(vcPolicies, "vc_policies should not be null")
        assertTrue(vcPolicies.policies.isNotEmpty(), "vc_policies should contain at least one policy")
        assertTrue(
            vcPolicies.policies.first() is CredentialSignaturePolicy,
            "First policy should be CredentialSignaturePolicy"
        )
    }

    @Test
    fun `default mDL policies include signature verification`() {
        val policies = EudiVerificationPolicies.defaultMdlPolicies()

        assertNotNull(policies)
        val vcPolicies = policies.vc_policies
        assertNotNull(vcPolicies, "vc_policies should not be null")
        assertTrue(vcPolicies.policies.isNotEmpty(), "vc_policies should contain at least one policy")
        assertTrue(
            vcPolicies.policies.first() is CredentialSignaturePolicy,
            "First policy should be CredentialSignaturePolicy"
        )
    }
}
