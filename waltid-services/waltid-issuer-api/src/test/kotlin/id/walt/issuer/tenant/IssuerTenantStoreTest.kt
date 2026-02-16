package id.walt.issuer.tenant

import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IssuerTenantStoreTest {

    @TempDir
    lateinit var tempDir: File

    private lateinit var store: IssuerTenantStore

    @BeforeEach
    fun setUp() {
        store = IssuerTenantStore(tempDir)
        store.init()
    }

    @AfterEach
    fun tearDown() {
        IssuerTenantStore.resetForTesting()
    }

    private fun createTenant(
        id: String = "test-id",
        legalName: String = "Test Issuer",
        domain: String = "issuer.example.com",
        country: String = "AU"
    ) = IssuerTenant(
        id = id,
        legalName = legalName,
        country = country,
        domain = domain,
        contactEmail = "admin@example.com",
        status = IssuerTenantStatus.ACTIVE,
        createdAt = "2026-01-01T00:00:00Z",
        updatedAt = "2026-01-01T00:00:00Z"
    )

    @Test
    fun `save and retrieve tenant by id`() {
        val tenant = createTenant()
        store.save(tenant)

        val retrieved = store.get("test-id")
        assertNotNull(retrieved)
        assertEquals("Test Issuer", retrieved.legalName)
        assertEquals("issuer.example.com", retrieved.domain)
        assertEquals("AU", retrieved.country)
    }

    @Test
    fun `list returns all saved tenants`() {
        store.save(createTenant(id = "t-1", domain = "one.example.com"))
        store.save(createTenant(id = "t-2", domain = "two.example.com"))
        store.save(createTenant(id = "t-3", domain = "three.example.com"))

        val list = store.list()
        assertEquals(3, list.size)
    }

    @Test
    fun `delete removes tenant and its JSON file`() {
        val tenant = createTenant()
        store.save(tenant)

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
    fun `findByDomain returns matching tenant`() {
        store.save(createTenant(domain = "find-me.example.com"))

        val found = store.findByDomain("find-me.example.com")
        assertNotNull(found)
        assertEquals("find-me.example.com", found.domain)
    }

    @Test
    fun `findByDomain is case insensitive`() {
        store.save(createTenant(domain = "Test.Example.COM"))

        val found = store.findByDomain("test.example.com")
        assertNotNull(found)
    }

    @Test
    fun `findByDomain returns null for unknown domain`() {
        store.save(createTenant(domain = "known.example.com"))

        val found = store.findByDomain("unknown.example.com")
        assertNull(found)
    }

    @Test
    fun `save overwrites existing tenant`() {
        store.save(createTenant(legalName = "Original Name"))
        store.save(createTenant(legalName = "Updated Name"))

        val retrieved = store.get("test-id")
        assertNotNull(retrieved)
        assertEquals("Updated Name", retrieved.legalName)
        assertEquals(1, store.list().size)
    }

    @Test
    fun `init loads existing JSON files from directory`() {
        store.save(createTenant(id = "persist-1", domain = "persist1.example.com"))
        store.save(createTenant(id = "persist-2", domain = "persist2.example.com"))

        // Create a new store pointing at the same directory
        val newStore = IssuerTenantStore(tempDir)
        newStore.init()

        assertEquals(2, newStore.list().size)
        assertNotNull(newStore.get("persist-1"))
        assertNotNull(newStore.get("persist-2"))
    }

    @Test
    fun `get returns null for unknown id`() {
        assertNull(store.get("nonexistent"))
    }

    @Test
    fun `tenant with certificate data persists correctly`() {
        val tenant = createTenant().copy(
            issuerKey = JsonObject(mapOf()),
            x5Chain = listOf("base64cert1", "base64cert2"),
            iacaCertificate = X509CertInfo(
                subject = "CN=Test IACA",
                issuer = "CN=Test IACA",
                notBefore = "2026-01-01",
                notAfter = "2031-01-01",
                serialNumber = "123",
                fingerprint = "AA:BB:CC"
            ),
            signerCertificate = X509CertInfo(
                subject = "CN=Test Signer",
                issuer = "CN=Test IACA",
                notBefore = "2026-01-01",
                notAfter = "2027-01-01",
                serialNumber = "456",
                fingerprint = "DD:EE:FF"
            ),
            ciTokenKey = "some-token-key-json"
        )
        store.save(tenant)

        val newStore = IssuerTenantStore(tempDir)
        newStore.init()
        val retrieved = newStore.get("test-id")

        assertNotNull(retrieved)
        assertNotNull(retrieved.issuerKey)
        assertEquals(2, retrieved.x5Chain!!.size)
        assertEquals("CN=Test IACA", retrieved.iacaCertificate!!.subject)
        assertEquals("CN=Test Signer", retrieved.signerCertificate!!.subject)
        assertEquals("some-token-key-json", retrieved.ciTokenKey)
    }

    @Test
    fun `companion init sets singleton instance`() {
        IssuerTenantStore.resetForTesting()
        assertNull(IssuerTenantStore.instanceOrNull())

        IssuerTenantStore.init(tempDir.absolutePath)
        assertNotNull(IssuerTenantStore.instanceOrNull())
    }
}
