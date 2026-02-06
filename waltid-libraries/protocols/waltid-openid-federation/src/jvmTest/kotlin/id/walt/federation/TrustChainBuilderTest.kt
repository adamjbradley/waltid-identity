package id.walt.federation

import id.walt.federation.models.EntityStatement
import id.walt.federation.models.FederationConfig
import id.walt.federation.models.TrustAnchor
import io.ktor.client.*
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.test.runTest
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TrustChainBuilderTest {

    private fun createFakeJwt(payload: String): String {
        val header = """{"alg":"none","typ":"JWT"}"""
        val encoder = Base64.getUrlEncoder().withoutPadding()
        val encodedHeader = encoder.encodeToString(header.toByteArray())
        val encodedPayload = encoder.encodeToString(payload.toByteArray())
        return "$encodedHeader.$encodedPayload.fakesig"
    }

    /**
     * Build a MockEngine that returns a specific JWT payload based on the request URL.
     * The urlToPayload map maps URL substrings to JSON payloads.
     */
    private fun buildMockEngine(urlToPayload: Map<String, String>): MockEngine {
        return MockEngine { request ->
            val url = request.url.toString()
            val payload = urlToPayload.entries.firstOrNull { (key, _) -> url.contains(key) }?.value
            if (payload != null) {
                respond(
                    content = createFakeJwt(payload),
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/entity-statement+jwt")
                )
            } else {
                respond(
                    content = "Not Found",
                    status = HttpStatusCode.NotFound
                )
            }
        }
    }

    // === Payloads for test scenarios ===

    // Self-signed entity with authority hint to a trust anchor
    private val entitySelfSignedPayload = """{
        "iss": "https://issuer.example.com",
        "sub": "https://issuer.example.com",
        "iat": 1700000000,
        "exp": 1700086400,
        "authority_hints": ["https://anchor.example.com"]
    }"""

    // Subordinate statement from anchor about entity
    private val anchorSubordinatePayload = """{
        "iss": "https://anchor.example.com",
        "sub": "https://issuer.example.com",
        "iat": 1700000000,
        "exp": 1700086400
    }"""

    // Entity whose iss != sub (not self-signed)
    private val notSelfSignedPayload = """{
        "iss": "https://other.example.com",
        "sub": "https://issuer.example.com",
        "iat": 1700000000,
        "exp": 1700086400,
        "authority_hints": ["https://anchor.example.com"]
    }"""

    // Entity with authority hint to an intermediate (not a trust anchor)
    private val entityWithIntermediatePayload = """{
        "iss": "https://leaf.example.com",
        "sub": "https://leaf.example.com",
        "iat": 1700000000,
        "exp": 1700086400,
        "authority_hints": ["https://intermediate.example.com"]
    }"""

    // Intermediate entity self-signed with authority hint to anchor
    private val intermediateSelfSignedPayload = """{
        "iss": "https://intermediate.example.com",
        "sub": "https://intermediate.example.com",
        "iat": 1700000000,
        "exp": 1700086400,
        "authority_hints": ["https://anchor.example.com"]
    }"""

    // Subordinate statement from intermediate about leaf
    private val intermediateSubordinatePayload = """{
        "iss": "https://intermediate.example.com",
        "sub": "https://leaf.example.com",
        "iat": 1700000000,
        "exp": 1700086400
    }"""

    // Subordinate statement from anchor about intermediate
    private val anchorAboutIntermediatePayload = """{
        "iss": "https://anchor.example.com",
        "sub": "https://intermediate.example.com",
        "iat": 1700000000,
        "exp": 1700086400
    }"""

    // Entity with authority hints that do not lead to any configured anchor
    private val entityNoAnchorPayload = """{
        "iss": "https://orphan.example.com",
        "sub": "https://orphan.example.com",
        "iat": 1700000000,
        "exp": 1700086400,
        "authority_hints": ["https://unknown-authority.example.com"]
    }"""

    // Unknown authority self-signed, no further hints
    private val unknownAuthoritySelfSignedPayload = """{
        "iss": "https://unknown-authority.example.com",
        "sub": "https://unknown-authority.example.com",
        "iat": 1700000000,
        "exp": 1700086400
    }"""

    // Subordinate from unknown authority about orphan
    private val unknownAuthoritySubordinatePayload = """{
        "iss": "https://unknown-authority.example.com",
        "sub": "https://orphan.example.com",
        "iat": 1700000000,
        "exp": 1700086400
    }"""

    @Test
    fun `buildChain returns null when no trust anchors configured`() = runTest {
        val config = FederationConfig(trustAnchors = emptyList())
        val mockEngine = buildMockEngine(emptyMap())
        val fetcher = EntityStatementFetcher(HttpClient(mockEngine))
        val builder = TrustChainBuilder(config, fetcher)

        val result = builder.buildChain("https://issuer.example.com")

        assertNull(result, "Expected null when no trust anchors are configured")
    }

    @Test
    fun `buildChain succeeds when entity has direct trust anchor`() = runTest {
        val config = FederationConfig(
            trustAnchors = listOf(TrustAnchor(entityId = "https://anchor.example.com"))
        )
        // Map URL patterns to payloads:
        // - .well-known/openid-federation for issuer.example.com -> self-signed entity
        // - anchor.example.com/fetch?sub=issuer.example.com -> subordinate statement
        val mockEngine = buildMockEngine(
            mapOf(
                "issuer.example.com/.well-known/openid-federation" to entitySelfSignedPayload,
                "anchor.example.com/fetch?sub=" to anchorSubordinatePayload
            )
        )
        val fetcher = EntityStatementFetcher(HttpClient(mockEngine))
        val builder = TrustChainBuilder(config, fetcher)

        val chain = builder.buildChain("https://issuer.example.com")

        assertNotNull(chain)
        assertTrue(chain.valid, "Expected chain to be valid")
        assertEquals("https://issuer.example.com", chain.entityId)
        assertEquals("https://anchor.example.com", chain.trustAnchorId)
        assertEquals(2, chain.depth, "Expected 2 statements: self-signed + subordinate from anchor")
        // First statement should be self-signed
        assertTrue(chain.statements[0].isSelfSigned)
        assertEquals("https://issuer.example.com", chain.statements[0].issuer)
        // Second statement should be from the anchor
        assertEquals("https://anchor.example.com", chain.statements[1].issuer)
        assertEquals("https://issuer.example.com", chain.statements[1].subject)
    }

    @Test
    fun `buildChain returns invalid when first statement not self-signed`() = runTest {
        val config = FederationConfig(
            trustAnchors = listOf(TrustAnchor(entityId = "https://anchor.example.com"))
        )
        val mockEngine = buildMockEngine(
            mapOf(
                "issuer.example.com/.well-known/openid-federation" to notSelfSignedPayload
            )
        )
        val fetcher = EntityStatementFetcher(HttpClient(mockEngine))
        val builder = TrustChainBuilder(config, fetcher)

        val chain = builder.buildChain("https://issuer.example.com")

        assertNotNull(chain)
        assertFalse(chain.valid, "Expected chain to be invalid when first statement is not self-signed")
        assertEquals("First statement is not self-signed", chain.error)
        assertEquals(1, chain.depth, "Expected 1 statement (the non-self-signed one)")
    }

    @Test
    fun `buildChain succeeds with intermediate entity`() = runTest {
        val config = FederationConfig(
            trustAnchors = listOf(TrustAnchor(entityId = "https://anchor.example.com")),
            maxChainDepth = 5
        )
        // Leaf -> Intermediate -> Anchor
        val mockEngine = buildMockEngine(
            mapOf(
                "leaf.example.com/.well-known/openid-federation" to entityWithIntermediatePayload,
                "intermediate.example.com/fetch?sub=https://leaf.example.com" to intermediateSubordinatePayload,
                "intermediate.example.com/.well-known/openid-federation" to intermediateSelfSignedPayload,
                "anchor.example.com/fetch?sub=https://intermediate.example.com" to anchorAboutIntermediatePayload
            )
        )
        val fetcher = EntityStatementFetcher(HttpClient(mockEngine))
        val builder = TrustChainBuilder(config, fetcher)

        val chain = builder.buildChain("https://leaf.example.com")

        assertNotNull(chain)
        assertTrue(chain.valid, "Expected chain to be valid with intermediate hop")
        assertEquals("https://leaf.example.com", chain.entityId)
        assertEquals("https://anchor.example.com", chain.trustAnchorId)
        // Statements: leaf self-signed, intermediate subordinate about leaf,
        //             intermediate self-signed, anchor subordinate about intermediate
        assertEquals(4, chain.depth, "Expected 4 statements in leaf->intermediate->anchor chain")
    }

    @Test
    fun `buildChain handles depth exceeded`() = runTest {
        // Set maxChainDepth to 1, but the chain requires intermediate hops
        val config = FederationConfig(
            trustAnchors = listOf(TrustAnchor(entityId = "https://anchor.example.com")),
            maxChainDepth = 1
        )
        // The entity points to an intermediate, not directly to the anchor.
        // With maxChainDepth=1, the loop only runs once and resolves the intermediate,
        // resulting in 3 statements (>= maxChainDepth=1), triggering depth exceeded.
        val mockEngine = buildMockEngine(
            mapOf(
                "leaf.example.com/.well-known/openid-federation" to entityWithIntermediatePayload,
                "intermediate.example.com/fetch?sub=https://leaf.example.com" to intermediateSubordinatePayload,
                "intermediate.example.com/.well-known/openid-federation" to intermediateSelfSignedPayload
            )
        )
        val fetcher = EntityStatementFetcher(HttpClient(mockEngine))
        val builder = TrustChainBuilder(config, fetcher)

        val chain = builder.buildChain("https://leaf.example.com")

        assertNotNull(chain)
        assertFalse(chain.valid, "Expected chain to be invalid when depth exceeded")
        assertNotNull(chain.error)
        assertTrue(chain.error.orEmpty().contains("exceeds max depth"), "Expected depth exceeded error message")
    }

    @Test
    fun `buildChain returns invalid when no anchor found`() = runTest {
        val config = FederationConfig(
            trustAnchors = listOf(TrustAnchor(entityId = "https://anchor.example.com")),
            maxChainDepth = 5
        )
        // The orphan entity points to an unknown authority that has no further hints.
        // The unknown authority is NOT a trust anchor, so the chain cannot be completed.
        val mockEngine = buildMockEngine(
            mapOf(
                "orphan.example.com/.well-known/openid-federation" to entityNoAnchorPayload,
                "unknown-authority.example.com/fetch?sub=https://orphan.example.com" to unknownAuthoritySubordinatePayload,
                "unknown-authority.example.com/.well-known/openid-federation" to unknownAuthoritySelfSignedPayload
            )
        )
        val fetcher = EntityStatementFetcher(HttpClient(mockEngine))
        val builder = TrustChainBuilder(config, fetcher)

        val chain = builder.buildChain("https://orphan.example.com")

        assertNotNull(chain)
        assertFalse(chain.valid, "Expected chain to be invalid when no anchor is reachable")
        assertEquals("", chain.trustAnchorId, "Expected empty trustAnchorId when no anchor found")
    }

    @Test
    fun `buildChain returns error when fetch fails`() = runTest {
        val config = FederationConfig(
            trustAnchors = listOf(TrustAnchor(entityId = "https://anchor.example.com"))
        )
        // All requests fail
        val mockEngine = MockEngine { _ ->
            respond(
                content = "Service Unavailable",
                status = HttpStatusCode.ServiceUnavailable
            )
        }
        val fetcher = EntityStatementFetcher(HttpClient(mockEngine))
        val builder = TrustChainBuilder(config, fetcher)

        val chain = builder.buildChain("https://issuer.example.com")

        assertNotNull(chain)
        assertFalse(chain.valid, "Expected chain to be invalid when fetch fails")
        assertNotNull(chain.error, "Expected error message when fetch fails")
    }

    @Test
    fun `buildChain returns valid false with empty statements when entity has no authority hints`() = runTest {
        // Entity is self-signed but has no authority_hints to walk
        val noHintsPayload = """{
            "iss": "https://standalone.example.com",
            "sub": "https://standalone.example.com",
            "iat": 1700000000,
            "exp": 1700086400
        }"""
        val config = FederationConfig(
            trustAnchors = listOf(TrustAnchor(entityId = "https://anchor.example.com"))
        )
        val mockEngine = buildMockEngine(
            mapOf(
                "standalone.example.com/.well-known/openid-federation" to noHintsPayload
            )
        )
        val fetcher = EntityStatementFetcher(HttpClient(mockEngine))
        val builder = TrustChainBuilder(config, fetcher)

        val chain = builder.buildChain("https://standalone.example.com")

        assertNotNull(chain)
        assertFalse(chain.valid, "Expected chain to be invalid when no authority hints lead to anchor")
        assertEquals("", chain.trustAnchorId)
        assertEquals(1, chain.depth, "Expected only the self-signed statement")
    }
}
