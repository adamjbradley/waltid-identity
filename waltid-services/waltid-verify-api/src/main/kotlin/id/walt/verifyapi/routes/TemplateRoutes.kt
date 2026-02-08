package id.walt.verifyapi.routes

import id.walt.verifyapi.auth.ApiKeyPrincipal
import id.walt.verifyapi.db.VerifyTemplates
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.http.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.time.Instant

private val logger = KotlinLogging.logger {}

/**
 * Response DTO for template listing.
 */
@Serializable
data class TemplateResponse(
    val name: String,
    val displayName: String?,
    val type: String,
    val description: String?,
    val isSystem: Boolean
)

/**
 * Request DTO for creating a custom template.
 */
@Serializable
data class CreateTemplateRequest(
    val name: String,
    val displayName: String? = null,
    val type: String = "identity",
    val description: String? = null,
    val claims: List<ClaimDefinition>,
    val validCredentials: List<String>? = null
)

/**
 * Defines a claim to be requested in the verification.
 */
@Serializable
data class ClaimDefinition(
    /** Path to the claim in the credential (e.g., ["family_name"] or ["org.iso.18013.5.1", "family_name"]) */
    val path: List<String>,
    /** Whether this claim is required (default true) */
    val required: Boolean = true,
    /** Optional alias for the claim in the response */
    val alias: String? = null
)

/**
 * Configure template management routes under /v1/templates.
 *
 * Provides endpoints for listing available templates and creating custom templates.
 * System templates (organizationId = null) are available to all organizations.
 * Custom templates are scoped to the authenticated organization.
 */
fun Route.templateRoutes() {
    authenticate("api-key") {
        route("/v1/templates") {
            /**
             * GET /v1/templates
             *
             * List all available templates for the authenticated organization.
             * Returns both system templates and organization-specific templates.
             */
            get {
                val principal = call.principal<ApiKeyPrincipal>()!!
                logger.debug { "Listing templates for organization: ${principal.organizationId}" }

                val templates = transaction {
                    VerifyTemplates.selectAll()
                        .where {
                            (VerifyTemplates.organizationId eq null) or
                            (VerifyTemplates.organizationId eq principal.organizationId)
                        }
                        .map { row ->
                            TemplateResponse(
                                name = row[VerifyTemplates.name],
                                displayName = row[VerifyTemplates.displayName],
                                type = row[VerifyTemplates.templateType],
                                description = row[VerifyTemplates.description],
                                isSystem = row[VerifyTemplates.organizationId] == null
                            )
                        }
                }

                call.respond(templates)
            }

            /**
             * POST /v1/templates
             *
             * Create a custom verification template for the authenticated organization.
             * Template names must be lowercase, start with a letter, and contain only
             * letters, numbers, and underscores.
             */
            post {
                val principal = call.principal<ApiKeyPrincipal>()!!
                val request = call.receive<CreateTemplateRequest>()

                logger.debug { "Creating template '${request.name}' for organization: ${principal.organizationId}" }

                // Validate template name format
                if (!request.name.matches(Regex("^[a-z][a-z0-9_]*$"))) {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        mapOf("error" to "Template name must be lowercase, start with letter, contain only letters/numbers/underscores")
                    )
                    return@post
                }

                // Validate claims
                if (request.claims.isEmpty()) {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        mapOf("error" to "At least one claim must be defined")
                    )
                    return@post
                }

                for (claim in request.claims) {
                    if (claim.path.isEmpty()) {
                        call.respond(
                            HttpStatusCode.BadRequest,
                            mapOf("error" to "Claim path cannot be empty")
                        )
                        return@post
                    }
                }

                // Validate template type
                val validTypes = listOf("identity", "payment", "custom")
                if (request.type !in validTypes) {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        mapOf("error" to "Template type must be one of: ${validTypes.joinToString(", ")}")
                    )
                    return@post
                }

                // Build DCQL query from claims
                val dcqlQuery = buildDcqlQuery(request.claims, request.validCredentials)
                val claimMappings = buildClaimMappings(request.claims)
                val validCredentialTypes = request.validCredentials?.let {
                    "[${it.joinToString(",") { v -> "\"$v\"" }}]"
                }

                try {
                    transaction {
                        // Check if template with same name already exists for this org
                        val existing = VerifyTemplates.selectAll()
                            .where {
                                (VerifyTemplates.organizationId eq principal.organizationId) and
                                (VerifyTemplates.name eq request.name)
                            }
                            .count()

                        if (existing > 0) {
                            throw IllegalStateException("Template with name '${request.name}' already exists")
                        }

                        val now = Instant.now()
                        VerifyTemplates.insert {
                            it[organizationId] = principal.organizationId
                            it[name] = request.name
                            it[displayName] = request.displayName
                            it[description] = request.description
                            it[templateType] = request.type
                            it[this.dcqlQuery] = dcqlQuery
                            it[responseMode] = "answers"
                            it[this.claimMappings] = claimMappings
                            it[this.validCredentialTypes] = validCredentialTypes
                            it[createdAt] = now
                            it[updatedAt] = now
                        }
                    }

                    logger.info { "Created template '${request.name}' for organization: ${principal.organizationId}" }

                    call.respond(
                        HttpStatusCode.Created,
                        mapOf(
                            "name" to request.name,
                            "message" to "Template created successfully"
                        )
                    )
                } catch (e: IllegalStateException) {
                    call.respond(
                        HttpStatusCode.Conflict,
                        mapOf("error" to (e.message ?: "Template already exists"))
                    )
                }
            }

            /**
             * GET /v1/templates/{name}
             *
             * Get a specific template by name.
             * Returns the template if it's a system template or belongs to the authenticated organization.
             */
            get("/{name}") {
                val principal = call.principal<ApiKeyPrincipal>()!!
                val templateName = call.parameters["name"]!!

                val template = transaction {
                    VerifyTemplates.selectAll()
                        .where {
                            (VerifyTemplates.name eq templateName) and
                            ((VerifyTemplates.organizationId eq null) or
                             (VerifyTemplates.organizationId eq principal.organizationId))
                        }
                        .singleOrNull()
                        ?.let { row ->
                            TemplateResponse(
                                name = row[VerifyTemplates.name],
                                displayName = row[VerifyTemplates.displayName],
                                type = row[VerifyTemplates.templateType],
                                description = row[VerifyTemplates.description],
                                isSystem = row[VerifyTemplates.organizationId] == null
                            )
                        }
                }

                if (template == null) {
                    call.respond(
                        HttpStatusCode.NotFound,
                        mapOf("error" to "Template not found")
                    )
                    return@get
                }

                call.respond(template)
            }

            /**
             * DELETE /v1/templates/{name}
             *
             * Delete a custom template.
             * System templates cannot be deleted.
             */
            delete("/{name}") {
                val principal = call.principal<ApiKeyPrincipal>()!!
                val templateName = call.parameters["name"]!!

                val deleted = transaction {
                    val template = VerifyTemplates.selectAll()
                        .where {
                            (VerifyTemplates.name eq templateName) and
                            (VerifyTemplates.organizationId eq principal.organizationId)
                        }
                        .singleOrNull()

                    if (template == null) {
                        // Check if it's a system template
                        val isSystem = VerifyTemplates.selectAll()
                            .where {
                                (VerifyTemplates.name eq templateName) and
                                (VerifyTemplates.organizationId eq null)
                            }
                            .count() > 0

                        if (isSystem) {
                            return@transaction "system"
                        }
                        return@transaction "not_found"
                    }

                    VerifyTemplates.deleteWhere {
                        (VerifyTemplates.name eq templateName) and
                        (VerifyTemplates.organizationId eq principal.organizationId)
                    }
                    "deleted"
                }

                when (deleted) {
                    "system" -> call.respond(
                        HttpStatusCode.Forbidden,
                        mapOf("error" to "System templates cannot be deleted")
                    )
                    "not_found" -> call.respond(
                        HttpStatusCode.NotFound,
                        mapOf("error" to "Template not found")
                    )
                    else -> {
                        logger.info { "Deleted template '$templateName' for organization: ${principal.organizationId}" }
                        call.respond(HttpStatusCode.NoContent)
                    }
                }
            }
        }
    }
}

/**
 * Build a DCQL query JSON string from the claim definitions.
 */
private fun buildDcqlQuery(claims: List<ClaimDefinition>, validCredentials: List<String>?): String {
    val claimsJson = claims.joinToString(",") { claim ->
        val pathJson = claim.path.joinToString(",") { "\"$it\"" }
        """{"path":[$pathJson]}"""
    }

    // Default to EUDI PID if no credentials specified
    val vctValues = validCredentials?.joinToString(",") { "\"$it\"" } ?: "\"urn:eudi:pid:1\""

    return """{"credentials":[{"id":"cred","format":"dc+sd-jwt","meta":{"vct_values":[$vctValues]},"claims":[$claimsJson]}]}"""
}

/**
 * Build claim mappings JSON string from claim definitions with aliases.
 */
private fun buildClaimMappings(claims: List<ClaimDefinition>): String {
    val mappings = claims.mapNotNull { claim ->
        val path = claim.path.joinToString(".")
        val alias = claim.alias ?: claim.path.last()
        "\"$path\":\"$alias\""
    }
    return "{${mappings.joinToString(",")}}"
}
