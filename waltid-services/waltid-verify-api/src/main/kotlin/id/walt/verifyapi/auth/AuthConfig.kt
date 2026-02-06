package id.walt.verifyapi.auth

import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.server.application.*
import io.ktor.server.auth.*

private val logger = KotlinLogging.logger {}

/**
 * Configures authentication for the Verify API.
 * Registers the API key authentication provider.
 */
fun Application.configureAuthentication() {
    install(Authentication) {
        apiKey("api-key")
    }

    logger.info { "Authentication configured with API key provider" }
}
