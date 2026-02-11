package id.walt.issuer.tenant

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.bouncycastle.asn1.ASN1ObjectIdentifier
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.ExtendedKeyUsage
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.KeyPurposeId
import org.bouncycastle.asn1.x509.KeyUsage
import org.bouncycastle.cert.jcajce.JcaX509ExtensionUtils
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.math.BigInteger
import java.security.*
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.util.*

object IssuerCertificateService {

    init {
        if (Security.getProvider("BC") == null) {
            Security.addProvider(BouncyCastleProvider())
        }
    }

    data class GeneratedIssuerCertificates(
        val iacaCertificate: X509Certificate,
        val iacaKeyPair: KeyPair,
        val iacaCertInfo: X509CertInfo,
        val signerCertificate: X509Certificate,
        val signerKeyPair: KeyPair,
        val signerCertInfo: X509CertInfo,
        val issuerKeyJwk: JsonObject,
        val x5Chain: List<String>,
        val ciTokenKeyJwk: JsonObject
    )

    fun generateCertificates(legalName: String, country: String): GeneratedIssuerCertificates {
        // Generate IACA (root CA) key pair
        val iacaKeyPair = generateEcKeyPair()
        // Generate Document Signer key pair
        val signerKeyPair = generateEcKeyPair()
        // Generate separate CI Token signing key
        val ciTokenKeyPair = generateEcKeyPair()

        val now = Date()
        val fiveYears = Date(now.time + 5L * 365 * 24 * 3600 * 1000)
        val oneYear = Date(now.time + 365L * 24 * 3600 * 1000)

        // --- IACA (root CA) certificate ---
        val iacaSubject = X500Name("CN=$legalName IACA, C=$country")
        val extUtils = JcaX509ExtensionUtils()

        val iacaBuilder = JcaX509v3CertificateBuilder(
            iacaSubject, // self-signed: issuer == subject
            BigInteger(160, SecureRandom()),
            now,
            fiveYears,
            iacaSubject,
            iacaKeyPair.public
        )
        iacaBuilder.addExtension(Extension.basicConstraints, true, BasicConstraints(true))
        iacaBuilder.addExtension(
            Extension.keyUsage, true,
            KeyUsage(KeyUsage.keyCertSign or KeyUsage.cRLSign)
        )
        iacaBuilder.addExtension(
            Extension.subjectKeyIdentifier, false,
            extUtils.createSubjectKeyIdentifier(iacaKeyPair.public)
        )
        iacaBuilder.addExtension(
            Extension.authorityKeyIdentifier, false,
            extUtils.createAuthorityKeyIdentifier(iacaKeyPair.public)
        )

        val iacaSigner = JcaContentSignerBuilder("SHA256withECDSA").setProvider("BC").build(iacaKeyPair.private)
        val iacaCert = JcaX509CertificateConverter().setProvider("BC").getCertificate(iacaBuilder.build(iacaSigner))

        // --- Document Signer (leaf) certificate ---
        val signerSubject = X500Name("CN=$legalName Document Signer, C=$country")

        val signerBuilder = JcaX509v3CertificateBuilder(
            iacaSubject, // issuer is IACA
            BigInteger(160, SecureRandom()),
            now,
            oneYear,
            signerSubject,
            signerKeyPair.public
        )
        signerBuilder.addExtension(
            Extension.keyUsage, true,
            KeyUsage(KeyUsage.digitalSignature)
        )
        // ISO 18013-5 mDoc signing EKU
        val mdocSigningOid = KeyPurposeId.getInstance(ASN1ObjectIdentifier("1.0.18013.5.1.2"))
        signerBuilder.addExtension(
            Extension.extendedKeyUsage, false,
            ExtendedKeyUsage(mdocSigningOid)
        )
        signerBuilder.addExtension(
            Extension.subjectKeyIdentifier, false,
            extUtils.createSubjectKeyIdentifier(signerKeyPair.public)
        )
        signerBuilder.addExtension(
            Extension.authorityKeyIdentifier, false,
            extUtils.createAuthorityKeyIdentifier(iacaKeyPair.public)
        )

        // Signed by IACA private key
        val signerSigner = JcaContentSignerBuilder("SHA256withECDSA").setProvider("BC").build(iacaKeyPair.private)
        val signerCert = JcaX509CertificateConverter().setProvider("BC").getCertificate(signerBuilder.build(signerSigner))

        val iacaCertInfo = extractCertInfo(iacaCert)
        val signerCertInfo = extractCertInfo(signerCert)
        val issuerKeyJwk = ecKeyToJwk(signerKeyPair)
        val ciTokenKeyJwk = ecKeyToJwk(ciTokenKeyPair)

        // x5Chain: leaf first, then root
        val x5Chain = listOf(
            Base64.getEncoder().encodeToString(signerCert.encoded),
            Base64.getEncoder().encodeToString(iacaCert.encoded)
        )

        return GeneratedIssuerCertificates(
            iacaCertificate = iacaCert,
            iacaKeyPair = iacaKeyPair,
            iacaCertInfo = iacaCertInfo,
            signerCertificate = signerCert,
            signerKeyPair = signerKeyPair,
            signerCertInfo = signerCertInfo,
            issuerKeyJwk = issuerKeyJwk,
            x5Chain = x5Chain,
            ciTokenKeyJwk = ciTokenKeyJwk
        )
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

    private fun generateEcKeyPair(): KeyPair =
        KeyPairGenerator.getInstance("EC", "BC").apply {
            initialize(ECGenParameterSpec("secp256r1"))
        }.generateKeyPair()

    private fun ecKeyToJwk(keyPair: KeyPair): JsonObject {
        val ecPublicKey = keyPair.public as java.security.interfaces.ECPublicKey
        val ecPrivateKey = keyPair.private as java.security.interfaces.ECPrivateKey

        val x = ecPublicKey.w.affineX.toByteArray().let { padOrTrimTo32(it) }
        val y = ecPublicKey.w.affineY.toByteArray().let { padOrTrimTo32(it) }
        val d = ecPrivateKey.s.toByteArray().let { padOrTrimTo32(it) }

        return JsonObject(
            mapOf(
                "kty" to JsonPrimitive("EC"),
                "crv" to JsonPrimitive("P-256"),
                "x" to JsonPrimitive(base64UrlEncode(x)),
                "y" to JsonPrimitive(base64UrlEncode(y)),
                "d" to JsonPrimitive(base64UrlEncode(d))
            )
        )
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
