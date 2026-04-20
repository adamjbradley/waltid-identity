package id.walt.authop.tokens

import id.walt.crypto.keys.KeyType
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class KeyProviderTest {

    @Test
    fun `generates key when file absent`(@TempDir tmp: Path) = runTest {
        val path = tmp.resolve("signing-key.json")
        val key1 = KeyProvider.loadOrCreate(path)
        assertTrue(path.exists(), "key file should be created on first load")
        assertEquals(KeyType.RSA, key1.keyType)
    }

    @Test
    fun `reuses existing key across calls`(@TempDir tmp: Path) = runTest {
        val path = tmp.resolve("signing-key.json")
        val key1 = KeyProvider.loadOrCreate(path)
        val key2 = KeyProvider.loadOrCreate(path)
        assertEquals(key1.getKeyId(), key2.getKeyId())
    }

    @Test
    fun `fails loudly when key file is empty`(@TempDir tmp: Path) = runTest {
        val path = tmp.resolve("signing-key.json")
        path.writeText("")
        val ex = assertFailsWith<KeyProviderException> { KeyProvider.loadOrCreate(path) }
        assertTrue(
            (ex.message ?: "").contains("empty"),
            "message should mention empty file; was: ${ex.message}"
        )
    }

    @Test
    fun `fails loudly when key file is not valid JWK`(@TempDir tmp: Path) = runTest {
        val path = tmp.resolve("signing-key.json")
        path.writeText("not-a-jwk")
        assertFailsWith<KeyProviderException> { KeyProvider.loadOrCreate(path) }
    }
}
