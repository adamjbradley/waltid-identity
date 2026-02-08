package id.walt.verifyapi.auth

import id.walt.verifyapi.portal.PortalAuthService
import id.walt.verifyapi.portal.PortalUserPrincipal
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.jwt.jwt
import io.ktor.server.response.respond
import java.util.UUID

private val logger = KotlinLogging.logger {}

const val AUTH_API_KEY = "api-key"
const val AUTH_PORTAL_JWT = "portal-jwt"

fun Application.configureAuthentication() {
    install(Authentication) {
        apiKey(AUTH_API_KEY)

        jwt(AUTH_PORTAL_JWT) {
            realm = PortalAuthService.jwtConfig.realm

            verifier(
                com.auth0.jwt.JWT.require(
                    com.auth0.jwt.algorithms.Algorithm.HMAC256(PortalAuthService.jwtConfig.secret)
                )
                    .withIssuer(PortalAuthService.jwtConfig.issuer)
                    .withAudience(PortalAuthService.jwtConfig.audience)
                    .withClaim("type", "access")
                    .build()
            )

            validate { credential ->
                val userId = credential.payload.subject
                val email = credential.payload.getClaim("email").asString()
                val orgId = credential.payload.getClaim("org_id").asString()
                val orgName = credential.payload.getClaim("org_name").asString()
                val role = credential.payload.getClaim("role").asString()
                val tokenType = credential.payload.getClaim("type").asString()

                if (userId != null && email != null && orgId != null && orgName != null && role != null) {
                    try {
                        PortalUserPrincipal(
                            userId = UUID.fromString(userId),
                            email = email,
                            organizationId = UUID.fromString(orgId),
                            organizationName = orgName,
                            role = role,
                            tokenType = tokenType ?: "access"
                        )
                    } catch (e: Exception) {
                        logger.debug { "JWT validation failed: invalid UUID format" }
                        null
                    }
                } else {
                    logger.debug { "JWT validation failed: missing required claims" }
                    null
                }
            }

            challenge { _, _ ->
                call.respond(
                    HttpStatusCode.Unauthorized,
                    mapOf(
                        "error" to "invalid_token",
                        "error_description" to "Token is missing, invalid, or expired"
                    )
                )
            }
        }
    }

    logger.info { "Authentication configured with API key and JWT providers" }
}
