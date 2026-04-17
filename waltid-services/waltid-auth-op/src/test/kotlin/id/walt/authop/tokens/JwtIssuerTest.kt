@file:OptIn(ExperimentalTime::class)

package id.walt.authop.tokens

import id.walt.authop.testKey
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * Covers the minting surface of [JwtIssuer].
 *
 * **Parse strategy (Option C from Task 7 spec).** Tests invoke
 * [id.walt.crypto.keys.jwk.JWKKey.verifyJws] — the walt crypto abstraction —
 * instead of Nimbus directly. It returns a `Result<JsonElement>` wrapping the
 * decoded payload on success, which doubles as the signature check. That keeps
 * the test consistent with the rest of the repo (no double source of truth
 * about what "a valid JWT" means) and lets the signature-tamper assertion be a
 * `.isFailure` check without constructing a standalone verifier.
 *
 * The header is parsed manually (base64url-decode the first compact segment);
 * verifyJws doesn't surface it. That's fine — there's only one header we care
 * to assert on here (`kid`).
 */
class JwtIssuerTest {

    /** Fixed clock so iat/exp assertions are deterministic. */
    private val testClock = TestClock(Instant.fromEpochSeconds(1_700_000_000))
    private val signingKey = testKey()
    private val issuer = JwtIssuer(
        key = signingKey,
        iss = "https://auth.example",
        lifetime = 1.hours,
        clock = testClock,
    )

    @Test
    fun `id token contains required claims and verifies against jwks`() = runTest {
        val token = issuer.mintIdToken(
            sub = "did:example:123",
            aud = "rp1",
            nonce = "n",
            claims = mapOf(
                "realm" to JsonPrimitive("citizens"),
                "acr" to JsonPrimitive("urn:walt:vp"),
            ),
        )
        val payload = signingKey.getPublicKey().verifyJws(token).getOrThrow() as JsonObject
        assertEquals("https://auth.example", payload["iss"]?.jsonPrimitive?.content)
        assertEquals("rp1", payload["aud"]?.jsonPrimitive?.content)
        assertEquals("did:example:123", payload["sub"]?.jsonPrimitive?.content)
        assertEquals("n", payload["nonce"]?.jsonPrimitive?.content)
        assertEquals("citizens", payload["realm"]?.jsonPrimitive?.content)
        assertEquals("urn:walt:vp", payload["acr"]?.jsonPrimitive?.content)
        assertEquals(1_700_000_000L, payload["iat"]?.jsonPrimitive?.content?.toLong())
        assertEquals(1_700_000_000L + 3600L, payload["exp"]?.jsonPrimitive?.content?.toLong())
    }

    @Test
    fun `access token lifetime honoured`() = runTest {
        val shortLived = JwtIssuer(
            key = signingKey,
            iss = "https://auth.example",
            lifetime = 90.seconds,
            clock = testClock,
        )
        val token = shortLived.mintAccessToken(
            sub = "did:example:123",
            aud = "rp1",
            scope = listOf("openid"),
        )
        val payload = signingKey.getPublicKey().verifyJws(token).getOrThrow() as JsonObject
        val iat = payload["iat"]!!.jsonPrimitive.content.toLong()
        val exp = payload["exp"]!!.jsonPrimitive.content.toLong()
        assertEquals(90L, exp - iat, "exp - iat should equal the configured lifetime in seconds")
    }

    @Test
    fun `id token omits nonce when null`() = runTest {
        val token = issuer.mintIdToken(sub = "s", aud = "rp1", nonce = null)
        val payload = signingKey.getPublicKey().verifyJws(token).getOrThrow() as JsonObject
        assertFalse("nonce" in payload, "nonce must be absent, not present-with-null")
    }

    @Test
    fun `id token includes auth_time when provided`() = runTest {
        val authTime = Instant.fromEpochSeconds(1_699_999_000L)
        val token = issuer.mintIdToken(sub = "s", aud = "rp1", nonce = null, authTime = authTime)
        val payload = signingKey.getPublicKey().verifyJws(token).getOrThrow() as JsonObject
        assertEquals(1_699_999_000L, payload["auth_time"]?.jsonPrimitive?.content?.toLong())
    }

    @Test
    fun `access token scope is space-delimited`() = runTest {
        val token = issuer.mintAccessToken(
            sub = "s",
            aud = "rp1",
            scope = listOf("openid", "profile", "email"),
        )
        val payload = signingKey.getPublicKey().verifyJws(token).getOrThrow() as JsonObject
        assertEquals("openid profile email", payload["scope"]?.jsonPrimitive?.content)
        assertEquals("access_token", payload["token_use"]?.jsonPrimitive?.content)
    }

    @Test
    fun `id token header contains kid matching signing key`() = runTest {
        val token = issuer.mintIdToken(sub = "s", aud = "rp1", nonce = null)
        val header = parseHeader(token)
        assertEquals(signingKey.getKeyId(), header["kid"]?.jsonPrimitive?.content)
        assertEquals("JWT", header["typ"]?.jsonPrimitive?.content)
        assertNotNull(header["alg"], "alg must be present in header")
    }

    @Test
    fun `extra claims merge on top of standard claims`() = runTest {
        val token = issuer.mintIdToken(
            sub = "s",
            aud = "rp1",
            nonce = null,
            claims = mapOf(
                "given_name" to JsonPrimitive("Ada"),
                // Attempt to overwrite a standard claim — should be rejected by the merge order.
                "iss" to JsonPrimitive("https://attacker.example"),
            ),
        )
        val payload = signingKey.getPublicKey().verifyJws(token).getOrThrow() as JsonObject
        assertEquals("Ada", payload["given_name"]?.jsonPrimitive?.content)
        assertEquals(
            "https://auth.example",
            payload["iss"]?.jsonPrimitive?.content,
            "standard iss must not be overwriteable via extra claims",
        )
    }

    @Test
    fun `id token fails signature verification when tampered`() = runTest {
        val token = issuer.mintIdToken(sub = "s", aud = "rp1", nonce = null)
        val (header, payload, sig) = token.split(".").also { check(it.size == 3) }
        // Flip a byte in the base64url payload so the decoded bytes change.
        val tamperedPayload = payload.replaceRange(0, 1, if (payload[0] == 'A') "B" else "A")
        val tampered = "$header.$tamperedPayload.$sig"
        val result = signingKey.getPublicKey().verifyJws(tampered)
        assertTrue(result.isFailure, "tampered JWT must fail verification")
    }

    // --- helpers -------------------------------------------------------------

    /** Decode the compact JWS header segment into a JsonObject. */
    private fun parseHeader(jws: String): JsonObject {
        val headerB64 = jws.substringBefore('.')
        val json = java.util.Base64.getUrlDecoder().decode(headerB64).decodeToString()
        return kotlinx.serialization.json.Json.parseToJsonElement(json) as JsonObject
    }

    /**
     * Minimal test clock — `kotlin.time.Clock` is a SAM with `fun now(): Instant`.
     * We don't need advancement for these tests; if a future test does, switch to
     * a mutable backing field.
     */
    private class TestClock(private val instant: Instant) : Clock {
        override fun now(): Instant = instant
    }
}
