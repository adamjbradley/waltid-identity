package id.walt.issuer.statuslist

import id.walt.commons.config.WaltConfig
import kotlinx.serialization.Serializable

@Serializable
data class StatusListConfig(
    val storageDir: String = "config/status-lists",
    val defaultListSize: Int = 131072,
    val defaultPurpose: String = "revocation"
) : WaltConfig()
