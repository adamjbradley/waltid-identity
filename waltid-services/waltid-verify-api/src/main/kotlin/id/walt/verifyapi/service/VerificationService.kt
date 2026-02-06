package id.walt.verifyapi.service

import id.walt.verifyapi.routes.VerificationResponse
import id.walt.verifyapi.session.ResponseMode
import id.walt.verifyapi.session.SessionManager
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.long
import kotlinx.serialization.json.longOrNull
import java.util.UUID

private val logger = KotlinLogging.logger {}

/**
 * Service that orchestrates identity verification requests.
 *
 * Responsibilities:
 * - Creates verification sessions
 * - Delegates to verifier-api2 for OID4VP protocol handling
 * - Generates QR code and deep link URLs
 */
object VerificationService {

    /**
     * Public base URL for this service, used in QR codes and deep links.
     * Configure via PUBLIC_BASE_URL environment variable.
     */
    private val publicBaseUrl: String by lazy {
        System.getenv("PUBLIC_BASE_URL") ?: "http://localhost:7010"
    }

    /**
     * Create a new identity verification session.
     *
     * This creates a session in our system and delegates to verifier-api2
     * to create the underlying OID4VP session with signed JAR for EUDI compatibility.
     *
     * @param organizationId Organization initiating the verification
     * @param templateName Name of the verification template to use
     * @param responseMode How to return verification results
     * @param redirectUri Optional redirect URI for same-device flows
     * @param metadata Optional metadata to attach to the session
     * @return VerificationResponse with session details and QR code data
     */
    suspend fun createIdentityVerification(
        organizationId: UUID,
        templateName: String,
        responseMode: ResponseMode,
        redirectUri: String?,
        metadata: Map<String, String>?
    ): VerificationResponse {
        // 1. Lookup the template to get the DCQL query
        val template = TemplateService.findTemplate(organizationId, templateName)
            ?: throw IllegalArgumentException("Template not found: $templateName")

        logger.info { "Found template '${template.name}' for verification (type: ${template.templateType})" }

        // 2. Create session on verifier-api2 with signed JAR request
        val verifierSession = try {
            Verifier2Client.createSession(template.dcqlQuery)
        } catch (e: Exception) {
            logger.error(e) { "Failed to create verifier-api2 session" }
            throw RuntimeException("Failed to create verification session: ${e.message}", e)
        }

        logger.info { "Created verifier-api2 session: ${verifierSession.sessionId}" }

        // 3. Create our internal session and link to verifier-api2 session
        val session = SessionManager.createSession(
            organizationId = organizationId,
            templateName = templateName,
            responseMode = responseMode,
            metadata = metadata,
            verifierSessionId = verifierSession.sessionId
        )

        logger.info { "Created verify-api session ${session.id} linked to verifier-api2 session ${verifierSession.sessionId}" }

        // 4. Extract QR code data from the verifier-api2 response
        // The bootstrapAuthorizationRequestUrl is the OpenID4VP URL for QR codes
        val qrCodeData = verifierSession.bootstrapAuthorizationRequestUrl

        // 5. Generate EUDI wallet deep link
        // Convert openid4vp:// to eudi-openid4vp:// for EUDI wallet
        val deepLink = qrCodeData.replace("openid4vp://", "eudi-openid4vp://")

        logger.debug { "Generated QR data for session ${session.id}: $qrCodeData" }

        return VerificationResponse(
            sessionId = session.id,
            qrCodeUrl = "$publicBaseUrl/v1/qr/${session.id}.png",
            qrCodeData = qrCodeData,
            deepLink = deepLink,
            expiresAt = session.expiresAt
        )
    }

    /**
     * Gets the status of a verification session.
     * Polls verifier-api2 for the actual status and updates local session.
     *
     * @param sessionId The verify-api session ID
     * @return Session status including any verification results
     */
    suspend fun getSessionStatus(sessionId: String): SessionStatus {
        val session = SessionManager.getSession(sessionId)
            ?: throw IllegalArgumentException("Session not found: $sessionId")

        // If session has an external ID, check verifier-api2 for status
        val verifierSessionId = session.verifierSessionId
        if (verifierSessionId != null) {
            try {
                val verifierInfo = Verifier2Client.getSessionInfo(verifierSessionId)

                // Map verifier-api2 status to our status
                val status = when (verifierInfo.status.uppercase()) {
                    "SUCCESSFUL" -> "verified"
                    "UNSUCCESSFUL" -> "failed"
                    "UNUSED", "IN_USE" -> "pending"
                    else -> "pending"
                }

                // Extract claims if verification was successful
                val claims: Map<String, Any>? = if (status == "verified" && verifierInfo.presentedCredentials != null) {
                    extractClaims(verifierInfo.presentedCredentials)
                } else null

                // Update local session if status changed
                val currentStatusStr = session.status.name.lowercase()
                if (status != currentStatusStr) {
                    SessionManager.updateSessionStatus(sessionId, status, claims)
                }

                return SessionStatus(
                    sessionId = sessionId,
                    status = status,
                    templateName = session.templateName,
                    result = claims?.let { SessionResult(it) },
                    verifiedAt = if (status == "verified") System.currentTimeMillis() else null,
                    metadata = session.metadata,
                    expiresAt = session.expiresAt
                )
            } catch (e: Exception) {
                logger.warn(e) { "Failed to get status from verifier-api2, using local status" }
            }
        }

        // Fall back to local session status
        return SessionStatus(
            sessionId = sessionId,
            status = session.status.name.lowercase(),
            templateName = session.templateName,
            result = session.result?.answers?.let { answers ->
                SessionResult(answers.mapValues { it.value as Any })
            },
            verifiedAt = session.result?.verifiedAt,
            metadata = session.metadata,
            expiresAt = session.expiresAt
        )
    }

    /**
     * Extracts claims from verifier-api2 presented credentials.
     */
    private fun extractClaims(presentedCredentials: JsonObject): Map<String, Any> {
        val claims = mutableMapOf<String, Any>()

        // Iterate through credential types
        for ((_, credArray) in presentedCredentials) {
            if (credArray is JsonArray && credArray.isNotEmpty()) {
                val cred = credArray[0]
                if (cred is JsonObject) {
                    // Get credentialData which contains the actual claims
                    val credentialData = cred["credentialData"]
                    if (credentialData is JsonObject) {
                        for ((key, value) in credentialData) {
                            // Skip internal SD-JWT fields
                            if (key.startsWith("_") || key in listOf("iat", "nbf", "exp", "iss", "cnf", "vct")) {
                                continue
                            }
                            // Convert JSON values to Kotlin types
                            claims[key] = when (value) {
                                is JsonPrimitive -> {
                                    when {
                                        value.isString -> value.content
                                        value.booleanOrNull != null -> value.boolean
                                        value.intOrNull != null -> value.int
                                        value.longOrNull != null -> value.long
                                        value.doubleOrNull != null -> value.double
                                        else -> value.content
                                    }
                                }
                                else -> value.toString()
                            }
                        }
                    }
                }
            }
        }

        return claims
    }

    data class SessionStatus(
        val sessionId: String,
        val status: String,
        val templateName: String,
        val result: SessionResult?,
        val verifiedAt: Long?,
        val metadata: Map<String, String>?,
        val expiresAt: Long
    )

    data class SessionResult(
        val claims: Map<String, Any>
    )
}
