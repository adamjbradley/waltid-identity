package id.walt.trust

import id.walt.credentials.formats.DigitalCredential
import id.walt.trust.models.TrustServiceList
import id.walt.trust.models.TrustServiceProvider

interface TrustService {
    suspend fun validateIssuer(credential: DigitalCredential): TrustValidationResult
    suspend fun validateVerifier(clientId: String, certificates: List<ByteArray>? = null): TrustValidationResult
    suspend fun getStatus(): TrustServiceStatus
    suspend fun setEnabled(source: TrustSource, enabled: Boolean)

    // LOTL browsing methods
    fun getLotl(): TrustServiceList?
    fun getMemberStateTls(): Map<String, TrustServiceList>
    fun getMemberStateTl(country: String): TrustServiceList?

    // Provider search
    suspend fun searchProviders(
        query: String? = null,
        country: String? = null,
        status: String? = null,
        serviceType: String? = null,
        limit: Int = 50,
        offset: Int = 0
    ): List<TrustServiceProvider>
}
