package id.walt.openid4vp.verifier.rp

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
enum class RpStatus { ACTIVE, SUSPENDED, REVOKED }

@Serializable
enum class LawfulBasis {
    CONSENT,
    CONTRACT,
    LEGAL_OBLIGATION,
    VITAL_INTEREST,
    PUBLIC_TASK,
    LEGITIMATE_INTEREST
}

@Serializable
data class X509CertInfo(
    val subject: String,
    val issuer: String,
    val notBefore: String,
    val notAfter: String,
    val serialNumber: String,
    val fingerprint: String
)

@Serializable
data class RelyingParty(
    val id: String,
    val legalName: String,
    val tradeName: String? = null,
    val registrationNumber: String? = null,
    val country: String,
    val contactEmail: String,
    val contactPhone: String? = null,
    val contactAddress: String? = null,
    val intendedUse: String? = null,
    val dcqlQuery: JsonObject? = null,
    val privacyPolicyUrl: String? = null,
    val dataRetentionPeriod: String? = null,
    val lawfulBasis: LawfulBasis? = null,
    val dpaAcknowledged: Boolean = false,
    val clientId: String,
    val domain: String,
    val certificate: X509CertInfo? = null,
    val privateKeyJwk: JsonObject? = null,
    val x5c: List<String>? = null,
    val status: RpStatus = RpStatus.ACTIVE,
    val createdAt: String,
    val updatedAt: String
)
