package id.walt.policies2.vc.policies

import id.walt.credentials.formats.DigitalCredential
import id.walt.policies2.vc.policies.status.StatusPolicyImplementation.verifyWithAttributes
import id.walt.policies2.vc.policies.status.Values
import id.walt.policies2.vc.policies.status.model.IETFStatusPolicyAttribute
import id.walt.policies2.vc.policies.status.model.W3CStatusPolicyAttribute
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive


@Serializable
@SerialName("revoked-status-list")
class RevocationPolicy : CredentialVerificationPolicy2() {

    override val id: String = "revoked-status-list"

    @Transient
    private val w3cAttribute = W3CStatusPolicyAttribute(
        value = 0u,
        purpose = "revocation",
        type = Values.STATUS_LIST_2021
    )

    @Transient
    private val ietfAttribute = IETFStatusPolicyAttribute(
        value = 0u,
    )

    override suspend fun verify(credential: DigitalCredential): Result<JsonElement> {
        val w3cResult = verifyWithAttributes(credential.credentialData, w3cAttribute)
        // If W3C found a status entry (success or failure), use that result
        val isPolicyNotAvailable = w3cResult.isSuccess && w3cResult.getOrNull()?.let {
            it is JsonObject && it.jsonObject["policy_available"]?.jsonPrimitive?.booleanOrNull == false
        } == true
        if (!isPolicyNotAvailable) return w3cResult
        // W3C status not found, try IETF Token Status List format (SD-JWT VCs)
        return verifyWithAttributes(credential.credentialData, ietfAttribute)
    }
}
