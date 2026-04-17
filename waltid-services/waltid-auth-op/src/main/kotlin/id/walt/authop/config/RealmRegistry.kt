package id.walt.authop.config

import com.sksamuel.hoplite.ConfigException
import com.sksamuel.hoplite.ConfigLoaderBuilder
import com.sksamuel.hoplite.addFileSource

/** Top-level wrapper for `realms.conf` — Hoplite requires a public type to reflect on. */
internal data class RealmsWrapper(val realms: List<RealmConfig> = emptyList())

/**
 * In-memory collection of realms loaded from a single HOCON file (typically `config/realms.conf`).
 *
 * Use [load] to parse a file. The registry is a read-only snapshot — callers that want to
 * pick up config changes must re-invoke [load].
 */
class RealmRegistry(private val byId: Map<String, RealmConfig>) {

    /** Lookup a realm by its id. Returns null if unknown. */
    operator fun get(id: String): RealmConfig? = byId[id]

    /** All realms, in no particular order. */
    fun all(): Collection<RealmConfig> = byId.values

    /** Number of realms in the registry. */
    val size: Int get() = byId.size

    companion object {

        /**
         * Load realms from [path] (HOCON). Performs structural validation:
         * - realm ids are unique
         * - method=oidc → `oidc` block required and `oid4vp` block absent
         * - method=oid4vp → `oid4vp` block required, `oidc` block absent, and `subStrategy` set
         * - when subStrategy=CLAIM_HASH → `subSourceClaims` must be non-empty
         *
         * @throws IllegalArgumentException on any validation failure, with a message naming the offending realm.
         */
        fun load(path: String): RealmRegistry {
            val wrapper = try {
                ConfigLoaderBuilder.default()
                    .addDecoder(CaseInsensitiveEnumDecoder())
                    .addFileSource(path)
                    .build()
                    .loadConfigOrThrow<RealmsWrapper>()
            } catch (e: ConfigException) {
                throw IllegalArgumentException("Failed to load realms from $path: ${e.message}", e)
            }

            val realms = wrapper.realms
            val byId = LinkedHashMap<String, RealmConfig>(realms.size)
            realms.forEach { realm ->
                validate(realm)
                require(byId.put(realm.id, realm) == null) {
                    "Duplicate realm id: '${realm.id}' in $path"
                }
            }
            return RealmRegistry(byId)
        }

        private fun validate(realm: RealmConfig) {
            val realmRef = "realm '${realm.id}'"
            when (realm.method) {
                RealmMethod.OIDC -> {
                    requireNotNull(realm.oidc) { "$realmRef: method=oidc but 'oidc' block is missing" }
                    require(realm.oid4vp == null) { "$realmRef: method=oidc but 'oid4vp' block is present" }
                }

                RealmMethod.OID4VP -> {
                    requireNotNull(realm.oid4vp) { "$realmRef: method=oid4vp but 'oid4vp' block is missing" }
                    require(realm.oidc == null) { "$realmRef: method=oid4vp but 'oidc' block is present" }
                    requireNotNull(realm.subStrategy) {
                        "$realmRef: OID4VP realms must declare 'sub_strategy'"
                    }
                    if (realm.subStrategy == SubStrategy.CLAIM_HASH) {
                        require(realm.subSourceClaims.isNotEmpty()) {
                            "$realmRef: sub_strategy=claim_hash requires non-empty 'sub_source_claims'"
                        }
                    }
                }
            }
        }
    }
}
