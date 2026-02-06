package id.walt.trust

import kotlinx.datetime.Instant
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TrustValidationResultTest {

    @Test
    fun testTrustedResult() {
        val result = TrustValidationResult(
            trusted = true,
            source = TrustSource.ETSI_TL,
            providerName = "Test Provider",
            country = "DE",
            status = "granted"
        )
        assertTrue(result.trusted)
        assertEquals(TrustSource.ETSI_TL, result.source)
        assertEquals("Test Provider", result.providerName)
    }

    @Test
    fun testUntrustedResult() {
        val result = TrustValidationResult(trusted = false)
        assertFalse(result.trusted)
        assertEquals(null, result.source)
    }

    @Test
    fun testSerializationRoundTrip() {
        val now = Instant.fromEpochSeconds(1700000000)
        val result = TrustValidationResult(
            trusted = true,
            source = TrustSource.ETSI_TL,
            providerName = "Bundesnetzagentur",
            country = "DE",
            status = "granted",
            validFrom = now,
            validUntil = now,
            details = mapOf("serviceType" to "QCert-for-ESig")
        )
        val json = Json.encodeToString(result)
        val deserialized = Json.decodeFromString<TrustValidationResult>(json)
        assertEquals(result, deserialized)
    }

    @Test
    fun testTrustSourceSerializationRoundTrip() {
        val json = Json.encodeToString(TrustSource.ETSI_TL)
        val deserialized = Json.decodeFromString<TrustSource>(json)
        assertEquals(TrustSource.ETSI_TL, deserialized)
    }

    @Test
    fun testTrustServiceStatusSerialization() {
        val status = TrustServiceStatus(
            healthy = true,
            sources = mapOf(
                TrustSource.ETSI_TL to TrustSourceStatus(
                    enabled = true,
                    healthy = true,
                    entryCount = 42
                )
            )
        )
        val json = Json.encodeToString(status)
        val deserialized = Json.decodeFromString<TrustServiceStatus>(json)
        assertEquals(status, deserialized)
    }
}
