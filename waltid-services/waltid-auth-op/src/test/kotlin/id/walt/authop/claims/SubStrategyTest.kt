package id.walt.authop.claims

import id.walt.authop.config.SubStrategy
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.security.MessageDigest
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Tests for [SubDerivation]. The derive function is the exact point where a
 * presented credential is turned into the OIDC `sub` claim of the minted ID
 * token — correctness and determinism here are security-critical.
 *
 * Test names for the four required cases match the plan verbatim; the two
 * additional tests pin the empty-string-on-missing and hard-failure
 * contracts.
 */
class SubStrategyTest {

    // Helper: credential body used by the happy-path tests.
    private fun aliceCredential() = buildJsonObject {
        putJsonObject("credentialSubject") {
            put("id", "did:example:alice")
        }
        put("email", "alice@example.com")
        put("given_name", "Alice")
    }

    // ---- required (verbatim names) ------------------------------------------

    @Test
    fun `credential_subject_id uses VC id`() {
        val sub = SubDerivation.derive(
            strategy = SubStrategy.CREDENTIAL_SUBJECT_ID,
            realmId = "vp-realm",
            credential = aliceCredential(),
            sourceClaimNames = emptyList(), // ignored for this strategy
        )
        assertEquals("did:example:alice", sub)
    }

    @Test
    fun `claim_hash is deterministic for same inputs`() {
        val cred = aliceCredential()
        val a = SubDerivation.derive(SubStrategy.CLAIM_HASH, "vp-realm", cred, listOf("email", "given_name"))
        val b = SubDerivation.derive(SubStrategy.CLAIM_HASH, "vp-realm", cred, listOf("email", "given_name"))
        assertEquals(a, b, "CLAIM_HASH must be a pure function of (realmId, credential, sourceClaimNames)")

        // Defensive cross-check: the output really is the spec formula —
        // BASE64URL(SHA-256(realmId || \u0000 || joinNul(values))).
        val expectedInput = listOf("vp-realm", "alice@example.com", "Alice").joinToString("\u0000")
        val expectedDigest = MessageDigest.getInstance("SHA-256")
            .digest(expectedInput.toByteArray(Charsets.UTF_8))
        val expected = Base64.getUrlEncoder().withoutPadding().encodeToString(expectedDigest)
        assertEquals(expected, a, "hash must follow the exact spec formula")
    }

    @Test
    fun `claim_hash differs across realms for same person`() {
        val cred = aliceCredential()
        val subA = SubDerivation.derive(SubStrategy.CLAIM_HASH, "realm-a", cred, listOf("email"))
        val subB = SubDerivation.derive(SubStrategy.CLAIM_HASH, "realm-b", cred, listOf("email"))
        assertNotEquals(
            subA, subB,
            "Same person across different realms MUST produce different subs (privacy / unlinkability)",
        )
    }

    @Test
    fun `ephemeral produces different sub each call`() {
        val cred = aliceCredential()
        val a = SubDerivation.derive(SubStrategy.EPHEMERAL, "vp-realm", cred, emptyList())
        val b = SubDerivation.derive(SubStrategy.EPHEMERAL, "vp-realm", cred, emptyList())
        val c = SubDerivation.derive(SubStrategy.EPHEMERAL, "vp-realm", cred, emptyList())
        assertNotEquals(a, b)
        assertNotEquals(b, c)
        assertNotEquals(a, c)
        assertTrue(a.isNotBlank() && b.isNotBlank() && c.isNotBlank())
    }

    // ---- additional (from plan's "Plus" list) --------------------------------

    @Test
    fun `claim_hash with missing source claims still produces deterministic hash with empty strings`() {
        val cred = buildJsonObject {
            put("email", "bob@example.com")
            // no `given_name` — substituted with "" per the policy docs
        }
        val a = SubDerivation.derive(
            SubStrategy.CLAIM_HASH, "vp-realm", cred, listOf("email", "given_name"),
        )
        val b = SubDerivation.derive(
            SubStrategy.CLAIM_HASH, "vp-realm", cred, listOf("email", "given_name"),
        )
        assertEquals(a, b, "missing-claim substitution must still be deterministic")

        // And the substitution is ACTUALLY empty string (not the literal
        // "null"): compare against the explicit formula.
        val expectedInput = listOf("vp-realm", "bob@example.com", "").joinToString("\u0000")
        val expectedDigest = MessageDigest.getInstance("SHA-256")
            .digest(expectedInput.toByteArray(Charsets.UTF_8))
        val expected = Base64.getUrlEncoder().withoutPadding().encodeToString(expectedDigest)
        assertEquals(expected, a)
    }

    @Test
    fun `derive throws on CREDENTIAL_SUBJECT_ID when credential has no id`() {
        val cred = buildJsonObject {
            putJsonObject("credentialSubject") {
                put("given_name", "Alice")
                // no "id"
            }
        }
        val ex = assertFailsWith<IllegalArgumentException> {
            SubDerivation.derive(
                SubStrategy.CREDENTIAL_SUBJECT_ID,
                "vp-realm",
                cred,
                sourceClaimNames = emptyList(),
            )
        }
        assertTrue(
            ex.message!!.contains("CREDENTIAL_SUBJECT_ID"),
            "error must mention the offending strategy: ${ex.message}",
        )
    }

    @Test
    fun `ephemeral ignores realm and claims — randomness dominates`() {
        // Two calls with identical inputs must still produce different output
        // (this is the security invariant: EPHEMERAL is unlinkable).
        val cred = aliceCredential()
        val a = SubDerivation.derive(SubStrategy.EPHEMERAL, "x", cred, listOf("email"))
        val b = SubDerivation.derive(SubStrategy.EPHEMERAL, "x", cred, listOf("email"))
        assertNotEquals(a, b)
    }

    @Test
    fun `claim_hash output is base64url with no padding`() {
        val cred = aliceCredential()
        val sub = SubDerivation.derive(
            SubStrategy.CLAIM_HASH, "r", cred, listOf("email"),
        )
        assertTrue('+' !in sub && '/' !in sub, "must use base64url alphabet: $sub")
        assertTrue('=' !in sub, "must be unpadded: $sub")
    }
}
