package id.walt.issuer.psp

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Type of funding source per EWC RFC007.
 */
@Serializable
enum class FundingSourceType {
    /** Payment card (credit, debit, prepaid) */
    CARD,
    /** Bank account (SEPA, ACH, etc.) */
    ACCOUNT,
    /** Any funding source type */
    ANY
}

/**
 * Represents a payment funding source that can be bound to a wallet
 * via Payment Wallet Attestation (EWC RFC007).
 *
 * @property credentialIdentifier Unique ID for this funding source, returned in token response
 * @property type Type of funding source (card or account)
 * @property panLastFour Last 4 digits of PAN for card types
 * @property iin Issuer Identification Number (first 6-8 digits) for card types
 * @property ibanLastFour Last 4 digits of IBAN for account types
 * @property bic Bank Identifier Code for account types
 * @property scheme Payment scheme (visa, mastercard, sepa, etc.)
 * @property currency ISO 4217 currency code
 * @property icon URL to card/bank icon for display
 * @property aliasId PSP-specific alias for the funding source
 */
@Serializable
data class FundingSource(
    val credentialIdentifier: String,
    val type: FundingSourceType,
    val panLastFour: String? = null,
    val iin: String? = null,
    val ibanLastFour: String? = null,
    val bic: String? = null,
    val scheme: String? = null,
    val currency: String? = null,
    val icon: String? = null,
    val aliasId: String? = null
) {
    /**
     * Convert to credential data format for SD-JWT issuance.
     * Returns a JsonObject with funding_source claims per EWC RFC007.
     */
    fun toCredentialData(): JsonObject = buildJsonObject {
        put("funding_source", buildJsonObject {
            put("type", type.name.lowercase())
            panLastFour?.let { put("pan_last_four", it) }
            iin?.let { put("iin", it) }
            ibanLastFour?.let { put("iban_last_four", it) }
            bic?.let { put("bic", it) }
            scheme?.let { put("scheme", it) }
            currency?.let { put("currency", it) }
            icon?.let { put("icon", it) }
            aliasId?.let { put("alias_id", it) }
        })
    }
}
