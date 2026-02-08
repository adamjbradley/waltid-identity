package id.walt.openid4vp.verifier.trust

import id.walt.commons.trust.TrustListServiceFactory
import id.walt.trust.TrustServiceStatus
import id.walt.trust.TrustSource
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

fun Application.trustAdminRoutes() {
    routing {
        route("/admin/trust") {
            get("/status") {
                val service = TrustListServiceFactory.getServiceOrNull()
                    ?: return@get call.respond(
                        HttpStatusCode.ServiceUnavailable,
                        mapOf("error" to "Trust lists feature is not enabled")
                    )
                val status = service.getStatus()
                call.respond(Json.encodeToJsonElement(TrustServiceStatus.serializer(), status))
            }

            put("/etsi") {
                val body = call.receive<ToggleRequest>()
                val service = TrustListServiceFactory.getServiceOrNull()
                    ?: return@put call.respond(
                        HttpStatusCode.ServiceUnavailable,
                        mapOf("error" to "Trust lists feature is not enabled")
                    )
                service.setEnabled(TrustSource.ETSI_TL, body.enabled)
                call.respond(HttpStatusCode.OK, mapOf("source" to "etsi_tl", "enabled" to body.enabled.toString()))
            }

            put("/federation") {
                val body = call.receive<ToggleRequest>()
                val service = TrustListServiceFactory.getServiceOrNull()
                    ?: return@put call.respond(
                        HttpStatusCode.ServiceUnavailable,
                        mapOf("error" to "Trust lists feature is not enabled")
                    )
                service.setEnabled(TrustSource.OPENID_FEDERATION, body.enabled)
                call.respond(
                    HttpStatusCode.OK,
                    mapOf("source" to "openid_federation", "enabled" to body.enabled.toString())
                )
            }

            post("/refresh") {
                val service = TrustListServiceFactory.getServiceOrNull()
                    ?: return@post call.respond(
                        HttpStatusCode.ServiceUnavailable,
                        mapOf("error" to "Trust lists feature is not enabled")
                    )
                // Re-fetch trust lists
                val status = service.getStatus()
                call.respond(Json.encodeToJsonElement(TrustServiceStatus.serializer(), status))
            }
        }
    }
}

@Serializable
data class ToggleRequest(val enabled: Boolean)
