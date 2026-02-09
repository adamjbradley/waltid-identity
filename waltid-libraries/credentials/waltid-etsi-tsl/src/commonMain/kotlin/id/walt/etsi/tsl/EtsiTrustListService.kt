package id.walt.etsi.tsl

import id.walt.etsi.tsl.config.TslConfig
import id.walt.etsi.tsl.models.TrustServiceProvider
import id.walt.etsi.tsl.models.TrustServiceList
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private val log = KotlinLogging.logger {}

class EtsiTrustListService(
    private val config: TslConfig,
    httpClient: HttpClient
) : EtsiTrustListProvider {

    private val fetcher = TslFetcher(config, httpClient)
    private val mutex = Mutex()
    private var cachedProviders: List<TrustServiceProvider>? = null
    private var _cachedLotl: TrustServiceList? = null
    private var _cachedMemberStateTls: Map<String, TrustServiceList> = emptyMap()
    private var healthy = false

    override suspend fun fetchLotl(): TrustServiceList {
        return fetcher.fetchLotl()
    }

    override suspend fun fetchMemberStateTl(url: String): TrustServiceList {
        return fetcher.fetchMemberStateTl(url)
    }

    override suspend fun getAllTrustedProviders(): List<TrustServiceProvider> {
        mutex.withLock {
            cachedProviders?.let { return it }
        }
        return refresh().let {
            mutex.withLock { cachedProviders ?: emptyList() }
        }
    }

    override suspend fun refresh() {
        try {
            log.info { "Refreshing ETSI trust lists..." }
            val lotl = fetchLotl()
            val allProviders = mutableListOf<TrustServiceProvider>()
            val memberStateTls = mutableMapOf<String, TrustServiceList>()

            // Fetch member state TLs from LOTL pointers
            for (pointer in lotl.pointers) {
                val territory = pointer.schemeTerritory ?: "unknown"

                // Filter by configured member states
                if (config.memberStates.isNotEmpty() &&
                    !config.memberStates.contains("*") &&
                    !config.memberStates.contains(territory)) {
                    log.debug { "Skipping trust list for $territory (not in configured member states)" }
                    continue
                }

                try {
                    val memberTl = fetchMemberStateTl(pointer.location)
                    memberStateTls[territory] = memberTl
                    val providersWithCountry = memberTl.trustServiceProviders.map {
                        it.copy(country = territory)
                    }
                    allProviders.addAll(providersWithCountry)
                    log.info { "Loaded ${providersWithCountry.size} providers from $territory" }
                } catch (e: Exception) {
                    log.warn { "Failed to fetch trust list for $territory: ${e.message}" }
                }
            }

            mutex.withLock {
                cachedProviders = allProviders
                _cachedLotl = lotl
                _cachedMemberStateTls = memberStateTls
                healthy = true
            }

            log.info { "ETSI trust list refresh complete: ${allProviders.size} total providers" }
        } catch (e: Exception) {
            log.error(e) { "Failed to refresh ETSI trust lists" }
            mutex.withLock { healthy = false }
        }
    }

    override fun isHealthy(): Boolean = healthy

    override fun getCachedLotl(): TrustServiceList? = _cachedLotl

    override fun getCachedMemberStateTls(): Map<String, TrustServiceList> = _cachedMemberStateTls

    override fun getCachedMemberStateTl(country: String): TrustServiceList? = _cachedMemberStateTls[country]
}
