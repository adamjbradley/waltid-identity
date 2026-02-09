package id.walt.openid4vp.verifier.rp

import id.walt.commons.config.WaltConfig
import kotlinx.serialization.Serializable

@Serializable
data class RpRegistrarConfig(
    val storageDir: String = "config/relying-parties"
) : WaltConfig()
