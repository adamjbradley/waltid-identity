package id.walt.openid4vp.verifier.rp

import io.klogging.noCoLogger
import kotlinx.serialization.json.Json
import java.io.File
import java.util.concurrent.ConcurrentHashMap

class RelyingPartyStore(private val storageDir: File) {

    private val log = noCoLogger("RelyingPartyStore")
    private val cache = ConcurrentHashMap<String, RelyingParty>()
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

    fun init() {
        storageDir.mkdirs()
        storageDir.listFiles { f -> f.extension == "json" }?.forEach { file ->
            try {
                val rp = json.decodeFromString<RelyingParty>(file.readText())
                cache[rp.id] = rp
                log.info("Loaded RP: ${rp.legalName} (${rp.id})")
            } catch (e: Exception) {
                log.warn("Failed to load RP file ${file.name}: ${e.message}")
            }
        }
        log.info("RelyingPartyStore initialized with ${cache.size} relying parties from ${storageDir.absolutePath}")
    }

    fun list(): List<RelyingParty> = cache.values.toList()

    fun get(id: String): RelyingParty? = cache[id]

    fun findByDomain(domain: String): RelyingParty? =
        cache.values.firstOrNull { it.domain.equals(domain, ignoreCase = true) }

    @Synchronized
    fun save(rp: RelyingParty) {
        cache[rp.id] = rp
        val file = File(storageDir, "${rp.id}.json")
        file.writeText(json.encodeToString(RelyingParty.serializer(), rp))
    }

    @Synchronized
    fun delete(id: String): Boolean {
        val removed = cache.remove(id) != null
        if (removed) {
            File(storageDir, "$id.json").delete()
        }
        return removed
    }

    companion object {
        @Volatile
        private var instance: RelyingPartyStore? = null

        fun init(storageDir: String) {
            val store = RelyingPartyStore(File(storageDir))
            store.init()
            instance = store
        }

        fun init(storageDir: File) {
            val store = RelyingPartyStore(storageDir)
            store.init()
            instance = store
        }

        fun instanceOrNull(): RelyingPartyStore? = instance

        fun resetForTesting() {
            instance = null
        }
    }
}
