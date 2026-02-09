# EUDI Trust Lists

Validates credential issuers and verifiers against official EU trust infrastructure using ETSI TS 119 612 Trust Service Lists and OpenID Federation 1.0.

**Feature-flagged.** Disabled by default. Zero impact on existing flows when off.

## Quick Start

### 1. Enable

```bash
# docker-compose/.env
TRUST_LISTS_ENABLED=true
```

Or per-service via `config/trust-lists.conf`:

```hocon
enabled = true
```

### 2. Restart Services

```bash
docker compose --profile identity up -d verifier-api2 wallet-api
```

On startup, the verifier will fetch the EU List of Trusted Lists (LOTL) and all member state trust lists. This takes 1-2 minutes and runs in the background - the service is available immediately.

### 3. Verify

```bash
curl http://localhost:7004/admin/trust/status
```

```json
{
  "healthy": true,
  "sources": {
    "etsi_tl": { "enabled": true, "healthy": true, "entryCount": 380 },
    "openid_federation": { "enabled": false, "healthy": true }
  }
}
```

## Configuration

### Environment Variables

| Variable | Default | Description |
|----------|---------|-------------|
| `TRUST_LISTS_ENABLED` | `false` | Enable the trust lists feature |
| `ETSI_LOTL_URL` | `https://ec.europa.eu/tools/lotl/eu-lotl.xml` | EU LOTL endpoint |

### Config File (`config/trust-lists.conf`)

Present in both `verifier-api2` and `wallet-api`:

```hocon
enabled = false
enabled = ${?TRUST_LISTS_ENABLED}

etsi {
    lotlUrl = "https://ec.europa.eu/tools/lotl/eu-lotl.xml"
    lotlUrl = ${?ETSI_LOTL_URL}
    cacheTtlHours = 24          # How long to cache fetched trust lists
    memberStates = ["*"]        # Filter: ["*"] = all, or ["DE", "FR", "IT"]
    validateSignatures = true   # Validate XMLDSig signatures on trust lists
}

openidFederation {
    trustAnchors = []           # OpenID Federation trust anchor URLs
}
```

### Member State Filtering

To only load trust lists from specific countries:

```hocon
etsi {
    memberStates = ["DE", "FR", "IT", "ES", "NL"]
}
```

Use the two-letter country codes from the EU LOTL scheme territories.

## Admin API

All endpoints are on the verifier-api2 service (default port 7004).

### GET /admin/trust/status

Returns the current trust service status including health, entry counts, and enabled sources.

**Response:**
```json
{
  "healthy": true,
  "sources": {
    "etsi_tl": {
      "enabled": true,
      "healthy": true,
      "entryCount": 380,
      "lastUpdate": null,
      "error": null
    },
    "openid_federation": {
      "enabled": false,
      "healthy": true,
      "entryCount": 0
    }
  }
}
```

**Status 503** when `TRUST_LISTS_ENABLED=false`.

### PUT /admin/trust/etsi

Toggle the ETSI Trust List source on or off at runtime. Resets to config default on restart.

```bash
# Disable ETSI trust lists
curl -X PUT http://localhost:7004/admin/trust/etsi \
  -H 'Content-Type: application/json' \
  -d '{"enabled": false}'

# Re-enable
curl -X PUT http://localhost:7004/admin/trust/etsi \
  -H 'Content-Type: application/json' \
  -d '{"enabled": true}'
```

### PUT /admin/trust/federation

Toggle the OpenID Federation source on or off at runtime.

```bash
curl -X PUT http://localhost:7004/admin/trust/federation \
  -H 'Content-Type: application/json' \
  -d '{"enabled": true}'
```

### POST /admin/trust/refresh

Force re-fetch all trust lists from source. Returns the updated status.

```bash
curl -X POST http://localhost:7004/admin/trust/refresh
```

## Verification Policy

Add `etsi-trusted-issuer` to a verification session to require issuers be listed in EU trust lists.

### Policy Parameters

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `memberStates` | `List<String>` | `[]` | Filter trusted member states (empty = all) |
| `requireGrantedStatus` | `Boolean` | `true` | Only accept "granted" status services |

### Usage in Verification Request

```json
{
  "request_credentials": ["VerifiableCredential"],
  "policies": [
    {
      "policy": "etsi-trusted-issuer",
      "memberStates": ["DE", "FR"],
      "requireGrantedStatus": true
    }
  ]
}
```

### Policy Behavior

| Condition | Result |
|-----------|--------|
| Feature disabled (`TRUST_LISTS_ENABLED=false`) | Policy returns **failure** with message "Trust lists feature is not enabled" |
| Issuer found in trust lists with "granted" status | **Success** - returns trust validation details |
| Issuer not found in any trust list | **Failure** - "Issuer not found in ETSI trust lists" |
| Trust list fetch failed | **Failure** - issuer can't be validated |

### Matching Logic

The policy matches the credential's `issuer` field against:

1. **X.509 Subject Name** in the trust service's digital identity
2. **Provider Name** (case-insensitive) from the Trust Service Provider

## Portal Admin UI

Navigate to **http://localhost:7102/admin/trust-config** for a visual dashboard.

Features:
- Trust source health indicators with entry counts
- Enable/disable toggles per source (calls admin API)
- Refresh button to force re-fetch all trust lists
- Test Validation form - enter an issuer DID to check against trust lists

The page requires `NEXT_PUBLIC_VERIFIER2` to be configured (set automatically in docker-compose).

## Wallet Trust Badge

The wallet displays a trust badge on credential detail pages showing whether the issuer is trusted.

### Badge States

| State | Appearance | Condition |
|-------|------------|-----------|
| **EUDI Trusted Issuer** | Green shield | Issuer found in ETSI/Federation trust lists |
| **Verified Issuer** | Blue info | Issuer passes legacy trust validation |
| **Issuer Not Verified** | Orange warning | Issuer not found in any trust source |

The badge is expandable, showing provider name, country, trust source, and service status when the issuer is trusted.

### Enhanced Trust Validation

The wallet's `EnhancedTrustValidationService` wraps the existing `DefaultTrustValidationService`:

- `validate()` - Unchanged legacy behavior (backward compatible)
- `validateWithDetails()` - Checks EUDI trust lists first, falls back to legacy. Returns `DetailedTrustResult` with both results

When the trust feature is disabled, `trustService` is `null` and all calls fall through to legacy validation.

## Architecture

```
waltid-trust                    Interface only (TrustService, TrustValidationResult)
    ^
    |
waltid-etsi-tsl                 ETSI TS 119 612 parser (XML/StAX on JVM)
waltid-openid-federation        OpenID Federation 1.0 client
    ^
    |
waltid-service-commons          CompositeTrustService, TrustListServiceFactory
    ^                ^
    |                |
verifier-api2        wallet-api
(TrustAdminController,  (EnhancedTrustValidationService,
 EtsiTrustedIssuerPolicy  TrustBadge.vue)
 wiring)
```

### Dependency Isolation

`waltid-verification-policies2` depends only on `waltid-trust` (interfaces, zero external dependencies). The heavy XML parsing and HTTP libraries in `waltid-etsi-tsl` are only pulled in at the service layer.

### Feature Flag Pattern

Follows the same `OptionalFeature` pattern as PWA:

```kotlin
val trustListFeature = OptionalFeature(
    "trust-lists",
    "EUDI Trust List validation",
    TrustListConfig::class,
    default = System.getenv("TRUST_LISTS_ENABLED")?.toBoolean() ?: false
)
```

- Config is **never loaded** unless feature is enabled
- `TrustListServiceFactory.getServiceOrNull()` returns `null` when disabled
- Routes registered via `{ trustAdminRoutes() } whenFeature FeatureCatalog.trustListFeature`

### Trust Sources

| Source | Status | Description |
|--------|--------|-------------|
| **ETSI_TL** | Implemented | EU Trusted Lists per ETSI TS 119 612 |
| **OPENID_FEDERATION** | Implemented (client) | OpenID Federation 1.0 trust chain resolution |
| **VICAL** | Planned | Verifiable Issuer Certificate Authority List (existing `waltid-vical` library) |
| **STATIC_LIST** | Planned | Manually configured trusted issuers |

## ETSI Trust List Details

### How It Works

1. On startup (when enabled), fetches the EU **List of Trusted Lists** (LOTL)
2. Parses LOTL to find pointers to each member state's trust list
3. Fetches and parses each member state's trust list
4. Extracts Trust Service Providers and their services
5. Caches results in memory (default 24-hour TTL)
6. On validation request, matches issuer against provider identities

### XML Parsing

- **JVM**: StAX parser (`javax.xml.stream`) - no external dependencies
- **JS**: DOMParser API
- **Signature validation**: JVM uses `javax.xml.crypto` (JSR 105)
- Security hardened: external entities and DTD processing disabled

### What Gets Loaded

From the EU LOTL, the service loads:
- ~380 Trust Service Providers across 27+ EU member states
- Each provider has one or more Trust Services (CA/QC, TSA, etc.)
- Each service has a status (granted, withdrawn, etc.) and digital identity (X.509 cert)

Some member states serve trust lists as PDF (not XML) - these are logged as warnings and skipped.

## Troubleshooting

### Trust lists not loading

Check verifier-api2 logs:
```bash
docker compose logs verifier-api2 | grep -i trust
```

Common issues:
- `Content is not allowed in prolog` - Member state serves PDF instead of XML (expected, skipped automatically)
- `TSL signature validation failed` - Signature verification failed but list still parsed (warning only)
- Network timeouts - Some member state servers are slow; retry via `POST /admin/trust/refresh`

### Admin API returns 503

The feature is disabled. Set `TRUST_LISTS_ENABLED=true` and restart the service.

### Policy returns "Trust lists feature is not enabled"

The `etsi-trusted-issuer` policy was included in a verification session but the trust feature is disabled on the verifier. Enable it or remove the policy from the session.

### Portal trust page shows "Verifier API2 is not configured"

The `NEXT_PUBLIC_VERIFIER2` environment variable is not set in the portal container. This is set automatically in docker-compose from `VERIFIER2_EXTERNAL_URL`. The page loads async - wait a few seconds for the environment to load.

### Zero entries loaded

Check that `memberStates` in config is set to `["*"]` or includes the desired country codes. Also verify the LOTL URL is accessible from the container.

## Module Reference

| Module | Location | Purpose |
|--------|----------|---------|
| `waltid-trust` | `waltid-libraries/credentials/waltid-trust/` | Interface + data types |
| `waltid-etsi-tsl` | `waltid-libraries/credentials/waltid-etsi-tsl/` | ETSI TSL parser/fetcher |
| `waltid-openid-federation` | `waltid-libraries/protocols/waltid-openid-federation/` | OpenID Federation client |
| Service commons | `waltid-services/waltid-service-commons/.../trust/` | CompositeTrustService, Factory |
| Verifier integration | `waltid-services/waltid-verifier-api2/.../trust/` | Admin API, policy wiring |
| Wallet integration | `waltid-services/waltid-wallet-api/.../trust/` | EnhancedTrustValidationService |
| Wallet UI | `waltid-applications/waltid-web-wallet/libs/components/credentials/TrustBadge.vue` | Trust badge component |
| Portal UI | `waltid-applications/waltid-web-portal/pages/admin/trust-config.tsx` | Admin dashboard |

## Test Coverage

79 unit tests across 10 test files:

| Module | Tests | File |
|--------|-------|------|
| EtsiTrustedIssuerPolicy | 6 | `waltid-verification-policies2/.../EtsiTrustedIssuerPolicyTest.kt` |
| CompositeTrustService | 12 | `waltid-service-commons/.../CompositeTrustServiceTest.kt` |
| TrustListServiceFactory | 6 | `waltid-service-commons/.../TrustListServiceFactoryTest.kt` |
| TrustAdminController | 10 | `waltid-verifier-api2/.../TrustAdminControllerTest.kt` |
| EnhancedTrustValidationService | 6 | `waltid-wallet-api/.../EnhancedTrustValidationServiceTest.kt` |
| JvmTslParser | 16 | `waltid-etsi-tsl/.../JvmTslParserTest.kt` |
| EtsiTrustListService | 6 | `waltid-etsi-tsl/.../EtsiTrustListServiceTest.kt` |
| EntityStatementFetcher | 10 | `waltid-openid-federation/.../EntityStatementFetcherTest.kt` |
| TrustChainBuilder | 8 | `waltid-openid-federation/.../TrustChainBuilderTest.kt` |

Run all trust-related tests:

```bash
./gradlew :waltid-libraries:credentials:waltid-trust:allTests \
  :waltid-libraries:credentials:waltid-etsi-tsl:jvmTest \
  :waltid-libraries:protocols:waltid-openid-federation:jvmTest \
  :waltid-libraries:credentials:waltid-verification-policies2:jvmTest \
  :waltid-services:waltid-service-commons:test \
  :waltid-services:waltid-verifier-api2:test \
  :waltid-services:waltid-wallet-api:test
```
