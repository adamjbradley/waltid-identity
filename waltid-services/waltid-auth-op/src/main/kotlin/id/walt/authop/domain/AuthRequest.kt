package id.walt.authop.domain

import kotlinx.serialization.json.JsonElement

/**
 * The in-flight OIDC authorization request. Created at `/authorize`, updated as the
 * user progresses through realm selection and login, and consumed at code issuance.
 *
 * Designed immutable — the [id.walt.authop.store.AuthRequestStore] holds a copy and
 * exposes `update(transform)` for mutations. This keeps the Caffeine-backed store from
 * having to reason about concurrent mutation of shared value instances.
 *
 * @property authRequestId internal opaque ID (not the client's `state`)
 * @property codeChallengeMethod kept as a string (v1 only accepts `S256`) to avoid
 *           leaking a protocol enum into the domain layer
 * @property prompt `"none"`, `"login"`, or null — OIDC prompt parameter
 * @property chosenRealmId null until the user picks a realm
 * @property subject null until login completes
 * @property claims filled in at login completion; empty until then
 * @property activeVpSessionId For OID4VP realms only: the verifier-api2 session
 *           id of the VP flow that is currently in progress for this auth request.
 *           Stamped by the VP kickoff route so the recovery path (refreshing the
 *           QR page after success) can look up the already-SUCCESSFUL VpSession
 *           without needing a secondary index on [id.walt.authop.store.VpSessionStore].
 *           Null for OIDC realms and until VP kickoff runs.
 */
data class AuthRequest(
    val authRequestId: String,
    val clientId: String,
    val redirectUri: String,
    val scope: List<String>,
    val state: String?,
    val nonce: String?,
    val codeChallenge: String,
    val codeChallengeMethod: String,
    val prompt: String?,
    val chosenRealmId: String?,
    val subject: String?,
    val claims: Map<String, JsonElement>,
    val activeVpSessionId: String? = null,
    /**
     * Flow-update session id bound to this auth request when the RP asked
     * for the `preferences` scope and consent POST kicked off the n8n
     * workflow. Stamped in the consent POST handler, consumed in
     * `/consent/flow-done` to look up the aggregate from
     * [id.walt.authop.store.FlowUpdateStore].
     *
     * Null for flows that don't request `preferences`.
     */
    val flowSessionId: String? = null,
    /**
     * The aggregate JSON from the n8n workflow (shape:
     * `{customerId, alcohol, fraud: {darkWeb, firstParty, combinedRiskScore, approved}}`).
     * Populated by `/consent/flow-done` once the workflow's final `aggregate`
     * callback has landed. Surfaces into the id_token and /userinfo under
     * the `preferences` claim key when the scope was requested.
     */
    val preferences: JsonElement? = null,
)
