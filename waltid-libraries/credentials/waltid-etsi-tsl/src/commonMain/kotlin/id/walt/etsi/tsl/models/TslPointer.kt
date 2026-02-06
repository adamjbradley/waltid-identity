package id.walt.etsi.tsl.models

import kotlinx.serialization.Serializable

@Serializable
data class TslPointer(
    val location: String,
    val schemeTerritory: String? = null,
    val mimeType: String? = null,
    val schemeTypeCommunityRule: String? = null
)
