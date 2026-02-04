# EUDI Wallet Verification Testing Guide

**Verified Working:** 2026-02-04

This guide documents the end-to-end verification flow with the EUDI Reference Wallet using the walt.id verifier-api2 service.

## Overview

| Component | Value |
|-----------|-------|
| Verifier Service | verifier-api2 (OpenID4VP 1.0 + DCQL) |
| Verifier URL | `https://verifier2.theaustraliahack.com` |
| Client ID | `x509_san_dns:verifier2.theaustraliahack.com` |
| Request Mode | Signed (JAR - RFC 9101) |
| Response Mode | `direct_post` |

## Prerequisites

1. **EUDI Wallet** with a PID mDoc credential issued
2. **Verifier certificate** in wallet's trust store (see [wallet-trust-store-update.md](../docker-compose/docs/wallet-trust-store-update.md))
3. **verifier-api2** running with X.509 certificate configured

## Create a Verification Session

### Request

```bash
curl -s -X POST https://verifier2.theaustraliahack.com/verification-session/create \
  -H "Content-Type: application/json" \
  -d '{
    "flow_type": "cross_device",
    "core_flow": {
      "signed_request": true,
      "clientId": "x509_san_dns:verifier2.theaustraliahack.com",
      "key": {
        "type": "jwk",
        "jwk": {
          "kty": "EC",
          "crv": "P-256",
          "x": "...",
          "y": "...",
          "d": "..."
        }
      },
      "x5c": ["BASE64_ENCODED_CERTIFICATE"],
      "dcql_query": {
        "credentials": [{
          "id": "eudi_pid_mdoc",
          "format": "mso_mdoc",
          "meta": {
            "doctype_value": "eu.europa.ec.eudi.pid.1"
          },
          "claims": [
            { "path": ["eu.europa.ec.eudi.pid.1", "family_name"] },
            { "path": ["eu.europa.ec.eudi.pid.1", "given_name"] },
            { "path": ["eu.europa.ec.eudi.pid.1", "birth_date"] }
          ]
        }]
      }
    }
  }'
```

### Response

```json
{
  "sessionId": "9f9e67bc-dde7-41b2-a7b8-6b96aba33d98",
  "bootstrapAuthorizationRequestUrl": "openid4vp://authorize?client_id=x509_san_dns%3Averifier2.theaustraliahack.com&request_uri=https%3A%2F%2Fverifier2.theaustraliahack.com%2Fverification-session%2F9f9e67bc-dde7-41b2-a7b8-6b96aba33d98%2Frequest",
  "fullAuthorizationRequestUrl": "openid4vp://authorize?response_type=vp_token&..."
}
```

## Trigger Wallet Presentation

### Option 1: QR Code / Share (Recommended)

Use the `bootstrapAuthorizationRequestUrl` from the session creation response:
- Generate a QR code from this URL
- Or use Android "share" functionality to send to EUDI wallet

### Option 2: ADB Command

```bash
# IMPORTANT: Use single quotes to prevent shell from interpreting &
adb shell am start -a android.intent.action.VIEW \
  -d 'openid4vp://authorize?client_id=x509_san_dns%3Averifier2.theaustraliahack.com&request_uri=https%3A%2F%2Fverifier2.theaustraliahack.com%2Fverification-session%2F{SESSION_ID}%2Frequest'
```

### ADB Shell Escaping Caveat

Without proper quoting, the `&` character is interpreted by the shell as a background operator, which **drops the `request_uri` parameter**. This causes the wallet to treat the request as unsigned, resulting in:

```
Invalid resolution: InvalidClientIdPrefix(value=X509SanDns cannot be used in unsigned request)
```

**Solution:** Always use single quotes around the URL when using ADB.

## Check Session Status

```bash
curl -s "https://verifier2.theaustraliahack.com/verification-session/{SESSION_ID}/info" | jq '.status'
```

Expected result: `"SUCCESSFUL"`

## Verification Policies

The following policies are applied to mso_mdoc credentials:

| Policy | Description | Required |
|--------|-------------|----------|
| `mso_mdoc/device-auth` | Verify device authentication | Yes |
| `mso_mdoc/device_key_auth` | Verify holder-verified data | Yes |
| `mso_mdoc/issuer_auth` | Verify issuer authentication (X.509 chain) | Yes |
| `mso_mdoc/issuer_signed_integrity` | Verify issuer-signed data integrity | Yes |
| `mso_mdoc/mso` | Verify Mobile Security Object | Yes |

## Verified Test Results

**Session ID:** `9f9e67bc-dde7-41b2-a7b8-6b96aba33d98`
**Date:** 2026-02-04T05:37:04Z
**Status:** SUCCESSFUL

### Credential Presented

| Field | Value |
|-------|-------|
| Format | `mso_mdoc` |
| DocType | `eu.europa.ec.eudi.pid.1` |
| family_name | DOE |
| given_name | JOHN |
| birth_date | 1990-01-15 |

### Policy Results

| Policy | Status |
|--------|--------|
| mso_mdoc/device-auth | PASS |
| mso_mdoc/device_key_auth | PASS |
| mso_mdoc/issuer_auth | PASS |
| mso_mdoc/issuer_signed_integrity | PASS |
| mso_mdoc/mso | PASS |
| signature (VC policy) | PASS |

### Issuer Certificate

- **Subject:** Waltid Test Document Signer
- **Issuer:** Waltid Test IACA
- **Valid:** 2025-06-02 to 2026-09-02
- **Key Type:** EC P-256

## Troubleshooting

### Error: "X509SanDns cannot be used in unsigned request"

**Cause:** The `request_uri` parameter was dropped from the URL.

**Solutions:**
1. Use QR code / share instead of ADB
2. If using ADB, ensure single quotes around the URL
3. Clear wallet app cache: `adb shell pm clear eu.europa.ec.euidi.dev`

### Error: Content-Type mismatch

**Cause:** Server not returning correct Content-Type for signed requests.

**Solution:** Ensure verifier-api2 returns `Content-Type: application/oauth-authz-req+jwt` for JAR signed requests. This is required per RFC 9101.

### Error: Certificate not trusted

**Cause:** Verifier certificate not in wallet's trust store.

**Solution:** See [wallet-trust-store-update.md](../docker-compose/docs/wallet-trust-store-update.md) for instructions on adding certificates to the EUDI wallet trust store.

## References

- [RFC 9101 - JWT-Secured Authorization Request (JAR)](https://www.rfc-editor.org/rfc/rfc9101.html)
- [OpenID4VP Specification](https://openid.net/specs/openid-4-verifiable-presentations-1_0.html)
- [EUDI Wallet Reference Implementation](https://github.com/eu-digital-identity-wallet)
- [eudi-lib-jvm-openid4vp-kt](https://github.com/eu-digital-identity-wallet/eudi-lib-jvm-openid4vp-kt)
