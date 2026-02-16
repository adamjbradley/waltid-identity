package id.walt.issuer.tenant

import io.klogging.noCoLogger
import kotlinx.serialization.json.Json
import java.io.File
import java.util.concurrent.ConcurrentHashMap

class IssuerTenantStore(private val storageDir: File) {

    private val log = noCoLogger("IssuerTenantStore")
    private val cache = ConcurrentHashMap<String, IssuerTenant>()
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

    fun init() {
        storageDir.mkdirs()
        storageDir.listFiles { f -> f.extension == "json" }?.forEach { file ->
            try {
                val tenant = json.decodeFromString<IssuerTenant>(file.readText())
                cache[tenant.id] = tenant
                log.info("Loaded issuer tenant: ${tenant.legalName} (${tenant.id})")
            } catch (e: Exception) {
                log.warn("Failed to load issuer tenant file ${file.name}: ${e.message}")
            }
        }
        log.info("IssuerTenantStore initialized with ${cache.size} tenants from ${storageDir.absolutePath}")
    }

    fun list(): List<IssuerTenant> = cache.values.toList()

    fun get(id: String): IssuerTenant? = cache[id]

    fun findByDomain(domain: String): IssuerTenant? =
        cache.values.firstOrNull { it.domain.equals(domain, ignoreCase = true) }

    @Synchronized
    fun save(tenant: IssuerTenant) {
        cache[tenant.id] = tenant
        val file = File(storageDir, "${tenant.id}.json")
        file.writeText(json.encodeToString(IssuerTenant.serializer(), tenant))
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
        private var instance: IssuerTenantStore? = null

        fun init(storageDir: String) {
            val store = IssuerTenantStore(File(storageDir))
            store.init()
            instance = store
        }

        fun instanceOrNull(): IssuerTenantStore? = instance

        fun resetForTesting() {
            instance = null
        }
    }
}
