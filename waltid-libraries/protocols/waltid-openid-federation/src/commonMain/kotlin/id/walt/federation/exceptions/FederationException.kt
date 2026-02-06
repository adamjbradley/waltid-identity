package id.walt.federation.exceptions

open class FederationException(message: String, cause: Throwable? = null) : Exception(message, cause)

class EntityStatementFetchException(entityId: String, cause: Throwable? = null) :
    FederationException("Failed to fetch entity statement for $entityId", cause)

class TrustChainBuildException(entityId: String, reason: String) :
    FederationException("Failed to build trust chain for $entityId: $reason")

class TrustChainDepthExceededException(entityId: String, maxDepth: Int) :
    FederationException("Trust chain for $entityId exceeds max depth of $maxDepth")
