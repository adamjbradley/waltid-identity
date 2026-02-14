package id.walt.commons.trust

import id.walt.commons.config.TrustListConfig
import id.walt.credentials.formats.DigitalCredential
import id.walt.credentials.signatures.CoseCredentialSignature
import id.walt.credentials.signatures.JwtBasedSignature
import id.walt.etsi.tsl.EtsiTrustListProvider
import id.walt.etsi.tsl.EtsiTrustListService
import id.walt.etsi.tsl.config.TslConfig
import id.walt.federation.OpenIdFederationProvider
import id.walt.federation.OpenIdFederationService
import id.walt.federation.models.FederationConfig
import id.walt.federation.models.TrustAnchor
import id.walt.trust.*
import id.walt.trust.models.TrustServiceList
import id.walt.trust.models.TrustServiceProvider
import io.klogging.noCoLogger
import io.ktor.client.*
import io.ktor.client.engine.okhttp.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

private val log = noCoLogger("CompositeTrustService")

class CompositeTrustService(
    private val config: TrustListConfig,
    etsiServiceProvider: EtsiTrustListProvider? = null,
    private val federationProvider: OpenIdFederationProvider? = null
) : TrustService {

    private val httpClient = HttpClient(OkHttp)

    private val etsiService: EtsiTrustListProvider by lazy {
        etsiServiceProvider ?: run {
            val tslConfig = TslConfig(
                lotlUrl = config.etsi.lotlUrl,
                cacheTtlHours = config.etsi.cacheTtlHours,
                memberStates = config.etsi.memberStates,
                validateSignatures = config.etsi.validateSignatures,
                additionalTslUrls = config.etsi.additionalTslUrls,
                cacheDir = config.etsi.cacheDir,
                refreshIntervalHours = config.etsi.refreshIntervalHours
            )
            EtsiTrustListService(tslConfig, httpClient)
        }
    }

    private val federationService: OpenIdFederationProvider by lazy {
        federationProvider ?: OpenIdFederationService(
            FederationConfig(
                trustAnchors = config.openidFederation.trustAnchors.map { TrustAnchor(entityId = it) },
                maxChainDepth = config.openidFederation.maxChainDepth,
                cacheTtlSeconds = config.openidFederation.cacheTtlSeconds
            ),
            httpClient
        )
    }

    private val enabledSources = mutableMapOf(
        TrustSource.ETSI_TL to true,
        TrustSource.OPENID_FEDERATION to (config.openidFederation.enabled && config.openidFederation.trustAnchors.isNotEmpty())
    )

    override suspend fun validateIssuer(credential: DigitalCredential): TrustValidationResult {
        val issuer = credential.issuer
        val x5cCerts = extractX5cCertificates(credential)

        // If no issuer string AND no x5c certificates, we can't validate
        if (issuer == null && x5cCerts == null) return TrustValidationResult(trusted = false)

        // Try ETSI TL
        if (enabledSources[TrustSource.ETSI_TL] == true) {
            try {
                val normalizedX5c = x5cCerts?.map { normalizeBase64(it) }?.toSet()

                val providers = etsiService.getAllTrustedProviders()
                for (provider in providers) {
                    for (service in provider.trustServices) {
                        val trustCert = service.serviceDigitalIdentity?.x509Certificate

                        // Primary: x5c certificate chain matching (ETSI TS 119 612 compliant)
                        // Works for both JWT (SD-JWT) and COSE (mDoc) credentials
                        if (normalizedX5c != null && trustCert != null) {
                            val normalizedTrustCert = normalizeBase64(trustCert)
                            if (normalizedTrustCert in normalizedX5c) {
                                log.info { "ETSI trust match via x5c certificate chain for issuer ${issuer ?: "(mDoc)"} (provider: ${provider.name}, country: ${provider.country})" }
                                return TrustValidationResult(
                                    trusted = true,
                                    source = TrustSource.ETSI_TL,
                                    providerName = provider.name,
                                    country = provider.country,
                                    status = service.currentStatus,
                                    details = mapOf(
                                        "serviceType" to service.serviceType,
                                        "serviceName" to service.serviceName,
                                        "isGranted" to service.isGranted.toString(),
                                        "matchedBy" to "x5c_certificate_chain"
                                    )
                                )
                            }
                        }

                        // Fallback: string matching by subject name or provider name (requires issuer string)
                        if (issuer != null) {
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
                                        "isGranted" to service.isGranted.toString(),
                                        "matchedBy" to "subject_name_fallback"
                                    )
                                )
                            }
                        }
                    }
                }

                if (x5cCerts == null) {
                    log.debug { "No x5c certificate chain found in credential from ${issuer ?: "(unknown)"} — only string matching was attempted" }
                }
            } catch (e: Exception) {
                log.warn { "ETSI TL validation failed for ${issuer ?: "(mDoc)"}: ${e.message}" }
            }
        }

        // Try OpenID Federation (requires issuer string — not available for mDoc)
        if (issuer != null && enabledSources[TrustSource.OPENID_FEDERATION] == true) {
            try {
                val chain = federationService.buildTrustChain(issuer)
                if (chain != null && chain.valid) {
                    return TrustValidationResult(
                        trusted = true,
                        source = TrustSource.OPENID_FEDERATION,
                        providerName = chain.trustAnchorId,
                        details = mapOf(
                            "trustAnchorId" to chain.trustAnchorId,
                            "chainDepth" to chain.depth.toString()
                        )
                    )
                }
            } catch (e: Exception) {
                log.warn { "OpenID Federation validation failed for $issuer: ${e.message}" }
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

        // Try OpenID Federation
        if (enabledSources[TrustSource.OPENID_FEDERATION] == true) {
            try {
                val chain = federationService.buildTrustChain(clientId)
                if (chain != null && chain.valid) {
                    return TrustValidationResult(
                        trusted = true,
                        source = TrustSource.OPENID_FEDERATION,
                        providerName = chain.trustAnchorId,
                        details = mapOf(
                            "trustAnchorId" to chain.trustAnchorId,
                            "chainDepth" to chain.depth.toString()
                        )
                    )
                }
            } catch (e: Exception) {
                log.warn { "OpenID Federation verifier validation failed for $clientId: ${e.message}" }
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

        val fedEnabled = enabledSources[TrustSource.OPENID_FEDERATION] == true
        val fedHealthy = if (fedEnabled) {
            try {
                federationService.isHealthy()
            } catch (_: Exception) {
                false
            }
        } else false

        sources[TrustSource.OPENID_FEDERATION] = TrustSourceStatus(
            enabled = fedEnabled,
            healthy = fedHealthy,
            entryCount = if (fedEnabled) config.openidFederation.trustAnchors.size else 0
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

    override fun getLotl(): TrustServiceList? {
        return if (enabledSources[TrustSource.ETSI_TL] == true) {
            etsiService.getCachedLotl()
        } else null
    }

    override fun getMemberStateTls(): Map<String, TrustServiceList> {
        return if (enabledSources[TrustSource.ETSI_TL] == true) {
            etsiService.getCachedMemberStateTls()
        } else emptyMap()
    }

    override fun getMemberStateTl(country: String): TrustServiceList? {
        return if (enabledSources[TrustSource.ETSI_TL] == true) {
            etsiService.getCachedMemberStateTl(country)
        } else null
    }

    override suspend fun searchProviders(
        query: String?,
        country: String?,
        status: String?,
        serviceType: String?,
        limit: Int,
        offset: Int
    ): List<TrustServiceProvider> {
        if (enabledSources[TrustSource.ETSI_TL] != true) return emptyList()

        val allProviders = etsiService.getAllTrustedProviders()
        val queryLower = query?.lowercase()

        return allProviders
            .filter { provider ->
                (country == null || provider.country.equals(country, ignoreCase = true)) &&
                (queryLower == null ||
                    provider.name.lowercase().contains(queryLower) ||
                    provider.tradeName?.lowercase()?.contains(queryLower) == true ||
                    provider.trustServices.any { it.serviceName.lowercase().contains(queryLower) })
            }
            .map { provider ->
                if (status == null && serviceType == null) provider
                else provider.copy(
                    trustServices = provider.trustServices.filter { service ->
                        (status == null || service.currentStatus.contains(status, ignoreCase = true)) &&
                        (serviceType == null || service.serviceType.contains(serviceType, ignoreCase = true))
                    }
                )
            }
            .filter { it.trustServices.isNotEmpty() || (status == null && serviceType == null) }
            .drop(offset)
            .take(limit)
    }

    override suspend fun addCustomTsl(country: String, url: String): TrustServiceList {
        return etsiService.addCustomTsl(country, url)
    }

    override fun removeCustomTsl(country: String): Boolean {
        return etsiService.removeCustomTsl(country)
    }

    override fun getCustomTslUrls(): Map<String, String> {
        return etsiService.getCustomTslUrls()
    }

    suspend fun refresh() {
        if (enabledSources[TrustSource.ETSI_TL] == true) {
            etsiService.refresh()
        }
        if (enabledSources[TrustSource.OPENID_FEDERATION] == true) {
            federationService.refresh()
        }
    }

    suspend fun initWithCacheAndRefresh(scope: CoroutineScope) {
        if (enabledSources[TrustSource.ETSI_TL] == true) {
            (etsiService as? EtsiTrustListService)?.initWithCacheAndRefresh(scope)
        }
    }

    private fun extractX5cCertificates(credential: DigitalCredential): List<String>? {
        val signature = credential.signature

        // Handle COSE-based credentials (mDoc)
        if (signature is CoseCredentialSignature) {
            return signature.x5cList?.x5c?.map { it.base64Der }?.takeIf { it.isNotEmpty() }
        }

        // Handle JWT-based credentials (SD-JWT)
        if (signature !is JwtBasedSignature) return null
        val x5c = signature.jwtHeader?.get("x5c")?.jsonArray ?: return null
        if (x5c.isEmpty()) return null
        return x5c.map { it.jsonPrimitive.content }
    }

    private fun normalizeBase64(base64: String): String =
        base64.replace("\\s".toRegex(), "").replace("\n", "").replace("\r", "")
}
