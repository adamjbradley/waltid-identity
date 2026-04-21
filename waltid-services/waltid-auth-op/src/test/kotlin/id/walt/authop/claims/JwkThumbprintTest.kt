package id.walt.authop.claims

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Thumbprint correctness vectors for [JwkThumbprint].
 *
 * The RSA vector is taken verbatim from RFC 7638 §3.1 and is THE canonical
 * reference for the algorithm; if this test drifts, the helper is wrong.
 * The EC and OKP vectors are derived from the RFC's own input + the
 * canonicalisation rules (§3.2, §3.3) — they pin the lex-ordered member
 * set for each kty so a future refactor cannot silently swap field order.
 */
class JwkThumbprintTest {

    @Test
    fun `RFC 7638 section 3_1 RSA vector`() {
        // JWK and expected thumbprint copied verbatim from RFC 7638 §3.1.
        val jwk = buildJsonObject {
            put("kty", "RSA")
            put(
                "n",
                "0vx7agoebGcQSuuPiLJXZptN9nndrQmbXEps2aiAFbWhM78LhWx4cbbf" +
                    "AAtVT86zwu1RK7aPFFxuhDR1L6tSoc_BJECPebWKRXjBZCiFV4n3oknjhMst" +
                    "n64tZ_2W-5JsGY4Hc5n9yBXArwl93lqt7_RN5w6Cf0h4QyQ5v-65YGjQR0_F" +
                    "DW2QvzqY368QQMicAtaSqzs8KJZgnYb9c7d0zgdAZHzu6qMQvRL5hajrn1n9" +
                    "1CbOpbISD08qNLyrdkt-bFTWhAI4vMQFh6WeZu0fM4lFd2NcRwr3XPksINHa" +
                    "Q-G_xBniIqbw0Ls1jF44-csFCur-kEgU8awapJzKnqDKgw",
            )
            put("e", "AQAB")
            put("alg", "RS256")
            put("kid", "2011-04-29")
        }

        val thumbprint = JwkThumbprint.compute(jwk)

        // Expected value from RFC 7638 §3.1 final paragraph.
        assertEquals("NzbLsXh8uDCcd-6MNwXF4W_7noWXFZAfHkxZsRGC9Xs", thumbprint)
    }

    @Test
    fun `EC key canonicalisation in lexicographic order`() {
        val jwk = buildJsonObject {
            // Insertion order deliberately scrambled — canonical output
            // must still be crv, kty, x, y.
            put("y", "y-value-b64url")
            put("crv", "P-256")
            put("kty", "EC")
            put("x", "x-value-b64url")
            put("use", "sig") // extra member must be dropped
        }

        val canonical = JwkThumbprint.canonicalJson(jwk)
        assertEquals(
            """{"crv":"P-256","kty":"EC","x":"x-value-b64url","y":"y-value-b64url"}""",
            canonical,
        )
        // Thumbprint is SHA-256 of the canonical bytes, Base64URL-unpadded.
        // Don't assert a specific value here — canonicalJson already pins
        // the input to the hash.
        assertNotNull(JwkThumbprint.compute(jwk))
    }

    @Test
    fun `OKP key uses crv kty x only`() {
        val jwk = buildJsonObject {
            put("kty", "OKP")
            put("crv", "Ed25519")
            put("x", "11qYAYKxCrfVS_7TyWQHOg7hcvPapiMlrwIaaPcHURo")
            put("kid", "key-1") // must not appear in canonical form
        }
        val canonical = JwkThumbprint.canonicalJson(jwk)
        assertEquals(
            """{"crv":"Ed25519","kty":"OKP","x":"11qYAYKxCrfVS_7TyWQHOg7hcvPapiMlrwIaaPcHURo"}""",
            canonical,
        )
    }

    @Test
    fun `missing required members returns null`() {
        val ecMissingY: JsonObject = buildJsonObject {
            put("kty", "EC")
            put("crv", "P-256")
            put("x", "x")
            // no y
        }
        assertNull(JwkThumbprint.compute(ecMissingY))

        val rsaMissingN: JsonObject = buildJsonObject {
            put("kty", "RSA")
            put("e", "AQAB")
            // no n
        }
        assertNull(JwkThumbprint.compute(rsaMissingN))
    }

    @Test
    fun `unsupported kty returns null`() {
        val oct = buildJsonObject {
            put("kty", "oct")
            put("k", "secret")
        }
        assertNull(JwkThumbprint.compute(oct))

        val none = buildJsonObject {
            put("alg", "HS256")
        }
        assertNull(JwkThumbprint.compute(none))
    }
}
