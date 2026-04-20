package id.walt.authop.endpoints

import id.walt.authop.config.AuthOpServiceConfig
import id.walt.crypto.keys.jwk.JWKKey
import io.ktor.http.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * OIDC discovery + JWKS routes.
 *
 * Serves a static metadata block derived from [AuthOpServiceConfig.canonicalIssuer]
 * plus the fixed endpoint path suffixes declared by the design. The issuer string
 * is echoed byte-for-byte; no path normalisation happens here (that's the
 * responsibility of [AuthOpServiceConfig]).
 *
 * The JWKS response contains a single public JWK derived from the signing key.
 * We call [JWKKey.getPublicKey] before [id.walt.crypto.keys.Key.exportJWKObject]
 * so the exported object has no private components (`d`, `p`, `q`, `dp`, `dq`,
 * `qi`). This is the one invariant tested exhaustively — a regression here
 * would leak the signing key to the world.
 */
fun Route.discoveryRoutes(config: AuthOpServiceConfig, signingKey: JWKKey) {
    val issuer = config.canonicalIssuer

    // Metadata is static per-(issuer, endpoint-set). Build it once at route
    // registration rather than per-request.
    val metadata: JsonObject = buildJsonObject {
        put("issuer", issuer)
        put("authorization_endpoint", "$issuer/authorize")
        put("token_endpoint", "$issuer/token")
        put("userinfo_endpoint", "$issuer/userinfo")
        put("end_session_endpoint", "$issuer/end_session")
        put("jwks_uri", "$issuer/jwks.json")

        put("response_types_supported", buildJsonArray { add("code") })
        put("response_modes_supported", buildJsonArray { add("query") })
        put("grant_types_supported", buildJsonArray { add("authorization_code") })
        put("code_challenge_methods_supported", buildJsonArray { add("S256") })
        put("token_endpoint_auth_methods_supported", buildJsonArray {
            add("client_secret_basic")
            add("client_secret_post")
            add("none")
        })
        put("scopes_supported", buildJsonArray {
            add("openid")
            add("profile")
            add("email")
        })
        put("subject_types_supported", buildJsonArray { add("public") })
        put("id_token_signing_alg_values_supported", buildJsonArray { add("RS256") })
    }

    get("/.well-known/openid-configuration") {
        call.respond(HttpStatusCode.OK, metadata)
    }

    get("/jwks.json") {
        // Derive the public JWK per-request. `getPublicKey()` strips private
        // components before serialisation — never serve `signingKey` directly.
        val publicJwk = signingKey.getPublicKey().exportJWKObject()
        val jwks = buildJsonObject {
            put("keys", buildJsonArray { add(publicJwk) })
        }
        call.respond(HttpStatusCode.OK, jwks)
    }
}
