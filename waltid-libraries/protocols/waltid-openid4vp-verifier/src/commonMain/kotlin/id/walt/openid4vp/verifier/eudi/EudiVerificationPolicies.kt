package id.walt.openid4vp.verifier.eudi

import id.walt.openid4vp.verifier.data.Verification2Session.DefinedVerificationPolicies
import id.walt.policies2.vc.VCPolicyList
import id.walt.policies2.vc.policies.CredentialSignaturePolicy
import id.walt.policies2.vp.policies.VPPolicyList

/**
 * Default verification policies for EUDI (European Digital Identity) credentials.
 *
 * Provides pre-configured policy sets for common EUDI credential types:
 * - PID (Personal Identification Data)
 * - mDL (Mobile Driving License)
 *
 * These policies can be used as defaults or customized for specific verification needs.
 *
 * TODO: The defaultPidPolicies() and defaultMdlPolicies() methods currently have similar
 * implementations but are kept separate intentionally. In future iterations, these will
 * diverge as we add credential-type-specific policies such as:
 * - Issuer trust verification (different trusted issuers per credential type)
 * - Validity period checks (different requirements for PID vs mDL)
 * - Format-specific validation (mDL uses mso_mdoc, PID may use SD-JWT)
 */
object EudiVerificationPolicies {

    /**
     * Default verification policies for PID credentials.
     *
     * Includes:
     * - Credential signature verification
     *
     * @return DefinedVerificationPolicies configured for PID verification
     */
    fun defaultPidPolicies(): DefinedVerificationPolicies {
        return DefinedVerificationPolicies(
            vp_policies = VPPolicyList(
                jwtVcJson = emptyList(),
                dcSdJwt = emptyList(),
                msoMdoc = emptyList()
            ),
            vc_policies = VCPolicyList(
                listOf(
                    CredentialSignaturePolicy()
                )
            )
        )
    }

    /**
     * Default verification policies for mDL (Mobile Driving License) credentials.
     *
     * Includes:
     * - Credential signature verification
     *
     * @return DefinedVerificationPolicies configured for mDL verification
     */
    fun defaultMdlPolicies(): DefinedVerificationPolicies {
        return DefinedVerificationPolicies(
            vp_policies = VPPolicyList(
                jwtVcJson = emptyList(),
                dcSdJwt = emptyList(),
                msoMdoc = emptyList()
            ),
            vc_policies = VCPolicyList(
                listOf(
                    CredentialSignaturePolicy()
                )
            )
        )
    }
}
