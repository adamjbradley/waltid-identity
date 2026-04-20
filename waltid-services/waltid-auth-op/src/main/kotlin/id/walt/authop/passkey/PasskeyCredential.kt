@file:OptIn(ExperimentalTime::class)

package id.walt.authop.passkey

import kotlinx.serialization.Serializable
import kotlinx.datetime.Instant
import kotlin.time.ExperimentalTime

/**
 * A single WebAuthn credential bound to an auth-op sub.
 *
 * Stored on disk as a line of [PasskeyStore]'s JSON registry. Multiple
 * credentials per sub are allowed — a user who re-enrolls on a new device
 * accumulates passkeys rather than replacing existing ones.
 *
 * @property sub the auth-op sub claim (for the citizens realm: a claim_hash
 *   of given_name+family_name+birth_date). Must match the sub the VP flow
 *   minted at first enrolment.
 * @property credentialId base64url-encoded WebAuthn credential ID.
 * @property publicKeyCose base64url-encoded COSE_Key public key blob as
 *   returned by Yubico's `RegistrationResult.getPublicKeyCose().getBase64Url()`.
 * @property signatureCount monotonic authenticator counter for replay
 *   protection. Starts at 0; bumped on every successful assertion.
 * @property displayName a human-readable label that the authenticator
 *   shows to the user during login ceremonies (e.g. "Sarah Mitchell").
 *   Sourced from the wallet-asserted given_name+family_name.
 * @property createdAt UTC timestamp of first registration.
 */
@Serializable
data class PasskeyCredential(
    val sub: String,
    val credentialId: String,
    val publicKeyCose: String,
    val signatureCount: Long,
    val displayName: String,
    val createdAt: Instant,
)
