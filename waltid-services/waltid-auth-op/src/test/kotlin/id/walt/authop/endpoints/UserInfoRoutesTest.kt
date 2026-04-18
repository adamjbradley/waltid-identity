@file:OptIn(ExperimentalTime::class)

package id.walt.authop.endpoints

import id.walt.authop.module
import id.walt.authop.testDeps
import id.walt.authop.testKey
import id.walt.authop.tokens.JwtIssuer
import id.walt.crypto.keys.jwk.JWKKey
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * Tests for `GET /userinfo` and `POST /userinfo`.
 *
 * Access tokens are minted directly via [JwtIssuer] rather than going through
 * `/authorize` → `/consent` → `/token` so the tests isolate `/userinfo`
 * behaviour. We reuse the same signing key for mint + verify — in production
 * the `/userinfo` endpoint validates against its own `signingKey.getPublicKey()`
 * which is the same key, so fidelity is preserved.
 *
 * [TestClock] lets expiry tests mint a token with `exp` in the past without
 * sleeping. Each test constructs its own [JwtIssuer] when it needs a
 * non-default clock, otherwise reuses the default `testDeps()`.
 */
class UserInfoRoutesTest {

    /**
     * Minimal fixed-instant clock — same pattern as JwtIssuerTest. Tests that
     * need a moving clock can wrap this with a mutable field; none of the
     * /userinfo tests currently need motion.
     */
    private class TestClock(private val instant: Instant) : Clock {
        override fun now(): Instant = instant
    }

    /**
     * Mint a valid access token for [subject] with the given [scopes] and any
     * [extraClaims]. Uses the default `testDeps()` signing key and a fresh
     * [JwtIssuer] so the caller can pick a lifetime/clock independently — the
     * issuer reuses the same key, which is what `/userinfo` verifies against.
     */
    private fun mintAccessToken(
        signingKey: JWKKey,
        subject: String = "user-42",
        scopes: List<String> = listOf("openid"),
        extraClaims: Map<String, JsonElement> = emptyMap(),
        lifetime: Duration = 1.hours,
        clock: Clock = Clock.System,
    ): String = runBlocking {
        JwtIssuer(
            key = signingKey,
            iss = "https://auth.example",
            lifetime = lifetime,
            clock = clock,
        ).mintAccessToken(
            sub = subject,
            aud = "rp1",
            scope = scopes,
            claims = extraClaims,
        )
    }

    /** Mint an ID token (no `token_use` claim) — used to prove /userinfo rejects ID tokens. */
    private fun mintIdToken(
        signingKey: JWKKey,
        subject: String = "user-42",
        extraClaims: Map<String, JsonElement> = emptyMap(),
    ): String = runBlocking {
        JwtIssuer(
            key = signingKey,
            iss = "https://auth.example",
            lifetime = 1.hours,
        ).mintIdToken(
            sub = subject,
            aud = "rp1",
            nonce = null,
            claims = extraClaims,
        )
    }

    private fun ApplicationTestBuilder.noFollowClient(): HttpClient = createClient {
        followRedirects = false
    }

    // -- Required tests (names verbatim) --------------------------------------

    @Test
    fun `valid bearer returns scope-filtered claims`() = testApplication {
        val key = testKey()
        val deps = testDeps(signingKey = key)
        application { module(deps) }

        val token = mintAccessToken(
            signingKey = key,
            subject = "alice",
            scopes = listOf("openid", "profile", "email"),
            extraClaims = mapOf(
                "given_name" to JsonPrimitive("Alice"),
                "family_name" to JsonPrimitive("Liddell"),
                "email" to JsonPrimitive("alice@example.com"),
                "email_verified" to JsonPrimitive(true),
            ),
        )

        val r = noFollowClient().get("/userinfo") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        assertEquals(HttpStatusCode.OK, r.status)
        val body = Json.parseToJsonElement(r.bodyAsText()) as JsonObject
        assertEquals("alice", body["sub"]?.jsonPrimitive?.content)
        assertEquals("Alice", body["given_name"]?.jsonPrimitive?.content)
        assertEquals("Liddell", body["family_name"]?.jsonPrimitive?.content)
        assertEquals("alice@example.com", body["email"]?.jsonPrimitive?.content)
        assertEquals("true", body["email_verified"]?.jsonPrimitive?.content)
    }

    @Test
    fun `invalid token returns 401 with WWW-Authenticate bearer`() = testApplication {
        application { module(testDeps()) }

        val r = noFollowClient().get("/userinfo") {
            header(HttpHeaders.Authorization, "Bearer not.a.jwt")
        }
        assertEquals(HttpStatusCode.Unauthorized, r.status)
        val www = r.headers[HttpHeaders.WWWAuthenticate]
        assertNotNull(www, "WWW-Authenticate header must be present")
        assertTrue(www.startsWith("Bearer"), "WWW-Authenticate must advertise Bearer scheme")
        assertTrue(
            www.contains("""error="invalid_token""""),
            "WWW-Authenticate should carry invalid_token for a signature failure; got $www",
        )
    }

    @Test
    fun `openid-only scope returns only sub`() = testApplication {
        val key = testKey()
        application { module(testDeps(signingKey = key)) }

        val token = mintAccessToken(
            signingKey = key,
            subject = "alice",
            scopes = listOf("openid"),
            extraClaims = mapOf(
                // Profile/email claims are present in the token payload, but
                // openid-only scope must filter them out.
                "given_name" to JsonPrimitive("Alice"),
                "email" to JsonPrimitive("alice@example.com"),
            ),
        )
        val r = noFollowClient().get("/userinfo") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        assertEquals(HttpStatusCode.OK, r.status)
        val body = Json.parseToJsonElement(r.bodyAsText()) as JsonObject
        assertEquals("alice", body["sub"]?.jsonPrimitive?.content)
        assertNull(body["given_name"], "openid-only response must not include given_name")
        assertNull(body["email"], "openid-only response must not include email")
        // sub is the only key we expect.
        assertEquals(setOf("sub"), body.keys)
    }

    @Test
    fun `profile scope expands expected claims`() = testApplication {
        val key = testKey()
        application { module(testDeps(signingKey = key)) }

        val token = mintAccessToken(
            signingKey = key,
            subject = "alice",
            scopes = listOf("openid", "profile"),
            extraClaims = mapOf(
                "name" to JsonPrimitive("Alice Liddell"),
                "given_name" to JsonPrimitive("Alice"),
                "family_name" to JsonPrimitive("Liddell"),
                "preferred_username" to JsonPrimitive("alice"),
                "locale" to JsonPrimitive("en-GB"),
                "zoneinfo" to JsonPrimitive("Europe/London"),
                "updated_at" to JsonPrimitive(1_700_000_000L),
            ),
        )
        val r = noFollowClient().get("/userinfo") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        assertEquals(HttpStatusCode.OK, r.status)
        val body = Json.parseToJsonElement(r.bodyAsText()) as JsonObject
        assertEquals("alice", body["sub"]?.jsonPrimitive?.content)
        assertEquals("Alice Liddell", body["name"]?.jsonPrimitive?.content)
        assertEquals("Alice", body["given_name"]?.jsonPrimitive?.content)
        assertEquals("Liddell", body["family_name"]?.jsonPrimitive?.content)
        assertEquals("alice", body["preferred_username"]?.jsonPrimitive?.content)
        assertEquals("en-GB", body["locale"]?.jsonPrimitive?.content)
        assertEquals("Europe/London", body["zoneinfo"]?.jsonPrimitive?.content)
        assertEquals(1_700_000_000L, body["updated_at"]?.jsonPrimitive?.content?.toLong())
    }

    // -- Additional tests -----------------------------------------------------

    @Test
    fun `missing Authorization header returns 401 with empty Bearer challenge`() = testApplication {
        application { module(testDeps()) }
        val r = noFollowClient().get("/userinfo")
        assertEquals(HttpStatusCode.Unauthorized, r.status)
        val www = r.headers[HttpHeaders.WWWAuthenticate]
        assertNotNull(www)
        // Empty challenge — just `Bearer`, no `error=...` parameter.
        assertEquals("Bearer", www)
    }

    @Test
    fun `malformed Authorization header returns 401 invalid_request`() = testApplication {
        application { module(testDeps()) }
        val r = noFollowClient().get("/userinfo") {
            header(HttpHeaders.Authorization, "Basic dXNlcjpwYXNz")
        }
        assertEquals(HttpStatusCode.Unauthorized, r.status)
        val body = Json.parseToJsonElement(r.bodyAsText()) as JsonObject
        assertEquals("invalid_request", body["error"]?.jsonPrimitive?.content)
        val www = r.headers[HttpHeaders.WWWAuthenticate]
        assertNotNull(www)
        assertTrue(www.startsWith("Bearer"), "challenge must still advertise Bearer; got $www")
        assertTrue(www.contains("""error="invalid_request""""))
    }

    @Test
    fun `expired access token returns 401 invalid_token`() = testApplication {
        val key = testKey()
        // The issuer inside `deps.jwtIssuer` uses Clock.System; we mint a token
        // whose `exp` is in the past by pinning a clock in the PAST for the
        // test's dedicated minting issuer.
        val pastInstant = Instant.fromEpochSeconds(1L) // 1970-01-01 + 1s
        val expiredToken = mintAccessToken(
            signingKey = key,
            subject = "alice",
            scopes = listOf("openid"),
            lifetime = 1.hours,
            clock = TestClock(pastInstant),
        )
        // The server uses the default clock (now) so `exp = 1 + 3600` is ancient.
        application { module(testDeps(signingKey = key)) }
        val r = noFollowClient().get("/userinfo") {
            header(HttpHeaders.Authorization, "Bearer $expiredToken")
        }
        assertEquals(HttpStatusCode.Unauthorized, r.status)
        val body = Json.parseToJsonElement(r.bodyAsText()) as JsonObject
        assertEquals("invalid_token", body["error"]?.jsonPrimitive?.content)
        assertTrue(
            body["error_description"]?.jsonPrimitive?.content?.contains("expired") == true,
            "error_description should mention expiry",
        )
    }

    @Test
    fun `ID token used at userinfo returns 401 (token_use != access_token)`() = testApplication {
        val key = testKey()
        application { module(testDeps(signingKey = key)) }
        // ID token is syntactically valid and signature-valid but lacks
        // `token_use=access_token`. Must be rejected with invalid_token.
        val idToken = mintIdToken(signingKey = key, subject = "alice")
        val r = noFollowClient().get("/userinfo") {
            header(HttpHeaders.Authorization, "Bearer $idToken")
        }
        assertEquals(HttpStatusCode.Unauthorized, r.status)
        val body = Json.parseToJsonElement(r.bodyAsText()) as JsonObject
        assertEquals("invalid_token", body["error"]?.jsonPrimitive?.content)
    }

    @Test
    fun `email scope returns email and email_verified`() = testApplication {
        val key = testKey()
        application { module(testDeps(signingKey = key)) }
        val token = mintAccessToken(
            signingKey = key,
            subject = "alice",
            scopes = listOf("openid", "email"),
            extraClaims = mapOf(
                "email" to JsonPrimitive("alice@example.com"),
                "email_verified" to JsonPrimitive(true),
                // profile claim present but not requested — must be filtered.
                "given_name" to JsonPrimitive("Alice"),
            ),
        )
        val r = noFollowClient().get("/userinfo") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        assertEquals(HttpStatusCode.OK, r.status)
        val body = Json.parseToJsonElement(r.bodyAsText()) as JsonObject
        assertEquals("alice@example.com", body["email"]?.jsonPrimitive?.content)
        assertEquals("true", body["email_verified"]?.jsonPrimitive?.content)
        assertNull(body["given_name"], "given_name is a profile claim; must be filtered out")
    }

    @Test
    fun `scope combinations return union of claims`() = testApplication {
        val key = testKey()
        application { module(testDeps(signingKey = key)) }
        val token = mintAccessToken(
            signingKey = key,
            subject = "alice",
            scopes = listOf("openid", "profile", "email"),
            extraClaims = mapOf(
                "given_name" to JsonPrimitive("Alice"),
                "family_name" to JsonPrimitive("Liddell"),
                "email" to JsonPrimitive("alice@example.com"),
                "email_verified" to JsonPrimitive(true),
            ),
        )
        val r = noFollowClient().get("/userinfo") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        assertEquals(HttpStatusCode.OK, r.status)
        val body = Json.parseToJsonElement(r.bodyAsText()) as JsonObject
        // All claim sets must be present.
        assertEquals("alice", body["sub"]?.jsonPrimitive?.content)
        assertEquals("Alice", body["given_name"]?.jsonPrimitive?.content)
        assertEquals("Liddell", body["family_name"]?.jsonPrimitive?.content)
        assertEquals("alice@example.com", body["email"]?.jsonPrimitive?.content)
        assertEquals("true", body["email_verified"]?.jsonPrimitive?.content)
    }

    @Test
    fun `userinfo response has Cache-Control no-store`() = testApplication {
        val key = testKey()
        application { module(testDeps(signingKey = key)) }
        val token = mintAccessToken(signingKey = key, scopes = listOf("openid"))
        val r = noFollowClient().get("/userinfo") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        assertEquals(HttpStatusCode.OK, r.status)
        assertEquals("no-store", r.headers[HttpHeaders.CacheControl])
    }

    @Test
    fun `userinfo via POST same as GET`() = testApplication {
        val key = testKey()
        application { module(testDeps(signingKey = key)) }
        val token = mintAccessToken(
            signingKey = key,
            subject = "alice",
            scopes = listOf("openid", "profile"),
            extraClaims = mapOf("given_name" to JsonPrimitive("Alice")),
        )
        val getR = noFollowClient().get("/userinfo") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        val postR = noFollowClient().post("/userinfo") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        assertEquals(HttpStatusCode.OK, getR.status)
        assertEquals(HttpStatusCode.OK, postR.status)
        // Identical bodies.
        assertEquals(getR.bodyAsText(), postR.bodyAsText())
    }

    @Test
    fun `sub is always returned even with minimum scope`() = testApplication {
        val key = testKey()
        application { module(testDeps(signingKey = key)) }
        // No scopes at all — even then sub must surface, since /userinfo's
        // whole purpose is subject identification.
        val token = mintAccessToken(
            signingKey = key,
            subject = "bob",
            scopes = emptyList(),
        )
        val r = noFollowClient().get("/userinfo") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        assertEquals(HttpStatusCode.OK, r.status)
        val body = Json.parseToJsonElement(r.bodyAsText()) as JsonObject
        assertEquals("bob", body["sub"]?.jsonPrimitive?.content)
    }

    @Test
    fun `non-standard claims are not returned unless configured`() = testApplication {
        val key = testKey()
        application { module(testDeps(signingKey = key)) }
        // Token carries `realm`, `acr`, `amr` — none are OIDC standard scope-
        // backed claims, so /userinfo must drop them even when scopes like
        // `profile` are present (which doesn't expand to them either).
        val token = mintAccessToken(
            signingKey = key,
            subject = "alice",
            scopes = listOf("openid", "profile"),
            extraClaims = mapOf(
                "realm" to JsonPrimitive("citizens"),
                "acr" to JsonPrimitive("urn:walt:vp"),
                "amr" to JsonPrimitive("pwd"),
                // Sanity: a real profile claim to prove filtering is on claim name, not presence.
                "given_name" to JsonPrimitive("Alice"),
            ),
        )
        val r = noFollowClient().get("/userinfo") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        assertEquals(HttpStatusCode.OK, r.status)
        val body = Json.parseToJsonElement(r.bodyAsText()) as JsonObject
        assertFalse("realm" in body, "realm must not leak into /userinfo without explicit opt-in")
        assertFalse("acr" in body, "acr must not leak into /userinfo without explicit opt-in")
        assertFalse("amr" in body, "amr must not leak into /userinfo without explicit opt-in")
        assertEquals("Alice", body["given_name"]?.jsonPrimitive?.content)
    }
}
