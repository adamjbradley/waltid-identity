package id.walt.etsi.tsl

import java.io.File

actual object FileStorage {
    actual fun readText(path: String): String? {
        val file = File(path)
        return if (file.exists()) file.readText() else null
    }

    actual fun writeText(path: String, content: String) {
        File(path).writeText(content)
    }

    actual fun mkdirs(path: String) {
        File(path).mkdirs()
    }
}
