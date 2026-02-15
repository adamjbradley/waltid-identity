package id.walt.issuer.statuslist

import id.walt.crypto.keys.KeySerialization
import id.walt.crypto.keys.KeyType
import id.walt.crypto.keys.jwk.JWKKey
import id.walt.did.dids.DidService
import id.walt.w3c.vc.vcs.W3CVC
import io.klogging.noCoLogger
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.util.concurrent.ConcurrentHashMap

object StatusListCredentialGenerator {

    private val log = noCoLogger("StatusListCredentialGenerator")

    private data class CachedCredential(val jwt: String, val updatedAt: String)

    private val cache = ConcurrentHashMap<String, CachedCredential>()

    // Lazily generated signing key for status list credentials
    private val signingKey by lazy {
        runBlocking { JWKKey.generate(KeyType.secp256r1) }
    }

    private val signingKeyId by lazy {
        runBlocking { signingKey.getKeyId() }
    }

    private val issuerDid by lazy {
        runBlocking { DidService.registerByKey("key", signingKey).did }
    }

    fun invalidateCache(listId: String) {
        cache.remove(listId)
    }

    suspend fun generateCredentialJwt(listId: String, baseUrl: String): String? {
        val store = StatusListStore.instanceOrNull() ?: return null
        val data = store.get(listId) ?: return null

        // Return cached JWT if still valid
        val cached = cache[listId]
        if (cached != null && cached.updatedAt == data.updatedAt) {
            return cached.jwt
        }

        val credentialId = "$baseUrl/status-lists/$listId"
        val now = kotlinx.datetime.Clock.System.now().toString()

        val vcJson = buildJsonObject {
            putJsonArray("@context") {
                add(JsonPrimitive("https://www.w3.org/2018/credentials/v1"))
                add(JsonPrimitive("https://w3id.org/vc/status-list/2021/v1"))
            }
            put("id", credentialId)
            putJsonArray("type") {
                add(JsonPrimitive("VerifiableCredential"))
                add(JsonPrimitive("StatusList2021Credential"))
            }
            put("issuer", issuerDid)
            put("issuanceDate", now)
            put("credentialSubject", buildJsonObject {
                put("id", "$credentialId#list")
                put("type", "StatusList2021")
                put("statusPurpose", data.purpose)
                put("encodedList", data.encodedList)
            })
        }

        val vc = W3CVC.fromJson(vcJson.toString())

        val jwt = vc.signJws(
            issuerKey = signingKey,
            issuerId = issuerDid,
            subjectDid = "$credentialId#list"
        )

        cache[listId] = CachedCredential(jwt = jwt, updatedAt = data.updatedAt)
        log.info("Generated StatusList2021Credential JWT for list $listId")

        return jwt
    }
}
