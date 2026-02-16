package id.walt.issuer.statuslist

import kotlinx.serialization.Serializable

@Serializable
data class StatusListData(
    val id: String,
    val tenantId: String? = null,
    val purpose: String = "revocation",
    val listSize: Int = 131072,
    val bitsPerStatus: Int = 1,
    val nextAvailableIndex: Int = 0,
    val revokedCount: Int = 0,
    val totalIssued: Int = 0,
    val encodedList: String,
    val encodedListIetf: String? = null,
    val issuerDid: String? = null,
    val credentialTypes: List<String> = emptyList(),
    val createdAt: String,
    val updatedAt: String
)

@Serializable
data class StatusListEntry(
    val index: Int,
    val listId: String? = null,
    val credentialId: String? = null,
    val credentialType: String? = null,
    val subjectDid: String? = null,
    val issuerDid: String? = null,
    val issuerName: String? = null,
    val country: String? = null,
    val issuedAt: String? = null,
    val revoked: Boolean = false,
    val revokedAt: String? = null,
    val revokedReason: String? = null
)

@Serializable
data class StatusListRegistry(
    val listId: String,
    val entries: MutableMap<Int, StatusListEntry> = mutableMapOf()
)

@Serializable
data class GlobalEntry(val listId: String, val entry: StatusListEntry)

@Serializable
data class StatsResponse(
    val totalLists: Int,
    val totalIssued: Int,
    val totalRevoked: Int,
    val byCountry: Map<String, DimensionStats>,
    val byIssuer: Map<String, IssuerDimensionStats>,
    val byCredentialType: Map<String, DimensionStats>,
)

@Serializable
data class DimensionStats(val issued: Int, val revoked: Int)

@Serializable
data class IssuerDimensionStats(val name: String?, val issued: Int, val revoked: Int)

@Serializable
data class BulkActionRequest(
    val action: String,
    val reason: String? = null,
    val filter: BulkActionFilter,
)

@Serializable
data class BulkActionFilter(
    val country: String? = null,
    val issuerDid: String? = null,
    val credentialType: String? = null,
    val revoked: Boolean? = null,
)

@Serializable
data class BulkActionResponse(
    val action: String,
    val affected: Int,
    val entries: List<AffectedEntry>,
)

@Serializable
data class AffectedEntry(val listId: String, val index: Int)

@Serializable
data class StatusListSummary(
    val id: String,
    val tenantId: String? = null,
    val purpose: String,
    val listSize: Int,
    val nextAvailableIndex: Int,
    val revokedCount: Int,
    val totalIssued: Int,
    val credentialTypes: List<String>,
    val createdAt: String,
    val updatedAt: String
) {
    companion object {
        fun from(data: StatusListData) = StatusListSummary(
            id = data.id,
            tenantId = data.tenantId,
            purpose = data.purpose,
            listSize = data.listSize,
            nextAvailableIndex = data.nextAvailableIndex,
            revokedCount = data.revokedCount,
            totalIssued = data.totalIssued,
            credentialTypes = data.credentialTypes,
            createdAt = data.createdAt,
            updatedAt = data.updatedAt
        )
    }
}
