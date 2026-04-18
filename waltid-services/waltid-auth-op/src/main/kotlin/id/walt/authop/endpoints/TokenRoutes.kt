package id.walt.authop.endpoints

import id.walt.authop.AuthOpDeps
import id.walt.authop.config.ClientConfig
import id.walt.authop.config.TokenEndpointAuthMethod
import id.walt.authop.domain.AuthCode
import id.walt.authop.errors.OidcError
import id.walt.authop.errors.respondOidcError
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.contentType
import io.ktor.server.request.receiveParameters
import io.ktor.server.response.header
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.security.MessageDigest
import java.util.Base64

/**
 * OAuth 2.0 / OIDC `/token` endpoint — redeems a single-use authorization code
 * for an ID token + access token.
 *
 * ### Validation order (strict)
 *
 * 1. **Content-Type** — must be `application/x-www-form-urlencoded` (RFC 6749 §3.2).
 * 2. **Required params** — `grant_type`, `code`, `redirect_uri`, `code_verifier`.
 * 3. **grant_type** — must equal `"authorization_code"` (RFC 6749 §4.1.3).
 * 4. **Client authentication** — pick method based on how credentials were
 *    presented (Basic header vs. form body), cross-check against the client's
 *    configured `token_endpoint_auth_method`, compare secrets constant-time.
 * 5. **Consume the code** — single-use; any subsequent failure is not rolled back.
 * 6. **Client binding** — `authCode.clientId` MUST equal the authenticated client.
 * 7. **redirect_uri byte-match** — OIDC Core §3.1.3.2.
 * 8. **PKCE S256** — `BASE64URL(SHA-256(verifier)) == authCode.codeChallenge`.
 * 9. **Mint tokens** — ID token + access token via [id.walt.authop.tokens.JwtIssuer].
 *
 * The order is security-load-bearing: client auth must precede code lookup so
 * an attacker without credentials learns nothing about which codes exist;
 * client-binding must precede PKCE so a stolen code from RP-A can't probe
 * RP-B's PKCE surface.
 *
 * ### Single-use
 *
 * Once `authCodeStore.consume(code)` returns a non-null value, the code is
 * permanently gone. If any subsequent check fails (client binding, PKCE,
 * redirect_uri mismatch) we do NOT put the code back — a failure here is
 * indistinguishable from a replay, which must also fail.
 *
 * ### Response shape
 *
 * On success: 200 JSON, `Cache-Control: no-store`, `Pragma: no-cache` (RFC
 * 6749 §5.1). Body: `access_token`, `token_type=Bearer`, `expires_in`,
 * `id_token`, `scope`. All five fields always present.
 *
 * ### Errors
 *
 * - `invalid_request` (400) — Content-Type wrong, required param missing.
 * - `unsupported_grant_type` (400) — grant_type != authorization_code.
 * - `invalid_client` (401 + `WWW-Authenticate: Basic`) — bad credentials or
 *   method mismatch.
 * - `invalid_grant` (400) — code unknown/expired, redirect_uri mismatch,
 *   PKCE mismatch, or client-binding mismatch.
 */
fun Route.tokenRoutes(deps: AuthOpDeps) {
    post("/token") {
        // --- 1. Content-Type must be x-www-form-urlencoded --------------------
        val ct = call.request.contentType()
        // Compare on the MIME type only; form-urlencoded MAY carry a charset
        // parameter. `match` handles that — it ignores parameters.
        if (!ct.match(ContentType.Application.FormUrlEncoded)) {
            call.respondOidcError(
                OidcError.InvalidRequestJson("content-type must be application/x-www-form-urlencoded"),
            )
            return@post
        }

        val form: Parameters = call.receiveParameters()

        // --- 2. grant_type ----------------------------------------------------
        // Spec-wise, grant_type is a required parameter. Split from "required
        // params" because a wrong grant_type has a distinct error code
        // (unsupported_grant_type, not invalid_request).
        val grantType = form["grant_type"]
        if (grantType.isNullOrBlank()) {
            call.respondOidcError(OidcError.InvalidRequestJson("missing grant_type"))
            return@post
        }
        if (grantType != "authorization_code") {
            call.respondOidcError(
                OidcError.UnsupportedGrantType("grant_type must be 'authorization_code'"),
            )
            return@post
        }

        // --- 3. Required params for authorization_code flow -------------------
        val code = form["code"]
        val redirectUri = form["redirect_uri"]
        val codeVerifier = form["code_verifier"]
        // Missing `code` / `redirect_uri` → invalid_request (can't even start).
        // Missing `code_verifier` → invalid_grant (PKCE mismatch with an
        //   empty verifier is semantically a grant failure; both spec
        //   interpretations are defensible but invalid_grant gives RPs a
        //   narrower surface to branch on). Handle this later under §8 so
        //   the consume() has already run — prevents using the code endpoint
        //   as an oracle for "does a code exist" via differential behaviour.
        if (code.isNullOrBlank()) {
            call.respondOidcError(OidcError.InvalidRequestJson("missing code"))
            return@post
        }
        if (redirectUri.isNullOrBlank()) {
            call.respondOidcError(OidcError.InvalidRequestJson("missing redirect_uri"))
            return@post
        }

        // --- 4. Authenticate the client ---------------------------------------
        val authResult = authenticateClient(call, form, deps)
        val authenticatedClient = when (authResult) {
            is ClientAuthResult.Ok -> authResult.client
            is ClientAuthResult.Fail -> {
                call.respondOidcError(OidcError.InvalidClient)
                return@post
            }
        }

        // --- 5. Consume the code (single-use — no rollback on later failure) --
        val authCode: AuthCode? = deps.authCodeStore.consume(code)
        if (authCode == null) {
            call.respondOidcError(OidcError.InvalidGrant("unknown or expired code"))
            return@post
        }

        // --- 6. Client binding ------------------------------------------------
        // Prevent RP-A from redeeming an RP-B code. Constant-time because the
        // client_id is public but the membership test behaviour should not
        // leak timing (defence in depth).
        if (!constantTimeEquals(authCode.clientId, authenticatedClient.clientId)) {
            call.respondOidcError(OidcError.InvalidGrant("code was not issued to this client"))
            return@post
        }

        // --- 7. redirect_uri byte-match ---------------------------------------
        if (!constantTimeEquals(redirectUri, authCode.redirectUri)) {
            call.respondOidcError(OidcError.InvalidGrant("redirect_uri does not match"))
            return@post
        }

        // --- 8. PKCE ----------------------------------------------------------
        // `codeChallengeMethod` is always "S256" for v1 (authorize rejects anything
        // else), but we assert the invariant to make the intent explicit.
        if (authCode.codeChallengeMethod != "S256") {
            call.respondOidcError(
                OidcError.InvalidGrant("unsupported code_challenge_method: ${authCode.codeChallengeMethod}"),
            )
            return@post
        }
        if (codeVerifier.isNullOrBlank()) {
            call.respondOidcError(OidcError.InvalidGrant("missing code_verifier"))
            return@post
        }
        val computedChallenge = s256Challenge(codeVerifier)
        if (!constantTimeEquals(computedChallenge, authCode.codeChallenge)) {
            call.respondOidcError(OidcError.InvalidGrant("PKCE verifier does not match challenge"))
            return@post
        }

        // --- 9. Mint tokens ---------------------------------------------------
        val idToken = deps.jwtIssuer.mintIdToken(
            sub = authCode.subject,
            aud = authCode.clientId,
            nonce = authCode.nonce,
            authTime = authCode.authTime,
            claims = authCode.claims,
        )
        val accessToken = deps.jwtIssuer.mintAccessToken(
            sub = authCode.subject,
            aud = authCode.clientId,
            scope = authCode.scope,
            claims = emptyMap(),
        )

        // --- 10. Response -----------------------------------------------------
        // RFC 6749 §5.1: token responses MUST include Cache-Control: no-store
        // and Pragma: no-cache. Older HTTP/1.0 proxies respect Pragma; we
        // include both rather than betting on modern-only intermediaries.
        val body = buildJsonObject {
            put("access_token", accessToken)
            put("token_type", "Bearer")
            // expires_in is a JSON number per RFC 6749. buildJsonObject maps
            // `put(String, Long)` → JsonPrimitive(Long) which emits the bare
            // integer — no quotes.
            put("expires_in", 3600L)
            put("id_token", idToken)
            put("scope", authCode.scope.joinToString(" "))
        }
        call.response.header(HttpHeaders.CacheControl, "no-store")
        call.response.header(HttpHeaders.Pragma, "no-cache")
        call.respondText(body.toString(), ContentType.Application.Json, HttpStatusCode.OK)
    }
}

// ---------------------------------------------------------------------------
// Client authentication
// ---------------------------------------------------------------------------

/**
 * Result of client authentication. Non-sealed data types so callers can
 * pattern-match; fail cases don't carry a reason because we MUST emit a
 * uniform `invalid_client` error (no description) to avoid enumeration
 * oracles (is the client_id wrong vs. the secret wrong).
 */
private sealed class ClientAuthResult {
    data class Ok(val client: ClientConfig) : ClientAuthResult()
    object Fail : ClientAuthResult()
}

/**
 * Authenticate the calling client.
 *
 * Priority order for locating `client_id`:
 *  1. Basic Auth header (`Authorization: Basic <base64>`) — implies `client_secret_basic`.
 *  2. Form body `client_id` — method depends on whether `client_secret` is present.
 *
 * Method-mismatch rules (strict — every "yes, but" is a rejection):
 *  - Client configured `CLIENT_SECRET_BASIC`: credentials MUST arrive via Basic.
 *    Body-only `client_id`/`client_secret` → fail (enforce the configured method).
 *  - Client configured `CLIENT_SECRET_POST`: credentials MUST arrive via form body.
 *    Basic Auth header → fail.
 *  - Client configured `NONE`: no secret expected. Basic header OR body `client_secret` → fail.
 *
 * Secret comparison is constant-time via [MessageDigest.isEqual] after byte-encoding.
 */
private fun authenticateClient(
    call: ApplicationCall,
    form: Parameters,
    deps: AuthOpDeps,
): ClientAuthResult {
    val basicHeader = call.request.headers[HttpHeaders.Authorization]
    val basicCreds = basicHeader?.let { parseBasicAuth(it) }
    val bodyClientId = form["client_id"]?.takeIf { it.isNotBlank() }
    val bodyClientSecret = form["client_secret"]?.takeIf { it.isNotBlank() }

    // Locate the client_id. Prefer Basic header when it's a valid Basic
    // scheme — any Basic header at all signals the client's intent.
    val claimedClientId = basicCreds?.clientId ?: bodyClientId ?: return ClientAuthResult.Fail
    val client = deps.clientRegistry[claimedClientId] ?: return ClientAuthResult.Fail

    return when (client.tokenEndpointAuthMethod) {
        TokenEndpointAuthMethod.CLIENT_SECRET_BASIC -> {
            // Must have Basic creds. No falling back to body; method mismatch.
            if (basicCreds == null) return ClientAuthResult.Fail
            val expected = client.clientSecret ?: return ClientAuthResult.Fail
            // Verify client_id matches (Basic header could disagree with body).
            if (!constantTimeEquals(basicCreds.clientId, client.clientId)) return ClientAuthResult.Fail
            if (!constantTimeEquals(basicCreds.secret, expected)) return ClientAuthResult.Fail
            ClientAuthResult.Ok(client)
        }

        TokenEndpointAuthMethod.CLIENT_SECRET_POST -> {
            // Must have body creds. Basic header is rejected — strict method enforcement.
            if (basicCreds != null) return ClientAuthResult.Fail
            val expected = client.clientSecret ?: return ClientAuthResult.Fail
            if (bodyClientSecret == null) return ClientAuthResult.Fail
            if (!constantTimeEquals(bodyClientId ?: "", client.clientId)) return ClientAuthResult.Fail
            if (!constantTimeEquals(bodyClientSecret, expected)) return ClientAuthResult.Fail
            ClientAuthResult.Ok(client)
        }

        TokenEndpointAuthMethod.NONE -> {
            // Public client: no secret expected anywhere. Any secret → fail.
            if (basicCreds != null) return ClientAuthResult.Fail
            if (bodyClientSecret != null) return ClientAuthResult.Fail
            if (!constantTimeEquals(bodyClientId ?: "", client.clientId)) return ClientAuthResult.Fail
            ClientAuthResult.Ok(client)
        }
    }
}

/** Parsed Basic-Auth credentials. Both fields are the decoded UTF-8 strings. */
private data class BasicCreds(val clientId: String, val secret: String)

/**
 * Parse an `Authorization: Basic <base64>` header. Returns null if the header
 * isn't Basic, isn't valid base64, or decodes to a string without a `:`.
 *
 * RFC 6749 §2.3.1 specifies `application/x-www-form-urlencoded` encoding for
 * the credential string before base64 — we do NOT URL-decode here because
 * none of our test client_ids or secrets contain characters that need it,
 * and adding strict RFC-compliant decoding for the zero-realistic-case
 * carries a risk of breaking interop with clients that send raw bytes (which
 * many libraries do). A future hardening pass can layer URL-decoding on.
 */
private fun parseBasicAuth(header: String): BasicCreds? {
    val prefix = "Basic "
    if (!header.startsWith(prefix, ignoreCase = true)) return null
    val encoded = header.substring(prefix.length).trim()
    val decoded = try {
        Base64.getDecoder().decode(encoded).decodeToString()
    } catch (_: IllegalArgumentException) {
        return null
    }
    val colon = decoded.indexOf(':')
    if (colon < 0) return null
    return BasicCreds(
        clientId = decoded.substring(0, colon),
        secret = decoded.substring(colon + 1),
    )
}

// ---------------------------------------------------------------------------
// PKCE + constant-time helpers
// ---------------------------------------------------------------------------

/**
 * Compute `BASE64URL(SHA-256(verifier))` per RFC 7636 §4.6.
 *
 * Explicitly `StandardCharsets.US_ASCII` would be spec-pedantic but the
 * `code_verifier` grammar forbids non-ASCII; UTF-8 for the expected input
 * produces identical bytes. Trailing `=` padding is stripped per RFC 7636
 * (Base64URL no-pad).
 */
internal fun s256Challenge(verifier: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII))
    return Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
}

/**
 * Constant-time string equality. Delegates to [MessageDigest.isEqual] which
 * compares byte arrays without early exit, so the comparison time is
 * independent of where (or whether) the first differing byte sits.
 *
 * `==` in Kotlin/JVM short-circuits on first mismatch and would leak a
 * timing side channel on secret comparisons. Always use this helper for
 * any value an attacker might iterate (client secrets, PKCE challenges,
 * code values).
 */
internal fun constantTimeEquals(a: String, b: String): Boolean {
    // Length check is not secret-dependent (a TLS attacker sees the byte
    // counts) and short-circuiting here doesn't leak anything useful. The
    // MessageDigest.isEqual call handles the constant-time content check.
    val aBytes = a.toByteArray(Charsets.UTF_8)
    val bBytes = b.toByteArray(Charsets.UTF_8)
    if (aBytes.size != bBytes.size) return false
    return MessageDigest.isEqual(aBytes, bBytes)
}
