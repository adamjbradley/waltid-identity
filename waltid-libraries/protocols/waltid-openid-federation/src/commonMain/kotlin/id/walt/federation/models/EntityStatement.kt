package id.walt.federation.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

@Serializable
data class EntityStatement(
    @SerialName("iss")
    val issuer: String,

    @SerialName("sub")
    val subject: String,

    @SerialName("iat")
    val issuedAt: Long? = null,

    @SerialName("exp")
    val expiresAt: Long? = null,

    @SerialName("jwks")
    val jwks: JsonObject? = null,

    @SerialName("authority_hints")
    val authorityHints: List<String>? = null,

    @SerialName("metadata")
    val metadata: JsonObject? = null,

    @SerialName("metadata_policy")
    val metadataPolicy: JsonObject? = null,

    @SerialName("constraints")
    val constraints: JsonObject? = null,

    @SerialName("source_endpoint")
    val sourceEndpoint: String? = null,

    @SerialName("trust_marks")
    val trustMarks: List<JsonElement>? = null
) {
    val isSelfSigned: Boolean
        get() = issuer == subject

    fun isExpired(nowEpochSeconds: Long): Boolean {
        return expiresAt?.let { it < nowEpochSeconds } ?: false
    }
}
