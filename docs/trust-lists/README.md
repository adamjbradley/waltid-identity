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
| `OPENID_FEDERATION_ENABLED` | `false` | Enable OpenID Federation trust source |

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
    enabled = false
    enabled = ${?OPENID_FEDERATION_ENABLED}
    trustAnchors = []           # OpenID Federation trust anchor URLs
    maxChainDepth = 5           # Max hops from leaf entity to trust anchor
    cacheTtlSeconds = 3600      # Entity statement cache TTL (1 hour)
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

### GET /admin/trust/lotl

Returns the LOTL overview with summaries for each member state, including provider and service counts.

**Response:**
```json
{
  "schemeTerritory": "EU",
  "schemeOperatorName": "European Commission",
  "listIssueDate": "2024-01-15T00:00:00Z",
  "nextUpdate": "2024-07-15T00:00:00Z",
  "sequenceNumber": 78,
  "memberStates": [
    {
      "country": "DE",
      "location": "https://...",
      "providerCount": 28,
      "serviceCount": 95,
      "healthy": true
    }
  ]
}
```

**Status 404** when LOTL hasn't been fetched yet (try `POST /admin/trust/refresh`).

### GET /admin/trust/lotl/{country}

Returns detailed trust list for a specific member state, including all providers and their services.

```bash
curl http://localhost:7004/admin/trust/lotl/DE
```

**Response:**
```json
{
  "schemeTerritory": "DE",
  "schemeOperatorName": "Bundesnetzagentur",
  "listIssueDate": "2024-01-10T00:00:00Z",
  "sequenceNumber": 42,
  "providers": [
    {
      "name": "D-Trust GmbH",
      "tradeName": "D-Trust",
      "services": [
        {
          "serviceName": "D-TRUST CA 3-1 2015",
          "serviceType": "http://uri.etsi.org/TrstSvc/Svctype/CA/QC",
          "serviceTypeLabel": "CA/QC",
          "status": "granted",
          "statusRaw": "http://uri.etsi.org/TrstSvc/TrustedList/Svcstatus/granted",
          "statusStartingTime": "2020-03-15T00:00:00Z",
          "isQualified": true
        }
      ]
    }
  ]
}
```

### GET /admin/trust/search

Search across all trust lists by provider name, country, status, and service type.

```bash
# Search by name
curl "http://localhost:7004/admin/trust/search?q=D-Trust"

# Filter by country and status
curl "http://localhost:7004/admin/trust/search?country=DE&status=granted"

# Paginate
curl "http://localhost:7004/admin/trust/search?q=cert&limit=10&offset=20"
```

**Query Parameters:**

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `q` | `String` | — | Search term (matches provider name, trade name, service name) |
| `country` | `String` | — | Two-letter country code filter |
| `status` | `String` | — | Status filter (`granted`, `withdrawn`, etc.) |
| `type` | `String` | — | Service type filter (`CA/QC`, `TSA`, etc.) |
| `limit` | `Int` | `50` | Results per page (max 200) |
| `offset` | `Int` | `0` | Skip N results |

**Response:**
```json
{
  "query": "D-Trust",
  "country": null,
  "status": null,
  "serviceType": null,
  "total": 3,
  "providers": [
    {
      "name": "D-Trust GmbH",
      "tradeName": "D-Trust",
      "country": "DE",
      "services": [...]
    }
  ]
}
```

### Response DTOs

| DTO | Fields |
|-----|--------|
| `LotlOverview` | `schemeTerritory`, `schemeOperatorName`, `listIssueDate`, `nextUpdate`, `sequenceNumber`, `memberStates` |
| `MemberStateSummary` | `country`, `location`, `providerCount`, `serviceCount`, `healthy` |
| `CountryTslDetail` | `schemeTerritory`, `schemeOperatorName`, `listIssueDate`, `nextUpdate`, `sequenceNumber`, `providers` |
| `ProviderDetail` | `name`, `tradeName`, `country`, `services` |
| `ServiceDetail` | `serviceName`, `serviceType`, `serviceTypeLabel`, `status`, `statusRaw`, `statusStartingTime`, `isQualified` |
| `SearchResponse` | `query`, `country`, `status`, `serviceType`, `total`, `providers` |

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

1. **X.509 Subject Name** in the trust service's digital identity (substring match)
2. **Provider Name** (case-insensitive equality) from the Trust Service Provider

## Portal Admin UI

Navigate to **http://localhost:7102/admin/trust-config** for a visual dashboard.

### Status Tab

- Trust source health indicators with entry counts
- Enable/disable toggles per source (calls admin API)
- Refresh button to force re-fetch all trust lists
- Test Validation form — enter an issuer DID to check against trust lists

### Trust Lists Tab

- **LOTL overview card** showing scheme operator, issue date, sequence number
- **Member state grid** with country entries showing provider/service counts
- Clickable country detail panels showing providers and services
- **Search bar** with debounced search (300ms) across all trust lists
- Status badges: granted (green), withdrawn (red), deprecated (yellow), recognised (blue)

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

### Wallet Trust Composable

The wallet provides a `useTrustValidation()` Vue composable for reactive trust status:

```typescript
export function useTrustValidation(
  walletId: Ref<string | null>,
  issuerDid: Ref<string | null>,
  credentialType?: Ref<string | null>
)
// Returns: { trustResult, loading, error, validate }
```

- Calls `/wallet-api/wallet/:id/trust/validate?did=...&type=...&detailed=true`
- Auto-validates when `issuerDid` or `walletId` change via `watch()`
- Returns `DetailedTrustResult` with both legacy and trust list results
- Returns `null` with error message when trust validation is unavailable

## Architecture

```
waltid-trust                    Interface + shared models (TrustService, TrustValidationResult,
    ^                             TrustServiceList, TrustServiceProvider, TrustServiceEntry)
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
 EtsiTrustedIssuerPolicy  TrustBadge.vue, useTrustValidation)
 LOTL browsing, search)
```

### Dependency Isolation

`waltid-verification-policies2` depends only on `waltid-trust` (interfaces, zero external dependencies). The heavy XML parsing and HTTP libraries in `waltid-etsi-tsl` are only pulled in at the service layer.

Shared trust models (`TrustServiceList`, `TrustServiceProvider`, `TrustServiceEntry`, `TslPointer`, `TrustAnchorInfo`) live in `waltid-trust` and are used by both the library and service layers.

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

### CompositeTrustService

The `CompositeTrustService` orchestrates both ETSI and Federation trust sources:

- **Lazy initialization** — both `etsiService` and `federationService` are `by lazy`, only instantiated on first use
- **Validation order** — ETSI first, Federation second. First trusted match wins.
- **Federation requires dual opt-in** — `config.openidFederation.enabled == true` AND `config.openidFederation.trustAnchors.isNotEmpty()`
- **Injectable provider** — accepts optional `OpenIdFederationProvider` and `EtsiTrustListProvider` for testability
- **LOTL browsing methods** — `getLotl()`, `getMemberStateTls()`, `getMemberStateTl(country)`, `searchProviders()` delegate to the ETSI service

### Trust Sources

| Source | Status | Description |
|--------|--------|-------------|
| **ETSI_TL** | Implemented | EU Trusted Lists per ETSI TS 119 612 |
| **OPENID_FEDERATION** | Implemented | OpenID Federation 1.0 trust chain resolution |
| **VICAL** | Planned | Verifiable Issuer Certificate Authority List (existing `waltid-vical` library) |
| **STATIC_LIST** | Planned | Manually configured trusted issuers |

## ETSI Trust List Deep Dive

### LOTL Fetch Flow

On startup (when enabled), `EtsiTrustListService.refresh()` executes:

1. **Fetch LOTL XML** — `TslFetcher.fetchLotl()` fetches from `config.lotlUrl` (default: EU LOTL)
2. **Parse LOTL** — `JvmTslParser` extracts `SchemeInformation` and `PointersToOtherTSL/OtherTSLPointer` list
3. **Filter by member states** — `config.memberStates` controls which pointers to follow (`["*"]` = all)
4. **Fetch each member state TL** — iterates pointers, fetches and parses each TSL XML
5. **Extract providers** — from each member state TL, extract `TrustServiceProvider/TSPService` entries, tag with country code
6. **Store in cache** — all providers aggregated into `cachedProviders`, full LOTL and member state TLs stored in `_cachedLotl` and `_cachedMemberStateTls` for browsing

### XML Parsing (JVM — StAX)

`JvmTslParser` uses cursor-based `XMLStreamReader` (javax.xml.stream):

- **Security hardened** — external entities and DTD processing disabled via `IS_SUPPORTING_EXTERNAL_ENTITIES=false` and `SUPPORT_DTD=false`
- **Element hierarchy** parsed:
  - `SchemeInformation` → `SchemeTerritory`, `SchemeOperatorName`, `ListIssueDateTime`, `NextUpdate`, `TSLSequenceNumber`
  - `PointersToOtherTSL/OtherTSLPointer` → `TSLLocation`, `SchemeTerritory`, `MimeType` (becomes `TslPointer`)
  - `TrustServiceProvider/TSPInformation` → `Name`, `TSPTradeName`
  - `TSPService/ServiceInformation` → `ServiceTypeIdentifier`, `Name`, `ServiceStatus`, `StatusStartingTime`, `X509Certificate`, `X509SubjectName`
- **First-match semantics** — for fields like `Name` that appear at multiple levels, first non-empty value wins

### XMLDSig Signature Validation

`JvmTslValidator` validates signatures using JSR 105 (`javax.xml.crypto`):

1. Parse XML with `DocumentBuilderFactory` (namespace-aware, DTD disabled, external entities disabled)
2. Find `ds:Signature` element via `getElementsByTagNameNS`
3. Create `DOMValidateContext` with custom `X509KeySelector`
4. `X509KeySelector` extracts the public key from the `KeyInfo/X509Data/X509Certificate` embedded in the signature
5. `signature.validate()` verifies the cryptographic math

**Limitation:** validates that the signature math is correct but does NOT verify the signing certificate against a trusted root CA. Failed signatures are logged as warnings but the list is still used.

### Caching

Two-layer caching architecture:

| Layer | Location | TTL | Scope |
|-------|----------|-----|-------|
| **Per-URL** | `TslFetcher.cache` | `cacheTtlHours` (default 24h) | Individual TSL documents, mutex-protected |
| **Aggregate** | `EtsiTrustListService._cachedProviders` etc. | Until next `refresh()` | All providers, full LOTL, member state TLs |

The per-URL cache in `TslFetcher` prevents redundant network fetches. The aggregate cache in `EtsiTrustListService` stores the fully-parsed LOTL metadata and member state TLs for browsing via the admin API.

### JS Platform

- Uses `DOMParser` API (browser-native XML parsing)
- **No signature validation** — `TslValidator.validateSignature()` returns `false` on JS
- Incomplete field extraction compared to JVM StAX parser

### Issuer Matching

When validating a credential issuer, `CompositeTrustService` iterates all cached providers and matches:

1. **X.509 Subject Name** — substring match: `service.serviceDigitalIdentity?.x509SubjectName?.contains(issuer)`
2. **Provider Name** — case-insensitive equality: `provider.name.equals(issuer, ignoreCase = true)`

First match wins. Returns `TrustValidationResult` with provider name, country, status, service type, and `isGranted` flag.

### ETSI Service Types

| Short Label | Full URI | Description |
|------------|----------|-------------|
| CA/QC | `http://uri.etsi.org/TrstSvc/Svctype/CA/QC` | Certificate Authority for Qualified Certificates |
| QES Validation | `http://uri.etsi.org/TrstSvc/Svctype/QESVal` | Qualified Electronic Signature Validation |
| Timestamp Authority | `http://uri.etsi.org/TrstSvc/Svctype/TSA` | Time Stamping Authority |
| Electronic Delivery | `http://uri.etsi.org/TrstSvc/Svctype/EDS/Q` | Electronic Delivery Service (Qualified) |
| Preservation Service | `http://uri.etsi.org/TrstSvc/Svctype/PSES/Q` | Preservation Service (Qualified) |
| Registered e-Mail | `http://uri.etsi.org/TrstSvc/Svctype/REM/Q` | Registered e-Mail Delivery Service |

### ETSI Statuses

| Label | URI | Meaning |
|-------|-----|---------|
| granted | `http://uri.etsi.org/TrstSvc/TrustedList/Svcstatus/granted` | Actively trusted |
| withdrawn | `http://uri.etsi.org/TrstSvc/TrustedList/Svcstatus/withdrawn` | No longer trusted |
| deprecated | `http://uri.etsi.org/TrstSvc/TrustedList/Svcstatus/deprecatedatnationallevel` | Deprecated at national level |
| recognised | `http://uri.etsi.org/TrstSvc/TrustedList/Svcstatus/recognisedatnationallevel` | Recognised at national level |

## OpenID Federation Deep Dive

See also: [`openid-federation.md`](openid-federation.md) for configuration and quick start.

### How Federation Validation Works

When `OPENID_FEDERATION_ENABLED=true` + trust anchors configured:

1. `CompositeTrustService` lazily instantiates `federationService` with `FederationConfig` mapped from `TrustListConfig.OpenIdFederationConfig`
2. `validateIssuer()` — after ETSI check, calls `federationService.buildTrustChain(issuer)`
3. If chain is valid (reaches a configured trust anchor), returns trusted with `source = OPENID_FEDERATION`

### Trust Chain Building Algorithm

`TrustChainBuilder.buildChain(entityId)` step-by-step:

1. **Fetch leaf entity's self-signed statement** from `{entityId}/.well-known/openid-federation`
2. **JWT decoded** — base64url payload extraction, parsed as `EntityStatement`. **Signature NOT verified** (critical limitation)
3. **Check `issuer == subject`** — validates the statement is self-signed
4. **Walk `authority_hints`** up to `maxChainDepth` (default 5):
   - For each hint: if hint is a configured trust anchor, fetch subordinate statement from `{anchor}/fetch?sub={entity}` and stop
   - Otherwise, treat as intermediate: fetch subordinate statement + self-signed statement, then continue walking
5. **First successful hint wins** per depth level
6. **Returns** `TrustChain(valid=true/false, statements, trustAnchorId, error)`

### EntityStatement Model

```kotlin
@Serializable
data class EntityStatement(
    @SerialName("iss") val issuer: String,
    @SerialName("sub") val subject: String,
    @SerialName("iat") val issuedAt: Long? = null,
    @SerialName("exp") val expiresAt: Long? = null,
    @SerialName("jwks") val jwks: JsonObject? = null,
    @SerialName("authority_hints") val authorityHints: List<String>? = null,
    @SerialName("metadata") val metadata: JsonObject? = null,
    @SerialName("metadata_policy") val metadataPolicy: JsonObject? = null,
    @SerialName("trust_marks") val trustMarks: List<JsonElement>? = null
)
```

Derived properties: `isSelfSigned` (`issuer == subject`), `isExpired(nowEpochSeconds)`.

### Federation Caching

`EntityStatementFetcher` uses an in-memory map with mutex:

- **Self-signed statements** cached with key = `entityId`, TTL = `cacheTtlSeconds` (default 1 hour)
- **Subordinate statements** NOT cached — always fetched fresh
- `Clock.System.now()` used for TTL comparisons
- `clearCache()` called on manual refresh

## How ETSI and Federation Work Together

`CompositeTrustService` is the orchestrator (lives in `waltid-service-commons`):

1. **Validation order** — ETSI first, Federation second. First trusted result wins.
2. **Independent enable/disable** — both sources can be toggled at runtime via admin API (`PUT /admin/trust/etsi`, `PUT /admin/trust/federation`)
3. **Shared interface** — both implement parts of the `TrustService` interface and use `TrustValidationResult` as the return type
4. **`TrustSource` enum** — `ETSI_TL`, `OPENID_FEDERATION`, `VICAL` (planned), `STATIC_LIST` (planned)
5. **Dual opt-in for Federation** — requires `TRUST_LISTS_ENABLED=true` + `OPENID_FEDERATION_ENABLED=true` + non-empty `trustAnchors`
6. **ETSI always enabled** when the trust lists feature is on; Federation is opt-in within that

## Known Limitations

| Component | Status | Detail |
|-----------|--------|--------|
| ETSI TL fetching & parsing | Complete | JVM fully functional, JS partial |
| ETSI XMLDSig validation | Partial | Validates signature math but not certificate chain against root CA |
| ETSI issuer matching | Complete | Substring on X.509 subject + case-insensitive name match |
| OpenID Federation library | Complete | Fetching, chain building, caching all work |
| Federation JWT verification | Not implemented | JWT signatures decoded but not cryptographically verified |
| Federation in CompositeTrustService | Wired | Lazy init, validates after ETSI, requires dual opt-in |
| Federation in OpenID4VP auth | Not implemented | `openid_federation:` prefix parsed but handler not yet active |
| Admin refresh endpoint | Bug | `POST /refresh` returns status but does not trigger re-fetch (`TrustService` interface lacks `refresh()`) |
| Policy `memberStates` filter | Not implemented | Field serialized but unused in `verify()` |
| JS platform | Partial | No XMLDSig validation, incomplete field extraction |
| Signature-failed lists | Non-blocking | Failed XMLDSig logged as warning, list still used |
| LOTL browsing API | Complete | 3 endpoints (LOTL overview, country detail, search) with pagination |
| Wallet trust composable | Complete | Reactive composable with auto-validation on DID/wallet change |

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
| `waltid-trust` | `waltid-libraries/credentials/waltid-trust/` | Interface + shared data models |
| `waltid-etsi-tsl` | `waltid-libraries/credentials/waltid-etsi-tsl/` | ETSI TSL parser/fetcher |
| `waltid-openid-federation` | `waltid-libraries/protocols/waltid-openid-federation/` | OpenID Federation client |
| Service commons | `waltid-services/waltid-service-commons/.../trust/` | CompositeTrustService, Factory |
| Verifier integration | `waltid-services/waltid-verifier-api2/.../trust/` | Admin API, LOTL browsing, search, policy wiring |
| Wallet integration | `waltid-services/waltid-wallet-api/.../trust/` | EnhancedTrustValidationService |
| Wallet UI | `waltid-applications/waltid-web-wallet/libs/components/credentials/TrustBadge.vue` | Trust badge component |
| Wallet composable | `waltid-applications/waltid-web-wallet/libs/composables/trust.ts` | `useTrustValidation()` composable |
| Portal UI | `waltid-applications/waltid-web-portal/pages/admin/trust-config.tsx` | Admin dashboard (status + trust lists tabs) |

## Test Coverage

98 unit tests across 12 test files:

| Module | Tests | File |
|--------|-------|------|
| EtsiTrustedIssuerPolicy | 6 | `waltid-verification-policies2/.../EtsiTrustedIssuerPolicyTest.kt` |
| CompositeTrustService | 12 | `waltid-service-commons/.../CompositeTrustServiceTest.kt` |
| TrustListServiceFactory | 6 | `waltid-service-commons/.../TrustListServiceFactoryTest.kt` |
| TrustAdminController | 19 | `waltid-verifier-api2/.../TrustAdminControllerTest.kt` |
| EnhancedTrustValidationService | 6 | `waltid-wallet-api/.../EnhancedTrustValidationServiceTest.kt` |
| JvmTslParser | 16 | `waltid-etsi-tsl/.../JvmTslParserTest.kt` |
| EtsiTrustListService | 6 | `waltid-etsi-tsl/.../EtsiTrustListServiceTest.kt` |
| EntityStatementFetcher | 10 | `waltid-openid-federation/.../EntityStatementFetcherTest.kt` |
| TrustChainBuilder | 8 | `waltid-openid-federation/.../TrustChainBuilderTest.kt` |

The TrustAdminControllerTest covers all browsing endpoints (LOTL overview, country detail, search) plus the original admin endpoints (status, toggle, refresh).

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
