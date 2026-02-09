package id.walt.etsi.tsl

expect object FileStorage {
    fun readText(path: String): String?
    fun writeText(path: String, content: String)
    fun mkdirs(path: String)
}
