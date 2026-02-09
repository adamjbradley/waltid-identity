package id.walt.openid4vp.verifier

import id.walt.commons.config.ConfigManager
import id.walt.crypto.keys.KeyManager
import id.walt.openid4vp.verifier.data.DcApiFlowSetup
import id.walt.openid4vp.verifier.data.UrlBearingDeviceFlowSetup
import id.walt.openid4vp.verifier.data.Verification2Session
import id.walt.openid4vp.verifier.data.VerificationSessionSetup
import id.walt.openid4vp.verifier.handlers.sessioncreation.VerificationSessionCreator
import id.walt.openid4vp.verifier.rp.RelyingPartyStore
import id.walt.openid4vp.verifier.rp.RpStatus
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

object OSSVerifier2Manager {

    private val config = ConfigManager.getConfig<OSSVerifier2ServiceConfig>()

    suspend fun createVerificationSession(
        setup: VerificationSessionSetup,
        rpId: String? = null,
    ): Verification2Session {
        // Only resolve RP if rpId is provided — when null, the code path is identical to before
        val rp = rpId?.let {
            val store = RelyingPartyStore.instanceOrNull()
                ?: throw IllegalStateException("RP Registrar feature is not enabled")
            val found = store.get(it) ?: throw IllegalArgumentException("Unknown RP: $it")
            require(found.status == RpStatus.ACTIVE) { "RP is ${found.status}" }
            require(found.privateKeyJwk != null && found.x5c != null) { "RP has no certificate" }
            found
        }

        val newSession = VerificationSessionCreator.createVerificationSession(
            setup = setup,
            clientId = setup.core.clientId ?: rp?.clientId ?: config.clientId,
            clientMetadata = setup.core.clientMetadata ?: config.clientMetadata,
            urlPrefix = if (setup is UrlBearingDeviceFlowSetup) {
                setup.urlConfig.urlPrefix
                    ?: rp?.let { "https://${it.domain}/verification-session" }
                    ?: config.urlPrefix
            } else null,
            urlHost = when (setup) {
                is UrlBearingDeviceFlowSetup -> setup.urlConfig.urlHost ?: config.urlHost
                is DcApiFlowSetup -> setup.expectedOrigins.firstOrNull() ?: throw IllegalArgumentException("Missing expected origins (at '$.expectedOrigins')")
            },
            key = setup.core.key?.key
                ?: rp?.privateKeyJwk?.let { jwk ->
                    // Wrap bare JWK in {"type":"jwk","jwk":{...}} format expected by KeyManager
                    val wrappedKey = buildJsonObject {
                        put("type", "jwk")
                        put("jwk", jwk)
                    }
                    KeyManager.resolveSerializedKey(wrappedKey)
                }
                ?: config.key?.let { KeyManager.resolveSerializedKey(it) },
            x5c = setup.core.x5c ?: rp?.x5c ?: config.x5c,
        )

        return newSession
    }


}
