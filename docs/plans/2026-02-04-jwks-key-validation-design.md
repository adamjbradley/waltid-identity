# JWKS Key Validation Design

**Date:** 2026-02-04
**Status:** Approved
**Branch:** feature/jar-content-type-fix

## Problem

The JWKS endpoint (`/draft13/jwks`) crashes with a 500 error when any issuance session contains an invalid key. The endpoint iterates through all sessions and attempts to resolve each issuer key. One bad key (e.g., P-256 with invalid curve coordinates) causes the entire endpoint to fail.

**Impact:** Verifiers cannot retrieve issuer public keys for signature verification, breaking the entire verification flow.

## Solution Overview

A comprehensive fix addressing three layers:

1. **Prevention** - Validate keys at issuance time, reject invalid keys with clear errors
2. **Cleanup** - Remove invalid sessions on startup
3. **Resilience** - Make JWKS endpoint skip bad keys gracefully

## Design

### 1. Key Validation Service

**New file:** `waltid-issuer-api/src/main/kotlin/id/walt/issuer/issuance/KeyValidationService.kt`

```kotlin
object KeyValidationService {

    fun validateIssuerKey(keyJson: JsonObject): Result<Unit>

    // Checks performed:
    // 1. Parse key via KeyManager.resolveSerializedKey()
    // 2. For EC keys: verify curve coordinates are valid points
    // 3. Verify key type matches declared algorithm
    // 4. Attempt test sign/verify cycle to confirm key works
}
```

**Integration:** Called in `IssuerApi.kt` before storing issuance requests.

**Error response (HTTP 400):**
```json
{
  "error": "invalid_issuer_key",
  "error_description": "EC key validation failed: coordinates not on P-256 curve",
  "details": {
    "key_type": "EC",
    "curve": "P-256",
    "validation_step": "curve_coordinates"
  }
}
```

### 2. Startup Session Cleanup

**Location:** `CIProvider.kt` initialization or new `SessionCleanupService.kt`

**Behavior:**
1. On service startup, after session store initializes
2. Iterate through all sessions via `authSessions.getAll()`
3. Validate each session's issuer keys using `KeyValidationService`
4. If validation fails:
   - Log warning: `"Removing session ${session.id}: invalid issuer key - ${error.message}"`
   - Remove session from store
5. Log summary: `"Startup cleanup: removed N sessions with invalid keys"`

**Timing:** Runs before OIDC API routes become available.

**Failure handling:** If cleanup fails, log error and continue startup. Don't block the service.

### 3. JWKS Endpoint Resilience

**Location:** `CIProvider.kt` - `getJwksSessions()` function

**Change:** Wrap key resolution in try/catch, skip invalid keys with warning log.

```kotlin
suspend fun getJwksSessions(): JsonObject {
    val keys = mutableListOf<JsonObject>()

    // Always include the system token key
    keys.add(CI_TOKEN_KEY.getPublicKey().exportJWKObject())

    // Add session keys, skipping any that fail to resolve
    authSessions.getAll().forEach { session ->
        session.issuanceRequests.forEach { request ->
            try {
                val key = KeyManager.resolveSerializedKey(request.issuerKey)
                keys.add(key.getPublicKey().exportJWKObject())
            } catch (e: Exception) {
                logger.warn { "Skipping invalid key in session ${session.id}: ${e.message}" }
            }
        }
    }

    return buildJsonObject { put("keys", JsonArray(keys)) }
}
```

## Test Plan

### Unit Tests (KeyValidationService)
- Valid P-256 key → passes
- Invalid P-256 coordinates → fails with clear message
- ES256 algorithm with P-384 key → fails (curve mismatch)
- Key that can't sign → fails
- RSA key with ES256 → fails (type mismatch)

### Integration Tests
- POST issuance with invalid key → 400 response
- Startup with bad session in store → session removed, JWKS works
- JWKS with mixed valid/invalid sessions → returns only valid keys

## Files Changed

**Create:**
- `waltid-issuer-api/src/main/kotlin/id/walt/issuer/issuance/KeyValidationService.kt`

**Modify:**
- `CIProvider.kt` - Add startup cleanup, update `getJwksSessions()` with try/catch
- `IssuerApi.kt` - Add key validation before storing issuance requests

## Execution Order

1. Service starts → session store initializes
2. Startup cleanup runs → removes invalid sessions
3. OIDC routes become available
4. On each issuance request → validate key before storing

## Rollback

Changes are isolated. If issues arise, disable validation by catching exceptions in the validation service without removing it.
