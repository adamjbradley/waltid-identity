package id.walt.etsi.tsl

import id.walt.etsi.tsl.models.TrustServiceList
import id.walt.etsi.tsl.models.TrustServiceProvider

interface EtsiTrustListProvider {
    suspend fun fetchLotl(): TrustServiceList
    suspend fun fetchMemberStateTl(url: String): TrustServiceList
    suspend fun getAllTrustedProviders(): List<TrustServiceProvider>
    suspend fun refresh()
    fun isHealthy(): Boolean
}
