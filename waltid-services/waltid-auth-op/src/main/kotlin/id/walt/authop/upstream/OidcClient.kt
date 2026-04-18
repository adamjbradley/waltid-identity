@file:OptIn(ExperimentalTime::class)

package id.walt.authop.upstream

import com.github.benmanes.caffeine.cache.Caffeine
import id.walt.crypto.keys.jwk.JWKKey
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.parameters
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.Base64
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.ExperimentalTime
import kotlin.time.toJavaDuration

/**
 * Generic OpenID Connect upstream client.
 *
 * Lets the auth-op's OIDC realm adapter (Task 14's `/callback/oidc`) drive an
 * arbitrary upstream IdP (Keycloak, Authentik, Okta, Auth0, …) through the
 * standard OIDC Core authorization-code flow:
 *
 *  1. [discover] — fetch `/.well-known/openid-configuration` (cached).
 *  2. The caller redirects the user to `discovery.authorizationEndpoint` with a
 *     state + nonce it chose locally.
 *  3. [exchangeCode] — trade the authorization code for an ID token + access
 *     token, verify the ID token's signature against the upstream's cached
 *     JWKS, and verify the five mandatory claims (`iss`, `aud`, `exp`, `nonce`,
 *     `kid`-selected signature).
 *
 * **Security invariants encoded here:**
 *  - The `iss` inside the discovery document MUST match the caller's expected
 *    issuer byte-for-byte (confused-deputy protection against attacker-hosted
 *    discovery endpoints).
 *  - The ID token signature MUST be verified against the upstream's JWK that
 *    matches the `kid` in the token header. We do NOT fall back to "any key in
 *    the set" — a missing `kid` or an unknown `kid` both reject.
 *  - The `nonce` claim MUST equal the caller's [expectedNonce] when the caller
 *    passes one. Callers are expected to pin the nonce at `/authorize` start
 *    and thread it through until code exchange, preventing replay.
 *  - Token endpoint authentication uses HTTP Basic (client_secret_basic), the
 *    baseline OIDC OP method, transmitted only over the upstream's token URL
 *    (which is TLS by convention).
 *
 * **Scope boundaries (v1):**
 *  - PKCE against the upstream is out-of-scope (OIDC realms here use
 *    client_secret; adding PKCE is a hardening item tracked elsewhere).
 *  - JWE / encrypted ID tokens are out of scope — we require JWS compact form.
 *  - Refresh tokens are captured but never used.
 *  - No UserInfo call — the ID token carries everything we need for claim
 *    mapping. If a realm ever needs UserInfo, add a helper here.
 *  - mTLS / private_key_jwt client auth not implemented.
 *
 * Caches use [Caffeine] with `expireAfterWrite` for simplicity. We accept
 * eventual consistency on key rotation: when verification fails because the
 * `kid` is unknown, we refresh JWKS once and retry — that is sufficient for
 * the typical "OP just rotated" case without polling.
 */
class OidcClient(
    private val httpClient: HttpClient = defaultHttpClient(),
    private val clock: Clock = Clock.System,
    discoveryCacheTtl: Duration = 1.hours,
    private val jwksCacheTtl: Duration = 5.minutes,
) {

    private val discoveryCache = Caffeine.newBuilder()
        .expireAfterWrite(discoveryCacheTtl.toJavaDuration())
        .build<String, OidcDiscovery>()

    /**
     * Upstream JWKS cache keyed by `jwksUri`. The stored value is a map of
     * `kid → JWK JsonObject` so verification lookups are O(1) without
     * re-parsing the set on every token.
     *
     * Entries are rebuilt via [fetchJwks] on a cache miss. When a token's
     * `kid` is absent from the cached map, the caller (see [verifyIdToken])
     * forces a refresh exactly once before giving up.
     */
    private val jwksCache = Caffeine.newBuilder()
        .expireAfterWrite(jwksCacheTtl.toJavaDuration())
        .build<String, Map<String, JsonObject>>()

    /**
     * Discover the upstream's configuration.
     *
     * @param issuer the expected issuer URL. Used both as the base for the
     *   well-known fetch AND as the byte-exact value that the discovery
     *   document's `issuer` field must equal.
     * @throws OidcClientException when the upstream returns a non-2xx, the
     *   document is malformed, required fields are missing, or the returned
     *   `issuer` does not match [issuer] exactly.
     */
    suspend fun discover(issuer: String): OidcDiscovery {
        discoveryCache.getIfPresent(issuer)?.let { return it }

        val url = "${issuer.trimEnd('/')}/.well-known/openid-configuration"
        val response: HttpResponse = try {
            httpClient.get(url)
        } catch (t: Throwable) {
            throw OidcClientException("upstream_discovery_failed", "discovery GET failed: ${t.message}", t)
        }
        if (!response.status.isSuccess()) {
            throw OidcClientException(
                code = "upstream_discovery_failed",
                description = "discovery returned HTTP ${response.status.value}"
            )
        }
        val body = response.bodyAsText()
        val doc = try {
            Json.parseToJsonElement(body).jsonObject
        } catch (t: Throwable) {
            throw OidcClientException("upstream_discovery_failed", "discovery body is not JSON", t)
        }

        val parsed = OidcDiscovery.fromJson(doc)
            ?: throw OidcClientException(
                "upstream_discovery_failed",
                "discovery document is missing required fields"
            )

        // Byte-exact issuer check — prevents redirecting to an attacker-hosted
        // well-known that advertises someone else's issuer and returns their
        // claims. This is the classic "confused deputy" guard.
        if (parsed.issuer != issuer) {
            throw OidcClientException(
                "upstream_issuer_mismatch",
                "discovery 'issuer'='${parsed.issuer}' does not match expected '$issuer'"
            )
        }

        discoveryCache.put(issuer, parsed)
        return parsed
    }

    /**
     * Fetch and cache the upstream's JWKS. Returned as a `kid → JWK` map for
     * O(1) lookup in [verifyIdToken].
     *
     * Keys without a `kid` are skipped — we do not support unkeyed JWKs because
     * the verification path always requires a `kid` to bind a signature to a
     * specific upstream key. An upstream that serves unkeyed JWKs is spec-
     * non-compliant for OIDC Core §10.2.1.
     */
    private suspend fun fetchJwks(jwksUri: String): Map<String, JsonObject> {
        val response: HttpResponse = try {
            httpClient.get(jwksUri)
        } catch (t: Throwable) {
            throw OidcClientException("upstream_jwks_failed", "JWKS GET failed: ${t.message}", t)
        }
        if (!response.status.isSuccess()) {
            throw OidcClientException(
                "upstream_jwks_failed",
                "JWKS returned HTTP ${response.status.value}"
            )
        }
        val body = response.bodyAsText()
        val doc = try {
            Json.parseToJsonElement(body).jsonObject
        } catch (t: Throwable) {
            throw OidcClientException("upstream_jwks_failed", "JWKS body is not JSON", t)
        }
        val keys = (doc["keys"] as? JsonArray)
            ?: throw OidcClientException("upstream_jwks_failed", "JWKS 'keys' array missing")

        val byKid = buildMap<String, JsonObject> {
            for (entry in keys) {
                val obj = entry as? JsonObject ?: continue
                val kid = obj["kid"]?.jsonPrimitive?.contentOrNullSafe() ?: continue
                put(kid, obj)
            }
        }
        jwksCache.put(jwksUri, byKid)
        return byKid
    }

    private suspend fun getJwks(jwksUri: String, forceRefresh: Boolean = false): Map<String, JsonObject> {
        if (!forceRefresh) {
            jwksCache.getIfPresent(jwksUri)?.let { return it }
        }
        return fetchJwks(jwksUri)
    }

    /**
     * Exchange an authorization code for an ID token (and optionally access +
     * refresh tokens) at the upstream token endpoint.
     *
     * Authentication uses `client_secret_basic` (RFC 6749 §2.3.1) — the
     * widest-supported method across Keycloak/Authentik/Okta/Auth0/Google.
     *
     * The returned ID token has already passed signature + claims verification
     * against [discovery]'s JWKS and the caller's [clientId] / [expectedNonce].
     */
    suspend fun exchangeCode(
        discovery: OidcDiscovery,
        clientId: String,
        clientSecret: String,
        code: String,
        redirectUri: String,
        expectedNonce: String?,
    ): OidcTokenResult {
        val basic = Base64.getEncoder().encodeToString("$clientId:$clientSecret".encodeToByteArray())

        val response: HttpResponse = try {
            httpClient.submitForm(
                url = discovery.tokenEndpoint,
                formParameters = parameters {
                    append("grant_type", "authorization_code")
                    append("code", code)
                    append("redirect_uri", redirectUri)
                    // client_id is technically redundant under client_secret_basic, but
                    // some OPs (notably Keycloak) still validate it when present. Sending
                    // it costs nothing and keeps the request unambiguous.
                    append("client_id", clientId)
                },
            ) {
                header(HttpHeaders.Authorization, "Basic $basic")
                header(HttpHeaders.Accept, "application/json")
            }
        } catch (t: Throwable) {
            throw OidcClientException("upstream_token_failed", "token POST failed: ${t.message}", t)
        }

        val rawBody = response.bodyAsText()
        if (!response.status.isSuccess()) {
            // RFC 6749 §5.2 error shape: `{"error":"invalid_grant", ...}`. Propagate
            // the upstream's machine-readable error code when present so Task 14 can
            // map it into our own `access_denied` with a useful reason, rather than
            // collapsing everything into an opaque 500.
            val upstreamCode = parseErrorCode(rawBody) ?: response.status.value.toString()
            throw OidcClientException(
                code = "upstream_token_failed",
                description = "token endpoint returned HTTP ${response.status.value} '$upstreamCode'"
            )
        }

        val body = try {
            Json.parseToJsonElement(rawBody).jsonObject
        } catch (t: Throwable) {
            throw OidcClientException("upstream_token_failed", "token body is not JSON", t)
        }

        val idToken = body["id_token"]?.jsonPrimitive?.contentOrNullSafe()
            ?: throw OidcClientException("upstream_token_failed", "token response missing id_token")

        val claims = verifyIdToken(
            idToken = idToken,
            discovery = discovery,
            expectedAud = clientId,
            expectedNonce = expectedNonce,
        )

        return OidcTokenResult(
            idToken = idToken,
            idTokenClaims = claims,
            accessToken = body["access_token"]?.jsonPrimitive?.contentOrNullSafe(),
            refreshToken = body["refresh_token"]?.jsonPrimitive?.contentOrNullSafe(),
        )
    }

    /**
     * Verify an upstream ID token's signature and claims.
     *
     * Returns the decoded payload (parsed JSON) on success; throws
     * [OidcClientException] with a distinct `code` on every failure mode so
     * callers can log + surface the specific reason.
     *
     * The verification order is deliberate:
     *  1. Parse the compact JWS. Malformed → `upstream_id_token_malformed`.
     *  2. Read `kid` from the JWS header. Missing → `upstream_id_token_no_kid`.
     *  3. Look up the JWK in the cached JWKS. If missing, refresh once and
     *     retry; still missing → `upstream_id_token_unknown_kid`. This single-
     *     shot refresh handles upstream key rotation without polling.
     *  4. Verify the signature using that JWK. Failure →
     *     `upstream_id_token_bad_signature`.
     *  5. Validate claims in spec order: `iss`, `aud`, `exp`, `nonce`. Each
     *     emits its own distinct code.
     */
    suspend fun verifyIdToken(
        idToken: String,
        discovery: OidcDiscovery,
        expectedAud: String,
        expectedNonce: String?,
    ): JsonObject {
        val segments = idToken.split(".")
        if (segments.size != 3) {
            throw OidcClientException(
                "upstream_id_token_malformed",
                "ID token is not a compact JWS (expected 3 segments, got ${segments.size})"
            )
        }
        val header = try {
            val bytes = Base64.getUrlDecoder().decode(padBase64Url(segments[0]))
            Json.parseToJsonElement(bytes.decodeToString()).jsonObject
        } catch (t: Throwable) {
            throw OidcClientException("upstream_id_token_malformed", "ID token header invalid", t)
        }

        val kid = header["kid"]?.jsonPrimitive?.contentOrNullSafe()
            ?: throw OidcClientException(
                "upstream_id_token_no_kid",
                "ID token header missing 'kid' (required for upstream JWK selection)"
            )

        var jwks = getJwks(discovery.jwksUri)
        var jwk = jwks[kid]
        if (jwk == null) {
            // Upstream may have just rotated. Refresh once, then give up.
            jwks = getJwks(discovery.jwksUri, forceRefresh = true)
            jwk = jwks[kid] ?: throw OidcClientException(
                "upstream_id_token_unknown_kid",
                "ID token 'kid'='$kid' not found in upstream JWKS after refresh"
            )
        }

        val jwkString = jwk.toString()
        val upstreamKey = JWKKey.importJWK(jwkString).getOrElse { t ->
            throw OidcClientException(
                "upstream_id_token_bad_signature",
                "could not import upstream JWK for kid='$kid'",
                t
            )
        }

        // verifyJws does both signature check AND payload decode in one pass.
        // A failed signature surfaces as Result.failure here.
        val payload = upstreamKey.verifyJws(idToken).getOrElse { t ->
            throw OidcClientException(
                "upstream_id_token_bad_signature",
                "ID token signature verification failed for kid='$kid'",
                t
            )
        } as? JsonObject ?: throw OidcClientException(
            "upstream_id_token_malformed",
            "ID token payload is not a JSON object"
        )

        // --- Claim checks, spec-ordered -------------------------------------
        val iss = payload["iss"]?.jsonPrimitive?.contentOrNullSafe()
        if (iss != discovery.issuer) {
            throw OidcClientException(
                "upstream_id_token_bad_iss",
                "ID token 'iss'='$iss' does not match expected '${discovery.issuer}'"
            )
        }
        val audClaim = payload["aud"]
        val audMatches = when (audClaim) {
            is JsonPrimitive -> audClaim.contentOrNullSafe() == expectedAud
            is JsonArray -> audClaim.jsonArray.any { it.jsonPrimitive.contentOrNullSafe() == expectedAud }
            else -> false
        }
        if (!audMatches) {
            throw OidcClientException(
                "upstream_id_token_bad_aud",
                "ID token 'aud' does not include expected '$expectedAud'"
            )
        }
        val exp = payload["exp"]?.jsonPrimitive?.contentOrNullSafe()?.toLongOrNull()
            ?: throw OidcClientException(
                "upstream_id_token_no_exp",
                "ID token missing or non-numeric 'exp'"
            )
        val nowSeconds = clock.now().epochSeconds
        if (exp <= nowSeconds) {
            throw OidcClientException(
                "upstream_id_token_expired",
                "ID token 'exp'=$exp is in the past (now=$nowSeconds)"
            )
        }
        if (expectedNonce != null) {
            val nonce = payload["nonce"]?.jsonPrimitive?.contentOrNullSafe()
            if (nonce != expectedNonce) {
                throw OidcClientException(
                    "upstream_id_token_bad_nonce",
                    "ID token 'nonce' mismatch"
                )
            }
        }
        return payload
    }

    private fun parseErrorCode(body: String): String? = try {
        (Json.parseToJsonElement(body) as? JsonObject)
            ?.get("error")?.jsonPrimitive?.contentOrNullSafe()
    } catch (_: Throwable) {
        null
    }

    companion object {
        /**
         * Default production [HttpClient]: OkHttp engine (the rest of the repo's
         * services use OkHttp), JSON content negotiation installed so the client
         * can decode `application/json` bodies without boilerplate.
         *
         * Tests swap this for a `MockEngine`-backed client via the ctor.
         */
        fun defaultHttpClient(): HttpClient = HttpClient(OkHttp) {
            install(ContentNegotiation) { json() }
        }
    }
}

/** Pad a base64url-encoded string out to a multiple of 4 for [Base64.getUrlDecoder]. */
private fun padBase64Url(s: String): String = when (s.length % 4) {
    0 -> s
    2 -> "$s=="
    3 -> "$s="
    else -> s  // invalid; decoder will throw below
}

/**
 * Null-safe accessor for [JsonPrimitive.content] that returns null when the
 * primitive is a JSON null (`isString == false && content == "null"`). The
 * stdlib `jsonPrimitive.content` returns the literal string `"null"` in that
 * case, which would sneak through byte-equality checks unnoticed.
 */
private fun JsonPrimitive.contentOrNullSafe(): String? =
    if (!isString && content == "null") null else content

private fun HttpStatusCode.isSuccess(): Boolean = value in 200..299

/**
 * Parsed, cached view of an upstream's `/.well-known/openid-configuration`
 * response. We capture only the fields the auth-op actually uses — if Task 14
 * or a later feature needs more (e.g. `token_endpoint_auth_methods_supported`
 * for negotiation), add them here.
 */
data class OidcDiscovery(
    val issuer: String,
    val authorizationEndpoint: String,
    val tokenEndpoint: String,
    val jwksUri: String,
    val userinfoEndpoint: String? = null,
    val endSessionEndpoint: String? = null,
) {
    companion object {
        /**
         * Parse from a JSON discovery document. Returns null if any of the four
         * mandatory fields (`issuer`, `authorization_endpoint`, `token_endpoint`,
         * `jwks_uri`) is missing or not a string. Missing optional fields are
         * fine; they stay null.
         */
        fun fromJson(doc: JsonObject): OidcDiscovery? {
            val issuer = doc["issuer"]?.asStringOrNull() ?: return null
            val authEndpoint = doc["authorization_endpoint"]?.asStringOrNull() ?: return null
            val tokenEndpoint = doc["token_endpoint"]?.asStringOrNull() ?: return null
            val jwksUri = doc["jwks_uri"]?.asStringOrNull() ?: return null
            return OidcDiscovery(
                issuer = issuer,
                authorizationEndpoint = authEndpoint,
                tokenEndpoint = tokenEndpoint,
                jwksUri = jwksUri,
                userinfoEndpoint = doc["userinfo_endpoint"]?.asStringOrNull(),
                endSessionEndpoint = doc["end_session_endpoint"]?.asStringOrNull(),
            )
        }

        private fun JsonElement.asStringOrNull(): String? =
            (this as? JsonPrimitive)?.takeIf { it.isString }?.content
    }
}

/** Outcome of a successful upstream authorization-code exchange. */
data class OidcTokenResult(
    val idToken: String,
    val idTokenClaims: JsonObject,
    val accessToken: String?,
    val refreshToken: String?,
)

/**
 * Exceptions raised by [OidcClient]. [code] is machine-readable and short
 * (`snake_case`); Task 14's `/callback/oidc` handler maps it into our own
 * [id.walt.authop.errors.OidcError.AccessDenied] with a `"upstream: <code>"`
 * reason.
 */
class OidcClientException(
    val code: String,
    description: String,
    cause: Throwable? = null,
) : RuntimeException(description, cause)
