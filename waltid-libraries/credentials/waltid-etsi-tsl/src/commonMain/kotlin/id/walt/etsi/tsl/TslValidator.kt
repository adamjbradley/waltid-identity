package id.walt.etsi.tsl

expect object TslValidator {
    fun validateSignature(xmlContent: String): Boolean
}
