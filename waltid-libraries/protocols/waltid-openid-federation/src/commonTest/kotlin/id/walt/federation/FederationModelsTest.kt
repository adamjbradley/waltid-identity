package id.walt.federation

import id.walt.federation.exceptions.*
import id.walt.federation.models.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FederationModelsTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun testEntityStatementSerialization() {
        val statement = EntityStatement(
            issuer = "https://example.com",
            subject = "https://example.com",
            issuedAt = 1700000000,
            expiresAt = 1700086400,
            authorityHints = listOf("https://trust-anchor.example.com")
        )
        val encoded = json.encodeToString(statement)
        val decoded = json.decodeFromString<EntityStatement>(encoded)
        assertEquals(statement, decoded)
    }

    @Test
    fun testEntityStatementIsSelfSigned() {
        val selfSigned = EntityStatement(
            issuer = "https://example.com",
            subject = "https://example.com"
        )
        assertTrue(selfSigned.isSelfSigned)

        val notSelfSigned = EntityStatement(
            issuer = "https://authority.example.com",
            subject = "https://example.com"
        )
        assertFalse(notSelfSigned.isSelfSigned)
    }

    @Test
    fun testEntityStatementExpiry() {
        val statement = EntityStatement(
            issuer = "https://example.com",
            subject = "https://example.com",
            expiresAt = 1700000000
        )
        assertTrue(statement.isExpired(1700000001))
        assertFalse(statement.isExpired(1699999999))
    }

    @Test
    fun testEntityStatementNoExpiry() {
        val statement = EntityStatement(
            issuer = "https://example.com",
            subject = "https://example.com"
        )
        assertFalse(statement.isExpired(1700000000))
    }

    @Test
    fun testTrustChainSerialization() {
        val chain = TrustChain(
            entityId = "https://issuer.example.com",
            trustAnchorId = "https://anchor.example.com",
            statements = listOf(
                EntityStatement(
                    issuer = "https://issuer.example.com",
                    subject = "https://issuer.example.com"
                )
            ),
            valid = true
        )
        val encoded = json.encodeToString(chain)
        val decoded = json.decodeFromString<TrustChain>(encoded)
        assertEquals(chain, decoded)
        assertEquals(1, decoded.depth)
    }

    @Test
    fun testFederationConfigDefaults() {
        val config = FederationConfig()
        assertEquals(emptyList(), config.trustAnchors)
        assertEquals(5, config.maxChainDepth)
        assertEquals(3600, config.cacheTtlSeconds)
    }

    @Test
    fun testFederationConfigSerialization() {
        val config = FederationConfig(
            trustAnchors = listOf(
                TrustAnchor(entityId = "https://anchor.example.com")
            ),
            maxChainDepth = 3
        )
        val encoded = json.encodeToString(config)
        val decoded = json.decodeFromString<FederationConfig>(encoded)
        assertEquals(config, decoded)
    }

    @Test
    fun testFederationExceptions() {
        val fetchEx = EntityStatementFetchException("https://example.com")
        assertTrue(fetchEx.message!!.contains("https://example.com"))

        val buildEx = TrustChainBuildException("https://example.com", "no anchors found")
        assertTrue(buildEx.message!!.contains("no anchors found"))

        val depthEx = TrustChainDepthExceededException("https://example.com", 5)
        assertTrue(depthEx.message!!.contains("5"))
    }

    @Test
    fun testTrustAnchorSerialization() {
        val anchor = TrustAnchor(
            entityId = "https://anchor.example.com",
            jwks = """{"keys": []}"""
        )
        val encoded = json.encodeToString(anchor)
        val decoded = json.decodeFromString<TrustAnchor>(encoded)
        assertEquals(anchor, decoded)
    }
}
