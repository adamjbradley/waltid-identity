package id.walt.issuer.statuslist

import id.walt.crypto.keys.KeyType
import id.walt.crypto.keys.jwk.JWKKey
import id.walt.crypto.utils.JsonUtils.toJsonElement
import id.walt.did.dids.DidService
import id.walt.w3c.vc.vcs.W3CVC
import io.klogging.noCoLogger
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.util.concurrent.ConcurrentHashMap

object StatusListCredentialGenerator {

    private val log = noCoLogger("StatusListCredentialGenerator")

    private data class CachedCredential(val jwt: String, val updatedAt: String)

    private val cache = ConcurrentHashMap<String, CachedCredential>()
    private val tokenCache = ConcurrentHashMap<String, CachedCredential>()

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
        tokenCache.remove(listId)
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

    suspend fun generateTokenStatusListJwt(listId: String, baseUrl: String): String? {
        val store = StatusListStore.instanceOrNull() ?: return null
        val data = store.get(listId) ?: return null

        val cached = tokenCache[listId]
        if (cached != null && cached.updatedAt == data.updatedAt) {
            return cached.jwt
        }

        // Ensure IETF encoded list exists (migrate on-demand if needed)
        val ietfEncodedList = data.encodedListIetf ?: run {
            val migratedList = BitstringManager.createEmptyIetf(data.listSize, data.bitsPerStatus)
            val registry = store.getRegistry(listId)
            var current = migratedList
            for ((index, entry) in registry.entries) {
                if (entry.revoked) {
                    current = BitstringManager.setStatusIetf(current, index, 0x01, data.bitsPerStatus)
                }
            }
            store.save(data.copy(encodedListIetf = current))
            current
        }

        val uri = "$baseUrl/status-lists/$listId/token"
        val now = kotlinx.datetime.Clock.System.now()
        val ttl = 300L

        val payload = buildJsonObject {
            put("sub", uri)
            put("iat", now.epochSeconds)
            put("exp", now.epochSeconds + ttl)
            put("ttl", ttl)
            put("status_list", buildJsonObject {
                put("bits", data.bitsPerStatus)
                put("lst", ietfEncodedList)
            })
        }

        val headers = mapOf(
            "typ" to "statuslist+jwt".toJsonElement(),
            "kid" to signingKeyId.toJsonElement(),
        )

        val jwt = signingKey.signJws(
            plaintext = payload.toString().encodeToByteArray(),
            headers = headers
        )

        tokenCache[listId] = CachedCredential(jwt = jwt, updatedAt = data.updatedAt)
        log.info("Generated IETF Token Status List JWT for list $listId")

        return jwt
    }
}
