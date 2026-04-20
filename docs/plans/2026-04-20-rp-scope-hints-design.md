# RP-to-auth-op credential-hint passthrough via OIDC scopes

## Problem

rp.theaustraliahack.com authenticates users through auth-op.theaustraliahack.com, but auth-op always requests the same static PID claim set (`given_name`, `family_name`, `birth_date`) regardless of what the RP actually needs. Merchants want two related capabilities:

1. **Hint which credentials/claims the RP needs.** For an age-restricted checkout, the merchant wants "age over 18 AND age over 21 AND KYC." Another merchant may want KYC only. The request shape must be per-session.
2. **Limit what the RP persists.** The RP's user store should hold *only* `age_over_18`, `age_over_21`, and `kyc_verified` flags — never `birth_date`, name, or nationality — so a breach of the RP reveals no PII.

## Design

### Request mechanism: OIDC scopes

The RP passes standard OIDC `scope` values on the `/authorize` redirect:

```
scope=openid kyc age_over_18 age_over_21
```

Auth-op keeps a realm-scoped catalog that maps each scope to (a) DCQL claim paths the wallet must disclose and (b) the id_token claim returned to the RP. Unknown scopes are ignored with a `warn`, per OIDC Core §3.1.2.1.

### Scope catalog

| Scope         | DCQL claim paths                                   | Consent label                      | id_token claim to RP |
|---------------|----------------------------------------------------|------------------------------------|----------------------|
| `openid`      | —                                                  | —                                  | `sub`                |
| `kyc`         | `given_name`, `family_name`, `nationality`         | "Name: X Y, Nationality: AU"       | `kyc_verified: true` |
| `age_over_18` | `age_equal_or_over.18`                             | "Over 18: ✓"                       | `age_over_18: true`  |
| `age_over_21` | `age_equal_or_over.21`                             | "Over 21: ✓"                       | `age_over_21: true`  |

Age is requested as the EUDI PID derived claim `age_equal_or_over.{N}`, not from `birth_date`. Birth date never leaves the wallet.

### End-to-end flow

1. RP redirects to `/authorize?scope=openid+kyc+age_over_18+age_over_21&...`.
2. `AuthorizeRoutes.kt` parses scopes, persists them on the `AuthRequest`.
3. User picks the wallet realm.
4. `VpFlowRoutes.kt` composes a DCQL query dynamically: union of `claim_paths` across requested scopes, targeted at supported PID VCTs, wrapped in a single credential query (one wallet interaction, not N).
5. verifier-api2 signs the JAR, wallet presents, user consents.
6. Auth-op receives disclosed claims: `given_name`, `family_name`, `nationality`, `age_equal_or_over_18`, `age_equal_or_over_21`.
7. Auth-op renders the consent screen with the disclosed PII labelled "shared with *Merchant* during this session" and the outgoing id_token claims labelled "Merchant will keep."
8. On consent, `TokenRoutes.kt` projects the session's claims through the scope catalog and issues an id_token containing only `{sub, kyc_verified, age_over_18, age_over_21}` — PII dropped.
9. RP stores only `{sub, provider, kyc_verified, age_over_18, age_over_21, firstSeenAt, lastSeenAt, loginCount}`.

Property: PII transits auth-op (for consent + `sub` hash derivation) but never reaches the RP.

## Files to change

### Auth-op

- `docker-compose/auth-op/config/realms.conf` — add `scopes { ... }` block under the `oid4vp` realm; each entry has `claim_paths` and `id_token_claim`. Drop `dcql_query_file`.
- `waltid-services/waltid-auth-op/src/main/kotlin/id/walt/authop/config/RealmRegistry.kt` — model the new `scopes` block as `Map<String, ScopeDefinition>`.
- `waltid-services/waltid-auth-op/src/main/kotlin/id/walt/authop/domain/AuthRequest.kt` — ensure requested scopes survive; already a `Set<String>`, just route into the VP flow.
- `waltid-services/waltid-auth-op/src/main/kotlin/id/walt/authop/endpoints/VpFlowRoutes.kt` — replace static DCQL file read with a DCQL composer driven by `AuthRequest.scopes ∩ realm.oid4vp.scopes.keys`.
- `waltid-services/waltid-auth-op/src/main/kotlin/id/walt/authop/endpoints/TokenRoutes.kt` — project wallet claims → id_token claims through the scope catalog.
- `waltid-services/waltid-auth-op/src/main/kotlin/id/walt/authop/templates/SharedChrome.kt` — new; extract CSS tokens, mesh background, theme toggle from `LoginPage.kt`.
- `waltid-services/waltid-auth-op/src/main/kotlin/id/walt/authop/templates/LoginPage.kt` — use `SharedChrome`.
- `waltid-services/waltid-auth-op/src/main/kotlin/id/walt/authop/templates/ConsentPage.kt` — rewrite to use `SharedChrome`, render "shared during session" vs "merchant will keep" lists based on scope catalog + disclosed claims.
- `docker-compose/auth-op/config/dcql/citizens.dcql.json` — delete (static file no longer read).

### RP

- `examples/rp-widget-demo/server.js` — request new scopes via `AUTH_SCOPES` env (default `openid kyc age_over_18 age_over_21`).
- `examples/rp-widget-demo/userStore.js` — new persisted shape; `upsert()` strips any field not in the allowlist before write.
- `examples/rp-widget-demo/public/*` — update the profile-card to render the new flags.

## Test matrix

| Scenario                                                    | Expected id_token to RP                                            |
|-------------------------------------------------------------|--------------------------------------------------------------------|
| `scope=openid kyc age_over_18 age_over_21`, user ≥ 21       | `sub`, `kyc_verified:true`, `age_over_18:true`, `age_over_21:true` |
| Same scopes, user is 19                                     | `sub`, `kyc_verified:true`, `age_over_18:true` (no `age_over_21`)  |
| `scope=openid kyc`                                          | `sub`, `kyc_verified:true`                                         |
| `scope=openid` only                                         | `sub` only (legacy behaviour)                                      |
| Any age/kyc scope against the employees/Keycloak realm      | `sub` only; `warn` logged noting Keycloak cannot attest age        |

## Out of scope

- Merchant-catalogue UI for editing scope catalogs (HOCON edits only for v1).
- A legacy-fallback path for wallets that don't support `age_equal_or_over.N` selective disclosure. EUDI PID v1.1 mandates it and every VCT in the current catalog is PID.
- Per-credential-format branching (mdoc-only RPs). This design assumes `dc+sd-jwt` across all target wallets.
