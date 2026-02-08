package id.walt.etsi.tsl.models

import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable

@Serializable
data class TrustServiceList(
    val schemeTerritory: String,
    val schemeOperatorName: String,
    val listIssueDate: Instant? = null,
    val nextUpdate: Instant? = null,
    val trustServiceProviders: List<TrustServiceProvider> = emptyList(),
    val pointers: List<TslPointer> = emptyList(),
    val sequenceNumber: Int? = null
)
