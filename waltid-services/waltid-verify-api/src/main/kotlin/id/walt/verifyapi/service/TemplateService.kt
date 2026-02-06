package id.walt.verifyapi.service

import id.walt.verifyapi.db.VerifyTemplates
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.util.UUID

private val logger = KotlinLogging.logger {}

/**
 * Data class representing a verification template.
 */
data class Template(
    val id: UUID,
    val name: String,
    val displayName: String?,
    val description: String?,
    val templateType: String,
    val dcqlQuery: JsonObject,
    val responseMode: String,
    val claimMappings: JsonObject?,
    val validCredentialTypes: List<String>?
)

/**
 * Service for looking up verification templates.
 */
object TemplateService {

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Finds a template by name for a given organization.
     * First checks organization-specific templates, then falls back to system templates.
     *
     * @param organizationId The organization ID
     * @param templateName The template name to look up
     * @return The template if found, null otherwise
     */
    fun findTemplate(organizationId: UUID, templateName: String): Template? {
        return transaction {
            // First try org-specific template, then fall back to system template (null org)
            val row = VerifyTemplates.selectAll()
                .where {
                    (VerifyTemplates.name eq templateName) and
                    ((VerifyTemplates.organizationId eq organizationId) or (VerifyTemplates.organizationId eq null))
                }
                .orderBy(VerifyTemplates.organizationId)  // Org-specific first (non-null)
                .firstOrNull()

            if (row == null) {
                logger.warn { "Template not found: $templateName for org: $organizationId" }
                return@transaction null
            }

            val dcqlQueryJson = json.parseToJsonElement(row[VerifyTemplates.dcqlQuery]).let {
                it as? JsonObject ?: run {
                    logger.error { "Invalid DCQL query JSON in template: $templateName" }
                    return@transaction null
                }
            }

            val claimMappingsJson = row[VerifyTemplates.claimMappings]?.let {
                json.parseToJsonElement(it) as? JsonObject
            }

            val validTypes = row[VerifyTemplates.validCredentialTypes]?.let {
                json.parseToJsonElement(it).let { arr ->
                    if (arr is kotlinx.serialization.json.JsonArray) {
                        arr.map { elem -> elem.toString().trim('"') }
                    } else null
                }
            }

            Template(
                id = row[VerifyTemplates.id].value,
                name = row[VerifyTemplates.name],
                displayName = row[VerifyTemplates.displayName],
                description = row[VerifyTemplates.description],
                templateType = row[VerifyTemplates.templateType],
                dcqlQuery = dcqlQueryJson,
                responseMode = row[VerifyTemplates.responseMode],
                claimMappings = claimMappingsJson,
                validCredentialTypes = validTypes
            )
        }
    }

    /**
     * Lists all templates available to an organization (includes system templates).
     */
    fun listTemplates(organizationId: UUID): List<Template> {
        return transaction {
            VerifyTemplates.selectAll()
                .where {
                    (VerifyTemplates.organizationId eq organizationId) or (VerifyTemplates.organizationId eq null)
                }
                .mapNotNull { row ->
                    try {
                        val dcqlQueryJson = json.parseToJsonElement(row[VerifyTemplates.dcqlQuery]) as JsonObject

                        val claimMappingsJson = row[VerifyTemplates.claimMappings]?.let {
                            json.parseToJsonElement(it) as? JsonObject
                        }

                        val validTypes = row[VerifyTemplates.validCredentialTypes]?.let {
                            val arr = json.parseToJsonElement(it)
                            if (arr is kotlinx.serialization.json.JsonArray) {
                                arr.map { elem -> elem.toString().trim('"') }
                            } else null
                        }

                        Template(
                            id = row[VerifyTemplates.id].value,
                            name = row[VerifyTemplates.name],
                            displayName = row[VerifyTemplates.displayName],
                            description = row[VerifyTemplates.description],
                            templateType = row[VerifyTemplates.templateType],
                            dcqlQuery = dcqlQueryJson,
                            responseMode = row[VerifyTemplates.responseMode],
                            claimMappings = claimMappingsJson,
                            validCredentialTypes = validTypes
                        )
                    } catch (e: Exception) {
                        logger.error(e) { "Failed to parse template: ${row[VerifyTemplates.name]}" }
                        null
                    }
                }
        }
    }
}
