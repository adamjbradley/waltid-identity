@file:OptIn(ExperimentalTime::class)

package id.walt.verifyapi.db

import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.dao.id.UUIDTable
import org.jetbrains.exposed.v1.javatime.timestamp
import kotlin.time.ExperimentalTime

/**
 * Organizations (tenants) table.
 * Each organization has its own API keys, webhooks, templates, and orchestrations.
 */
object VerifyOrganizations : UUIDTable("verify_organizations") {
    val name = varchar("name", 255)
    val billingEmail = varchar("billing_email", 255)
    val plan = varchar("plan", 50).default("free")
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")
}

/**
 * API Keys for authentication.
 * Keys are stored as SHA-256 hashes with a prefix for identification.
 * Format: vfy_{env}_{random} where env is 'live' or 'test'
 */
object VerifyApiKeys : UUIDTable("verify_api_keys") {
    val organizationId = reference(
        "organization_id",
        VerifyOrganizations,
        onDelete = ReferenceOption.CASCADE
    )
    val keyHash = varchar("key_hash", 64)  // SHA-256 hash
    val keyPrefix = varchar("key_prefix", 16)  // e.g., "vfy_live_abc123"
    val environment = varchar("environment", 10)  // "live" or "test"
    val name = varchar("name", 100).nullable()  // Optional friendly name
    val lastUsedAt = timestamp("last_used_at").nullable()
    val createdAt = timestamp("created_at")
    val revokedAt = timestamp("revoked_at").nullable()

    init {
        index(isUnique = false, organizationId)
        index(isUnique = true, keyPrefix)
    }
}

/**
 * Webhooks for event notifications.
 * Each webhook can subscribe to specific events.
 */
object VerifyWebhooks : UUIDTable("verify_webhooks") {
    val organizationId = reference(
        "organization_id",
        VerifyOrganizations,
        onDelete = ReferenceOption.CASCADE
    )
    val url = varchar("url", 2048)
    val secret = varchar("secret", 64)  // For HMAC signature verification
    val events = text("events")  // JSON array of event types
    val enabled = bool("enabled").default(true)
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")

    init {
        index(isUnique = false, organizationId)
    }
}

/**
 * Verification templates.
 * System templates have null organizationId, org-specific templates reference their org.
 * Templates define what credentials and claims to request.
 */
object VerifyTemplates : UUIDTable("verify_templates") {
    val organizationId = reference(
        "organization_id",
        VerifyOrganizations,
        onDelete = ReferenceOption.CASCADE
    ).nullable()  // null = system template
    val name = varchar("name", 100)
    val displayName = varchar("display_name", 255).nullable()
    val description = text("description").nullable()
    val templateType = varchar("template_type", 20)  // "identity", "payment", "custom"
    val dcqlQuery = text("dcql_query")  // DCQL query JSON
    val responseMode = varchar("response_mode", 20).default("answers")  // "full", "answers", "minimal"
    val claimMappings = text("claim_mappings").nullable()  // JSON object for claim renaming
    val validCredentialTypes = text("valid_credential_types").nullable()  // JSON array
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")

    init {
        // Unique constraint: system templates by name (org null), or org+name combo
        uniqueIndex(organizationId, name)
    }
}

/**
 * Orchestrations for multi-step verification flows.
 * Allows chaining multiple verification templates with conditional logic.
 */
object VerifyOrchestrations : UUIDTable("verify_orchestrations") {
    val organizationId = reference(
        "organization_id",
        VerifyOrganizations,
        onDelete = ReferenceOption.CASCADE
    )
    val name = varchar("name", 100)
    val description = text("description").nullable()
    val steps = text("steps")  // JSON array of orchestration steps
    val onComplete = text("on_complete").nullable()  // JSON object with completion actions
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")

    init {
        uniqueIndex(organizationId, name)
    }
}

/**
 * Verification sessions to track verification requests.
 * Links to the verifier-api2 session for actual OID4VP processing.
 */
object VerifySessions : UUIDTable("verify_sessions") {
    val organizationId = reference(
        "organization_id",
        VerifyOrganizations,
        onDelete = ReferenceOption.CASCADE
    )
    val templateId = reference(
        "template_id",
        VerifyTemplates,
        onDelete = ReferenceOption.SET_NULL
    ).nullable()
    val orchestrationId = reference(
        "orchestration_id",
        VerifyOrchestrations,
        onDelete = ReferenceOption.SET_NULL
    ).nullable()
    val externalSessionId = varchar("external_session_id", 100).nullable()  // verifier-api2 session ID
    val status = varchar("status", 20)  // "pending", "in_progress", "completed", "failed", "expired"
    val environment = varchar("environment", 10)  // "live" or "test"
    val metadata = text("metadata").nullable()  // JSON object for custom metadata
    val result = text("result").nullable()  // JSON object with verification result
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")
    val expiresAt = timestamp("expires_at")

    init {
        index(isUnique = false, organizationId)
        index(isUnique = false, status)
        index(isUnique = true, externalSessionId)
    }
}
