@file:OptIn(ExperimentalTime::class)

package id.walt.verifyapi.db

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.server.application.*
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.time.Instant
import kotlin.time.ExperimentalTime

private val logger = KotlinLogging.logger {}

/**
 * Database configuration for the Verify API.
 * Uses HikariCP for connection pooling and PostgreSQL as the database.
 */
fun Application.configureDatabase() {
    val dbUrl = environment.config.propertyOrNull("database.url")?.getString()
        ?: System.getenv("VERIFY_DATABASE_URL")
        ?: System.getenv("DATABASE_URL")
        ?: "jdbc:postgresql://localhost:5432/waltid"

    val dbUser = environment.config.propertyOrNull("database.user")?.getString()
        ?: System.getenv("VERIFY_DATABASE_USER")
        ?: System.getenv("DATABASE_USER")
        ?: "postgres"

    val dbPassword = environment.config.propertyOrNull("database.password")?.getString()
        ?: System.getenv("VERIFY_DATABASE_PASSWORD")
        ?: System.getenv("DATABASE_PASSWORD")
        ?: "postgres"

    logger.info { "Connecting to database: $dbUrl" }

    val config = HikariConfig().apply {
        jdbcUrl = dbUrl
        username = dbUser
        password = dbPassword
        driverClassName = "org.postgresql.Driver"
        maximumPoolSize = 10
        minimumIdle = 2
        idleTimeout = 60000
        connectionTimeout = 30000
        maxLifetime = 1800000
        poolName = "verify-api-pool"

        // Performance tuning
        addDataSourceProperty("cachePrepStmts", "true")
        addDataSourceProperty("prepStmtCacheSize", "250")
        addDataSourceProperty("prepStmtCacheSqlLimit", "2048")
    }

    val dataSource = HikariDataSource(config)
    Database.connect(dataSource)

    // Create tables if they don't exist
    transaction {
        SchemaUtils.createMissingTablesAndColumns(
            VerifyOrganizations,
            VerifyUsers,
            VerifyApiKeys,
            VerifyWebhooks,
            VerifyTemplates,
            VerifyOrchestrations,
            VerifySessions,
            VerifyWidgetTokens,
            VerifyUsageEvents
        )
    }

    // Seed system templates
    seedSystemTemplates()

    // Seed sandbox organization for demo/testing
    seedSandboxOrganization()

    logger.info { "Database configured successfully" }
}

/**
 * Seeds the database with system-level verification templates.
 * These templates are available to all organizations (organizationId = null).
 * Uses per-template upsert so new templates are added to existing databases
 * without duplicating ones that already exist.
 */
private fun seedSystemTemplates() {
    data class SystemTemplate(
        val name: String,
        val displayName: String,
        val description: String,
        val templateType: String,
        val dcqlQuery: String,
        val claimMappings: String,
        val validCredentialTypes: String,
    )

    val templates = listOf(
        SystemTemplate(
            name = "age_check",
            displayName = "Age Verification",
            description = "Verify user is over 18 years old",
            templateType = "identity",
            // See note on `age_over_18` below: one credential query per VCT + credential_sets
            // so iOS EudiWalletKit's CredentialQuery.docType (only reads the first vct_value)
            // doesn't silently exclude all but the first VCT.
            dcqlQuery = """{"credentials":[{"id":"eudi_pid","format":"dc+sd-jwt","meta":{"vct_values":["urn:eudi:pid:1"]},"claims":[{"path":["age_over_18"]}]},{"id":"au_mygovid","format":"dc+sd-jwt","meta":{"vct_values":["urn:au:gov:mygovid:pid:1"]},"claims":[{"path":["age_over_18"]}]},{"id":"in_dl","format":"dc+sd-jwt","meta":{"vct_values":["urn:in:gov:dl:1"]},"claims":[{"path":["age_over_18"]}]}],"credential_sets":[{"options":[["eudi_pid"],["au_mygovid"],["in_dl"]]}]}""",
            claimMappings = """{"age_over_18":"is_adult"}""",
            validCredentialTypes = """["urn:eudi:pid:1","urn:au:gov:mygovid:pid:1","urn:in:gov:dl:1"]""",
        ),
        SystemTemplate(
            name = "age_over_18",
            displayName = "Age 18+ Verification",
            description = "Verify user is 18 years or older",
            templateType = "identity",
            // Use credential_sets so iOS EudiWalletKit can match any of the listed VCTs.
            // EudiWalletKit's CredentialQuery.docType only inspects the FIRST entry of
            // meta.vct_values (Openid4VpUtils.swift:155), so a single credential query
            // with multiple vct_values silently excludes all but the first. One query
            // per VCT, joined with options, gives wallets a spec-standard "any of"
            // semantics that both iOS and Android resolve correctly.
            dcqlQuery = """{"credentials":[{"id":"eudi_pid","format":"dc+sd-jwt","meta":{"vct_values":["urn:eudi:pid:1"]},"claims":[{"path":["age_over_18"]}]},{"id":"au_mygovid","format":"dc+sd-jwt","meta":{"vct_values":["urn:au:gov:mygovid:pid:1"]},"claims":[{"path":["age_over_18"]}]},{"id":"in_dl","format":"dc+sd-jwt","meta":{"vct_values":["urn:in:gov:dl:1"]},"claims":[{"path":["age_over_18"]}]}],"credential_sets":[{"options":[["eudi_pid"],["au_mygovid"],["in_dl"]]}]}""",
            claimMappings = """{"age_over_18":"is_adult"}""",
            validCredentialTypes = """["urn:eudi:pid:1","urn:au:gov:mygovid:pid:1","urn:in:gov:dl:1"]""",
        ),
        SystemTemplate(
            name = "age_over_21",
            displayName = "Age 21+ Verification",
            description = "Verify user is 21 years or older",
            templateType = "identity",
            // One credential query per VCT + credential_sets so any country-specific
            // PID the wallet holds satisfies the request.
            dcqlQuery = """{"credentials":[{"id":"eudi_pid","format":"dc+sd-jwt","meta":{"vct_values":["urn:eudi:pid:1"]},"claims":[{"path":["age_over_21"]}]},{"id":"au_mygovid","format":"dc+sd-jwt","meta":{"vct_values":["urn:au:gov:mygovid:pid:1"]},"claims":[{"path":["age_over_21"]}]},{"id":"in_dl","format":"dc+sd-jwt","meta":{"vct_values":["urn:in:gov:dl:1"]},"claims":[{"path":["age_over_21"]}]}],"credential_sets":[{"options":[["eudi_pid"],["au_mygovid"],["in_dl"]]}]}""",
            claimMappings = """{"age_over_21":"is_adult"}""",
            validCredentialTypes = """["urn:eudi:pid:1","urn:au:gov:mygovid:pid:1","urn:in:gov:dl:1"]""",
        ),
        SystemTemplate(
            name = "kyc_basic",
            displayName = "Basic KYC",
            description = "Basic identity verification with name and date of birth",
            templateType = "identity",
            dcqlQuery = """{"credentials":[{"id":"eudi_pid","format":"dc+sd-jwt","meta":{"vct_values":["urn:eudi:pid:1"]},"claims":[{"path":["family_name"]},{"path":["given_name"]},{"path":["birth_date"]}]},{"id":"au_mygovid","format":"dc+sd-jwt","meta":{"vct_values":["urn:au:gov:mygovid:pid:1"]},"claims":[{"path":["family_name"]},{"path":["given_name"]},{"path":["birth_date"]}]},{"id":"in_dl","format":"dc+sd-jwt","meta":{"vct_values":["urn:in:gov:dl:1"]},"claims":[{"path":["family_name"]},{"path":["given_name"]},{"path":["birth_date"]}]}],"credential_sets":[{"options":[["eudi_pid"],["au_mygovid"],["in_dl"]]}]}""",
            claimMappings = """{"family_name":"last_name","given_name":"first_name","birth_date":"date_of_birth"}""",
            validCredentialTypes = """["urn:eudi:pid:1","urn:au:gov:mygovid:pid:1","urn:in:gov:dl:1"]""",
        ),
        SystemTemplate(
            name = "full_kyc",
            displayName = "Full KYC",
            description = "Complete identity verification with name, birth date, and nationality",
            templateType = "identity",
            // EUDI PID option also requests `nationality`; AU/IN variants request only the
            // base three claims since those PID types don't carry a nationality claim.
            dcqlQuery = """{"credentials":[{"id":"eudi_pid","format":"dc+sd-jwt","meta":{"vct_values":["urn:eudi:pid:1"]},"claims":[{"path":["family_name"]},{"path":["given_name"]},{"path":["birth_date"]},{"path":["nationality"]}]},{"id":"au_mygovid","format":"dc+sd-jwt","meta":{"vct_values":["urn:au:gov:mygovid:pid:1"]},"claims":[{"path":["family_name"]},{"path":["given_name"]},{"path":["birth_date"]}]},{"id":"in_dl","format":"dc+sd-jwt","meta":{"vct_values":["urn:in:gov:dl:1"]},"claims":[{"path":["family_name"]},{"path":["given_name"]},{"path":["birth_date"]}]}],"credential_sets":[{"options":[["eudi_pid"],["au_mygovid"],["in_dl"]]}]}""",
            claimMappings = """{"family_name":"last_name","given_name":"first_name","birth_date":"date_of_birth","nationality":"nationality"}""",
            validCredentialTypes = """["urn:eudi:pid:1","urn:au:gov:mygovid:pid:1","urn:in:gov:dl:1"]""",
        ),
        SystemTemplate(
            name = "transaction_binding",
            displayName = "Payment Authorization",
            description = "Verify payment wallet attestation for transaction binding",
            templateType = "payment",
            dcqlQuery = """{"credentials":[{"id":"pwa","format":"dc+sd-jwt","meta":{"vct_values":["PaymentWalletAttestation"]},"claims":[{"path":["funding_source"]},{"path":["funding_source","type"]},{"path":["funding_source","panLastFour"]}]}]}""",
            claimMappings = """{"funding_source.type":"payment_method","funding_source.panLastFour":"card_last_four"}""",
            validCredentialTypes = """["PaymentWalletAttestation"]""",
        ),
        SystemTemplate(
            name = "basic_identity",
            displayName = "Basic Identity",
            description = "Verify basic identity with name only",
            templateType = "identity",
            dcqlQuery = """{"credentials":[{"id":"eudi_pid","format":"dc+sd-jwt","meta":{"vct_values":["urn:eudi:pid:1"]},"claims":[{"path":["family_name"]},{"path":["given_name"]}]},{"id":"au_mygovid","format":"dc+sd-jwt","meta":{"vct_values":["urn:au:gov:mygovid:pid:1"]},"claims":[{"path":["family_name"]},{"path":["given_name"]}]},{"id":"in_dl","format":"dc+sd-jwt","meta":{"vct_values":["urn:in:gov:dl:1"]},"claims":[{"path":["family_name"]},{"path":["given_name"]}]}],"credential_sets":[{"options":[["eudi_pid"],["au_mygovid"],["in_dl"]]}]}""",
            claimMappings = """{"family_name":"last_name","given_name":"first_name"}""",
            validCredentialTypes = """["urn:eudi:pid:1","urn:au:gov:mygovid:pid:1","urn:in:gov:dl:1"]""",
        ),
        SystemTemplate(
            name = "mdl_verification",
            displayName = "Driving License",
            description = "Verify mobile driving license (mDL) — accepts ISO 18013-5 mDoc or country-specific SD-JWT DL",
            templateType = "identity",
            // Accepts either the ISO mso_mdoc mDL or country-specific SD-JWT driving
            // licences. credential_sets gives wallets an "any of" semantic.
            dcqlQuery = """{"credentials":[{"id":"mdl_mdoc","format":"mso_mdoc","meta":{"doctype_value":"org.iso.18013.5.1.mDL"},"claims":[{"path":["org.iso.18013.5.1","family_name"]},{"path":["org.iso.18013.5.1","given_name"]},{"path":["org.iso.18013.5.1","birth_date"]},{"path":["org.iso.18013.5.1","document_number"]}]},{"id":"au_dl","format":"dc+sd-jwt","meta":{"vct_values":["urn:au:gov:dl:1"]},"claims":[{"path":["family_name"]},{"path":["given_name"]},{"path":["birth_date"]},{"path":["document_number"]}]},{"id":"in_dl","format":"dc+sd-jwt","meta":{"vct_values":["urn:in:gov:dl:1"]},"claims":[{"path":["family_name"]},{"path":["given_name"]},{"path":["birth_date"]},{"path":["document_number"]}]}],"credential_sets":[{"options":[["mdl_mdoc"],["au_dl"],["in_dl"]]}]}""",
            claimMappings = """{"org.iso.18013.5.1.family_name":"last_name","org.iso.18013.5.1.given_name":"first_name","org.iso.18013.5.1.birth_date":"date_of_birth","org.iso.18013.5.1.document_number":"license_number","family_name":"last_name","given_name":"first_name","birth_date":"date_of_birth","document_number":"license_number"}""",
            validCredentialTypes = """["org.iso.18013.5.1.mDL","urn:au:gov:dl:1","urn:in:gov:dl:1"]""",
        ),
    )

    transaction {
        val now = Instant.now()
        var inserted = 0
        var updated = 0

        for (tmpl in templates) {
            val existing = VerifyTemplates.selectAll()
                .where { (VerifyTemplates.organizationId eq null) and (VerifyTemplates.name eq tmpl.name) }
                .firstOrNull()

            if (existing == null) {
                VerifyTemplates.insert {
                    it[organizationId] = null
                    it[name] = tmpl.name
                    it[displayName] = tmpl.displayName
                    it[description] = tmpl.description
                    it[templateType] = tmpl.templateType
                    it[dcqlQuery] = tmpl.dcqlQuery
                    it[responseMode] = "answers"
                    it[claimMappings] = tmpl.claimMappings
                    it[validCredentialTypes] = tmpl.validCredentialTypes
                    it[createdAt] = now
                    it[updatedAt] = now
                }
                inserted++
            } else {
                // Upsert: system templates (organizationId = null) are owned by the code —
                // override any drift in the DB so DCQL shape + claim mappings stay in sync
                // with the latest seed on every boot (subsumes main's "only when
                // dcql/vct_values changed" check). Organization-scoped copies are
                // left alone.
                val rows = VerifyTemplates.update({
                    (VerifyTemplates.organizationId eq null) and (VerifyTemplates.name eq tmpl.name)
                }) {
                    it[displayName] = tmpl.displayName
                    it[description] = tmpl.description
                    it[templateType] = tmpl.templateType
                    it[dcqlQuery] = tmpl.dcqlQuery
                    it[responseMode] = "answers"
                    it[claimMappings] = tmpl.claimMappings
                    it[validCredentialTypes] = tmpl.validCredentialTypes
                    it[updatedAt] = now
                }
                if (rows > 0) updated++
            }
        }

        logger.info { "System templates: inserted=$inserted, updated=$updated, total=${templates.size}" }
    }
}

/**
 * Seeds the database with a sandbox organization and API keys for demo/testing.
 *
 * Sandbox credentials:
 * - Organization: "Sandbox Demo"
 * - Test Key: vfy_test_sandbox_demo_key_12345678
 * - Live Key: vfy_live_sandbox_demo_key_12345678
 *
 * These credentials are for demo/testing purposes only and should not be used in production.
 */
private fun seedSandboxOrganization() {
    transaction {
        // Check if sandbox organization already exists
        val existingOrg = VerifyOrganizations.selectAll()
            .where { VerifyOrganizations.name eq "Sandbox Demo" }
            .singleOrNull()

        if (existingOrg != null) {
            // Sync rp_id from env var if it changed (prevents stale RP references after re-registration)
            val sandboxRpIdEnv = System.getenv("RP_THEAUSTRALIAHACK_ID")
            val currentRpId = existingOrg[VerifyOrganizations.rpId]
            if (sandboxRpIdEnv != null && sandboxRpIdEnv != currentRpId) {
                val orgId = existingOrg[VerifyOrganizations.id]
                VerifyOrganizations.update({ VerifyOrganizations.id eq orgId }) {
                    it[rpId] = sandboxRpIdEnv
                    it[updatedAt] = Instant.now()
                }
                logger.info { "Updated sandbox organization rp_id: $currentRpId -> $sandboxRpIdEnv" }
            } else {
                logger.info { "Sandbox organization already exists (rpId=$currentRpId), no sync needed" }
            }
            return@transaction
        }

        logger.info { "Seeding sandbox organization and API keys..." }
        val now = Instant.now()

        // Create sandbox organization
        val orgId = java.util.UUID.randomUUID()
        val sandboxRpId = System.getenv("RP_THEAUSTRALIAHACK_ID")
        VerifyOrganizations.insert {
            it[id] = orgId
            it[name] = "Sandbox Demo"
            it[billingEmail] = "sandbox@demo.example.com"
            it[plan] = "sandbox"
            if (sandboxRpId != null) {
                it[rpId] = sandboxRpId
            }
            it[createdAt] = now
            it[updatedAt] = now
        }
        if (sandboxRpId != null) {
            logger.info { "Sandbox organization linked to registered RP: $sandboxRpId" }
        }

        // Create sandbox test API key
        VerifyApiKeys.insert {
            it[id] = java.util.UUID.randomUUID()
            it[organizationId] = orgId
            it[keyHash] = hashApiKey("vfy_test_sandbox_demo_key_12345678")
            it[keyPrefix] = "vfy_test_sandbox"
            it[name] = "Sandbox Test Key"
            it[environment] = "test"
            it[lastUsedAt] = null
            it[createdAt] = now
        }

        // Create sandbox live API key (for production-like testing)
        VerifyApiKeys.insert {
            it[id] = java.util.UUID.randomUUID()
            it[organizationId] = orgId
            it[keyHash] = hashApiKey("vfy_live_sandbox_demo_key_12345678")
            it[keyPrefix] = "vfy_live_sandbox"
            it[name] = "Sandbox Live Key"
            it[environment] = "live"
            it[lastUsedAt] = null
            it[createdAt] = now
        }

        logger.info { "Seeded sandbox organization with test and live API keys" }
    }
}

/**
 * Hash an API key for storage.
 * Uses SHA-256 for consistent hashing.
 */
private fun hashApiKey(apiKey: String): String {
    val digest = java.security.MessageDigest.getInstance("SHA-256")
    val hashBytes = digest.digest(apiKey.toByteArray())
    return hashBytes.joinToString("") { "%02x".format(it) }
}
