package id.walt.openid4vp.verifier.rp

import id.walt.commons.config.ConfigManager
import id.walt.openid4vp.verifier.OSSVerifier2ServiceConfig
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.security.MessageDigest
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.*

fun Application.rpPublicRoutes() {
    routing {
        route("/.well-known") {
            get("/rp-certificates") {
                val pemCerts = mutableListOf<String>()
                val jsonEntries = mutableListOf<JsonObject>()

                // 1. Collect ACTIVE RP certificates (if RP Registrar is enabled)
                RelyingPartyStore.instanceOrNull()?.list()
                    ?.filter { it.status == RpStatus.ACTIVE && !it.x5c.isNullOrEmpty() }
                    ?.forEach { rp ->
                        val derBase64 = rp.x5c!!.first()
                        pemCerts.add(derToPem(derBase64))
                        val certInfo = rp.certificate
                        if (certInfo != null) {
                            jsonEntries.add(buildJsonObject {
                                put("source", "rp")
                                put("rpId", rp.id)
                                put("domain", rp.domain)
                                put("subject", certInfo.subject)
                                put("issuer", certInfo.issuer)
                                put("notBefore", certInfo.notBefore)
                                put("notAfter", certInfo.notAfter)
                                put("fingerprint", certInfo.fingerprint)
                            })
                        }
                    }

                // 2. Include verifier's own certificate from config
                val config = ConfigManager.getConfig<OSSVerifier2ServiceConfig>()
                config.x5c?.forEach { derBase64 ->
                    pemCerts.add(derToPem(derBase64))
                    try {
                        val cert = decodeDerCert(derBase64)
                        val info = RpCertificateService.extractCertInfo(cert)
                        jsonEntries.add(buildJsonObject {
                            put("source", "verifier")
                            put("subject", info.subject)
                            put("issuer", info.issuer)
                            put("notBefore", info.notBefore)
                            put("notAfter", info.notAfter)
                            put("fingerprint", info.fingerprint)
                        })
                    } catch (_: Exception) {
                        // If cert can't be parsed for metadata, still include PEM
                    }
                }

                if (pemCerts.isEmpty()) {
                    call.respond(HttpStatusCode.OK, "")
                    return@get
                }

                // ETag based on content hash
                val pemBundle = pemCerts.joinToString("\n")
                val etag = computeEtag(pemBundle)

                val ifNoneMatch = call.request.header(HttpHeaders.IfNoneMatch)
                if (ifNoneMatch == etag) {
                    call.response.header(HttpHeaders.ETag, etag)
                    call.respond(HttpStatusCode.NotModified, "")
                    return@get
                }

                call.response.header(HttpHeaders.ETag, etag)

                // Content negotiation: JSON or PEM
                val acceptJson = call.request.acceptItems().any {
                    it.value == ContentType.Application.Json.toString()
                }

                if (acceptJson) {
                    call.respond(buildJsonObject {
                        put("certificates", buildJsonArray { jsonEntries.forEach { add(it) } })
                        put("count", jsonEntries.size)
                        put("etag", etag)
                    })
                } else {
                    call.respondText(pemBundle, ContentType("application", "x-pem-file"))
                }
            }
        }
    }
}

private fun derToPem(derBase64: String): String {
    val lines = derBase64.chunked(64).joinToString("\n")
    return "-----BEGIN CERTIFICATE-----\n$lines\n-----END CERTIFICATE-----"
}

private fun decodeDerCert(derBase64: String): X509Certificate {
    val bytes = Base64.getDecoder().decode(derBase64)
    val cf = CertificateFactory.getInstance("X.509")
    return cf.generateCertificate(bytes.inputStream()) as X509Certificate
}

private fun computeEtag(content: String): String {
    val md = MessageDigest.getInstance("SHA-256")
    val hash = md.digest(content.toByteArray())
    return "\"${hash.take(16).joinToString("") { "%02x".format(it) }}\""
}
