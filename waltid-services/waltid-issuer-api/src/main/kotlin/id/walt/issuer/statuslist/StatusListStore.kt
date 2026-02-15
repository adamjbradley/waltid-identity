package id.walt.issuer.statuslist

import io.klogging.noCoLogger
import kotlinx.serialization.json.Json
import java.io.File
import java.util.concurrent.ConcurrentHashMap

class StatusListStore(private val storageDir: File) {

    private val log = noCoLogger("StatusListStore")
    private val listCache = ConcurrentHashMap<String, StatusListData>()
    private val registryCache = ConcurrentHashMap<String, StatusListRegistry>()
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

    fun init() {
        storageDir.mkdirs()
        registryDir().mkdirs()

        storageDir.listFiles { f -> f.extension == "json" }?.forEach { file ->
            try {
                val data = json.decodeFromString<StatusListData>(file.readText())
                listCache[data.id] = data
                log.info("Loaded status list: ${data.id} (purpose=${data.purpose}, size=${data.listSize})")
            } catch (e: Exception) {
                log.warn("Failed to load status list file ${file.name}: ${e.message}")
            }
        }

        registryDir().listFiles { f -> f.extension == "json" }?.forEach { file ->
            try {
                val registry = json.decodeFromString<StatusListRegistry>(file.readText())
                registryCache[registry.listId] = registry
                log.info("Loaded status list registry: ${registry.listId} (${registry.entries.size} entries)")
            } catch (e: Exception) {
                log.warn("Failed to load registry file ${file.name}: ${e.message}")
            }
        }

        log.info("StatusListStore initialized with ${listCache.size} lists from ${storageDir.absolutePath}")
    }

    fun list(tenantId: String? = null): List<StatusListData> {
        val all = listCache.values.toList()
        return if (tenantId != null) all.filter { it.tenantId == tenantId } else all
    }

    fun get(listId: String): StatusListData? = listCache[listId]

    @Synchronized
    fun save(data: StatusListData) {
        listCache[data.id] = data
        val file = File(storageDir, "${data.id}.json")
        file.writeText(json.encodeToString(StatusListData.serializer(), data))
    }

    @Synchronized
    fun delete(listId: String): Boolean {
        val removed = listCache.remove(listId) != null
        if (removed) {
            File(storageDir, "$listId.json").delete()
            registryCache.remove(listId)
            File(registryDir(), "$listId-entries.json").delete()
        }
        return removed
    }

    fun getRegistry(listId: String): StatusListRegistry {
        return registryCache.getOrPut(listId) { StatusListRegistry(listId = listId) }
    }

    @Synchronized
    fun saveRegistry(registry: StatusListRegistry) {
        registryCache[registry.listId] = registry
        val file = File(registryDir(), "${registry.listId}-entries.json")
        file.writeText(json.encodeToString(StatusListRegistry.serializer(), registry))
    }

    @Synchronized
    fun allocateIndex(listId: String, entry: StatusListEntry): Int {
        val data = listCache[listId] ?: throw IllegalArgumentException("Status list not found: $listId")
        val index = data.nextAvailableIndex
        if (index >= data.listSize) {
            throw IllegalStateException("Status list $listId is full (size=${data.listSize})")
        }

        val allocatedEntry = entry.copy(index = index)
        val registry = getRegistry(listId)
        registry.entries[index] = allocatedEntry
        saveRegistry(registry)

        val now = kotlinx.datetime.Clock.System.now().toString()
        val updated = data.copy(
            nextAvailableIndex = index + 1,
            totalIssued = data.totalIssued + 1,
            updatedAt = now
        )
        save(updated)

        return index
    }

    @Synchronized
    fun setEntryStatus(listId: String, index: Int, revoked: Boolean, reason: String? = null): StatusListData {
        val data = listCache[listId] ?: throw IllegalArgumentException("Status list not found: $listId")
        val registry = getRegistry(listId)
        val entry = registry.entries[index]

        val now = kotlinx.datetime.Clock.System.now().toString()

        val updatedEntry = (entry ?: StatusListEntry(index = index)).copy(
            revoked = revoked,
            revokedAt = if (revoked) now else null,
            revokedReason = if (revoked) reason else null
        )
        registry.entries[index] = updatedEntry
        saveRegistry(registry)

        val newEncodedList = BitstringManager.setBit(data.encodedList, index, revoked)
        val newRevokedCount = BitstringManager.countSetBits(newEncodedList)
        val updated = data.copy(
            encodedList = newEncodedList,
            revokedCount = newRevokedCount,
            updatedAt = now
        )
        save(updated)

        return updated
    }

    private fun registryDir() = File(storageDir, "registry")

    companion object {
        @Volatile
        private var instance: StatusListStore? = null

        fun init(storageDir: String) {
            val store = StatusListStore(File(storageDir))
            store.init()
            instance = store
        }

        fun instanceOrNull(): StatusListStore? = instance
    }
}
