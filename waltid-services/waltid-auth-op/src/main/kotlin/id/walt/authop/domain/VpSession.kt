package id.walt.authop.domain

import kotlinx.serialization.json.JsonObject

/**
 * Lifecycle state of a verifiable-presentation session proxied to verifier-api2.
 *
 * `PENDING` is the initial state; transitions to `SUCCESSFUL` / `UNSUCCESSFUL`
 * are driven by the verifier-api2 webhook callback.
 */
enum class VpSessionStatus { PENDING, SUCCESSFUL, UNSUCCESSFUL }

/**
 * The credential payload captured when a VP presentation succeeds.
 *
 * Kept as plain [JsonObject] intentionally — we don't need to deserialize into
 * walt's `DigitalCredential` here; the `/complete` handler JSONPath-extracts
 * whichever claims the realm mapping requires.
 *
 * Mirrors verifier-api2's `Map<String, List<DigitalCredential>>` shape.
 */
data class CapturedCredential(
    val presentedCredentials: JsonObject,
    val presentedPresentations: JsonObject
)

/**
 * A VP session opened against verifier-api2 for a user currently authenticating.
 *
 * @property verifierSessionId session ID assigned by verifier-api2 (primary key in the store)
 * @property sessionCookieId the auth-op session cookie at the time the VP was kicked off —
 *           `/complete` refuses to attach captured credentials to a different session,
 *           preventing cross-session hijack of a presentation in flight
 * @property webhookSecret shared with verifier-api2; presented back on webhook callback so
 *           we can authenticate an inbound `status=SUCCESSFUL` update
 * @property capturedCredential null until the webhook arrives
 */
data class VpSession(
    val verifierSessionId: String,
    val realmId: String,
    val authRequestId: String,
    val sessionCookieId: String,
    val webhookSecret: String,
    val status: VpSessionStatus,
    val capturedCredential: CapturedCredential?
)
