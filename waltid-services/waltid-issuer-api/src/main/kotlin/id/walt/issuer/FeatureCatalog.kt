package id.walt.issuer

import id.walt.commons.config.list.DevModeConfig
import id.walt.commons.featureflag.BaseFeature
import id.walt.commons.featureflag.OptionalFeature
import id.walt.commons.featureflag.ServiceFeatureCatalog
import id.walt.issuer.config.AuthenticationServiceConfig
import id.walt.issuer.config.CredentialTypeConfig
import id.walt.issuer.config.EudiMdocConfig
import id.walt.issuer.config.OIDCIssuerServiceConfig
import id.walt.issuer.psp.PwaConfig

object FeatureCatalog : ServiceFeatureCatalog {

    private val credentialTypes = BaseFeature(
        "credential-types",
        "Configure the credential types available in this issuer instance",
        mapOf("credential-issuer-metadata" to CredentialTypeConfig::class)
    )

    private val issuerService = BaseFeature(
        "issuer-service",
        "Issuer Service Implementation",
        mapOf(
            "issuer-service" to OIDCIssuerServiceConfig::class,
            "eudiMdoc" to EudiMdocConfig::class
        )
    )

    private val authenticationService = BaseFeature(
        "authentication-service",
        "Authentication Service Implementation",
        AuthenticationServiceConfig::class
    )

    val entra = OptionalFeature("entra", "Enable support for Microsoft Entra", default = false)

    val devModeFeature = OptionalFeature("dev-mode", "Development mode", DevModeConfig::class, default = false)

    /**
     * Payment Wallet Attestation (EWC RFC007) feature.
     * Enables PSPs to issue credentials that bind payment funding sources to EUDI wallets.
     * DISABLED by default - enable via PWA_ENABLED=true environment variable.
     *
     * Note: We check the environment variable directly here because the feature must be
     * enabled BEFORE its config is loaded (chicken-and-egg problem with HOCON substitution).
     */
    val pwaFeature = OptionalFeature(
        "pwa",
        "Payment Wallet Attestation (EWC RFC007)",
        PwaConfig::class,
        default = System.getenv("PWA_ENABLED")?.toBoolean() ?: false
    )

    override val baseFeatures = listOf(credentialTypes, issuerService, authenticationService)
    override val optionalFeatures = listOf(entra, devModeFeature, pwaFeature)

}
