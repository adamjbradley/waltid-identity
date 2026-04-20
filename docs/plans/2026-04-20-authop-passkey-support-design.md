# auth-op passkey support — design

## Context

After a user completes the OID4VP flow at `/login/realm/citizens`, auth-op derives a stable `sub` (hash of given_name + family_name + birth_date per realm config) and mints an OIDC code. This works, but every return visit requires presenting the wallet credential again — heavy for repeat logins.

This feature adds FIDO2 / WebAuthn passkeys as a **convenience second auth method** tied to the existing sub.

## Invariants

1. **Wallet VP is the only path to issue a passkey.** A fresh passkey can only be registered at the end of a successful `/login/realm/citizens` wallet verify. No other registration path.
2. **Passkeys are additive**. Registering on a new device does not invalidate passkeys on previous devices. Each sub can accumulate multiple passkeys.
3. **Passkeys authorise the same sub** the wallet did. No new identity is minted from the passkey path — the sub was already established by the wallet VP.

## UX

### Returning-user entry (conditional UI)
`/login/realm/citizens` loads as today (QR + polling). On page load the browser runs:

```
navigator.credentials.get({
  mediation: 'conditional',
  publicKey: <request_options_from_server>
})
```

If the user agent has a passkey for `auth-op.theaustraliahack.com`, the browser surfaces a "sign in with passkey" prompt silently (typically via autofill UI or OS sheet). If the user uses it, the page posts the assertion and lands on the OIDC `redirect_uri` directly, never touching the wallet QR. If no passkey, the user simply scans the QR as today.

### Post-wallet-verify registration
After VP succeeds, the existing handler redirects to a new `/register-passkey` page (same sid cookie). That page runs `navigator.credentials.create()` and then posts the attestation. On success or "Skip" it redirects to the OIDC callback with the code auth-op already minted for this sid.

## Architecture

### RP ID
`auth-op.theaustraliahack.com` (strict, no cross-subdomain).

### Storage
JSON file at `/waltid-auth-op/data/passkeys.json` — a single-file credential registry:

```json
{
  "credentials": [
    {
      "sub": "<claim_hash>",
      "credentialId": "<base64url>",
      "publicKeyCose": "<base64url>",
      "signatureCount": 0,
      "displayName": "Sarah Mitchell",
      "createdAt": "2026-04-20T10:00:00Z"
    }
  ]
}
```

Writes are atomic (write to `.tmp`, rename). The host path is bind-mounted from the Windows repo's `docker-compose/auth-op/data/` so restarts preserve state.

### Library
`com.yubico:webauthn-server-core` — handles ceremony construction, attestation verification, assertion verification, challenge generation.

### Endpoints
- `POST /webauthn/register/begin` (sid cookie required, VP already complete) → `PublicKeyCredentialCreationOptions`
- `POST /webauthn/register/complete` → stores credential, returns 200
- `POST /webauthn/login/begin` (no sid) → `PublicKeyCredentialRequestOptions` with empty `allowCredentials` (discoverable-credentials flow)
- `POST /webauthn/login/complete` → verifies assertion, resolves sub, establishes sid + mints OIDC code same path as VP completion

### Files
- `PasskeyStore.kt` — file-backed registry
- `PasskeyService.kt` — RelyingParty wrapper, ceremony construction
- `WebauthnRoutes.kt` — the four endpoints above
- `RegisterPasskeyPage.kt` — HTML template for the post-verify registration page
- Updates to `LoginRoutes.kt` (inject conditional UI JS), `VpFlowRoutes.kt` (redirect to /register-passkey after VP instead of direct OIDC completion)

## Out of scope

- Passkey deletion UX (user manages via their OS)
- Multiple realms (passkeys only issue from the citizens realm in this pass)
- Attestation statement validation (set to `none`; self-attestation accepted)
- Rate limiting / anti-enumeration (demo stack)
