package id.walt.issuer.psp

import io.github.oshai.kotlinlogging.KotlinLogging

/**
 * Mock PSP adapter for testing Payment Wallet Attestation flows.
 *
 * Returns sample funding sources for any authenticated user.
 * In production, replace with a real PSP integration that connects
 * to your payment infrastructure.
 */
class MockPspAdapter : PspAdapter {

    private val log = KotlinLogging.logger { }

    /**
     * Sample funding sources for testing.
     * These represent a typical user with one card and one bank account.
     */
    private val mockFundingSources = listOf(
        FundingSource(
            credentialIdentifier = "pwa_visa_1234",
            type = FundingSourceType.CARD,
            panLastFour = "1234",
            iin = "411111",
            scheme = "visa",
            currency = "EUR",
            icon = "https://example.com/visa-icon.png"
        ),
        FundingSource(
            credentialIdentifier = "pwa_mastercard_5678",
            type = FundingSourceType.CARD,
            panLastFour = "5678",
            iin = "555555",
            scheme = "mastercard",
            currency = "EUR",
            icon = "https://example.com/mastercard-icon.png"
        ),
        FundingSource(
            credentialIdentifier = "pwa_sepa_9012",
            type = FundingSourceType.ACCOUNT,
            ibanLastFour = "9012",
            bic = "DEUTDEFF",
            scheme = "sepa",
            currency = "EUR",
            icon = "https://example.com/sepa-icon.png"
        )
    )

    override suspend fun resolveFundingSources(
        subject: String,
        attestationIssuer: String?
    ): List<FundingSource> {
        log.debug { "MockPspAdapter: Resolving funding sources for subject=$subject, attestationIssuer=$attestationIssuer" }
        // In a real implementation, this would query the PSP's backend
        // to get the user's actual payment instruments
        return mockFundingSources
    }

    override suspend fun validateFundingSource(fundingSourceId: String): Boolean {
        log.debug { "MockPspAdapter: Validating funding source $fundingSourceId" }
        // In a real implementation, this would check if the card/account is still active
        return mockFundingSources.any { it.credentialIdentifier == fundingSourceId }
    }

    override suspend fun getFundingSource(fundingSourceId: String): FundingSource? {
        log.debug { "MockPspAdapter: Getting funding source $fundingSourceId" }
        return mockFundingSources.find { it.credentialIdentifier == fundingSourceId }
    }
}
