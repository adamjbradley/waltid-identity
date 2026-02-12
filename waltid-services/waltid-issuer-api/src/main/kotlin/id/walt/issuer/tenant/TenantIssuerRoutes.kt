package id.walt.issuer.tenant

import id.walt.issuer.issuance.IssuanceRequest
import id.walt.issuer.issuance.createCredentialOfferUri
import id.walt.issuer.issuance.validateIssuanceRequestKey
import id.walt.issuer.issuance.validateIssuanceRequestKeys
import id.walt.oid4vc.data.CredentialFormat
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.klogging.noCoLogger
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

private val log = noCoLogger("TenantIssuerRoutes")

fun Application.tenantIssuerRoutes() {
    routing {
        route("issuers/{issuerId}/openid4vc") {

            route("jwt") {
                post("issue") {
                    val (tenant, provider) = resolveTenantForIssuance(call) ?: return@post
                    val request = call.receive<IssuanceRequest>()
                    val enriched = enrichRequestWithTenantKeys(request, tenant)
                    validateIssuanceRequestKey(enriched)

                    val format = provider.getFormatByCredentialConfigurationId(enriched.credentialConfigurationId)
                        ?: throw IllegalArgumentException("Invalid Credential Configuration Id")

                    val offerUri = createTenantCredentialOfferUri(
                        provider = provider,
                        issuanceRequests = listOf(enriched),
                        credentialFormat = format,
                        callbackUrl = call.request.header("statusCallbackUri"),
                        sessionTtl = call.request.header("sessionTtl")?.toLongOrNull()?.seconds
                    )
                    call.respond(HttpStatusCode.OK, offerUri)
                }
            }

            route("sdjwt") {
                post("issue") {
                    val (tenant, provider) = resolveTenantForIssuance(call) ?: return@post
                    val request = call.receive<IssuanceRequest>()
                    val enriched = enrichRequestWithTenantKeys(request, tenant)
                    validateIssuanceRequestKey(enriched)

                    val format = provider.getFormatByCredentialConfigurationId(enriched.credentialConfigurationId)
                        ?: throw IllegalArgumentException("Invalid Credential Configuration Id")

                    val offerUri = createTenantCredentialOfferUri(
                        provider = provider,
                        issuanceRequests = listOf(enriched),
                        credentialFormat = format,
                        callbackUrl = call.request.header("statusCallbackUri"),
                        sessionTtl = call.request.header("sessionTtl")?.toLongOrNull()?.seconds
                    )
                    call.respond(HttpStatusCode.OK, offerUri)
                }
            }

            route("mdoc") {
                post("issue") {
                    val (tenant, provider) = resolveTenantForIssuance(call) ?: return@post
                    val request = call.receive<IssuanceRequest>()
                    val enriched = enrichRequestWithTenantKeys(request, tenant)
                    validateIssuanceRequestKey(enriched)

                    val format = provider.getFormatByCredentialConfigurationId(enriched.credentialConfigurationId)
                        ?: throw IllegalArgumentException("Invalid Credential Configuration Id")

                    val offerUri = createTenantCredentialOfferUri(
                        provider = provider,
                        issuanceRequests = listOf(enriched),
                        credentialFormat = format,
                        callbackUrl = call.request.header("statusCallbackUri"),
                        sessionTtl = call.request.header("sessionTtl")?.toLongOrNull()?.seconds
                    )
                    call.respond(HttpStatusCode.OK, offerUri)
                }
            }
        }
    }
}

/**
 * Enriches an issuance request with tenant's signing keys if not already provided.
 * Cascade: request → tenant → (global would throw at issuance time)
 */
private fun enrichRequestWithTenantKeys(request: IssuanceRequest, tenant: IssuerTenant): IssuanceRequest {
    // Wrap raw JWK in {"type":"jwk","jwk":{...}} format expected by KeyManager
    val wrappedKey = if (request.issuerKey != null) null else tenant.issuerKey?.let { rawJwk ->
        if (rawJwk.containsKey("type")) rawJwk  // Already wrapped
        else buildJsonObject {
            put("type", JsonPrimitive("jwk"))
            put("jwk", rawJwk)
        }
    }

    return request.copy(
        issuerKey = request.issuerKey ?: wrappedKey,
        x5Chain = request.x5Chain ?: tenant.x5Chain?.map { certBase64 ->
            // Convert base64 DER to PEM-style string that X509CertUtils can parse
            val derBytes = java.util.Base64.getDecoder().decode(certBase64)
            String(derBytes, Charsets.ISO_8859_1)
        },
        issuerDid = request.issuerDid ?: tenant.issuerDid
    )
}

/**
 * Creates a credential offer URI using the tenant's CIProvider.
 */
private fun createTenantCredentialOfferUri(
    provider: id.walt.issuer.issuance.CIProvider,
    issuanceRequests: List<IssuanceRequest>,
    credentialFormat: CredentialFormat,
    callbackUrl: String? = null,
    sessionTtl: kotlin.time.Duration? = null,
): String {
    val expiresIn = 5.minutes

    val overwrittenRequests = issuanceRequests.map {
        it.copy(
            credentialFormat = credentialFormat,
            vct = if (credentialFormat == CredentialFormat.sd_jwt_vc || credentialFormat == CredentialFormat.sd_jwt_dc)
                provider.metadata.getVctByCredentialConfigurationId(it.credentialConfigurationId)
                    ?: throw IllegalArgumentException("VCT not found")
            else null
        )
    }

    overwrittenRequests.first().standardVersion
        ?: throw IllegalArgumentException("Attribute [standardVersion] is null")

    val issuanceSession = provider.initializeCredentialOffer(
        issuanceRequests = overwrittenRequests,
        expiresIn = sessionTtl ?: expiresIn,
        callbackUrl = callbackUrl,
        standardVersion = overwrittenRequests.first().standardVersion!!
    )

    val offerRequest = id.walt.oid4vc.requests.CredentialOfferRequest(
        credentialOffer = null,
        credentialOfferUri = provider.buildCredentialOfferUri(
            standardVersion = overwrittenRequests.first().standardVersion!!,
            issuanceSessionId = issuanceSession.id
        )
    )

    return provider.buildOfferUri(offerRequest = offerRequest)
}

/**
 * Resolves tenant for issuance API calls. Similar to resolveTenantProvider but
 * allows tenants without credential configurations (they can specify in the request).
 */
private suspend fun resolveTenantForIssuance(call: ApplicationCall): Pair<IssuerTenant, id.walt.issuer.issuance.CIProvider>? {
    val issuerId = call.parameters["issuerId"]
        ?: run {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing issuerId"))
            return null
        }

    val store = IssuerTenantStore.instanceOrNull()
        ?: run {
            call.respond(HttpStatusCode.NotFound, mapOf("error" to "Issuer Registrar not available"))
            return null
        }

    val tenant = store.get(issuerId)
        ?: run {
            call.respond(HttpStatusCode.NotFound, mapOf("error" to "Unknown issuer: $issuerId"))
            return null
        }

    if (tenant.status != IssuerTenantStatus.ACTIVE) {
        call.respond(
            HttpStatusCode.Forbidden,
            mapOf("error" to "Issuer is ${tenant.status}")
        )
        return null
    }

    if (tenant.issuerKey == null) {
        call.respond(
            HttpStatusCode.Forbidden,
            mapOf("error" to "Issuer has no signing keys configured. Generate or upload certificates first.")
        )
        return null
    }

    val provider = IssuerTenantRegistry.getOrCreate(tenant)
    return tenant to provider
}
