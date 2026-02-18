# EUDI Wallet Multi-Country Flavors Design

**Date:** 2026-02-18
**Status:** Approved

## Goal

Add two country-branded Android app variants (Australia + India) to the existing EUDI Wallet Android fork, enabling cross-border issuance and presentation simulation. The existing single-tenant app remains unchanged.

## App Variants

| Flavor | App Name | App ID Suffix | Issuer Tenant | RP Tenant |
|--------|----------|---------------|---------------|-----------|
| `default` | EUDI Wallet (unchanged) | (none) | `theaustraliahack.com/draft13` (single-tenant) | `verifier2.theaustraliahack.com` (single-tenant) |
| `au` | MyID Wallet Australia | `.au` | AU issuer tenant from registrar | AU RP tenant (`rp-au.theaustraliahack.com`) |
| `in` | Aadhaar Wallet India | `.in` | IN issuer tenant from registrar | IN RP tenant (`rp-in.theaustraliahack.com`) |

All three apps install side-by-side on the same device (different application IDs).

## Cross-Border Flow

Both issue and verify bidirectionally:
- AU wallet receives AU PID from AU issuer tenant
- IN wallet receives IN PID from IN issuer tenant
- AU wallet can present to IN RP tenant (and vice versa)
- Trust established via cross-imported TSLs from the issuer registrar

## Build Structure — Product Flavors

Gradle `country` dimension layered on existing `dev`/`demo` build types:

```kotlin
flavorDimensions += "country"
productFlavors {
    create("default") {
        dimension = "country"
    }
    create("au") {
        dimension = "country"
        applicationIdSuffix = ".au"
        resValue("string", "app_name", "MyID Wallet Australia")
    }
    create("in") {
        dimension = "country"
        applicationIdSuffix = ".in"
        resValue("string", "app_name", "Aadhaar Wallet India")
    }
}
```

Primary build variants: `auDev`, `inDev` (for multi-tenant testing).

## Source Sets

### Config (`core-logic/src/{flavor}/`)

Each flavor gets its own `WalletCoreConfigImpl.kt`:

**AU** (`core-logic/src/au/java/eu/europa/ec/corelogic/config/WalletCoreConfigImpl.kt`):
```
Issuer URL:  https://issuer.theaustraliahack.com/issuers/{au-tenant-id}/draft13
RP Certs:    https://rp-au.theaustraliahack.com/.well-known/rp-certificates
Client ID:   x509_san_dns:rp-au.theaustraliahack.com
```

**IN** (`core-logic/src/in/java/eu/europa/ec/corelogic/config/WalletCoreConfigImpl.kt`):
```
Issuer URL:  https://issuer.theaustraliahack.com/issuers/{in-tenant-id}/draft13
RP Certs:    https://rp-in.theaustraliahack.com/.well-known/rp-certificates
Client ID:   x509_san_dns:rp-in.theaustraliahack.com
```

**`default`** reuses the existing `dev` source set unchanged.

### Trust Store

Both tenant issuer certs embedded in `resources-logic/src/main/res/raw/` (shared across all flavors) so every wallet trusts every country's issuer. Dynamic RP certificate fetch (`ReaderTrustStoreUpdater`) loads verifier certs at startup from the flavor-specific URL.

### Branding (`resources-logic/src/{flavor}/res/`)

**AU — MyID Wallet Australia:**
- Theme: Gold primary (`#DAA520`), green accent (`#006847`)
- App icon: EUDI shield with gold tint
- Splash: Gold-tinted logo with "Australia" subtitle

**IN — Aadhaar Wallet India:**
- Theme: Saffron primary (`#FF9933`), navy accent (`#000080`)
- App icon: EUDI shield with saffron tint
- Splash: Saffron-tinted logo with "India" subtitle

Resources per flavor:

| Resource | File |
|----------|------|
| Colors | `res/values/colors.xml` |
| Theme overrides | `res/values/themes.xml` |
| App icon | `res/mipmap-*/ic_launcher.png` |
| Splash drawable | `res/drawable/splash_logo.xml` |

## Backend Prerequisites

### Issuer Registrar
1. **AU tenant:** country `AU`, P-256 key + X.509 cert, PID + mDL credential templates
2. **IN tenant:** country `IN`, P-256 key + X.509 cert, Aadhaar-based PID templates

### RP Registrar
1. **AU RP:** `x509_san_dns:rp-au.theaustraliahack.com`, own cert
2. **IN RP:** `x509_san_dns:rp-in.theaustraliahack.com`, own cert

### Cross-Border Trust
- Import AU issuer TSL into IN verifier: `POST /admin/trust/custom-tsls {"country":"AU","url":"..."}`
- Import IN issuer TSL into AU verifier: `POST /admin/trust/custom-tsls {"country":"IN","url":"..."}`

### DNS
- `rp-au.theaustraliahack.com` → Caddy reverse proxy → verifier-api2
- `rp-in.theaustraliahack.com` → Caddy reverse proxy → verifier-api2
- TLS certs matching RP tenant client IDs

## Implementation Steps

| Step | What | Effort |
|------|------|--------|
| 1 | Add Gradle `country` flavor dimension with `default`, `au`, `in` | Small |
| 2 | Create `core-logic/src/au/` with `WalletCoreConfigImpl.kt` for AU tenant | Small |
| 3 | Create `core-logic/src/in/` with `WalletCoreConfigImpl.kt` for IN tenant | Small |
| 4 | Embed AU + IN issuer certs in `resources-logic/src/main/res/raw/` | Small |
| 5 | AU branding: colors, icon, splash in `resources-logic/src/au/res/` | Medium |
| 6 | IN branding: colors, icon, splash in `resources-logic/src/in/res/` | Medium |
| 7 | DNS + Caddy config for `rp-au` and `rp-in` subdomains | Small |
| 8 | Create AU + IN issuer tenants and RP tenants (if not done) | Small |
| 9 | Cross-border TSL import between countries | Small |
| 10 | Test: install both apps, issue PID in each, present cross-border | Medium |

## Key Constraint

The `default` flavor is **untouched** — existing single-tenant behavior preserved exactly. When `MT_WALLET_ENABLED=false` and the `default` flavor is built, everything works as it does today.
