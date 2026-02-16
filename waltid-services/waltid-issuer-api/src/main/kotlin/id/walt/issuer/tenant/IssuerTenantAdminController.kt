@file:OptIn(ExperimentalUuidApi::class)

package id.walt.issuer.tenant

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.*
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

// -- Request/Response DTOs --

@Serializable
data class RegisterIssuerRequest(
    val legalName: String,
    val country: String,
    val domain: String,
    val contactEmail: String,
    val contactAddress: String? = null
)

@Serializable
data class UpdateIssuerRequest(
    val legalName: String? = null,
    val country: String? = null,
    val contactEmail: String? = null,
    val contactAddress: String? = null,
    val status: IssuerTenantStatus? = null
)

@Serializable
data class IssuerSummary(
    val id: String,
    val legalName: String,
    val domain: String,
    val country: String,
    val status: IssuerTenantStatus,
    val hasCertificate: Boolean,
    val certificateExpiry: String? = null,
    val credentialCount: Int,
    val createdAt: String
)

@Serializable
data class IssuerDetail(
    val id: String,
    val legalName: String,
    val country: String,
    val domain: String,
    val contactEmail: String,
    val contactAddress: String? = null,
    val issuerDid: String? = null,
    val iacaCertificate: X509CertInfo? = null,
    val signerCertificate: X509CertInfo? = null,
    val x5Chain: List<String>? = null,
    val credentialConfigurations: Map<String, JsonElement> = emptyMap(),
    val status: IssuerTenantStatus,
    val createdAt: String,
    val updatedAt: String
)

@Serializable
data class UploadIssuerCertRequest(
    val issuerKeyJwk: JsonObject,
    val x5Chain: List<String>,
    val ciTokenKeyJwk: JsonObject? = null
)

fun Application.issuerTenantAdminRoutes() {
    routing {
        route("/admin/issuer") {
            get {
                val store = IssuerTenantStore.instanceOrNull()
                    ?: return@get call.respond(
                        HttpStatusCode.ServiceUnavailable,
                        mapOf("error" to "Issuer Registrar feature is not enabled")
                    )

                val summaries = store.list().map { it.toSummary() }
                call.respond(summaries)
            }

            post {
                val store = IssuerTenantStore.instanceOrNull()
                    ?: return@post call.respond(
                        HttpStatusCode.ServiceUnavailable,
                        mapOf("error" to "Issuer Registrar feature is not enabled")
                    )

                val req = call.receive<RegisterIssuerRequest>()

                if (req.legalName.isBlank()) return@post call.respond(
                    HttpStatusCode.BadRequest, mapOf("error" to "legalName is required")
                )
                if (req.country.isBlank()) return@post call.respond(
                    HttpStatusCode.BadRequest, mapOf("error" to "country is required")
                )
                if (req.domain.isBlank()) return@post call.respond(
                    HttpStatusCode.BadRequest, mapOf("error" to "domain is required")
                )
                if (req.contactEmail.isBlank()) return@post call.respond(
                    HttpStatusCode.BadRequest, mapOf("error" to "contactEmail is required")
                )

                if (store.findByDomain(req.domain) != null) return@post call.respond(
                    HttpStatusCode.Conflict, mapOf("error" to "Domain already registered: ${req.domain}")
                )

                val now = java.time.Instant.now().toString()
                val tenant = IssuerTenant(
                    id = Uuid.random().toString(),
                    legalName = req.legalName,
                    country = req.country.uppercase(),
                    domain = req.domain,
                    contactEmail = req.contactEmail,
                    contactAddress = req.contactAddress,
                    status = IssuerTenantStatus.ACTIVE,
                    createdAt = now,
                    updatedAt = now
                )

                store.save(tenant)
                call.respond(HttpStatusCode.Created, tenant.toDetail())
            }

            // -- Trust Service List (TSL) endpoints --

            get("lotl.xml") {
                val store = IssuerTenantStore.instanceOrNull()
                    ?: return@get call.respond(
                        HttpStatusCode.ServiceUnavailable,
                        mapOf("error" to "Issuer Registrar feature is not enabled")
                    )

                val baseUrl = call.request.headers["X-Forwarded-Proto"]?.let { proto ->
                    call.request.headers["X-Forwarded-Host"]?.let { host -> "$proto://$host" }
                } ?: call.request.local.let { "${it.scheme}://${it.serverHost}:${it.serverPort}" }

                val now = ZonedDateTime.now(ZoneOffset.UTC).format(DateTimeFormatter.ISO_INSTANT)

                // Group ACTIVE tenants with certs by country
                val countriesWithIssuers = store.list()
                    .filter { it.status == IssuerTenantStatus.ACTIVE && it.x5Chain != null }
                    .groupBy { it.country.uppercase() }
                    .keys.sorted()

                val pointers = countriesWithIssuers.joinToString("\n") { country ->
                    """      <OtherTSLPointer>
        <TSLLocation>${escapeXml(baseUrl)}/admin/issuer/tsl/${escapeXml(country)}.xml</TSLLocation>
        <AdditionalInformation>
          <SchemeTerritory>${escapeXml(country)}</SchemeTerritory>
          <MimeType>application/vnd.etsi.tsl+xml</MimeType>
        </AdditionalInformation>
      </OtherTSLPointer>"""
                }

                val xml = """<?xml version="1.0" encoding="UTF-8"?>
<TrustServiceStatusList xmlns="http://uri.etsi.org/02231/v2#">
  <SchemeInformation>
    <SchemeOperatorName><Name xml:lang="en">Issuer Registrar List of Trusted Lists</Name></SchemeOperatorName>
    <SchemeTerritory>XX</SchemeTerritory>
    <ListIssueDateTime>$now</ListIssueDateTime>
    <PointersToOtherTSL>
$pointers
    </PointersToOtherTSL>
  </SchemeInformation>
</TrustServiceStatusList>"""

                call.respondText(xml, ContentType.Application.Xml)
            }

            get("tsl/{country}.xml") {
                val store = IssuerTenantStore.instanceOrNull()
                    ?: return@get call.respond(
                        HttpStatusCode.ServiceUnavailable,
                        mapOf("error" to "Issuer Registrar feature is not enabled")
                    )

                val country = call.parameters["country"]!!.uppercase()
                val now = ZonedDateTime.now(ZoneOffset.UTC).format(DateTimeFormatter.ISO_INSTANT)

                val issuers = store.list()
                    .filter { it.status == IssuerTenantStatus.ACTIVE && it.x5Chain != null && it.country.uppercase() == country }

                if (issuers.isEmpty()) {
                    return@get call.respond(
                        HttpStatusCode.NotFound,
                        mapOf("error" to "No active issuers found for country: $country")
                    )
                }

                val providers = issuers.joinToString("\n") { tenant ->
                    val iacaCertBase64 = if (tenant.x5Chain!!.size > 1) tenant.x5Chain[1] else tenant.x5Chain[0]
                    val subjectDn = tenant.iacaCertificate?.subject ?: tenant.legalName
                    val statusStart = tenant.iacaCertificate?.notBefore ?: tenant.createdAt
                    """    <TrustServiceProvider>
      <TSPInformation>
        <TSPName><Name xml:lang="en">${escapeXml(tenant.legalName)}</Name></TSPName>
      </TSPInformation>
      <TSPServices>
        <TSPService>
          <ServiceInformation>
            <ServiceTypeIdentifier>http://uri.etsi.org/TrstSvc/Svctype/CA/QC</ServiceTypeIdentifier>
            <ServiceName><Name xml:lang="en">${escapeXml(tenant.legalName)} Credential Issuing Service</Name></ServiceName>
            <ServiceStatus>http://uri.etsi.org/TrstSvc/TrustedList/Svcstatus/granted</ServiceStatus>
            <StatusStartingTime>$statusStart</StatusStartingTime>
            <ServiceDigitalIdentity>
              <DigitalId>
                <X509Certificate>$iacaCertBase64</X509Certificate>
                <X509SubjectName>${escapeXml(subjectDn)}</X509SubjectName>
              </DigitalId>
            </ServiceDigitalIdentity>
          </ServiceInformation>
        </TSPService>
      </TSPServices>
    </TrustServiceProvider>"""
                }

                val xml = """<?xml version="1.0" encoding="UTF-8"?>
<TrustServiceStatusList xmlns="http://uri.etsi.org/02231/v2#">
  <SchemeInformation>
    <SchemeOperatorName><Name xml:lang="en">$country Trusted Issuers</Name></SchemeOperatorName>
    <SchemeTerritory>$country</SchemeTerritory>
    <ListIssueDateTime>$now</ListIssueDateTime>
  </SchemeInformation>
  <TrustServiceProviderList>
$providers
  </TrustServiceProviderList>
</TrustServiceStatusList>"""

                call.respondText(xml, ContentType.Application.Xml)
            }

            route("{id}") {
                get {
                    val store = IssuerTenantStore.instanceOrNull()
                        ?: return@get call.respond(
                            HttpStatusCode.ServiceUnavailable,
                            mapOf("error" to "Issuer Registrar feature is not enabled")
                        )

                    val id = call.parameters["id"]!!
                    val tenant = store.get(id)
                        ?: return@get call.respond(
                            HttpStatusCode.NotFound, mapOf("error" to "Issuer tenant not found: $id")
                        )

                    call.respond(tenant.toDetail())
                }

                put {
                    val store = IssuerTenantStore.instanceOrNull()
                        ?: return@put call.respond(
                            HttpStatusCode.ServiceUnavailable,
                            mapOf("error" to "Issuer Registrar feature is not enabled")
                        )

                    val id = call.parameters["id"]!!
                    val tenant = store.get(id)
                        ?: return@put call.respond(
                            HttpStatusCode.NotFound, mapOf("error" to "Issuer tenant not found: $id")
                        )

                    val req = call.receive<UpdateIssuerRequest>()

                    if (tenant.status == IssuerTenantStatus.REVOKED && req.status == IssuerTenantStatus.ACTIVE) {
                        return@put call.respond(
                            HttpStatusCode.BadRequest,
                            mapOf("error" to "Cannot reactivate a revoked issuer tenant")
                        )
                    }

                    val updated = tenant.copy(
                        legalName = req.legalName ?: tenant.legalName,
                        country = req.country?.uppercase() ?: tenant.country,
                        contactEmail = req.contactEmail ?: tenant.contactEmail,
                        contactAddress = req.contactAddress ?: tenant.contactAddress,
                        status = req.status ?: tenant.status,
                        updatedAt = java.time.Instant.now().toString()
                    )

                    store.save(updated)
                    if (req.status != null && req.status != tenant.status) {
                        IssuerTenantRegistry.invalidate(id)
                    }
                    call.respond(updated.toDetail())
                }

                delete {
                    val store = IssuerTenantStore.instanceOrNull()
                        ?: return@delete call.respond(
                            HttpStatusCode.ServiceUnavailable,
                            mapOf("error" to "Issuer Registrar feature is not enabled")
                        )

                    val id = call.parameters["id"]!!
                    val removed = store.delete(id)
                    if (removed) {
                        IssuerTenantRegistry.invalidate(id)
                        call.respond(HttpStatusCode.OK, mapOf("deleted" to id))
                    } else {
                        call.respond(HttpStatusCode.NotFound, mapOf("error" to "Issuer tenant not found: $id"))
                    }
                }

                post("certificate/generate") {
                    val store = IssuerTenantStore.instanceOrNull()
                        ?: return@post call.respond(
                            HttpStatusCode.ServiceUnavailable,
                            mapOf("error" to "Issuer Registrar feature is not enabled")
                        )

                    val id = call.parameters["id"]!!
                    val tenant = store.get(id)
                        ?: return@post call.respond(
                            HttpStatusCode.NotFound, mapOf("error" to "Issuer tenant not found: $id")
                        )

                    val generated = IssuerCertificateService.generateCertificates(
                        legalName = tenant.legalName,
                        country = tenant.country
                    )

                    // Wrap ciTokenKey in {"type":"jwk","jwk":{...}} format for KeyManager.resolveSerializedKey
                    val wrappedCiTokenKey = JsonObject(mapOf(
                        "type" to JsonPrimitive("jwk"),
                        "jwk" to generated.ciTokenKeyJwk
                    )).toString()

                    val updated = tenant.copy(
                        issuerKey = generated.issuerKeyJwk,
                        x5Chain = generated.x5Chain,
                        iacaCertificate = generated.iacaCertInfo,
                        signerCertificate = generated.signerCertInfo,
                        ciTokenKey = wrappedCiTokenKey,
                        updatedAt = java.time.Instant.now().toString()
                    )
                    store.save(updated)
                    IssuerTenantRegistry.invalidate(id)

                    call.respond(HttpStatusCode.Created, updated.toDetail())
                }

                post("certificate/upload") {
                    val store = IssuerTenantStore.instanceOrNull()
                        ?: return@post call.respond(
                            HttpStatusCode.ServiceUnavailable,
                            mapOf("error" to "Issuer Registrar feature is not enabled")
                        )

                    val id = call.parameters["id"]!!
                    val tenant = store.get(id)
                        ?: return@post call.respond(
                            HttpStatusCode.NotFound, mapOf("error" to "Issuer tenant not found: $id")
                        )

                    val req = call.receive<UploadIssuerCertRequest>()

                    try {
                        // Parse the leaf certificate from x5Chain to extract cert info
                        val leafCertBytes = Base64.getDecoder().decode(req.x5Chain.first())
                        val cf = java.security.cert.CertificateFactory.getInstance("X.509")
                        val leafCert = cf.generateCertificate(leafCertBytes.inputStream()) as java.security.cert.X509Certificate
                        val signerCertInfo = IssuerCertificateService.extractCertInfo(leafCert)

                        val iacaCertInfo = if (req.x5Chain.size > 1) {
                            val iacaBytes = Base64.getDecoder().decode(req.x5Chain[1])
                            val iacaCert = cf.generateCertificate(iacaBytes.inputStream()) as java.security.cert.X509Certificate
                            IssuerCertificateService.extractCertInfo(iacaCert)
                        } else null

                        // Wrap ciTokenKey in {"type":"jwk","jwk":{...}} format for KeyManager.resolveSerializedKey
                        val wrappedCiTokenKey = req.ciTokenKeyJwk?.let { jwk ->
                            JsonObject(mapOf(
                                "type" to JsonPrimitive("jwk"),
                                "jwk" to jwk
                            )).toString()
                        } ?: tenant.ciTokenKey

                        val updated = tenant.copy(
                            issuerKey = req.issuerKeyJwk,
                            x5Chain = req.x5Chain,
                            iacaCertificate = iacaCertInfo,
                            signerCertificate = signerCertInfo,
                            ciTokenKey = wrappedCiTokenKey,
                            updatedAt = java.time.Instant.now().toString()
                        )
                        store.save(updated)
                        IssuerTenantRegistry.invalidate(id)

                        call.respond(updated.toDetail())
                    } catch (e: Exception) {
                        call.respond(
                            HttpStatusCode.BadRequest,
                            mapOf("error" to "Invalid certificate data: ${e.message}")
                        )
                    }
                }

                get("certificate/download") {
                    val store = IssuerTenantStore.instanceOrNull()
                        ?: return@get call.respond(
                            HttpStatusCode.ServiceUnavailable,
                            mapOf("error" to "Issuer Registrar feature is not enabled")
                        )

                    val id = call.parameters["id"]!!
                    val tenant = store.get(id)
                        ?: return@get call.respond(
                            HttpStatusCode.NotFound, mapOf("error" to "Issuer tenant not found: $id")
                        )

                    if (tenant.issuerKey == null || tenant.x5Chain == null) {
                        return@get call.respond(
                            HttpStatusCode.NotFound,
                            mapOf("error" to "No generated certificate for this issuer. Use certificate/generate first.")
                        )
                    }

                    val response = buildJsonObject {
                        putJsonArray("x5c") { tenant.x5Chain.forEach { add(JsonPrimitive(it)) } }
                        put("issuerKey", tenant.issuerKey)
                        put("iacaCertificate", Json.encodeToJsonElement(tenant.iacaCertificate!!))
                        put("signerCertificate", Json.encodeToJsonElement(tenant.signerCertificate!!))
                    }
                    call.respond(response)
                }

                put("credentials") {
                    val store = IssuerTenantStore.instanceOrNull()
                        ?: return@put call.respond(
                            HttpStatusCode.ServiceUnavailable,
                            mapOf("error" to "Issuer Registrar feature is not enabled")
                        )

                    val id = call.parameters["id"]!!
                    val tenant = store.get(id)
                        ?: return@put call.respond(
                            HttpStatusCode.NotFound, mapOf("error" to "Issuer tenant not found: $id")
                        )

                    val credentialConfigs = call.receive<Map<String, JsonElement>>()

                    val updated = tenant.copy(
                        credentialConfigurations = credentialConfigs,
                        updatedAt = java.time.Instant.now().toString()
                    )
                    store.save(updated)
                    IssuerTenantRegistry.invalidate(id)

                    call.respond(updated.toDetail())
                }
            }
        }
    }
}

// -- XML helpers --

private fun escapeXml(text: String): String = text
    .replace("&", "&amp;")
    .replace("<", "&lt;")
    .replace(">", "&gt;")
    .replace("\"", "&quot;")
    .replace("'", "&apos;")

// -- Extension functions --

private fun IssuerTenant.toSummary() = IssuerSummary(
    id = id,
    legalName = legalName,
    domain = domain,
    country = country,
    status = status,
    hasCertificate = issuerKey != null,
    certificateExpiry = signerCertificate?.notAfter,
    credentialCount = credentialConfigurations.size,
    createdAt = createdAt
)

private fun IssuerTenant.toDetail() = IssuerDetail(
    id = id,
    legalName = legalName,
    country = country,
    domain = domain,
    contactEmail = contactEmail,
    contactAddress = contactAddress,
    issuerDid = issuerDid,
    iacaCertificate = iacaCertificate,
    signerCertificate = signerCertificate,
    x5Chain = x5Chain,
    credentialConfigurations = credentialConfigurations,
    status = status,
    createdAt = createdAt,
    updatedAt = updatedAt
)
