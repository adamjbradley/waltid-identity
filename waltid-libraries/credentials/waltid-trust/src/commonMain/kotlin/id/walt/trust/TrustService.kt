package id.walt.trust

import id.walt.credentials.formats.DigitalCredential

interface TrustService {
    suspend fun validateIssuer(credential: DigitalCredential): TrustValidationResult
    suspend fun validateVerifier(clientId: String, certificates: List<ByteArray>? = null): TrustValidationResult
    suspend fun getStatus(): TrustServiceStatus
    suspend fun setEnabled(source: TrustSource, enabled: Boolean)
}
