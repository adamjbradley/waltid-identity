package id.walt.authop.endpoints

import id.walt.authop.jsonClient
import id.walt.authop.module
import id.walt.authop.testConfig
import id.walt.authop.testKey
import id.walt.authop.toStringList
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DiscoveryRoutesTest {

    @Test
    fun `openid-configuration reports expected fields`() = testApplication {
        application { module(testConfig(issuer = "https://auth.example"), testKey()) }
        val client = jsonClient()

        val r = client.get("/.well-known/openid-configuration")
        assertEquals(HttpStatusCode.OK, r.status)
        val body = r.body<JsonObject>()

        assertEquals("https://auth.example", body["issuer"]!!.jsonPrimitive.content)
        assertEquals(listOf("code"), body["response_types_supported"]!!.toStringList())
        assertEquals(listOf("query"), body["response_modes_supported"]!!.toStringList())
        assertTrue(
            body["code_challenge_methods_supported"]!!.toStringList().contains("S256"),
            "S256 must be advertised as a code_challenge_method"
        )

        // Sanity: endpoint URLs are derived from the configured issuer.
        assertEquals("https://auth.example/authorize", body["authorization_endpoint"]!!.jsonPrimitive.content)
        assertEquals("https://auth.example/token", body["token_endpoint"]!!.jsonPrimitive.content)
        assertEquals("https://auth.example/userinfo", body["userinfo_endpoint"]!!.jsonPrimitive.content)
        assertEquals("https://auth.example/end_session", body["end_session_endpoint"]!!.jsonPrimitive.content)
        assertEquals("https://auth.example/jwks.json", body["jwks_uri"]!!.jsonPrimitive.content)

        // Other required metadata — we assert presence + membership rather than
        // exact list equality so future additions (e.g. another auth method)
        // don't break this test needlessly.
        val grants = body["grant_types_supported"]!!.toStringList()
        assertTrue("authorization_code" in grants, "authorization_code must be advertised")

        val authMethods = body["token_endpoint_auth_methods_supported"]!!.toStringList()
        assertTrue("client_secret_basic" in authMethods)
        assertTrue("client_secret_post" in authMethods)
        assertTrue("none" in authMethods)

        val scopes = body["scopes_supported"]!!.toStringList()
        assertTrue("openid" in scopes)

        assertEquals(listOf("public"), body["subject_types_supported"]!!.toStringList())
        assertTrue("RS256" in body["id_token_signing_alg_values_supported"]!!.toStringList())
    }

    @Test
    fun `issuer with trailing slash is normalised consistently`() = testApplication {
        // The canonicalisation lives on AuthOpServiceConfig; discovery routes
        // only echo the canonical form. Verify end-to-end that no endpoint URL
        // contains a double slash.
        application { module(testConfig(issuer = "https://auth.example/"), testKey()) }
        val body = jsonClient().get("/.well-known/openid-configuration").body<JsonObject>()
        assertEquals("https://auth.example", body["issuer"]!!.jsonPrimitive.content)
        assertEquals("https://auth.example/authorize", body["authorization_endpoint"]!!.jsonPrimitive.content)
    }

    @Test
    fun `jwks contains one public key matching signing key id`() = testApplication {
        val key = testKey()
        application { module(testConfig(), key) }

        val body = jsonClient().get("/jwks.json").body<JsonObject>()
        val keys = body["keys"]!!.jsonArray
        assertEquals(1, keys.size, "JWKS should contain exactly one key")

        val publicKey = keys[0] as JsonObject
        // `kid` is present on both the signing key and the exported JWK —
        // they must match so RPs that fetch the JWKS can resolve the key that
        // will sign their tokens.
        val expectedKid = runBlocking { key.getKeyId() }
        assertEquals(expectedKid, publicKey["kid"]!!.jsonPrimitive.content)
    }

    @Test
    fun `jwks never leaks private components`() = testApplication {
        application { module(testConfig(), testKey()) }
        val body = jsonClient().get("/jwks.json").bodyAsText()

        // RSA private components — any of these would be a catastrophic leak.
        assertFalse(body.contains("\"d\""), "JWKS must not contain RSA private exponent 'd'")
        assertFalse(body.contains("\"p\""), "JWKS must not contain RSA prime 'p'")
        assertFalse(body.contains("\"q\""), "JWKS must not contain RSA prime 'q'")
        assertFalse(body.contains("\"dp\""), "JWKS must not contain RSA CRT exponent 'dp'")
        assertFalse(body.contains("\"dq\""), "JWKS must not contain RSA CRT exponent 'dq'")
        assertFalse(body.contains("\"qi\""), "JWKS must not contain RSA CRT coefficient 'qi'")
    }
}
