package id.walt.etsi.tsl

import id.walt.etsi.tsl.config.TslConfig
import id.walt.etsi.tsl.models.TrustServiceList
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

private val log = KotlinLogging.logger {}

class TslFetcher(
    private val config: TslConfig,
    private val httpClient: HttpClient
) {
    private val cache = mutableMapOf<String, CachedTsl>()
    private val mutex = Mutex()

    private data class CachedTsl(
        val tsl: TrustServiceList,
        val fetchedAt: Instant
    )

    suspend fun fetchLotl(): TrustServiceList {
        return fetchAndParse(config.lotlUrl)
    }

    suspend fun fetchMemberStateTl(url: String): TrustServiceList {
        return fetchAndParse(url)
    }

    private suspend fun fetchAndParse(url: String): TrustServiceList {
        mutex.withLock {
            cache[url]?.let { cached ->
                val age = Clock.System.now() - cached.fetchedAt
                if (age < config.cacheTtlHours.hours) {
                    log.debug { "Using cached TSL for $url (age: $age)" }
                    return cached.tsl
                }
            }
        }

        log.info { "Fetching TSL from $url" }
        val xmlContent = if (config.isLocalTestMode && url == config.lotlUrl) {
            log.info { "Using local test file: ${config.localTestFile}" }
            throw IllegalStateException("Local test mode requires direct content, not URL fetch")
        } else {
            httpClient.get(url).bodyAsText()
        }

        if (config.validateSignatures) {
            val valid = TslValidator.validateSignature(xmlContent)
            if (!valid) {
                log.warn { "TSL signature validation failed for $url" }
            }
        }

        val tsl = TslParser.parse(xmlContent)

        mutex.withLock {
            cache[url] = CachedTsl(tsl, Clock.System.now())
        }

        log.info { "Parsed TSL for ${tsl.schemeTerritory}: ${tsl.trustServiceProviders.size} providers" }
        return tsl
    }

    fun clearCache() {
        cache.clear()
    }
}
