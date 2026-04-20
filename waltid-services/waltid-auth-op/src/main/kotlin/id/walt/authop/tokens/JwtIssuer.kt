@file:OptIn(ExperimentalTime::class)

package id.walt.authop.tokens

import id.walt.crypto.keys.jwk.JWKKey
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.Json
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * Mints the two JWTs the auth-op downstream endpoint hands to relying parties:
 * the OIDC Core §2 ID token and a JWT-shaped access token (RFC 7519 + RFC 6749 scope).
 *
 * **Signing.** Uses [JWKKey.signJws] — the same crypto abstraction the rest of the
 * repo exercises (see `VerificationSessionCreator.signJws`). The header carries
 * `alg` (injected automatically by the crypto lib from the key type), `typ = JWT`,
 * and the `kid` of the signing key so RPs can look it up in the JWKS endpoint
 * (Task 3). No `alg: none`, no `alg` override.
 *
 * **Clock injection.** [clock] lets tests pin `iat` / `exp` without sleeping.
 * Using `kotlin.time.Clock` (Kotlin 2.3 API split: stdlib clock for runtime ops,
 * `kotlinx.datetime` reserved for serialized model fields).
 *
 * Scope boundaries for v1:
 * - Access tokens are JWTs (no opaque tokens, no introspection endpoint).
 * - No refresh tokens, no rotation, no revocation.
 * - JWS only (no JWE — RPs get their tokens over TLS).
 * - Caller decides which extra claims to merge in (realm config's
 *   `claim_mapping` output), we don't know about realms here.
 *
 * @property key Active signing key. Must have a private component; `signJws` will
 *   fail loudly otherwise. Generated / persisted by [KeyProvider].
 * @property iss The exact issuer URL stamped into every token's `iss` claim. Must
 *   match the OP's discovery `issuer` byte-for-byte (OIDC Core §15.5.2).
 * @property lifetime How long ID + access tokens remain valid, applied from the
 *   moment of minting. One hour is the OIDC Core recommendation for ID tokens;
 *   access tokens can in theory differ but we keep them uniform for v1.
 * @property clock Injectable clock; defaults to [Clock.System].
 */
class JwtIssuer(
    private val key: JWKKey,
    private val iss: String,
    private val lifetime: Duration = 1.hours,
    private val clock: Clock = Clock.System,
) {

    /**
     * Read the current instant from the issuer's clock. Exposed as a public
     * accessor so downstream code (e.g. the `/userinfo` endpoint in
     * [id.walt.authop.endpoints.userInfoRoutes]) can make `exp` comparisons
     * against the same clock the minting side uses — without that, injecting a
     * [TestClock]-style pinned clock for deterministic mint tests would leave
     * the verify side reading `Clock.System.now()` and drifting.
     */
    fun now(): Instant = clock.now()

    /**
     * Mint an OIDC Core §2 ID token.
     *
     * Standard claims (`iss`, `sub`, `aud`, `iat`, `exp`) are emitted verbatim.
     * [nonce] is included only when non-null (OIDC Core: include when the RP sent
     * one; never emit `"nonce": null`). [authTime] is emitted as epoch seconds when
     * provided. Anything in [claims] is merged on top — extra claims cannot
     * overwrite the five standard ones because the standard pairs are added last.
     *
     * @param sub Subject identifier. The auth-op mints its own subs (Task 5's
     *   session model); this is whatever the caller mapped from upstream.
     * @param aud client_id of the RP that will consume this token.
     * @param nonce The RP-supplied nonce; pass null to omit the claim entirely.
     * @param authTime Time the user authenticated (for `max_age` / `prompt=login`
     *   compliance). Null = omit the claim.
     * @param claims Realm-configured extras (`realm`, `acr`, `amr`, `given_name`, …).
     */
    suspend fun mintIdToken(
        sub: String,
        aud: String,
        nonce: String?,
        authTime: Instant? = null,
        claims: Map<String, JsonElement> = emptyMap(),
    ): String {
        val now = clock.now()
        val payload = buildMap<String, JsonElement> {
            // Extra claims first so standard claims below win on key collision.
            putAll(claims)
            put("iss", JsonPrimitive(iss))
            put("sub", JsonPrimitive(sub))
            put("aud", JsonPrimitive(aud))
            put("iat", JsonPrimitive(now.epochSeconds))
            put("exp", JsonPrimitive(now.epochSeconds + lifetime.inWholeSeconds))
            if (nonce != null) put("nonce", JsonPrimitive(nonce))
            if (authTime != null) put("auth_time", JsonPrimitive(authTime.epochSeconds))
        }
        return sign(payload)
    }

    /**
     * Mint a JWT-shaped access token for calls to the auth-op's UserInfo endpoint
     * (Task 12) or any RP-facing resource server.
     *
     * `scope` is serialized as the space-delimited string per RFC 6749 §3.3.
     * `token_use = "access_token"` disambiguates from ID tokens at the resource
     * server — a convention popularised by AWS Cognito / Auth0 and cheap enough
     * to adopt even without a strict spec requirement.
     *
     * @param scope Scopes granted to this token. Empty list produces `""` — the
     *   resource server decides whether that's acceptable.
     */
    suspend fun mintAccessToken(
        sub: String,
        aud: String,
        scope: List<String>,
        claims: Map<String, JsonElement> = emptyMap(),
    ): String {
        val now = clock.now()
        val payload = buildMap<String, JsonElement> {
            putAll(claims)
            put("iss", JsonPrimitive(iss))
            put("sub", JsonPrimitive(sub))
            put("aud", JsonPrimitive(aud))
            put("iat", JsonPrimitive(now.epochSeconds))
            put("exp", JsonPrimitive(now.epochSeconds + lifetime.inWholeSeconds))
            put("scope", JsonPrimitive(scope.joinToString(" ")))
            put("token_use", JsonPrimitive("access_token"))
        }
        return sign(payload)
    }

    /**
     * JWS-sign the payload with standard header. The crypto lib injects `alg`
     * from the key type; we add `typ` and `kid` so JWKS lookups work.
     */
    private suspend fun sign(payload: Map<String, JsonElement>): String {
        val headers = mapOf<String, JsonElement>(
            "typ" to JsonPrimitive("JWT"),
            "kid" to JsonPrimitive(key.getKeyId()),
        )
        val bytes = Json.encodeToString(JsonObject.serializer(), JsonObject(payload)).encodeToByteArray()
        return key.signJws(bytes, headers)
    }
}
