@file:OptIn(ExperimentalTime::class)

package id.walt.verifyapi.db

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.server.application.*
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
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
            VerifyApiKeys,
            VerifyWebhooks,
            VerifyTemplates,
            VerifyOrchestrations,
            VerifySessions
        )
    }

    // Seed system templates
    seedSystemTemplates()

    logger.info { "Database configured successfully" }
}

/**
 * Seeds the database with system-level verification templates.
 * These templates are available to all organizations (organizationId = null).
 */
private fun seedSystemTemplates() {
    transaction {
        val existingCount = VerifyTemplates.selectAll()
            .where { VerifyTemplates.organizationId eq null }
            .count()

        if (existingCount == 0L) {
            logger.info { "Seeding system templates..." }
            val now = Instant.now()

            // Age Check template - simple age verification
            VerifyTemplates.insert {
                it[organizationId] = null
                it[name] = "age_check"
                it[displayName] = "Age Verification"
                it[description] = "Verify user is over 18 years old"
                it[templateType] = "identity"
                it[dcqlQuery] = """{"credentials":[{"id":"pid","format":"dc+sd-jwt","meta":{"vct_values":["urn:eudi:pid:1"]},"claims":[{"path":["age_over_18"]}]}]}"""
                it[responseMode] = "answers"
                it[claimMappings] = """{"age_over_18":"is_adult"}"""
                it[validCredentialTypes] = """["urn:eudi:pid:1"]"""
                it[createdAt] = now
                it[updatedAt] = now
            }

            // Full KYC template - complete identity verification
            VerifyTemplates.insert {
                it[organizationId] = null
                it[name] = "full_kyc"
                it[displayName] = "Full KYC"
                it[description] = "Complete identity verification with name, birth date, and nationality"
                it[templateType] = "identity"
                it[dcqlQuery] = """{"credentials":[{"id":"pid","format":"dc+sd-jwt","meta":{"vct_values":["urn:eudi:pid:1"]},"claims":[{"path":["family_name"]},{"path":["given_name"]},{"path":["birth_date"]},{"path":["nationality"]}]}]}"""
                it[responseMode] = "answers"
                it[claimMappings] = """{"family_name":"last_name","given_name":"first_name","birth_date":"date_of_birth","nationality":"nationality"}"""
                it[validCredentialTypes] = """["urn:eudi:pid:1"]"""
                it[createdAt] = now
                it[updatedAt] = now
            }

            // Transaction Binding template - for payment wallet attestation
            VerifyTemplates.insert {
                it[organizationId] = null
                it[name] = "transaction_binding"
                it[displayName] = "Payment Authorization"
                it[description] = "Verify payment wallet attestation for transaction binding"
                it[templateType] = "payment"
                it[dcqlQuery] = """{"credentials":[{"id":"pwa","format":"dc+sd-jwt","meta":{"vct_values":["PaymentWalletAttestation"]},"claims":[{"path":["funding_source"]},{"path":["funding_source","type"]},{"path":["funding_source","panLastFour"]}]}]}"""
                it[responseMode] = "answers"
                it[claimMappings] = """{"funding_source.type":"payment_method","funding_source.panLastFour":"card_last_four"}"""
                it[validCredentialTypes] = """["PaymentWalletAttestation"]"""
                it[createdAt] = now
                it[updatedAt] = now
            }

            // Basic Identity template - minimal identity verification
            VerifyTemplates.insert {
                it[organizationId] = null
                it[name] = "basic_identity"
                it[displayName] = "Basic Identity"
                it[description] = "Verify basic identity with name only"
                it[templateType] = "identity"
                it[dcqlQuery] = """{"credentials":[{"id":"pid","format":"dc+sd-jwt","meta":{"vct_values":["urn:eudi:pid:1"]},"claims":[{"path":["family_name"]},{"path":["given_name"]}]}]}"""
                it[responseMode] = "answers"
                it[claimMappings] = """{"family_name":"last_name","given_name":"first_name"}"""
                it[validCredentialTypes] = """["urn:eudi:pid:1"]"""
                it[createdAt] = now
                it[updatedAt] = now
            }

            // mDL template - mobile driving license verification
            VerifyTemplates.insert {
                it[organizationId] = null
                it[name] = "mdl_verification"
                it[displayName] = "Driving License"
                it[description] = "Verify mobile driving license (mDL)"
                it[templateType] = "identity"
                it[dcqlQuery] = """{"credentials":[{"id":"mdl","format":"mso_mdoc","meta":{"doctype_value":"org.iso.18013.5.1.mDL"},"claims":[{"path":["org.iso.18013.5.1","family_name"]},{"path":["org.iso.18013.5.1","given_name"]},{"path":["org.iso.18013.5.1","birth_date"]},{"path":["org.iso.18013.5.1","document_number"]}]}]}"""
                it[responseMode] = "answers"
                it[claimMappings] = """{"org.iso.18013.5.1.family_name":"last_name","org.iso.18013.5.1.given_name":"first_name","org.iso.18013.5.1.birth_date":"date_of_birth","org.iso.18013.5.1.document_number":"license_number"}"""
                it[validCredentialTypes] = """["org.iso.18013.5.1.mDL"]"""
                it[createdAt] = now
                it[updatedAt] = now
            }

            logger.info { "Seeded 5 system templates" }
        } else {
            logger.info { "System templates already exist (count: $existingCount), skipping seed" }
        }
    }
}
