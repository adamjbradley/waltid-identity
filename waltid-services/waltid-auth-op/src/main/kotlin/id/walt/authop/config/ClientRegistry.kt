package id.walt.authop.config

import com.sksamuel.hoplite.ConfigException
import com.sksamuel.hoplite.ConfigLoaderBuilder
import com.sksamuel.hoplite.addFileSource

/** Top-level wrapper for `clients.conf` — Hoplite requires a public type to reflect on. */
internal data class ClientsWrapper(val clients: List<ClientConfig> = emptyList())

/**
 * In-memory collection of relying parties loaded from a single HOCON file
 * (typically `config/clients.conf`), keyed by `client_id`.
 *
 * Use [load] to parse a file. Like [RealmRegistry], this is a read-only snapshot.
 */
class ClientRegistry(private val byClientId: Map<String, ClientConfig>) {

    /** Lookup a client by its `client_id`. Returns null if unknown. */
    operator fun get(clientId: String): ClientConfig? = byClientId[clientId]

    /** All registered clients. */
    fun all(): Collection<ClientConfig> = byClientId.values

    /** Number of clients in the registry. */
    val size: Int get() = byClientId.size

    companion object {

        /**
         * Load clients from [path] (HOCON). Performs structural validation:
         * - client_id is unique
         * - redirect_uris is non-empty
         * - if token_endpoint_auth_method is CLIENT_SECRET_BASIC / CLIENT_SECRET_POST,
         *   client_secret must be set
         * - if token_endpoint_auth_method is NONE, client_secret must be null
         *
         * TODO: cross-validate `allowedRealms` against a [RealmRegistry]. This is not done here
         *   because the registry boundary is a single file; validation that spans registries
         *   belongs at service wiring time.
         *
         * @throws IllegalArgumentException on any validation failure, with a message naming the offending client.
         */
        fun load(path: String): ClientRegistry {
            val wrapper = try {
                ConfigLoaderBuilder.default()
                    .addDecoder(CaseInsensitiveEnumDecoder())
                    .addFileSource(path)
                    .build()
                    .loadConfigOrThrow<ClientsWrapper>()
            } catch (e: ConfigException) {
                throw IllegalArgumentException("Failed to load clients from $path: ${e.message}", e)
            }

            val byClientId = LinkedHashMap<String, ClientConfig>(wrapper.clients.size)
            wrapper.clients.forEach { client ->
                validate(client)
                require(byClientId.put(client.clientId, client) == null) {
                    "Duplicate client_id: '${client.clientId}' in $path"
                }
            }
            return ClientRegistry(byClientId)
        }

        private fun validate(client: ClientConfig) {
            val clientRef = "client '${client.clientId}'"
            require(client.redirectUris.isNotEmpty()) {
                "$clientRef: 'redirect_uris' must be non-empty"
            }
            when (client.tokenEndpointAuthMethod) {
                TokenEndpointAuthMethod.NONE -> require(client.clientSecret == null) {
                    "$clientRef: token_endpoint_auth_method=none but 'client_secret' is set"
                }

                TokenEndpointAuthMethod.CLIENT_SECRET_BASIC,
                TokenEndpointAuthMethod.CLIENT_SECRET_POST -> requireNotNull(client.clientSecret) {
                    "$clientRef: token_endpoint_auth_method=${client.tokenEndpointAuthMethod.name.lowercase()} requires 'client_secret'"
                }
            }
        }
    }
}
