@file:OptIn(ExperimentalTime::class)

package id.walt.webwallet.service.trust

import id.walt.trust.TrustService
import id.walt.trust.TrustValidationResult
import id.walt.webwallet.config.TrustConfig
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.*
import kotlinx.serialization.Serializable
import kotlin.time.ExperimentalTime

private val log = KotlinLogging.logger {}

/**
 * Enhanced trust validation service that wraps the legacy [DefaultTrustValidationService]
 * and adds EUDI trust list lookups (ETSI TL, OpenID Federation) when the trust-lists
 * feature is enabled.
 *
 * When [trustService] is null (feature disabled), all calls delegate directly to
 * the legacy service, preserving exact backward compatibility.
 */
class EnhancedTrustValidationService(
    private val http: HttpClient,
    trustRecord: TrustConfig.TrustRecord?,
    private val trustService: TrustService?
) : TrustValidationService {

    private val legacy = DefaultTrustValidationService(http, trustRecord)

    /**
     * Preserves exact existing behavior by delegating to [DefaultTrustValidationService].
     */
    override suspend fun validate(did: String, type: String, egfUri: String): Boolean {
        return legacy.validate(did, type, egfUri)
    }

    /**
     * Enhanced validation that returns detailed trust information.
     * Uses ETSI/Federation trust lists when available, falls back to legacy validation.
     *
     * Note: Since [DigitalCredential] is sealed and cannot be constructed from just a DID,
     * this method uses [TrustService.validateVerifier] which performs the same trust list
     * lookup by string identifier. The underlying [CompositeTrustService] matches the
     * identifier against X.509 subject names and provider names in the trust lists.
     */
    suspend fun validateWithDetails(did: String, type: String): DetailedTrustResult {
        // Try EUDI trust lists first if available
        trustService?.let { service ->
            try {
                // Use validateVerifier as a string-based trust list lookup.
                // CompositeTrustService.validateVerifier matches the clientId against
                // X.509 subject names and provider names, which is exactly the same
                // matching logic used in validateIssuer(credential) after extracting
                // credential.issuer.
                val result = service.validateVerifier(did)
                if (result.trusted) {
                    // Also check legacy for completeness
                    val legacyResult = try {
                        legacy.validate(did, type, "")
                    } catch (e: Exception) {
                        false
                    }
                    return DetailedTrustResult(
                        trusted = true,
                        legacyTrusted = legacyResult,
                        trustListResult = result
                    )
                }
            } catch (e: Exception) {
                log.warn { "Trust list validation failed for $did: ${e.message}" }
            }
        }

        // Fall back to legacy validation
        val legacyResult = try {
            legacy.validate(did, type, "")
        } catch (e: Exception) {
            false
        }

        return DetailedTrustResult(
            trusted = legacyResult,
            legacyTrusted = legacyResult,
            trustListResult = null
        )
    }
}

@Serializable
data class DetailedTrustResult(
    val trusted: Boolean,
    val legacyTrusted: Boolean,
    val trustListResult: TrustValidationResult? = null
)
