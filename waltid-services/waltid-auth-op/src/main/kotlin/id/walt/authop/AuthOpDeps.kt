package id.walt.authop

import id.walt.authop.config.AuthOpServiceConfig
import id.walt.authop.config.ClientRegistry
import id.walt.authop.config.RealmRegistry
import id.walt.authop.store.AuthCodeStore
import id.walt.authop.store.AuthRequestStore
import id.walt.authop.store.CsrfTokenStore
import id.walt.authop.store.LogoutFlowStore
import id.walt.authop.passkey.PasskeyService
import id.walt.authop.passkey.PasskeyStore
import id.walt.authop.store.FlowUpdateStore
import id.walt.authop.store.SessionStore
import id.walt.authop.store.UpstreamFlowStore
import id.walt.authop.store.VpSessionStore
import id.walt.authop.tokens.JwtIssuer
import id.walt.authop.upstream.OidcClient
import id.walt.authop.upstream.Verifier2Client
import id.walt.crypto.keys.jwk.JWKKey

/**
 * Aggregate of every runtime dependency the auth-op Ktor module threads into
 * its routes. The module signature was originally `module(config, signingKey)`;
 * as flow-level tasks (/authorize, /login, /token, …) come online the dep list
 * grows (registries, stores, JWT issuer, HTTP clients for upstream OIDC, etc.).
 *
 * Packaging everything into one container keeps `Application.module` a 1-arg
 * function, test fixtures a single helper, and production wiring a single
 * mapping from [AuthOpRuntime] → `AuthOpDeps`. Tests override only the fields
 * they care about (via the named-arg copy pattern on a data class with defaults)
 * instead of constructing every unused component.
 *
 * **Why a data class over an interface:** the dep list is additive and tests
 * need named-argument construction with defaults. `copy()` gives us override
 * ergonomics for free. If a field ever needs to be lazily constructed or
 * polymorphically swapped we can promote that field's type to an interface
 * without changing this container's shape.
 */
data class AuthOpDeps(
    val config: AuthOpServiceConfig,
    val signingKey: JWKKey,
    val clientRegistry: ClientRegistry,
    val realmRegistry: RealmRegistry,
    val authRequestStore: AuthRequestStore,
    val sessionStore: SessionStore,
    val authCodeStore: AuthCodeStore,
    val csrfTokenStore: CsrfTokenStore,
    val upstreamFlowStore: UpstreamFlowStore,
    val vpSessionStore: VpSessionStore,
    val logoutFlowStore: LogoutFlowStore,
    val jwtIssuer: JwtIssuer,
    val oidcClient: OidcClient,
    val verifier2Client: Verifier2Client,
    // Null when AuthOpServiceConfig.passkey is absent (passkey support
    // disabled). Routes that require passkeys must 404 when null so the
    // absence of the feature is surfaced cleanly rather than via NPE.
    val passkeyStore: PasskeyStore? = null,
    val passkeyService: PasskeyService? = null,
    // Null when AuthOpServiceConfig.flowCallbackSecret is absent (flow-update
    // feature disabled). Every /api/flow-* route 404s when null, same
    // convention as passkey.
    val flowUpdateStore: FlowUpdateStore? = null,
)
