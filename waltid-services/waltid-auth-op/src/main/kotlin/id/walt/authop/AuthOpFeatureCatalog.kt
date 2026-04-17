package id.walt.authop

import id.walt.commons.featureflag.BaseFeature
import id.walt.commons.featureflag.OptionalFeature
import id.walt.commons.featureflag.ServiceFeatureCatalog

object AuthOpFeatureCatalog : ServiceFeatureCatalog {

    override val baseFeatures: List<BaseFeature> = emptyList()
    override val optionalFeatures: List<OptionalFeature> = emptyList()
}
