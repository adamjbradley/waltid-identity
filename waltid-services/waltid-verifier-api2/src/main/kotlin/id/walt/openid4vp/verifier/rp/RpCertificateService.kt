package id.walt.openid4vp.verifier.rp

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.GeneralName
import org.bouncycastle.asn1.x509.GeneralNames
import org.bouncycastle.asn1.x509.KeyUsage
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
        val keyPair = KeyPairGenerator.getInstance("EC", "BC").apply {
            initialize(ECGenParameterSpec("secp256r1"))
        }.generateKeyPair()

        val now = Date()
        val oneYear = Date(now.time + 365L * 24 * 3600 * 1000)

        val subject = X500Name("CN=$legalName, O=$legalName")

        val builder = JcaX509v3CertificateBuilder(
            subject, // self-signed: issuer == subject
            BigInteger(160, SecureRandom()),
            now,
            oneYear,
            subject,
            keyPair.public
        )

        // Add SAN extension with dNSName
        builder.addExtension(
            Extension.subjectAlternativeName,
            false,
            GeneralNames(GeneralName(GeneralName.dNSName, domain))
        )

        builder.addExtension(
            Extension.keyUsage,
            true,
            KeyUsage(KeyUsage.digitalSignature)
        )

        val signer = JcaContentSignerBuilder("SHA256withECDSA").setProvider("BC").build(keyPair.private)
        val cert = JcaX509CertificateConverter().setProvider("BC").getCertificate(builder.build(signer))

        val certInfo = extractCertInfo(cert)
        val jwk = ecKeyToJwk(keyPair)
        val x5c = listOf(Base64.getEncoder().encodeToString(cert.encoded))

        return GeneratedCertificate(cert, keyPair, certInfo, jwk, x5c)
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
            "type" to JsonPrimitive("jwk"),
            "jwk" to JsonObject(mapOf(
                "kty" to JsonPrimitive("EC"),
                "crv" to JsonPrimitive("P-256"),
                "x" to JsonPrimitive(base64UrlEncode(x)),
                "y" to JsonPrimitive(base64UrlEncode(y)),
                "d" to JsonPrimitive(base64UrlEncode(d))
            ))
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
