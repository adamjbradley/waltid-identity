package id.walt.authop

import id.walt.authop.config.AuthOpServiceConfig
import id.walt.authop.config.ClientRegistry
import id.walt.authop.config.RealmRegistry
import id.walt.authop.store.AuthRequestStore
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
)
