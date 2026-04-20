# Integrating an OIDC Relying Party with `waltid-auth-op`

This guide is for operators and app developers wiring an existing or new OIDC
Relying Party (RP) against `waltid-auth-op`. The service is a standalone OIDC
Authorization Server with a realm discovery login UI; each realm authenticates
users via either classic upstream OIDC (e.g. Keycloak) or OID4VP (a successful
verifiable-presentation = login).

For the design rationale see
[`docs/plans/2026-04-18-custom-oidc-op-design.md`](../../../docs/plans/2026-04-18-custom-oidc-op-design.md).

## At a glance

- **Runtime URL (default):** `https://auth.theaustraliahack.com`
- **Service port (container):** `7005`
- **Discovery URL:** `https://auth.theaustraliahack.com/.well-known/openid-configuration`
- **Design stance:** drop-in replacement for Keycloak at the OIDC layer — most
  RPs integrate by changing one env var.
- **Non-negotiable flow constraints:**
  - `response_type=code` only
  - PKCE required (`code_challenge_method=S256`) — including for confidential
    clients
  - `scope` must include `openid`

## Endpoints advertised in discovery

| Endpoint | Path |
|---|---|
| `issuer` | `https://auth.theaustraliahack.com` |
| `authorization_endpoint` | `/authorize` |
| `token_endpoint` | `/token` |
| `userinfo_endpoint` | `/userinfo` |
| `jwks_uri` | `/jwks.json` |
| `end_session_endpoint` | `/end_session` |

Supported values:

| Metadata | Values |
|---|---|
| `response_types_supported` | `["code"]` |
| `response_modes_supported` | `["query"]` |
| `grant_types_supported` | `["authorization_code"]` |
| `code_challenge_methods_supported` | `["S256"]` |
| `token_endpoint_auth_methods_supported` | `["client_secret_basic", "client_secret_post", "none"]` |
| `scopes_supported` | `["openid", "profile", "email"]` |
| `id_token_signing_alg_values_supported` | `["RS256"]` |
| `subject_types_supported` | `["public"]` |

## Quick start — existing demos in this repo

Both `examples/rp-web-nextjs` and `examples/rp-widget-demo` have their callback
URLs pre-registered in
[`docker-compose/auth-op/config/clients.conf`](../../../docker-compose/auth-op/config/clients.conf)
(client id `rp_theaustraliahack`). Integration is one env-var flip per RP.

### NextAuth demo (`examples/rp-web-nextjs`)

The NextAuth config uses the built-in `Keycloak` provider. Keep the provider
name; only change the issuer:

```diff
- AUTH_KEYCLOAK_ISSUER=https://keycloak.theaustraliahack.com/realms/issuer
+ AUTH_KEYCLOAK_ISSUER=https://auth.theaustraliahack.com
  AUTH_KEYCLOAK_ID=rp_theaustraliahack
  AUTH_KEYCLOAK_SECRET=<same rp secret>
```

Callback URI stays `/api/auth/callback/keycloak` (registered).

### Widget demo (`examples/rp-widget-demo`)

Vanilla `oidc-client-ts` configuration. Point `OIDC_ISSUER` at auth-op:

```diff
- OIDC_ISSUER=https://keycloak.theaustraliahack.com/realms/issuer
+ OIDC_ISSUER=https://auth.theaustraliahack.com
```

Callback URI stays `/callback` (registered).

## Integrating a new RP

### 1. Register the client

Add an entry to
[`docker-compose/auth-op/config/clients.conf`](../../../docker-compose/auth-op/config/clients.conf):

```hocon
clients = [
  # ... existing entries ...
  {
    client_id = "your-app"
    # Optional — omit when token_endpoint_auth_method = "none"
    client_secret = ${?YOUR_APP_SECRET}
    token_endpoint_auth_method = "client_secret_basic"
    redirect_uris = ["https://your-app.example/callback"]
    post_logout_redirect_uris = ["https://your-app.example/*"]
    allowed_scopes = ["openid", "profile", "email"]
    # Optional — restricts which realms this client can authenticate against
    allowed_realms = ["employees", "citizens"]
    # true = first-party app, skip consent screen
    trusted = true
  }
]
```

Restart the service so the new client is loaded:

```bash
docker compose --profile identity up -d --force-recreate auth-op
```

### 2. Choose a `token_endpoint_auth_method`

| Method | When to use | Secret required |
|---|---|---|
| `client_secret_basic` | Confidential server-side apps (recommended) | Yes, via `Authorization: Basic` |
| `client_secret_post` | Confidential apps whose HTTP client can't set headers | Yes, in form body |
| `none` | Public clients: SPAs, mobile apps — PKCE is the only auth | No |

### 3. Point your OIDC client library at discovery

Any spec-compliant OIDC library should work. Feed the discovery URL:

```
https://auth.theaustraliahack.com/.well-known/openid-configuration
```

Tested / known-compatible:

- **NextAuth** (with `Keycloak` provider or a generic OIDC provider)
- **`oidc-client-ts`** / **`oidc-client-js`** (browser SPAs)
- **`openid-client`** (Node.js)
- **Spring Security** (OIDC client)
- **`golang.org/x/oauth2` + `github.com/coreos/go-oidc`**
- **Python `authlib`** / **`oauthlib`**

### 4. Build the authorize URL

Minimum required params:

```
GET /authorize
  ?response_type=code
  &client_id=your-app
  &redirect_uri=https://your-app.example/callback
  &scope=openid profile email
  &state=<CSRF token, echoed back>
  &code_challenge=<BASE64URL(SHA-256(code_verifier))>
  &code_challenge_method=S256
  &nonce=<random, included in ID token>
```

Optional:

- `prompt=none` — silent re-auth (fails fast with `error=login_required` if no
  session)
- `prompt=login` — force re-auth even if a session exists

### 5. Handle the callback

On success, auth-op redirects to your `redirect_uri` with:

```
?code=<one-time authorization code>&state=<your original state>
```

Exchange the code at the token endpoint:

```http
POST /token
Authorization: Basic <base64(client_id:client_secret)>
Content-Type: application/x-www-form-urlencoded

grant_type=authorization_code
&code=<code>
&redirect_uri=https://your-app.example/callback
&code_verifier=<the original PKCE verifier>
```

Response (200 OK):

```json
{
  "access_token": "<JWT>",
  "token_type": "Bearer",
  "expires_in": 3600,
  "id_token": "<JWT>",
  "scope": "openid profile email"
}
```

## ID token claims

Every ID token carries:

| Claim | Type | Source |
|---|---|---|
| `iss` | string | byte-exact match to discovery's `issuer` |
| `sub` | string | realm-dependent — see [Subject strategy](#subject-strategy) |
| `aud` | string | your `client_id` |
| `iat`, `exp` | number (epoch seconds) | standard |
| `auth_time` | number (epoch seconds) | when auth flow completed |
| `nonce` | string | echoed from your `/authorize` request (if sent) |
| `acr` | string | `"urn:walt:upstream-oidc"` (OIDC realm) or `"urn:walt:vp"` (VP realm) |
| `amr` | string array | propagated from upstream (OIDC) or `["swk"]` (VP) |
| `https://auth.theaustraliahack.com/realm` | string | the realm the user picked (e.g. `"employees"`, `"citizens"`). **Namespaced** by issuer URL to avoid collisions with other OPs. |

Plus any claims produced by the realm's `claim_mapping` — typically
`given_name`, `family_name`, `email`, etc.

### Subject strategy

The `sub` claim's stability depends on the realm's `sub_strategy`:

| Strategy | `sub` derivation | Stability |
|---|---|---|
| (OIDC realm) | Upstream `sub` passed through | Stable per user within the upstream IdP realm |
| `credential_subject_id` | `$.credentialSubject.id` from the VC | Stable only when the credential has a stable holder DID |
| `claim_hash` | `BASE64URL(SHA-256(realm_id ‖ NUL ‖ selected_claims))` | Deterministic across logins for the same person + realm; **pseudonymous across realms** (same person gets a different `sub` in a different realm) |
| `ephemeral` | Random 128-bit per login | Maximum privacy — no returning-user detection possible |

Configured per-realm in `docker-compose/auth-op/config/realms.conf`.

## UserInfo endpoint

Standard OIDC UserInfo. Call with the access token:

```http
GET /userinfo
Authorization: Bearer <access_token>
```

Returns `sub` plus scope-filtered claims per OIDC Core §5.4:

- `openid` (required) → `sub`
- `profile` → `name`, `given_name`, `family_name`, `middle_name`, `nickname`,
  `preferred_username`, `profile`, `picture`, `website`, `gender`, `birthdate`,
  `zoneinfo`, `locale`, `updated_at` (whichever are set)
- `email` → `email`, `email_verified`

Non-standard claims (`acr`, `amr`, `realm`) are **not** returned by `/userinfo` —
read them from the ID token instead.

## Logout

Redirect the user to the end-session endpoint:

```
GET /end_session
  ?client_id=your-app
  &id_token_hint=<your copy of the ID token>   # or omit if client_id is supplied
  &post_logout_redirect_uri=<registered URL>
  &state=<echoed back>
```

Behaviour depends on the session's realm:

- **OIDC realm:** auth-op chains to the upstream IdP's `end_session_endpoint`
  (with the upstream `id_token_hint` stored at login time), so the Keycloak
  session is also terminated. On return, the user is redirected to your
  `post_logout_redirect_uri`.
- **VP realm:** auth-op clears its session cookie and redirects directly.

### Security requirements

- `post_logout_redirect_uri` MUST match a registered pattern in
  `clients.conf` — patterns may use `/*` suffix for prefix matching (e.g.
  `https://your-app.example/*`). Operators must terminate the prefix with `/`
  before `*` to avoid `https://your-app.example.attacker/...` smuggling.
- `state` echoes back byte-exact; use it as CSRF protection.

## Known limitations (v1)

| Limitation | Impact | Workaround |
|---|---|---|
| **No refresh tokens** | Access tokens expire after 1h; RP must bounce through `/authorize` to renew | Browser session cookie means silent re-auth is invisible to the user. For mobile/native apps, plan for re-auth on each app launch. |
| **In-memory state** | Service restart logs everyone out mid-flow (pending `/authorize` requests and `/token` codes invalidate) | Don't restart during peak; a Valkey-backed store is scaffolded behind interfaces for a future release. |
| **No dynamic client registration** | New RPs require editing `clients.conf` + restart | Acceptable for a small demo population. |
| **No consent memory** | Non-trusted clients show the consent screen on every login | Use `trusted = true` for first-party RPs. |
| **Single signing key** | No rotation; JWKS exposes one key | Key is persisted (`config/signing-key.json`), stable across restarts. Rotation is future work. |
| **Forced realm selection** | Users always see the realm picker | A `realm` hint on `/authorize` is not supported yet. |
| **`id_token_hint` at `/end_session` not signature-verified** | A forged hint with a victim's `aud` can route logout to that client, but redirect URI must still match the client's registered patterns | Narrow attack surface; tracked as tech debt. |

## Operational prerequisites

Before the service is reachable externally:

1. **Cloudflare tunnel route** — `auth.theaustraliahack.com` must be added as
   a public hostname pointing at the Caddy origin in the Cloudflare Zero Trust
   dashboard. The tunnel is remote-managed via `TUNNEL_TOKEN` (not a code
   change).
2. **Keycloak client** — for the `employees` OIDC realm, register a
   confidential client in Keycloak's `issuer` realm named `auth-op` with
   redirect URI `https://auth.theaustraliahack.com/callback/oidc`.
3. **Real secrets** — replace the placeholder `EMPLOYEES_OIDC_SECRET` and
   `RP_THEAUSTRALIAHACK_SECRET` in `docker-compose/.env.local` (or via
   Doppler). Do not commit real secrets to `.env`.

## Troubleshooting

| Symptom | Likely cause |
|---|---|
| `error=invalid_request` on `/authorize` with `missing code_challenge` | Client library not sending PKCE; enable it (required even for confidential clients) |
| `error=invalid_client` at `/token` | Mismatch between configured `token_endpoint_auth_method` and how the RP sends credentials (Basic header vs body) |
| `error=invalid_grant` at `/token` | Code already consumed (single-use), expired (60 s TTL), PKCE verifier mismatch, or `redirect_uri` doesn't byte-match the one from `/authorize` |
| `error=login_required` on `/authorize` with `prompt=none` | User has no valid session cookie at auth-op; drop `prompt=none` to render the realm picker |
| 400 "unregistered redirect_uri" (plain HTML) | The `redirect_uri` in your request isn't in the client's `redirect_uris` list. auth-op deliberately does **not** redirect in this case (RFC 6749 §4.1.2.1); add the URI to `clients.conf`. |
| 502 from Cloudflare on `auth.theaustraliahack.com` | Tunnel route not configured (see Operational prerequisites #1) |
| Container fails to start: `Hoplite config error: 'issuer': Missing String from config` | `auth-op.conf` / `web.conf` missing under `docker-compose/auth-op/config/`; ensure both exist |

## References

- [Design doc](../../../docs/plans/2026-04-18-custom-oidc-op-design.md) — architecture, threat model, deliberate scope cuts
- [Implementation plan](../../../docs/plans/2026-04-18-custom-oidc-op-plan.md) — 23 tasks, phases, commit-level TDD
- OIDC Core 1.0 — <https://openid.net/specs/openid-connect-core-1_0.html>
- OIDC Discovery 1.0 — <https://openid.net/specs/openid-connect-discovery-1_0.html>
- RFC 7636 (PKCE) — <https://www.rfc-editor.org/rfc/rfc7636>
- RFC 6749 (OAuth 2.0) — <https://www.rfc-editor.org/rfc/rfc6749>
- OID4VP — <https://openid.net/specs/openid-4-verifiable-presentations-1_0.html>
