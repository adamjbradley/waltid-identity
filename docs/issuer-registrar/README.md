# Issuer Registrar (Multi-Tenant Issuance)

The Issuer Registrar allows multiple organizations to share a single issuer-api deployment, each with independent signing keys, certificate chains, credential catalogs, and OpenID4VCI metadata endpoints.

## Architecture

### Tenant Isolation

Each issuer tenant gets:

- **Own CIProvider instance** (cached in `IssuerTenantRegistry`) with tenant-specific `baseUrl`
- **Own session namespace** — `ConfiguredPersistence` scoped by tenant ID, preventing cross-tenant session access
- **Own token signing key** (`ciTokenKey`) — tokens from tenant A are rejected at tenant B's credential endpoint
- **Own certificate chain** — IACA (root CA) + Document Signer (leaf) per ISO 18013-5
- **Own credential catalog** — tenant metadata only exposes that tenant's credential types

### Route Architecture

```
Global routes (unchanged):
  /{standardVersion}/.well-known/openid-credential-issuer
  /{standardVersion}/token
  /{standardVersion}/credential
  /openid4vc/{format}/issue

Tenant routes (new, behind feature flag):
  /issuers/{issuerId}/{standardVersion}/.well-known/openid-credential-issuer
  /issuers/{issuerId}/{standardVersion}/token
  /issuers/{issuerId}/{standardVersion}/credential
  /issuers/{issuerId}/openid4vc/{format}/issue

Admin routes (new, behind feature flag):
  /admin/issuer                              (CRUD)
  /admin/issuer/{id}/certificate/generate    (cert management)
  /admin/issuer/{id}/credentials             (credential catalog)
```

### Feature Flag

Controlled by `ISSUER_REGISTRAR_ENABLED` (default: `false`). When disabled:

- No tenant routes are registered in Ktor routing
- No tenant store is initialized
- No config is loaded
- Zero runtime impact

### Code Map

All files in `waltid-services/waltid-issuer-api/src/main/kotlin/id/walt/issuer/tenant/`:

| File | Description |
|------|-------------|
| `IssuerTenant.kt` | Data model (`IssuerTenant`, `IssuerTenantStatus`, `X509CertInfo`) |
| `IssuerTenantStore.kt` | ConcurrentHashMap + JSON file persistence |
| `IssuerTenantRegistry.kt` | Per-tenant CIProvider cache with invalidation |
| `IssuerTenantAdminController.kt` | Admin CRUD + cert management routes |
| `TenantOidcRoutes.kt` | Tenant-scoped OID4VCI endpoints (wallet-facing) |
| `TenantIssuerRoutes.kt` | Tenant-scoped issuance endpoints (API-facing) |
| `IssuerCertificateService.kt` | IACA + Document Signer chain generation |
| `IssuerRegistrarConfig.kt` | Config class (`storageDir`) |

### Certificate Chain

Per ISO 18013-5, each tenant gets a two-level chain:

**IACA (Root CA):**
- Subject: `CN={legalName} IACA, C={country}`
- Self-signed, BasicConstraints(CA=true), KeyUsage(keyCertSign, cRLSign)
- P-256 EC key, 5-year validity

**Document Signer (Leaf):**
- Subject: `CN={legalName} Document Signer, C={country}`
- Signed by IACA, EKU OID `1.0.18013.5.1.2` (ISO 18013-5 mDoc signing)
- KeyUsage(digitalSignature), AuthorityKeyIdentifier -> IACA
- P-256 EC key, 1-year validity

**x5Chain format:** `[documentSignerCertBase64, iacaCertBase64]` (leaf first)

A separate `ciTokenKey` (P-256) is generated for OID4VCI token signing.

### CIProvider Lifecycle

1. First request to tenant endpoint calls `IssuerTenantRegistry.getOrCreate(tenant)`
2. Registry creates a new `CIProvider` with tenant's `baseUrl`, credential config, and token key
3. Provider is cached in `ConcurrentHashMap` by tenant ID
4. On certificate regeneration, config change, or tenant deletion, `invalidate(tenantId)` removes the cached provider
5. Next request creates a fresh provider with updated configuration
