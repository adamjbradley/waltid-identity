@file:OptIn(ExperimentalUuidApi::class)

package id.walt.issuer.statuslist

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@Serializable
data class CreateStatusListRequest(
    val purpose: String = "revocation",
    val listSize: Int? = null,
    val credentialTypes: List<String> = emptyList()
)

@Serializable
data class RevokeRequest(
    val reason: String? = null
)

@Serializable
data class BulkRevokeRequest(
    val indices: List<Int>,
    val reason: String? = null
)

@Serializable
data class BulkRevokeResponse(
    val revoked: Int,
    val indices: List<Int>
)

fun Application.statusListAdminRoutes() {
    routing {
        route("/admin/status-lists") {
            get {
                val store = StatusListStore.instanceOrNull()
                    ?: return@get call.respond(
                        HttpStatusCode.ServiceUnavailable,
                        mapOf("error" to "Status Lists feature is not enabled")
                    )

                val tenantId = call.request.queryParameters["tenantId"]
                val lists = store.list(tenantId).map { StatusListSummary.from(it) }
                call.respond(lists)
            }

            post {
                val store = StatusListStore.instanceOrNull()
                    ?: return@post call.respond(
                        HttpStatusCode.ServiceUnavailable,
                        mapOf("error" to "Status Lists feature is not enabled")
                    )

                val req = call.receive<CreateStatusListRequest>()
                val now = kotlinx.datetime.Clock.System.now().toString()
                val listSize = req.listSize ?: 131072

                val data = StatusListData(
                    id = Uuid.random().toString(),
                    purpose = req.purpose,
                    listSize = listSize,
                    bitsPerStatus = 1,
                    encodedList = BitstringManager.createEmpty(listSize),
                    encodedListIetf = BitstringManager.createEmptyIetf(listSize, 1),
                    credentialTypes = req.credentialTypes,
                    createdAt = now,
                    updatedAt = now
                )

                store.save(data)
                call.respond(HttpStatusCode.Created, StatusListSummary.from(data))
            }

            get("entries/search") {
                val store = StatusListStore.instanceOrNull()
                    ?: return@get call.respond(
                        HttpStatusCode.ServiceUnavailable,
                        mapOf("error" to "Status Lists feature is not enabled")
                    )

                val page = call.request.queryParameters["page"]?.toIntOrNull() ?: 1
                val size = call.request.queryParameters["size"]?.toIntOrNull() ?: 50
                val countryFilter = call.request.queryParameters["country"]
                val issuerDidFilter = call.request.queryParameters["issuerDid"]
                val credentialTypeFilter = call.request.queryParameters["credentialType"]
                val revokedFilter = call.request.queryParameters["revoked"]?.toBooleanStrictOrNull()

                val allEntries = store.list().flatMap { data ->
                    store.getRegistry(data.id).entries.values.map { entry ->
                        GlobalEntry(listId = data.id, entry = entry)
                    }
                }.let { entries ->
                    var filtered = entries
                    if (countryFilter != null) filtered = filtered.filter { it.entry.country == countryFilter }
                    if (issuerDidFilter != null) filtered = filtered.filter { it.entry.issuerDid == issuerDidFilter }
                    if (credentialTypeFilter != null) filtered = filtered.filter { it.entry.credentialType == credentialTypeFilter }
                    if (revokedFilter != null) filtered = filtered.filter { it.entry.revoked == revokedFilter }
                    filtered.sortedByDescending { it.entry.issuedAt }
                }

                val total = allEntries.size
                val start = (page - 1) * size
                val paged = allEntries.drop(start).take(size)

                call.response.header("X-Total-Count", total.toString())
                call.respond(paged)
            }

            get("stats") {
                val store = StatusListStore.instanceOrNull()
                    ?: return@get call.respond(
                        HttpStatusCode.ServiceUnavailable,
                        mapOf("error" to "Status Lists feature is not enabled")
                    )

                val allEntries = store.list().flatMap { data ->
                    store.getRegistry(data.id).entries.values.toList()
                }

                val byCountry = mutableMapOf<String, MutableList<StatusListEntry>>()
                val byIssuer = mutableMapOf<String, MutableList<StatusListEntry>>()
                val byType = mutableMapOf<String, MutableList<StatusListEntry>>()

                for (entry in allEntries) {
                    entry.country?.let { byCountry.getOrPut(it) { mutableListOf() }.add(entry) }
                    entry.issuerDid?.let { byIssuer.getOrPut(it) { mutableListOf() }.add(entry) }
                    entry.credentialType?.let { byType.getOrPut(it) { mutableListOf() }.add(entry) }
                }

                call.respond(StatsResponse(
                    totalLists = store.list().size,
                    totalIssued = allEntries.size,
                    totalRevoked = allEntries.count { it.revoked },
                    byCountry = byCountry.mapValues { (_, entries) ->
                        DimensionStats(issued = entries.size, revoked = entries.count { it.revoked })
                    },
                    byIssuer = byIssuer.mapValues { (_, entries) ->
                        IssuerDimensionStats(
                            name = entries.firstOrNull()?.issuerName,
                            issued = entries.size,
                            revoked = entries.count { it.revoked }
                        )
                    },
                    byCredentialType = byType.mapValues { (_, entries) ->
                        DimensionStats(issued = entries.size, revoked = entries.count { it.revoked })
                    },
                ))
            }

            post("bulk-action") {
                val store = StatusListStore.instanceOrNull()
                    ?: return@post call.respond(
                        HttpStatusCode.ServiceUnavailable,
                        mapOf("error" to "Status Lists feature is not enabled")
                    )

                val req = call.receive<BulkActionRequest>()
                val revoke = when (req.action) {
                    "revoke" -> true
                    "unrevoke" -> false
                    else -> return@post call.respond(
                        HttpStatusCode.BadRequest,
                        mapOf("error" to "action must be 'revoke' or 'unrevoke'")
                    )
                }

                val affected = mutableListOf<AffectedEntry>()
                val invalidatedLists = mutableSetOf<String>()

                for (data in store.list()) {
                    val registry = store.getRegistry(data.id)
                    for ((index, entry) in registry.entries) {
                        if (entry.revoked == revoke) continue
                        val filter = req.filter
                        if (filter.country != null && entry.country != filter.country) continue
                        if (filter.issuerDid != null && entry.issuerDid != filter.issuerDid) continue
                        if (filter.credentialType != null && entry.credentialType != filter.credentialType) continue
                        if (filter.revoked != null && entry.revoked != filter.revoked) continue

                        store.setEntryStatus(data.id, index, revoked = revoke, reason = req.reason)
                        affected.add(AffectedEntry(listId = data.id, index = index))
                        invalidatedLists.add(data.id)
                    }
                }

                invalidatedLists.forEach { StatusListCredentialGenerator.invalidateCache(it) }

                call.respond(BulkActionResponse(
                    action = req.action,
                    affected = affected.size,
                    entries = affected,
                ))
            }

            route("{listId}") {
                get {
                    val store = StatusListStore.instanceOrNull()
                        ?: return@get call.respond(
                            HttpStatusCode.ServiceUnavailable,
                            mapOf("error" to "Status Lists feature is not enabled")
                        )

                    val listId = call.parameters["listId"]!!
                    val data = store.get(listId)
                        ?: return@get call.respond(
                            HttpStatusCode.NotFound, mapOf("error" to "Status list not found: $listId")
                        )

                    call.respond(StatusListSummary.from(data))
                }

                delete {
                    val store = StatusListStore.instanceOrNull()
                        ?: return@delete call.respond(
                            HttpStatusCode.ServiceUnavailable,
                            mapOf("error" to "Status Lists feature is not enabled")
                        )

                    val listId = call.parameters["listId"]!!
                    val removed = store.delete(listId)
                    if (removed) {
                        StatusListCredentialGenerator.invalidateCache(listId)
                        call.respond(HttpStatusCode.OK, mapOf("deleted" to listId))
                    } else {
                        call.respond(HttpStatusCode.NotFound, mapOf("error" to "Status list not found: $listId"))
                    }
                }

                get("entries") {
                    val store = StatusListStore.instanceOrNull()
                        ?: return@get call.respond(
                            HttpStatusCode.ServiceUnavailable,
                            mapOf("error" to "Status Lists feature is not enabled")
                        )

                    val listId = call.parameters["listId"]!!
                    store.get(listId)
                        ?: return@get call.respond(
                            HttpStatusCode.NotFound, mapOf("error" to "Status list not found: $listId")
                        )

                    val page = call.request.queryParameters["page"]?.toIntOrNull() ?: 1
                    val size = call.request.queryParameters["size"]?.toIntOrNull() ?: 50
                    val revokedFilter = call.request.queryParameters["revoked"]?.toBooleanStrictOrNull()
                    val credentialTypeFilter = call.request.queryParameters["credentialType"]

                    val registry = store.getRegistry(listId)
                    var entries = registry.entries.values.toList()

                    if (revokedFilter != null) {
                        entries = entries.filter { it.revoked == revokedFilter }
                    }
                    if (credentialTypeFilter != null) {
                        entries = entries.filter { it.credentialType == credentialTypeFilter }
                    }

                    entries = entries.sortedBy { it.index }

                    val total = entries.size
                    val start = (page - 1) * size
                    val paged = entries.drop(start).take(size)

                    call.response.header("X-Total-Count", total.toString())
                    call.respond(paged)
                }

                put("entries/{index}/revoke") {
                    val store = StatusListStore.instanceOrNull()
                        ?: return@put call.respond(
                            HttpStatusCode.ServiceUnavailable,
                            mapOf("error" to "Status Lists feature is not enabled")
                        )

                    val listId = call.parameters["listId"]!!
                    val index = call.parameters["index"]?.toIntOrNull()
                        ?: return@put call.respond(
                            HttpStatusCode.BadRequest, mapOf("error" to "Invalid index")
                        )

                    store.get(listId)
                        ?: return@put call.respond(
                            HttpStatusCode.NotFound, mapOf("error" to "Status list not found: $listId")
                        )

                    val req = call.receive<RevokeRequest>()
                    store.setEntryStatus(listId, index, revoked = true, reason = req.reason)
                    StatusListCredentialGenerator.invalidateCache(listId)

                    val registry = store.getRegistry(listId)
                    val entry = registry.entries[index]
                    call.respond(entry ?: mapOf("index" to index, "revoked" to true))
                }

                put("entries/{index}/unrevoke") {
                    val store = StatusListStore.instanceOrNull()
                        ?: return@put call.respond(
                            HttpStatusCode.ServiceUnavailable,
                            mapOf("error" to "Status Lists feature is not enabled")
                        )

                    val listId = call.parameters["listId"]!!
                    val index = call.parameters["index"]?.toIntOrNull()
                        ?: return@put call.respond(
                            HttpStatusCode.BadRequest, mapOf("error" to "Invalid index")
                        )

                    store.get(listId)
                        ?: return@put call.respond(
                            HttpStatusCode.NotFound, mapOf("error" to "Status list not found: $listId")
                        )

                    store.setEntryStatus(listId, index, revoked = false)
                    StatusListCredentialGenerator.invalidateCache(listId)

                    val registry = store.getRegistry(listId)
                    val entry = registry.entries[index]
                    call.respond(entry ?: mapOf("index" to index, "revoked" to false))
                }

                post("bulk-revoke") {
                    val store = StatusListStore.instanceOrNull()
                        ?: return@post call.respond(
                            HttpStatusCode.ServiceUnavailable,
                            mapOf("error" to "Status Lists feature is not enabled")
                        )

                    val listId = call.parameters["listId"]!!
                    store.get(listId)
                        ?: return@post call.respond(
                            HttpStatusCode.NotFound, mapOf("error" to "Status list not found: $listId")
                        )

                    val req = call.receive<BulkRevokeRequest>()
                    for (index in req.indices) {
                        store.setEntryStatus(listId, index, revoked = true, reason = req.reason)
                    }
                    StatusListCredentialGenerator.invalidateCache(listId)

                    call.respond(BulkRevokeResponse(revoked = req.indices.size, indices = req.indices))
                }
            }
        }
    }
}
