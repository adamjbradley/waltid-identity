# Custom OIDC Provider with Realm Discovery — Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Ship `waltid-services/waltid-auth-op` — a standalone OIDC Provider whose login page is a realm discovery screen; each realm authenticates via upstream OIDC (drop-in for Keycloak) or OID4VP (successful verifiable-presentation = login).

**Architecture:** Kotlin 2.3.0 + Ktor 3 service on port 7005. OIDC authorization-code flow with PKCE to downstream RPs. Per-realm auth: generic OIDC client for classic realms; webhook-based credential capture from `waltid-verifier-api2` for VP realms (works in transactional mode). In-memory state. Signing key persisted to `config/signing-key.json`. JWT signing via project's `id.walt.crypto` abstraction; JSONPath via `com.eygraber:jsonpathkt-kotlinx`.

**Tech Stack:** Kotlin 2.3.0, Ktor 3.3.3, Gradle/Kotlin DSL, Java 21, kotlinx.serialization (HOCON + JSON), `id.walt.crypto`, `waltid-openid4vc`, Caffeine (in-memory TTL), JUnit 5 + Mokkery for tests, Jib for Docker.

**Source of truth for spec:** [`2026-04-18-custom-oidc-op-design.md`](./2026-04-18-custom-oidc-op-design.md). Plan references it; never duplicates it.

**Conventions:**
- All Gradle commands run from repo root.
- Module path shorthand `:auth-op` := `:waltid-services:waltid-auth-op`.
- One commit per task. Commit message format: `feat(auth-op): <task summary>` for net-new code, `test(auth-op): ...` for test-only.
- Never skip hooks. If a hook fails, fix the cause.

---

## Phase 1 — Module skeleton (tasks 1–4)

### Task 1: Create Gradle module + Ktor hello-world + passing baseline test

**Files:**
- Create: `waltid-services/waltid-auth-op/build.gradle.kts`
- Create: `waltid-services/waltid-auth-op/src/main/kotlin/id/walt/authop/Main.kt`
- Create: `waltid-services/waltid-auth-op/src/main/resources/logback.xml`
- Create: `waltid-services/waltid-auth-op/src/test/kotlin/id/walt/authop/HealthRouteTest.kt`
- Modify: `settings.gradle.kts` (root) — include the new module

**Step 1: Write the failing test**

```kotlin
// src/test/kotlin/id/walt/authop/HealthRouteTest.kt
class HealthRouteTest {
    @Test fun `health endpoint returns 200 ok`() = testApplication {
        application { module() }
        val r = client.get("/health")
        assertEquals(HttpStatusCode.OK, r.status)
        assertEquals("ok", r.bodyAsText())
    }
}
```

**Step 2: Verify test fails**

```
./gradlew :auth-op:test --tests 'id.walt.authop.HealthRouteTest' -i
```

Expected: compile failure (`module` function not found). 

**Step 3: Add module to `settings.gradle.kts`**

```kotlin
include(":waltid-services:waltid-auth-op")
```

**Step 4: Write `build.gradle.kts`**

Model it on `waltid-services/waltid-verifier-api2/build.gradle.kts`. Minimum dependencies:
- `io.ktor:ktor-server-core:3.3.3`, `ktor-server-netty`, `ktor-server-html-builder`, `ktor-server-content-negotiation`, `ktor-server-auth`, `ktor-server-status-pages`, `ktor-server-call-logging`, `ktor-server-sessions`
- `io.ktor:ktor-serialization-kotlinx-json`
- `org.jetbrains.kotlinx:kotlinx-serialization-json`
- `ch.qos.logback:logback-classic`
- Test: `io.ktor:ktor-server-test-host`, `org.jetbrains.kotlin:kotlin-test`, `io.mockk:mockk`

**Step 5: Write `Main.kt`**

```kotlin
fun main() = embeddedServer(Netty, port = 7005, module = Application::module).start(wait = true)

fun Application.module() {
    install(ContentNegotiation) { json() }
    routing {
        get("/health") { call.respondText("ok") }
    }
}
```

**Step 6: Write `logback.xml`** (copy from `waltid-verifier-api2`).

**Step 7: Verify test passes**

```
./gradlew :auth-op:test --tests 'id.walt.authop.HealthRouteTest' -i
```

Expected: PASS.

**Step 8: Commit**

```bash
git add settings.gradle.kts waltid-services/waltid-auth-op
git commit -m "feat(auth-op): scaffold Gradle module with Ktor skeleton and health endpoint"
```

---

### Task 2: Config model + HOCON loading

**Files:**
- Create: `src/main/kotlin/id/walt/authop/config/AuthOpConfig.kt`
- Create: `src/main/kotlin/id/walt/authop/config/RealmRegistry.kt`
- Create: `src/main/kotlin/id/walt/authop/config/ClientRegistry.kt`
- Create: `src/test/kotlin/id/walt/authop/config/ConfigLoadingTest.kt`
- Create: `src/test/resources/config/realms.conf`, `clients.conf` (fixtures)
- Modify: `build.gradle.kts` — add `io.github.config4k:config4k:0.7.0` or the version used elsewhere in the repo (grep `config4k` to match)

**Step 1: Fixture config files** (minimal, matching design §Components)

`src/test/resources/config/realms.conf`:
```hocon
realms = [
  { id = "employees", name = "Employees", method = "oidc"
    oidc = { issuer = "https://keycloak.example/realms/issuer", client_id = "auth-op", client_secret = "s", scopes = ["openid","profile"] }
    claim_mapping = { sub = "$.sub", email = "$.email" } },
  { id = "citizens", name = "Citizens", method = "oid4vp"
    oid4vp = { verifier_base_url = "https://verifier.example", dcql_query_file = "config/dcql/citizens.dcql.json",
               webhook_callback_path = "/login/realm/citizens/webhook" }
    sub_strategy = "claim_hash"
    claim_mapping = { given_name = "$.credentialSubject.given_name", family_name = "$.credentialSubject.family_name" }
    sub_source_claims = ["given_name","family_name"] }
]
```

**Step 2: Failing test**

```kotlin
class ConfigLoadingTest {
    @Test fun `loads realms from HOCON`() {
        val realms = RealmRegistry.load("src/test/resources/config/realms.conf")
        assertEquals(2, realms.size)
        val citizens = realms["citizens"]!!
        assertEquals(RealmMethod.OID4VP, citizens.method)
        assertEquals(SubStrategy.CLAIM_HASH, citizens.oid4vp!!.subStrategy)
    }
    @Test fun `missing realm id fails at load time`() { /* ... */ }
}
```

**Step 3: Run test** — fails (classes don't exist).

**Step 4: Implement**

Data classes for `RealmConfig`, `OidcRealmConfig`, `Oid4vpRealmConfig`, `ClaimMapping`, enums `RealmMethod { OIDC, OID4VP }`, `SubStrategy { CREDENTIAL_SUBJECT_ID, CLAIM_HASH, EPHEMERAL }`. Load via config4k's `extract<T>()`.

**Step 5: Run test — PASS.**

**Step 6: Commit**

```bash
git add -A
git commit -m "feat(auth-op): config model + HOCON loading for realms and clients"
```

---

### Task 3: Key provider — load-or-generate signing key

**Files:**
- Create: `src/main/kotlin/id/walt/authop/tokens/KeyProvider.kt`
- Create: `src/test/kotlin/id/walt/authop/tokens/KeyProviderTest.kt`

**Tests:**

```kotlin
class KeyProviderTest {
    @Test fun `generates key when file absent`(@TempDir tmp: Path) {
        val path = tmp.resolve("signing-key.json")
        val key1 = KeyProvider.loadOrCreate(path)
        assertTrue(path.exists())
        assertEquals(KeyType.RSA, key1.keyType)
    }
    @Test fun `reuses existing key across calls`(@TempDir tmp: Path) {
        val path = tmp.resolve("signing-key.json")
        val key1 = KeyProvider.loadOrCreate(path)
        val key2 = KeyProvider.loadOrCreate(path)
        assertEquals(key1.getKeyId(), key2.getKeyId())
    }
}
```

**Implementation sketch:** use `id.walt.crypto.keys.jwk.JWKKey.generate(KeyType.RSA)`; serialize via `JWKKey.exportJWK()` → write file; on read, use `JWKKey.importJWK()`. Grep `JWKKey` in `waltid-libraries/waltid-crypto` for exact APIs.

**Verify + commit:**
```bash
./gradlew :auth-op:test --tests 'id.walt.authop.tokens.KeyProviderTest'
git commit -am "feat(auth-op): load-or-generate signing key with filesystem persistence"
```

---

### Task 4: Discovery + JWKS endpoints

**Files:**
- Create: `src/main/kotlin/id/walt/authop/endpoints/DiscoveryRoutes.kt`
- Create: `src/test/kotlin/id/walt/authop/endpoints/DiscoveryRoutesTest.kt`
- Modify: `Main.kt` — wire routes

**Tests:**

```kotlin
class DiscoveryRoutesTest {
    @Test fun `openid-configuration reports expected fields`() = testApplication {
        application { module(testConfig(issuer = "https://auth.example"), testKey()) }
        val r = client.get("/.well-known/openid-configuration")
        val body = r.body<JsonObject>()
        assertEquals("https://auth.example", body["issuer"]!!.jsonPrimitive.content)
        assertEquals(listOf("code"), body["response_types_supported"]!!.toStringList())
        assertEquals(listOf("query"), body["response_modes_supported"]!!.toStringList())
        assertTrue(body["code_challenge_methods_supported"]!!.toStringList().contains("S256"))
    }
    @Test fun `jwks contains one public key matching signing key id`() = testApplication { /* ... */ }
    @Test fun `jwks never leaks private components`() = testApplication {
        val body = client.get("/jwks.json").bodyAsText()
        assertFalse(body.contains("\"d\""))   // RSA private exponent
        assertFalse(body.contains("\"p\""))
        assertFalse(body.contains("\"q\""))
    }
}
```

**Implementation:** serve a static JSON structure derived from `AuthOpConfig.issuer` + the endpoint paths. `/jwks.json` returns `{"keys": [<public-part-of-signing-key>]}` via `JWKKey.getPublicKey().exportJWKObject()`.

**Verify + commit.**

---

## Phase 2 — State & errors (tasks 5–7)

### Task 5: Four state stores behind interfaces

**Files:**
- Create: `src/main/kotlin/id/walt/authop/store/{AuthRequestStore,AuthCodeStore,SessionStore,VpSessionStore}.kt`
- Create: `src/main/kotlin/id/walt/authop/domain/{AuthRequest,AuthCode,Session,VpSession}.kt`
- Create: `src/test/kotlin/id/walt/authop/store/StoreTests.kt`
- Modify: `build.gradle.kts` — add `com.github.ben-manes.caffeine:caffeine:3.1.8` (check repo version)

**Tests (one per store, all in one file for brevity):**

```kotlin
class StoreTests {
    @Test fun `auth code is single-use`() {
        val store = InMemoryAuthCodeStore(ttl = 60.seconds, clock = TestClock())
        store.put("c1", sampleAuthCode())
        assertNotNull(store.consume("c1"))
        assertNull(store.consume("c1"))          // second read gone
    }
    @Test fun `auth code expires after TTL`() {
        val clock = TestClock()
        val store = InMemoryAuthCodeStore(ttl = 60.seconds, clock = clock)
        store.put("c1", sampleAuthCode())
        clock.advance(61.seconds)
        assertNull(store.consume("c1"))
    }
    @Test fun `session store retrieves then removes on logout`() { /* ... */ }
    @Test fun `vp session stores webhook secret and captured credential`() { /* ... */ }
}
```

**Implementation:** Caffeine `Cache<String, T>` with `expireAfterWrite`. Interfaces + in-memory impls only (forward-compat hook for Valkey).

**Verify + commit.**

---

### Task 6: OIDC error enum + dispatcher

**Files:**
- Create: `src/main/kotlin/id/walt/authop/errors/OidcError.kt`
- Create: `src/test/kotlin/id/walt/authop/errors/OidcErrorDispatchTest.kt`

Complete error table is in design doc §Error handling — implement exactly those rows.

**Tests:**

```kotlin
class OidcErrorDispatchTest {
    @Test fun `invalid redirect_uri renders plain 400, never redirects`() = testApplication {
        val r = client.get("/authorize?client_id=unknown&redirect_uri=https://evil")
        assertEquals(HttpStatusCode.BadRequest, r.status)
        assertNull(r.headers["Location"])
    }
    @Test fun `missing PKCE redirects to RP with error=invalid_request and echoes state`() = testApplication {
        /* construct a valid (client_id, redirect_uri) but no code_challenge */
        val r = client.get("/authorize?...&state=xyz")
        assertEquals(HttpStatusCode.Found, r.status)
        val loc = r.headers["Location"]!!
        assertTrue(loc.contains("error=invalid_request"))
        assertTrue(loc.contains("state=xyz"))
    }
}
```

Dispatcher is a small function: `fun ApplicationCall.respondOidcError(err: OidcError, authReq: AuthRequest?)`.

**Verify + commit.**

---

### Task 7: JWT issuer — id_token + access_token

**Files:**
- Create: `src/main/kotlin/id/walt/authop/tokens/JwtIssuer.kt`
- Create: `src/test/kotlin/id/walt/authop/tokens/JwtIssuerTest.kt`

**Tests:**

```kotlin
class JwtIssuerTest {
    @Test fun `id token contains required claims and verifies against jwks`() {
        val issuer = JwtIssuer(key = testKey(), iss = "https://auth.example", lifetime = 3600.seconds)
        val token = issuer.mintIdToken(
            sub = "did:example:123", aud = "rp1", nonce = "n",
            claims = mapOf("realm" to "citizens", "acr" to "urn:walt:vp")
        )
        val parsed = JWT.decode(token)            // any Nimbus/alt JWT parser
        assertEquals("https://auth.example", parsed.issuer)
        assertEquals("rp1", parsed.audience.single())
        assertEquals("did:example:123", parsed.subject)
        // verify signature against public key
        assertTrue(verifySignature(token, testKey().getPublicKey()))
    }
    @Test fun `access token lifetime honoured`() { /* ... */ }
}
```

Uses `id.walt.crypto.keys.jwk.JWKKey#signJws()` (see `VerificationSessionCreator` for pattern).

**Verify + commit.**

---

## Phase 3 — Core OP (tasks 8–12)

### Task 8: `/authorize` validation + AuthRequest + session cookie

**Files:**
- Create: `src/main/kotlin/id/walt/authop/endpoints/AuthorizeRoutes.kt`
- Create: `src/test/kotlin/id/walt/authop/endpoints/AuthorizeRoutesTest.kt`
- Modify: `Main.kt` — register session cookie plugin (`install(Sessions)`)

**Tests:**

```kotlin
class AuthorizeRoutesTest {
    @Test fun `invalid client_id returns 400 without redirect`() { /* ... */ }
    @Test fun `valid request creates auth request and redirects to login with cookie`() {
        val r = client.get("/authorize?client_id=rp1&redirect_uri=https://rp/cb&response_type=code&scope=openid&state=s&code_challenge=XYZ&code_challenge_method=S256")
        assertEquals(HttpStatusCode.Found, r.status)
        assertEquals("/login", r.headers["Location"])
        assertNotNull(r.setCookie().firstOrNull { it.name == "sid" })
    }
    @Test fun `unsupported response_type redirects with error`() { /* ... */ }
    @Test fun `response_mode fragment rejected`() { /* ... */ }
    @Test fun `missing code_challenge rejected`() { /* ... */ }
}
```

**Verify + commit.**

---

### Task 9: `/login` realm list + `prompt` handling

**Files:**
- Create: `src/main/kotlin/id/walt/authop/endpoints/LoginRoutes.kt`
- Create: `src/main/kotlin/id/walt/authop/templates/LoginPage.kt` (Ktor HTML DSL)
- Create: `src/test/kotlin/id/walt/authop/endpoints/LoginRoutesTest.kt`

**Tests:**

```kotlin
class LoginRoutesTest {
    @Test fun `login page lists configured realms`() {
        val body = primedAuthRequest().get("/login").bodyAsText()
        assertContains(body, "Employees")
        assertContains(body, "Citizens")
    }
    @Test fun `prompt=none without session redirects with login_required`() {
        val r = client.get("/authorize?...&prompt=none")
        assertTrue(r.headers["Location"]!!.contains("error=login_required"))
    }
    @Test fun `prompt=none with session returns code silently`() { /* ... */ }
    @Test fun `prompt=login forces re-auth even with session`() { /* ... */ }
    @Test fun `allowed_realms on client filters visible realms`() { /* ... */ }
}
```

**Verify + commit.**

---

### Task 10: `/consent` GET/POST + CSRF + trusted-skip

**Files:**
- Create: `src/main/kotlin/id/walt/authop/endpoints/ConsentRoutes.kt`
- Create: `src/main/kotlin/id/walt/authop/templates/ConsentPage.kt`
- Create: `src/test/kotlin/id/walt/authop/endpoints/ConsentRoutesTest.kt`

**Tests:**

```kotlin
class ConsentRoutesTest {
    @Test fun `trusted client skips consent and mints auth code directly`() { /* ... */ }
    @Test fun `non-trusted client renders consent page with CSRF token`() { /* ... */ }
    @Test fun `POST without matching CSRF token rejected`() { /* ... */ }
    @Test fun `user denying consent redirects with access_denied`() { /* ... */ }
}
```

**Verify + commit.**

---

### Task 11: `/token` endpoint

**Files:**
- Create: `src/main/kotlin/id/walt/authop/endpoints/TokenRoutes.kt`
- Create: `src/test/kotlin/id/walt/authop/endpoints/TokenRoutesTest.kt`

**Tests:**

```kotlin
class TokenRoutesTest {
    @Test fun `valid code + correct PKCE + client_secret_basic returns tokens`() { /* ... */ }
    @Test fun `reused code returns invalid_grant`() { /* ... */ }
    @Test fun `PKCE verifier mismatch returns invalid_grant`() { /* ... */ }
    @Test fun `wrong client secret returns invalid_client with WWW-Authenticate basic`() { /* ... */ }
    @Test fun `public client with token_endpoint_auth_method none accepted without secret`() { /* ... */ }
    @Test fun `code redirect_uri mismatch returns invalid_grant`() { /* ... */ }
}
```

Supports `client_secret_basic`, `client_secret_post`, `none`. Validates PKCE: `BASE64URL(SHA-256(verifier))` vs stored challenge.

**Verify + commit.**

---

### Task 12: `/userinfo`

**Files:**
- Create: `src/main/kotlin/id/walt/authop/endpoints/UserInfoRoutes.kt`
- Create: `src/test/kotlin/id/walt/authop/endpoints/UserInfoRoutesTest.kt`

**Tests:**

```kotlin
class UserInfoRoutesTest {
    @Test fun `valid bearer returns scope-filtered claims`() { /* ... */ }
    @Test fun `invalid token returns 401 with WWW-Authenticate bearer`() { /* ... */ }
    @Test fun `openid-only scope returns only sub`() { /* ... */ }
    @Test fun `profile scope expands expected claims`() { /* ... */ }
}
```

**Verify + commit.**

---

## Phase 4 — OIDC realm path (tasks 13–15)

### Task 13: Generic OIDC upstream client

**Files:**
- Create: `src/main/kotlin/id/walt/authop/upstream/OidcClient.kt`
- Create: `src/test/kotlin/id/walt/authop/upstream/OidcClientTest.kt`

Discovers upstream via `/.well-known/openid-configuration` (cached), fetches upstream JWKS (cached, short TTL), exchanges authorization code for tokens, verifies upstream ID token (sig, `iss`, `aud`, `exp`, `nonce`).

**Tests:** mocked upstream with `MockEngine` (Ktor client). Cover happy-path code exchange, signature verification failure, nonce mismatch, expired token.

**Verify + commit.**

---

### Task 14: `/callback/oidc` + OIDC claim mapper + session creation

**Files:**
- Create: `src/main/kotlin/id/walt/authop/endpoints/OidcCallbackRoutes.kt`
- Create: `src/main/kotlin/id/walt/authop/claims/ClaimMapper.kt` (used by both paths)
- Create: `src/test/kotlin/id/walt/authop/endpoints/OidcCallbackRoutesTest.kt`
- Create: `src/test/kotlin/id/walt/authop/claims/ClaimMapperTest.kt`

**ClaimMapper tests** (OIDC payload shape):

```kotlin
class ClaimMapperTest {
    @Test fun `maps upstream id token claims via JSONPath`() {
        val payload = buildJsonObject { put("sub","u1"); put("email","a@b.com") }
        val mapping = mapOf("sub" to "$.sub", "email" to "$.email")
        val out = ClaimMapper.apply(payload, mapping)
        assertEquals("u1", out["sub"]!!.jsonPrimitive.content)
    }
    @Test fun `missing path produces null not error`() { /* ... */ }
}
```

**Callback tests:** mock upstream returns valid ID token, auth-op creates session, resumes AuthRequest, redirects to `/consent`. Stores upstream `id_token` in session for logout.

**Verify + commit.**

---

### Task 15: Full OIDC flow integration test

**Files:**
- Create: `src/test/kotlin/id/walt/authop/e2e/OidcFlowE2ETest.kt`

End-to-end with mocked upstream: `/authorize` → `/login` → realm pick → mock Keycloak → `/callback/oidc` → (trusted client) → `/token` → `/userinfo`. Assert resulting ID token has `realm`, `acr="urn:walt:upstream-oidc"`, and mapped claims.

**Verify + commit.**

---

## Phase 5 — VP realm path (tasks 16–19)

### Task 16: `Verifier2Client`

**Files:**
- Create: `src/main/kotlin/id/walt/authop/upstream/Verifier2Client.kt`
- Create: `src/test/kotlin/id/walt/authop/upstream/Verifier2ClientTest.kt`

**Pre-work (~2 min):** grep `VerificationSessionSetupData` + `GeneralFlowConfig` in `waltid-services/waltid-verifier-api2` to confirm exact field names for webhook registration. Mark this in the code as a verified-against comment: `// Verified against VerificationSessionSetupData at <file:line>`.

**Client responsibilities:**
- `createSession(realm: Oid4vpRealmConfig, webhookSecret: String): CreateSessionResponse` — POSTs to `/verification-session/create` with DCQL from `dcql_query_file` and notifications block registering our webhook URL + secret.
- `getSessionInfo(verifierBaseUrl, sessionId)` — used ONLY for recovery-path pre-check, never for credential data.

**Tests with mocked verifier:**

```kotlin
class Verifier2ClientTest {
    @Test fun `createSession posts to correct path with dcql and webhook registration`() { /* captured request assertions */ }
    @Test fun `createSession surfaces verifier 4xx as domain error`() { /* ... */ }
}
```

**Verify + commit.**

---

### Task 17: VP QR page + `/login/realm/{id}/status`

**Files:**
- Create: `src/main/kotlin/id/walt/authop/endpoints/VpFlowRoutes.kt`
- Create: `src/main/kotlin/id/walt/authop/templates/VpQrPage.kt`
- Create: `src/test/kotlin/id/walt/authop/endpoints/VpFlowRoutesTest.kt`

**Route flow (server-side):**
1. Generate a 256-bit webhook secret.
2. Call `Verifier2Client.createSession(realm, secret)`.
3. Persist `VpSession { verifierSessionId, realmId, authRequestId, sessionCookieId, webhookSecret, status=PENDING }`.
4. Render QR page with `bootstrapAuthorizationRequestUrl` embedded.
5. `/login/realm/{id}/status?verifierSessionId=...` reads our `VpSessionStore`, returns `{status: "PENDING"|"SUCCESSFUL"|"UNSUCCESSFUL"}`. No credential data returned on this endpoint.

**Tests:**

```kotlin
class VpFlowRoutesTest {
    @Test fun `selecting VP realm renders QR page and creates VpSession`() { /* ... */ }
    @Test fun `status endpoint reports PENDING until webhook fires`() { /* ... */ }
    @Test fun `status endpoint rejects requests whose sid cookie mismatches stored sessionCookieId`() { /* ... */ }
    @Test fun `recovery - refreshing page for an already-SUCCESSFUL session redirects to complete`() { /* ... */ }
}
```

**Verify + commit.**

---

### Task 18: `/login/realm/{id}/webhook` handler

**Files:**
- Extend: `VpFlowRoutes.kt` (add POST handler)
- Extend: `VpFlowRoutesTest.kt`

**Webhook logic:**
1. Look up `VpSession` by `verifierSessionId` from request body. Missing → 404.
2. Compare request's webhook secret with stored `webhookSecret` via constant-time compare. Mismatch → 401.
3. If event is `policy_results_available`, extract `newSession.presentedCredentials` + `newSession.presentedPresentations`, store into `VpSessionStore.capturedCredential`, set status according to `newSession.status` (SUCCESSFUL / UNSUCCESSFUL).
4. Respond 200 quickly.

**Tests:**

```kotlin
@Test fun `webhook with correct secret captures credential and marks SUCCESSFUL`() { /* ... */ }
@Test fun `webhook with wrong secret returns 401 and does not mutate store`() { /* ... */ }
@Test fun `webhook for unknown verifierSessionId returns 404`() { /* ... */ }
@Test fun `webhook non-policy_results event is 200 but does not capture`() { /* ... */ }
@Test fun `webhook UNSUCCESSFUL status captured correctly`() { /* ... */ }
@Test fun `constant-time compare used for secret`() { /* test by timing or by code inspection marker */ }
```

**Verify + commit.**

---

### Task 19: `/login/realm/{id}/complete` + `SubStrategy` + VP claim mapping

**Files:**
- Create: `src/main/kotlin/id/walt/authop/claims/SubStrategy.kt`
- Create: `src/test/kotlin/id/walt/authop/claims/SubStrategyTest.kt`
- Extend: `VpFlowRoutes.kt` (`/complete` handler)
- Extend: `VpFlowRoutesTest.kt`

**SubStrategy tests:**

```kotlin
class SubStrategyTest {
    @Test fun `credential_subject_id uses VC id`() { /* ... */ }
    @Test fun `claim_hash is deterministic for same inputs`() { /* ... */ }
    @Test fun `claim_hash differs across realms for same person`() { /* ... */ }
    @Test fun `ephemeral produces different sub each call`() { /* ... */ }
}
```

Hash formula exactly as design: `BASE64URL(SHA-256(realm.id || "\u0000" || joinNul(claimValues)))`.

**Complete endpoint tests:**

```kotlin
@Test fun `complete with matching sid cookie, SUCCESSFUL status and captured credential mints auth code`() { /* ... */ }
@Test fun `complete rejects if sid cookie mismatches stored sessionCookieId`() { /* ... */ }
@Test fun `complete rejects if status != SUCCESSFUL`() { /* ... */ }
@Test fun `complete rejects if capturedCredential is null`() { /* ... */ }
@Test fun `capturedCredential cleared after successful consumption`() { /* ... */ }
@Test fun `id token has acr=urn:walt:vp and amr=[swk]`() { /* ... */ }
```

**Phase 5 integration test** (separate small file):
- Mock Verifier2Client. Kick off authorize → pick citizens → assert QR page. Simulate webhook POST with valid secret + mock credential. Assert status endpoint flips to SUCCESSFUL. Call /complete. Assert token has correct claims.

**Verify + commit.**

---

## Phase 6 — Logout (task 20)

### Task 20: `/end_session` + upstream chain for OIDC realms

**Files:**
- Create: `src/main/kotlin/id/walt/authop/endpoints/EndSessionRoutes.kt`
- Create: `src/test/kotlin/id/walt/authop/endpoints/EndSessionRoutesTest.kt`

**Behaviour:**
- VP session → clear `sid` cookie → redirect to validated `post_logout_redirect_uri`.
- OIDC session with stored upstream `id_token` → redirect to upstream `end_session_endpoint` with `id_token_hint` + `post_logout_redirect_uri=<our /end_session/upstream_return>&state=<nonce>`. On return, clear cookie, redirect to RP's `post_logout_redirect_uri`. Pattern matches `examples/rp-widget-demo/server.js:292-309`.

**Tests:**

```kotlin
@Test fun `VP session logout clears cookie and redirects to RP`() { /* ... */ }
@Test fun `OIDC session logout chains upstream then returns to RP`() { /* ... */ }
@Test fun `end_session rejects unregistered post_logout_redirect_uri`() { /* ... */ }
@Test fun `end_session without session is still a 302 to RP (spec: no-op)`() { /* ... */ }
@Test fun `upstream_return validates state nonce to prevent CSRF`() { /* ... */ }
```

**Verify + commit.**

---

## Phase 7 — Packaging & integration (tasks 21–22)

### Task 21: Dockerfile / jib config

**Files:**
- Modify: `build.gradle.kts` — add jib configuration block
- Optional: `Dockerfile` as alternate for local iteration

Model on `waltid-services/waltid-verifier-api2/build.gradle.kts` — same Jib plugin version, same base image, same layering convention.

**Verification:**

```bash
./gradlew :auth-op:jibDockerBuild
docker tag waltid/auth-op:latest waltid/auth-op:stable
docker run --rm -p 7005:7005 waltid/auth-op:stable &
sleep 5
curl -fsS http://localhost:7005/health | grep -q ok
kill %1
```

**Commit:**
```bash
git commit -am "feat(auth-op): jib Docker build + image layering"
```

---

### Task 22: docker-compose + Caddyfile + env — drop-in under `identity` profile

**Files:**
- Modify: `docker-compose/docker-compose.yaml` — add `auth-op` service under `profiles: [identity, all]`
- Modify: `docker-compose/Caddyfile` — add `auth-op.theaustraliahack.com:443` vhost block
- Modify: `docker-compose/.env` — add `AUTH_OP_PORT=7005`, client secrets placeholders, realm secrets
- Create: `docker-compose/auth-op/config/realms.conf`
- Create: `docker-compose/auth-op/config/clients.conf`
- Create: `docker-compose/auth-op/config/dcql/citizens.dcql.json`

**Caddy block** (matches design — use existing `issuer.theaustraliahack.com:443` as template):

```caddy
auth-op.theaustraliahack.com:443 {
    tls internal
    reverse_proxy http://auth-op:{$AUTH_OP_PORT}
}
```

**docker-compose service** (model after `issuer-api`):

```yaml
auth-op:
  image: waltid/auth-op:${VERSION_TAG:-stable}
  profiles: [identity, all]
  pull_policy: missing
  depends_on: [caddy]
  env_file: [.env]
  environment:
    AUTH_OP_PORT: ${AUTH_OP_PORT:-7005}
    AUTH_OP_ISSUER: https://auth-op.theaustraliahack.com
    RP_THEAUSTRALIAHACK_SECRET: ${RP_THEAUSTRALIAHACK_SECRET}
    EMPLOYEES_OIDC_SECRET: ${EMPLOYEES_OIDC_SECRET}
  volumes:
    - ./auth-op/config:/app/config
  ports:
    - "${AUTH_OP_PORT:-7005}:${AUTH_OP_PORT:-7005}"
```

**Realms/clients config** — populate from design doc's drop-in example; point `issuer` at the in-tree Keycloak (`https://keycloak.theaustraliahack.com/realms/issuer`).

**Infra note (manual, out-of-repo):** add public-hostname `auth-op.theaustraliahack.com` → Caddy origin in the Cloudflare Zero Trust dashboard (tunnel is `TUNNEL_TOKEN`-managed; see `memory/infrastructure.md`). Test plan will fail at the external-URL step until this is done.

**Smoke:**
```bash
docker compose --profile identity up -d auth-op caddy
sleep 3
docker compose logs auth-op --tail 20 | grep -i "started"
curl -sk https://auth-op.theaustraliahack.com/health     # 200
curl -sk https://auth-op.theaustraliahack.com/.well-known/openid-configuration | jq '.issuer'
```

**Commit:**
```bash
git add docker-compose
git commit -m "feat(auth-op): docker-compose + Caddy vhost + config for auth-op.theaustraliahack.com"
```

---

## Phase 8 — End-to-end verification (task 23)

### Task 23: Manual verification against real Keycloak + verifier-api2

Walks through every row of the design's **Verification plan** against the running stack. No code here — this is a **checklist task**; produces a `docs/plans/2026-04-18-custom-oidc-op-verification.md` file recording which steps passed/failed with paste-outs of the critical bits (token JSON, redirect chain, webhook log).

If any step fails: stop, return to Phase 1 of the `superpowers:systematic-debugging` skill — fix root cause, re-verify.

**Commit:**
```bash
git add docs/plans/2026-04-18-custom-oidc-op-verification.md
git commit -m "docs(auth-op): e2e verification record"
```

---

## After all 23 tasks

Run `superpowers:requesting-code-review` on the diff. Then `superpowers:finishing-a-development-branch` to decide merge/PR strategy.

## Key references

- Design doc: [`docs/plans/2026-04-18-custom-oidc-op-design.md`](./2026-04-18-custom-oidc-op-design.md)
- Codex review: [`../../codex.feedback`](../../codex.feedback)
- Claude review & change log: [`../../claude.feedback`](../../claude.feedback)
- Verifier transactional mode: `waltid-services/waltid-verifier-api2/docs/TRANSACTIONAL_VERIFICATION.md`
- Existing OIDC RP pattern: `examples/rp-web-nextjs/auth.ts`, `examples/rp-widget-demo/server.js`
- Existing upstream-logout pattern: `examples/rp-widget-demo/server.js:292-309`
- Existing Keycloak client registration (drop-in target): `docker-compose/keycloak/realm-export.json`
- Crypto / signing reference call site: `waltid-libraries/protocols/waltid-openid4vp-verifier/.../VerificationSessionCreator.kt` (`.signJws()`)
- Memory with infra quirks: `~/.claude/projects/-Users-adambradley-Projects-Mastercard-India-waltid-identity/memory/infrastructure.md`
