package id.walt.issuer.statuslist

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

object BitstringManager {

    fun createEmpty(bitCount: Int): String {
        val byteCount = (bitCount + 7) / 8
        val bytes = ByteArray(byteCount)
        return compress(bytes)
    }

    fun setBit(encodedList: String, index: Int, value: Boolean): String {
        val bytes = decompress(encodedList)
        val byteIndex = index / 8
        val bitIndex = index % 8
        if (value) {
            bytes[byteIndex] = (bytes[byteIndex].toInt() or (1 shl (7 - bitIndex))).toByte()
        } else {
            bytes[byteIndex] = (bytes[byteIndex].toInt() and (1 shl (7 - bitIndex)).inv()).toByte()
        }
        return compress(bytes)
    }

    fun getBit(encodedList: String, index: Int): Boolean {
        val bytes = decompress(encodedList)
        val byteIndex = index / 8
        val bitIndex = index % 8
        return (bytes[byteIndex].toInt() shr (7 - bitIndex)) and 1 == 1
    }

    fun countSetBits(encodedList: String): Int {
        val bytes = decompress(encodedList)
        var count = 0
        for (byte in bytes) {
            count += Integer.bitCount(byte.toInt() and 0xFF)
        }
        return count
    }

    private fun compress(data: ByteArray): String {
        val bos = ByteArrayOutputStream()
        GZIPOutputStream(bos).use { it.write(data) }
        return Base64.getEncoder().encodeToString(bos.toByteArray())
    }

    private fun decompress(encoded: String): ByteArray {
        val compressed = Base64.getDecoder().decode(encoded)
        return GZIPInputStream(ByteArrayInputStream(compressed)).use { it.readBytes() }
    }
}
