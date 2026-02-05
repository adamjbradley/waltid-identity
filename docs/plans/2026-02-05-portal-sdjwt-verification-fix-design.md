# Portal SD-JWT Verification Fix Design

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Fix portal SD-JWT verification to work with EUDI wallet by sanitizing DCQL IDs and adding signing configuration.

**Architecture:** Modify portal's verification request generation to match the working CLI format - sanitize credential IDs for DCQL compliance and include explicit signing parameters.

**Tech Stack:** Next.js (TypeScript), verifier-api2, EUDI wallet

---

## Problem

The portal's SD-JWT verification fails with EUDI wallet error:
```
The value must be a non-empty string consisting of alphanumeric, underscore (_) or hyphen (-) characters
```

**Root causes:**
1. DCQL credential `id` field uses `urn:eudi:pid:1` which contains colons
2. Portal doesn't include signing parameters (`signed_request`, `key`, `x5c`)

**Evidence:**
- Portal verification: FAILS with validation error
- CLI verification with same verifier-api2: WORKS

## Solution

### 1. Sanitize DCQL Credential IDs

Add function to replace invalid characters:

```typescript
function sanitizeDcqlId(id: string): string {
  return id.replace(/[^a-zA-Z0-9_-]/g, '_');
}
```

Results:
- `urn:eudi:pid:1` → `urn_eudi_pid_1`
- `eu.europa.ec.eudi.pid.1` → `eu_europa_ec_eudi_pid_1`

### 2. Add Signing Configuration

Include explicit signing parameters in verification requests:

```typescript
const requestBody = {
  flow_type: 'cross_device',
  core_flow: {
    signed_request: true,
    clientId: env.NEXT_PUBLIC_VERIFIER2_CLIENT_ID,
    key: JSON.parse(env.NEXT_PUBLIC_VERIFIER2_SIGNING_KEY),
    x5c: [env.NEXT_PUBLIC_VERIFIER2_X5C],
    dcql_query: dcqlQuery,
  },
};
```

## Files to Modify

| File | Change |
|------|--------|
| `waltid-web-portal/types/credentials.tsx` | Add `sanitizeDcqlId()`, use in DCQL construction |
| `waltid-web-portal/pages/verify/index.tsx` | Add signing params to `core_flow` |
| `docker-compose/web-portal/.env` | Add verifier signing environment variables |

## Environment Variables

```env
NEXT_PUBLIC_VERIFIER2_CLIENT_ID=x509_san_dns:verifier2.theaustraliahack.com
NEXT_PUBLIC_VERIFIER2_SIGNING_KEY={"type":"jwk","jwk":{"kty":"EC","crv":"P-256","x":"1Z2eGpdQVfWkAQQmNv8oT-lMwbhsFxWTZmhAYFHR5wY","y":"tvX699C21qGEMq7zqjpEhqy2kPT8KInnbxlLZzeSXdo","d":"j6-GyxLnrDSQGCljc678kmrihQFa0GR92JZXHDEQX38"}}
NEXT_PUBLIC_VERIFIER2_X5C=MIIBnzCCAUagAwIBAgIUQSg5NhDlxwDFyAM7YJe++0QGyKIwCgYIKoZIzj0EAwIwKTEnMCUGA1UEAwwedmVyaWZpZXIyLnRoZWF1c3RyYWxpYWhhY2suY29tMB4XDTI2MDIwMzAzNTIwM1oXDTI3MDIwMzAzNTIwM1owKTEnMCUGA1UEAwwedmVyaWZpZXIyLnRoZWF1c3RyYWxpYWhhY2suY29tMFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAE1Z2eGpdQVfWkAQQmNv8oT+lMwbhsFxWTZmhAYFHR5wa29fr30LbWoYQyrvOqOkSGrLaQ9PwoiedvGUtnN5Jd2qNMMEowKQYDVR0RBCIwIIIedmVyaWZpZXIyLnRoZWF1c3RyYWxpYWhhY2suY29tMB0GA1UdDgQWBBRt0uKz8aKVlUxKF9j6vhAsGl3nHDAKBggqhkjOPQQDAgNHADBEAiAQ+AlF3Q4dput8QTizDyKo99R/sv3CC7BzqEjOxxsnzQIgF+rnBf0HghobWkjSVNwP8j/ekasfjp+1HDJclcNaUvs=
```

## Testing Plan

1. Rebuild web-portal Docker image
2. Test SD-JWT verification via portal → should work
3. Verify mDoc verification still works (regression)
4. Verify mDL verification still works (regression)

## Success Criteria

- [ ] Portal SD-JWT verification works with EUDI wallet
- [ ] No regression in mDoc verification
- [ ] No regression in mDL verification
- [ ] Clear error message if signing config missing

## Security Note

The private key is exposed client-side via `NEXT_PUBLIC_` prefix. This is acceptable for development/testing as it only signs verification requests. Production deployments should use server-side signing.
