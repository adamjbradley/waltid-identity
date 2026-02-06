package id.walt.etsi.tsl

import io.github.oshai.kotlinlogging.KotlinLogging
import java.io.ByteArrayInputStream
import java.security.Key
import java.security.cert.X509Certificate
import javax.xml.crypto.AlgorithmMethod
import javax.xml.crypto.KeySelector
import javax.xml.crypto.KeySelectorException
import javax.xml.crypto.KeySelectorResult
import javax.xml.crypto.XMLCryptoContext
import javax.xml.crypto.dsig.XMLSignatureFactory
import javax.xml.crypto.dsig.dom.DOMValidateContext
import javax.xml.crypto.dsig.keyinfo.KeyInfo
import javax.xml.crypto.dsig.keyinfo.X509Data
import javax.xml.parsers.DocumentBuilderFactory

private val log = KotlinLogging.logger {}

actual object TslValidator {
    actual fun validateSignature(xmlContent: String): Boolean {
        return try {
            val dbf = DocumentBuilderFactory.newInstance().apply {
                isNamespaceAware = true
                setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
                setFeature("http://xml.org/sax/features/external-general-entities", false)
                setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            }
            val doc = dbf.newDocumentBuilder().parse(ByteArrayInputStream(xmlContent.toByteArray()))

            val signatureNodes = doc.getElementsByTagNameNS(
                "http://www.w3.org/2000/09/xmldsig#", "Signature"
            )

            if (signatureNodes.length == 0) {
                log.warn { "No XML signature found in TSL" }
                return false
            }

            val fac = XMLSignatureFactory.getInstance("DOM")
            val signatureNode = signatureNodes.item(0)

            val valContext = DOMValidateContext(X509KeySelector(), signatureNode)
            val signature = fac.unmarshalXMLSignature(valContext)
            signature.validate(valContext)
        } catch (e: Exception) {
            log.error(e) { "TSL signature validation failed" }
            false
        }
    }

    private class X509KeySelector : KeySelector() {
        override fun select(
            keyInfo: KeyInfo?,
            purpose: KeySelector.Purpose?,
            method: AlgorithmMethod?,
            context: XMLCryptoContext?
        ): KeySelectorResult {
            if (keyInfo == null) {
                throw KeySelectorException("No KeyInfo found in signature")
            }

            for (xmlStructure in keyInfo.content) {
                if (xmlStructure is X509Data) {
                    for (certObj in xmlStructure.content) {
                        if (certObj is X509Certificate) {
                            val key = certObj.publicKey
                            return KeySelectorResult { key }
                        }
                    }
                }
            }
            throw KeySelectorException("No X509Certificate found in signature")
        }
    }
}
