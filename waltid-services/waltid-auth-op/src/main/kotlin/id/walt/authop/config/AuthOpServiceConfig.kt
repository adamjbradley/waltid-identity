package id.walt.authop.config

import id.walt.commons.config.WaltConfig

/**
 * Top-level service configuration for the auth-op (custom OIDC OP).
 *
 * Loaded via [id.walt.commons.config.ConfigManager] from `config/auth-op.conf`.
 *
 * **Byte-exact issuer rule.** Whatever string survives construction as [issuer] is
 * what this OP advertises in discovery metadata AND what it stamps in the `iss`
 * claim of every JWT it signs. RPs compare both strings byte-for-byte, so we
 * normalise only once — here at construction time — by trimming a single trailing
 * slash. Anything else (casing, port, path) passes through unchanged.
 *
 * @property issuer Canonical issuer URL (e.g. `https://auth.example.com`).
 *   A single trailing `/` is stripped on construction; otherwise echoed verbatim.
 * @property signingKeyPath Filesystem path (absolute or relative to the service
 *   working directory) where the signing key JWK is persisted.
 *   See [id.walt.authop.tokens.KeyProvider.loadOrCreate].
 * @property cookieSecure Whether the `sid` session cookie is marked `Secure`
 *   (only sent over HTTPS). Defaults to **false** for local/dev ergonomics;
 *   operators must flip to `true` in every non-dev deployment. Threaded
 *   through to [id.walt.authop.endpoints.authorizeRoutes] which stamps it
 *   onto the outbound cookie.
 */
data class AuthOpServiceConfig(
    val issuer: String,
    val signingKeyPath: String = "config/signing-key.json",
    val cookieSecure: Boolean = false,
) : WaltConfig() {
    init {
        require(issuer.isNotBlank()) { "auth-op: 'issuer' must not be blank" }
    }

    /**
     * Canonicalised issuer string used for metadata + JWT `iss`.
     * Strips a single trailing slash. Does nothing else.
     */
    val canonicalIssuer: String =
        if (issuer.endsWith('/')) issuer.dropLast(1) else issuer
}
