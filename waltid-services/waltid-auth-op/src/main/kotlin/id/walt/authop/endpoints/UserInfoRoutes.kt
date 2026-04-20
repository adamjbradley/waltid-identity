@file:OptIn(ExperimentalTime::class)

package id.walt.authop.endpoints

import id.walt.authop.AuthOpDeps
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.header
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.time.ExperimentalTime

/**
 * OIDC Core §5.3 `/userinfo` endpoint — returns scope-filtered claims about the
 * end user who is the subject of the presented access token.
 *
 * ### Bearer-only
 *
 * OIDC Core §5.3.1 permits the access token to be presented in any of three
 * locations:
 *   - `Authorization: Bearer <token>` header
 *   - `access_token` form param (POST only, `application/x-www-form-urlencoded`)
 *   - `access_token` query param
 *
 * We support only the header. The query-param form is a well-known leakage
 * vector (landing in access logs, HTTP referer headers, bookmarks, bash
 * history); the form-body variant is minor enough in practice that we skip it
 * for MVP too — RPs can always set a header. If a future deployment needs
 * body-form acceptance we can layer that in; the reverse is harder to roll back.
 *
 * ### Token validation
 *
 * The bearer is validated as a JWS against the OP's own signing key (the same
 * one used to mint the token at `/token`). Validation order:
 *   1. Parse as compact JWS + verify signature → `invalid_token` on failure.
 *   2. Check `exp` against the injected clock → `invalid_token` if in the past.
 *   3. Require `token_use == "access_token"` → `invalid_token` otherwise (this
 *      keeps ID tokens, which carry the same key-signed claims, from being
 *      reused as access tokens at the resource server).
 *
 * `token_use` is the same convention we stamp at mint time in
 * [id.walt.authop.tokens.JwtIssuer.mintAccessToken]; without it there is no
 * wire-level way to distinguish an ID token from an access token when both are
 * JWTs signed by the same key. AWS Cognito / Auth0 popularised this claim and
 * we adopt it unmodified.
 *
 * ### Scope filtering (OIDC Core §5.4)
 *
 * Claims are returned only when the token's scope grants them. `sub` is always
 * included (it's the primary purpose of the response). `openid` alone maps to
 * `{"sub": "..."}`. `profile` adds the standard profile claims. `email` adds
 * `email` + `email_verified`. Non-standard claims (`realm`, `acr`, `amr`, …) are
 * NOT returned unless they're explicitly surfaced via the OIDC `claims` request
 * parameter (not in MVP). This follows the spec default and keeps `/userinfo`
 * conservative — RPs who need those claims read them off the ID token.
 *
 * ### Response shape
 *
 * 200 JSON, `Cache-Control: no-store`. Errors are 401 JSON with
 * `WWW-Authenticate: Bearer ...` per RFC 6750 §3. Both success and failure
 * bodies set `Cache-Control: no-store` so intermediaries never cache a
 * response tied to a single subject/session.
 */
fun Route.userInfoRoutes(deps: AuthOpDeps) {
    get("/userinfo") { call.handleUserInfo(deps) }
    post("/userinfo") { call.handleUserInfo(deps) }
}

/**
 * Shared GET/POST handler — OIDC Core §5.3.1 lets an RP call /userinfo with
 * either verb, and the result must be identical. Splitting the handler keeps
 * the [Route] block a two-line wiring declaration.
 */
private suspend fun ApplicationCall.handleUserInfo(deps: AuthOpDeps) {
    // Set Cache-Control first so the error branches inherit it without having
    // to remember. `respondText` overwrites Content-Type on response but leaves
    // other headers untouched.
    response.header(HttpHeaders.CacheControl, "no-store")

    // --- 1. Extract bearer token ------------------------------------------
    val authHeader = request.headers[HttpHeaders.Authorization]
    if (authHeader.isNullOrBlank()) {
        // No header at all — per RFC 6750 §3 the challenge is empty (just
        // `Bearer`) to tell the client that a token is required without
        // claiming a specific error.
        response.header(HttpHeaders.WWWAuthenticate, "Bearer")
        respondJsonError("invalid_token", null, HttpStatusCode.Unauthorized)
        return
    }

    val bearerPrefix = "Bearer "
    if (!authHeader.startsWith(bearerPrefix, ignoreCase = true)) {
        // Header present but not a Bearer scheme. Per RFC 6750 §3.1, a
        // malformed request-authentication MUST return `invalid_request`
        // (distinct from `invalid_token`, which reserves itself for
        // signature/expiry/type failures on a syntactically-valid bearer).
        response.header(
            HttpHeaders.WWWAuthenticate,
            """Bearer error="invalid_request", error_description="Authorization header must use Bearer scheme"""",
        )
        respondJsonError(
            "invalid_request",
            "Authorization header must use Bearer scheme",
            HttpStatusCode.Unauthorized,
        )
        return
    }
    val token = authHeader.substring(bearerPrefix.length).trim()
    if (token.isEmpty()) {
        response.header(
            HttpHeaders.WWWAuthenticate,
            """Bearer error="invalid_request", error_description="empty bearer token"""",
        )
        respondJsonError("invalid_request", "empty bearer token", HttpStatusCode.Unauthorized)
        return
    }

    // --- 2. Verify signature ----------------------------------------------
    // Use the public form of the signing key so we exercise the same code path
    // as an external verifier would (and so a future split between signing and
    // verification keys is a single-line change).
    val publicKey = deps.signingKey.getPublicKey()
    val verified = publicKey.verifyJws(token)
    if (verified.isFailure) {
        respondInvalidToken("signature verification failed")
        return
    }
    val claims = verified.getOrThrow() as? JsonObject
    if (claims == null) {
        // verifyJws returned a JsonElement that isn't an object — the payload
        // didn't parse as a JSON object (the only legal JWT payload shape).
        respondInvalidToken("malformed token payload")
        return
    }

    // --- 3. Check expiry --------------------------------------------------
    val expSeconds = claims["exp"]?.jsonPrimitive?.content?.toLongOrNull()
    if (expSeconds == null) {
        // No `exp` → reject. Tokens without expiry are a compliance smell and
        // would let a leaked token live forever at this endpoint.
        respondInvalidToken("token missing exp claim")
        return
    }
    val now = deps.jwtIssuer.now()
    if (now.epochSeconds >= expSeconds) {
        respondInvalidToken("token expired")
        return
    }

    // --- 4. Confirm this is an access token (not an ID token) -------------
    val tokenUse = claims["token_use"]?.jsonPrimitive?.content
    if (tokenUse != "access_token") {
        // We stamp `token_use=access_token` at mint time on access tokens only
        // (see JwtIssuer.mintAccessToken). An ID token presented here will be
        // missing the claim — reject.
        respondInvalidToken("not an access token")
        return
    }

    // --- 5. Build scope-filtered response ---------------------------------
    val scopes = claims["scope"]?.jsonPrimitive?.content
        ?.split(' ')
        ?.filter { it.isNotBlank() }
        ?: emptyList()
    val filtered = filterClaimsForScope(claims, scopes)

    val body = JsonObject(filtered).toString()
    respondText(body, ContentType.Application.Json, HttpStatusCode.OK)
}

/**
 * Profile-scope claim names per OIDC Core §5.4. Any of these that are present
 * in the access token payload are surfaced when the token carries the
 * `profile` scope.
 */
private val profileClaims = setOf(
    "name",
    "given_name",
    "family_name",
    "middle_name",
    "nickname",
    "preferred_username",
    "profile",
    "picture",
    "website",
    "gender",
    "birthdate",
    "zoneinfo",
    "locale",
    "updated_at",
)

/** Email-scope claim names per OIDC Core §5.4. */
private val emailClaims = setOf("email", "email_verified")

/**
 * Filter a token's claim set by granted OIDC scopes.
 *
 * - `sub` is always surfaced if present (the primary purpose of /userinfo).
 * - `profile` scope → [profileClaims] that exist in the token payload.
 * - `email` scope → [emailClaims] that exist in the token payload.
 * - Anything else (`realm`, `acr`, `amr`, custom claims) is dropped for MVP.
 *   OIDC Core §5.5 lets RPs request those via the `claims` parameter on
 *   /authorize; until we implement that pathway we follow the spec default.
 *
 * Pulled out of the handler so it can be unit-tested in isolation without
 * spinning up a test application.
 */
internal fun filterClaimsForScope(
    allClaims: Map<String, JsonElement>,
    scopes: List<String>,
): Map<String, JsonElement> {
    val result = linkedMapOf<String, JsonElement>()
    allClaims["sub"]?.let { result["sub"] = it }
    if ("profile" in scopes) profileClaims.forEach { name ->
        allClaims[name]?.let { result[name] = it }
    }
    if ("email" in scopes) emailClaims.forEach { name ->
        allClaims[name]?.let { result[name] = it }
    }
    return result
}

/**
 * Emit a 401 `invalid_token` response with a `WWW-Authenticate` challenge
 * carrying [description]. Keeps every invalid-token branch in lockstep so an
 * RP can always parse the header the same way.
 */
private suspend fun ApplicationCall.respondInvalidToken(description: String) {
    response.header(
        HttpHeaders.WWWAuthenticate,
        """Bearer error="invalid_token", error_description="$description"""",
    )
    respondJsonError("invalid_token", description, HttpStatusCode.Unauthorized)
}

/**
 * Write the standard OAuth2 §5.2 error JSON shape. Status code is set by the
 * caller — some paths are 401 (unauthenticated) and some future paths might be
 * 400 (malformed, but we're currently treating all such cases as 401 on the
 * userinfo endpoint since the caller proved no identity to complain about).
 */
private suspend fun ApplicationCall.respondJsonError(
    code: String,
    description: String?,
    status: HttpStatusCode,
) {
    val body = buildJsonObject {
        put("error", JsonPrimitive(code))
        description?.let { put("error_description", JsonPrimitive(it)) }
    }
    respondText(body.toString(), ContentType.Application.Json, status)
}

