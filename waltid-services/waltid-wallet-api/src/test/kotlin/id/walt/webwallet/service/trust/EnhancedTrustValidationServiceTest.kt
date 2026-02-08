package id.walt.webwallet.service.trust

import id.walt.trust.TrustService
import id.walt.trust.TrustSource
import id.walt.trust.TrustValidationResult
import io.ktor.client.*
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EnhancedTrustValidationServiceTest {

    private val mockHttpClient = HttpClient(MockEngine { request ->
        respond("", HttpStatusCode.NotFound)
    })

    @Test
    fun `validate delegates to legacy service`() = runTest {
        // Given: EnhancedTrustValidationService with no trust service and no trust record
        val sut = EnhancedTrustValidationService(
            http = mockHttpClient,
            trustRecord = null,
            trustService = null
        )

        // When: calling validate (legacy delegation)
        val result = sut.validate("did:example:123", "VerifiableCredential", "")

        // Then: returns false since no real trust record URL is configured (empty baseUrl)
        assertFalse(result)
    }

    @Test
    fun `validateWithDetails returns trust list result when trusted`() = runTest {
        // Given: a TrustService that returns a trusted result
        val trustServiceMock = mockk<TrustService>()
        val trustedResult = TrustValidationResult(
            trusted = true,
            source = TrustSource.ETSI_TL,
            providerName = "Test QTSP"
        )
        coEvery { trustServiceMock.validateVerifier("did:example:trusted") } returns trustedResult

        val sut = EnhancedTrustValidationService(
            http = mockHttpClient,
            trustRecord = null,
            trustService = trustServiceMock
        )

        // When: calling validateWithDetails
        val result = sut.validateWithDetails("did:example:trusted", "VerifiableCredential")

        // Then: trusted is true and trustListResult is populated
        assertTrue(result.trusted)
        val trustListResult = assertNotNull(result.trustListResult)
        assertEquals(TrustSource.ETSI_TL, trustListResult.source)
        assertEquals("Test QTSP", trustListResult.providerName)
        assertTrue(trustListResult.trusted)

        coVerify(exactly = 1) { trustServiceMock.validateVerifier("did:example:trusted") }
    }

    @Test
    fun `validateWithDetails falls back to legacy when trust service returns untrusted`() = runTest {
        // Given: a TrustService that returns an untrusted result
        val trustServiceMock = mockk<TrustService>()
        val untrustedResult = TrustValidationResult(trusted = false)
        coEvery { trustServiceMock.validateVerifier("did:example:untrusted") } returns untrustedResult

        val sut = EnhancedTrustValidationService(
            http = mockHttpClient,
            trustRecord = null,
            trustService = trustServiceMock
        )

        // When: calling validateWithDetails
        val result = sut.validateWithDetails("did:example:untrusted", "VerifiableCredential")

        // Then: falls through to legacy, which also returns false (no trust record configured)
        assertFalse(result.trusted)
        assertFalse(result.legacyTrusted)
        assertNull(result.trustListResult)

        coVerify(exactly = 1) { trustServiceMock.validateVerifier("did:example:untrusted") }
    }

    @Test
    fun `validateWithDetails falls back to legacy when trust service is null`() = runTest {
        // Given: EnhancedTrustValidationService with no trust service
        val sut = EnhancedTrustValidationService(
            http = mockHttpClient,
            trustRecord = null,
            trustService = null
        )

        // When: calling validateWithDetails
        val result = sut.validateWithDetails("did:example:123", "VerifiableCredential")

        // Then: trustListResult is null (no trust service), falls back to legacy (also false)
        assertFalse(result.trusted)
        assertFalse(result.legacyTrusted)
        assertNull(result.trustListResult)
    }

    @Test
    fun `validateWithDetails handles trust service exception gracefully`() = runTest {
        // Given: a TrustService that throws an exception
        val trustServiceMock = mockk<TrustService>()
        coEvery { trustServiceMock.validateVerifier("did:example:error") } throws RuntimeException("Network error")

        val sut = EnhancedTrustValidationService(
            http = mockHttpClient,
            trustRecord = null,
            trustService = trustServiceMock
        )

        // When: calling validateWithDetails (should not propagate exception)
        val result = sut.validateWithDetails("did:example:error", "VerifiableCredential")

        // Then: graceful fallback to legacy, no exception propagated
        assertFalse(result.trusted)
        assertFalse(result.legacyTrusted)
        assertNull(result.trustListResult)

        coVerify(exactly = 1) { trustServiceMock.validateVerifier("did:example:error") }
    }

    @Test
    fun `DetailedTrustResult serialization round trip`() {
        val json = Json { ignoreUnknownKeys = true }

        // Case 1: with trustListResult populated
        val resultWithTrustList = DetailedTrustResult(
            trusted = true,
            legacyTrusted = false,
            trustListResult = TrustValidationResult(
                trusted = true,
                source = TrustSource.ETSI_TL,
                providerName = "Test QTSP",
                country = "DE",
                status = "granted"
            )
        )
        val encoded1 = json.encodeToString(resultWithTrustList)
        val decoded1 = json.decodeFromString<DetailedTrustResult>(encoded1)
        assertEquals(resultWithTrustList, decoded1)

        // Case 2: with trustListResult null
        val resultWithoutTrustList = DetailedTrustResult(
            trusted = false,
            legacyTrusted = false,
            trustListResult = null
        )
        val encoded2 = json.encodeToString(resultWithoutTrustList)
        val decoded2 = json.decodeFromString<DetailedTrustResult>(encoded2)
        assertEquals(resultWithoutTrustList, decoded2)
    }
}
