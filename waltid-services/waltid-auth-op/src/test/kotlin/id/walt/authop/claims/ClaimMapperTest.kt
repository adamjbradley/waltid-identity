package id.walt.authop.claims

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for [ClaimMapper]. Exercised with both the OIDC payload shape
 * (flat object of primitives) and with nested/array shapes so that when
 * Task 19 reuses the mapper for VP payloads, the guarantees here cover
 * the surface area it'll actually hit.
 */
class ClaimMapperTest {

    @Test
    fun `maps upstream id token claims via JSONPath`() {
        val payload = buildJsonObject {
            put("sub", "u1")
            put("email", "a@b.com")
        }
        val mapping = mapOf("sub" to "$.sub", "email" to "$.email")

        val out = ClaimMapper.apply(payload, mapping)

        assertEquals("u1", out["sub"]!!.jsonPrimitive.content)
        assertEquals("a@b.com", out["email"]!!.jsonPrimitive.content)
    }

    @Test
    fun `missing path produces null not error`() {
        val payload = buildJsonObject {
            put("sub", "u1")
        }
        val mapping = mapOf(
            "sub" to "$.sub",
            "email" to "$.email", // not present
        )

        // Must not throw — absent paths are silently omitted.
        val out = ClaimMapper.apply(payload, mapping)

        assertEquals("u1", out["sub"]!!.jsonPrimitive.content)
        // And specifically NOT present with null-value — we omit entries.
        assertFalse("email" in out, "missing path must not add an entry at all")
    }

    @Test
    fun `array element access works`() {
        val payload = buildJsonObject {
            put("addresses", buildJsonArray {
                add(buildJsonObject {
                    put("street", "221B Baker St")
                    put("city", "London")
                })
                add(buildJsonObject {
                    put("street", "10 Downing")
                    put("city", "London")
                })
            })
        }
        val mapping = mapOf(
            "primary_street" to "$.addresses[0].street",
            "secondary_street" to "$.addresses[1].street",
        )

        val out = ClaimMapper.apply(payload, mapping)

        assertEquals("221B Baker St", out["primary_street"]!!.jsonPrimitive.content)
        assertEquals("10 Downing", out["secondary_street"]!!.jsonPrimitive.content)
    }

    @Test
    fun `nested object access works`() {
        val payload = buildJsonObject {
            putJsonObject("profile") {
                putJsonObject("name") {
                    put("given", "Ada")
                    put("family", "Lovelace")
                }
            }
        }
        val mapping = mapOf(
            "given_name" to "$.profile.name.given",
            "family_name" to "$.profile.name.family",
        )

        val out = ClaimMapper.apply(payload, mapping)

        assertEquals("Ada", out["given_name"]!!.jsonPrimitive.content)
        assertEquals("Lovelace", out["family_name"]!!.jsonPrimitive.content)
    }

    @Test
    fun `empty mapping returns empty map`() {
        val payload = buildJsonObject { put("sub", "u1") }
        assertTrue(ClaimMapper.apply(payload, emptyMap()).isEmpty())
    }

    @Test
    fun `preserves non-primitive JSON types`() {
        // When the JSONPath resolves to an object or array, ClaimMapper must
        // return it as-is so callers that need structure (groups, arrays)
        // keep the typing.
        val payload = buildJsonObject {
            put("groups", buildJsonArray { add(JsonPrimitive("admin")); add(JsonPrimitive("ops")) })
            putJsonObject("profile") { put("name", "Ada") }
        }
        val mapping = mapOf(
            "groups" to "$.groups",
            "profile" to "$.profile",
        )

        val out = ClaimMapper.apply(payload, mapping)

        val groups = out["groups"]!!.jsonArray
        assertEquals("admin", groups[0].jsonPrimitive.content)
        assertEquals("ops", groups[1].jsonPrimitive.content)
        val profile = out["profile"]!!.jsonObject
        assertEquals("Ada", profile["name"]!!.jsonPrimitive.content)
    }

    @Test
    fun `output_name can differ from source claim`() {
        // Realms project upstream `sub` into whatever output name they want.
        // This demonstrates the output side is the one under realm control.
        val payload = buildJsonObject {
            put("preferred_username", "ada-l")
            put("sub", "upstream-123")
        }
        val mapping = mapOf(
            "sub" to "$.sub",
            "username" to "$.preferred_username",
        )

        val out = ClaimMapper.apply(payload, mapping)

        assertEquals("upstream-123", out["sub"]!!.jsonPrimitive.content)
        assertEquals("ada-l", out["username"]!!.jsonPrimitive.content)
    }

    @Test
    fun `numeric and boolean primitives pass through unchanged`() {
        val payload = buildJsonObject {
            put("age", 42)
            put("verified", true)
        }
        val mapping = mapOf("age" to "$.age", "verified" to "$.verified")

        val out = ClaimMapper.apply(payload, mapping)

        val age = out["age"] as JsonPrimitive
        val verified = out["verified"] as JsonPrimitive
        assertEquals("42", age.content)
        assertFalse(age.isString)
        assertEquals("true", verified.content)
        assertFalse(verified.isString)
    }
}
