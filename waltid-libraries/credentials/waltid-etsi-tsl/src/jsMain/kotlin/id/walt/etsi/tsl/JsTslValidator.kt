package id.walt.etsi.tsl

import io.github.oshai.kotlinlogging.KotlinLogging

private val log = KotlinLogging.logger {}

actual object TslValidator {
    actual fun validateSignature(xmlContent: String): Boolean {
        log.warn { "XMLDSig validation not available on JS platform - skipping signature check" }
        return true
    }
}
