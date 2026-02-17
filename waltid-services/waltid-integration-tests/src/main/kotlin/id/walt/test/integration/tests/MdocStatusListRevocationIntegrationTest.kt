@file:OptIn(ExperimentalUuidApi::class, ExperimentalTime::class)

package id.walt.test.integration.tests

import id.walt.issuer.issuance.openapi.issuerapi.MdocDocs
import id.walt.test.integration.environment.api.wallet.WalletApi
import io.klogging.Klogging
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.MethodOrderer.OrderAnnotation
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import kotlin.test.*
import kotlin.time.ExperimentalTime
import kotlin.uuid.ExperimentalUuidApi

/**
 * mDoc mDL credential issuance triggers StatusListIssuanceHook which auto-allocates
 * a status list entry for mso_mdoc format. This test verifies the full revocation lifecycle
 * using a dedicated mDoc wallet (COSE key setup) separate from the default SD-JWT wallet.
 */
@TestMethodOrder(OrderAnnotation::class)
class MdocStatusListRevocationIntegrationTest : AbstractIntegrationTest(), Klogging {
    companion object {
        private lateinit var mDocWalletApi: WalletApi

        var offerUrl: String? = null
        var credentialId: String? = null
        var statusListId: String? = null
        var statusListIndex: Int? = null

        @JvmStatic
        @BeforeAll
        fun setupMdocWallet() = runBlocking {
            mDocWalletApi = environment.getMdocWalletApi()
        }
    }

    @Order(0)
    @Test
    fun shouldIssueMdocCredential() = runTest {
        offerUrl = issuerApi.issueMdocCredential(MdocDocs.mdlBaseIssuanceExample)
        assertNotNull(offerUrl)
    }

    @Order(1)
    @Test
    fun shouldClaimMdocCredential() = runTest {
        assertNotNull(offerUrl, "mDoc offer URL should be set")
        val claimed = mDocWalletApi.claimCredential(offerUrl!!)
        assertNotNull(claimed)
        assertEquals(1, claimed.size)
        credentialId = claimed[0].id
        assertNotNull(credentialId)
    }

    @Order(2)
    @Test
    fun shouldHaveMdocStatusListEntry() = runTest {
        val lists = issuerApi.listStatusLists()
        assertTrue(lists.isNotEmpty(), "Should have at least one status list after mDoc issuance")

        val entries = issuerApi.searchStatusListEntries("org.iso.18013.5.1.mDL")
        assertTrue(entries.isNotEmpty(), "Should have status list entries for issued mDoc credential")

        val lastEntry = entries.last()
        statusListId = lastEntry.listId
        statusListIndex = lastEntry.entry.index
        assertNotNull(statusListId)
        assertNotNull(statusListIndex)
        assertFalse(lastEntry.entry.revoked, "Newly issued mDoc credential should not be revoked")
    }

    @Order(3)
    @Test
    fun shouldCallMdocWalletStatusEndpoint() = runTest {
        assertNotNull(credentialId, "mDoc credential ID should be set")
        val statusResults = mDocWalletApi.getCredentialStatus(credentialId!!)
        assertNotNull(statusResults, "Status results should not be null")
        logger.info("mDoc wallet status results: $statusResults")

        val revocationResults = statusResults.filter { it.type == "revocation" }
        for (result in revocationResults) {
            assertFalse(result.result, "Active mDoc credential should not be marked as revoked")
        }
    }

    @Order(4)
    @Test
    fun shouldRevokeMdocCredential() = runTest {
        assertNotNull(statusListId, "mDoc status list ID should be set")
        assertNotNull(statusListIndex, "mDoc status list index should be set")

        val result = issuerApi.revokeEntry(statusListId!!, statusListIndex!!, "Test mDoc revocation")
        assertNotNull(result)
        logger.info("mDoc revoke result: $result")

        val entries = issuerApi.searchStatusListEntries("org.iso.18013.5.1.mDL")
        val revokedEntry = entries.find { it.listId == statusListId && it.entry.index == statusListIndex }
        assertNotNull(revokedEntry, "Should find the revoked mDoc entry")
        assertTrue(revokedEntry.entry.revoked, "mDoc entry should be marked as revoked")
    }

    @Order(5)
    @Test
    fun shouldVerifyMdocRevokedInStatusList() = runTest {
        val entries = issuerApi.searchStatusListEntries("org.iso.18013.5.1.mDL")
        val entry = entries.find { it.listId == statusListId && it.entry.index == statusListIndex }
        assertNotNull(entry, "Should find the mDoc entry")
        assertTrue(entry.entry.revoked, "mDoc entry should be revoked")
        assertEquals("Test mDoc revocation", entry.entry.revokedReason)

        val statusResults = mDocWalletApi.getCredentialStatus(credentialId!!)
        assertNotNull(statusResults)
        logger.info("mDoc wallet status after revoke: $statusResults")
    }

    @Order(6)
    @Test
    fun shouldUnrevokeMdocCredential() = runTest {
        assertNotNull(statusListId, "mDoc status list ID should be set")
        assertNotNull(statusListIndex, "mDoc status list index should be set")

        val result = issuerApi.unrevokeEntry(statusListId!!, statusListIndex!!)
        assertNotNull(result)
        logger.info("mDoc unrevoke result: $result")

        val entries = issuerApi.searchStatusListEntries("org.iso.18013.5.1.mDL")
        val entry = entries.find { it.listId == statusListId && it.entry.index == statusListIndex }
        assertNotNull(entry, "Should find the mDoc entry")
        assertFalse(entry.entry.revoked, "mDoc entry should no longer be revoked")
    }

    @Order(7)
    @Test
    fun shouldVerifyMdocUnrevokedInStatusList() = runTest {
        val entries = issuerApi.searchStatusListEntries("org.iso.18013.5.1.mDL")
        val entry = entries.find { it.listId == statusListId && it.entry.index == statusListIndex }
        assertNotNull(entry, "Should find the mDoc entry")
        assertFalse(entry.entry.revoked, "mDoc entry should be active after unrevoke")

        val statusResults = mDocWalletApi.getCredentialStatus(credentialId!!)
        assertNotNull(statusResults)
        logger.info("mDoc wallet status after unrevoke: $statusResults")
    }

    @Order(8)
    @Test
    fun shouldCleanupMdoc() = runTest {
        assertNotNull(credentialId, "mDoc credential ID should be set")
        mDocWalletApi.deleteCredential(credentialId!!, permanent = true)
    }
}
