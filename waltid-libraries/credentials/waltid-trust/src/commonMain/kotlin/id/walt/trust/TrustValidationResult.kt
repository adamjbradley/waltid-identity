package id.walt.trust

import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable

@Serializable
data class TrustValidationResult(
    val trusted: Boolean,
    val source: TrustSource? = null,
    val providerName: String? = null,
    val country: String? = null,
    val status: String? = null,
    val validFrom: Instant? = null,
    val validUntil: Instant? = null,
    val details: Map<String, String> = emptyMap()
)
