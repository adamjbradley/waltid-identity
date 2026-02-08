package id.walt.federation.models

import kotlinx.serialization.Serializable

@Serializable
data class TrustChain(
    val entityId: String,
    val trustAnchorId: String,
    val statements: List<EntityStatement>,
    val valid: Boolean,
    val error: String? = null
) {
    val depth: Int
        get() = statements.size
}
