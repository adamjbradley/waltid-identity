package id.walt.authop.config

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ConfigLoadingTest {

    // --- RealmRegistry --------------------------------------------------------

    @Test
    fun `loads realms from HOCON`() {
        val realms = RealmRegistry.load("src/test/resources/config/realms.conf")

        assertEquals(2, realms.size)

        val employees = realms["employees"]
        assertNotNull(employees, "expected 'employees' realm")
        assertEquals(RealmMethod.OIDC, employees.method)
        val employeesOidc = employees.oidc
        assertNotNull(employeesOidc)
        assertEquals("https://keycloak.example/realms/issuer", employeesOidc.issuer)
        assertEquals("auth-op", employeesOidc.clientId)
        assertEquals(listOf("openid", "profile"), employeesOidc.scopes)
        assertEquals(mapOf("sub" to "\$.sub", "email" to "\$.email"), employees.claimMapping)

        val citizens = realms["citizens"]
        assertNotNull(citizens, "expected 'citizens' realm")
        assertEquals(RealmMethod.OID4VP, citizens.method)
        assertEquals(SubStrategy.CLAIM_HASH, citizens.subStrategy)
        val citizensVp = citizens.oid4vp
        assertNotNull(citizensVp)
        assertEquals("https://verifier.example", citizensVp.verifierBaseUrl)
        assertEquals("config/dcql/citizens.dcql.json", citizensVp.dcqlQueryFile)
        assertEquals("/login/realm/citizens/webhook", citizensVp.webhookCallbackPath)
        assertEquals(listOf("given_name", "family_name"), citizens.subSourceClaims)
    }

    @Test
    fun `duplicate realm id fails at load time`(@TempDirPath tmp: Path) {
        val file = tmp.resolve("realms.conf")
        file.writeText(
            """
            realms = [
              { id = "dup", name = "A", method = "oidc"
                oidc = { issuer = "https://a", client_id = "x", client_secret = "s" } },
              { id = "dup", name = "B", method = "oidc"
                oidc = { issuer = "https://b", client_id = "x", client_secret = "s" } }
            ]
            """.trimIndent()
        )
        val ex = assertFailsWith<IllegalArgumentException> { RealmRegistry.load(file.toString()) }
        assertContains(ex.message ?: "", "Duplicate realm id")
    }

    @Test
    fun `oid4vp realm without sub_strategy fails at load time`(@TempDirPath tmp: Path) {
        val file = tmp.resolve("realms.conf")
        file.writeText(
            """
            realms = [
              { id = "bad", name = "Bad", method = "oid4vp"
                oid4vp = { verifier_base_url = "https://v", dcql_query_file = "x.json",
                           webhook_callback_path = "/cb" } }
            ]
            """.trimIndent()
        )
        val ex = assertFailsWith<IllegalArgumentException> { RealmRegistry.load(file.toString()) }
        assertContains(ex.message ?: "", "sub_strategy")
    }

    @Test
    fun `oidc realm missing oidc block fails at load time`(@TempDirPath tmp: Path) {
        val file = tmp.resolve("realms.conf")
        file.writeText(
            """
            realms = [
              { id = "missing-block", name = "X", method = "oidc" }
            ]
            """.trimIndent()
        )
        val ex = assertFailsWith<IllegalArgumentException> { RealmRegistry.load(file.toString()) }
        assertContains(ex.message ?: "", "missing-block")
    }

    @Test
    fun `claim_hash without sub_source_claims fails at load time`(@TempDirPath tmp: Path) {
        val file = tmp.resolve("realms.conf")
        file.writeText(
            """
            realms = [
              { id = "empty-sources", name = "X", method = "oid4vp"
                oid4vp = { verifier_base_url = "https://v", dcql_query_file = "x.json",
                           webhook_callback_path = "/cb" }
                sub_strategy = "claim_hash" }
            ]
            """.trimIndent()
        )
        val ex = assertFailsWith<IllegalArgumentException> { RealmRegistry.load(file.toString()) }
        assertContains(ex.message ?: "", "sub_source_claims")
    }

    // --- ClientRegistry -------------------------------------------------------

    @Test
    fun `loads clients from HOCON`() {
        val clients = ClientRegistry.load("src/test/resources/config/clients.conf")

        val rp = clients["rp_theaustraliahack"]
        assertNotNull(rp, "expected 'rp_theaustraliahack' client")
        assertTrue(rp.trusted)
        assertEquals(TokenEndpointAuthMethod.CLIENT_SECRET_BASIC, rp.tokenEndpointAuthMethod)
        assertEquals("rp-secret", rp.clientSecret)
        assertTrue(rp.allowedRealms.contains("employees"))
        assertTrue(rp.allowedRealms.contains("citizens"))
        assertEquals(listOf("https://rp.example/api/auth/callback/keycloak"), rp.redirectUris)
        assertEquals(listOf("https://rp.example/*"), rp.postLogoutRedirectUris)
        assertEquals(listOf("openid", "profile", "email"), rp.allowedScopes)
    }

    @Test
    fun `public client has null secret and token_endpoint_auth_method none`(@TempDirPath tmp: Path) {
        val file = tmp.resolve("clients.conf")
        file.writeText(
            """
            clients = [
              { client_id = "public_spa"
                token_endpoint_auth_method = "none"
                redirect_uris = ["https://spa.example/callback"]
                allowed_scopes = ["openid"]
                allowed_realms = ["employees"] }
            ]
            """.trimIndent()
        )
        val clients = ClientRegistry.load(file.toString())
        val spa = clients["public_spa"]
        assertNotNull(spa)
        assertNull(spa.clientSecret)
        assertEquals(TokenEndpointAuthMethod.NONE, spa.tokenEndpointAuthMethod)
        assertEquals(false, spa.trusted)
    }

    @Test
    fun `client with empty redirect_uris fails at load time`(@TempDirPath tmp: Path) {
        val file = tmp.resolve("clients.conf")
        file.writeText(
            """
            clients = [
              { client_id = "bad", client_secret = "s"
                redirect_uris = [] }
            ]
            """.trimIndent()
        )
        val ex = assertFailsWith<IllegalArgumentException> { ClientRegistry.load(file.toString()) }
        assertContains(ex.message ?: "", "redirect_uris")
    }

    @Test
    fun `confidential client missing secret fails at load time`(@TempDirPath tmp: Path) {
        val file = tmp.resolve("clients.conf")
        file.writeText(
            """
            clients = [
              { client_id = "no_secret"
                token_endpoint_auth_method = "client_secret_basic"
                redirect_uris = ["https://x.example/cb"] }
            ]
            """.trimIndent()
        )
        val ex = assertFailsWith<IllegalArgumentException> { ClientRegistry.load(file.toString()) }
        assertContains(ex.message ?: "", "client_secret")
    }

    @Test
    fun `duplicate client_id fails at load time`(@TempDirPath tmp: Path) {
        val file = tmp.resolve("clients.conf")
        file.writeText(
            """
            clients = [
              { client_id = "same", client_secret = "a", redirect_uris = ["https://a/cb"] },
              { client_id = "same", client_secret = "b", redirect_uris = ["https://b/cb"] }
            ]
            """.trimIndent()
        )
        val ex = assertFailsWith<IllegalArgumentException> { ClientRegistry.load(file.toString()) }
        assertContains(ex.message ?: "", "Duplicate client_id")
    }
}

// --- Helpers ----------------------------------------------------------------

/**
 * JUnit 5 `@TempDir` for kotlin.test style imports — re-exported here so the test file
 * only depends on kotlin.test for assertions and JUnit for the tempdir fixture.
 */
typealias TempDirPath = org.junit.jupiter.api.io.TempDir
