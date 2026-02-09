package id.walt.verifyapi.routes

import id.walt.verifyapi.auth.AUTH_PORTAL_JWT
import id.walt.verifyapi.db.VerifyOrganizations
import id.walt.verifyapi.portal.PortalUserPrincipal
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.http.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.time.Instant
import java.util.UUID

private val logger = KotlinLogging.logger {}

@Serializable
data class SetRpIdRequest(
    val rpId: String
)

@Serializable
data class OrgRpResponse(
    val organizationId: String,
    val rpId: String?
)

/**
 * Admin routes for managing organization-to-RP mappings.
 *
 * Links a Verify API organization to a registered RP in verifier-api2,
 * so verification sessions use the RP's own clientId and certificate
 * instead of the global verifier config.
 */
fun Route.adminOrgRoutes() {
    authenticate(AUTH_PORTAL_JWT) {
        route("/v1/admin/organizations/{orgId}/rp") {
            /**
             * GET /v1/admin/organizations/{orgId}/rp
             *
             * Get the registered RP ID for an organization.
             */
            get {
                val principal = call.principal<PortalUserPrincipal>()!!
                if (principal.role != "admin") {
                    call.respond(HttpStatusCode.Forbidden, mapOf("error" to "Admin role required"))
                    return@get
                }

                val orgId = call.parameters["orgId"]?.let {
                    runCatching { UUID.fromString(it) }.getOrNull()
                }

                if (orgId == null) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid organization ID format"))
                    return@get
                }

                val result = transaction {
                    VerifyOrganizations.selectAll()
                        .where { VerifyOrganizations.id eq orgId }
                        .singleOrNull()
                }

                if (result == null) {
                    call.respond(HttpStatusCode.NotFound, mapOf("error" to "Organization not found"))
                    return@get
                }

                call.respond(OrgRpResponse(
                    organizationId = orgId.toString(),
                    rpId = result[VerifyOrganizations.rpId]
                ))
            }

            /**
             * PUT /v1/admin/organizations/{orgId}/rp
             *
             * Set or update the registered RP ID for an organization.
             * This links the organization to a registered RP in verifier-api2's RP Registrar,
             * so verification sessions use the RP's own clientId and certificate.
             */
            put {
                val principal = call.principal<PortalUserPrincipal>()!!
                if (principal.role != "admin") {
                    call.respond(HttpStatusCode.Forbidden, mapOf("error" to "Admin role required"))
                    return@put
                }

                val orgId = call.parameters["orgId"]?.let {
                    runCatching { UUID.fromString(it) }.getOrNull()
                }

                if (orgId == null) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid organization ID format"))
                    return@put
                }

                val request = call.receive<SetRpIdRequest>()

                if (request.rpId.isBlank()) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "rpId must not be blank"))
                    return@put
                }

                val updated = transaction {
                    val exists = VerifyOrganizations.selectAll()
                        .where { VerifyOrganizations.id eq orgId }
                        .count() > 0

                    if (!exists) return@transaction false

                    VerifyOrganizations.update({ VerifyOrganizations.id eq orgId }) {
                        it[rpId] = request.rpId
                        it[updatedAt] = Instant.now()
                    }
                    true
                }

                if (!updated) {
                    call.respond(HttpStatusCode.NotFound, mapOf("error" to "Organization not found"))
                    return@put
                }

                logger.info { "Linked organization $orgId to RP ${request.rpId}" }
                call.respond(OrgRpResponse(
                    organizationId = orgId.toString(),
                    rpId = request.rpId
                ))
            }

            /**
             * DELETE /v1/admin/organizations/{orgId}/rp
             *
             * Remove the RP link for an organization, reverting to global verifier config.
             */
            delete {
                val principal = call.principal<PortalUserPrincipal>()!!
                if (principal.role != "admin") {
                    call.respond(HttpStatusCode.Forbidden, mapOf("error" to "Admin role required"))
                    return@delete
                }

                val orgId = call.parameters["orgId"]?.let {
                    runCatching { UUID.fromString(it) }.getOrNull()
                }

                if (orgId == null) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid organization ID format"))
                    return@delete
                }

                val updated = transaction {
                    val exists = VerifyOrganizations.selectAll()
                        .where { VerifyOrganizations.id eq orgId }
                        .count() > 0

                    if (!exists) return@transaction false

                    VerifyOrganizations.update({ VerifyOrganizations.id eq orgId }) {
                        it[rpId] = null
                        it[updatedAt] = Instant.now()
                    }
                    true
                }

                if (!updated) {
                    call.respond(HttpStatusCode.NotFound, mapOf("error" to "Organization not found"))
                    return@delete
                }

                logger.info { "Unlinked organization $orgId from RP (reverted to global config)" }
                call.respond(HttpStatusCode.NoContent)
            }
        }
    }
}
