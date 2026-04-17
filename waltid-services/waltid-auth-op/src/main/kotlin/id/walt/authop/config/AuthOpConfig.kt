package id.walt.authop.config

/**
 * Configuration model for the auth-op (custom OIDC OP) service.
 *
 * Two HOCON files drive the service:
 * - `realms.conf` — declares authentication realms (OIDC upstream or OID4VP)
 * - `clients.conf` — declares OAuth 2.0 / OIDC relying parties that may authenticate against the OP
 *
 * These types are deliberately POJOs (Hoplite-friendly data classes). Validation of
 * structural invariants (e.g. realm-id uniqueness, OIDC/OID4VP block presence matching
 * `method`, non-empty redirect URIs) happens at load time in [RealmRegistry.load] /
 * [ClientRegistry.load] and throws [IllegalArgumentException] on violation.
 */

enum class RealmMethod { OIDC, OID4VP }

enum class SubStrategy { CREDENTIAL_SUBJECT_ID, CLAIM_HASH, EPHEMERAL }

enum class TokenEndpointAuthMethod { CLIENT_SECRET_BASIC, CLIENT_SECRET_POST, NONE }

/**
 * A realm groups users authenticated via the same upstream mechanism.
 *
 * Exactly one of [oidc] or [oid4vp] must be present, matching [method].
 *
 * @property id Stable machine identifier (used in URLs, sub-derivation, and `acr_values`).
 * @property name Human-readable display name (used on the realm picker UI).
 * @property method Upstream authentication mechanism.
 * @property oidc OIDC upstream configuration; required when [method] is [RealmMethod.OIDC].
 * @property oid4vp OID4VP verifier configuration; required when [method] is [RealmMethod.OID4VP].
 * @property subStrategy Strategy for deriving the OIDC `sub` claim for OID4VP realms.
 *   Ignored for OIDC realms (which pass through the upstream `sub`).
 * @property claimMapping JSONPath-to-claim mapping applied to the upstream userinfo or
 *   presented credential, producing the ID-token / userinfo claims emitted by this OP.
 * @property subSourceClaims When [subStrategy] is [SubStrategy.CLAIM_HASH], the ordered
 *   list of claims (keyed into [claimMapping]) whose concatenation is hashed to form `sub`.
 */
data class RealmConfig(
    val id: String,
    val name: String,
    val method: RealmMethod,
    val oidc: OidcRealmConfig? = null,
    val oid4vp: Oid4vpRealmConfig? = null,
    val subStrategy: SubStrategy? = null,
    val claimMapping: Map<String, String> = emptyMap(),
    val subSourceClaims: List<String> = emptyList(),
)

/**
 * OIDC upstream configuration for a realm (method = "oidc").
 *
 * @property issuer Upstream OIDC issuer URL (discovery is done at `${issuer}/.well-known/openid-configuration`).
 * @property clientId Client ID this OP presents to the upstream OP.
 * @property clientSecret Client secret for `client_secret_basic` / `client_secret_post`.
 * @property scopes Scopes requested from the upstream OP.
 */
data class OidcRealmConfig(
    val issuer: String,
    val clientId: String,
    val clientSecret: String,
    val scopes: List<String> = listOf("openid"),
)

/**
 * OID4VP verifier configuration for a realm (method = "oid4vp").
 *
 * @property verifierBaseUrl Base URL of the walt.id verifier-api2 instance.
 * @property dcqlQueryFile Path (relative to the service working directory) to the DCQL JSON
 *   this realm presents to the wallet.
 * @property webhookCallbackPath URL path the verifier invokes on this OP when a presentation
 *   succeeds; the OP then resolves the pending authorize session.
 */
data class Oid4vpRealmConfig(
    val verifierBaseUrl: String,
    val dcqlQueryFile: String,
    val webhookCallbackPath: String,
)

/**
 * A relying-party entry in `clients.conf`.
 *
 * @property clientId Client ID presented by the RP (e.g. at the authorize / token endpoints).
 * @property clientSecret Client secret, or null for public clients using `token_endpoint_auth_method = "none"`.
 * @property tokenEndpointAuthMethod How the RP authenticates at the token endpoint.
 * @property redirectUris Whitelist of authorization-response redirect URIs; must be non-empty.
 * @property postLogoutRedirectUris Whitelist of post-logout redirect URIs (may contain `*` wildcards).
 * @property allowedScopes Scopes this RP may request.
 * @property allowedRealms Realms this RP may authenticate against.
 *   Cross-validation against [RealmRegistry] is deferred (TODO — see [ClientRegistry.load]).
 * @property trusted When true, consent may be skipped for this RP.
 */
data class ClientConfig(
    val clientId: String,
    val clientSecret: String? = null,
    val tokenEndpointAuthMethod: TokenEndpointAuthMethod = TokenEndpointAuthMethod.CLIENT_SECRET_BASIC,
    val redirectUris: List<String> = emptyList(),
    val postLogoutRedirectUris: List<String> = emptyList(),
    val allowedScopes: List<String> = emptyList(),
    val allowedRealms: List<String> = emptyList(),
    val trusted: Boolean = false,
)
