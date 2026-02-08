package id.walt.verifyapi.session

import kotlinx.serialization.Serializable

/**
 * Represents a verification session stored in Valkey/Redis.
 *
 * Sessions are created when a verification request is initiated and track the
 * entire lifecycle of the verification process.
 */
@Serializable
data class VerificationSession(
    /** Unique session ID in format vs_xxxxxxxxxxxx */
    val id: String,
    /** Organization ID that owns this session */
    val organizationId: String,
    /** Name of the verification template used */
    val templateName: String,
    /** How to return verification results */
    val responseMode: ResponseMode,
    /** Current status of the verification */
    val status: SessionStatus,
    /** Session ID from the underlying verifier-api2 */
    val verifierSessionId: String? = null,
    /** Epoch millis when session was created */
    val createdAt: Long,
    /** Epoch millis when session expires */
    val expiresAt: Long,
    /** Verification result data (populated when status is VERIFIED) */
    val result: VerificationResult? = null,
    /** Optional metadata from the requesting application */
    val metadata: Map<String, String>? = null
)

/**
 * Status of a verification session.
 */
@Serializable
enum class SessionStatus {
    /** Session created, waiting for wallet response */
    PENDING,
    /** Verification completed successfully */
    VERIFIED,
    /** Verification failed (invalid credential, policy failure, etc.) */
    FAILED,
    /** Session timed out before completion */
    EXPIRED
}

/**
 * Response mode determines what data is returned to the relying party.
 */
@Serializable
enum class ResponseMode {
    /** Return only the extracted answers from disclosed claims */
    ANSWERS,
    /** Return the raw credentials with all disclosed claims */
    RAW_CREDENTIALS
}

/**
 * Result of a successful verification.
 */
@Serializable
data class VerificationResult(
    /** Extracted answers mapped to question names (when responseMode is ANSWERS) */
    val answers: Map<String, String>? = null,
    /** Raw credentials with disclosed data (when responseMode is RAW_CREDENTIALS) */
    val credentials: List<RawCredential>? = null,
    /** Epoch millis when verification completed */
    val verifiedAt: Long
)

/**
 * A raw credential with its disclosed claims.
 */
@Serializable
data class RawCredential(
    /** Credential format: dc+sd-jwt, mso_mdoc, jwt_vc, etc. */
    val format: String,
    /** Verifiable Credential Type for SD-JWT credentials */
    val vct: String? = null,
    /** Document type for mdoc credentials */
    val doctype: String? = null,
    /** Raw credential string (encoded format) */
    val raw: String,
    /** Claims that were disclosed from this credential */
    val disclosedClaims: Map<String, String>
)
