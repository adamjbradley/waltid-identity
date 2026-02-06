package id.walt.etsi.tsl.models

import kotlinx.serialization.Serializable

@Serializable
data class TrustServiceProvider(
    val name: String,
    val tradeName: String? = null,
    val country: String? = null,
    val trustServices: List<TrustServiceEntry> = emptyList()
)
