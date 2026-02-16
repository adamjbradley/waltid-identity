package id.walt.issuer.statuslist

import id.walt.issuer.tenant.IssuerTenantStore
import io.klogging.noCoLogger
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

object StatusListIssuanceHook {

    private val log = noCoLogger("StatusListIssuanceHook")

    data class StatusAllocation(
        val listId: String,
        val index: Int,
        val uri: String,
    )

    fun allocateForCredential(
        credentialConfigurationId: String,
        issuerDid: String?,
        baseUrl: String,
    ): StatusAllocation? {
        val store = StatusListStore.instanceOrNull() ?: return null

        val tenant = IssuerTenantStore.instanceOrNull()?.let { tenantStore ->
            issuerDid?.let { did -> tenantStore.list().firstOrNull { it.issuerDid == did } }
        }

        val listId = store.findOrCreateDefaultList(credentialConfigurationId)

        val entry = StatusListEntry(
            index = -1,
            credentialType = credentialConfigurationId,
            issuerDid = issuerDid,
            issuerName = tenant?.legalName,
            country = tenant?.country,
            issuedAt = kotlinx.datetime.Clock.System.now().toString(),
        )

        val allocatedIndex = store.allocateRandomIndex(listId, entry)
        val uri = "$baseUrl/status-lists/$listId/token"

        log.info("Allocated status index $allocatedIndex in list $listId for $credentialConfigurationId")

        return StatusAllocation(listId = listId, index = allocatedIndex, uri = uri)
    }

    fun buildStatusClaim(allocation: StatusAllocation): JsonObject {
        return buildJsonObject {
            put("status_list", buildJsonObject {
                put("idx", allocation.index)
                put("uri", allocation.uri)
            })
        }
    }
}
