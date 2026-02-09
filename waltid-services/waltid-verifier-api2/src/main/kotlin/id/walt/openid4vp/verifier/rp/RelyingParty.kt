package id.walt.openid4vp.verifier.rp

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
enum class RpStatus { ACTIVE, SUSPENDED, REVOKED }

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
    val clientId: String,
    val domain: String,
    val certificate: X509CertInfo? = null,
    val privateKeyJwk: JsonObject? = null,
    val x5c: List<String>? = null,
    val status: RpStatus = RpStatus.ACTIVE,
    val createdAt: String,
    val updatedAt: String
)
