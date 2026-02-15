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
                    encodedList = BitstringManager.createEmpty(listSize),
                    credentialTypes = req.credentialTypes,
                    createdAt = now,
                    updatedAt = now
                )

                store.save(data)
                call.respond(HttpStatusCode.Created, StatusListSummary.from(data))
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
