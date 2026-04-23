package id.walt.openid4vp.verifier.verification

import id.walt.credentials.presentations.formats.DcSdJwtPresentation
import id.walt.dcql.models.ClaimsQuery
import id.walt.openid4vp.verifier.verification.Verifier2PresentationValidator.PresentationValidationResult

object SdJwtVcPresentationValidator {

    /**
     * Validates a full SD-JWT VC presentation string (core~disclosures~kb-jwt).
     */
    suspend fun validateSdJwtVcPresentation(
        sdJwtPresentationString: String,
        expectedAudience: String?,
        expectedNonce: String,
        originalClaimsQuery: List<ClaimsQuery>?,
        /**
         * EWC RFC008 / OID4VP §5.7 commitment check. When non-null + non-empty,
         * the KB-JWT's `transaction_data_hashes` must cover every entry's
         * SHA-256 hash. `null` skips the check.
         */
        expectedTransactionData: List<String>? = null,
    ): Result<PresentationValidationResult> {
        val presentation = DcSdJwtPresentation.parse(sdJwtPresentationString)
            .getOrThrow()
        presentation.presentationVerification(
            expectedAudience = expectedAudience,
            expectedNonce = expectedNonce,
            originalClaimsQuery = originalClaimsQuery,
            expectedTransactionData = expectedTransactionData,
        )

        return Result.success(
            PresentationValidationResult(
                presentation,
                listOf(presentation.credential)
            )
        )
    }

}
