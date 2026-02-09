package id.walt.openid4vp.verifier.rp

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import java.security.KeyStore
import java.security.cert.X509Certificate
import java.security.interfaces.ECPublicKey
import java.util.*
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class RpCertificateServiceTest {

    @Test
    fun `generateCertificate creates EC P-256 key pair`() {
        val result = RpCertificateService.generateCertificate("Test RP", "test.example.com")

        val publicKey = result.keyPair.public as ECPublicKey
        assertEquals("EC", publicKey.algorithm)
        // secp256r1 field size is 256 bits
        assertEquals(256, publicKey.params.order.bitLength())
    }

    @Test
    fun `generated certificate has correct SAN dNSName`() {
        val result = RpCertificateService.generateCertificate("Test RP", "my-verifier.example.com")

        val sanExtension = result.certificate.subjectAlternativeNames
        assertNotNull(sanExtension, "Certificate should have SAN extension")

        val dnsNames = sanExtension.filter { (it[0] as Int) == 2 }.map { it[1] as String }
        assertTrue(dnsNames.contains("my-verifier.example.com"), "SAN should contain the domain")
    }

    @Test
    fun `generated certificate subject contains legal name`() {
        val result = RpCertificateService.generateCertificate("Acme Corp Verifier", "acme.example.com")

        val subject = result.certificate.subjectX500Principal.name
        assertTrue(subject.contains("Acme Corp Verifier"), "Subject should contain the legal name")
    }

    @Test
    fun `generated certificate is valid for 1 year`() {
        val before = System.currentTimeMillis()
        val result = RpCertificateService.generateCertificate("Test RP", "test.example.com")
        val after = System.currentTimeMillis()

        val notBefore = result.certificate.notBefore.time
        val notAfter = result.certificate.notAfter.time

        // notBefore should be approximately now
        assertTrue(notBefore >= before - 1000 && notBefore <= after + 1000, "notBefore should be close to now")

        // notAfter should be approximately 1 year from now
        val oneYearMs = 365L * 24 * 3600 * 1000
        val expectedAfter = notBefore + oneYearMs
        assertTrue(
            notAfter >= expectedAfter - 1000 && notAfter <= expectedAfter + 1000,
            "notAfter should be ~1 year from notBefore"
        )
    }

    @Test
    fun `generated JWK has correct key type and curve`() {
        val result = RpCertificateService.generateCertificate("Test RP", "test.example.com")

        val jwk = result.privateKeyJwk
        assertEquals("jwk", jwk["type"]?.jsonPrimitive?.content)

        val innerJwk = jwk["jwk"]?.jsonObject
        assertNotNull(innerJwk, "JWK should have inner jwk object")
        assertEquals("EC", innerJwk["kty"]?.jsonPrimitive?.content)
        assertEquals("P-256", innerJwk["crv"]?.jsonPrimitive?.content)
        assertNotNull(innerJwk["x"]?.jsonPrimitive?.content, "JWK should have x coordinate")
        assertNotNull(innerJwk["y"]?.jsonPrimitive?.content, "JWK should have y coordinate")
        assertNotNull(innerJwk["d"]?.jsonPrimitive?.content, "JWK should have d (private key)")
    }

    @Test
    fun `generated x5c contains base64 DER matching certificate`() {
        val result = RpCertificateService.generateCertificate("Test RP", "test.example.com")

        assertEquals(1, result.x5c.size, "x5c should contain exactly one certificate")

        val certBytes = Base64.getDecoder().decode(result.x5c.first())
        val cf = java.security.cert.CertificateFactory.getInstance("X.509")
        val parsedCert = cf.generateCertificate(certBytes.inputStream()) as X509Certificate

        assertEquals(result.certificate.serialNumber, parsedCert.serialNumber, "Parsed cert should match original")
    }

    @Test
    fun `extractCertInfo returns correct info`() {
        val result = RpCertificateService.generateCertificate("Test RP", "test.example.com")
        val info = result.certInfo

        assertTrue(info.subject.contains("Test RP"), "Subject should contain legal name")
        assertTrue(info.issuer.contains("Test RP"), "Issuer should contain legal name (self-signed)")
        assertNotNull(info.notBefore, "Should have notBefore")
        assertNotNull(info.notAfter, "Should have notAfter")
        assertNotNull(info.serialNumber, "Should have serial number")
        assertTrue(info.fingerprint.contains(":"), "Fingerprint should be colon-separated hex")
    }

    @Test
    fun `buildPkcs12 produces valid PKCS12 with key and cert`() {
        val result = RpCertificateService.generateCertificate("Test RP", "test.example.com")
        val pkcs12Bytes = RpCertificateService.buildPkcs12(result.keyPair, result.certificate)

        assertTrue(pkcs12Bytes.isNotEmpty(), "PKCS12 should not be empty")

        // Verify it's a valid PKCS12
        val ks = KeyStore.getInstance("PKCS12")
        ks.load(pkcs12Bytes.inputStream(), "changeit".toCharArray())

        val alias = ks.aliases().nextElement()
        assertNotNull(ks.getKey(alias, "changeit".toCharArray()), "Should contain private key")
        assertNotNull(ks.getCertificate(alias), "Should contain certificate")
    }

    @Test
    fun `parseCertificatePem extracts correct X509CertInfo`() {
        val result = RpCertificateService.generateCertificate("Parse Test RP", "parse.example.com")

        // Convert to PEM
        val base64Cert = Base64.getMimeEncoder(64, "\n".toByteArray())
            .encodeToString(result.certificate.encoded)
        val pem = "-----BEGIN CERTIFICATE-----\n$base64Cert\n-----END CERTIFICATE-----"

        val (parsedCert, parsedInfo) = RpCertificateService.parseCertificatePem(pem)

        assertEquals(result.certificate.serialNumber, parsedCert.serialNumber)
        assertTrue(parsedInfo.subject.contains("Parse Test RP"))
        assertEquals(result.certInfo.fingerprint, parsedInfo.fingerprint)
    }
}
