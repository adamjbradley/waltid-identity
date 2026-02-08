package id.walt.federation.models

import kotlinx.serialization.Serializable

@Serializable
data class FederationConfig(
    val trustAnchors: List<TrustAnchor> = emptyList(),
    val maxChainDepth: Int = 5,
    val cacheTtlSeconds: Long = 3600
)

@Serializable
data class TrustAnchor(
    val entityId: String,
    val jwks: String? = null
)
