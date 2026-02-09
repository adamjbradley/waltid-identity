@file:OptIn(ExperimentalUuidApi::class)

package id.walt.openid4vp.verifier.rp

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.util.*
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

// -- Request/Response DTOs --

@Serializable
data class RegisterRpRequest(
    val legalName: String,
    val tradeName: String? = null,
    val registrationNumber: String? = null,
    val country: String,
    val domain: String,
    val contactEmail: String,
    val contactPhone: String? = null,
    val contactAddress: String,
    val intendedUse: String? = null,
    val privacyPolicyUrl: String,
    val dataRetentionPeriod: String,
    val lawfulBasis: LawfulBasis,
    val dpaAcknowledged: Boolean
)

@Serializable
data class UpdateRpRequest(
    val legalName: String? = null,
    val tradeName: String? = null,
    val registrationNumber: String? = null,
    val country: String? = null,
    val contactEmail: String? = null,
    val contactPhone: String? = null,
    val contactAddress: String? = null,
    val intendedUse: String? = null,
    val status: RpStatus? = null,
    val privacyPolicyUrl: String? = null,
    val dataRetentionPeriod: String? = null,
    val lawfulBasis: LawfulBasis? = null,
    val dpaAcknowledged: Boolean? = null
)

@Serializable
data class IntendedUseRequest(
    val intendedUse: String? = null,
    val dcqlQuery: JsonObject? = null
)

@Serializable
data class UploadCertRequest(
    val certificatePem: String,
    val privateKeyPem: String? = null
)

@Serializable
data class RpSummary(
    val id: String,
    val legalName: String,
    val domain: String,
    val country: String,
    val status: RpStatus,
    val hasCertificate: Boolean,
    val certificateExpiry: String? = null,
    val createdAt: String
)

@Serializable
data class RpDetail(
    val id: String,
    val legalName: String,
    val tradeName: String? = null,
    val registrationNumber: String? = null,
    val country: String,
    val contactEmail: String,
    val contactPhone: String? = null,
    val contactAddress: String? = null,
    val intendedUse: String? = null,
    val dcqlQuery: JsonObject? = null,
    val privacyPolicyUrl: String? = null,
    val dataRetentionPeriod: String? = null,
    val lawfulBasis: LawfulBasis? = null,
    val dpaAcknowledged: Boolean = false,
    val clientId: String,
    val domain: String,
    val certificate: X509CertInfo? = null,
    val x5c: List<String>? = null,
    val status: RpStatus,
    val createdAt: String,
    val updatedAt: String
)

fun Application.rpAdminRoutes() {
    routing {
        route("/admin/rp") {
            get {
                val store = RelyingPartyStore.instanceOrNull()
                    ?: return@get call.respond(
                        HttpStatusCode.ServiceUnavailable,
                        mapOf("error" to "RP Registrar feature is not enabled")
                    )

                val summaries = store.list().map { it.toSummary() }
                call.respond(summaries)
            }

            post {
                val store = RelyingPartyStore.instanceOrNull()
                    ?: return@post call.respond(
                        HttpStatusCode.ServiceUnavailable,
                        mapOf("error" to "RP Registrar feature is not enabled")
                    )

                val req = call.receive<RegisterRpRequest>()

                // Validation
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
                if (req.contactAddress.isBlank()) return@post call.respond(
                    HttpStatusCode.BadRequest, mapOf("error" to "contactAddress is required")
                )
                if (req.privacyPolicyUrl.isBlank()) return@post call.respond(
                    HttpStatusCode.BadRequest, mapOf("error" to "privacyPolicyUrl is required")
                )
                if (req.dataRetentionPeriod.isBlank()) return@post call.respond(
                    HttpStatusCode.BadRequest, mapOf("error" to "dataRetentionPeriod is required")
                )
                if (!req.dpaAcknowledged) return@post call.respond(
                    HttpStatusCode.BadRequest, mapOf("error" to "dpaAcknowledged must be true to register")
                )
                if (req.intendedUse != null && req.intendedUse.length > 500) return@post call.respond(
                    HttpStatusCode.BadRequest, mapOf("error" to "intendedUse must be 500 characters or less")
                )

                // Check duplicate domain
                if (store.findByDomain(req.domain) != null) return@post call.respond(
                    HttpStatusCode.Conflict, mapOf("error" to "Domain already registered: ${req.domain}")
                )

                val now = java.time.Instant.now().toString()
                val rp = RelyingParty(
                    id = Uuid.random().toString(),
                    legalName = req.legalName,
                    tradeName = req.tradeName,
                    registrationNumber = req.registrationNumber,
                    country = req.country.uppercase(),
                    contactEmail = req.contactEmail,
                    contactPhone = req.contactPhone,
                    contactAddress = req.contactAddress,
                    intendedUse = req.intendedUse,
                    privacyPolicyUrl = req.privacyPolicyUrl,
                    dataRetentionPeriod = req.dataRetentionPeriod,
                    lawfulBasis = req.lawfulBasis,
                    dpaAcknowledged = req.dpaAcknowledged,
                    clientId = "x509_san_dns:${req.domain}",
                    domain = req.domain,
                    status = RpStatus.ACTIVE,
                    createdAt = now,
                    updatedAt = now
                )

                store.save(rp)
                call.respond(HttpStatusCode.Created, rp.toDetail())
            }

            route("{id}") {
                get {
                    val store = RelyingPartyStore.instanceOrNull()
                        ?: return@get call.respond(
                            HttpStatusCode.ServiceUnavailable,
                            mapOf("error" to "RP Registrar feature is not enabled")
                        )

                    val id = call.parameters["id"]!!
                    val rp = store.get(id)
                        ?: return@get call.respond(HttpStatusCode.NotFound, mapOf("error" to "RP not found: $id"))

                    call.respond(rp.toDetail())
                }

                put {
                    val store = RelyingPartyStore.instanceOrNull()
                        ?: return@put call.respond(
                            HttpStatusCode.ServiceUnavailable,
                            mapOf("error" to "RP Registrar feature is not enabled")
                        )

                    val id = call.parameters["id"]!!
                    val rp = store.get(id)
                        ?: return@put call.respond(HttpStatusCode.NotFound, mapOf("error" to "RP not found: $id"))

                    val req = call.receive<UpdateRpRequest>()

                    // Prevent reactivation from REVOKED
                    if (rp.status == RpStatus.REVOKED && req.status == RpStatus.ACTIVE) {
                        return@put call.respond(
                            HttpStatusCode.BadRequest,
                            mapOf("error" to "Cannot reactivate a revoked RP")
                        )
                    }

                    val updated = rp.copy(
                        legalName = req.legalName ?: rp.legalName,
                        tradeName = req.tradeName ?: rp.tradeName,
                        registrationNumber = req.registrationNumber ?: rp.registrationNumber,
                        country = req.country?.uppercase() ?: rp.country,
                        contactEmail = req.contactEmail ?: rp.contactEmail,
                        contactPhone = req.contactPhone ?: rp.contactPhone,
                        contactAddress = req.contactAddress ?: rp.contactAddress,
                        intendedUse = req.intendedUse ?: rp.intendedUse,
                        privacyPolicyUrl = req.privacyPolicyUrl ?: rp.privacyPolicyUrl,
                        dataRetentionPeriod = req.dataRetentionPeriod ?: rp.dataRetentionPeriod,
                        lawfulBasis = req.lawfulBasis ?: rp.lawfulBasis,
                        dpaAcknowledged = req.dpaAcknowledged ?: rp.dpaAcknowledged,
                        status = req.status ?: rp.status,
                        updatedAt = java.time.Instant.now().toString()
                    )

                    store.save(updated)
                    call.respond(updated.toDetail())
                }

                delete {
                    val store = RelyingPartyStore.instanceOrNull()
                        ?: return@delete call.respond(
                            HttpStatusCode.ServiceUnavailable,
                            mapOf("error" to "RP Registrar feature is not enabled")
                        )

                    val id = call.parameters["id"]!!
                    val removed = store.delete(id)
                    if (removed) {
                        call.respond(HttpStatusCode.OK, mapOf("deleted" to id))
                    } else {
                        call.respond(HttpStatusCode.NotFound, mapOf("error" to "RP not found: $id"))
                    }
                }

                post("certificate/generate") {
                    val store = RelyingPartyStore.instanceOrNull()
                        ?: return@post call.respond(
                            HttpStatusCode.ServiceUnavailable,
                            mapOf("error" to "RP Registrar feature is not enabled")
                        )

                    val id = call.parameters["id"]!!
                    val rp = store.get(id)
                        ?: return@post call.respond(HttpStatusCode.NotFound, mapOf("error" to "RP not found: $id"))

                    val generated = RpCertificateService.generateCertificate(rp.legalName, rp.domain)

                    val updated = rp.copy(
                        certificate = generated.certInfo,
                        privateKeyJwk = generated.privateKeyJwk,
                        x5c = generated.x5c,
                        updatedAt = java.time.Instant.now().toString()
                    )
                    store.save(updated)

                    call.respond(HttpStatusCode.Created, updated.toDetail())
                }

                post("certificate/upload") {
                    val store = RelyingPartyStore.instanceOrNull()
                        ?: return@post call.respond(
                            HttpStatusCode.ServiceUnavailable,
                            mapOf("error" to "RP Registrar feature is not enabled")
                        )

                    val id = call.parameters["id"]!!
                    val rp = store.get(id)
                        ?: return@post call.respond(HttpStatusCode.NotFound, mapOf("error" to "RP not found: $id"))

                    val req = call.receive<UploadCertRequest>()
                    try {
                        val (cert, certInfo) = RpCertificateService.parseCertificatePem(req.certificatePem)
                        val x5c = listOf(Base64.getEncoder().encodeToString(cert.encoded))

                        val updated = rp.copy(
                            certificate = certInfo,
                            x5c = x5c,
                            updatedAt = java.time.Instant.now().toString()
                        )
                        store.save(updated)
                        call.respond(updated.toDetail())
                    } catch (e: Exception) {
                        call.respond(
                            HttpStatusCode.BadRequest,
                            mapOf("error" to "Invalid certificate PEM: ${e.message}")
                        )
                    }
                }

                get("certificate/download") {
                    val store = RelyingPartyStore.instanceOrNull()
                        ?: return@get call.respond(
                            HttpStatusCode.ServiceUnavailable,
                            mapOf("error" to "RP Registrar feature is not enabled")
                        )

                    val id = call.parameters["id"]!!
                    val rp = store.get(id)
                        ?: return@get call.respond(HttpStatusCode.NotFound, mapOf("error" to "RP not found: $id"))

                    if (rp.privateKeyJwk == null || rp.x5c == null) {
                        return@get call.respond(
                            HttpStatusCode.NotFound,
                            mapOf("error" to "No generated certificate for this RP. Use certificate/generate first.")
                        )
                    }

                    // Reconstruct cert from x5c
                    val certBytes = Base64.getDecoder().decode(rp.x5c.first())
                    val cf = java.security.cert.CertificateFactory.getInstance("X.509")
                    val cert = cf.generateCertificate(certBytes.inputStream()) as java.security.cert.X509Certificate

                    val response = buildJsonObject {
                        putJsonArray("x5c") { rp.x5c.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } }
                        put("privateKeyJwk", rp.privateKeyJwk!!)
                        put("certificate", Json.encodeToJsonElement(rp.certificate!!))
                    }
                    call.respond(response)
                }

                put("intended-use") {
                    val store = RelyingPartyStore.instanceOrNull()
                        ?: return@put call.respond(
                            HttpStatusCode.ServiceUnavailable,
                            mapOf("error" to "RP Registrar feature is not enabled")
                        )

                    val id = call.parameters["id"]!!
                    val rp = store.get(id)
                        ?: return@put call.respond(HttpStatusCode.NotFound, mapOf("error" to "RP not found: $id"))

                    val req = call.receive<IntendedUseRequest>()

                    if (req.intendedUse != null && req.intendedUse.length > 500) {
                        return@put call.respond(
                            HttpStatusCode.BadRequest,
                            mapOf("error" to "intendedUse must be 500 characters or less")
                        )
                    }

                    val updated = rp.copy(
                        intendedUse = req.intendedUse ?: rp.intendedUse,
                        dcqlQuery = req.dcqlQuery ?: rp.dcqlQuery,
                        updatedAt = java.time.Instant.now().toString()
                    )
                    store.save(updated)
                    call.respond(updated.toDetail())
                }
            }
        }
    }
}

// -- Extension functions --

private fun RelyingParty.toSummary() = RpSummary(
    id = id,
    legalName = legalName,
    domain = domain,
    country = country,
    status = status,
    hasCertificate = certificate != null,
    certificateExpiry = certificate?.notAfter,
    createdAt = createdAt
)

private fun RelyingParty.toDetail() = RpDetail(
    id = id,
    legalName = legalName,
    tradeName = tradeName,
    registrationNumber = registrationNumber,
    country = country,
    contactEmail = contactEmail,
    contactPhone = contactPhone,
    contactAddress = contactAddress,
    intendedUse = intendedUse,
    dcqlQuery = dcqlQuery,
    privacyPolicyUrl = privacyPolicyUrl,
    dataRetentionPeriod = dataRetentionPeriod,
    lawfulBasis = lawfulBasis,
    dpaAcknowledged = dpaAcknowledged,
    clientId = clientId,
    domain = domain,
    certificate = certificate,
    x5c = x5c,
    status = status,
    createdAt = createdAt,
    updatedAt = updatedAt
)
