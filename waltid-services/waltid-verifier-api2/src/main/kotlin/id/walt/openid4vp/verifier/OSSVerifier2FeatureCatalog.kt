package id.walt.openid4vp.verifier

import id.walt.commons.config.TrustListConfig
import id.walt.commons.config.list.DevModeConfig
import id.walt.commons.featureflag.BaseFeature
import id.walt.commons.featureflag.OptionalFeature
import id.walt.commons.featureflag.ServiceFeatureCatalog
import id.walt.openid4vp.verifier.rp.RpRegistrarConfig

object OSSVerifier2FeatureCatalog : ServiceFeatureCatalog {

    val verifierService =
        BaseFeature("verifier-service", "Verifier Service Implementation", OSSVerifier2ServiceConfig::class)

    val devModeFeature = OptionalFeature("dev-mode", "Development mode", DevModeConfig::class, default = false)

    val trustListFeature = OptionalFeature(
        "trust-lists",
        "EUDI Trust List validation",
        TrustListConfig::class,
        default = System.getenv("TRUST_LISTS_ENABLED")?.toBoolean() ?: false
    )

    val rpRegistrarFeature = OptionalFeature(
        "rp-registrar",
        "Relying Party Registrar for EUDI RP onboarding",
        RpRegistrarConfig::class,
        default = System.getenv("RP_REGISTRAR_ENABLED")?.toBoolean() ?: false
    )

    override val baseFeatures = listOf(verifierService)
    override val optionalFeatures: List<OptionalFeature> = listOf(devModeFeature, trustListFeature, rpRegistrarFeature)
}
