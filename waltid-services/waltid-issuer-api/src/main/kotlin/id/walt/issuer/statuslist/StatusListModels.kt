package id.walt.issuer.statuslist

import kotlinx.serialization.Serializable

@Serializable
data class StatusListData(
    val id: String,
    val tenantId: String? = null,
    val purpose: String = "revocation",
    val listSize: Int = 131072,
    val nextAvailableIndex: Int = 0,
    val revokedCount: Int = 0,
    val totalIssued: Int = 0,
    val encodedList: String,
    val issuerDid: String? = null,
    val credentialTypes: List<String> = emptyList(),
    val createdAt: String,
    val updatedAt: String
)

@Serializable
data class StatusListEntry(
    val index: Int,
    val credentialId: String? = null,
    val credentialType: String? = null,
    val subjectDid: String? = null,
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
