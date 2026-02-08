package id.walt.federation

import id.walt.federation.exceptions.TrustChainDepthExceededException
import id.walt.federation.models.EntityStatement
import id.walt.federation.models.FederationConfig
import id.walt.federation.models.TrustChain
import io.github.oshai.kotlinlogging.KotlinLogging

private val log = KotlinLogging.logger {}

class TrustChainBuilder(
    private val config: FederationConfig,
    private val fetcher: EntityStatementFetcher
) {
    private val trustAnchorIds = config.trustAnchors.map { it.entityId }.toSet()

    suspend fun buildChain(entityId: String): TrustChain? {
        if (trustAnchorIds.isEmpty()) {
            log.warn { "No trust anchors configured, cannot build trust chain" }
            return null
        }

        return try {
            val statements = mutableListOf<EntityStatement>()

            // Step 1: Fetch self-signed entity statement
            val selfStatement = fetcher.fetch(entityId)
            statements.add(selfStatement)

            if (!selfStatement.isSelfSigned) {
                return TrustChain(
                    entityId = entityId,
                    trustAnchorId = "",
                    statements = statements,
                    valid = false,
                    error = "First statement is not self-signed"
                )
            }

            // Step 2: Walk authority hints toward a trust anchor
            var currentEntityId = entityId
            var currentStatement = selfStatement
            var foundAnchor: String? = null

            for (depth in 0 until config.maxChainDepth) {
                val hints = currentStatement.authorityHints
                if (hints.isNullOrEmpty()) {
                    break
                }

                // Try each authority hint
                for (hint in hints) {
                    if (hint in trustAnchorIds) {
                        // Fetch subordinate statement from the trust anchor
                        try {
                            val subordinateStatement = fetcher.fetchSubordinateStatement(hint, currentEntityId)
                            statements.add(subordinateStatement)
                            foundAnchor = hint
                        } catch (e: Exception) {
                            log.warn { "Failed to fetch subordinate statement from anchor $hint: ${e.message}" }
                        }
                        break
                    }

                    // Intermediate entity - fetch and continue
                    try {
                        val intermediateStatement = fetcher.fetchSubordinateStatement(hint, currentEntityId)
                        statements.add(intermediateStatement)

                        val intermediateSelfStatement = fetcher.fetch(hint)
                        statements.add(intermediateSelfStatement)

                        currentEntityId = hint
                        currentStatement = intermediateSelfStatement
                        break
                    } catch (e: Exception) {
                        log.warn { "Failed to resolve intermediate $hint: ${e.message}" }
                    }
                }

                if (foundAnchor != null) break
            }

            if (foundAnchor == null && statements.size >= config.maxChainDepth) {
                throw TrustChainDepthExceededException(entityId, config.maxChainDepth)
            }

            TrustChain(
                entityId = entityId,
                trustAnchorId = foundAnchor ?: "",
                statements = statements,
                valid = foundAnchor != null
            )
        } catch (e: TrustChainDepthExceededException) {
            TrustChain(
                entityId = entityId,
                trustAnchorId = "",
                statements = emptyList(),
                valid = false,
                error = e.message
            )
        } catch (e: Exception) {
            log.error(e) { "Failed to build trust chain for $entityId" }
            TrustChain(
                entityId = entityId,
                trustAnchorId = "",
                statements = emptyList(),
                valid = false,
                error = e.message
            )
        }
    }
}
