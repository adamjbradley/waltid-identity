@file:OptIn(ExperimentalTime::class)

package id.walt.authop.domain

import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * A user session created after successful login and bound to the `sid` cookie.
 *
 * For OIDC-realm sessions [upstreamIdToken] holds the ID token received from the
 * upstream provider — used as `id_token_hint` on RP-initiated logout. It is NEVER
 * issued downstream to the relying party (the auth-op mints its own ID tokens).
 *
 * `authTime` uses [kotlin.time.Instant] because this type is in-memory only
 * (the session store is not serialized today). When a store-level serialization
 * hook is added for Valkey, switch to `kotlinx.datetime.Instant`.
 */
data class Session(
    val sessionId: String,
    val subject: String,
    val realmId: String,
    val amr: List<String>,
    val acr: String?,
    val authTime: Instant,
    val upstreamIdToken: String?
)
