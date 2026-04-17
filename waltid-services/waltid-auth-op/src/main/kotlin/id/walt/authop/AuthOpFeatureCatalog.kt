package id.walt.authop

import id.walt.authop.config.AuthOpServiceConfig
import id.walt.commons.featureflag.BaseFeature
import id.walt.commons.featureflag.OptionalFeature
import id.walt.commons.featureflag.ServiceFeatureCatalog

object AuthOpFeatureCatalog : ServiceFeatureCatalog {

    /**
     * Service-level configuration (issuer URL, signing-key path). Loaded from
     * `config/auth-op.conf`. Required — without it the OP has no issuer identity.
     *
     * We use the map-form [BaseFeature] constructor so the feature name stays
     * stable (`auth-op-service`) while the config file id is the shorter
     * `auth-op` — matching sibling services' `issuer-service.conf`, etc.
     */
    private val authOpService = BaseFeature(
        "auth-op-service",
        "Auth-OP service configuration (issuer URL, signing key path)",
        mapOf("auth-op" to AuthOpServiceConfig::class),
    )

    override val baseFeatures: List<BaseFeature> = listOf(authOpService)
    override val optionalFeatures: List<OptionalFeature> = emptyList()
}
