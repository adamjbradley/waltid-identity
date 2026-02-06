package id.walt.commons.trust

import id.walt.commons.config.TrustListConfig
import id.walt.credentials.formats.DigitalCredential
import id.walt.trust.TrustSource
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
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
    fun `getStatus shows OPENID_FEDERATION enabled when trust anchors present`() = runBlocking {
        val configWithAnchors = TrustListConfig(
            enabled = true,
            openidFederation = TrustListConfig.OpenIdFederationConfig(
                trustAnchors = listOf("https://trust-anchor.example.com")
            )
        )
        val serviceWithAnchors = CompositeTrustService(configWithAnchors)

        val status = serviceWithAnchors.getStatus()

        assertTrue(
            status.sources[TrustSource.OPENID_FEDERATION]!!.enabled,
            "OPENID_FEDERATION should be enabled when trust anchors are configured"
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
}
