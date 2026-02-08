package id.walt.federation

import id.walt.federation.models.EntityStatement
import id.walt.federation.models.TrustChain

interface OpenIdFederationProvider {
    suspend fun fetchEntityStatement(entityId: String): EntityStatement
    suspend fun buildTrustChain(entityId: String): TrustChain?
    suspend fun resolveMetadata(entityId: String): Map<String, Any>?
    suspend fun refresh()
    fun isHealthy(): Boolean
}
