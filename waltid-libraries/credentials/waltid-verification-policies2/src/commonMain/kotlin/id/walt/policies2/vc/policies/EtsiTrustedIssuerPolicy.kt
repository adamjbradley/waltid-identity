package id.walt.policies2.vc.policies

import id.walt.credentials.formats.DigitalCredential
import id.walt.trust.TrustService
import id.walt.trust.TrustValidationResult
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.encodeToJsonElement

@Serializable
@SerialName("etsi-trusted-issuer")
data class EtsiTrustedIssuerPolicy(
    val memberStates: List<String> = emptyList(),
    val requireGrantedStatus: Boolean = true
) : CredentialVerificationPolicy2() {
    override val id = "etsi-trusted-issuer"

    companion object {
        /**
         * Service provider set by the service layer at startup.
         * This avoids compile-time coupling to service-commons from multiplatform code.
         */
        var trustServiceProvider: (() -> TrustService?)? = null
    }

    override suspend fun verify(credential: DigitalCredential): Result<JsonElement> {
        val trustService = trustServiceProvider?.invoke()
            ?: return Result.failure(
                IllegalStateException("Trust lists feature is not enabled. Enable it in config or via TRUST_LISTS_ENABLED=true")
            )

        val result = trustService.validateIssuer(credential)

        return if (result.trusted) {
            Result.success(Json.encodeToJsonElement(result))
        } else {
            Result.failure(
                IllegalStateException("Issuer not found in ETSI trust lists")
            )
        }
    }
}
