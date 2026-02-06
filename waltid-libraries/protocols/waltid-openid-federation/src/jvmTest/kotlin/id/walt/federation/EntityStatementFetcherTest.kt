package id.walt.federation

import id.walt.federation.exceptions.EntityStatementFetchException
import id.walt.federation.models.EntityStatement
import io.ktor.client.*
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.test.runTest
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class EntityStatementFetcherTest {

    /**
     * Creates a fake JWT (header.payload.signature) from a JSON payload string.
     * The header and signature are not cryptographically valid, but the payload
     * is base64url-encoded JSON that the fetcher's parser can decode.
     */
    private fun createFakeJwt(payload: String): String {
        val header = """{"alg":"none","typ":"JWT"}"""
        val encoder = Base64.getUrlEncoder().withoutPadding()
        val encodedHeader = encoder.encodeToString(header.toByteArray())
        val encodedPayload = encoder.encodeToString(payload.toByteArray())
        return "$encodedHeader.$encodedPayload.fakesig"
    }

    private val selfSignedPayload = """{
        "iss": "https://example.com",
        "sub": "https://example.com",
        "iat": 1700000000,
        "exp": 1700086400,
        "authority_hints": ["https://anchor.example.com"]
    }"""

    private val subordinatePayload = """{
        "iss": "https://anchor.example.com",
        "sub": "https://example.com",
        "iat": 1700000000,
        "exp": 1700086400
    }"""

    @Test
    fun `fetch parses JWT response correctly`() = runTest {
        val mockEngine = MockEngine { _ ->
            respond(
                content = createFakeJwt(selfSignedPayload),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/entity-statement+jwt")
            )
        }
        val fetcher = EntityStatementFetcher(HttpClient(mockEngine))

        val statement = fetcher.fetch("https://example.com")

        assertEquals("https://example.com", statement.issuer)
        assertEquals("https://example.com", statement.subject)
        assertEquals(1700000000L, statement.issuedAt)
        assertEquals(1700086400L, statement.expiresAt)
        assertEquals(listOf("https://anchor.example.com"), statement.authorityHints)
        assertTrue(statement.isSelfSigned)
    }

    @Test
    fun `fetch builds correct well-known URL`() = runTest {
        var capturedUrl: String? = null
        val mockEngine = MockEngine { request ->
            capturedUrl = request.url.toString()
            respond(
                content = createFakeJwt(selfSignedPayload),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/entity-statement+jwt")
            )
        }
        val fetcher = EntityStatementFetcher(HttpClient(mockEngine))

        fetcher.fetch("https://example.com")

        assertEquals("https://example.com/.well-known/openid-federation", capturedUrl)
    }

    @Test
    fun `fetch builds correct well-known URL with trailing slash`() = runTest {
        var capturedUrl: String? = null
        val mockEngine = MockEngine { request ->
            capturedUrl = request.url.toString()
            respond(
                content = createFakeJwt(selfSignedPayload),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/entity-statement+jwt")
            )
        }
        val fetcher = EntityStatementFetcher(HttpClient(mockEngine))

        fetcher.fetch("https://example.com/")

        assertEquals("https://example.com/.well-known/openid-federation", capturedUrl)
    }

    @Test
    fun `fetch caches results`() = runTest {
        var requestCount = 0
        val mockEngine = MockEngine { _ ->
            requestCount++
            respond(
                content = createFakeJwt(selfSignedPayload),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/entity-statement+jwt")
            )
        }
        val fetcher = EntityStatementFetcher(HttpClient(mockEngine))

        val first = fetcher.fetch("https://example.com")
        val second = fetcher.fetch("https://example.com")

        assertEquals(1, requestCount, "Expected only one HTTP request due to caching")
        assertEquals(first, second)
    }

    @Test
    fun `clearCache invalidates cache`() = runTest {
        var requestCount = 0
        val mockEngine = MockEngine { _ ->
            requestCount++
            respond(
                content = createFakeJwt(selfSignedPayload),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/entity-statement+jwt")
            )
        }
        val fetcher = EntityStatementFetcher(HttpClient(mockEngine))

        fetcher.fetch("https://example.com")
        assertEquals(1, requestCount)

        fetcher.clearCache()
        fetcher.fetch("https://example.com")
        assertEquals(2, requestCount, "Expected second HTTP request after cache was cleared")
    }

    @Test
    fun `fetch throws EntityStatementFetchException on network error`() = runTest {
        val mockEngine = MockEngine { _ ->
            respond(
                content = "Internal Server Error",
                status = HttpStatusCode.InternalServerError
            )
        }
        val fetcher = EntityStatementFetcher(HttpClient(mockEngine))

        val exception = assertFailsWith<EntityStatementFetchException> {
            fetcher.fetch("https://example.com")
        }
        assertTrue(exception.message!!.contains("https://example.com"))
    }

    @Test
    fun `fetchSubordinateStatement builds correct URL`() = runTest {
        var capturedUrl: String? = null
        val mockEngine = MockEngine { request ->
            capturedUrl = request.url.toString()
            respond(
                content = createFakeJwt(subordinatePayload),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/entity-statement+jwt")
            )
        }
        val fetcher = EntityStatementFetcher(HttpClient(mockEngine))

        val statement = fetcher.fetchSubordinateStatement(
            "https://anchor.example.com",
            "https://example.com"
        )

        assertEquals("https://anchor.example.com/fetch?sub=https://example.com", capturedUrl)
        assertEquals("https://anchor.example.com", statement.issuer)
        assertEquals("https://example.com", statement.subject)
    }

    @Test
    fun `fetchSubordinateStatement throws EntityStatementFetchException on error`() = runTest {
        val mockEngine = MockEngine { _ ->
            respond(
                content = "Not Found",
                status = HttpStatusCode.NotFound
            )
        }
        val fetcher = EntityStatementFetcher(HttpClient(mockEngine))

        val exception = assertFailsWith<EntityStatementFetchException> {
            fetcher.fetchSubordinateStatement(
                "https://anchor.example.com",
                "https://unknown.example.com"
            )
        }
        assertTrue(exception.message!!.contains("https://unknown.example.com"))
    }

    @Test
    fun `fetch rejects invalid JWT with wrong number of parts`() = runTest {
        val mockEngine = MockEngine { _ ->
            respond(
                content = "not-a-jwt",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/entity-statement+jwt")
            )
        }
        val fetcher = EntityStatementFetcher(HttpClient(mockEngine))

        val exception = assertFailsWith<EntityStatementFetchException> {
            fetcher.fetch("https://example.com")
        }
        // The root cause should be an IllegalArgumentException about invalid JWT format
        assertTrue(exception.cause is IllegalArgumentException)
        assertTrue(exception.cause!!.message!!.contains("Invalid JWT format"))
    }

    @Test
    fun `fetch handles entity statement with minimal fields`() = runTest {
        val minimalPayload = """{"iss": "https://minimal.example.com", "sub": "https://minimal.example.com"}"""
        val mockEngine = MockEngine { _ ->
            respond(
                content = createFakeJwt(minimalPayload),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/entity-statement+jwt")
            )
        }
        val fetcher = EntityStatementFetcher(HttpClient(mockEngine))

        val statement = fetcher.fetch("https://minimal.example.com")

        assertEquals("https://minimal.example.com", statement.issuer)
        assertEquals("https://minimal.example.com", statement.subject)
        assertEquals(null, statement.issuedAt)
        assertEquals(null, statement.expiresAt)
        assertEquals(null, statement.authorityHints)
        assertTrue(statement.isSelfSigned)
    }

    @Test
    fun `fetch caches different entity IDs separately`() = runTest {
        val payloadA = """{"iss": "https://a.example.com", "sub": "https://a.example.com"}"""
        val payloadB = """{"iss": "https://b.example.com", "sub": "https://b.example.com"}"""
        var requestCount = 0
        val mockEngine = MockEngine { request ->
            requestCount++
            val payload = if (request.url.toString().contains("a.example.com")) payloadA else payloadB
            respond(
                content = createFakeJwt(payload),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/entity-statement+jwt")
            )
        }
        val fetcher = EntityStatementFetcher(HttpClient(mockEngine))

        val statementA = fetcher.fetch("https://a.example.com")
        val statementB = fetcher.fetch("https://b.example.com")

        assertEquals(2, requestCount, "Expected two HTTP requests for different entity IDs")
        assertEquals("https://a.example.com", statementA.issuer)
        assertEquals("https://b.example.com", statementB.issuer)
    }
}
