# Custom OIDC Provider with Realm Discovery — Design

**Date:** 2026-04-18
**Status:** Design — pending approval to plan implementation

## Context

We need a new standalone authentication service that downstream apps authenticate against via OpenID Connect. What makes it unusual is the login page: instead of a username/password form, users land on a **realm discovery page** and each realm picks one of two authentication methods:

1. **Classic OIDC** — the auth-op acts as an OIDC client to an upstream IdP (Keycloak, Google, etc.), relays the login, and mints its own tokens.
2. **OID4VP (OpenID for Verifiable Presentations)** — the user presents a verifiable credential via `waltid-verifier-api2`. **A successful presentation IS the login.** No password, no second step.

This fits the walt.id stack — `waltid-verifier-api2` already handles VP verification, Keycloak is in-tree for OIDC, and `waltid-openid4vc` provides protocol data models. No existing walt.id service exposes a classic OIDC OP surface (the issuer-api's `/token` is OID4VCI-specific), so this is new surface area.

## Non-goals (explicit cuts)

These are deliberate scope cuts for v1, already agreed:

| Area | Decision | Rationale |
|---|---|---|
| Dynamic client registration | Cut | Static HOCON-like client list; matches existing `RelyingPartyStore` pattern |
| Refresh tokens | Cut | In-memory store can't persist them; expired access tokens re-auth through our session |
| End-session | **Include (OIDC-aware)** | Clear our cookie; for OIDC realms, chain upstream logout via `end_session_endpoint` + `id_token_hint` — pattern already used by `rp-widget-demo/server.js` |
| Consent screen | Include | With `trusted: true/false` per client; trusted clients skip the page |
| External state store (Valkey) | Cut | In-memory `ConcurrentHashMap`s; restart = everyone re-logs in |
| Multi-tenant partitioning | Cut | Single issuer URL; realm is an ID-token claim, not a path segment |

## Architecture

New Kotlin/Ktor service: **`waltid-services/waltid-auth-op`** on port **7005**. Matches project stack, reuses `waltid-openid4vc` for OIDC protocol primitives and the project's `id.walt.crypto` abstraction for JWT signing (not Nimbus directly — Nimbus is used only for cert/JWK parsing in this codebase).

```
  Downstream RP ──(OIDC auth-code + PKCE)──▶ auth-op (/authorize)
                                                   │
                                           /login (realm list)
                                                   │
                          ┌────────────────────────┴────────────────────────┐
                          ▼                                                 ▼
             Classic OIDC path                                      OID4VP path
  ────────────────────────────────────               ─────────────────────────────────
  redirect to realm's OIDC IdP                       POST verifier-api2 /verification-session/create
       (Keycloak / Google / any)                     render QR (bootstrap URL) + SSE
  callback with code → exchange → claims             on SUCCESSFUL → read presentedCredentials
                          └────────────────────────┬────────────────────────┘
                                                   ▼
                                     /consent (skipped for trusted clients)
                                                   │
                                    bind subject + claims to auth code
                                                   │
                                  302 back to RP with ?code=&state=
                                                   │
                                    RP: POST /token → id_token + access_token
```

**Single issuer URL:** `https://auth.theaustraliahack.com` (byte-exact, no trailing slash — whatever appears in `.well-known/openid-configuration#issuer` must be identical to `iss` in every issued token). Realm surfaced in ID token as custom claim + `acr` for the auth context class.

## Components

### Realm registry (static config)

Realms live in `config/realms.conf` (HOCON), loaded at boot. DCQL queries are kept in separate files (they're verbose) and referenced by path.

```hocon
realms = [
  {
    id = "employees"
    name = "Employees"
    method = "oidc"
    oidc = {
      issuer = "https://keycloak.theaustraliahack.com/realms/issuer"
      client_id = "auth-op"
      client_secret = "${EMPLOYEES_OIDC_SECRET}"
      scopes = ["openid", "profile", "email"]
      # /.well-known/openid-configuration fetched and cached at boot
    }
    claim_mapping = {
      sub = "$.sub"
      email = "$.email"
      name = "$.name"
    }
  },
  {
    id = "citizens"
    name = "Citizens"
    method = "oid4vp"
    oid4vp = {
      verifier_base_url = "https://verifier2.theaustraliahack.com"
      dcql_query_file = "config/dcql/citizens.dcql.json"
      # rpId optional — lookup for signing keys in verifier-api2
      rp_id = null
      # auth-op posts a webhook URL into the verifier session so credentials
      # are delivered server-to-server. Works in transactional mode (where
      # credential data is cleared from session state after delivery).
      webhook_callback_path = "/login/realm/citizens/webhook"
    }
    sub_strategy = "claim_hash"            # see "Subject strategy" below
    claim_mapping = {
      # JSONPath is applied against the first DigitalCredential in
      # presentedCredentials; realm is responsible for picking a mapping that
      # makes sense for the credential type it requires via DCQL.
      given_name = "$.credentialSubject.given_name"
      family_name = "$.credentialSubject.family_name"
      birth_date = "$.credentialSubject.birth_date"
    }
    sub_source_claims = ["given_name", "family_name", "birth_date"]  # for claim_hash
  }
]
```

**Subject strategy** (per realm, VP path only):
- `credential_subject_id` — use `$.credentialSubject.id` from the VC. Stable only when the credential has a stable holder DID.
- `claim_hash` — SHA-256 over concatenated values of `sub_source_claims`, Base64Url-encoded. Deterministic for the same person across logins, pseudonymous across realms.
- `ephemeral` — random per login. Maximum privacy; RPs cannot recognise returning users.

OIDC-path realms always use `$.sub` from the upstream ID token (stable within that IdP).

Claim mapping uses `com.eygraber:jsonpathkt-kotlinx` (already on the project classpath).

### Client registry (static config)

**Drop-in intent:** auth-op is designed as a drop-in replacement for Keycloak as the OIDC provider in the existing demos. RPs keep their current callback URIs and provider identifiers — only their `issuer` env var (e.g. `AUTH_KEYCLOAK_ISSUER`) is repointed at `https://auth.theaustraliahack.com`. The `redirect_uris` below mirror what's already in `docker-compose/keycloak/realm-export.json#rp-theaustraliahack` so no RP-side changes are needed to demo.

```hocon
clients = [
  {
    client_id = "rp-theaustraliahack"                       # same as the existing Keycloak client id
    client_secret = "${RP_THEAUSTRALIAHACK_SECRET}"
    token_endpoint_auth_method = "client_secret_basic"      # also: client_secret_post, none (PKCE-only public clients)
    redirect_uris = [
      "https://rp.theaustraliahack.com/api/auth/callback/keycloak",   # NextAuth demo (provider name stays 'keycloak')
      "https://rp.theaustraliahack.com/callback",                      # widget demo
      "http://localhost:3000/api/auth/callback/keycloak",
      "http://localhost:7020/api/auth/callback/keycloak",
      "http://localhost:4000/callback"
    ]
    post_logout_redirect_uris = [
      "https://rp.theaustraliahack.com/*",
      "http://localhost:3000/*",
      "http://localhost:7020/*",
      "http://localhost:4000/*"
    ]
    allowed_scopes = ["openid", "profile", "email"]
    allowed_realms = ["employees", "citizens"]              # restricts which realms this RP can use; default = all
    trusted = true                                           # skip consent screen
  }
]
```

Out of scope for v1 (and a follow-up if desired): renaming the NextAuth provider from `keycloak` to `waltid` across `examples/rp-web-nextjs/auth.ts` and related env wiring. Keeping the name lets existing demos work unchanged.

### Core protocol endpoints (v1 minimum)

| Method | Path | Purpose |
|---|---|---|
| GET | `/.well-known/openid-configuration` | OP metadata (exact-match `issuer`, declared endpoints, `response_types_supported=["code"]`, `response_modes_supported=["query"]`, `grant_types_supported=["authorization_code"]`, `code_challenge_methods_supported=["S256"]`, `token_endpoint_auth_methods_supported=["client_secret_basic","client_secret_post","none"]`, `scopes_supported=["openid","profile","email"]`) |
| GET | `/jwks.json` | Public signing keys |
| GET | `/authorize` | Auth request entry — validates client/redirect/scope/PKCE, handles `prompt` param |
| GET | `/login` | Realm discovery page |
| GET/POST | `/login/realm/{realmId}` | Initiates realm's chosen auth method |
| GET | `/login/realm/{realmId}/status` | Page-accessible status polling (reads auth-op's own `VpSessionStore`) |
| POST | `/login/realm/{realmId}/webhook` | **Server-to-server** from verifier-api2; delivers credential data. Authenticated by webhook secret registered at session creation |
| GET | `/login/realm/{realmId}/complete` | Finalise VP login — session-cookie-bound |
| GET | `/callback/oidc` | Upstream OIDC callback (for OIDC realms) |
| GET | `/consent` | Consent screen (skipped for `trusted: true` clients) |
| POST | `/consent` | Consent form submission |
| POST | `/token` | Auth code → JWT ID token + access token |
| GET | `/userinfo` | Userinfo from access token — same claim set as ID token, filtered by scopes granted on the access token |
| GET | `/end_session` | For VP-realm sessions: clear cookie + redirect. For OIDC-realm sessions with a stored upstream `id_token`: redirect to upstream `end_session_endpoint` with `id_token_hint` + `post_logout_redirect_uri=<our /end_session/upstream_return>`; on return, clear our cookie and redirect to the RP's registered `post_logout_redirect_uri` |
| GET | `/end_session/upstream_return` | Callback for upstream logout chain; finalises logout by clearing our cookie and redirecting to the RP |

**Supported parameters on `/authorize`:**
- `response_type=code` only (others → `unsupported_response_type` error)
- `response_mode=query` only (others → `unsupported_response_type`)
- `code_challenge` + `code_challenge_method=S256` required (including for confidential clients — defence in depth)
- `prompt` understood: `none`, `login`, default (empty)
- `scope` must include `openid`; `profile` / `email` optional with standard OIDC Core §5.4 claim expansion

### Scope → claim expansion (OIDC Core §5.4)

| Scope | Claims released |
|---|---|
| `openid` | `sub` |
| `profile` | `name`, `given_name`, `family_name`, `middle_name`, `nickname`, `preferred_username`, `profile`, `picture`, `website`, `gender`, `birthdate`, `zoneinfo`, `locale`, `updated_at` (whichever are actually available post-mapping) |
| `email` | `email`, `email_verified` |

Any additional claims produced by `claim_mapping` beyond these scopes are released only if explicitly requested via `claims` parameter — out of scope for v1; for v1 they are dropped with a log line.

### State (in-memory, TTL'd)

- `AuthRequestStore` — keyed by internal `authRequestId`. Holds `client_id`, `redirect_uri`, requested `scope`, PKCE challenge, nonce, state, `prompt`, realm choice (once made), login status. TTL: 10 min.
- `AuthCodeStore` — keyed by the code string. Holds subject, claim set, `client_id`, `redirect_uri`, PKCE challenge. TTL: 60s. Single-use (deleted on redemption).
- `SessionStore` — keyed by session cookie ID. Holds subject, realm, `amr` / `acr`, `auth_time`, and for OIDC-realm sessions the upstream `id_token` (used as `id_token_hint` on logout; never issued downstream). TTL: configurable (default 1 h).
- `VpSessionStore` — keyed by verifier-api2 session ID. Holds `{verifierSessionId, realmId, authRequestId, sessionCookieId, webhookSecret, status, capturedCredential}` where `capturedCredential` is populated by the webhook handler on `policy_results_available` and cleared on consumption in `/complete`. TTL: 10 min.

All behind thin interfaces so a Valkey-backed implementation slots in cleanly later.

## Login flows

### Classic OIDC path

1. RP redirects to `GET /authorize?...&response_type=code&code_challenge=...&code_challenge_method=S256`.
2. Validate client, redirect_uri, scopes, PKCE. Create `AuthRequest`, set session cookie `sid`, redirect to `/login`.
3. `/login` checks session:
   - **If `prompt=none` and no valid session** → redirect to RP with `error=login_required`.
   - **If valid session and `prompt` is empty** → skip straight to `/consent` (SSO).
   - Otherwise render realm list.
4. User picks realm `employees` (`method=oidc`). Generate upstream `state` + `nonce`, redirect to upstream `/authorize` with our `redirect_uri = https://auth.../callback/oidc`.
5. User authenticates at upstream, returns to `/callback/oidc?code=&state=`.
6. Validate state matches what we stashed. Exchange upstream code for tokens at upstream `/token`. Verify upstream ID token: signature against upstream JWKS (cached, short TTL), `iss`, `aud`, `exp`, `nonce`.
7. Optionally call upstream `/userinfo` if `claim_mapping` references claims not in the ID token.
8. Apply `claim_mapping` → our claim set. Propagate upstream `amr` if present; otherwise default `amr: ["pwd"]` (unknown). Set `acr: "urn:walt:upstream-oidc"`.
9. Create user session. Resume the original AuthRequest. → `/consent` or skip if trusted.

### OID4VP path

**Critical constraint:** `waltid-verifier-api2` may run in *transactional verification mode*, where credential data is delivered via SSE/webhook during the final event (`policy_results_available`) and then **cleared** from session state (`TRANSACTIONAL_VERIFICATION.md:91-94, 117-118`). Reading `/verification-session/{id}/info` after `SUCCESSFUL` returns a session with `presentedCredentials: null` in transactional mode. The design therefore captures credential data server-to-server via webhook during the verifier session, **never** by re-reading session info after the fact. This works in both transactional and persisted modes.

1. RP → `/authorize` → realm list (same start).
2. User picks realm `citizens` (`method=oid4vp`). Server-side auth-op:
   - Load DCQL from `dcql_query_file`.
   - Generate a short-lived **webhook secret** bound to this pending login, store in `VpSessionStore`.
   - `POST verifier-api2 /verification-session/create` (optionally `?rpId=...`) with `flow_type`, `dcqlQuery`, and a notifications block registering `{webhook_url: "https://auth.theaustraliahack.com/login/realm/citizens/webhook", webhook_secret}`.
   - Response: `{ sessionId, bootstrapAuthorizationRequestUrl, fullAuthorizationRequestUrl }`.
   - Store `{verifierSessionId, authRequestId, sessionCookieId, webhookSecret, status: PENDING, capturedCredential: null}` in `VpSessionStore`.
   - Render a page with QR for `bootstrapAuthorizationRequestUrl` + `openid4vp://` deep-link. Embed `verifierSessionId` only (not the webhook secret).
3. Page JS polls `GET /login/realm/{realmId}/status?verifierSessionId=...` every 2 s. Optionally also subscribes to an SSE stream at the same path on auth-op (server-relayed from our own captured state — not from verifier-api2 directly) for lower-latency UX. SSE drop → polling still works, so SSE is nice-to-have, not load-bearing.
4. **User flow:** wallet scans QR → builds VP → POSTs VP to verifier's response endpoint → verifier verifies.
5. **Webhook POST `/login/realm/{realmId}/webhook`:** verifier-api2 POSTs the session event payload to our webhook URL. Auth-op:
   - Verifies the request by checking the webhook secret matches what we registered. **This is the primary authenticity check** for credential data.
   - Inspects the event type. On `policy_results_available` (the final event with credential data per `TRANSACTIONAL_VERIFICATION.md:129`), extracts `newSession.presentedCredentials` and `newSession.presentedPresentations` and stores them in `VpSessionStore[verifierSessionId].capturedCredential`.
   - Marks status `SUCCESSFUL` (or `UNSUCCESSFUL`) based on the event / `newSession.status`.
   - Responds `200 OK` quickly (keep handler lightweight; verifier retries on 5xx).
6. **Recovery path:** when the page loads/reloads, if `VpSessionStore[verifierSessionId].status` is already `SUCCESSFUL`, the status endpoint reports it immediately; JS redirects to `/complete`. Handles page refresh / JS-crash after webhook arrived.
7. Page JS sees `SUCCESSFUL` from the status endpoint → navigates to `GET /login/realm/citizens/complete` (same-origin, session-cookie-bound).
8. Server-side on `/complete`:
   - Validate the browser's `sid` cookie matches `VpSessionStore[verifierSessionId].sessionCookieId`. **This binding is the primary defence against hijack** — a leaked `verifierSessionId` can't be redeemed from a different browser.
   - Require `status == SUCCESSFUL` and `capturedCredential != null` in our own store. **Do not re-fetch `/verification-session/{id}/info`** — under transactional mode that would return stripped data.
   - Walk `capturedCredential.presentedCredentials: Map<String, List<DigitalCredential>>` — pick the first credential matching the DCQL intent. Apply `claim_mapping`.
   - Derive `sub` using `sub_strategy`:
     - `credential_subject_id` → `$.credentialSubject.id`
     - `claim_hash` → `BASE64URL(SHA-256(realm.id || "\0" || joinNul(claimMapping.sub_source_claims)))`
     - `ephemeral` → random 128-bit ID
   - Set `amr: ["swk"]` (software-backed key — closest RFC 8176 value for wallet-held key), `acr: "urn:walt:vp"`.
   - Create user session. Resume the original AuthRequest. → `/consent` or skip if trusted.
   - Delete `capturedCredential` from `VpSessionStore` (no retention past consumption; credential data is sensitive).

Key insight: **the VP result itself establishes identity**. No separate credential check. Credentials arrive via an authenticated server-to-server webhook — this survives transactional verifier mode and keeps credential payloads off the browser. The `sub` comes from a realm-configured strategy; the additional claims come from applying `claim_mapping` to the captured credential.

**Implementation note on verifier-api2 webhook support:** confirm the exact field name for webhook registration in `VerificationSessionSetupData`/notifications when implementing — the spec doc shows the mechanism exists (`notifications` block with `webhook_url`), but the field naming should be read from code not prose at build time.

### Consent + code issuance (common tail)

1. `/consent` shows client name + requested scopes. **Skipped for `trusted: true` clients.**
2. On approval: generate 256-bit auth code, store in `AuthCodeStore` with subject + claim set + PKCE challenge, TTL 60 s.
3. Redirect `redirect_uri?code=&state=` (state echoed byte-for-byte from original `/authorize`).
4. RP calls `POST /token` with `grant_type=authorization_code`, `code`, `code_verifier`, client auth per its `token_endpoint_auth_method`.
5. Validate code (exists, not consumed, matches client + redirect), PKCE, client auth. Mint tokens. Delete code.

### ID token claims (VP-path example)

```json
{
  "iss": "https://auth.theaustraliahack.com",
  "sub": "PEF3ZDA...Base64UrlClaimHash",
  "aud": "rp-nextjs-demo",
  "iat": 1777000000,
  "exp": 1777003600,
  "auth_time": 1777000000,
  "nonce": "<RP nonce>",
  "amr": ["swk"],
  "acr": "urn:walt:vp",
  "https://auth.theaustraliahack.com/realm": "citizens",
  "given_name": "Alice",
  "family_name": "Smith",
  "birth_date": "1990-01-01"
}
```

Custom `realm` claim is namespaced by our issuer URL to avoid collision with other OPs' custom claims.

## Error handling (OIDC error responses)

Redirect to RP with `?error=&error_description=&state=` when the error is on a valid `(client_id, redirect_uri)` pair. Plain HTTP error (no redirect) when the request itself is untrusted.

| Condition | HTTP response |
|---|---|
| Invalid / unknown `client_id` | 400, rendered error page (**no redirect** — RFC 6749 §4.1.2.1) |
| Unregistered `redirect_uri` | 400, rendered error page (same reason) |
| Missing / invalid PKCE | 302 to RP, `error=invalid_request` |
| `response_type` ≠ `code` | 302 to RP, `error=unsupported_response_type` |
| `prompt=none` but no session | 302 to RP, `error=login_required` |
| User cancels at realm list | 302 to RP, `error=access_denied`, description `"user cancelled login"` |
| Upstream OIDC returns error | 302 to RP, `error=access_denied`, description `"upstream: <upstream error>"` |
| VP session `UNSUCCESSFUL` | 302 to RP, `error=access_denied`, description `"presentation did not satisfy requirements"` |
| AuthRequest TTL expired | 302 to RP if known, else 400 |
| Invalid / expired auth code at `/token` | `400` with `{"error": "invalid_grant"}` |
| Invalid client credentials at `/token` | `401` with `{"error": "invalid_client"}` (and `WWW-Authenticate: Basic` if `client_secret_basic`) |
| Token endpoint missing PKCE verifier | `400` with `{"error": "invalid_grant"}` |
| `/userinfo` with invalid token | `401` with `WWW-Authenticate: Bearer error="invalid_token"` |
| Webhook secret mismatch on `/login/realm/*/webhook` | `401` (no body); `VpSessionStore` unchanged |
| Webhook for unknown `verifierSessionId` | `404`; do not create new state from webhook data |

All redirect-based errors echo `state` byte-for-byte. All errors log the `authRequestId` + correlation ID without logging tokens, codes, or claim values.

## Security considerations

- **PKCE required** on all clients, including confidential ones.
- **State param required**; CSRF defence on both our `/authorize` and upstream OIDC callbacks.
- **Nonce required** for OIDC realms; upstream nonce validated; separate nonce threaded to our downstream ID token.
- **Redirect URI: exact match** against `redirect_uris`. No wildcards, no substring, no scheme/port substitution.
- **Upstream ID token validation:** signature against cached JWKS (TTL ≤ 5 min), plus `iss`, `aud`, `exp`, `nonce`. Userinfo is never trusted alone — it's unsigned.
- **VP completion binding:** `/login/realm/{id}/complete` requires the browser's `sid` cookie to match the `sessionCookieId` stored when the verifier session was created. Anyone who learns the `verifierSessionId` still cannot complete login from another browser.
- **Signing keys:** `config/signing-key.json` is auto-generated on first startup and reused on subsequent starts (stable JWKS across restarts so RP caches don't go stale). Keys are RSA-2048 by default; config can override to EC P-256. Prod deployments should mount an externally-managed key file. **No key rotation in v1** — JWKS exposes a single active key.
- **Cookies:** `sid` is `HttpOnly`, `Secure`, `SameSite=Lax`. `Max-Age` matches session TTL.
- **CSRF on forms:** `/consent` POST uses a per-request CSRF token embedded as a hidden field.
- **Logging hygiene:** never log tokens, codes, full claim values, or PKCE verifiers. Log `authRequestId`, `client_id`, `realm`, and redacted subject prefixes only.
- **Upstream logout for OIDC realms:** when completing an OIDC-realm login we store the upstream `id_token` in the session (not issued downstream — used only as `id_token_hint` for logout). `/end_session` uses it to chain upstream `end_session_endpoint`. Pattern already in use by `examples/rp-widget-demo/server.js:292-309`. Registered `post_logout_redirect_uris` must exist in the upstream Keycloak realm (`docker-compose/keycloak/realm-export.json` already lists the demo URIs). For VP realms there is no upstream session, so we just clear our cookie.
- **Webhook authentication:** the VP credential-capture webhook is authenticated by a per-session secret we generated at session creation and passed to verifier-api2. Reject requests whose secret doesn't match the pending `verifierSessionId`. Do not accept webhooks for session IDs not in our `VpSessionStore`.

## Tech stack

- **Runtime:** Kotlin + Ktor 3 — matches every other walt.id service.
- **JWT/JWS signing:** project's `id.walt.crypto` (`KeyManager`, `DirectSerializedKey.signJws()`) — consistent with how `VerificationSessionCreator` and `CIProvider` sign. Nimbus JOSE only for cert / JWK parsing where needed.
- **OIDC protocol types:** `waltid-libraries/waltid-openid4vc`.
- **JSONPath:** `com.eygraber:jsonpathkt-kotlinx:3.0.2` — already a project dep.
- **Templating:** Ktor HTML DSL for the handful of pages (`/login`, `/consent`, the VP-QR page). Good enough for v1; swap to a template engine or static SPA for real theming later.
- **HTTP client:** Ktor Client for upstream OIDC + verifier-api2 calls.
- **Config:** HOCON via `config4k`, matching other walt.id services.
- **Build:** Gradle subproject. Jib for Docker image.

## Infrastructure prerequisites

- **Port:** expose 7005 inside docker, publish as `${AUTH_OP_PORT:-7005}`.
- **Caddy vhost** at `auth.theaustraliahack.com:443` pattern matching the existing `issuer.theaustraliahack.com:443` block:
  ```caddy
  auth.theaustraliahack.com:443 {
      tls internal
      reverse_proxy http://auth-op:{$AUTH_OP_PORT}
  }
  ```
- **Cloudflare tunnel** — remote-managed via `TUNNEL_TOKEN` (see `memory/infrastructure.md`). Needs a public-hostname entry in the Cloudflare Zero Trust dashboard mapping `auth.theaustraliahack.com` → the caddy origin. **This must be done manually by someone with dashboard access; no code change makes it happen.**
- **Keycloak OIDC client** — in the chosen Keycloak realm, register a client with:
  - Client ID matching the realm config (e.g. `auth-op`)
  - `Confidential` access type
  - Valid redirect URI: `https://auth.theaustraliahack.com/callback/oidc`
  - Client secret → supplied via env to auth-op as `EMPLOYEES_OIDC_SECRET` etc.

## Files that will be created

```
waltid-services/waltid-auth-op/
├── build.gradle.kts
├── Dockerfile / jib config
├── src/main/kotlin/id/walt/authop/
│   ├── Main.kt
│   ├── config/
│   │   ├── AuthOpConfig.kt
│   │   ├── RealmRegistry.kt
│   │   └── ClientRegistry.kt
│   ├── domain/
│   │   ├── AuthRequest.kt
│   │   ├── AuthCode.kt
│   │   ├── Session.kt
│   │   └── Claims.kt
│   ├── endpoints/
│   │   ├── DiscoveryRoutes.kt            # .well-known, jwks
│   │   ├── AuthorizeRoutes.kt            # /authorize, /login, prompt handling
│   │   ├── OidcCallbackRoutes.kt         # /callback/oidc
│   │   ├── VpFlowRoutes.kt               # /login/realm/{id}, /status, /complete
│   │   ├── ConsentRoutes.kt              # /consent
│   │   ├── TokenRoutes.kt                # /token, /userinfo
│   │   └── EndSessionRoutes.kt           # /end_session
│   ├── store/
│   │   ├── AuthRequestStore.kt
│   │   ├── AuthCodeStore.kt
│   │   ├── SessionStore.kt
│   │   └── VpSessionStore.kt
│   ├── tokens/
│   │   ├── KeyProvider.kt                # load-or-generate signing key
│   │   └── JwtIssuer.kt                  # mints ID/access tokens via DirectSerializedKey
│   ├── upstream/
│   │   ├── OidcClient.kt                 # generic OIDC client w/ discovery + JWKS cache
│   │   └── Verifier2Client.kt            # thin HTTP wrapper: create, info, SSE proxy-or-direct
│   ├── claims/
│   │   ├── ClaimMapper.kt                # jsonpathkt-kotlinx
│   │   └── SubStrategy.kt                # credential_subject_id | claim_hash | ephemeral
│   ├── errors/
│   │   └── OidcError.kt                  # error enum + RP-redirect / plain-error dispatch
│   └── templates/                         # Ktor HTML DSL
│       ├── LoginPage.kt
│       ├── ConsentPage.kt
│       ├── VpQrPage.kt
│       └── ErrorPage.kt
├── src/main/resources/
│   ├── logback.xml
│   └── static/                            # any CSS / logo assets
├── src/test/kotlin/...
docker-compose/
├── auth-op/config/realms.conf
├── auth-op/config/clients.conf
├── auth-op/config/dcql/citizens.dcql.json
└── docker-compose.yaml                    # adds auth-op service under 'identity' profile, Caddyfile vhost
```

## Testing

- **Unit:**
  - Config loading (realms, clients), roundtrip through typed models.
  - `ClaimMapper` — OIDC-style payloads + VP `DigitalCredential` payloads.
  - `SubStrategy` — all three modes; determinism of `claim_hash`.
  - `KeyProvider` — generate-then-reload; existing key preserved.
  - `JwtIssuer` — correct `iss`/`aud`/`exp`/signature.
  - Each store — TTL, eviction, single-use code semantics.
- **Integration (JUnit 5 + Ktor test app + Mokkery):**
  - Classic OIDC flow end-to-end, mocked upstream IdP.
  - OID4VP flow end-to-end, mocked `Verifier2Client` driving SSE transitions.
  - `prompt=none` without session → `login_required`.
  - `prompt=none` with session → silent auth.
  - `prompt=login` with session → forced re-auth.
  - Consent flow for `trusted: false`; skip for `trusted: true`.
  - PKCE: happy path, tampered verifier → `invalid_grant`, missing code_challenge → `invalid_request`.
  - Error redirect logic: every row of the error table.
  - VP completion binding: cookie mismatch → rejected.
  - `/end_session` clears cookie + redirects to registered `post_logout_redirect_uri`.
- **Manual verification (against real stack):** stand up against Keycloak (OIDC path) and verifier-api2 (VP path), use `rp-nextjs-example-1` or `rp-widget-example-1` as the RP, run both realms end-to-end.

## Open questions / future work

- **Subject continuity across methods:** same person logging in via `employees` (Keycloak) and `citizens` (VP) will get different `sub`s by design. Federation / linking is a separate feature.
- **Consent memory:** returning users to non-trusted clients see the consent screen every time. Adding memory requires external storage (cut #4).
- **Back-channel logout / multi-RP session coordination:** currently `/end_session` only propagates to the upstream IdP that authenticated the current session, not to other RPs the user may be logged into via auth-op.
- **Rate limiting** on `/token`, `/authorize`, `/login/realm/*/complete`.
- **Multi-instance / HA:** requires external store.
- **Theming:** swap Ktor HTML DSL for a real template engine or a separate SPA frontend.
- **Observability:** structured logs only in v1; OpenTelemetry wiring later.
- **Key rotation:** add multiple active keys in JWKS, rotate by overlap.

## Verification plan

1. Build the service, `docker compose --profile identity up -d auth-op`.
2. `curl https://auth.theaustraliahack.com/.well-known/openid-configuration` → valid JSON, `issuer` byte-matches what's in the config.
3. `curl https://auth.theaustraliahack.com/jwks.json` → valid JWKS with one RSA key.
4. Point `rp-nextjs-example-1` at auth-op as its OIDC provider (env vars for discovery URL, client_id, client_secret).
5. **Classic flow:** Login in RP → `/login` → pick `employees` → Keycloak login → back to RP. Decode ID token, confirm `iss`, `aud`, `realm="employees"`, `acr="urn:walt:upstream-oidc"`, mapped claims present.
6. **VP flow:** Login → pick `citizens` → scan QR with walt.id demo wallet holding a configured credential → back to RP. Decode ID token, confirm `realm="citizens"`, `acr="urn:walt:vp"`, `amr=["swk"]`, `sub` matches expected strategy output, credential claims present.
7. **Consent:** second client with `trusted: false` → consent page shown; accept → complete.
8. **End-session — OIDC realm:** after a Keycloak login, RP calls `/end_session?id_token_hint=...&post_logout_redirect_uri=...`. Expect a redirect chain: auth-op → upstream Keycloak `end_session_endpoint` → back to auth-op's `/end_session/upstream_return` → RP's `post_logout_redirect_uri`. After this, next `/authorize` → realm pick → Keycloak shows login (no silent SSO). Verifies the upstream chain.
9. **End-session — VP realm:** after a VP login, RP calls `/end_session`. No upstream hop. Cookie cleared, redirect straight to RP. Next `/authorize` → login page.
10. **`prompt=none`:** from cold, `/authorize?...&prompt=none` → redirect back to RP with `error=login_required`. After a fresh login, `prompt=none` returns code silently.
11. **Error paths:** truncated PKCE challenge → `invalid_request`; request with unregistered `redirect_uri` → 400 page (no redirect).
12. **Webhook authenticity:** POST to `/login/realm/citizens/webhook` with a wrong or missing webhook secret → 401, `VpSessionStore` unchanged. With correct secret for a non-existent `verifierSessionId` → 404.
13. **Transactional-mode compatibility:** enable transactional verification in verifier-api2 (see `TRANSACTIONAL_VERIFICATION.md`), re-run the VP flow — login still succeeds end-to-end because credentials were captured via webhook, not by re-reading session info.
