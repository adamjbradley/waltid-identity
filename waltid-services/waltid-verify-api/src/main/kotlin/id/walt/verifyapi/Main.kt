package id.walt.verifyapi

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

fun main() {
    // ============================================================
    // CRITICAL: Feature flag check - service exits if not enabled
    // ============================================================
    val enabled = System.getenv("VERIFY_API_ENABLED")?.toBoolean() ?: false

    if (!enabled) {
        println("+" + "=".repeat(60) + "+")
        println("|  Verify API is DISABLED" + " ".repeat(36) + "|")
        println("|" + " ".repeat(60) + "|")
        println("|  To enable, set environment variable:" + " ".repeat(20) + "|")
        println("|    VERIFY_API_ENABLED=true" + " ".repeat(33) + "|")
        println("|" + " ".repeat(60) + "|")
        println("|  This service will NOT start until explicitly enabled." + " ".repeat(4) + "|")
        println("+" + "=".repeat(60) + "+")
        return  // Exit cleanly without binding port
    }

    println("+" + "=".repeat(60) + "+")
    println("|  Verify API is ENABLED" + " ".repeat(37) + "|")
    println("|  Starting server on port 7010..." + " ".repeat(26) + "|")
    println("+" + "=".repeat(60) + "+")

    embeddedServer(Netty, port = 7010) {
        routing {
            get("/health") {
                call.respondText("OK", ContentType.Text.Plain)
            }
            get("/") {
                call.respondText(
                    """
                    |Verify API - Feature flagged service
                    |Status: ENABLED
                    |Docs: /docs
                    """.trimMargin(),
                    ContentType.Text.Plain
                )
            }
        }
    }.start(wait = true)
}
