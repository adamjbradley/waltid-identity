package id.walt.issuer.statuslist

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.Deflater
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import java.util.zip.Inflater

object BitstringManager {

    // ── W3C Bitstring Status List (GZIP + Base64, MSB-first) ──

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

    // ── IETF Token Status List (DEFLATE/ZLIB + base64url, LSB-first) ──
    // Per draft-ietf-oauth-status-list-17

    fun createEmptyIetf(bitCount: Int, bitsPerStatus: Int = 1): String {
        val byteCount = (bitCount * bitsPerStatus + 7) / 8
        val bytes = ByteArray(byteCount)
        return compressIetf(bytes)
    }

    fun setStatusIetf(encodedList: String, index: Int, statusValue: Int, bitsPerStatus: Int = 1): String {
        val bytes = decompressIetf(encodedList)
        val bitIndex = index * bitsPerStatus
        val byteIndex = bitIndex / 8
        val bitOffset = bitIndex % 8
        val mask = ((1 shl bitsPerStatus) - 1) shl bitOffset
        bytes[byteIndex] = ((bytes[byteIndex].toInt() and mask.inv()) or
                ((statusValue and ((1 shl bitsPerStatus) - 1)) shl bitOffset)).toByte()
        return compressIetf(bytes)
    }

    fun getStatusIetf(encodedList: String, index: Int, bitsPerStatus: Int = 1): Int {
        val bytes = decompressIetf(encodedList)
        val bitIndex = index * bitsPerStatus
        val byteIndex = bitIndex / 8
        val bitOffset = bitIndex % 8
        return (bytes[byteIndex].toInt() shr bitOffset) and ((1 shl bitsPerStatus) - 1)
    }

    private fun compressIetf(data: ByteArray): String {
        val deflater = Deflater()
        deflater.setInput(data)
        deflater.finish()
        val bos = ByteArrayOutputStream()
        val buf = ByteArray(1024)
        while (!deflater.finished()) {
            val count = deflater.deflate(buf)
            bos.write(buf, 0, count)
        }
        deflater.end()
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bos.toByteArray())
    }

    private fun decompressIetf(encoded: String): ByteArray {
        val compressed = Base64.getUrlDecoder().decode(encoded)
        val inflater = Inflater()
        inflater.setInput(compressed)
        val bos = ByteArrayOutputStream()
        val buf = ByteArray(1024)
        while (!inflater.finished()) {
            val count = inflater.inflate(buf)
            bos.write(buf, 0, count)
        }
        inflater.end()
        return bos.toByteArray()
    }
}
