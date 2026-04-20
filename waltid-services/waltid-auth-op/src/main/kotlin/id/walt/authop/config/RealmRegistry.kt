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
                // Use localizedMessage (falls back to toString()) so Hoplite's formatted
                // multi-error description survives instead of being collapsed to `e.message`.
                throw ConfigLoadException(
                    path = path,
                    message = "Failed to load realms from $path:\n${e.localizedMessage ?: e.toString()}",
                    cause = e,
                )
            }

            val realms = wrapper.realms
            val byId = LinkedHashMap<String, RealmConfig>(realms.size)
            realms.forEach { realm ->
                validate(path, realm)
                if (byId.put(realm.id, realm) != null) {
                    throw ConfigLoadException(path, "Duplicate realm id: '${realm.id}' in $path")
                }
            }
            return RealmRegistry(byId)
        }

        private fun validate(path: String, realm: RealmConfig) {
            val realmRef = "realm '${realm.id}'"
            fun fail(msg: String): Nothing = throw ConfigLoadException(path, msg)
            when (realm.method) {
                RealmMethod.OIDC -> {
                    if (realm.oidc == null) fail("$realmRef: method=oidc but 'oidc' block is missing")
                    if (realm.oid4vp != null) fail("$realmRef: method=oidc but 'oid4vp' block is present")
                }

                RealmMethod.OID4VP -> {
                    if (realm.oid4vp == null) fail("$realmRef: method=oid4vp but 'oid4vp' block is missing")
                    if (realm.oidc != null) fail("$realmRef: method=oid4vp but 'oidc' block is present")
                    if (realm.subStrategy == null) fail("$realmRef: OID4VP realms must declare 'sub_strategy'")
                    if (realm.subStrategy == SubStrategy.CLAIM_HASH && realm.subSourceClaims.isEmpty()) {
                        fail("$realmRef: sub_strategy=claim_hash requires non-empty 'sub_source_claims'")
                    }
                    val hasFile = !realm.oid4vp.dcqlQueryFile.isNullOrBlank()
                    val hasScopes = realm.oid4vp.scopes.isNotEmpty()
                    if (!hasFile && !hasScopes) {
                        fail("$realmRef: oid4vp block must declare either 'dcql_query_file' or a 'scopes' catalog")
                    }
                    if (hasFile && hasScopes) {
                        fail("$realmRef: oid4vp block must declare 'dcql_query_file' OR 'scopes', not both")
                    }
                    if (hasScopes && realm.oid4vp.vctValues.isEmpty()) {
                        fail("$realmRef: 'scopes' catalog requires 'vct_values' so the composer can target a credential type")
                    }
                }
            }
        }
    }
}
