package id.walt.issuer.psp

import id.walt.commons.config.WaltConfig
import kotlinx.serialization.Serializable

/**
 * Configuration for Payment Wallet Attestation (EWC RFC007) feature.
 *
 * ## Default State
 *
 * **PWA is DISABLED by default (enabled = false).**
 *
 * When disabled:
 * - PaymentWalletAttestation credential type is NOT registered in metadata
 * - Token responses do NOT include authorization_details
 * - PSP adapter is NOT instantiated
 * - Zero impact on existing credential issuance flows
 *
 * ## How to Enable
 *
 * Enable via one of:
 * - Environment variable: `PWA_ENABLED=true`
 * - Config file: Set `enabled = true` in `config/pwa.conf`
 * - Features config: Add `pwa` to `enabledFeatures` in `config/_features.conf`
 *
 * ## Docker Compose
 *
 * ```bash
 * echo "PWA_ENABLED=true" >> docker-compose/.env.local
 * docker compose --profile identity up -d issuer-api
 * ```
 */
@Serializable
data class PwaConfig(
    /**
     * Master switch for PWA feature.
     *
     * DEFAULTS TO FALSE - PWA is disabled by default.
     * Can be overridden by PWA_ENABLED environment variable.
     */
    val enabled: Boolean = false,

    /** PSP adapter type: "mock" for testing, or fully qualified class name for production */
    val pspAdapterType: String = "mock",

    /** Credential configuration ID for PaymentWalletAttestation */
    val credentialConfigurationId: String = "PaymentWalletAttestation",
) : WaltConfig()
