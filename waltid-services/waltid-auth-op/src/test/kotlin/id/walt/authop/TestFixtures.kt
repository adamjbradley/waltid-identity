package id.walt.authop

import id.walt.authop.config.AuthOpServiceConfig
import id.walt.crypto.keys.KeyType
import id.walt.crypto.keys.jwk.JWKKey
import io.ktor.client.HttpClient
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.ApplicationTestBuilder
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

/**
 * Shared test fixtures for auth-op endpoint tests.
 *
 * - [testConfig] returns an [AuthOpServiceConfig] with tunable issuer.
 * - [testKey] returns a fresh RSA [JWKKey] (no filesystem side-effects —
 *   tests that need persistence-aware behaviour should exercise
 *   [id.walt.authop.tokens.KeyProvider] directly).
 * - [toStringList] is a small helper for decoding `JsonArray` primitive-string
 *   values — discovery metadata lists like `response_types_supported`.
 * - [jsonClient] is a ktor test-client builder with JSON content negotiation,
 *   so `client.get(...).body<JsonObject>()` works.
 */
fun testConfig(
    issuer: String = "https://auth.example",
    signingKeyPath: String = "build/tmp/test-signing-key.json",
): AuthOpServiceConfig = AuthOpServiceConfig(
    issuer = issuer,
    signingKeyPath = signingKeyPath,
)

/** Generate a fresh RSA signing key for tests. Blocks briefly — fine for JUnit. */
fun testKey(): JWKKey = runBlocking { JWKKey.generate(KeyType.RSA) }

/**
 * Decode a `JsonArray` of strings to `List<String>`. Throws on non-primitive
 * or non-string entries — that's intentional, metadata violations should be loud.
 */
fun JsonElement.toStringList(): List<String> =
    (this as JsonArray).jsonArray.map { it.jsonPrimitive.content }

/**
 * Build a ktor test [HttpClient] with JSON content negotiation installed so
 * `response.body<JsonObject>()` decodes automatically.
 */
fun ApplicationTestBuilder.jsonClient(): HttpClient = createClient {
    install(ContentNegotiation) { json() }
}
