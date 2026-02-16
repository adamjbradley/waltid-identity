# Multi-Tenant Portal Design

**Date:** 2026-02-12
**Branch:** feature/issuer-registrar
**Status:** Approved

## Overview

Extend the web portal to fully support multi-tenant issuance and verification. The backend APIs are complete — this is portal-only work connecting admin management to user-facing flows.

**User model:** Platform admin first (no auth). Tenant self-service is a future phase.

## Design

### 1. Tenant-Aware Issuance Flow

When `ISSUER_REGISTRAR_ENABLED=true`, the issuance flow gains tenant awareness at two entry points.

**Entry point A — Credential selection.** `IssueSection` gets an "Issuing as" dropdown populated from `GET /admin/issuer` (filtered to ACTIVE with certificates). Selecting an issuer filters the credential list to that tenant's `credentialConfigurations` and passes `issuerId` through to the offer page. A "Global (default)" option remains for non-tenant issuance. Hidden when the feature flag is off.

**Entry point B — Issuer admin page.** Each issuer's detail panel gets an "Issue Credential" button routing to `/credentials?issuerId={id}&mode=issuance`. Pre-selects the tenant and filters the catalog. If no credentials configured, shows "Configure credentials first" linking to the catalog editor.

**Data flow:** `issuerId` is already plumbed through `getOfferUrl()` which constructs tenant-scoped URLs (`/issuers/{id}/openid4vc/{format}/issue`). No backend changes needed.

### 2. Credential Template Picker

The raw JSON editor in the issuer detail panel is replaced with a template-based catalog manager. No backend changes — `PUT /admin/issuer/{id}/credentials` already accepts arbitrary credential configuration JSON.

**Template library.** Static client-side collection organized by category:

| Category | Templates |
|----------|-----------|
| EUDI | PID mDoc, PID SD-JWT, mDL |
| Financial | BankId (JWT), PaymentWalletAttestation (DC+SD-JWT) |
| Identity | Passport, NationalID, ResidencePermit |
| Custom | Blank template — admin fills in format, docType/VCT, claims |

**UX flow.** Credential section shows current catalog as cards (name, format badge, claim count). "Add Credential" opens a modal with the template library as a grid. Clicking a template adds it. Admin customizes claims, then saves. "Remove" button on each card. Save calls `PUT /admin/issuer/{id}/credentials`.

**Advanced escape hatch.** "Edit as JSON" toggle reveals the raw JSON editor for power users.

**Templates stored in** `credentialTemplates.ts`, similar to `EudiCredentials` in `types/credentials.tsx`.

### 3. RP-Aware Verification Flow

When `RP_REGISTRAR_ENABLED=true`, the verification flow gains RP awareness.

**Entry point A — Verification section.** `VerificationSection` gets a "Verifying as" dropdown populated from `GET /admin/rp` (filtered to ACTIVE with certificates). Selecting an RP auto-injects that RP's `clientId`, signing key, and `x5c` into the verification session request. A "Global (default)" option uses existing env-based config. Hidden when feature flag is off.

**Entry point B — RP admin page.** Each RP's detail panel gets a "Verify as this RP" button routing to `/credentials?rpId={id}&mode=verification`. Pre-selects the RP and auto-configures signing.

**Entry point C — Shareable verify link.** `/verify?rpId={id}` pre-configures the verification session with that RP's credentials. Admin copies this link for RP integration testing. Verify page fetches RP details on mount via `GET /admin/rp/{id}`.

**Signing keys.** `GET /admin/rp/{id}/certificate/download` returns both certificate and private key. Portal fetches at verification time to build signed JAR requests.

### 4. Navigation & Admin Integration

**Issuer admin enhancements:**
- "Issue Credential" button in detail panel (routes to issuance with `issuerId`)
- "View Metadata" link opens tenant's `.well-known/openid-credential-issuer` in new tab
- Credential count in list view links to catalog section in detail panel

**RP admin enhancements:**
- "Verify as this RP" button in detail panel (routes to verification with `rpId`)
- "Copy Verify Link" generates and copies shareable `/verify?rpId={id}` URL
- "Download Certificate" button for RP system integration

**Homepage awareness.** Subtle banner when MT features are enabled hinting at tenant selection capability.

**AdminNav unchanged.** Existing breadcrumb (Portal / Trust Lists / Issuers / Relying Parties) already covers all admin pages.

### 5. Data Flow & State Management

**No new global context.** Tenant selection is page-local state via URL query parameters (`issuerId`, `rpId`). This gives deep links, browser navigation, and zero state synchronization complexity.

**Issuance data flow:**
1. Homepage or admin → `/credentials?issuerId=X&mode=issuance`
2. `IssueSection` reads `issuerId`, fetches `GET /admin/issuer/{id}` for tenant's credential catalog
3. Filters `CredentialsContext` to match tenant's `credentialConfigurations` keys
4. User selects credential, edits data, clicks Issue
5. Routes to `/offer?ids=X&format=Y&issuerId=Z` (already supported)
6. `getOfferUrl()` constructs tenant-scoped API path (already implemented)

**Verification data flow:**
1. Homepage or admin → `/credentials?rpId=X&mode=verification`
2. `VerificationSection` reads `rpId`, fetches `GET /admin/rp/{id}` for signing config
3. Fetches `GET /admin/rp/{id}/certificate/download` for private key + x5c
4. User selects credentials, clicks Verify
5. Routes to `/verify?rpId=X&ids=Y&format=Z`
6. Verify page uses RP's `clientId`, key, `x5c` instead of env vars

**Caching.** Fetch once per page load. No SWR or React Query needed.

## Scope

**In scope:**
- Issuer tenant dropdown in `IssueSection` with credential filtering
- RP tenant dropdown in `VerificationSection` with auto-signing config
- Credential template picker replacing JSON editor in issuer admin
- Action buttons in admin panels ("Issue Credential", "Verify as RP")
- Shareable `/verify?rpId=X` links
- Homepage banner when MT features enabled
- All behind existing feature flags — zero impact when off

**Out of scope (future phases):**
- Tenant self-service / authentication
- Per-tenant branding or custom UI themes
- DCQL query visual builder for RPs
- Credential display preview (logo, colors)
- Tenant usage analytics or audit logging
- Certificate upload UI in portal
- Multi-verifier-API routing per RP

## Implementation Notes

- **Backend changes:** None. All required API endpoints exist.
- **Testing:** Jest unit tests for component rendering + query param handling, manual E2E via Docker.
- **Feature flags:** `ISSUER_REGISTRAR_ENABLED` and `RP_REGISTRAR_ENABLED` gate all new UI.
- **URL convention:** `issuerId` and `rpId` as optional query params, preserving backward compatibility.
