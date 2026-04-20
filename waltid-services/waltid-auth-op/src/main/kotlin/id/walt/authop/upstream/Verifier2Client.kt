@file:OptIn(ExperimentalTime::class)

package id.walt.authop.upstream

import id.walt.authop.domain.VpSessionStatus
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.URLBuilder
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * HTTP client for driving the walt.id verifier-api2 session-management API from
 * the auth-op's OID4VP realm adapter.
 *
 * # Responsibilities
 *
 *  1. [createSession] — POSTs to `/verification-session/create` with a DCQL
 *     query (from `dcql_query_file`) plus a webhook-notifications block that
 *     registers this service as the destination for presentation results.
 *  2. [getSessionInfo] — GETs `/verification-session/{id}/info` ONLY for the
 *     recovery-path pre-check on page reload. This endpoint **MUST NOT** be
 *     used to source credential data: in transactional mode verifier-api2
 *     clears `presentedCredentials` / `presentedPresentations` after the final
 *     `policy_results_available` event (see
 *     `waltid-services/waltid-verifier-api2/docs/TRANSACTIONAL_VERIFICATION.md:91-94, 117-118`).
 *     Credential data flows in via the inbound webhook handled in Task 18.
 *
 * # Request shape (Verified against upstream sources)
 *
 * `VerificationSessionSetup` is a polymorphic sealed interface with a
 * `flow_type` JSON discriminator (`cross_device`, `same_device`, `dc_api`).
 * Every flow wraps a [GeneralFlowConfig] under the `core` field — but
 * `CrossDeviceFlowSetup` overrides the `core` SerialName to `core_flow`
 * (see `VerificationSessionSetupData.kt:94-97`), so the body we POST for
 * cross-device flows is:
 *
 * ```json
 * {
 *   "flow_type": "cross_device",
 *   "core_flow": {
 *     "dcql_query": { ... },              // SerialName "dcql_query" — NOT dcqlQuery
 *     "notifications": {
 *       "webhook": {
 *         "url": "<our callback URL>",
 *         "bearer_token": "<shared secret>"   // SerialName "bearer_token"
 *       }
 *     }
 *   }
 * }
 * ```
 *
 * Field-name provenance:
 *  - `flow_type` discriminator: `VerificationSessionSetupData.kt:77`
 *    (`@JsonClassDiscriminator("flow_type")`).
 *  - `core_flow` (cross-device): `VerificationSessionSetupData.kt:94-95`
 *    (`@SerialName("core_flow") override val core`).
 *  - `dcql_query`: `VerificationSessionSetupData.kt:28-29`
 *    (`@SerialName("dcql_query") val dcqlQuery`).
 *  - `notifications`: `VerificationSessionSetupData.kt:36`
 *    (`val notifications: KtorSessionNotifications? = null`).
 *  - `notifications.webhook.url` / `.bearer_token`:
 *    `waltid-ktor-notifications-core/.../KtorSessionNotifications.kt:8, 12, 21`
 *    (the `bearer_token` key is the SerialName for the `bearerToken` field).
 *
 * Optional query parameter `?rpId=<rpId>` is read at the route handler layer
 * (`OSSVerifier2Service.kt:82-84`) — verifier-api2 uses it to resolve the RP's
 * own signing key / certificate chain when the RP registrar is enabled.
 *
 * # Response shape (Verified against upstream sources)
 *
 * `VerificationSessionCreator.VerificationSessionCreationResponse`
 * (`VerificationSessionCreator.kt:36-41`) returns:
 *
 * ```json
 * {
 *   "sessionId": "...",
 *   "bootstrapAuthorizationRequestUrl": "openid4vp://...",   // nullable; present for cross-device (QR code)
 *   "fullAuthorizationRequestUrl": "openid4vp://...",        // required
 *   "creationTarget": null                                   // nullable; always null today
 * }
 * ```
 *
 * # Scope boundaries (v1)
 *
 *  - No SSE streaming — the wallet→verifier→our-webhook path is server-to-server.
 *  - No webhook RECEIVER (Task 18).
 *  - No credential extraction (Task 19's ClaimMapper reuse).
 *  - No polling loop — Task 17's browser polls our own endpoint, not the
 *    verifier's.
 */
class Verifier2Client(
    private val httpClient: HttpClient = defaultHttpClient(),
    @Suppress("unused") private val clock: Clock = Clock.System,
) {

    /**
     * Create a verification session on verifier-api2, registering our webhook
     * URL + shared secret so presentation results flow back server-to-server.
     *
     * @param verifierBaseUrl e.g. `https://verifier2.example` (no trailing slash
     *   is fine — we normalize).
     * @param dcqlQuery the DCQL query object loaded from the realm's
     *   `dcql_query_file`. Passed through byte-for-byte as `core_flow.dcql_query`.
     * @param webhookUrl the absolute URL verifier-api2 will POST to when the
     *   wallet completes a presentation.
     * @param webhookSecret the shared secret transmitted in the verifier's
     *   `Authorization: Bearer <secret>` header on webhook callback. The inbound
     *   webhook handler (Task 18) validates this before accepting credentials.
     * @param rpId optional RP ID (per-RP signing key / x5c resolution at
     *   verifier-api2). Appended as a `?rpId=` query parameter when non-null.
     */
    suspend fun createSession(
        verifierBaseUrl: String,
        dcqlQuery: JsonObject,
        webhookUrl: String,
        webhookSecret: String,
        rpId: String? = null,
    ): CreateSessionResponse {
        val base = verifierBaseUrl.trimEnd('/')
        val url = URLBuilder("$base/verification-session/create").apply {
            if (rpId != null) parameters.append("rpId", rpId)
        }.buildString()

        // Verified against VerificationSessionSetupData.kt:77, 94-95, 28-29, 36
        // (flow_type discriminator + cross_device's core_flow field + dcql_query +
        // notifications), and KtorSessionNotifications.kt:8, 12, 21 (webhook.url +
        // bearer_token SerialName).
        val body = buildJsonObject {
            put("flow_type", "cross_device")
            putJsonObject("core_flow") {
                put("dcql_query", dcqlQuery)
                // EUDI wallet profile requires the Request Object to be served
                // as a signed JAR (RFC 9101, content-type application/oauth-authz-req+jwt).
                // Without this, verifier-api2 falls back to plain JSON and the wallet
                // rejects with `InvalidJarJwt(cause=JAR JWT parse error)`.
                put("signed_request", true)
                putJsonObject("notifications") {
                    putJsonObject("webhook") {
                        put("url", webhookUrl)
                        put("bearer_token", webhookSecret)
                    }
                }
            }
        }

        val response: HttpResponse = try {
            httpClient.post(url) {
                contentType(ContentType.Application.Json)
                header(HttpHeaders.Accept, "application/json")
                setBody(body.toString())
            }
        } catch (t: HttpRequestTimeoutException) {
            throw Verifier2ClientException(
                "upstream_timeout",
                "verifier-api2 session-create timed out: ${t.message}",
                t
            )
        } catch (t: Throwable) {
            throw Verifier2ClientException(
                "verifier_session_create_failed",
                "verifier-api2 session-create failed: ${t.message}",
                t
            )
        }

        val raw = response.bodyAsText()
        if (!response.status.isSuccess()) {
            throw Verifier2ClientException(
                "verifier_session_create_failed",
                "verifier-api2 /verification-session/create returned HTTP ${response.status.value}: ${raw.take(512)}"
            )
        }

        val doc = try {
            Json.parseToJsonElement(raw).jsonObject
        } catch (t: Throwable) {
            throw Verifier2ClientException(
                "verifier_session_create_failed",
                "verifier-api2 session-create body is not JSON",
                t
            )
        }

        val sessionId = doc["sessionId"]?.jsonPrimitive?.contentOrNullSafe()
            ?: throw Verifier2ClientException(
                "verifier_session_create_failed",
                "verifier-api2 session-create response missing 'sessionId'"
            )
        val fullUrl = doc["fullAuthorizationRequestUrl"]?.jsonPrimitive?.contentOrNullSafe()
            ?: throw Verifier2ClientException(
                "verifier_session_create_failed",
                "verifier-api2 session-create response missing 'fullAuthorizationRequestUrl'"
            )
        return CreateSessionResponse(
            sessionId = sessionId,
            bootstrapAuthorizationRequestUrl = doc["bootstrapAuthorizationRequestUrl"]
                ?.jsonPrimitive?.contentOrNullSafe(),
            fullAuthorizationRequestUrl = fullUrl,
            creationTarget = doc["creationTarget"]?.jsonPrimitive?.contentOrNullSafe(),
        )
    }

    /**
     * Used ONLY for recovery-path pre-check on page reload (check if session is
     * already `SUCCESSFUL` / `UNSUCCESSFUL`, so we can short-circuit re-rendering
     * the QR).
     *
     * **Not a credential-data source** — see class KDoc: verifier-api2 clears
     * `presentedCredentials` / `presentedPresentations` after the final
     * `policy_results_available` event in transactional mode.
     */
    suspend fun getSessionInfo(verifierBaseUrl: String, sessionId: String): SessionInfo {
        val base = verifierBaseUrl.trimEnd('/')
        val url = "$base/verification-session/$sessionId/info"

        val response: HttpResponse = try {
            httpClient.get(url) {
                header(HttpHeaders.Accept, "application/json")
            }
        } catch (t: HttpRequestTimeoutException) {
            throw Verifier2ClientException(
                "upstream_timeout",
                "verifier-api2 session-info timed out: ${t.message}",
                t
            )
        } catch (t: Throwable) {
            throw Verifier2ClientException(
                "verifier_session_info_failed",
                "verifier-api2 session-info failed: ${t.message}",
                t
            )
        }

        if (response.status == HttpStatusCode.NotFound) {
            throw Verifier2ClientException(
                "verifier_session_not_found",
                "verifier-api2 has no session '$sessionId'"
            )
        }
        if (!response.status.isSuccess()) {
            throw Verifier2ClientException(
                "verifier_session_info_failed",
                "verifier-api2 /verification-session/$sessionId/info returned HTTP ${response.status.value}"
            )
        }

        val raw = response.bodyAsText()
        val doc = try {
            Json.parseToJsonElement(raw).jsonObject
        } catch (t: Throwable) {
            throw Verifier2ClientException(
                "verifier_session_info_failed",
                "verifier-api2 session-info body is not JSON",
                t
            )
        }

        // Verified against Verification2Session.kt:58 (`var status`) and :147-177
        // (`VerificationSessionStatus` enum values: ACTIVE / UNUSED / IN_USE /
        // VALIDATING_RECEIVED_REQUEST / PROCESSING_FLOW / EXPIRED / SUCCESSFUL /
        // FAILED / UNSUCCESSFUL). We also defensively treat the literal
        // "UNKNOWN" as UNSUCCESSFUL so a truly indeterminate state from a
        // future verifier-api2 doesn't strand the user polling forever.
        val upstreamStatus = doc["status"]?.jsonPrimitive?.contentOrNullSafe()
            ?: throw Verifier2ClientException(
                "verifier_session_info_failed",
                "verifier-api2 session-info response missing 'status'"
            )

        val mapped = when (upstreamStatus) {
            "SUCCESSFUL" -> VpSessionStatus.SUCCESSFUL
            "UNSUCCESSFUL", "FAILED", "EXPIRED", "UNKNOWN" -> VpSessionStatus.UNSUCCESSFUL
            else -> VpSessionStatus.PENDING  // ACTIVE / UNUSED / IN_USE / VALIDATING_RECEIVED_REQUEST / PROCESSING_FLOW
        }

        return SessionInfo(sessionId = sessionId, status = mapped)
    }

    companion object {
        /**
         * Default production [HttpClient]: OkHttp engine (repo-wide convention),
         * JSON content negotiation, and [HttpTimeout] so a slow or misbehaving
         * verifier-api2 cannot hold a user-facing coroutine indefinitely.
         *
         * Values mirror [OidcClient.defaultHttpClient]. Tests swap this for a
         * `MockEngine`-backed client via the ctor.
         */
        fun defaultHttpClient(): HttpClient = HttpClient(OkHttp) {
            install(ContentNegotiation) { json() }
            install(HttpTimeout) {
                requestTimeoutMillis = 15_000
                connectTimeoutMillis = 5_000
                socketTimeoutMillis = 15_000
            }
        }
    }
}

/** Parsed subset of verifier-api2's `VerificationSessionCreationResponse`. */
data class CreateSessionResponse(
    val sessionId: String,
    /** Present for cross-device flows (QR code target). Null for same-device. */
    val bootstrapAuthorizationRequestUrl: String?,
    /** Same-device deep-link — always present in verifier-api2 responses. */
    val fullAuthorizationRequestUrl: String,
    /** Always null in current verifier-api2 builds, kept for forward-compat. */
    val creationTarget: String?,
)

/**
 * Recovery-path pre-check response. **Deliberately omits** credential fields to
 * enforce at the type level that this endpoint is never a source of credential
 * data.
 */
data class SessionInfo(
    val sessionId: String,
    val status: VpSessionStatus,
)

/**
 * Exceptions raised by [Verifier2Client]. [code] is machine-readable; callers
 * can map it into a domain error (Task 17's VpFlowRoutes will surface
 * `upstream_timeout` distinctly from `verifier_session_create_failed` in error
 * responses / logs).
 */
class Verifier2ClientException(
    val code: String,
    description: String,
    cause: Throwable? = null,
) : RuntimeException(description, cause)

/**
 * Null-safe accessor for [JsonPrimitive.content] that returns null when the
 * primitive is a JSON null (`isString == false && content == "null"`). Prevents
 * the literal string `"null"` from sneaking through byte-equality checks.
 */
private fun JsonPrimitive.contentOrNullSafe(): String? =
    if (!isString && content == "null") null else content

private fun HttpStatusCode.isSuccess(): Boolean = value in 200..299
