package id.walt.openid4vp.verifier.eudi

import id.walt.dcql.models.DcqlQuery
import id.walt.openid4vp.verifier.data.CrossDeviceFlowSetup
import id.walt.openid4vp.verifier.data.GeneralFlowConfig
import id.walt.openid4vp.verifier.data.SameDeviceFlowSetup
import id.walt.openid4vp.verifier.data.UrlConfig
import id.walt.openid4vp.verifier.data.Verification2Session.DefinedVerificationPolicies
import id.walt.openid4vp.verifier.data.Verification2Session.VerificationSessionRedirects

/**
 * Builder for creating EUDI verification session setups.
 *
 * Provides convenience methods for creating verification sessions for common EUDI
 * credential types (PID, mDL) in both cross-device (QR code) and same-device
 * (deep link) flows.
 *
 * Example usage:
 * ```kotlin
 * // Cross-device PID verification (user scans QR code)
 * val setup = EudiSessionSetupBuilder.pidMdocCrossDevice(
 *     claims = listOf("family_name", "birth_date")
 * )
 *
 * // Same-device mDL verification (opens wallet app)
 * val setup = EudiSessionSetupBuilder.mdlSameDevice(
 *     claims = listOf("family_name", "driving_privileges"),
 *     walletUrl = "eudi-wallet://authorize",
 *     successRedirectUri = "https://verifier.example.com/success",
 *     errorRedirectUri = "https://verifier.example.com/error"
 * )
 * ```
 */
object EudiSessionSetupBuilder {

    /**
     * Create cross-device (QR code) verification for PID mDoc.
     *
     * @param claims List of claim names to request (e.g., "family_name", "birth_date")
     * @param policies Verification policies to apply (defaults to PID policies)
     * @return CrossDeviceFlowSetup configured for PID mDoc verification
     */
    fun pidMdocCrossDevice(
        claims: List<String>,
        policies: DefinedVerificationPolicies = EudiVerificationPolicies.defaultPidPolicies()
    ): CrossDeviceFlowSetup {
        val query = DcqlQuery(
            credentials = listOf(EudiDcqlQueryBuilder.pidMdoc(claims))
        )
        return CrossDeviceFlowSetup(
            core = GeneralFlowConfig(
                dcqlQuery = query,
                policies = policies
            )
        )
    }

    /**
     * Create cross-device (QR code) verification for PID SD-JWT.
     *
     * @param claims List of claim names to request (e.g., "family_name", "given_name")
     * @param policies Verification policies to apply (defaults to PID policies)
     * @return CrossDeviceFlowSetup configured for PID SD-JWT verification
     */
    fun pidSdJwtCrossDevice(
        claims: List<String>,
        policies: DefinedVerificationPolicies = EudiVerificationPolicies.defaultPidPolicies()
    ): CrossDeviceFlowSetup {
        val query = DcqlQuery(
            credentials = listOf(EudiDcqlQueryBuilder.pidSdJwt(claims))
        )
        return CrossDeviceFlowSetup(
            core = GeneralFlowConfig(
                dcqlQuery = query,
                policies = policies
            )
        )
    }

    /**
     * Create cross-device (QR code) verification for mDL (Mobile Driving License).
     *
     * @param claims List of claim names to request (e.g., "family_name", "driving_privileges")
     * @param policies Verification policies to apply (defaults to mDL policies)
     * @return CrossDeviceFlowSetup configured for mDL verification
     */
    fun mdlCrossDevice(
        claims: List<String>,
        policies: DefinedVerificationPolicies = EudiVerificationPolicies.defaultMdlPolicies()
    ): CrossDeviceFlowSetup {
        val query = DcqlQuery(
            credentials = listOf(EudiDcqlQueryBuilder.mdl(claims))
        )
        return CrossDeviceFlowSetup(
            core = GeneralFlowConfig(
                dcqlQuery = query,
                policies = policies
            )
        )
    }

    /**
     * Create same-device (deep link) verification for PID mDoc.
     *
     * @param claims List of claim names to request (e.g., "birth_date", "family_name")
     * @param walletUrl The wallet URL to redirect to (e.g., "eudi-wallet://authorize")
     * @param successRedirectUri URI to redirect to on successful verification
     * @param errorRedirectUri URI to redirect to on verification error
     * @param policies Verification policies to apply (defaults to PID policies)
     * @return SameDeviceFlowSetup configured for PID mDoc verification
     */
    fun pidMdocSameDevice(
        claims: List<String>,
        walletUrl: String,
        successRedirectUri: String,
        errorRedirectUri: String,
        policies: DefinedVerificationPolicies = EudiVerificationPolicies.defaultPidPolicies()
    ): SameDeviceFlowSetup {
        val query = DcqlQuery(
            credentials = listOf(EudiDcqlQueryBuilder.pidMdoc(claims))
        )
        return SameDeviceFlowSetup(
            core = GeneralFlowConfig(
                dcqlQuery = query,
                policies = policies
            ),
            urlConfig = UrlConfig(urlHost = walletUrl),
            redirects = VerificationSessionRedirects(
                successRedirectUri = successRedirectUri,
                errorRedirectUri = errorRedirectUri
            )
        )
    }

    /**
     * Create same-device (deep link) verification for PID SD-JWT.
     *
     * @param claims List of claim names to request (e.g., "family_name", "given_name")
     * @param walletUrl The wallet URL to redirect to (e.g., "eudi-wallet://authorize")
     * @param successRedirectUri URI to redirect to on successful verification
     * @param errorRedirectUri URI to redirect to on verification error
     * @param policies Verification policies to apply (defaults to PID policies)
     * @return SameDeviceFlowSetup configured for PID SD-JWT verification
     */
    fun pidSdJwtSameDevice(
        claims: List<String>,
        walletUrl: String,
        successRedirectUri: String,
        errorRedirectUri: String,
        policies: DefinedVerificationPolicies = EudiVerificationPolicies.defaultPidPolicies()
    ): SameDeviceFlowSetup {
        val query = DcqlQuery(
            credentials = listOf(EudiDcqlQueryBuilder.pidSdJwt(claims))
        )
        return SameDeviceFlowSetup(
            core = GeneralFlowConfig(
                dcqlQuery = query,
                policies = policies
            ),
            urlConfig = UrlConfig(urlHost = walletUrl),
            redirects = VerificationSessionRedirects(
                successRedirectUri = successRedirectUri,
                errorRedirectUri = errorRedirectUri
            )
        )
    }

    /**
     * Create same-device (deep link) verification for mDL (Mobile Driving License).
     *
     * @param claims List of claim names to request (e.g., "family_name", "driving_privileges")
     * @param walletUrl The wallet URL to redirect to (e.g., "eudi-wallet://authorize")
     * @param successRedirectUri URI to redirect to on successful verification
     * @param errorRedirectUri URI to redirect to on verification error
     * @param policies Verification policies to apply (defaults to mDL policies)
     * @return SameDeviceFlowSetup configured for mDL verification
     */
    fun mdlSameDevice(
        claims: List<String>,
        walletUrl: String,
        successRedirectUri: String,
        errorRedirectUri: String,
        policies: DefinedVerificationPolicies = EudiVerificationPolicies.defaultMdlPolicies()
    ): SameDeviceFlowSetup {
        val query = DcqlQuery(
            credentials = listOf(EudiDcqlQueryBuilder.mdl(claims))
        )
        return SameDeviceFlowSetup(
            core = GeneralFlowConfig(
                dcqlQuery = query,
                policies = policies
            ),
            urlConfig = UrlConfig(urlHost = walletUrl),
            redirects = VerificationSessionRedirects(
                successRedirectUri = successRedirectUri,
                errorRedirectUri = errorRedirectUri
            )
        )
    }
}
