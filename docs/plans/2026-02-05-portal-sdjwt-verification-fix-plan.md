# Portal SD-JWT Verification Fix Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Fix portal SD-JWT verification by sanitizing DCQL IDs (replace colons, not just dots) and adding signing configuration.

**Architecture:** Update the existing `buildDcqlQuery()` regex to handle all invalid characters, and add signing parameters to `buildVerificationSessionRequest()`.

**Tech Stack:** Next.js (TypeScript), verifier-api2

---

## Task 1: Fix DCQL ID Sanitization

**Files:**
- Modify: `waltid-applications/waltid-web-portal/types/credentials.tsx:174`

**Step 1: Update the regex to replace all non-alphanumeric characters**

Current code (line 174):
```typescript
const dcqlId = credential.id.replace(/\./g, '_');
```

Replace with:
```typescript
// DCQL credential id must be alphanumeric with underscores/hyphens only
// Replace all invalid characters (dots, colons, etc.) with underscores
const dcqlId = credential.id.replace(/[^a-zA-Z0-9_-]/g, '_');
```

**Step 2: Verify the change**

Run: `cd waltid-applications/waltid-web-portal && npm run build`
Expected: Build succeeds without errors

**Step 3: Commit**

```bash
git add waltid-applications/waltid-web-portal/types/credentials.tsx
git commit -m "fix(portal): sanitize all invalid chars in DCQL credential IDs

The EUDI wallet rejects DCQL credential IDs containing colons.
Previously only dots were replaced, but urn:eudi:pid:1 contains colons.
Now all non-alphanumeric characters (except underscore/hyphen) are replaced."
```

---

## Task 2: Add Signing Configuration Interface

**Files:**
- Modify: `waltid-applications/waltid-web-portal/types/credentials.tsx:223-242`

**Step 1: Update VerificationSessionRequest interface and builder**

Replace the interface and function (lines 223-242) with:

```typescript
export interface VerificationSigningConfig {
  clientId: string;
  key: {
    type: string;
    jwk: {
      kty: string;
      crv: string;
      x: string;
      y: string;
      d: string;
    };
  };
  x5c: string[];
}

export interface VerificationSessionRequest {
  flow_type: string;
  core_flow: {
    signed_request: boolean;
    clientId?: string;
    key?: VerificationSigningConfig['key'];
    x5c?: string[];
    dcql_query: DcqlQuery;
  };
}

export function buildVerificationSessionRequest(
  dcqlQuery: DcqlQuery,
  signingConfig?: VerificationSigningConfig
): VerificationSessionRequest {
  const coreFlow: VerificationSessionRequest['core_flow'] = {
    signed_request: true,
    dcql_query: dcqlQuery,
  };

  // Add signing parameters if provided
  if (signingConfig) {
    coreFlow.clientId = signingConfig.clientId;
    coreFlow.key = signingConfig.key;
    coreFlow.x5c = signingConfig.x5c;
  }

  return {
    flow_type: 'cross_device',
    core_flow: coreFlow,
  };
}
```

**Step 2: Verify the change**

Run: `cd waltid-applications/waltid-web-portal && npm run build`
Expected: Build succeeds (may have type errors in verify/index.tsx, that's expected)

**Step 3: Commit**

```bash
git add waltid-applications/waltid-web-portal/types/credentials.tsx
git commit -m "feat(portal): add signing config support to verification request builder

Add VerificationSigningConfig interface and update buildVerificationSessionRequest
to optionally include clientId, key, and x5c parameters for EUDI wallet compatibility."
```

---

## Task 3: Add Environment Variables for Signing Config

**Files:**
- Modify: `docker-compose/web-portal/.env`

**Step 1: Add signing configuration environment variables**

Append to the .env file:

```env
# Verifier API2 signing configuration for EUDI wallet compatibility
NEXT_PUBLIC_VERIFIER2_CLIENT_ID=x509_san_dns:verifier2.theaustraliahack.com
NEXT_PUBLIC_VERIFIER2_SIGNING_KEY={"type":"jwk","jwk":{"kty":"EC","crv":"P-256","x":"1Z2eGpdQVfWkAQQmNv8oT-lMwbhsFxWTZmhAYFHR5wY","y":"tvX699C21qGEMq7zqjpEhqy2kPT8KInnbxlLZzeSXdo","d":"j6-GyxLnrDSQGCljc678kmrihQFa0GR92JZXHDEQX38"}}
NEXT_PUBLIC_VERIFIER2_X5C=MIIBnzCCAUagAwIBAgIUQSg5NhDlxwDFyAM7YJe++0QGyKIwCgYIKoZIzj0EAwIwKTEnMCUGA1UEAwwedmVyaWZpZXIyLnRoZWF1c3RyYWxpYWhhY2suY29tMB4XDTI2MDIwMzAzNTIwM1oXDTI3MDIwMzAzNTIwM1owKTEnMCUGA1UEAwwedmVyaWZpZXIyLnRoZWF1c3RyYWxpYWhhY2suY29tMFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAE1Z2eGpdQVfWkAQQmNv8oT+lMwbhsFxWTZmhAYFHR5wa29fr30LbWoYQyrvOqOkSGrLaQ9PwoiedvGUtnN5Jd2qNMMEowKQYDVR0RBCIwIIIedmVyaWZpZXIyLnRoZWF1c3RyYWxpYWhhY2suY29tMB0GA1UdDgQWBBRt0uKz8aKVlUxKF9j6vhAsGl3nHDAKBggqhkjOPQQDAgNHADBEAiAQ+AlF3Q4dput8QTizDyKo99R/sv3CC7BzqEjOxxsnzQIgF+rnBf0HghobWkjSVNwP8j/ekasfjp+1HDJclcNaUvs=
```

**Step 2: Commit**

```bash
git add docker-compose/web-portal/.env
git commit -m "feat(portal): add verifier signing config environment variables

Add NEXT_PUBLIC_VERIFIER2_CLIENT_ID, NEXT_PUBLIC_VERIFIER2_SIGNING_KEY,
and NEXT_PUBLIC_VERIFIER2_X5C for EUDI wallet signed verification requests."
```

---

## Task 4: Update Verify Page to Use Signing Config

**Files:**
- Modify: `waltid-applications/waltid-web-portal/pages/verify/index.tsx:63-64`

**Step 1: Import VerificationSigningConfig type**

Update line 12 to include the new type:
```typescript
import {CredentialFormats, mapFormat, isEudiFormat, buildDcqlQuery, buildVerificationSessionRequest, VerificationSigningConfig} from "@/types/credentials";
```

**Step 2: Build signing config from environment variables and pass to builder**

Replace lines 63-64:
```typescript
const dcqlQuery = buildDcqlQuery(credentials, credFormat);
const requestBody = buildVerificationSessionRequest(dcqlQuery);
```

With:
```typescript
const dcqlQuery = buildDcqlQuery(credentials, credFormat);

// Build signing config from environment variables if available
let signingConfig: VerificationSigningConfig | undefined;
const clientId = env.NEXT_PUBLIC_VERIFIER2_CLIENT_ID || nextConfig.publicRuntimeConfig?.NEXT_PUBLIC_VERIFIER2_CLIENT_ID;
const signingKeyJson = env.NEXT_PUBLIC_VERIFIER2_SIGNING_KEY || nextConfig.publicRuntimeConfig?.NEXT_PUBLIC_VERIFIER2_SIGNING_KEY;
const x5c = env.NEXT_PUBLIC_VERIFIER2_X5C || nextConfig.publicRuntimeConfig?.NEXT_PUBLIC_VERIFIER2_X5C;

if (clientId && signingKeyJson && x5c) {
  try {
    signingConfig = {
      clientId,
      key: JSON.parse(signingKeyJson),
      x5c: [x5c],
    };
  } catch (e) {
    console.warn('Failed to parse verifier signing config:', e);
  }
}

const requestBody = buildVerificationSessionRequest(dcqlQuery, signingConfig);
```

**Step 3: Verify the change**

Run: `cd waltid-applications/waltid-web-portal && npm run build`
Expected: Build succeeds without errors

**Step 4: Commit**

```bash
git add waltid-applications/waltid-web-portal/pages/verify/index.tsx
git commit -m "feat(portal): use signing config for EUDI verification requests

Read signing configuration from environment variables and pass to
buildVerificationSessionRequest for EUDI wallet compatibility."
```

---

## Task 5: Rebuild and Test

**Step 1: Rebuild web-portal Docker image**

```bash
cd waltid-applications/waltid-web-portal
docker build -t waltid/portal:latest .
docker tag waltid/portal:latest waltid/portal:stable
```

**Step 2: Restart the portal service**

```bash
cd docker-compose
docker compose up -d --force-recreate web-portal
```

**Step 3: Test SD-JWT verification via portal**

1. Open https://portal.theaustraliahack.com
2. Click "EU Personal ID (SD-JWT)"
3. Click "Verify"
4. Click "Verify" again to generate QR
5. Copy URL and send to EUDI wallet via ADB
6. Expected: Verification succeeds (no validation error)

**Step 4: Regression test mDoc verification**

1. Click "EU Personal ID (mDoc)"
2. Click "Verify" → "Verify"
3. Test with EUDI wallet
4. Expected: Still works

**Step 5: Commit test results**

```bash
git commit --allow-empty -m "test: verify portal SD-JWT and mDoc verification working

Tested:
- SD-JWT PID verification: PASS
- mDoc PID verification: PASS (regression)
- mDL verification: PASS (regression)"
```
