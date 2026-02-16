package id.walt.issuer.tenant

import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import java.security.cert.X509Certificate
import java.security.interfaces.ECPublicKey
import java.util.*
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class IssuerCertificateServiceTest {

    @Test
    fun `generateCertificates creates two-level chain`() {
        val result = IssuerCertificateService.generateCertificates("Test Bank", "AU")

        assertEquals(2, result.x5Chain.size, "x5Chain should have 2 entries (leaf + root)")
    }

    @Test
    fun `IACA certificate is self-signed CA`() {
        val result = IssuerCertificateService.generateCertificates("Test Bank", "AU")
        val iacaInfo = result.iacaCertInfo

        assertTrue(iacaInfo.subject.contains("Test Bank IACA"), "Subject should contain legal name + IACA")
        assertTrue(iacaInfo.subject.contains("AU"), "Subject should contain country code")
        assertEquals(iacaInfo.subject, iacaInfo.issuer, "IACA should be self-signed (subject == issuer)")
    }

    @Test
    fun `Document Signer is signed by IACA`() {
        val result = IssuerCertificateService.generateCertificates("Test Bank", "AU")
        val signerInfo = result.signerCertInfo
        val iacaInfo = result.iacaCertInfo

        assertTrue(signerInfo.subject.contains("Test Bank Document Signer"), "Signer subject should contain legal name + Document Signer")
        assertEquals(iacaInfo.subject, signerInfo.issuer, "Signer should be signed by IACA")
    }

    @Test
    fun `x5Chain is leaf-first order`() {
        val result = IssuerCertificateService.generateCertificates("Test Bank", "AU")

        // Parse both certs from x5Chain
        val cf = java.security.cert.CertificateFactory.getInstance("X.509")

        val leafBytes = Base64.getDecoder().decode(result.x5Chain[0])
        val leafCert = cf.generateCertificate(leafBytes.inputStream()) as X509Certificate

        val rootBytes = Base64.getDecoder().decode(result.x5Chain[1])
        val rootCert = cf.generateCertificate(rootBytes.inputStream()) as X509Certificate

        // Leaf should be signed by root
        assertTrue(leafCert.subjectX500Principal.name.contains("Document Signer"), "x5Chain[0] should be Document Signer")
        assertTrue(rootCert.subjectX500Principal.name.contains("IACA"), "x5Chain[1] should be IACA")

        // Verify chain: leaf signed by root's key
        leafCert.verify(rootCert.publicKey)
    }

    @Test
    fun `IACA has CA BasicConstraints`() {
        val result = IssuerCertificateService.generateCertificates("Test Bank", "AU")

        val rootBytes = Base64.getDecoder().decode(result.x5Chain[1])
        val cf = java.security.cert.CertificateFactory.getInstance("X.509")
        val rootCert = cf.generateCertificate(rootBytes.inputStream()) as X509Certificate

        // BasicConstraints: pathLenConstraint >= 0 means CA=true
        assertTrue(rootCert.basicConstraints >= 0, "IACA should have BasicConstraints CA=true")
    }

    @Test
    fun `Document Signer has correct ExtendedKeyUsage`() {
        val result = IssuerCertificateService.generateCertificates("Test Bank", "AU")

        val leafBytes = Base64.getDecoder().decode(result.x5Chain[0])
        val cf = java.security.cert.CertificateFactory.getInstance("X.509")
        val leafCert = cf.generateCertificate(leafBytes.inputStream()) as X509Certificate

        val ekuList = leafCert.extendedKeyUsage
        assertNotNull(ekuList, "Document Signer should have ExtendedKeyUsage")
        assertTrue(ekuList.contains("1.0.18013.5.1.2"), "EKU should contain ISO 18013-5 mDoc signing OID")
    }

    @Test
    fun `generated keys are EC P-256`() {
        val result = IssuerCertificateService.generateCertificates("Test Bank", "AU")

        val issuerJwk = result.issuerKeyJwk
        assertEquals("EC", issuerJwk["kty"]?.jsonPrimitive?.content)
        assertEquals("P-256", issuerJwk["crv"]?.jsonPrimitive?.content)
        assertNotNull(issuerJwk["x"]?.jsonPrimitive?.content, "JWK should have x coordinate")
        assertNotNull(issuerJwk["y"]?.jsonPrimitive?.content, "JWK should have y coordinate")
        assertNotNull(issuerJwk["d"]?.jsonPrimitive?.content, "JWK should have d (private key)")
    }

    @Test
    fun `ciTokenKey is separate from issuerKey`() {
        val result = IssuerCertificateService.generateCertificates("Test Bank", "AU")

        val issuerJwk = result.issuerKeyJwk
        val ciTokenJwk = result.ciTokenKeyJwk

        // Different keys should have different x,y coordinates
        val issuerX = issuerJwk["x"]?.jsonPrimitive?.content
        val tokenX = ciTokenJwk["x"]?.jsonPrimitive?.content
        assertTrue(issuerX != tokenX, "ciTokenKey should be a different key from issuerKey")

        assertEquals("EC", ciTokenJwk["kty"]?.jsonPrimitive?.content)
        assertEquals("P-256", ciTokenJwk["crv"]?.jsonPrimitive?.content)
    }

    @Test
    fun `Document Signer validity is approximately 1 year`() {
        val result = IssuerCertificateService.generateCertificates("Test Bank", "AU")

        val leafBytes = Base64.getDecoder().decode(result.x5Chain[0])
        val cf = java.security.cert.CertificateFactory.getInstance("X.509")
        val leafCert = cf.generateCertificate(leafBytes.inputStream()) as X509Certificate

        val notBefore = leafCert.notBefore.time
        val notAfter = leafCert.notAfter.time
        val oneYearMs = 365L * 24 * 3600 * 1000

        assertTrue(
            notAfter - notBefore in (oneYearMs - 5000)..(oneYearMs + 5000),
            "Document Signer validity should be ~1 year"
        )
    }

    @Test
    fun `IACA validity is approximately 5 years`() {
        val result = IssuerCertificateService.generateCertificates("Test Bank", "AU")

        val rootBytes = Base64.getDecoder().decode(result.x5Chain[1])
        val cf = java.security.cert.CertificateFactory.getInstance("X.509")
        val rootCert = cf.generateCertificate(rootBytes.inputStream()) as X509Certificate

        val notBefore = rootCert.notBefore.time
        val notAfter = rootCert.notAfter.time
        val fiveYearsMs = 5L * 365 * 24 * 3600 * 1000

        assertTrue(
            notAfter - notBefore in (fiveYearsMs - 5000)..(fiveYearsMs + 5000),
            "IACA validity should be ~5 years"
        )
    }

    @Test
    fun `cert info fingerprints are colon-separated hex`() {
        val result = IssuerCertificateService.generateCertificates("Test Bank", "AU")

        assertTrue(result.iacaCertInfo.fingerprint.contains(":"), "IACA fingerprint should be colon-separated hex")
        assertTrue(result.signerCertInfo.fingerprint.contains(":"), "Signer fingerprint should be colon-separated hex")
    }

    @Test
    fun `extractCertInfo returns correct info`() {
        val result = IssuerCertificateService.generateCertificates("Extract Test", "NZ")

        val leafBytes = Base64.getDecoder().decode(result.x5Chain[0])
        val cf = java.security.cert.CertificateFactory.getInstance("X.509")
        val leafCert = cf.generateCertificate(leafBytes.inputStream()) as X509Certificate

        val info = IssuerCertificateService.extractCertInfo(leafCert)
        assertTrue(info.subject.contains("Extract Test"), "Subject should contain legal name")
        assertNotNull(info.notBefore)
        assertNotNull(info.notAfter)
        assertNotNull(info.serialNumber)
        assertTrue(info.fingerprint.contains(":"))
    }
}
