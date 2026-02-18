@file:OptIn(ExperimentalUuidApi::class, ExperimentalTime::class)

package id.walt.test.integration.tests

import id.walt.commons.testing.utils.ServiceTestUtils.loadResource
import id.walt.issuer.issuance.IssuanceRequest
import id.walt.oid4vc.data.CredentialFormat
import id.walt.sdjwt.SDField
import id.walt.sdjwt.SDMap
import id.walt.test.integration.loadJsonResource
import io.klogging.Klogging
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.MethodOrderer.OrderAnnotation
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import kotlin.test.*
import kotlin.time.ExperimentalTime
import kotlin.uuid.ExperimentalUuidApi

/**
 * SD-JWT credential issuance triggers StatusListIssuanceHook which auto-allocates
 * a status list entry. This test verifies the full revocation lifecycle:
 * issue → claim → verify entry → revoke → verify revoked → unrevoke → verify active → cleanup
 */
private val sdJwtCredential = IssuanceRequest(
    issuerKey = loadJsonResource("issuance/key.json"),
    credentialConfigurationId = "identity_credential_vc+sd-jwt",
    credentialData = buildJsonObject {
        put("sub", "status-list-test-user")
        put("family_name", "Revoke")
        put("given_name", "Test")
        put("birthdate", "1990-01-01")
    },
    selectiveDisclosure = SDMap(mapOf("birthdate" to SDField(sd = true))),
    issuerDid = loadResource("issuance/did.txt"),
    credentialFormat = CredentialFormat.sd_jwt_vc,
)

@TestMethodOrder(OrderAnnotation::class)
class StatusListRevocationIntegrationTest : AbstractIntegrationTest(), Klogging {
    companion object {
        var offerUrl: String? = null
        var credentialId: String? = null
        var statusListId: String? = null
        var statusListIndex: Int? = null
    }

    @Order(0)
    @Test
    fun shouldIssueSdJwtCredential() = runTest {
        offerUrl = issuerApi.issueSdJwtCredential(sdJwtCredential)
        assertNotNull(offerUrl)
    }

    @Order(1)
    @Test
    fun shouldClaimCredential() = runTest {
        assertNotNull(offerUrl, "Offer URL should be set")
        val claimed = defaultWalletApi.claimCredential(offerUrl!!)
        assertNotNull(claimed)
        assertEquals(1, claimed.size)
        credentialId = claimed[0].id
        assertNotNull(credentialId)
    }

    @Order(2)
    @Test
    fun shouldHaveStatusListEntry() = runTest {
        val lists = issuerApi.listStatusLists()
        assertTrue(lists.isNotEmpty(), "Should have at least one status list after SD-JWT issuance")
        assertTrue(lists.any { it.totalIssued > 0 }, "At least one list should have issued entries")

        val entries = issuerApi.searchStatusListEntries("identity_credential_vc+sd-jwt")
        assertTrue(entries.isNotEmpty(), "Should have status list entries for issued SD-JWT credential")

        val lastEntry = entries.last()
        statusListId = lastEntry.listId
        statusListIndex = lastEntry.entry.index
        assertNotNull(statusListId)
        assertNotNull(statusListIndex)
        assertFalse(lastEntry.entry.revoked, "Newly issued credential should not be revoked")
    }

    @Order(3)
    @Test
    fun shouldCallWalletStatusEndpoint() = runTest {
        assertNotNull(credentialId, "Credential ID should be set")
        val statusResults = defaultWalletApi.getCredentialStatus(credentialId!!)
        assertNotNull(statusResults, "Status results should not be null")
        logger.info("Wallet status results for credential: $statusResults")

        val revocationResults = statusResults.filter { it.type == "revocation" }
        for (result in revocationResults) {
            assertFalse(result.result, "Active credential should not be marked as revoked")
        }
    }

    @Order(4)
    @Test
    fun shouldRevokeCredential() = runTest {
        assertNotNull(statusListId, "Status list ID should be set")
        assertNotNull(statusListIndex, "Status list index should be set")

        val result = issuerApi.revokeEntry(statusListId!!, statusListIndex!!, "Test revocation")
        assertNotNull(result)
        logger.info("Revoke result: $result")

        val entries = issuerApi.searchStatusListEntries("identity_credential_vc+sd-jwt")
        val revokedEntry = entries.find { it.listId == statusListId && it.entry.index == statusListIndex }
        assertNotNull(revokedEntry, "Should find the revoked entry")
        assertTrue(revokedEntry.entry.revoked, "Entry should be marked as revoked in issuer")
    }

    @Order(5)
    @Test
    fun shouldVerifyRevokedInStatusList() = runTest {
        assertNotNull(statusListId, "Status list ID should be set")
        assertNotNull(statusListIndex, "Status list index should be set")

        val entries = issuerApi.searchStatusListEntries("identity_credential_vc+sd-jwt")
        val entry = entries.find { it.listId == statusListId && it.entry.index == statusListIndex }
        assertNotNull(entry, "Should find the entry")
        assertTrue(entry.entry.revoked, "Entry should be revoked")
        assertEquals("Test revocation", entry.entry.revokedReason)

        val statusResults = defaultWalletApi.getCredentialStatus(credentialId!!)
        assertNotNull(statusResults)
        logger.info("Wallet status after revoke: $statusResults")
    }

    @Order(6)
    @Test
    fun shouldUnrevokeCredential() = runTest {
        assertNotNull(statusListId, "Status list ID should be set")
        assertNotNull(statusListIndex, "Status list index should be set")

        val result = issuerApi.unrevokeEntry(statusListId!!, statusListIndex!!)
        assertNotNull(result)
        logger.info("Unrevoke result: $result")

        val entries = issuerApi.searchStatusListEntries("identity_credential_vc+sd-jwt")
        val entry = entries.find { it.listId == statusListId && it.entry.index == statusListIndex }
        assertNotNull(entry, "Should find the entry")
        assertFalse(entry.entry.revoked, "Entry should no longer be revoked")
    }

    @Order(7)
    @Test
    fun shouldVerifyUnrevokedInStatusList() = runTest {
        assertNotNull(statusListId, "Status list ID should be set")
        assertNotNull(statusListIndex, "Status list index should be set")

        val entries = issuerApi.searchStatusListEntries("identity_credential_vc+sd-jwt")
        val entry = entries.find { it.listId == statusListId && it.entry.index == statusListIndex }
        assertNotNull(entry, "Should find the entry")
        assertFalse(entry.entry.revoked, "Entry should be active after unrevoke")

        val statusResults = defaultWalletApi.getCredentialStatus(credentialId!!)
        assertNotNull(statusResults)
        logger.info("Wallet status after unrevoke: $statusResults")
    }

    @Order(8)
    @Test
    fun shouldCleanup() = runTest {
        assertNotNull(credentialId, "Credential ID should be set")
        defaultWalletApi.deleteCredential(credentialId!!, permanent = true)
    }
}
