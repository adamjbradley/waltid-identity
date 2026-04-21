package id.walt.authop.claims

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.security.MessageDigest
import java.util.Base64

/**
 * RFC 7638 JWK thumbprint — the SHA-256 hash of the JWK canonicalised to
 * exactly the required members in lexicographic order, JSON-encoded
 * without whitespace, Base64URL-encoded without padding.
 *
 * **Why this helper exists in auth-op.** The citizens-realm VP flow needs
 * to project a `cnf_jkt` claim into the id-token (and into the consent-
 * screen preview) so the downstream RP can bind its session to the
 * wallet's holder-binding key without learning a globally stable `sub`.
 * Thumbprint computation is a tiny, self-contained routine; pulling in
 * a dependency would be heavier than the inline 15-line helper.
 *
 * **Supported key types** (per RFC 7638 §3.2–§3.4):
 *  - `EC` — required members: `crv`, `kty`, `x`, `y`
 *  - `RSA` — required members: `e`, `kty`, `n`
 *  - `OKP` — required members: `crv`, `kty`, `x`
 *
 * Any other key type (or a missing required member) returns null — the
 * caller treats that as "no binding" and skips the claim. We never throw;
 * an unbindable presentation still logs the user in, just without the
 * cnf_jkt anchor.
 *
 * **Canonicalisation.** JSON keys in strict lexicographic order, ASCII
 * only, no whitespace, strings wrapped in `"`. We hand-write the JSON
 * instead of relying on [kotlinx.serialization.json.Json]'s default
 * emission order because kotlinx-serialization's JsonObject preserves
 * insertion order rather than sorting — a subtly non-canonical output
 * that would produce different thumbprints across different callers.
 */
object JwkThumbprint {

    /**
     * Compute the SHA-256 JWK thumbprint of [jwk]. Returns null on
     * unsupported key types or malformed input.
     */
    fun compute(jwk: JsonObject): String? {
        val canonical = canonicalJson(jwk) ?: return null
        val digest = MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8))
        return Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
    }

    /** Hand-written canonical JSON per RFC 7638 rules. Visible for testing. */
    internal fun canonicalJson(jwk: JsonObject): String? {
        val kty = jwk["kty"]?.let { (it as? JsonPrimitive)?.contentOrNull } ?: return null
        return when (kty) {
            "EC" -> {
                val crv = stringMember(jwk, "crv") ?: return null
                val x = stringMember(jwk, "x") ?: return null
                val y = stringMember(jwk, "y") ?: return null
                """{"crv":${quote(crv)},"kty":${quote(kty)},"x":${quote(x)},"y":${quote(y)}}"""
            }
            "RSA" -> {
                val e = stringMember(jwk, "e") ?: return null
                val n = stringMember(jwk, "n") ?: return null
                """{"e":${quote(e)},"kty":${quote(kty)},"n":${quote(n)}}"""
            }
            "OKP" -> {
                val crv = stringMember(jwk, "crv") ?: return null
                val x = stringMember(jwk, "x") ?: return null
                """{"crv":${quote(crv)},"kty":${quote(kty)},"x":${quote(x)}}"""
            }
            else -> null
        }
    }

    private fun stringMember(jwk: JsonObject, name: String): String? =
        (jwk[name] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

    /**
     * RFC 7638-compatible JSON string escape. JWK members are Base64URL
     * (no `"`, no backslash, no control chars), so the full escape table
     * is overkill in practice — but we implement the required subset so a
     * future non-Base64URL member (e.g. a key id) would still canonicalise
     * correctly.
     */
    private fun quote(s: String): String {
        val sb = StringBuilder(s.length + 2)
        sb.append('"')
        for (ch in s) {
            when (ch) {
                '\\' -> sb.append("\\\\")
                '"' -> sb.append("\\\"")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (ch.code < 0x20) sb.append("\\u%04x".format(ch.code)) else sb.append(ch)
            }
        }
        sb.append('"')
        return sb.toString()
    }
}
