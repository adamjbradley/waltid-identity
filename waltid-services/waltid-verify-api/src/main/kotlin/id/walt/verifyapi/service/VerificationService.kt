package id.walt.verifyapi.service

import id.walt.verifyapi.routes.VerificationResponse
import id.walt.verifyapi.session.ResponseMode
import id.walt.verifyapi.session.SessionManager
import io.github.oshai.kotlinlogging.KotlinLogging
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
     * This creates a session in our system and will eventually delegate to
     * verifier-api2 to create the underlying OID4VP session.
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
        // Create our session first
        val session = SessionManager.createSession(
            organizationId = organizationId,
            templateName = templateName,
            responseMode = responseMode,
            metadata = metadata
        )

        logger.info { "Created verification session ${session.id} for template $templateName (org: $organizationId)" }

        // TODO: Task 4.1 will implement template lookup and verifier-api2 delegation
        // For now, generate placeholder OID4VP URLs that will be replaced with real ones

        // Generate the OID4VP authorization URL
        // In the full implementation, this will be obtained from verifier-api2
        val requestUri = "$publicBaseUrl/v1/sessions/${session.id}/request"
        val qrCodeData = "openid4vp://authorize?client_id=verify-api&request_uri=$requestUri"

        // Generate EUDI wallet deep link
        // Uses eudi-openid4vp scheme as per EUDI wallet specification
        val deepLink = "eudi-openid4vp://authorize?request_uri=$requestUri"

        logger.debug { "Generated QR data for session ${session.id}: $qrCodeData" }

        return VerificationResponse(
            sessionId = session.id,
            qrCodeUrl = "$publicBaseUrl/v1/qr/${session.id}.png",
            qrCodeData = qrCodeData,
            deepLink = deepLink,
            expiresAt = session.expiresAt
        )
    }
}
