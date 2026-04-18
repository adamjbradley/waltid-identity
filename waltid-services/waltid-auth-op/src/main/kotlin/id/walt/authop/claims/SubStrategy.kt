package id.walt.authop.claims

import com.nfeld.jsonpathkt.JsonPath
import com.nfeld.jsonpathkt.kotlinx.resolveOrNull
import id.walt.authop.config.SubStrategy
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * Derives the OIDC `sub` claim for OID4VP realms from a verified credential.
 *
 * Named [SubDerivation] (not `SubStrategy`) to avoid a name collision with the
 * config enum [SubStrategy] in `AuthOpConfig.kt`; the enum is the realm-config
 * surface, this object is the runtime helper that consumes it.
 *
 * ### Determinism
 *
 * - [SubStrategy.CREDENTIAL_SUBJECT_ID] — pass-through of `$.credentialSubject.id`.
 *   Deterministic and stable across logins (the wallet's VC is the truth).
 * - [SubStrategy.CLAIM_HASH] — `BASE64URL(SHA-256(realmId ‖ \u0000 ‖ joinedClaims))`.
 *   Deterministic across logins (same realmId + same claim values = same sub).
 *   Different realms produce different subs for the same person — a privacy
 *   guarantee: one RP's `sub` cannot be linked to another RP's `sub` unless
 *   both realms use the same source claims AND both realms share the same id.
 * - [SubStrategy.EPHEMERAL] — 128 bits of [SecureRandom] per login. Ignores
 *   realmId and claims; the user is unlinkable across sessions. For
 *   privacy-preserving RPs that don't need continuity.
 *
 * ### CLAIM_HASH missing-claim policy
 *
 * Absent source claims are substituted with the empty string. This keeps the
 * hash stable (the operator chose a fixed list; a wallet that omitted one
 * should still produce *a* deterministic sub rather than a crash), and the
 * NUL separator prevents `["a", ""]` colliding with `["a\u0000"]`.
 *
 * ### Claim extraction
 *
 * For CLAIM_HASH, source claim names are treated as plain top-level field
 * names into the credential JSON — NOT JSONPath. This is deliberate: `sub`
 * source claims are almost always trivial field lookups (`email`,
 * `nationalId`, …); JSONPath would add parse-time failure modes with zero
 * benefit on this path. Realms that need nested extraction should project
 * the claim into a top-level name via [ClaimMapper] first.
 */
object SubDerivation {
    private val secureRandom = SecureRandom()

    fun derive(
        strategy: SubStrategy,
        realmId: String,
        credential: JsonObject,
        sourceClaimNames: List<String>,
    ): String = when (strategy) {
        SubStrategy.CREDENTIAL_SUBJECT_ID -> {
            // Pass-through: resolve `$.credentialSubject.id` on the credential
            // body. Null / missing / non-string means the realm-config picked
            // CREDENTIAL_SUBJECT_ID but the wallet didn't supply a
            // credentialSubject.id — hard error so the operator fixes either
            // the realm config or the DCQL (the credential is unusable for a
            // sub under this strategy).
            val compiled = JsonPath.compile("$.credentialSubject.id")
            val value: JsonElement? = credential.resolveOrNull(compiled)
            val asString = (value as? JsonPrimitive)?.let { if (it.isString) it.content else null }
            require(!asString.isNullOrBlank()) {
                "CREDENTIAL_SUBJECT_ID: credential has no non-empty '\$.credentialSubject.id'"
            }
            asString
        }

        SubStrategy.CLAIM_HASH -> {
            // For each configured source claim, pick the primitive string
            // value (or empty string if absent / non-primitive). Using
            // contentOrNull keeps JSON `null` → "", matching missing.
            val values = sourceClaimNames.map { name ->
                (credential[name] as? JsonPrimitive)?.contentOrNull ?: ""
            }
            // BASE64URL(SHA-256(realmId ‖ \u0000 ‖ joinNul(claimValues)))
            val input = (listOf(realmId) + values).joinToString(NUL)
            val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
            Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
        }

        SubStrategy.EPHEMERAL -> {
            // 128 bits of urandom, Base64URL. Independent of realmId /
            // claims — the whole point is unlinkability.
            val bytes = ByteArray(16).also { secureRandom.nextBytes(it) }
            Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        }
    }

    /** NUL separator — keeps `["a", ""]` and `["a\u0000"]` distinct. */
    private const val NUL = "\u0000"
}
