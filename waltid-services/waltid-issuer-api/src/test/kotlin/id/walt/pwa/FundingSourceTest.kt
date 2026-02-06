package id.walt.pwa

import id.walt.issuer.psp.FundingSource
import id.walt.issuer.psp.FundingSourceType
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Tests for FundingSource data model and credential data conversion.
 */
class FundingSourceTest {

    @Test
    fun testCardFundingSourceToCredentialData() {
        val source = FundingSource(
            credentialIdentifier = "pwa_visa_1234",
            type = FundingSourceType.CARD,
            panLastFour = "1234",
            iin = "411111",
            scheme = "visa",
            currency = "EUR",
            icon = "https://example.com/visa-icon.png"
        )

        val data = source.toCredentialData()
        val fundingSource = data["funding_source"]?.jsonObject

        assertNotNull(fundingSource, "funding_source should be present")
        assertEquals("card", fundingSource["type"]?.jsonPrimitive?.content)
        assertEquals("1234", fundingSource["pan_last_four"]?.jsonPrimitive?.content)
        assertEquals("411111", fundingSource["iin"]?.jsonPrimitive?.content)
        assertEquals("visa", fundingSource["scheme"]?.jsonPrimitive?.content)
        assertEquals("EUR", fundingSource["currency"]?.jsonPrimitive?.content)
        assertEquals("https://example.com/visa-icon.png", fundingSource["icon"]?.jsonPrimitive?.content)
        assertNull(fundingSource["iban_last_four"], "iban_last_four should not be present for card")
        assertNull(fundingSource["bic"], "bic should not be present for card")
    }

    @Test
    fun testAccountFundingSourceToCredentialData() {
        val source = FundingSource(
            credentialIdentifier = "pwa_sepa_5678",
            type = FundingSourceType.ACCOUNT,
            ibanLastFour = "5678",
            bic = "DEUTDEFF",
            scheme = "sepa",
            currency = "EUR"
        )

        val data = source.toCredentialData()
        val fundingSource = data["funding_source"]?.jsonObject

        assertNotNull(fundingSource, "funding_source should be present")
        assertEquals("account", fundingSource["type"]?.jsonPrimitive?.content)
        assertEquals("5678", fundingSource["iban_last_four"]?.jsonPrimitive?.content)
        assertEquals("DEUTDEFF", fundingSource["bic"]?.jsonPrimitive?.content)
        assertEquals("sepa", fundingSource["scheme"]?.jsonPrimitive?.content)
        assertEquals("EUR", fundingSource["currency"]?.jsonPrimitive?.content)
        assertNull(fundingSource["pan_last_four"], "pan_last_four should not be present for account")
        assertNull(fundingSource["iin"], "iin should not be present for account")
    }

    @Test
    fun testMinimalFundingSourceToCredentialData() {
        val source = FundingSource(
            credentialIdentifier = "pwa_any_0001",
            type = FundingSourceType.ANY
        )

        val data = source.toCredentialData()
        val fundingSource = data["funding_source"]?.jsonObject

        assertNotNull(fundingSource, "funding_source should be present")
        assertEquals("any", fundingSource["type"]?.jsonPrimitive?.content)
    }

    @Test
    fun testFundingSourceWithAliasId() {
        val source = FundingSource(
            credentialIdentifier = "pwa_card_alias",
            type = FundingSourceType.CARD,
            aliasId = "user-card-alias-123"
        )

        val data = source.toCredentialData()
        val fundingSource = data["funding_source"]?.jsonObject

        assertNotNull(fundingSource, "funding_source should be present")
        assertEquals("user-card-alias-123", fundingSource["alias_id"]?.jsonPrimitive?.content)
    }
}
