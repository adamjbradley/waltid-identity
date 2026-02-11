package id.walt.issuer.tenant

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

@Serializable
enum class IssuerTenantStatus { ACTIVE, SUSPENDED, REVOKED }

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
data class IssuerTenant(
    val id: String,
    val legalName: String,
    val country: String,
    val domain: String,
    val contactEmail: String,
    val contactAddress: String? = null,

    // Issuer identity
    val issuerKey: JsonObject? = null,
    val issuerDid: String? = null,
    val x5Chain: List<String>? = null,
    val iacaCertificate: X509CertInfo? = null,
    val signerCertificate: X509CertInfo? = null,
    val ciTokenKey: String? = null,

    // Credential catalog
    val credentialConfigurations: Map<String, JsonElement> = emptyMap(),

    val status: IssuerTenantStatus = IssuerTenantStatus.ACTIVE,
    val createdAt: String,
    val updatedAt: String
)
