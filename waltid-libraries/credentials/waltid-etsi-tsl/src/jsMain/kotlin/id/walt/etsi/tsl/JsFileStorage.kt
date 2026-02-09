package id.walt.etsi.tsl

actual object FileStorage {
    actual fun readText(path: String): String? = null
    actual fun writeText(path: String, content: String) {}
    actual fun mkdirs(path: String) {}
}
