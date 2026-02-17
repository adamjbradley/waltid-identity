package id.walt.test.integration.environment.api.issuer

import id.walt.commons.testing.E2ETest
import id.walt.issuer.issuance.IssuanceRequest
import id.walt.issuer.statuslist.CreateStatusListRequest
import id.walt.issuer.statuslist.GlobalEntry
import id.walt.issuer.statuslist.RevokeRequest
import id.walt.issuer.statuslist.StatusListSummary
import id.walt.oid4vc.data.OpenIDProviderMetadata
import id.walt.test.integration.expectSuccess
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import kotlinx.serialization.json.JsonObject

class IssuerApi(
    private val e2e: E2ETest,
    private val client: HttpClient
) {

    suspend fun getProviderMetaDataRaw() =
        client.get("/draft13/.well-known/openid-credential-issuer")

    suspend fun getProviderMetaData(): OpenIDProviderMetadata {
        val response = getProviderMetaDataRaw()
        response.expectSuccess()
        return response.body<OpenIDProviderMetadata>()
    }

    suspend fun issueJwtCredentialRaw(request: IssuanceRequest, cbUrl: String? = null) =
        issueCredentialRaw("/openid4vc/jwt/issue", request, cbUrl)

    suspend fun issueJwtCredential(request: IssuanceRequest, cbUrl: String? = null): String =
        issueJwtCredentialRaw(request, cbUrl).let {
            it.expectSuccess()
            it.body<String>()
        }

    suspend fun issueSdJwtCredentialRaw(request: IssuanceRequest, cbUrl: String? = null) =
        issueCredentialRaw("/openid4vc/sdjwt/issue", request, cbUrl)

    suspend fun issueSdJwtCredential(request: IssuanceRequest, cbUrl: String? = null): String =
        issueSdJwtCredentialRaw(request, cbUrl).let {
            it.expectSuccess()
            it.body<String>()
        }

    suspend fun issueMdocCredentialRaw(request: IssuanceRequest, cbUrl: String? = null) =
        issueCredentialRaw("/openid4vc/mdoc/issue", request, cbUrl)

    suspend fun issueMdocCredential(request: IssuanceRequest, cbUrl: String? = null): String =
        issueMdocCredentialRaw(request, cbUrl).let {
            it.expectSuccess()
            it.body<String>()
        }



    private suspend fun issueCredentialRaw(
        url: String,
        request: IssuanceRequest,
        cbUrl: String? = null
    ) = client.post(url) {
        if (!cbUrl.isNullOrEmpty()) {
            header("statusCallbackUri", cbUrl)
        }
        setBody(request)
    }

    suspend fun mdoc(request: IssuanceRequest, output: ((String) -> Unit)? = null) = issue(
        name = "/openid4vc/mdoc/issue - issue mdoc credential",
        url = "/openid4vc/mdoc/issue",
        request = request,
        output = output,
    )

    suspend fun issueJwtBatch(requests: List<IssuanceRequest>, output: ((String) -> Unit)? = null) =
        e2e.test("/openid4vc/jwt/issueBatch - issue jwt credential batch") {
            client.post("/openid4vc/jwt/issueBatch") {
                setBody(requests)
            }.expectSuccess().apply {
                output?.invoke(body<String>())
            }
        }

    suspend fun issueSdJwtBatch(requests: List<IssuanceRequest>, output: ((String) -> Unit)? = null) =
        e2e.test("/openid4vc/sdjwt/issueBatch - issue sd-jwt credential batch") {
            client.post("/openid4vc/sdjwt/issueBatch") {
                setBody(requests)
            }.expectSuccess().apply {
                output?.invoke(body<String>())
            }
        }

    // Status List Admin API
    suspend fun createStatusListRaw(request: CreateStatusListRequest = CreateStatusListRequest()) =
        client.post("/admin/status-lists") { setBody(request) }

    suspend fun createStatusList(request: CreateStatusListRequest = CreateStatusListRequest()): StatusListSummary =
        createStatusListRaw(request).let { it.expectSuccess(); it.body() }

    suspend fun listStatusListsRaw() = client.get("/admin/status-lists")

    suspend fun listStatusLists(): List<StatusListSummary> =
        listStatusListsRaw().let { it.expectSuccess(); it.body() }

    suspend fun revokeEntryRaw(listId: String, index: Int, reason: String? = null) =
        client.put("/admin/status-lists/$listId/entries/$index/revoke") { setBody(RevokeRequest(reason)) }

    suspend fun revokeEntry(listId: String, index: Int, reason: String? = null): JsonObject =
        revokeEntryRaw(listId, index, reason).let { it.expectSuccess(); it.body() }

    suspend fun unrevokeEntryRaw(listId: String, index: Int) =
        client.put("/admin/status-lists/$listId/entries/$index/unrevoke")

    suspend fun unrevokeEntry(listId: String, index: Int): JsonObject =
        unrevokeEntryRaw(listId, index).let { it.expectSuccess(); it.body() }

    suspend fun searchStatusListEntriesRaw(credentialType: String? = null) =
        client.get("/admin/status-lists/entries/search") {
            url { credentialType?.let { parameters.append("credentialType", it) } }
        }

    suspend fun searchStatusListEntries(credentialType: String? = null): List<GlobalEntry> =
        searchStatusListEntriesRaw(credentialType).let { it.expectSuccess(); it.body() }

    private suspend fun issue(
        name: String,
        url: String,
        request: IssuanceRequest,
        cbUrl: String? = null,
        output: ((String) -> Unit)? = null
    ) =
        e2e.test(name) {
            client.post(url) {
                if (!cbUrl.isNullOrEmpty()) {
                    header("statusCallbackUri", cbUrl)
                }
                setBody(request)
            }.expectSuccess().apply {
                output?.invoke(body<String>())
            }
        }
}