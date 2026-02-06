package id.walt.commons.trust

import id.walt.commons.config.TrustListConfig
import id.walt.credentials.formats.DigitalCredential
import id.walt.etsi.tsl.EtsiTrustListService
import id.walt.etsi.tsl.config.TslConfig
import id.walt.trust.*
import io.klogging.noCoLogger
import io.ktor.client.*
import io.ktor.client.engine.okhttp.*

private val log = noCoLogger("CompositeTrustService")

class CompositeTrustService(
    private val config: TrustListConfig
) : TrustService {

    private val httpClient = HttpClient(OkHttp)

    private val etsiService by lazy {
        val tslConfig = TslConfig(
            lotlUrl = config.etsi.lotlUrl,
            cacheTtlHours = config.etsi.cacheTtlHours,
            memberStates = config.etsi.memberStates,
            validateSignatures = config.etsi.validateSignatures
        )
        EtsiTrustListService(tslConfig, httpClient)
    }

    private val enabledSources = mutableMapOf(
        TrustSource.ETSI_TL to true,
        TrustSource.OPENID_FEDERATION to config.openidFederation.trustAnchors.isNotEmpty()
    )

    override suspend fun validateIssuer(credential: DigitalCredential): TrustValidationResult {
        val issuer = credential.issuer ?: return TrustValidationResult(trusted = false)

        // Try ETSI TL
        if (enabledSources[TrustSource.ETSI_TL] == true) {
            try {
                val providers = etsiService.getAllTrustedProviders()
                for (provider in providers) {
                    for (service in provider.trustServices) {
                        // Match by service name or digital identity against the issuer
                        val identityMatch = service.serviceDigitalIdentity?.x509SubjectName?.contains(issuer) == true
                        val nameMatch = provider.name.equals(issuer, ignoreCase = true)

                        if (identityMatch || nameMatch) {
                            return TrustValidationResult(
                                trusted = true,
                                source = TrustSource.ETSI_TL,
                                providerName = provider.name,
                                country = provider.country,
                                status = service.currentStatus,
                                details = mapOf(
                                    "serviceType" to service.serviceType,
                                    "serviceName" to service.serviceName,
                                    "isGranted" to service.isGranted.toString()
                                )
                            )
                        }
                    }
                }
            } catch (e: Exception) {
                log.warn { "ETSI TL validation failed for $issuer: ${e.message}" }
            }
        }

        return TrustValidationResult(trusted = false)
    }

    override suspend fun validateVerifier(clientId: String, certificates: List<ByteArray>?): TrustValidationResult {
        // Similar pattern - check trust lists for verifier identity
        if (enabledSources[TrustSource.ETSI_TL] == true) {
            try {
                val providers = etsiService.getAllTrustedProviders()
                for (provider in providers) {
                    for (service in provider.trustServices) {
                        val identityMatch = service.serviceDigitalIdentity?.x509SubjectName?.contains(clientId) == true
                        if (identityMatch) {
                            return TrustValidationResult(
                                trusted = true,
                                source = TrustSource.ETSI_TL,
                                providerName = provider.name,
                                country = provider.country,
                                status = service.currentStatus,
                                details = mapOf(
                                    "serviceType" to service.serviceType,
                                    "serviceName" to service.serviceName
                                )
                            )
                        }
                    }
                }
            } catch (e: Exception) {
                log.warn { "ETSI TL verifier validation failed for $clientId: ${e.message}" }
            }
        }

        return TrustValidationResult(trusted = false)
    }

    override suspend fun getStatus(): TrustServiceStatus {
        val sources = mutableMapOf<TrustSource, TrustSourceStatus>()

        val etsiEnabled = enabledSources[TrustSource.ETSI_TL] == true
        val etsiHealthy = if (etsiEnabled) {
            try {
                etsiService.isHealthy()
            } catch (_: Exception) {
                false
            }
        } else false

        val etsiEntryCount = if (etsiEnabled) {
            try {
                etsiService.getAllTrustedProviders().size
            } catch (_: Exception) {
                0
            }
        } else 0

        sources[TrustSource.ETSI_TL] = TrustSourceStatus(
            enabled = etsiEnabled,
            healthy = etsiHealthy,
            entryCount = etsiEntryCount
        )

        sources[TrustSource.OPENID_FEDERATION] = TrustSourceStatus(
            enabled = enabledSources[TrustSource.OPENID_FEDERATION] == true,
            healthy = true,
            entryCount = 0
        )

        return TrustServiceStatus(
            healthy = sources.values.any { it.enabled && it.healthy },
            sources = sources
        )
    }

    override suspend fun setEnabled(source: TrustSource, enabled: Boolean) {
        enabledSources[source] = enabled
        log.info { "Trust source $source ${if (enabled) "enabled" else "disabled"}" }
    }
}
