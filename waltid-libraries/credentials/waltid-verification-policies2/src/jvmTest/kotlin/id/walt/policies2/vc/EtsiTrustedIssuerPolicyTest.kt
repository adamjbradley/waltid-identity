package id.walt.policies2.vc

import id.walt.credentials.CredentialParser
import id.walt.credentials.examples.W3CExamples
import id.walt.policies2.vc.policies.CredentialVerificationPolicy2
import id.walt.policies2.vc.policies.EtsiTrustedIssuerPolicy
import id.walt.trust.TrustService
import id.walt.trust.TrustSource
import id.walt.trust.TrustValidationResult
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class EtsiTrustedIssuerPolicyTest {

    private var originalProvider: (() -> TrustService?)? = null

    @BeforeEach
    fun setUp() {
        originalProvider = EtsiTrustedIssuerPolicy.trustServiceProvider
    }

    @AfterEach
    fun tearDown() {
        EtsiTrustedIssuerPolicy.trustServiceProvider = originalProvider
    }

    @Test
    fun `test verify succeeds when trust service returns trusted result`() = runTest {
        val mockTrustService = mockk<TrustService>()
        coEvery { mockTrustService.validateIssuer(any()) } returns TrustValidationResult(
            trusted = true,
            source = TrustSource.ETSI_TL,
            providerName = "Test Provider"
        )
        EtsiTrustedIssuerPolicy.trustServiceProvider = { mockTrustService }

        val policy = EtsiTrustedIssuerPolicy()
        val credential = CredentialParser.parseOnly(W3CExamples.w3cCredential)
        val result = policy.verify(credential)

        assertTrue(result.isSuccess, "Expected verification to succeed but it failed: ${result.exceptionOrNull()}")
    }

    @Test
    fun `test verify fails when trust service returns untrusted result`() = runTest {
        val mockTrustService = mockk<TrustService>()
        coEvery { mockTrustService.validateIssuer(any()) } returns TrustValidationResult(
            trusted = false
        )
        EtsiTrustedIssuerPolicy.trustServiceProvider = { mockTrustService }

        val policy = EtsiTrustedIssuerPolicy()
        val credential = CredentialParser.parseOnly(W3CExamples.w3cCredential)
        val result = policy.verify(credential)

        assertTrue(result.isFailure, "Expected verification to fail but it succeeded")
        val exception = result.exceptionOrNull()
        assertNotNull(exception)
        assertTrue(
            exception.message!!.contains("Issuer not found in ETSI trust lists"),
            "Expected message about issuer not found, got: ${exception.message}"
        )
    }

    @Test
    fun `test verify fails when trust service provider is null`() = runTest {
        EtsiTrustedIssuerPolicy.trustServiceProvider = null

        val policy = EtsiTrustedIssuerPolicy()
        val credential = CredentialParser.parseOnly(W3CExamples.w3cCredential)
        val result = policy.verify(credential)

        assertTrue(result.isFailure, "Expected verification to fail when trustServiceProvider is null")
        val exception = result.exceptionOrNull()
        assertNotNull(exception)
        assertTrue(
            exception.message!!.contains("not enabled"),
            "Expected message about trust lists not enabled, got: ${exception.message}"
        )
    }

    @Test
    fun `test verify fails when trust service provider returns null`() = runTest {
        EtsiTrustedIssuerPolicy.trustServiceProvider = { null }

        val policy = EtsiTrustedIssuerPolicy()
        val credential = CredentialParser.parseOnly(W3CExamples.w3cCredential)
        val result = policy.verify(credential)

        assertTrue(result.isFailure, "Expected verification to fail when trustServiceProvider returns null")
        val exception = result.exceptionOrNull()
        assertNotNull(exception)
        assertTrue(
            exception.message!!.contains("not enabled"),
            "Expected message about trust lists not enabled, got: ${exception.message}"
        )
    }

    @OptIn(ExperimentalSerializationApi::class)
    @Test
    fun `test serialization round trip`() {
        val json = Json {
            classDiscriminator = "policy"
            ignoreUnknownKeys = true
        }

        val original = EtsiTrustedIssuerPolicy(
            memberStates = listOf("DE", "FR"),
            requireGrantedStatus = true
        )

        val serialized = json.encodeToString(CredentialVerificationPolicy2.serializer(), original)
        val deserialized = json.decodeFromString(CredentialVerificationPolicy2.serializer(), serialized)

        check(deserialized is EtsiTrustedIssuerPolicy) { "Deserialized policy should be EtsiTrustedIssuerPolicy" }
        assertEquals(listOf("DE", "FR"), deserialized.memberStates)
        assertEquals(true, deserialized.requireGrantedStatus)
        assertEquals("etsi-trusted-issuer", deserialized.id)
    }

    @Test
    fun `test default values`() {
        val policy = EtsiTrustedIssuerPolicy()

        assertEquals(emptyList(), policy.memberStates, "Default memberStates should be empty")
        assertEquals(true, policy.requireGrantedStatus, "Default requireGrantedStatus should be true")
        assertEquals("etsi-trusted-issuer", policy.id, "Policy id should be 'etsi-trusted-issuer'")
    }
}
