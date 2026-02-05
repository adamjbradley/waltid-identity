# Portal EUDI Verification Fix Design

**Date:** 2026-02-05
**Status:** Approved
**Author:** Claude + Adam

## Problem

The portal's verification requests fail with EUDI wallets because:
1. Portal sends minimal requests without `key`, `x5c`, `clientId`
2. Backend config defaults aren't being applied
3. DCQL queries lack proper `claims` paths required by EUDI wallets

## Solution

Backend applies config defaults; portal includes configurable claims with full editor UI.

## Design

### 1. Data Model Changes

**File:** `waltid-applications/waltid-web-portal/types/credentials.tsx`

Extend `EudiCredentials` to include default claims:

```typescript
export interface ClaimDefinition {
  path: string[];      // e.g., ["eu.europa.ec.eudi.pid.1", "family_name"]
  required?: boolean;  // UI hint - can't be removed if true
}

export const EudiCredentials: AvailableCredential[] = [
  {
    id: 'eu.europa.ec.eudi.pid.1',
    title: 'EU Personal ID (mDoc)',
    offer: { doctype: 'eu.europa.ec.eudi.pid.1' },
    defaultClaims: [
      { path: ['eu.europa.ec.eudi.pid.1', 'family_name'] },
      { path: ['eu.europa.ec.eudi.pid.1', 'given_name'] },
      { path: ['eu.europa.ec.eudi.pid.1', 'birth_date'] },
    ]
  },
  {
    id: 'org.iso.18013.5.1.mDL',
    title: 'Mobile Driving License',
    offer: { doctype: 'org.iso.18013.5.1.mDL' },
    defaultClaims: [
      { path: ['org.iso.18013.5.1', 'family_name'] },
      { path: ['org.iso.18013.5.1', 'given_name'] },
      { path: ['org.iso.18013.5.1', 'birth_date'] },
    ]
  },
  {
    id: 'urn:eudi:pid:1',
    title: 'EU Personal ID (SD-JWT)',
    offer: { vct: 'urn:eudi:pid:1' },
    defaultClaims: [
      { path: ['family_name'] },
      { path: ['given_name'] },
      { path: ['birth_date'] },
    ]
  }
];
```

Update `buildDcqlQuery` to accept and include claims.

### 2. Claims Editor UI Component

**New file:** `waltid-applications/waltid-web-portal/components/walt/forms/ClaimsEditor.tsx`

Collapsible section with editable claim paths:

```
┌─ Requested Claims ──────────────────────────────┐
│ ▼ EU Personal ID (mDoc)                         │
│   ┌────────────────────────────────────┐  [×]   │
│   │ eu.europa.ec.eudi.pid.1.family_name│        │
│   └────────────────────────────────────┘        │
│   ┌────────────────────────────────────┐  [×]   │
│   │ eu.europa.ec.eudi.pid.1.given_name │        │
│   └────────────────────────────────────┘        │
│   ┌────────────────────────────────────┐  [×]   │
│   │ eu.europa.ec.eudi.pid.1.birth_date │        │
│   └────────────────────────────────────┘        │
│                              [+ Add Claim]      │
└─────────────────────────────────────────────────┘
```

**Behavior:**
- Collapsed by default (shows "N claims" summary)
- Each claim is editable text field (dot-notation, converted to/from path array)
- [×] removes claim, [+ Add Claim] adds empty field
- State passed to `buildDcqlQuery`

### 3. Backend Config Defaults Merge

**File:** `waltid-services/waltid-verifier-api2` route handler

Merge config defaults before session creation:

```kotlin
fun createSession(request: VerificationSessionRequest): Session {
    val config = loadVerifierServiceConfig()

    val effectiveRequest = request.copy(
        coreFlow = request.coreFlow.copy(
            clientId = request.coreFlow.clientId ?: config.clientId,
            key = request.coreFlow.key ?: config.key,
            x5c = request.coreFlow.x5c ?: config.x5c,
        )
    )

    return sessionCreator.createSession(effectiveRequest)
}
```

### 4. Backend Default Claims Fallback

**Same file:** Add fallback claims for known doctypes when claims empty:

```kotlin
val defaultClaimsMap = mapOf(
    "eu.europa.ec.eudi.pid.1" to listOf(
        ClaimsQuery(path = listOf("eu.europa.ec.eudi.pid.1", "family_name")),
        ClaimsQuery(path = listOf("eu.europa.ec.eudi.pid.1", "given_name")),
        ClaimsQuery(path = listOf("eu.europa.ec.eudi.pid.1", "birth_date")),
    ),
    "org.iso.18013.5.1.mDL" to listOf(
        ClaimsQuery(path = listOf("org.iso.18013.5.1", "family_name")),
        ClaimsQuery(path = listOf("org.iso.18013.5.1", "given_name")),
        ClaimsQuery(path = listOf("org.iso.18013.5.1", "birth_date")),
    ),
    "urn:eudi:pid:1" to listOf(
        ClaimsQuery(path = listOf("family_name")),
        ClaimsQuery(path = listOf("given_name")),
        ClaimsQuery(path = listOf("birth_date")),
    ),
)

fun enrichDcqlQuery(query: DcqlQuery): DcqlQuery {
    return query.copy(
        credentials = query.credentials.map { cred ->
            if (cred.claims.isNullOrEmpty()) {
                val doctype = cred.meta?.doctypeValue ?: cred.meta?.vctValues?.firstOrNull()
                cred.copy(claims = defaultClaimsMap[doctype] ?: emptyList())
            } else cred
        }
    )
}
```

## Data Flow

```
Portal                    Verifier-API2                 Session Creator
  │                            │                              │
  │ {signed_request: true,     │                              │
  │  dcql_query: {claims}}     │                              │
  ├───────────────────────────►│                              │
  │                            │ Merge config defaults        │
  │                            │ (clientId, key, x5c)         │
  │                            │                              │
  │                            │ Enrich DCQL (add claims      │
  │                            │ if empty)                    │
  │                            │                              │
  │                            ├─────────────────────────────►│
  │                            │                              │ Build signed JAR
  │                            │◄─────────────────────────────┤
  │◄───────────────────────────┤ Return session + QR URL      │
  │                            │                              │
```

## Files to Modify

| File | Change |
|------|--------|
| `types/credentials.tsx` | Add `ClaimDefinition`, `defaultClaims` to credentials, update `buildDcqlQuery` |
| `components/walt/forms/ClaimsEditor.tsx` | New component - claims editor UI |
| `components/sections/VerificationSection.tsx` | Integrate ClaimsEditor, pass claims state |
| `waltid-verifier-api2/.../Verifier2Api.kt` | Merge config defaults, enrich DCQL |

## Testing

1. Portal verification with default claims → wallet accepts
2. Portal verification with custom claims → wallet accepts
3. Portal verification with empty claims → backend adds defaults → wallet accepts
4. All 3 EUDI credential types (PID mDoc, PID SD-JWT, mDL)
