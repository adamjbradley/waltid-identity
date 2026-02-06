package id.walt.verifyapi.session

import id.walt.commons.persistence.ConfiguredPersistence
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID
import kotlin.time.Duration.Companion.minutes

private val logger = KotlinLogging.logger {}

/**
 * Manages verification sessions using Valkey/Redis persistence.
 *
 * Sessions are stored with a configurable TTL (default 5 minutes) and automatically
 * expire. The underlying persistence layer is configured via the standard
 * PersistenceConfiguration (memory or redis).
 */
object SessionManager {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val sessionTtl = 5.minutes

    private val sessionPersistence = ConfiguredPersistence(
        discriminator = "verify:session",
        defaultExpiration = sessionTtl,
        encoding = { session: VerificationSession -> json.encodeToString(session) },
        decoding = { data: String -> json.decodeFromString<VerificationSession>(data) }
    )

    /**
     * Generate a unique session ID in the format vs_xxxxxxxxxxxx
     */
    private fun generateSessionId(): String {
        val uuid = UUID.randomUUID().toString().replace("-", "").take(12)
        return "vs_$uuid"
    }

    /**
     * Create a new verification session.
     *
     * @param organizationId Organization initiating the verification
     * @param templateName Name of the verification template to use
     * @param responseMode How to return verification results
     * @param metadata Optional metadata from the requesting application
     * @return The created session
     */
    fun createSession(
        organizationId: UUID,
        templateName: String,
        responseMode: ResponseMode,
        metadata: Map<String, String>? = null
    ): VerificationSession {
        val sessionId = generateSessionId()
        val now = System.currentTimeMillis()

        val session = VerificationSession(
            id = sessionId,
            organizationId = organizationId.toString(),
            templateName = templateName,
            responseMode = responseMode,
            status = SessionStatus.PENDING,
            createdAt = now,
            expiresAt = now + sessionTtl.inWholeMilliseconds,
            metadata = metadata
        )

        sessionPersistence[sessionId] = session
        logger.debug { "Created session $sessionId for org $organizationId with template $templateName" }

        return session
    }

    /**
     * Retrieve a session by ID.
     *
     * @param sessionId The session ID (vs_xxx format)
     * @return The session if found, null otherwise
     */
    fun getSession(sessionId: String): VerificationSession? {
        return sessionPersistence[sessionId]
    }

    /**
     * Update an existing session.
     *
     * @param session The updated session data
     * @return The updated session
     */
    fun updateSession(session: VerificationSession): VerificationSession {
        sessionPersistence[session.id] = session
        logger.debug { "Updated session ${session.id} to status ${session.status}" }
        return session
    }

    /**
     * Update session with verifier session ID from verifier-api2.
     *
     * @param sessionId Our session ID
     * @param verifierSessionId The session ID from verifier-api2
     * @return The updated session or null if session not found
     */
    fun linkVerifierSession(sessionId: String, verifierSessionId: String): VerificationSession? {
        val session = getSession(sessionId) ?: return null
        val updated = session.copy(verifierSessionId = verifierSessionId)
        return updateSession(updated)
    }

    /**
     * Mark a session as verified with results.
     *
     * @param sessionId The session ID
     * @param result The verification result data
     * @return The updated session or null if session not found
     */
    fun markVerified(sessionId: String, result: VerificationResult): VerificationSession? {
        val session = getSession(sessionId) ?: return null
        val updated = session.copy(
            status = SessionStatus.VERIFIED,
            result = result
        )
        return updateSession(updated)
    }

    /**
     * Mark a session as failed.
     *
     * @param sessionId The session ID
     * @param reason Optional reason for failure
     * @return The updated session or null if session not found
     */
    fun markFailed(sessionId: String, reason: String? = null): VerificationSession? {
        val session = getSession(sessionId) ?: return null
        val metadata = session.metadata?.toMutableMap() ?: mutableMapOf()
        if (reason != null) {
            metadata["failureReason"] = reason
        }
        val updated = session.copy(
            status = SessionStatus.FAILED,
            metadata = metadata.ifEmpty { null }
        )
        return updateSession(updated)
    }

    /**
     * Check if a session exists.
     *
     * @param sessionId The session ID
     * @return true if the session exists
     */
    fun sessionExists(sessionId: String): Boolean {
        return sessionPersistence.contains(sessionId)
    }

    /**
     * Delete a session.
     *
     * @param sessionId The session ID to delete
     */
    fun deleteSession(sessionId: String) {
        sessionPersistence.remove(sessionId)
        logger.debug { "Deleted session $sessionId" }
    }
}
