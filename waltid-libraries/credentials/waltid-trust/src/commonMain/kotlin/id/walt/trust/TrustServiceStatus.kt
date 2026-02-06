package id.walt.trust

import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable

@Serializable
data class TrustServiceStatus(
    val healthy: Boolean,
    val sources: Map<TrustSource, TrustSourceStatus> = emptyMap(),
    val lastUpdate: Instant? = null
)

@Serializable
data class TrustSourceStatus(
    val enabled: Boolean,
    val healthy: Boolean,
    val lastUpdate: Instant? = null,
    val entryCount: Int = 0,
    val error: String? = null
)
