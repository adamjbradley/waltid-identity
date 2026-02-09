package id.walt.commons.trust

import id.walt.commons.config.TrustListConfig
import id.walt.credentials.formats.DigitalCredential
import id.walt.etsi.tsl.EtsiTrustListProvider
import id.walt.federation.OpenIdFederationProvider
import id.walt.federation.models.EntityStatement
import id.walt.federation.models.TrustChain
import id.walt.trust.TrustSource
import id.walt.trust.models.TrustAnchorInfo
import id.walt.trust.models.TrustServiceEntry
import id.walt.trust.models.TrustServiceList
import id.walt.trust.models.TrustServiceProvider
import id.walt.trust.models.TslPointer
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CompositeTrustServiceTest {

    private lateinit var defaultConfig: TrustListConfig
    private lateinit var service: CompositeTrustService

    @BeforeEach
    fun setUp() {
        defaultConfig = TrustListConfig(enabled = true)
        service = CompositeTrustService(defaultConfig)
    }

    // -- setEnabled tests --

    @Test
    fun `setEnabled toggles ETSI_TL source`() = runBlocking {
        val statusBefore = service.getStatus()
        assertTrue(statusBefore.sources[TrustSource.ETSI_TL]!!.enabled, "ETSI_TL should be enabled by default")

        service.setEnabled(TrustSource.ETSI_TL, false)

        val statusAfter = service.getStatus()
        assertFalse(statusAfter.sources[TrustSource.ETSI_TL]!!.enabled, "ETSI_TL should be disabled after setEnabled(false)")
    }

    @Test
    fun `setEnabled can re-enable a disabled source`() = runBlocking {
        service.setEnabled(TrustSource.ETSI_TL, false)
        assertFalse(service.getStatus().sources[TrustSource.ETSI_TL]!!.enabled)

        service.setEnabled(TrustSource.ETSI_TL, true)
        assertTrue(service.getStatus().sources[TrustSource.ETSI_TL]!!.enabled)
    }

    // -- validateIssuer tests --

    @Test
    fun `validateIssuer returns untrusted for null issuer`() = runBlocking {
        val credential = mockk<DigitalCredential>()
        every { credential.issuer } returns null

        val result = service.validateIssuer(credential)

        assertFalse(result.trusted, "Credential with null issuer should be untrusted")
        assertEquals(null, result.source)
    }

    @Test
    fun `validateIssuer returns untrusted when all sources disabled`() = runBlocking {
        service.setEnabled(TrustSource.ETSI_TL, false)
        service.setEnabled(TrustSource.OPENID_FEDERATION, false)

        val credential = mockk<DigitalCredential>()
        every { credential.issuer } returns "some-issuer"

        val result = service.validateIssuer(credential)

        assertFalse(result.trusted, "Should be untrusted when all sources are disabled")
    }

    @Test
    fun `validateIssuer returns untrusted when ETSI disabled`() = runBlocking {
        service.setEnabled(TrustSource.ETSI_TL, false)

        val credential = mockk<DigitalCredential>()
        every { credential.issuer } returns "did:example:issuer123"

        val result = service.validateIssuer(credential)

        assertFalse(result.trusted, "Should return untrusted when ETSI is disabled and no other sources match")
    }

    // -- validateVerifier tests --

    @Test
    fun `validateVerifier returns untrusted when ETSI disabled`() = runBlocking {
        service.setEnabled(TrustSource.ETSI_TL, false)

        val result = service.validateVerifier("client-id-123", null)

        assertFalse(result.trusted, "Should be untrusted when ETSI is disabled")
    }

    // -- getStatus tests --

    @Test
    fun `getStatus returns both ETSI_TL and OPENID_FEDERATION entries`() = runBlocking {
        val status = service.getStatus()

        assertNotNull(status.sources[TrustSource.ETSI_TL], "Status should contain ETSI_TL source")
        assertNotNull(status.sources[TrustSource.OPENID_FEDERATION], "Status should contain OPENID_FEDERATION source")
        assertEquals(2, status.sources.size, "Status should have exactly 2 sources")
    }

    @Test
    fun `getStatus shows ETSI_TL enabled by default`() = runBlocking {
        val status = service.getStatus()

        assertTrue(status.sources[TrustSource.ETSI_TL]!!.enabled, "ETSI_TL should be enabled by default")
    }

    @Test
    fun `getStatus shows OPENID_FEDERATION disabled when no trust anchors`() = runBlocking {
        val status = service.getStatus()

        assertFalse(
            status.sources[TrustSource.OPENID_FEDERATION]!!.enabled,
            "OPENID_FEDERATION should be disabled when no trust anchors are configured"
        )
    }

    @Test
    fun `getStatus shows OPENID_FEDERATION enabled when enabled flag and trust anchors present`() = runBlocking {
        val configWithAnchors = TrustListConfig(
            enabled = true,
            openidFederation = TrustListConfig.OpenIdFederationConfig(
                enabled = true,
                trustAnchors = listOf("https://trust-anchor.example.com")
            )
        )
        val serviceWithAnchors = CompositeTrustService(configWithAnchors)

        val status = serviceWithAnchors.getStatus()

        assertTrue(
            status.sources[TrustSource.OPENID_FEDERATION]!!.enabled,
            "OPENID_FEDERATION should be enabled when enabled flag is true and trust anchors are configured"
        )
    }

    @Test
    fun `getStatus shows OPENID_FEDERATION disabled when enabled false despite trust anchors`() = runBlocking {
        val configDisabledWithAnchors = TrustListConfig(
            enabled = true,
            openidFederation = TrustListConfig.OpenIdFederationConfig(
                enabled = false,
                trustAnchors = listOf("https://trust-anchor.example.com")
            )
        )
        val serviceDisabled = CompositeTrustService(configDisabledWithAnchors)

        val status = serviceDisabled.getStatus()

        assertFalse(
            status.sources[TrustSource.OPENID_FEDERATION]!!.enabled,
            "OPENID_FEDERATION should be disabled when enabled flag is false even with trust anchors"
        )
    }

    @Test
    fun `getStatus shows OPENID_FEDERATION disabled when enabled true but no trust anchors`() = runBlocking {
        val configEnabledNoAnchors = TrustListConfig(
            enabled = true,
            openidFederation = TrustListConfig.OpenIdFederationConfig(
                enabled = true,
                trustAnchors = emptyList()
            )
        )
        val serviceNoAnchors = CompositeTrustService(configEnabledNoAnchors)

        val status = serviceNoAnchors.getStatus()

        assertFalse(
            status.sources[TrustSource.OPENID_FEDERATION]!!.enabled,
            "OPENID_FEDERATION should be disabled when enabled is true but no trust anchors configured"
        )
    }

    @Test
    fun `getStatus reports unhealthy when all sources disabled`() = runBlocking {
        service.setEnabled(TrustSource.ETSI_TL, false)
        service.setEnabled(TrustSource.OPENID_FEDERATION, false)

        val status = service.getStatus()

        assertFalse(status.healthy, "Service should be unhealthy when all sources are disabled")
    }

    @Test
    fun `getStatus shows ETSI_TL disabled after setEnabled false`() = runBlocking {
        service.setEnabled(TrustSource.ETSI_TL, false)

        val status = service.getStatus()

        assertFalse(status.sources[TrustSource.ETSI_TL]!!.enabled)
        assertFalse(status.sources[TrustSource.ETSI_TL]!!.healthy)
        assertEquals(0, status.sources[TrustSource.ETSI_TL]!!.entryCount)
    }

    // -- LOTL browsing and search tests (with injected mock) --

    @Nested
    inner class BrowsingAndSearchTests {

        private lateinit var mockEtsi: EtsiTrustListProvider
        private lateinit var svc: CompositeTrustService

        private val lotl = TrustServiceList(
            schemeTerritory = "EU",
            schemeOperatorName = "European Commission",
            sequenceNumber = 312,
            pointers = listOf(
                TslPointer(location = "https://tsl.at/tsl.xml", schemeTerritory = "AT"),
                TslPointer(location = "https://tsl.de/tsl.xml", schemeTerritory = "DE")
            )
        )

        private val atTsl = TrustServiceList(
            schemeTerritory = "AT",
            schemeOperatorName = "AT Authority",
            trustServiceProviders = listOf(
                TrustServiceProvider(
                    name = "A-Trust Gesellschaft",
                    tradeName = "A-Trust",
                    country = "AT",
                    trustServices = listOf(
                        TrustServiceEntry(
                            serviceType = TrustServiceEntry.TYPE_CA_QC,
                            serviceName = "a.sign Premium Sig",
                            currentStatus = TrustServiceEntry.STATUS_GRANTED
                        ),
                        TrustServiceEntry(
                            serviceType = TrustServiceEntry.TYPE_TSA,
                            serviceName = "a.sign TSA",
                            currentStatus = TrustServiceEntry.STATUS_WITHDRAWN
                        )
                    )
                )
            )
        )

        private val deTsl = TrustServiceList(
            schemeTerritory = "DE",
            schemeOperatorName = "DE Authority",
            trustServiceProviders = listOf(
                TrustServiceProvider(
                    name = "D-Trust GmbH",
                    tradeName = "D-Trust",
                    country = "DE",
                    trustServices = listOf(
                        TrustServiceEntry(
                            serviceType = TrustServiceEntry.TYPE_CA_QC,
                            serviceName = "D-TRUST Qualified CA",
                            currentStatus = TrustServiceEntry.STATUS_GRANTED
                        )
                    )
                ),
                TrustServiceProvider(
                    name = "Deutsche Telekom Security",
                    tradeName = "TeleSec",
                    country = "DE",
                    trustServices = listOf(
                        TrustServiceEntry(
                            serviceType = TrustServiceEntry.TYPE_TSA,
                            serviceName = "TeleSec Timestamp",
                            currentStatus = TrustServiceEntry.STATUS_GRANTED
                        )
                    )
                )
            )
        )

        private val allProviders = atTsl.trustServiceProviders + deTsl.trustServiceProviders

        @BeforeEach
        fun setUp() {
            mockEtsi = mockk<EtsiTrustListProvider>()
            every { mockEtsi.getCachedLotl() } returns lotl
            every { mockEtsi.getCachedMemberStateTls() } returns mapOf("AT" to atTsl, "DE" to deTsl)
            every { mockEtsi.getCachedMemberStateTl("AT") } returns atTsl
            every { mockEtsi.getCachedMemberStateTl("DE") } returns deTsl
            every { mockEtsi.getCachedMemberStateTl("XX") } returns null
            every { mockEtsi.isHealthy() } returns true
            coEvery { mockEtsi.getAllTrustedProviders() } returns allProviders

            svc = CompositeTrustService(TrustListConfig(enabled = true), mockEtsi)
        }

        // -- getLotl --

        @Test
        fun `getLotl returns LOTL when ETSI enabled`() {
            val result = svc.getLotl()
            assertNotNull(result)
            assertEquals("EU", result.schemeTerritory)
            assertEquals(2, result.pointers.size)
        }

        @Test
        fun `getLotl returns null when ETSI disabled`() = runBlocking {
            svc.setEnabled(TrustSource.ETSI_TL, false)
            assertNull(svc.getLotl())
        }

        // -- getMemberStateTls --

        @Test
        fun `getMemberStateTls returns all cached TSLs when enabled`() {
            val result = svc.getMemberStateTls()
            assertEquals(2, result.size)
            assertTrue(result.containsKey("AT"))
            assertTrue(result.containsKey("DE"))
        }

        @Test
        fun `getMemberStateTls returns empty map when disabled`() = runBlocking {
            svc.setEnabled(TrustSource.ETSI_TL, false)
            assertEquals(emptyMap(), svc.getMemberStateTls())
        }

        // -- getMemberStateTl --

        @Test
        fun `getMemberStateTl returns TSL for known country`() {
            val result = svc.getMemberStateTl("AT")
            assertNotNull(result)
            assertEquals("AT", result.schemeTerritory)
            assertEquals(1, result.trustServiceProviders.size)
        }

        @Test
        fun `getMemberStateTl returns null for unknown country`() {
            assertNull(svc.getMemberStateTl("XX"))
        }

        @Test
        fun `getMemberStateTl returns null when disabled`() = runBlocking {
            svc.setEnabled(TrustSource.ETSI_TL, false)
            assertNull(svc.getMemberStateTl("AT"))
        }

        // -- searchProviders: no filters --

        @Test
        fun `searchProviders returns all providers when no filters`() = runBlocking {
            val results = svc.searchProviders()
            assertEquals(3, results.size)
        }

        @Test
        fun `searchProviders returns empty when ETSI disabled`() = runBlocking {
            svc.setEnabled(TrustSource.ETSI_TL, false)
            val results = svc.searchProviders(query = "trust")
            assertTrue(results.isEmpty())
        }

        // -- searchProviders: query matching --

        @Test
        fun `searchProviders matches provider name case-insensitively`() = runBlocking {
            val results = svc.searchProviders(query = "a-trust")
            assertEquals(1, results.size)
            assertEquals("A-Trust Gesellschaft", results[0].name)
        }

        @Test
        fun `searchProviders matches trade name`() = runBlocking {
            val results = svc.searchProviders(query = "telesec")
            assertEquals(1, results.size)
            assertEquals("Deutsche Telekom Security", results[0].name)
        }

        @Test
        fun `searchProviders matches service name`() = runBlocking {
            val results = svc.searchProviders(query = "timestamp")
            assertEquals(1, results.size)
            assertEquals("Deutsche Telekom Security", results[0].name)
        }

        @Test
        fun `searchProviders returns empty for non-matching query`() = runBlocking {
            val results = svc.searchProviders(query = "nonexistent")
            assertTrue(results.isEmpty())
        }

        // -- searchProviders: country filter --

        @Test
        fun `searchProviders filters by country`() = runBlocking {
            val results = svc.searchProviders(country = "AT")
            assertEquals(1, results.size)
            assertEquals("A-Trust Gesellschaft", results[0].name)
        }

        @Test
        fun `searchProviders country filter is case-insensitive`() = runBlocking {
            val results = svc.searchProviders(country = "at")
            assertEquals(1, results.size)
        }

        @Test
        fun `searchProviders returns empty for unknown country`() = runBlocking {
            val results = svc.searchProviders(country = "XX")
            assertTrue(results.isEmpty())
        }

        // -- searchProviders: status filter --

        @Test
        fun `searchProviders filters by status`() = runBlocking {
            val results = svc.searchProviders(status = "withdrawn")
            assertEquals(1, results.size)
            assertEquals("A-Trust Gesellschaft", results[0].name)
            assertEquals(1, results[0].trustServices.size)
            assertEquals("a.sign TSA", results[0].trustServices[0].serviceName)
        }

        @Test
        fun `searchProviders status filter removes non-matching services`() = runBlocking {
            val results = svc.searchProviders(status = "granted")
            assertEquals(3, results.size)
            // A-Trust should only have the granted service, not the withdrawn one
            val aTrust = results.find { it.name == "A-Trust Gesellschaft" }
            assertNotNull(aTrust)
            assertEquals(1, aTrust.trustServices.size)
            assertEquals("a.sign Premium Sig", aTrust.trustServices[0].serviceName)
        }

        // -- searchProviders: serviceType filter --

        @Test
        fun `searchProviders filters by service type`() = runBlocking {
            val results = svc.searchProviders(serviceType = "TSA")
            assertEquals(2, results.size)
            results.forEach { provider ->
                assertTrue(provider.trustServices.all { it.serviceType.contains("TSA") })
            }
        }

        @Test
        fun `searchProviders filters by CA QC service type`() = runBlocking {
            val results = svc.searchProviders(serviceType = "CA/QC")
            assertEquals(2, results.size)
        }

        // -- searchProviders: combined filters --

        @Test
        fun `searchProviders combines query and country`() = runBlocking {
            val results = svc.searchProviders(query = "trust", country = "DE")
            assertEquals(1, results.size)
            assertEquals("D-Trust GmbH", results[0].name)
        }

        @Test
        fun `searchProviders combines query, country and status`() = runBlocking {
            val results = svc.searchProviders(query = "trust", country = "AT", status = "withdrawn")
            assertEquals(1, results.size)
            assertEquals(1, results[0].trustServices.size)
            assertEquals(TrustServiceEntry.STATUS_WITHDRAWN, results[0].trustServices[0].currentStatus)
        }

        // -- searchProviders: pagination --

        @Test
        fun `searchProviders respects limit`() = runBlocking {
            val results = svc.searchProviders(limit = 2)
            assertEquals(2, results.size)
        }

        @Test
        fun `searchProviders respects offset`() = runBlocking {
            val all = svc.searchProviders()
            val offsetResults = svc.searchProviders(offset = 1)
            assertEquals(all.size - 1, offsetResults.size)
            assertEquals(all[1].name, offsetResults[0].name)
        }

        @Test
        fun `searchProviders respects limit and offset together`() = runBlocking {
            val results = svc.searchProviders(limit = 1, offset = 1)
            assertEquals(1, results.size)
        }

        @Test
        fun `searchProviders returns empty when offset exceeds results`() = runBlocking {
            val results = svc.searchProviders(offset = 100)
            assertTrue(results.isEmpty())
        }
    }

    // -- Federation validation tests (with mocked provider) --

    private fun createServiceWithFederation(
        mockProvider: OpenIdFederationProvider
    ): CompositeTrustService {
        val config = TrustListConfig(
            enabled = true,
            openidFederation = TrustListConfig.OpenIdFederationConfig(
                enabled = true,
                trustAnchors = listOf("https://trust-anchor.example.com")
            )
        )
        return CompositeTrustService(config, federationProvider = mockProvider)
    }

    private fun validTrustChain(entityId: String = "https://issuer.example.com") = TrustChain(
        entityId = entityId,
        trustAnchorId = "https://trust-anchor.example.com",
        statements = listOf(
            EntityStatement(issuer = entityId, subject = entityId),
            EntityStatement(issuer = "https://trust-anchor.example.com", subject = entityId)
        ),
        valid = true
    )

    @Test
    fun `validateIssuer returns trusted via federation when chain is valid`() = runBlocking {
        val mockProvider = mockk<OpenIdFederationProvider>()
        coEvery { mockProvider.buildTrustChain(any()) } returns validTrustChain()
        val svc = createServiceWithFederation(mockProvider)
        svc.setEnabled(TrustSource.ETSI_TL, false)

        val credential = mockk<DigitalCredential>()
        every { credential.issuer } returns "https://issuer.example.com"

        val result = svc.validateIssuer(credential)

        assertTrue(result.trusted, "Should be trusted when federation returns valid chain")
        assertEquals(TrustSource.OPENID_FEDERATION, result.source)
        assertEquals("https://trust-anchor.example.com", result.providerName)
        assertEquals("https://trust-anchor.example.com", result.details["trustAnchorId"])
        assertEquals("2", result.details["chainDepth"])
    }

    @Test
    fun `validateIssuer returns untrusted via federation when chain is invalid`() = runBlocking {
        val mockProvider = mockk<OpenIdFederationProvider>()
        coEvery { mockProvider.buildTrustChain(any()) } returns TrustChain(
            entityId = "https://issuer.example.com",
            trustAnchorId = "",
            statements = emptyList(),
            valid = false,
            error = "No trust anchor found"
        )
        val svc = createServiceWithFederation(mockProvider)
        svc.setEnabled(TrustSource.ETSI_TL, false)

        val credential = mockk<DigitalCredential>()
        every { credential.issuer } returns "https://issuer.example.com"

        val result = svc.validateIssuer(credential)

        assertFalse(result.trusted, "Should be untrusted when federation returns invalid chain")
    }

    @Test
    fun `validateIssuer returns untrusted via federation when chain is null`() = runBlocking {
        val mockProvider = mockk<OpenIdFederationProvider>()
        coEvery { mockProvider.buildTrustChain(any()) } returns null
        val svc = createServiceWithFederation(mockProvider)
        svc.setEnabled(TrustSource.ETSI_TL, false)

        val credential = mockk<DigitalCredential>()
        every { credential.issuer } returns "https://issuer.example.com"

        val result = svc.validateIssuer(credential)

        assertFalse(result.trusted, "Should be untrusted when federation returns null chain")
    }

    @Test
    fun `validateVerifier returns trusted via federation when chain is valid`() = runBlocking {
        val mockProvider = mockk<OpenIdFederationProvider>()
        coEvery { mockProvider.buildTrustChain(any()) } returns validTrustChain("https://verifier.example.com")
        val svc = createServiceWithFederation(mockProvider)
        svc.setEnabled(TrustSource.ETSI_TL, false)

        val result = svc.validateVerifier("https://verifier.example.com", null)

        assertTrue(result.trusted, "Should be trusted when federation returns valid chain for verifier")
        assertEquals(TrustSource.OPENID_FEDERATION, result.source)
        assertEquals("https://trust-anchor.example.com", result.providerName)
    }

    @Test
    fun `validateIssuer handles federation exception gracefully`() = runBlocking {
        val mockProvider = mockk<OpenIdFederationProvider>()
        coEvery { mockProvider.buildTrustChain(any()) } throws RuntimeException("Network error")
        val svc = createServiceWithFederation(mockProvider)
        svc.setEnabled(TrustSource.ETSI_TL, false)

        val credential = mockk<DigitalCredential>()
        every { credential.issuer } returns "https://issuer.example.com"

        val result = svc.validateIssuer(credential)

        assertFalse(result.trusted, "Should be untrusted when federation throws exception")
    }

    @Test
    fun `getStatus reports federation entry count from config`() = runBlocking {
        val mockProvider = mockk<OpenIdFederationProvider>()
        every { mockProvider.isHealthy() } returns true
        val svc = createServiceWithFederation(mockProvider)

        val status = svc.getStatus()

        val fedStatus = status.sources[TrustSource.OPENID_FEDERATION]!!
        assertTrue(fedStatus.enabled, "Federation should be enabled")
        assertTrue(fedStatus.healthy, "Federation should be healthy")
        assertEquals(1, fedStatus.entryCount, "Entry count should match configured trust anchors")
    }

    // -- Custom TSL management tests --

    @Nested
    inner class CustomTslTests {

        private lateinit var mockEtsi: EtsiTrustListProvider
        private lateinit var svc: CompositeTrustService

        private val customTsl = TrustServiceList(
            schemeTerritory = "AU",
            schemeOperatorName = "Australia Hack Authority",
            trustServiceProviders = listOf(
                TrustServiceProvider(
                    name = "TheAustraliaHack Issuer",
                    country = "AU",
                    trustServices = listOf(
                        TrustServiceEntry(
                            serviceType = TrustServiceEntry.TYPE_CA_QC,
                            serviceName = "Issuer CA/QC",
                            currentStatus = TrustServiceEntry.STATUS_GRANTED
                        )
                    )
                )
            )
        )

        @BeforeEach
        fun setUp() {
            mockEtsi = mockk<EtsiTrustListProvider>()
            coEvery { mockEtsi.addCustomTsl("AU", any()) } returns customTsl
            every { mockEtsi.removeCustomTsl("AU") } returns true
            every { mockEtsi.removeCustomTsl("XX") } returns false
            every { mockEtsi.getCustomTslUrls() } returns mapOf("AU" to "https://issuer.theaustraliahack.com/tsl.xml")
            every { mockEtsi.isHealthy() } returns true
            coEvery { mockEtsi.getAllTrustedProviders() } returns customTsl.trustServiceProviders
            every { mockEtsi.getCachedLotl() } returns null
            every { mockEtsi.getCachedMemberStateTls() } returns mapOf("AU" to customTsl)
            every { mockEtsi.getCachedMemberStateTl("AU") } returns customTsl

            svc = CompositeTrustService(TrustListConfig(enabled = true), mockEtsi)
        }

        @Test
        fun `addCustomTsl delegates to ETSI provider and returns TSL`() = runBlocking {
            val result = svc.addCustomTsl("AU", "https://issuer.theaustraliahack.com/tsl.xml")

            assertEquals("AU", result.schemeTerritory)
            assertEquals("Australia Hack Authority", result.schemeOperatorName)
            assertEquals(1, result.trustServiceProviders.size)
        }

        @Test
        fun `removeCustomTsl delegates to ETSI provider`() = runBlocking {
            assertTrue(svc.removeCustomTsl("AU"))
            assertFalse(svc.removeCustomTsl("XX"))
        }

        @Test
        fun `getCustomTslUrls returns URLs from ETSI provider`() = runBlocking {
            val urls = svc.getCustomTslUrls()
            assertEquals(1, urls.size)
            assertEquals("https://issuer.theaustraliahack.com/tsl.xml", urls["AU"])
        }

        @Test
        fun `custom country providers appear in getMemberStateTls`() {
            val tls = svc.getMemberStateTls()
            assertTrue(tls.containsKey("AU"))
            assertEquals("Australia Hack Authority", tls["AU"]!!.schemeOperatorName)
        }

        @Test
        fun `custom country TSL accessible via getMemberStateTl`() {
            val tsl = svc.getMemberStateTl("AU")
            assertNotNull(tsl)
            assertEquals(1, tsl.trustServiceProviders.size)
            assertEquals("TheAustraliaHack Issuer", tsl.trustServiceProviders[0].name)
        }

        @Test
        fun `searchProviders finds custom country providers`() = runBlocking {
            val results = svc.searchProviders(country = "AU")
            assertEquals(1, results.size)
            assertEquals("TheAustraliaHack Issuer", results[0].name)
        }
    }

    // -- Custom TSL issuer validation tests (x509SubjectName matching) --

    @Nested
    inner class CustomTslIssuerValidationTests {

        private lateinit var mockEtsi: EtsiTrustListProvider
        private lateinit var svc: CompositeTrustService

        private val customTslWithCerts = TrustServiceList(
            schemeTerritory = "AU",
            schemeOperatorName = "Australia Hack Authority",
            trustServiceProviders = listOf(
                TrustServiceProvider(
                    name = "TheAustraliaHack Issuer",
                    country = "AU",
                    trustServices = listOf(
                        TrustServiceEntry(
                            serviceType = TrustServiceEntry.TYPE_CA_QC,
                            serviceName = "TheAustraliaHack Issuer CA/QC",
                            currentStatus = TrustServiceEntry.STATUS_GRANTED,
                            serviceDigitalIdentity = TrustAnchorInfo(
                                x509SubjectName = "CN=issuer.theaustraliahack.com",
                                x509Certificate = "MIIByDCCAW+gAwIBAgIUU9b7c6Hrgh9j..."
                            )
                        )
                    )
                ),
                TrustServiceProvider(
                    name = "TheAustraliaHack Verifier",
                    country = "AU",
                    trustServices = listOf(
                        TrustServiceEntry(
                            serviceType = TrustServiceEntry.TYPE_CA_QC,
                            serviceName = "TheAustraliaHack Verifier CA/QC",
                            currentStatus = TrustServiceEntry.STATUS_GRANTED,
                            serviceDigitalIdentity = TrustAnchorInfo(
                                x509SubjectName = "CN=verifier2.theaustraliahack.com",
                                x509Certificate = "MIIBnzCCAUagAwIBAgIUQSg5NhDlxwDF..."
                            )
                        )
                    )
                )
            )
        )

        @BeforeEach
        fun setUp() {
            mockEtsi = mockk<EtsiTrustListProvider>()
            every { mockEtsi.isHealthy() } returns true
            coEvery { mockEtsi.getAllTrustedProviders() } returns customTslWithCerts.trustServiceProviders
            every { mockEtsi.getCachedLotl() } returns null
            every { mockEtsi.getCachedMemberStateTls() } returns mapOf("AU" to customTslWithCerts)

            svc = CompositeTrustService(TrustListConfig(enabled = true), mockEtsi)
        }

        @Test
        fun `validateIssuer matches by x509SubjectName from custom TSL`() = runBlocking {
            val credential = mockk<DigitalCredential>()
            every { credential.issuer } returns "issuer.theaustraliahack.com"

            val result = svc.validateIssuer(credential)

            assertTrue(result.trusted, "Issuer matching x509SubjectName in custom TSL should be trusted")
            assertEquals(TrustSource.ETSI_TL, result.source)
            assertEquals("TheAustraliaHack Issuer", result.providerName)
            assertEquals("AU", result.country)
            assertEquals(TrustServiceEntry.STATUS_GRANTED, result.status)
            assertEquals("TheAustraliaHack Issuer CA/QC", result.details["serviceName"])
        }

        @Test
        fun `validateIssuer matches partial issuer string within x509SubjectName`() = runBlocking {
            // The matching uses contains() so "issuer.theaustraliahack.com" is found in "CN=issuer.theaustraliahack.com"
            val credential = mockk<DigitalCredential>()
            every { credential.issuer } returns "issuer.theaustraliahack.com"

            val result = svc.validateIssuer(credential)

            assertTrue(result.trusted, "Issuer contained in x509SubjectName should match")
        }

        @Test
        fun `validateVerifier matches by x509SubjectName from custom TSL`() = runBlocking {
            val result = svc.validateVerifier("verifier2.theaustraliahack.com", null)

            assertTrue(result.trusted, "Verifier matching x509SubjectName in custom TSL should be trusted")
            assertEquals(TrustSource.ETSI_TL, result.source)
            assertEquals("TheAustraliaHack Verifier", result.providerName)
            assertEquals("AU", result.country)
        }

        @Test
        fun `validateIssuer returns untrusted for unknown issuer`() = runBlocking {
            val credential = mockk<DigitalCredential>()
            every { credential.issuer } returns "unknown.issuer.com"

            val result = svc.validateIssuer(credential)

            assertFalse(result.trusted, "Unknown issuer should not be trusted")
        }

        @Test
        fun `validateIssuer does not match against certificate data only subject name`() = runBlocking {
            // The certificate base64 string should NOT be used for matching — only x509SubjectName
            val credential = mockk<DigitalCredential>()
            every { credential.issuer } returns "MIIByDCCAW"

            val result = svc.validateIssuer(credential)

            assertFalse(result.trusted, "Certificate base64 data should not be used for issuer matching")
        }
    }
}
