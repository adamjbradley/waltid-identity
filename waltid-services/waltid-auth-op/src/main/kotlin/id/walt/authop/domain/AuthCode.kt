package id.walt.authop.domain

import kotlinx.serialization.json.JsonElement

/**
 * A single-use authorization code issued by `/authorize` and redeemed at `/token`.
 *
 * The [id.walt.authop.store.AuthCodeStore] enforces single-use semantics via
 * `consume` (read + remove atomically).
 *
 * `clientId`, `redirectUri`, and the PKCE challenge are captured here so the token
 * endpoint can verify the redemption without re-reading the original auth request.
 */
data class AuthCode(
    val code: String,
    val clientId: String,
    val redirectUri: String,
    val subject: String,
    val claims: Map<String, JsonElement>,
    val codeChallenge: String,
    val codeChallengeMethod: String
)
