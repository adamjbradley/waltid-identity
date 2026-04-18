@file:OptIn(ExperimentalTime::class)

package id.walt.authop.domain

import kotlinx.serialization.json.JsonElement
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * A single-use authorization code issued by `/authorize` and redeemed at `/token`.
 *
 * The [id.walt.authop.store.AuthCodeStore] enforces single-use semantics via
 * `consume` (read + remove atomically).
 *
 * `clientId`, `redirectUri`, and the PKCE challenge are captured here so the token
 * endpoint can verify the redemption without re-reading the original auth request.
 *
 * `nonce`, `authTime`, and `scope` are captured at code-mint time so the token
 * endpoint can emit the corresponding ID-token / access-token claims without
 * reaching back into the AuthRequest (which has already been deleted for
 * single-use flow enforcement). `authTime` is the moment the code was minted —
 * a reasonable MVP approximation of "when the user authenticated", since for
 * the direct-login flow that moment is the same wall-clock second.
 *
 * `authTime` uses [kotlin.time.Instant] because this type lives in-memory only
 * (AuthCodeStore is not serialized today). When a Valkey-backed store hook
 * arrives, switch to `kotlinx.datetime.Instant`.
 */
data class AuthCode(
    val code: String,
    val clientId: String,
    val redirectUri: String,
    val subject: String,
    val claims: Map<String, JsonElement>,
    val codeChallenge: String,
    val codeChallengeMethod: String,
    val nonce: String? = null,
    val authTime: Instant,
    val scope: List<String> = emptyList(),
)
