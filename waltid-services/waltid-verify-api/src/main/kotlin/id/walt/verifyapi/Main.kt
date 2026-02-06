package id.walt.verifyapi

import id.walt.verifyapi.db.configureDatabase
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.cio.*
import io.ktor.server.engine.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.cors.routing.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.json.Json

private val logger = KotlinLogging.logger {}

fun main() {
    // ============================================================
    // CRITICAL: Feature flag check - service exits if not enabled
    // ============================================================
    val enabled = System.getenv("VERIFY_API_ENABLED")?.toBoolean() ?: false

    if (!enabled) {
        logger.warn {
            """
            |
            |+============================================================+
            ||  Verify API is DISABLED                                    |
            ||                                                            |
            ||  To enable, set environment variable:                      |
            ||    VERIFY_API_ENABLED=true                                 |
            ||                                                            |
            ||  This service will NOT start until explicitly enabled.    |
            |+============================================================+
            """.trimMargin()
        }
        return  // Exit cleanly without binding port
    }

    logger.info { "Verify API is ENABLED. Starting server on port 7010..." }

    embeddedServer(CIO, port = 7010, module = Application::module).start(wait = true)
}

fun Application.module() {
    configureSerialization()
    configureCORS()
    configureStatusPages()
    configureDatabase()
    configureRouting()

    logger.info { "Verify API started successfully on port 7010" }
}

fun Application.configureSerialization() {
    install(ContentNegotiation) {
        json(Json {
            prettyPrint = true
            isLenient = true
            ignoreUnknownKeys = true
            encodeDefaults = true
        })
    }
}

fun Application.configureCORS() {
    install(CORS) {
        anyHost()
        allowHeader(HttpHeaders.ContentType)
        allowHeader(HttpHeaders.Authorization)
        allowHeader("X-API-Key")
        allowMethod(HttpMethod.Get)
        allowMethod(HttpMethod.Post)
        allowMethod(HttpMethod.Put)
        allowMethod(HttpMethod.Delete)
        allowMethod(HttpMethod.Patch)
        allowMethod(HttpMethod.Options)
    }
}

fun Application.configureStatusPages() {
    install(StatusPages) {
        exception<IllegalArgumentException> { call, cause ->
            logger.warn(cause) { "Bad request: ${cause.message}" }
            call.respondText(
                text = cause.message ?: "Bad Request",
                status = HttpStatusCode.BadRequest
            )
        }
        exception<IllegalStateException> { call, cause ->
            logger.warn(cause) { "Conflict: ${cause.message}" }
            call.respondText(
                text = cause.message ?: "Conflict",
                status = HttpStatusCode.Conflict
            )
        }
        exception<Throwable> { call, cause ->
            logger.error(cause) { "Unhandled exception" }
            call.respondText(
                text = "Internal Server Error",
                status = HttpStatusCode.InternalServerError
            )
        }
    }
}

fun Application.configureRouting() {
    routing {
        // Health check endpoint
        get("/health") {
            call.respondText("OK", ContentType.Text.Plain)
        }

        // Root endpoint with service info
        get("/") {
            call.respondText(
                """
                |Verify API - Multi-tenant Verification Gateway
                |Status: ENABLED
                |Version: 1.0.0-SNAPSHOT
                |Port: 7010
                |
                |Endpoints:
                |  /health - Health check
                |  /docs - API documentation (coming soon)
                |  /api/v1 - API endpoints (coming soon)
                """.trimMargin(),
                ContentType.Text.Plain
            )
        }

        // API version info
        get("/version") {
            call.respond(mapOf(
                "service" to "verify-api",
                "version" to "1.0.0-SNAPSHOT",
                "status" to "enabled"
            ))
        }
    }
}
