package id.walt.openid4vp.verifier.rp

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RelyingPartyStoreTest {

    @TempDir
    lateinit var tempDir: File

    private lateinit var store: RelyingPartyStore

    @BeforeEach
    fun setUp() {
        store = RelyingPartyStore(tempDir)
        store.init()
    }

    @AfterEach
    fun tearDown() {
        RelyingPartyStore.resetForTesting()
    }

    private fun createRp(
        id: String = "test-id",
        legalName: String = "Test RP",
        domain: String = "test.example.com",
        country: String = "AU"
    ) = RelyingParty(
        id = id,
        legalName = legalName,
        tradeName = null,
        country = country,
        contactEmail = "test@example.com",
        clientId = "x509_san_dns:$domain",
        domain = domain,
        status = RpStatus.ACTIVE,
        createdAt = "2026-01-01T00:00:00Z",
        updatedAt = "2026-01-01T00:00:00Z"
    )

    @Test
    fun `save and retrieve RP by id`() {
        val rp = createRp()
        store.save(rp)

        val retrieved = store.get("test-id")
        assertNotNull(retrieved)
        assertEquals("Test RP", retrieved.legalName)
        assertEquals("test.example.com", retrieved.domain)
    }

    @Test
    fun `list returns all saved RPs`() {
        store.save(createRp(id = "rp-1", domain = "rp1.example.com"))
        store.save(createRp(id = "rp-2", domain = "rp2.example.com"))
        store.save(createRp(id = "rp-3", domain = "rp3.example.com"))

        val list = store.list()
        assertEquals(3, list.size)
    }

    @Test
    fun `delete removes RP and its JSON file`() {
        val rp = createRp()
        store.save(rp)

        assertTrue(File(tempDir, "test-id.json").exists())

        val deleted = store.delete("test-id")
        assertTrue(deleted)
        assertNull(store.get("test-id"))
        assertTrue(!File(tempDir, "test-id.json").exists())
    }

    @Test
    fun `delete returns false for unknown id`() {
        val deleted = store.delete("nonexistent")
        assertTrue(!deleted)
    }

    @Test
    fun `findByDomain returns matching RP`() {
        store.save(createRp(domain = "find-me.example.com"))

        val found = store.findByDomain("find-me.example.com")
        assertNotNull(found)
        assertEquals("find-me.example.com", found.domain)
    }

    @Test
    fun `findByDomain is case insensitive`() {
        store.save(createRp(domain = "Test.Example.COM"))

        val found = store.findByDomain("test.example.com")
        assertNotNull(found)
    }

    @Test
    fun `findByDomain returns null for unknown domain`() {
        store.save(createRp(domain = "known.example.com"))

        val found = store.findByDomain("unknown.example.com")
        assertNull(found)
    }

    @Test
    fun `save overwrites existing RP`() {
        store.save(createRp(legalName = "Original Name"))
        store.save(createRp(legalName = "Updated Name"))

        val retrieved = store.get("test-id")
        assertNotNull(retrieved)
        assertEquals("Updated Name", retrieved.legalName)
        assertEquals(1, store.list().size)
    }

    @Test
    fun `init loads existing JSON files from directory`() {
        // Save some RPs, creating JSON files
        store.save(createRp(id = "persist-1", domain = "persist1.example.com"))
        store.save(createRp(id = "persist-2", domain = "persist2.example.com"))

        // Create a new store pointing at the same directory
        val newStore = RelyingPartyStore(tempDir)
        newStore.init()

        assertEquals(2, newStore.list().size)
        assertNotNull(newStore.get("persist-1"))
        assertNotNull(newStore.get("persist-2"))
    }
}
