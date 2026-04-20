package id.walt.openid4vp.verifier.rp

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.ASN1ObjectIdentifier
import org.bouncycastle.asn1.x509.AuthorityKeyIdentifier
import org.bouncycastle.asn1.x509.ExtendedKeyUsage
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.GeneralName
import org.bouncycastle.asn1.x509.GeneralNames
import org.bouncycastle.asn1.x509.KeyPurposeId
import org.bouncycastle.asn1.x509.KeyUsage
import org.bouncycastle.asn1.x509.SubjectKeyIdentifier
import org.bouncycastle.cert.jcajce.JcaX509ExtensionUtils
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.security.*
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.util.*

object RpCertificateService {

    init {
        if (Security.getProvider("BC") == null) {
            Security.addProvider(BouncyCastleProvider())
        }
    }

    data class GeneratedCertificate(
        val certificate: X509Certificate,
        val keyPair: KeyPair,
        val certInfo: X509CertInfo,
        val privateKeyJwk: JsonObject,
        val x5c: List<String>
    )

    fun generateCertificate(legalName: String, domain: String): GeneratedCertificate {
        val extUtils = JcaX509ExtensionUtils()
        val now = Date()
        val oneYear = Date(now.time + 365L * 24 * 3600 * 1000)

        // Step 1: generate a Root CA (goes into /.well-known/rp-certificates as the trust anchor)
        val caKeyPair = KeyPairGenerator.getInstance("EC", "BC").apply {
            initialize(ECGenParameterSpec("secp256r1"))
        }.generateKeyPair()
        val caSubject = X500Name("CN=$domain RP CA")
        val caBuilder = JcaX509v3CertificateBuilder(
            caSubject, BigInteger(160, SecureRandom()), now, oneYear, caSubject, caKeyPair.public
        )
        caBuilder.addExtension(Extension.basicConstraints, true,
            org.bouncycastle.asn1.x509.BasicConstraints(true))
        caBuilder.addExtension(Extension.keyUsage, true,
            KeyUsage(KeyUsage.keyCertSign or KeyUsage.cRLSign))
        caBuilder.addExtension(Extension.subjectKeyIdentifier, false,
            extUtils.createSubjectKeyIdentifier(caKeyPair.public))
        val caCert = JcaX509CertificateConverter().setProvider("BC").getCertificate(
            caBuilder.build(JcaContentSignerBuilder("SHA256withECDSA").setProvider("BC").build(caKeyPair.private))
        )

        // Step 2: generate the leaf cert signed by the Root CA (goes in x5c[0])
        val leafKeyPair = KeyPairGenerator.getInstance("EC", "BC").apply {
            initialize(ECGenParameterSpec("secp256r1"))
        }.generateKeyPair()
        val leafSubject = X500Name("CN=$legalName, O=$legalName")
        val leafBuilder = JcaX509v3CertificateBuilder(
            caSubject, BigInteger(160, SecureRandom()), now, oneYear, leafSubject, leafKeyPair.public
        )
        leafBuilder.addExtension(Extension.basicConstraints, true,
            org.bouncycastle.asn1.x509.BasicConstraints(false))
        leafBuilder.addExtension(Extension.subjectAlternativeName, false,
            GeneralNames(GeneralName(GeneralName.dNSName, domain)))
        leafBuilder.addExtension(Extension.keyUsage, true,
            KeyUsage(KeyUsage.digitalSignature))
        val readerAuthOid = KeyPurposeId.getInstance(ASN1ObjectIdentifier("1.0.18013.5.1.6"))
        leafBuilder.addExtension(Extension.extendedKeyUsage, false,
            ExtendedKeyUsage(readerAuthOid))
        leafBuilder.addExtension(Extension.subjectKeyIdentifier, false,
            extUtils.createSubjectKeyIdentifier(leafKeyPair.public))
        leafBuilder.addExtension(Extension.authorityKeyIdentifier, false,
            extUtils.createAuthorityKeyIdentifier(caCert))
        val leafCert = JcaX509CertificateConverter().setProvider("BC").getCertificate(
            leafBuilder.build(JcaContentSignerBuilder("SHA256withECDSA").setProvider("BC").build(caKeyPair.private))
        )

        val certInfo = extractCertInfo(leafCert)
        val jwk = ecKeyToJwk(leafKeyPair)
        // x5c: leaf first, then CA — wallet validates chain leaf → CA → trust anchor
        val x5c = listOf(
            Base64.getEncoder().encodeToString(leafCert.encoded),
            Base64.getEncoder().encodeToString(caCert.encoded)
        )

        return GeneratedCertificate(leafCert, leafKeyPair, certInfo, jwk, x5c)
    }

    fun extractCertInfo(cert: X509Certificate): X509CertInfo {
        val md = MessageDigest.getInstance("SHA-256")
        val fingerprint = md.digest(cert.encoded).joinToString(":") { "%02X".format(it) }
        return X509CertInfo(
            subject = cert.subjectX500Principal.name,
            issuer = cert.issuerX500Principal.name,
            notBefore = cert.notBefore.toInstant().toString(),
            notAfter = cert.notAfter.toInstant().toString(),
            serialNumber = cert.serialNumber.toString(16),
            fingerprint = fingerprint
        )
    }

    fun parseCertificatePem(pem: String): Pair<X509Certificate, X509CertInfo> {
        val cf = java.security.cert.CertificateFactory.getInstance("X.509")
        val cert = cf.generateCertificate(pem.byteInputStream()) as X509Certificate
        return cert to extractCertInfo(cert)
    }

    fun buildPkcs12(keyPair: KeyPair, cert: X509Certificate, password: String = "changeit"): ByteArray {
        val ks = KeyStore.getInstance("PKCS12")
        ks.load(null, null)
        ks.setKeyEntry("rp-key", keyPair.private, password.toCharArray(), arrayOf(cert))
        val baos = ByteArrayOutputStream()
        ks.store(baos, password.toCharArray())
        return baos.toByteArray()
    }

    private fun ecKeyToJwk(keyPair: KeyPair): JsonObject {
        val ecPublicKey = keyPair.public as java.security.interfaces.ECPublicKey
        val ecPrivateKey = keyPair.private as java.security.interfaces.ECPrivateKey

        val x = ecPublicKey.w.affineX.toByteArray().let { padOrTrimTo32(it) }
        val y = ecPublicKey.w.affineY.toByteArray().let { padOrTrimTo32(it) }
        val d = ecPrivateKey.s.toByteArray().let { padOrTrimTo32(it) }

        return JsonObject(mapOf(
            "kty" to JsonPrimitive("EC"),
            "crv" to JsonPrimitive("P-256"),
            "x" to JsonPrimitive(base64UrlEncode(x)),
            "y" to JsonPrimitive(base64UrlEncode(y)),
            "d" to JsonPrimitive(base64UrlEncode(d))
        ))
    }

    private fun padOrTrimTo32(bytes: ByteArray): ByteArray {
        return when {
            bytes.size == 32 -> bytes
            bytes.size > 32 -> bytes.takeLast(32).toByteArray()
            else -> ByteArray(32 - bytes.size) + bytes
        }
    }

    private fun base64UrlEncode(bytes: ByteArray): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
}
