package id.walt.authop.claims

import id.walt.authop.config.RealmConfig
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Projects the wallet-disclosed claim set for an OID4VP realm down to the
 * id-token shape the RP will actually receive, based on the scopes that RP
 * requested and the realm's scope catalog.
 *
 * **Why a projector.** [ClaimMapper] extracts claims from a credential using a
 * realm-wide JSONPath mapping — a broad set that powers the consent screen's
 * "shared with merchant this session" view. The RP's id_token is strictly
 * narrower: only the catalog-declared `id_token_claim` per requested, satisfied
 * scope. Running both layers keeps PII transiting auth-op for consent display
 * without leaking it into the id_token the RP persists.
 *
 * **What is kept unconditionally.** Operator-stamped meta claims (`acr`, `amr`,
 * the realm claim) and any non-scope-catalog claim added by the complete step
 * survive — they describe the authentication, not the user. The stamped keys
 * are passed in [preservedClaimKeys] so this object stays ignorant of their
 * concrete names.
 *
 * **What is filtered.** Every claim that came from `claim_mapping` is dropped
 * by default. A scope is added back only if:
 *   1. The scope was requested on /authorize.
 *   2. The scope is present in the realm's scope catalog.
 *   3. Every name in the scope's `required_claims` is present in the disclosed
 *      map with a truthy value (not null, not JSON `false`).
 *   4. The scope's `id_token_claim` is non-null.
 *
 * When all four hold we emit `{id_token_claim: true}` — boolean only, never
 * the raw disclosed value. An age scope for an under-age user thus fails
 * gracefully: the DCQL path either disclosed `false` (newer wallets) or did
 * not disclose the claim at all; either way the scope is silently dropped
 * from the id_token without failing the login.
 *
 * Realms without an `oid4vp.scopes` catalog (static DCQL file mode) fall
 * through to "pass all claims" — preserves legacy behaviour for realms that
 * haven't been migrated.
 */
object ScopeProjector {

    fun project(
        realm: RealmConfig,
        requestedScopes: List<String>,
        disclosed: Map<String, JsonElement>,
        preservedClaimKeys: Set<String>,
    ): Map<String, JsonElement> {
        val catalog = realm.oid4vp?.scopes ?: return disclosed
        if (catalog.isEmpty()) return disclosed

        val result = LinkedHashMap<String, JsonElement>()
        preservedClaimKeys.forEach { key ->
            disclosed[key]?.let { result[key] = it }
        }

        requestedScopes.forEach { scope ->
            val def = catalog[scope] ?: return@forEach
            val idTokenClaim = def.idTokenClaim ?: return@forEach
            val allSatisfied = def.requiredClaims.all { name ->
                val value = disclosed[name] ?: return@all false
                // Truthy = present, not JSON null, not JSON false. A JSON
                // string "true"/"false" (occasionally emitted by older EUDI
                // PID wallets) is handled via booleanOrNull → null fallback
                // to "non-empty" below.
                val prim = (value as? JsonPrimitive) ?: return@all true
                when (val b = prim.booleanOrNull) {
                    null -> prim.content.isNotBlank() && prim.content != "null"
                    else -> b
                }
            }
            if (allSatisfied) {
                result[idTokenClaim] = JsonPrimitive(true)
            }
        }

        return result
    }
}
