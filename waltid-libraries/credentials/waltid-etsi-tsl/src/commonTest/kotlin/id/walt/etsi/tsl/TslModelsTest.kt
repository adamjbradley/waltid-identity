package id.walt.etsi.tsl

import id.walt.etsi.tsl.models.*
import id.walt.etsi.tsl.config.TslConfig
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFalse

class TslModelsTest {

    @Test
    fun testTrustServiceEntryStatusConstants() {
        assertEquals(
            "http://uri.etsi.org/TrstSvc/TrustedList/Svcstatus/granted",
            TrustServiceEntry.STATUS_GRANTED
        )
    }

    @Test
    fun testTrustServiceEntryIsGranted() {
        val granted = TrustServiceEntry(
            serviceType = TrustServiceEntry.TYPE_CA_QC,
            serviceName = "Test",
            currentStatus = TrustServiceEntry.STATUS_GRANTED
        )
        assertTrue(granted.isGranted)

        val withdrawn = TrustServiceEntry(
            serviceType = TrustServiceEntry.TYPE_CA_QC,
            serviceName = "Test",
            currentStatus = TrustServiceEntry.STATUS_WITHDRAWN
        )
        assertFalse(withdrawn.isGranted)
    }

    @Test
    fun testTrustServiceEntryIsQualified() {
        val qualified = TrustServiceEntry(
            serviceType = TrustServiceEntry.TYPE_CA_QC,
            serviceName = "Test",
            currentStatus = TrustServiceEntry.STATUS_GRANTED
        )
        assertTrue(qualified.isQualified)
    }

    @Test
    fun testTslConfigDefaults() {
        val config = TslConfig()
        assertEquals("https://ec.europa.eu/tools/lotl/eu-lotl.xml", config.lotlUrl)
        assertEquals(24, config.cacheTtlHours)
        assertEquals(listOf("*"), config.memberStates)
        assertTrue(config.validateSignatures)
        assertFalse(config.isLocalTestMode)
    }

    @Test
    fun testTslConfigLocalTestMode() {
        val config = TslConfig(localTestFile = "test-lotl.xml")
        assertTrue(config.isLocalTestMode)
    }

    @Test
    fun testTslConfigSerialization() {
        val config = TslConfig(memberStates = listOf("DE", "FR"))
        val json = Json.encodeToString(config)
        val deserialized = Json.decodeFromString<TslConfig>(json)
        assertEquals(config, deserialized)
    }

    @Test
    fun testTrustServiceListSerialization() {
        val tsl = TrustServiceList(
            schemeTerritory = "EU",
            schemeOperatorName = "European Commission",
            pointers = listOf(
                TslPointer(
                    location = "https://example.com/tl.xml",
                    schemeTerritory = "DE"
                )
            )
        )
        val json = Json.encodeToString(tsl)
        val deserialized = Json.decodeFromString<TrustServiceList>(json)
        assertEquals(tsl, deserialized)
    }

    @Test
    fun testTrustServiceProviderSerialization() {
        val provider = TrustServiceProvider(
            name = "Test Provider",
            tradeName = "TestCo",
            country = "DE",
            trustServices = listOf(
                TrustServiceEntry(
                    serviceType = TrustServiceEntry.TYPE_CA_QC,
                    serviceName = "Qualified Certificate Service",
                    currentStatus = TrustServiceEntry.STATUS_GRANTED,
                    serviceDigitalIdentity = TrustAnchorInfo(
                        x509SubjectName = "CN=Test"
                    )
                )
            )
        )
        val json = Json.encodeToString(provider)
        val deserialized = Json.decodeFromString<TrustServiceProvider>(json)
        assertEquals(provider, deserialized)
    }
}
