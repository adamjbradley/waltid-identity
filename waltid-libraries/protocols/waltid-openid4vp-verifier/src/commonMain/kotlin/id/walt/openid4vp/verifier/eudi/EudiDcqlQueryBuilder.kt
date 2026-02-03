package id.walt.openid4vp.verifier.eudi

import id.walt.dcql.models.ClaimsQuery
import id.walt.dcql.models.CredentialFormat
import id.walt.dcql.models.CredentialQuery
import id.walt.dcql.models.meta.MsoMdocMeta
import id.walt.dcql.models.meta.SdJwtVcMeta

/**
 * Utility for building DCQL queries for EUDI credential types.
 *
 * Supports:
 * - PID (Personal Identification Data) in mso_mdoc and dc+sd-jwt formats
 * - mDL (Mobile Driving License) in mso_mdoc format
 */
object EudiDcqlQueryBuilder {

    // EUDI PID doctype for mso_mdoc format
    private const val PID_DOCTYPE = "eu.europa.ec.eudi.pid.1"

    // EUDI PID namespace (same as doctype for PID)
    private const val PID_NAMESPACE = "eu.europa.ec.eudi.pid.1"

    // EUDI PID VCT for SD-JWT format
    private const val PID_VCT = "urn:eudi:pid:1"

    // ISO mDL doctype
    private const val MDL_DOCTYPE = "org.iso.18013.5.1.mDL"

    // ISO mDL namespace
    private const val MDL_NAMESPACE = "org.iso.18013.5.1"

    /**
     * Build DCQL query for PID in mso_mdoc format.
     *
     * @param claims List of claim names to request (e.g., "birth_date", "family_name")
     * @return CredentialQuery configured for PID mDoc
     */
    fun pidMdoc(claims: List<String>): CredentialQuery {
        return CredentialQuery(
            id = "pid_mdoc",
            format = CredentialFormat.MSO_MDOC,
            meta = MsoMdocMeta(doctypeValue = PID_DOCTYPE),
            claims = claims.map { claimName ->
                ClaimsQuery(
                    path = listOf(PID_NAMESPACE, claimName)
                )
            }
        )
    }

    /**
     * Build DCQL query for PID in dc+sd-jwt format.
     *
     * @param claims List of claim names to request (e.g., "family_name", "given_name")
     * @return CredentialQuery configured for PID SD-JWT
     */
    fun pidSdJwt(claims: List<String>): CredentialQuery {
        return CredentialQuery(
            id = "pid_sdjwt",
            format = CredentialFormat.DC_SD_JWT,
            meta = SdJwtVcMeta(vctValues = listOf(PID_VCT)),
            claims = claims.map { claimName ->
                ClaimsQuery(path = listOf(claimName))
            }
        )
    }

    /**
     * Build DCQL query for mDL in mso_mdoc format.
     *
     * @param claims List of claim names to request (e.g., "family_name", "driving_privileges")
     * @return CredentialQuery configured for mDL mDoc
     */
    fun mdl(claims: List<String>): CredentialQuery {
        return CredentialQuery(
            id = "mdl_mdoc",
            format = CredentialFormat.MSO_MDOC,
            meta = MsoMdocMeta(doctypeValue = MDL_DOCTYPE),
            claims = claims.map { claimName ->
                ClaimsQuery(
                    path = listOf(MDL_NAMESPACE, claimName)
                )
            }
        )
    }
}
