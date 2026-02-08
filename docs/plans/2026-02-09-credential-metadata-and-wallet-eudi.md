# Design: Comprehensive Credential Metadata & Web Wallet EUDI Support

**Date:** 2026-02-09
**Branch:** `feature/credential-metadata`

## Problem Statement

Two issues identified:

1. **Non-EUDI credential issuance broken**: The docker-compose `credential-issuer-metadata.conf` only contained 4 EUDI credential types, preventing issuance of W3C credentials like AlpsTourReservation.

2. **Web wallet DC+SD-JWT gap**: The Nuxt web wallet handles mDoc and vc+sd-jwt credentials correctly, but treats dc+sd-jwt (EUDI SD-JWT format) as generic JWTs without selective disclosure support.

## Part 1: Credential Issuer Metadata (COMPLETED)

### Root Cause

`docker-compose/issuer-api/config/credential-issuer-metadata.conf` overrode code-level defaults from `CredentialTypeConfig.kt`, restricting the issuer to only 4 EUDI types. The default config (in source) is entirely commented out, so the code-defined 50+ types would normally all be available.

### Solution

Rebuilt `credential-issuer-metadata.conf` as a comprehensive catalog:
- **38 simple W3C array types** (auto-expand to ~10 format variants each → 388 total)
- **4 advanced W3C types** (KiwiAccess, identity_credential, custom_vct, photoID)
- **4 EUDI types** with full claims/display metadata (PID mDoc, mDL, PID SD-JWT, PWA)

### Verification

- Issuer metadata endpoint returns 388 credential types
- AlpsTourReservation issuance returns valid credential offer
- All 4 EUDI credentials still present with detailed metadata

## Part 2: Web Wallet EUDI Support (DESIGN)

### Current State

| Format | Receive/Store | Parse | Display | Selective Disclosure |
|--------|:---:|:---:|:---:|:---:|
| mso_mdoc | OK | OK | OK | N/A |
| vc+sd-jwt | OK | OK | OK | OK |
| dc+sd-jwt | OK | Generic JWT | No format detection | Not wired |

### Files to Modify

#### 1. `libs/composables/credential.ts`

**Current** (line 85):
```typescript
const issuerKid = computed(() =>
    credential.value.format === "vc+sd-jwt" ? jwtJson.value?.iss ?? null : null
);
```

**Change**: Add `dc+sd-jwt` to the format check:
```typescript
const issuerKid = computed(() =>
    (credential.value.format === "vc+sd-jwt" || credential.value.format === "dc+sd-jwt")
        ? jwtJson.value?.iss ?? null : null
);
```

#### 2. `apps/waltid-dev-wallet/src/pages/wallet/[wallet]/credentials/[credentialId].vue`

Add dc+sd-jwt format detection in the credential display section, reusing the existing vc+sd-jwt selective disclosure UI.

### What Stays Unchanged

- `waltid-services/waltid-wallet-api/` — Backend already handles all formats
- mDoc display logic — Already working
- Presentation (verification) flow — Wallet API handles format-specific logic
- VCT resolution — Already calls fetchVctName() endpoint

### Risk Assessment

- **Low risk**: Changes are additive format detection, no existing behavior modified
- **Zero impact on mDoc**: mDoc path is separate (CBOR parsing vs JWT parsing)
- **Backwards compatible**: Existing vc+sd-jwt flow unchanged
