@file:OptIn(ExperimentalTime::class)

package id.walt.authop.store

import com.github.benmanes.caffeine.cache.Ticker
import id.walt.authop.domain.AuthCode
import id.walt.authop.domain.AuthRequest
import id.walt.authop.domain.CapturedCredential
import id.walt.authop.domain.Session
import id.walt.authop.domain.VpSession
import id.walt.authop.domain.VpSessionStatus
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.seconds
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * Caffeine [Ticker] that reads from a shared mutable offset, letting tests
 * fast-forward expiration deterministically.
 *
 * Caffeine's own expiration uses `System.nanoTime` internally, so we must
 * inject a [Ticker] (not a `Clock`) to override that. `advance(Duration)`
 * adds to the offset and Caffeine's next cache access sees the new time.
 */
private class TestTicker(private val startNanos: Long = 0L) : Ticker {
    private var offset: Long = 0L
    override fun read(): Long = startNanos + offset
    fun advance(d: kotlin.time.Duration) {
        offset += d.inWholeNanoseconds
    }
}

private fun sampleAuthCode(code: String = "c1") = AuthCode(
    code = code,
    clientId = "client-a",
    redirectUri = "https://rp.example/cb",
    subject = "sub-1",
    claims = emptyMap(),
    codeChallenge = "challenge",
    codeChallengeMethod = "S256"
)

private fun sampleAuthRequest(id: String = "ar1") = AuthRequest(
    authRequestId = id,
    clientId = "client-a",
    redirectUri = "https://rp.example/cb",
    scope = listOf("openid", "profile"),
    state = "state-xyz",
    nonce = "nonce-xyz",
    codeChallenge = "challenge",
    codeChallengeMethod = "S256",
    prompt = null,
    chosenRealmId = null,
    subject = null,
    claims = emptyMap()
)

private fun sampleSession(id: String = "sid-1") = Session(
    sessionId = id,
    subject = "sub-1",
    realmId = "realm-a",
    amr = listOf("pwd"),
    acr = "urn:mace:incommon:iap:silver",
    authTime = Instant.fromEpochSeconds(1_700_000_000),
    upstreamIdToken = null
)

private fun sampleVpSession(id: String = "vp-1") = VpSession(
    verifierSessionId = id,
    realmId = "realm-a",
    authRequestId = "ar1",
    sessionCookieId = "sid-1",
    webhookSecret = "secret-shh",
    status = VpSessionStatus.PENDING,
    capturedCredential = null
)

private fun sampleCapturedCredential(): CapturedCredential {
    val creds: JsonObject = buildJsonObject {
        put("vc+sd-jwt", buildJsonObject { put("sub", "alice") })
    }
    val pres: JsonObject = buildJsonObject {
        put("vc+sd-jwt", buildJsonObject { put("nonce", "abc") })
    }
    return CapturedCredential(presentedCredentials = creds, presentedPresentations = pres)
}

class StoreTests {

    // ---- AuthCodeStore ---------------------------------------------------

    @Test
    fun `auth code is single-use`() {
        val store = InMemoryAuthCodeStore(ttl = 60.seconds, ticker = TestTicker())
        store.put("c1", sampleAuthCode("c1"))
        assertNotNull(store.consume("c1"))
        assertNull(store.consume("c1"), "second consume must return null")
    }

    @Test
    fun `auth code expires after TTL`() {
        val ticker = TestTicker()
        val store = InMemoryAuthCodeStore(ttl = 60.seconds, ticker = ticker)
        store.put("c1", sampleAuthCode("c1"))
        ticker.advance(61.seconds)
        assertNull(store.consume("c1"))
    }

    // ---- AuthRequestStore ------------------------------------------------

    @Test
    fun `auth request update transforms value and persists`() {
        val store = InMemoryAuthRequestStore(ttl = 600.seconds, ticker = TestTicker())
        val initial = sampleAuthRequest("ar1")
        store.put("ar1", initial)

        val updated = store.update("ar1") { it.copy(chosenRealmId = "realm-b", subject = "sub-2") }
        assertNotNull(updated)
        assertEquals("realm-b", updated.chosenRealmId)
        assertEquals("sub-2", updated.subject)

        // Persisted across a fresh get (we're not just mutating the local copy).
        val reread = store.get("ar1")
        assertNotNull(reread)
        assertEquals("realm-b", reread.chosenRealmId)
        assertEquals("sub-2", reread.subject)
    }

    // ---- SessionStore ----------------------------------------------------

    @Test
    fun `session store retrieves then removes on logout`() {
        val store = InMemorySessionStore(ttl = 3600.seconds, ticker = TestTicker())
        val session = sampleSession("sid-1")
        store.put("sid-1", session)

        assertEquals(session, store.get("sid-1"))
        store.remove("sid-1")
        assertNull(store.get("sid-1"), "session must be gone after logout")
    }

    @Test
    fun `session get after TTL returns null`() {
        val ticker = TestTicker()
        val store = InMemorySessionStore(ttl = 60.seconds, ticker = ticker)
        store.put("sid-1", sampleSession("sid-1"))
        ticker.advance(61.seconds)
        assertNull(store.get("sid-1"))
    }

    // ---- VpSessionStore --------------------------------------------------

    @Test
    fun `vp session stores webhook secret and captured credential`() {
        val store = InMemoryVpSessionStore(ttl = 600.seconds, ticker = TestTicker())
        val initial = sampleVpSession("vp-1")
        store.put("vp-1", initial)

        val captured = sampleCapturedCredential()
        val updated = store.update("vp-1") {
            it.copy(status = VpSessionStatus.SUCCESSFUL, capturedCredential = captured)
        }
        assertNotNull(updated)
        assertEquals("secret-shh", updated.webhookSecret)
        assertEquals(captured, updated.capturedCredential)

        val reread = store.get("vp-1")
        assertNotNull(reread)
        assertEquals(VpSessionStatus.SUCCESSFUL, reread.status)
        assertEquals(captured, reread.capturedCredential)
    }

    @Test
    fun `vp session status transitions PENDING to SUCCESSFUL via update`() {
        val store = InMemoryVpSessionStore(ttl = 600.seconds, ticker = TestTicker())
        store.put("vp-2", sampleVpSession("vp-2"))

        assertEquals(VpSessionStatus.PENDING, store.get("vp-2")?.status)
        store.update("vp-2") { it.copy(status = VpSessionStatus.SUCCESSFUL) }
        assertEquals(VpSessionStatus.SUCCESSFUL, store.get("vp-2")?.status)
    }
}
