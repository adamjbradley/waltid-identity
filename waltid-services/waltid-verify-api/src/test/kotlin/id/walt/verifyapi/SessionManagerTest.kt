package id.walt.verifyapi

import id.walt.verifyapi.session.ResponseMode
import id.walt.verifyapi.session.SessionManager
import id.walt.verifyapi.session.SessionStatus
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Unit tests for SessionManager functionality.
 *
 * These tests verify session ID generation format and enum values
 * without requiring database or Redis connectivity.
 */
class SessionManagerTest {

    @Test
    fun `test session ID format starts with vs_ prefix`() {
        val sessionId = SessionManager.generateSessionId()
        assertTrue(sessionId.startsWith("vs_"), "Session ID should start with 'vs_' prefix")
    }

    @Test
    fun `test session ID has correct length`() {
        val sessionId = SessionManager.generateSessionId()
        assertEquals(15, sessionId.length, "Session ID should be 15 characters (vs_ + 12 chars)")
    }

    @Test
    fun `test session ID is unique`() {
        val sessionId1 = SessionManager.generateSessionId()
        val sessionId2 = SessionManager.generateSessionId()
        assertNotEquals(sessionId1, sessionId2, "Session IDs should be unique")
    }

    @Test
    fun `test session ID contains only alphanumeric characters after prefix`() {
        val sessionId = SessionManager.generateSessionId()
        val idPart = sessionId.removePrefix("vs_")
        assertTrue(idPart.all { it.isLetterOrDigit() }, "Session ID suffix should contain only alphanumeric characters")
    }

    @Test
    fun `test response mode enum values`() {
        assertEquals("ANSWERS", ResponseMode.ANSWERS.name)
        assertEquals("RAW_CREDENTIALS", ResponseMode.RAW_CREDENTIALS.name)
        assertEquals(2, ResponseMode.entries.size)
    }

    @Test
    fun `test session status enum values`() {
        assertEquals(4, SessionStatus.entries.size)
        assertTrue(SessionStatus.entries.contains(SessionStatus.PENDING))
        assertTrue(SessionStatus.entries.contains(SessionStatus.VERIFIED))
        assertTrue(SessionStatus.entries.contains(SessionStatus.FAILED))
        assertTrue(SessionStatus.entries.contains(SessionStatus.EXPIRED))
    }

    @Test
    fun `test session status has expected names`() {
        assertEquals("PENDING", SessionStatus.PENDING.name)
        assertEquals("VERIFIED", SessionStatus.VERIFIED.name)
        assertEquals("FAILED", SessionStatus.FAILED.name)
        assertEquals("EXPIRED", SessionStatus.EXPIRED.name)
    }
}
