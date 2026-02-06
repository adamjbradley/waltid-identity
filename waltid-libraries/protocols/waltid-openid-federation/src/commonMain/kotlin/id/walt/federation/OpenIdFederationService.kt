package id.walt.federation

import id.walt.federation.models.EntityStatement
import id.walt.federation.models.FederationConfig
import id.walt.federation.models.TrustChain
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.*

private val log = KotlinLogging.logger {}

class OpenIdFederationService(
    private val config: FederationConfig,
    httpClient: HttpClient
) : OpenIdFederationProvider {

    private val fetcher = EntityStatementFetcher(httpClient, config.cacheTtlSeconds)
    private val chainBuilder = TrustChainBuilder(config, fetcher)
    private var healthy = false

    override suspend fun fetchEntityStatement(entityId: String): EntityStatement {
        return fetcher.fetch(entityId)
    }

    override suspend fun buildTrustChain(entityId: String): TrustChain? {
        return try {
            val chain = chainBuilder.buildChain(entityId)
            healthy = true
            chain
        } catch (e: Exception) {
            log.error(e) { "Error building trust chain for $entityId" }
            healthy = false
            null
        }
    }

    override suspend fun resolveMetadata(entityId: String): Map<String, Any>? {
        // Metadata resolution follows trust chain - simplified for now
        val chain = buildTrustChain(entityId)
        if (chain == null || !chain.valid) return null

        // Return the leaf entity's metadata
        val leafStatement = chain.statements.firstOrNull() ?: return null
        // Convert metadata JsonObject to Map
        return leafStatement.metadata?.let { metadata ->
            metadata.entries.associate { (key, value) -> key to value.toString() }
        }
    }

    override suspend fun refresh() {
        log.info { "Refreshing OpenID Federation cache" }
        fetcher.clearCache()
        healthy = true
    }

    override fun isHealthy(): Boolean = healthy
}
