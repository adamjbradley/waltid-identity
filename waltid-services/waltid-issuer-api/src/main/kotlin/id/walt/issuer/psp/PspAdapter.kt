package id.walt.issuer.psp

/**
 * Payment Service Provider (PSP) adapter interface for resolving funding sources.
 *
 * This interface defines the contract for PSP integrations per EWC RFC007.
 * PSPs implement this to provide their funding source resolution logic.
 *
 * The adapter is invoked during the authorization flow to resolve which
 * payment instruments (cards, accounts) the authenticated user has available,
 * and during credential issuance to validate and retrieve funding source details.
 */
interface PspAdapter {
    /**
     * Resolve the user's funding sources after authentication.
     *
     * This is called after the user successfully authenticates to determine
     * which payment instruments they can bind to their wallet.
     *
     * @param subject Authenticated user identifier (from authentication flow)
     * @param attestationIssuer Wallet provider identifier (from Wallet Unit Attestation, if available)
     * @return List of funding sources available to the user
     */
    suspend fun resolveFundingSources(
        subject: String,
        attestationIssuer: String? = null
    ): List<FundingSource>

    /**
     * Validate that a funding source is still active and can be issued.
     *
     * Called before credential issuance to ensure the funding source
     * is still valid (not expired, not blocked, etc.).
     *
     * @param fundingSourceId The credentialIdentifier of the funding source to validate
     * @return true if the funding source is valid and can be issued, false otherwise
     */
    suspend fun validateFundingSource(fundingSourceId: String): Boolean

    /**
     * Get a specific funding source by its identifier.
     *
     * @param fundingSourceId The credentialIdentifier of the funding source
     * @return The funding source if found, null otherwise
     */
    suspend fun getFundingSource(fundingSourceId: String): FundingSource?
}
