package id.walt.verifyapi

import id.walt.verifyapi.session.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Unit tests for verification session data types and serialization.
 *
 * These tests verify that session types can be correctly serialized
 * and deserialized for storage in Valkey/Redis.
 */
class VerificationSessionTest {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = true
    }

    // ============================================================
    // VerificationSession Tests
    // ============================================================

    @Test
    fun `test VerificationSession serialization`() {
        val session = VerificationSession(
            id = "vs_abc123def456",
            organizationId = "org-uuid-123",
            templateName = "kyc-basic",
            responseMode = ResponseMode.ANSWERS,
            status = SessionStatus.PENDING,
            createdAt = 1704067200000,
            expiresAt = 1704067500000
        )

        val serialized = json.encodeToString(session)
        assertTrue(serialized.contains("\"id\""))
        assertTrue(serialized.contains("\"vs_abc123def456\""))

        val deserialized = json.decodeFromString<VerificationSession>(serialized)
        assertEquals("vs_abc123def456", deserialized.id)
        assertEquals("org-uuid-123", deserialized.organizationId)
        assertEquals("kyc-basic", deserialized.templateName)
        assertEquals(ResponseMode.ANSWERS, deserialized.responseMode)
        assertEquals(SessionStatus.PENDING, deserialized.status)
    }

    @Test
    fun `test VerificationSession with verified result`() {
        val session = VerificationSession(
            id = "vs_verified123",
            organizationId = "org-1",
            templateName = "age-check",
            responseMode = ResponseMode.ANSWERS,
            status = SessionStatus.VERIFIED,
            createdAt = 1704067200000,
            expiresAt = 1704067500000,
            result = VerificationResult(
                answers = mapOf(
                    "full_name" to "John Doe",
                    "age_over_18" to "true"
                ),
                verifiedAt = 1704067300000
            )
        )

        val serialized = json.encodeToString(session)
        val deserialized = json.decodeFromString<VerificationSession>(serialized)

        assertEquals(SessionStatus.VERIFIED, deserialized.status)
        assertNotNull(deserialized.result)
        assertEquals("John Doe", deserialized.result?.answers?.get("full_name"))
        assertEquals("true", deserialized.result?.answers?.get("age_over_18"))
    }

    @Test
    fun `test VerificationSession with raw credentials result`() {
        val session = VerificationSession(
            id = "vs_raw123",
            organizationId = "org-1",
            templateName = "full-credential",
            responseMode = ResponseMode.RAW_CREDENTIALS,
            status = SessionStatus.VERIFIED,
            createdAt = 1704067200000,
            expiresAt = 1704067500000,
            result = VerificationResult(
                credentials = listOf(
                    RawCredential(
                        format = "dc+sd-jwt",
                        vct = "urn:eudi:pid:1",
                        raw = "eyJhbGciOiJFUzI1NiJ9...",
                        disclosedClaims = mapOf(
                            "given_name" to "Jane",
                            "family_name" to "Smith"
                        )
                    )
                ),
                verifiedAt = 1704067300000
            )
        )

        val serialized = json.encodeToString(session)
        val deserialized = json.decodeFromString<VerificationSession>(serialized)

        assertEquals(ResponseMode.RAW_CREDENTIALS, deserialized.responseMode)
        assertNotNull(deserialized.result?.credentials)
        assertEquals(1, deserialized.result?.credentials?.size)

        val credential = deserialized.result?.credentials?.first()
        assertEquals("dc+sd-jwt", credential?.format)
        assertEquals("urn:eudi:pid:1", credential?.vct)
        assertEquals("Jane", credential?.disclosedClaims?.get("given_name"))
    }

    @Test
    fun `test VerificationSession with metadata`() {
        val session = VerificationSession(
            id = "vs_meta123",
            organizationId = "org-1",
            templateName = "template",
            responseMode = ResponseMode.ANSWERS,
            status = SessionStatus.PENDING,
            createdAt = 1704067200000,
            expiresAt = 1704067500000,
            metadata = mapOf(
                "userId" to "user-12345",
                "orderId" to "order-67890",
                "source" to "checkout"
            )
        )

        val serialized = json.encodeToString(session)
        val deserialized = json.decodeFromString<VerificationSession>(serialized)

        assertNotNull(deserialized.metadata)
        assertEquals("user-12345", deserialized.metadata?.get("userId"))
        assertEquals("order-67890", deserialized.metadata?.get("orderId"))
        assertEquals("checkout", deserialized.metadata?.get("source"))
    }

    @Test
    fun `test VerificationSession with verifier session ID`() {
        val session = VerificationSession(
            id = "vs_linked123",
            organizationId = "org-1",
            templateName = "template",
            responseMode = ResponseMode.ANSWERS,
            status = SessionStatus.PENDING,
            verifierSessionId = "verifier-api2-session-xyz",
            createdAt = 1704067200000,
            expiresAt = 1704067500000
        )

        val serialized = json.encodeToString(session)
        val deserialized = json.decodeFromString<VerificationSession>(serialized)

        assertEquals("verifier-api2-session-xyz", deserialized.verifierSessionId)
    }

    // ============================================================
    // SessionStatus Tests
    // ============================================================

    @Test
    fun `test SessionStatus enum serialization`() {
        assertEquals("\"PENDING\"", json.encodeToString(SessionStatus.PENDING))
        assertEquals("\"VERIFIED\"", json.encodeToString(SessionStatus.VERIFIED))
        assertEquals("\"FAILED\"", json.encodeToString(SessionStatus.FAILED))
        assertEquals("\"EXPIRED\"", json.encodeToString(SessionStatus.EXPIRED))
    }

    @Test
    fun `test SessionStatus enum deserialization`() {
        assertEquals(SessionStatus.PENDING, json.decodeFromString("\"PENDING\""))
        assertEquals(SessionStatus.VERIFIED, json.decodeFromString("\"VERIFIED\""))
        assertEquals(SessionStatus.FAILED, json.decodeFromString("\"FAILED\""))
        assertEquals(SessionStatus.EXPIRED, json.decodeFromString("\"EXPIRED\""))
    }

    // ============================================================
    // ResponseMode Tests
    // ============================================================

    @Test
    fun `test ResponseMode enum serialization`() {
        assertEquals("\"ANSWERS\"", json.encodeToString(ResponseMode.ANSWERS))
        assertEquals("\"RAW_CREDENTIALS\"", json.encodeToString(ResponseMode.RAW_CREDENTIALS))
    }

    @Test
    fun `test ResponseMode enum deserialization`() {
        assertEquals(ResponseMode.ANSWERS, json.decodeFromString("\"ANSWERS\""))
        assertEquals(ResponseMode.RAW_CREDENTIALS, json.decodeFromString("\"RAW_CREDENTIALS\""))
    }

    // ============================================================
    // VerificationResult Tests
    // ============================================================

    @Test
    fun `test VerificationResult with answers only`() {
        val result = VerificationResult(
            answers = mapOf(
                "name" to "Alice",
                "email" to "alice@example.com"
            ),
            verifiedAt = 1704067300000
        )

        val serialized = json.encodeToString(result)
        val deserialized = json.decodeFromString<VerificationResult>(serialized)

        assertNotNull(deserialized.answers)
        assertEquals(null, deserialized.credentials)
        assertEquals("Alice", deserialized.answers?.get("name"))
    }

    @Test
    fun `test VerificationResult with credentials only`() {
        val result = VerificationResult(
            credentials = listOf(
                RawCredential(
                    format = "mso_mdoc",
                    doctype = "eu.europa.ec.eudi.pid.1",
                    raw = "base64-encoded-mdoc",
                    disclosedClaims = mapOf("given_name" to "Bob")
                )
            ),
            verifiedAt = 1704067300000
        )

        val serialized = json.encodeToString(result)
        val deserialized = json.decodeFromString<VerificationResult>(serialized)

        assertEquals(null, deserialized.answers)
        assertNotNull(deserialized.credentials)
        assertEquals(1, deserialized.credentials?.size)
    }

    // ============================================================
    // RawCredential Tests
    // ============================================================

    @Test
    fun `test RawCredential SD-JWT format`() {
        val credential = RawCredential(
            format = "dc+sd-jwt",
            vct = "urn:eudi:pid:1",
            raw = "eyJhbGciOiJFUzI1NiJ9.eyJfc2QiOlsiWVhZ...",
            disclosedClaims = mapOf(
                "given_name" to "Charlie",
                "family_name" to "Brown",
                "birth_date" to "1990-05-15"
            )
        )

        val serialized = json.encodeToString(credential)
        val deserialized = json.decodeFromString<RawCredential>(serialized)

        assertEquals("dc+sd-jwt", deserialized.format)
        assertEquals("urn:eudi:pid:1", deserialized.vct)
        assertEquals(null, deserialized.doctype)
        assertEquals(3, deserialized.disclosedClaims.size)
    }

    @Test
    fun `test RawCredential mDoc format`() {
        val credential = RawCredential(
            format = "mso_mdoc",
            doctype = "org.iso.18013.5.1.mDL",
            raw = "base64-encoded-cbor-mdoc",
            disclosedClaims = mapOf(
                "given_name" to "Diana",
                "driving_privileges" to "[{\"vehicle_category_code\":\"B\"}]"
            )
        )

        val serialized = json.encodeToString(credential)
        val deserialized = json.decodeFromString<RawCredential>(serialized)

        assertEquals("mso_mdoc", deserialized.format)
        assertEquals(null, deserialized.vct)
        assertEquals("org.iso.18013.5.1.mDL", deserialized.doctype)
    }

    @Test
    fun `test RawCredential with empty claims`() {
        val credential = RawCredential(
            format = "dc+sd-jwt",
            vct = "test-vct",
            raw = "jwt-string",
            disclosedClaims = emptyMap()
        )

        val serialized = json.encodeToString(credential)
        val deserialized = json.decodeFromString<RawCredential>(serialized)

        assertTrue(deserialized.disclosedClaims.isEmpty())
    }

    // ============================================================
    // Edge Cases Tests
    // ============================================================

    @Test
    fun `test session with unicode in metadata`() {
        val session = VerificationSession(
            id = "vs_unicode",
            organizationId = "org-1",
            templateName = "template",
            responseMode = ResponseMode.ANSWERS,
            status = SessionStatus.PENDING,
            createdAt = 1704067200000,
            expiresAt = 1704067500000,
            metadata = mapOf(
                "name" to "José García",
                "address" to "北京市",
                "emoji" to "✓ verified"
            )
        )

        val serialized = json.encodeToString(session)
        val deserialized = json.decodeFromString<VerificationSession>(serialized)

        assertEquals("José García", deserialized.metadata?.get("name"))
        assertEquals("北京市", deserialized.metadata?.get("address"))
        assertEquals("✓ verified", deserialized.metadata?.get("emoji"))
    }

    @Test
    fun `test failed session without result`() {
        val session = VerificationSession(
            id = "vs_failed",
            organizationId = "org-1",
            templateName = "template",
            responseMode = ResponseMode.ANSWERS,
            status = SessionStatus.FAILED,
            createdAt = 1704067200000,
            expiresAt = 1704067500000,
            result = null
        )

        val serialized = json.encodeToString(session)
        val deserialized = json.decodeFromString<VerificationSession>(serialized)

        assertEquals(SessionStatus.FAILED, deserialized.status)
        assertEquals(null, deserialized.result)
    }

    @Test
    fun `test expired session`() {
        val session = VerificationSession(
            id = "vs_expired",
            organizationId = "org-1",
            templateName = "template",
            responseMode = ResponseMode.ANSWERS,
            status = SessionStatus.EXPIRED,
            createdAt = 1704067200000,
            expiresAt = 1704067500000
        )

        val serialized = json.encodeToString(session)
        val deserialized = json.decodeFromString<VerificationSession>(serialized)

        assertEquals(SessionStatus.EXPIRED, deserialized.status)
    }
}
