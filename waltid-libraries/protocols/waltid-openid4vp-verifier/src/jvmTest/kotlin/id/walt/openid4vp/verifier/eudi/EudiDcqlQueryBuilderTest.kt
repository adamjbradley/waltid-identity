package id.walt.openid4vp.verifier.eudi

import id.walt.dcql.models.CredentialFormat
import id.walt.dcql.models.meta.MsoMdocMeta
import id.walt.dcql.models.meta.SdJwtVcMeta
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class EudiDcqlQueryBuilderTest {

    @Test
    fun `build PID mDoc query with birth_date claim`() {
        val query = EudiDcqlQueryBuilder.pidMdoc(
            claims = listOf("birth_date")
        )

        assertEquals("pid_mdoc", query.id)
        assertEquals(CredentialFormat.MSO_MDOC, query.format)
        assertNotNull(query.meta)
        assertEquals(1, query.claims?.size)

        // Verify the claims path structure for mDoc
        val claim = query.claims!!.first()
        assertEquals(listOf("eu.europa.ec.eudi.pid.1", "birth_date"), claim.path)
    }

    @Test
    fun `build PID mDoc query with multiple claims`() {
        val query = EudiDcqlQueryBuilder.pidMdoc(
            claims = listOf("family_name", "given_name", "birth_date")
        )

        assertEquals("pid_mdoc", query.id)
        assertEquals(CredentialFormat.MSO_MDOC, query.format)
        assertEquals(3, query.claims?.size)

        // Verify meta contains correct doctype
        assertTrue(query.meta is MsoMdocMeta)
        assertEquals("eu.europa.ec.eudi.pid.1", (query.meta as MsoMdocMeta).doctypeValue)
    }

    @Test
    fun `build PID SD-JWT query with family_name claim`() {
        val query = EudiDcqlQueryBuilder.pidSdJwt(
            claims = listOf("family_name", "given_name")
        )

        assertEquals("pid_sdjwt", query.id)
        assertEquals(CredentialFormat.DC_SD_JWT, query.format)
        assertEquals(2, query.claims?.size)

        // Verify meta contains correct VCT
        assertTrue(query.meta is SdJwtVcMeta)
        assertEquals(listOf("urn:eudi:pid:1"), (query.meta as SdJwtVcMeta).vctValues)

        // Verify the claims path structure for SD-JWT (simple path)
        val firstClaim = query.claims!!.first()
        assertEquals(listOf("family_name"), firstClaim.path)
    }

    @Test
    fun `build mDL query with driving privileges`() {
        val query = EudiDcqlQueryBuilder.mdl(
            claims = listOf("family_name", "driving_privileges")
        )

        assertEquals("mdl_mdoc", query.id)
        assertEquals(CredentialFormat.MSO_MDOC, query.format)
        assertEquals(2, query.claims?.size)

        // Verify meta contains correct doctype
        assertTrue(query.meta is MsoMdocMeta)
        assertEquals("org.iso.18013.5.1.mDL", (query.meta as MsoMdocMeta).doctypeValue)

        // Verify claims use ISO namespace
        val firstClaim = query.claims!!.first()
        assertEquals(listOf("org.iso.18013.5.1", "family_name"), firstClaim.path)
    }

    @Test
    fun `build mDL query with single claim`() {
        val query = EudiDcqlQueryBuilder.mdl(
            claims = listOf("portrait")
        )

        assertEquals("mdl_mdoc", query.id)
        assertEquals(1, query.claims?.size)
        assertEquals(listOf("org.iso.18013.5.1", "portrait"), query.claims!!.first().path)
    }

    @Test
    fun `build queries with empty claims list`() {
        val pidMdoc = EudiDcqlQueryBuilder.pidMdoc(claims = emptyList())
        val pidSdJwt = EudiDcqlQueryBuilder.pidSdJwt(claims = emptyList())
        val mdl = EudiDcqlQueryBuilder.mdl(claims = emptyList())

        // All should return queries with empty claims lists
        assertTrue(pidMdoc.claims?.isEmpty() == true)
        assertTrue(pidSdJwt.claims?.isEmpty() == true)
        assertTrue(mdl.claims?.isEmpty() == true)
    }
}
