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
 * Two modes for expressing the DCQL query:
 *  - Static: [dcqlQueryFile] points at a JSON file read on each kickoff.
 *  - Dynamic: [scopes] declares a catalog of OIDC-scope → DCQL-claim-paths +
 *    id-token-claim projections. At kickoff the composer unions the claim
 *    paths for the scopes actually requested on this authorize call and
 *    wraps them in a single credential query against [vctValues].
 *
 * Exactly one of the two must be present; [RealmRegistry.load] validates this.
 *
 * @property verifierBaseUrl Base URL of the walt.id verifier-api2 instance.
 * @property dcqlQueryFile Path (relative to the service working directory) to the DCQL JSON
 *   this realm presents to the wallet. Mutually exclusive with [scopes].
 * @property webhookCallbackPath URL path the verifier invokes on this OP when a presentation
 *   succeeds; the OP then resolves the pending authorize session.
 * @property rpId Optional RP ID forwarded to verifier-api2 as `?rpId=` on session-create
 *   so verifier-api2 resolves the per-RP signing key / x5c chain when the RP registrar
 *   is enabled. Null means "verifier-api2 uses its default RP identity".
 * @property credentialFormat DCQL `format` for the composed credential query. Defaults to
 *   `dc+sd-jwt` — the format every PID VCT in this stack speaks.
 * @property vctValues DCQL `meta.vct_values` for the composed query — the list of VCT URIs
 *   the wallet may match against. Only meaningful with [scopes].
 * @property scopes Scope catalog: each entry maps a requested OIDC scope to the DCQL
 *   claim paths the wallet must disclose and (optionally) the claim the RP receives in
 *   its id_token. Unknown scopes on an authorize request are dropped silently.
 */
data class Oid4vpRealmConfig(
    val verifierBaseUrl: String,
    val webhookCallbackPath: String,
    val dcqlQueryFile: String? = null,
    val rpId: String? = null,
    val credentialFormat: String = "dc+sd-jwt",
    val vctValues: List<String> = emptyList(),
    val scopes: Map<String, ScopeDefinition> = emptyMap(),
)

/**
 * One entry in an OID4VP realm's scope catalog.
 *
 * @property claimPaths DCQL `claims[*].path` entries the composer asks the wallet to
 *   disclose. A single scope can demand multiple paths (e.g. KYC = given_name +
 *   family_name + nationality).
 * @property requiredClaims Claim-mapping OUTPUT keys (from
 *   [RealmConfig.claimMapping]) that must all be present (non-null, non-false) for
 *   this scope to be considered satisfied. When empty the scope is considered
 *   always satisfied if requested — useful for informational scopes.
 *   Separate from [claimPaths] because the claim_mapping already flattens nested
 *   DCQL paths (`age_equal_or_over.18` → `age_over_18`); matching by mapped name
 *   avoids re-walking the raw credential in the projector.
 * @property idTokenClaim Name of the id-token claim emitted to the RP when all
 *   [requiredClaims] are present. Null means "don't emit anything to the RP" —
 *   scope is informational-only.
 * @property consentLabel Human-readable label shown on the consent screen under
 *   the "during this session" group. Null falls back to the scope name.
 */
data class ScopeDefinition(
    val claimPaths: List<List<String>>,
    val requiredClaims: List<String> = emptyList(),
    val idTokenClaim: String? = null,
    val consentLabel: String? = null,
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
