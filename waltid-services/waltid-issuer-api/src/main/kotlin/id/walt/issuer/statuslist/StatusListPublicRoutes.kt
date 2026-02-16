package id.walt.issuer.statuslist

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

fun Application.statusListPublicRoutes() {
    routing {
        get("/status-lists/{listId}") {
            val store = StatusListStore.instanceOrNull()
                ?: return@get call.respond(
                    HttpStatusCode.ServiceUnavailable,
                    mapOf("error" to "Status Lists feature is not enabled")
                )

            val listId = call.parameters["listId"]!!
            store.get(listId)
                ?: return@get call.respond(
                    HttpStatusCode.NotFound, mapOf("error" to "Status list not found: $listId")
                )

            val baseUrl = call.request.headers["X-Forwarded-Proto"]?.let { proto ->
                call.request.headers["X-Forwarded-Host"]?.let { host -> "$proto://$host" }
            } ?: call.request.local.let { "${it.scheme}://${it.serverHost}:${it.serverPort}" }

            val jwt = StatusListCredentialGenerator.generateCredentialJwt(listId, baseUrl)
                ?: return@get call.respond(
                    HttpStatusCode.InternalServerError,
                    mapOf("error" to "Failed to generate status list credential")
                )

            call.respondText(jwt, ContentType("application", "jwt"))
        }

        get("/status-lists/{listId}/token") {
            val store = StatusListStore.instanceOrNull()
                ?: return@get call.respond(
                    HttpStatusCode.ServiceUnavailable,
                    mapOf("error" to "Status Lists feature is not enabled")
                )

            val listId = call.parameters["listId"]!!
            store.get(listId)
                ?: return@get call.respond(
                    HttpStatusCode.NotFound, mapOf("error" to "Status list not found: $listId")
                )

            val baseUrl = call.request.headers["X-Forwarded-Proto"]?.let { proto ->
                call.request.headers["X-Forwarded-Host"]?.let { host -> "$proto://$host" }
            } ?: call.request.local.let { "${it.scheme}://${it.serverHost}:${it.serverPort}" }

            val jwt = StatusListCredentialGenerator.generateTokenStatusListJwt(listId, baseUrl)
                ?: return@get call.respond(
                    HttpStatusCode.InternalServerError,
                    mapOf("error" to "Failed to generate token status list")
                )

            call.response.header("Cache-Control", "max-age=300")
            call.respondText(jwt, ContentType("application", "statuslist+jwt"))
        }
    }
}
