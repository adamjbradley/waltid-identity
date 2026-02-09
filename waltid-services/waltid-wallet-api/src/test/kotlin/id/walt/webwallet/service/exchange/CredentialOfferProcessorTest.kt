package id.walt.webwallet.service.exchange

import id.walt.oid4vc.OpenID4VCI
import id.walt.oid4vc.data.CredentialFormat
import id.walt.oid4vc.data.OpenIDProviderMetadata
import id.walt.oid4vc.requests.CredentialRequest
import id.walt.oid4vc.responses.CredentialResponse
import io.mockk.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.*

class CredentialOfferProcessorTest {

    private val providerMetadata = mockk<OpenIDProviderMetadata>()
    private val accessToken = "test-access-token"

    @BeforeTest
    fun setup() {
        mockkObject(OpenID4VCI)
        every { providerMetadata.credentialEndpoint } returns "https://issuer.example.com/credential"
    }

    @AfterTest
    fun teardown() {
        unmockkObject(OpenID4VCI)
    }

    @Test
    fun `single JWT credential returns ProcessedCredentialOffer`() = runTest {
        val credentialRequest = mockk<CredentialRequest>()
        val jwtString = "eyJhbGciOiJFUzI1NiJ9.eyJzdWIiOiIxMjM0In0.signature"
        val credentialResponse = CredentialResponse.success(
            format = CredentialFormat.jwt_vc_json,
            credential = JsonPrimitive(jwtString)
        )

        coEvery {
            OpenID4VCI.sendCredentialRequest(providerMetadata, accessToken, credentialRequest)
        } returns credentialResponse

        val result = CredentialOfferProcessor.process(
            credentialRequests = listOf(credentialRequest),
            providerMetadata = providerMetadata,
            accessToken = accessToken,
        )

        assertEquals(1, result.size)
        assertEquals(credentialResponse, result.first().credentialResponse)
        assertEquals(credentialRequest, result.first().credentialRequest)
    }

    @Test
    fun `single SD-JWT credential with trailing tilde passes`() = runTest {
        val credentialRequest = mockk<CredentialRequest>()
        val sdJwtString = "eyJhbGciOiJFUzI1NiJ9.eyJfc2QiOlsidGVzdCJdfQ.sig~disc1~disc2~"
        val credentialResponse = CredentialResponse.success(
            format = CredentialFormat.sd_jwt_vc,
            credential = JsonPrimitive(sdJwtString)
        )

        coEvery {
            OpenID4VCI.sendCredentialRequest(providerMetadata, accessToken, credentialRequest)
        } returns credentialResponse

        val result = CredentialOfferProcessor.process(
            credentialRequests = listOf(credentialRequest),
            providerMetadata = providerMetadata,
            accessToken = accessToken,
        )

        assertEquals(1, result.size)
        assertEquals(sdJwtString, result.first().credentialResponse.credential?.let {
            (it as JsonPrimitive).content
        })
    }

    @Test
    fun `single SD-JWT credential without trailing tilde throws`() = runTest {
        val credentialRequest = mockk<CredentialRequest>()
        // Contains ~ but does NOT end with ~
        val sdJwtString = "eyJhbGciOiJFUzI1NiJ9.eyJfc2QiOlsidGVzdCJdfQ.sig~disc1~disc2"
        val credentialResponse = CredentialResponse.success(
            format = CredentialFormat.sd_jwt_vc,
            credential = JsonPrimitive(sdJwtString)
        )

        coEvery {
            OpenID4VCI.sendCredentialRequest(providerMetadata, accessToken, credentialRequest)
        } returns credentialResponse

        assertFailsWith<IllegalArgumentException>("SD-JWT Credential must end with '~'") {
            CredentialOfferProcessor.process(
                credentialRequests = listOf(credentialRequest),
                providerMetadata = providerMetadata,
                accessToken = accessToken,
            )
        }
    }

    @Test
    fun `single credential with null credential does not crash`() = runTest {
        val credentialRequest = mockk<CredentialRequest>()
        val credentialResponse = CredentialResponse(
            format = CredentialFormat.jwt_vc_json,
            credential = null,
        )

        coEvery {
            OpenID4VCI.sendCredentialRequest(providerMetadata, accessToken, credentialRequest)
        } returns credentialResponse

        val result = CredentialOfferProcessor.process(
            credentialRequests = listOf(credentialRequest),
            providerMetadata = providerMetadata,
            accessToken = accessToken,
        )

        assertEquals(1, result.size)
        assertNull(result.first().credentialResponse.credential)
    }

    @Test
    fun `deferred credential response with acceptance token`() = runTest {
        val credentialRequest = mockk<CredentialRequest>()
        val credentialResponse = CredentialResponse.deferred(
            format = CredentialFormat.jwt_vc_json,
            acceptanceToken = "txn_12345"
        )

        coEvery {
            OpenID4VCI.sendCredentialRequest(providerMetadata, accessToken, credentialRequest)
        } returns credentialResponse

        val result = CredentialOfferProcessor.process(
            credentialRequests = listOf(credentialRequest),
            providerMetadata = providerMetadata,
            accessToken = accessToken,
        )

        assertEquals(1, result.size)
        assertNull(result.first().credentialResponse.credential)
        assertEquals("txn_12345", result.first().credentialResponse.acceptanceToken)
        assertTrue(result.first().credentialResponse.isDeferred)
    }

    @Test
    fun `non-SD-JWT credential without tilde passes`() = runTest {
        val credentialRequest = mockk<CredentialRequest>()
        // Plain JWT without any tildes — should skip SD-JWT validation entirely
        val jwtString = "eyJhbGciOiJFUzI1NiJ9.eyJ2YyI6eyJ0eXBlIjoiT3BlbkJhZGdlIn19.signature"
        val credentialResponse = CredentialResponse.success(
            format = CredentialFormat.jwt_vc_json,
            credential = JsonPrimitive(jwtString)
        )

        coEvery {
            OpenID4VCI.sendCredentialRequest(providerMetadata, accessToken, credentialRequest)
        } returns credentialResponse

        val result = CredentialOfferProcessor.process(
            credentialRequests = listOf(credentialRequest),
            providerMetadata = providerMetadata,
            accessToken = accessToken,
        )

        assertEquals(1, result.size)
        assertEquals(jwtString, result.first().credentialResponse.credential?.let {
            (it as JsonPrimitive).content
        })
    }
}
