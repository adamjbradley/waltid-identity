package id.walt.issuer.config

import id.walt.commons.config.WaltConfig
import kotlinx.serialization.json.JsonObject

/**
 * Configuration for EUDI mDoc issuance defaults.
 *
 * When mDoc credentials are issued without an issuerKey or x5Chain parameter in the request,
 * these defaults will be used to provide the necessary signing key and X.509 certificate chain.
 *
 * Configure in issuer-service.conf under the "eudiMdoc" section:
 * ```hocon
 * eudiMdoc {
 *   issuerKey: { "type": "jwk", "jwk": { ... } }
 *   x5Chain: ["-----BEGIN CERTIFICATE-----\n...\n-----END CERTIFICATE-----"]
 * }
 * ```
 */
data class EudiMdocConfig(
    /**
     * Default issuer key for mDoc signing (JWK format as JsonObject).
     * Must be P-256 EC key for EUDI wallet compatibility.
     */
    val issuerKey: JsonObject? = null,

    /**
     * Default X.509 certificate chain for mDoc issuance.
     * Required for EUDI wallet verification - without it, verification fails.
     */
    val x5Chain: List<String>? = null,
) : WaltConfig()
