package id.walt.etsi.tsl.models

import kotlinx.serialization.Serializable

@Serializable
data class TrustAnchorInfo(
    val x509Certificate: String? = null,
    val x509SubjectName: String? = null,
    val x509SKI: String? = null
)
