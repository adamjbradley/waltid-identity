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
 * issue -> claim -> verify entry -> revoke -> verify revoked -> unrevoke -> verify active -> cleanup
 *
 * Tests cover both VC+SD-JWT (orders 0-8) and DC+SD-JWT (orders 9-16) formats.
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

private val dcSdJwtCredential = IssuanceRequest(
    issuerKey = loadJsonResource("issuance/key.json"),
    credentialConfigurationId = "urn:eudi:pid:1",
    credentialData = buildJsonObject {
        put("sub", "dc-sdjwt-status-test-user")
        put("family_name", "DcRevoke")
        put("given_name", "Test")
        put("birthdate", "1990-01-01")
    },
    selectiveDisclosure = SDMap(mapOf("birthdate" to SDField(sd = true))),
    issuerDid = loadResource("issuance/did.txt"),
    credentialFormat = CredentialFormat.sd_jwt_dc,
)

@TestMethodOrder(OrderAnnotation::class)
class StatusListRevocationIntegrationTest : AbstractIntegrationTest(), Klogging {
    companion object {
        // VC+SD-JWT state
        var vcOfferUrl: String? = null
        var vcCredentialId: String? = null
        var vcStatusListId: String? = null
        var vcStatusListIndex: Int? = null

        // DC+SD-JWT state
        var dcOfferUrl: String? = null
        var dcCredentialId: String? = null
        var dcStatusListId: String? = null
        var dcStatusListIndex: Int? = null
    }

    // ---- VC+SD-JWT lifecycle (orders 0-8) ----

    @Order(0)
    @Test
    fun shouldIssueSdJwtCredential() = runTest {
        vcOfferUrl = issuerApi.issueSdJwtCredential(sdJwtCredential)
        assertNotNull(vcOfferUrl)
    }

    @Order(1)
    @Test
    fun shouldClaimCredential() = runTest {
        assertNotNull(vcOfferUrl, "Offer URL should be set")
        val claimed = defaultWalletApi.claimCredential(vcOfferUrl!!)
        assertNotNull(claimed)
        assertEquals(1, claimed.size)
        vcCredentialId = claimed[0].id
        assertNotNull(vcCredentialId)
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
        vcStatusListId = lastEntry.listId
        vcStatusListIndex = lastEntry.entry.index
        assertNotNull(vcStatusListId)
        assertNotNull(vcStatusListIndex)
        assertFalse(lastEntry.entry.revoked, "Newly issued credential should not be revoked")
    }

    @Order(3)
    @Test
    fun shouldCallWalletStatusEndpoint() = runTest {
        assertNotNull(vcCredentialId, "Credential ID should be set")
        val statusResults = defaultWalletApi.getCredentialStatus(vcCredentialId!!)
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
        assertNotNull(vcStatusListId, "Status list ID should be set")
        assertNotNull(vcStatusListIndex, "Status list index should be set")

        val result = issuerApi.revokeEntry(vcStatusListId!!, vcStatusListIndex!!, "Test revocation")
        assertNotNull(result)
        logger.info("Revoke result: $result")

        val entries = issuerApi.searchStatusListEntries("identity_credential_vc+sd-jwt")
        val revokedEntry = entries.find { it.listId == vcStatusListId && it.entry.index == vcStatusListIndex }
        assertNotNull(revokedEntry, "Should find the revoked entry")
        assertTrue(revokedEntry.entry.revoked, "Entry should be marked as revoked in issuer")
    }

    @Order(5)
    @Test
    fun shouldVerifyRevokedInStatusList() = runTest {
        assertNotNull(vcStatusListId, "Status list ID should be set")
        assertNotNull(vcStatusListIndex, "Status list index should be set")

        val entries = issuerApi.searchStatusListEntries("identity_credential_vc+sd-jwt")
        val entry = entries.find { it.listId == vcStatusListId && it.entry.index == vcStatusListIndex }
        assertNotNull(entry, "Should find the entry")
        assertTrue(entry.entry.revoked, "Entry should be revoked")
        assertEquals("Test revocation", entry.entry.revokedReason)

        val statusResults = defaultWalletApi.getCredentialStatus(vcCredentialId!!)
        assertNotNull(statusResults)
        logger.info("Wallet status after revoke: $statusResults")
    }

    @Order(6)
    @Test
    fun shouldUnrevokeCredential() = runTest {
        assertNotNull(vcStatusListId, "Status list ID should be set")
        assertNotNull(vcStatusListIndex, "Status list index should be set")

        val result = issuerApi.unrevokeEntry(vcStatusListId!!, vcStatusListIndex!!)
        assertNotNull(result)
        logger.info("Unrevoke result: $result")

        val entries = issuerApi.searchStatusListEntries("identity_credential_vc+sd-jwt")
        val entry = entries.find { it.listId == vcStatusListId && it.entry.index == vcStatusListIndex }
        assertNotNull(entry, "Should find the entry")
        assertFalse(entry.entry.revoked, "Entry should no longer be revoked")
    }

    @Order(7)
    @Test
    fun shouldVerifyUnrevokedInStatusList() = runTest {
        assertNotNull(vcStatusListId, "Status list ID should be set")
        assertNotNull(vcStatusListIndex, "Status list index should be set")

        val entries = issuerApi.searchStatusListEntries("identity_credential_vc+sd-jwt")
        val entry = entries.find { it.listId == vcStatusListId && it.entry.index == vcStatusListIndex }
        assertNotNull(entry, "Should find the entry")
        assertFalse(entry.entry.revoked, "Entry should be active after unrevoke")

        val statusResults = defaultWalletApi.getCredentialStatus(vcCredentialId!!)
        assertNotNull(statusResults)
        logger.info("Wallet status after unrevoke: $statusResults")
    }

    @Order(8)
    @Test
    fun shouldCleanup() = runTest {
        assertNotNull(vcCredentialId, "Credential ID should be set")
        defaultWalletApi.deleteCredential(vcCredentialId!!, permanent = true)
    }

    // ---- DC+SD-JWT lifecycle (orders 9-16) ----

    @Order(9)
    @Test
    fun shouldIssueDcSdJwtCredential() = runTest {
        dcOfferUrl = issuerApi.issueSdJwtCredential(dcSdJwtCredential)
        assertNotNull(dcOfferUrl)
    }

    @Order(10)
    @Test
    fun shouldClaimDcSdJwtCredential() = runTest {
        assertNotNull(dcOfferUrl, "DC+SD-JWT offer URL should be set")
        val claimed = defaultWalletApi.claimCredential(dcOfferUrl!!)
        assertNotNull(claimed)
        assertEquals(1, claimed.size)
        dcCredentialId = claimed[0].id
        assertNotNull(dcCredentialId)
    }

    @Order(11)
    @Test
    fun shouldHaveDcSdJwtStatusListEntry() = runTest {
        val entries = issuerApi.searchStatusListEntries("urn:eudi:pid:1")
        assertTrue(entries.isNotEmpty(), "Should have status list entries for issued DC+SD-JWT credential")

        val lastEntry = entries.last()
        dcStatusListId = lastEntry.listId
        dcStatusListIndex = lastEntry.entry.index
        assertNotNull(dcStatusListId)
        assertNotNull(dcStatusListIndex)
        assertFalse(lastEntry.entry.revoked, "Newly issued DC+SD-JWT credential should not be revoked")
    }

    @Order(12)
    @Test
    fun shouldCallDcSdJwtWalletStatusEndpoint() = runTest {
        assertNotNull(dcCredentialId, "DC+SD-JWT credential ID should be set")
        val statusResults = defaultWalletApi.getCredentialStatus(dcCredentialId!!)
        assertNotNull(statusResults, "Status results should not be null")
        logger.info("DC+SD-JWT wallet status results: $statusResults")

        val revocationResults = statusResults.filter { it.type == "revocation" }
        for (result in revocationResults) {
            assertFalse(result.result, "Active DC+SD-JWT credential should not be marked as revoked")
        }
    }

    @Order(13)
    @Test
    fun shouldRevokeDcSdJwtCredential() = runTest {
        assertNotNull(dcStatusListId, "DC+SD-JWT status list ID should be set")
        assertNotNull(dcStatusListIndex, "DC+SD-JWT status list index should be set")

        val result = issuerApi.revokeEntry(dcStatusListId!!, dcStatusListIndex!!, "Test DC+SD-JWT revocation")
        assertNotNull(result)
        logger.info("DC+SD-JWT revoke result: $result")

        val entries = issuerApi.searchStatusListEntries("urn:eudi:pid:1")
        val revokedEntry = entries.find { it.listId == dcStatusListId && it.entry.index == dcStatusListIndex }
        assertNotNull(revokedEntry, "Should find the revoked DC+SD-JWT entry")
        assertTrue(revokedEntry.entry.revoked, "DC+SD-JWT entry should be marked as revoked")
    }

    @Order(14)
    @Test
    fun shouldVerifyDcSdJwtRevokedInStatusList() = runTest {
        val entries = issuerApi.searchStatusListEntries("urn:eudi:pid:1")
        val entry = entries.find { it.listId == dcStatusListId && it.entry.index == dcStatusListIndex }
        assertNotNull(entry, "Should find the DC+SD-JWT entry")
        assertTrue(entry.entry.revoked, "DC+SD-JWT entry should be revoked")
        assertEquals("Test DC+SD-JWT revocation", entry.entry.revokedReason)

        val statusResults = defaultWalletApi.getCredentialStatus(dcCredentialId!!)
        assertNotNull(statusResults)
        logger.info("DC+SD-JWT wallet status after revoke: $statusResults")
    }

    @Order(15)
    @Test
    fun shouldUnrevokeDcSdJwtCredential() = runTest {
        assertNotNull(dcStatusListId, "DC+SD-JWT status list ID should be set")
        assertNotNull(dcStatusListIndex, "DC+SD-JWT status list index should be set")

        val result = issuerApi.unrevokeEntry(dcStatusListId!!, dcStatusListIndex!!)
        assertNotNull(result)
        logger.info("DC+SD-JWT unrevoke result: $result")

        val entries = issuerApi.searchStatusListEntries("urn:eudi:pid:1")
        val entry = entries.find { it.listId == dcStatusListId && it.entry.index == dcStatusListIndex }
        assertNotNull(entry, "Should find the DC+SD-JWT entry")
        assertFalse(entry.entry.revoked, "DC+SD-JWT entry should no longer be revoked")
    }

    @Order(16)
    @Test
    fun shouldCleanupDcSdJwt() = runTest {
        assertNotNull(dcCredentialId, "DC+SD-JWT credential ID should be set")
        defaultWalletApi.deleteCredential(dcCredentialId!!, permanent = true)
    }
}
