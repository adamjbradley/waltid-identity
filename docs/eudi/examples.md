# EUDI Wallet Complete Examples

**Validated:** 2026-02-05

All examples in this document have been tested against the live issuer and verifier APIs.

## Test Results Summary

| Credential | Issuance | Verification (VP) | Verification (VC Sig) | Notes |
|------------|----------|-------------------|----------------------|-------|
| PID mDoc | ✅ | ✅ | ⚠️ | mDoc requires IACA chain for signature verification |
| PID SD-JWT | ✅ | ✅ | ⚠️ | Custom keys not in JWKS; VP policies all pass |
| mDL | ✅ | ✅ | ⚠️ | Requires PID first + x5Chain |

**Key Finding:** Presentation flows work correctly. Credential signature verification requires issuer keys to be published in JWKS or have proper IACA certificate chains.

---

## Issuer Key (for all examples)

**IMPORTANT:** The key must have valid P-256 curve coordinates. Invalid coordinates will cause a 500 error.

```json
{
  "type": "jwk",
  "jwk": {
    "kty": "EC",
    "crv": "P-256",
    "x": "_-t2Oc_Nra8Cgix7Nw2-_RuZt5KrgVZsK3r8aTMSsVQ",
    "y": "nkaVInW3t_q5eB85KnULykQbprApT2RCNZZuJlNPD2Q",
    "d": "URb-8MihTBwKpFA91vzVfcuqxj5qhjNrnhd2fARX62A",
    "kid": "eudi-issuer-key-1"
  }
}
```

To generate a new valid P-256 key:
```bash
node -e "
const { generateKeyPairSync } = require('crypto');
const { privateKey } = generateKeyPairSync('ec', { namedCurve: 'P-256' });
console.log(JSON.stringify(privateKey.export({ format: 'jwk' }), null, 2));
"
```

## Verifier Key and Certificate (for signed verification)

```json
{
  "key": {
    "type": "jwk",
    "jwk": {
      "kty": "EC",
      "crv": "P-256",
      "x": "1Z2eGpdQVfWkAQQmNv8oT-lMwbhsFxWTZmhAYFHR5wY",
      "y": "tvX699C21qGEMq7zqjpEhqy2kPT8KInnbxlLZzeSXdo",
      "d": "j6-GyxLnrDSQGCljc678kmrihQFa0GR92JZXHDEQX38"
    }
  },
  "x5c": ["MIIBnzCCAUagAwIBAgIUQSg5NhDlxwDFyAM7YJe++0QGyKIwCgYIKoZIzj0EAwIwKTEnMCUGA1UEAwwedmVyaWZpZXIyLnRoZWF1c3RyYWxpYWhhY2suY29tMB4XDTI2MDIwMzAzNTIwM1oXDTI3MDIwMzAzNTIwM1owKTEnMCUGA1UEAwwedmVyaWZpZXIyLnRoZWF1c3RyYWxpYWhhY2suY29tMFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAE1Z2eGpdQVfWkAQQmNv8oT+lMwbhsFxWTZmhAYFHR5wa29fr30LbWoYQyrvOqOkSGrLaQ9PwoiedvGUtnN5Jd2qNMMEowKQYDVR0RBCIwIIIedmVyaWZpZXIyLnRoZWF1c3RyYWxpYWhhY2suY29tMB0GA1UdDgQWBBRt0uKz8aKVlUxKF9j6vhAsGl3nHDAKBggqhkjOPQQDAgNHADBEAiAQ+AlF3Q4dput8QTizDyKo99R/sv3CC7BzqEjOxxsnzQIgF+rnBf0HghobWkjSVNwP8j/ekasfjp+1HDJclcNaUvs="]
}
```

---

# Issuance Examples

## 1. EUDI PID (mDoc) ✅ Verified

### Request

**IMPORTANT:** For mDoc credentials, use `mdocData` (NOT `credentialData`) with namespaced claims.

```bash
curl -X POST "https://issuer.theaustraliahack.com/openid4vc/mdoc/issue" \
  -H "Content-Type: application/json" \
  -d '{
    "issuerKey": {
      "type": "jwk",
      "jwk": {
        "kty": "EC",
        "crv": "P-256",
        "x": "_-t2Oc_Nra8Cgix7Nw2-_RuZt5KrgVZsK3r8aTMSsVQ",
        "y": "nkaVInW3t_q5eB85KnULykQbprApT2RCNZZuJlNPD2Q",
        "d": "URb-8MihTBwKpFA91vzVfcuqxj5qhjNrnhd2fARX62A",
        "kid": "eudi-issuer-key-1"
      }
    },
    "credentialConfigurationId": "eu.europa.ec.eudi.pid.1",
    "mdocData": {
      "eu.europa.ec.eudi.pid.1": {
        "family_name": "DOE",
        "given_name": "JOHN",
        "birth_date": "1990-01-15",
        "issuance_date": "2026-02-04",
        "expiry_date": "2031-02-04",
        "issuing_country": "AU",
        "issuing_authority": "Test Authority"
      }
    }
  }'
```

### Response

```
openid-credential-offer://?credential_offer_uri=https%3A%2F%2Fissuer.theaustraliahack.com%2Fdraft13%2FcredentialOffer%3Fid%3D<session-id>
```

---

## 2. EUDI PID (SD-JWT)

### Request

**Note:** SD-JWT uses flat `credentialData` (not namespaced like mDoc).

```bash
curl -X POST "https://issuer.theaustraliahack.com/openid4vc/sdjwt/issue" \
  -H "Content-Type: application/json" \
  -d '{
    "issuerKey": {
      "type": "jwk",
      "jwk": {
        "kty": "EC",
        "crv": "P-256",
        "x": "_-t2Oc_Nra8Cgix7Nw2-_RuZt5KrgVZsK3r8aTMSsVQ",
        "y": "nkaVInW3t_q5eB85KnULykQbprApT2RCNZZuJlNPD2Q",
        "d": "URb-8MihTBwKpFA91vzVfcuqxj5qhjNrnhd2fARX62A",
        "kid": "eudi-issuer-key-1"
      }
    },
    "credentialConfigurationId": "urn:eudi:pid:1",
    "credentialData": {
      "family_name": "DOE",
      "given_name": "JOHN",
      "birth_date": "1990-01-15",
      "issuance_date": "2026-02-04",
      "expiry_date": "2031-02-04",
      "issuing_country": "AU",
      "issuing_authority": "Test Authority"
    }
  }'
```

### Response

```
openid-credential-offer://?credential_offer_uri=https%3A%2F%2Fissuer.theaustraliahack.com%2Fdraft13%2FcredentialOffer%3Fid%3D<session-id>
```

---

## 3. Mobile Driving License (mDL)

### Request

**IMPORTANT:** For mDoc credentials, use `mdocData` with namespace `org.iso.18013.5.1`.

```bash
curl -X POST "https://issuer.theaustraliahack.com/openid4vc/mdoc/issue" \
  -H "Content-Type: application/json" \
  -d '{
    "issuerKey": {
      "type": "jwk",
      "jwk": {
        "kty": "EC",
        "crv": "P-256",
        "x": "_-t2Oc_Nra8Cgix7Nw2-_RuZt5KrgVZsK3r8aTMSsVQ",
        "y": "nkaVInW3t_q5eB85KnULykQbprApT2RCNZZuJlNPD2Q",
        "d": "URb-8MihTBwKpFA91vzVfcuqxj5qhjNrnhd2fARX62A",
        "kid": "eudi-issuer-key-1"
      }
    },
    "credentialConfigurationId": "org.iso.18013.5.1.mDL",
    "mdocData": {
      "org.iso.18013.5.1": {
        "family_name": "DOE",
        "given_name": "JOHN",
        "birth_date": "1990-01-15",
        "issue_date": "2023-01-01",
        "expiry_date": "2033-01-01",
        "issuing_country": "AU",
        "issuing_authority": "Roads and Maritime Services",
        "document_number": "DL123456",
        "driving_privileges": [
          {
            "vehicle_category_code": "C",
            "issue_date": "2023-01-01",
            "expiry_date": "2033-01-01"
          }
        ]
      }
    }
  }'
```

### Response

```
openid-credential-offer://?credential_offer_uri=https%3A%2F%2Fissuer.theaustraliahack.com%2Fdraft13%2FcredentialOffer%3Fid%3D<session-id>
```

---

# Verification Examples

## 1. EUDI PID (mDoc) - Signed Request

### Create Session

```bash
curl -X POST "https://verifier2.theaustraliahack.com/verification-session/create" \
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
          "x": "1Z2eGpdQVfWkAQQmNv8oT-lMwbhsFxWTZmhAYFHR5wY",
          "y": "tvX699C21qGEMq7zqjpEhqy2kPT8KInnbxlLZzeSXdo",
          "d": "j6-GyxLnrDSQGCljc678kmrihQFa0GR92JZXHDEQX38"
        }
      },
      "x5c": ["MIIBnzCCAUagAwIBAgIUQSg5NhDlxwDFyAM7YJe++0QGyKIwCgYIKoZIzj0EAwIwKTEnMCUGA1UEAwwedmVyaWZpZXIyLnRoZWF1c3RyYWxpYWhhY2suY29tMB4XDTI2MDIwMzAzNTIwM1oXDTI3MDIwMzAzNTIwM1owKTEnMCUGA1UEAwwedmVyaWZpZXIyLnRoZWF1c3RyYWxpYWhhY2suY29tMFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAE1Z2eGpdQVfWkAQQmNv8oT+lMwbhsFxWTZmhAYFHR5wa29fr30LbWoYQyrvOqOkSGrLaQ9PwoiedvGUtnN5Jd2qNMMEowKQYDVR0RBCIwIIIedmVyaWZpZXIyLnRoZWF1c3RyYWxpYWhhY2suY29tMB0GA1UdDgQWBBRt0uKz8aKVlUxKF9j6vhAsGl3nHDAKBggqhkjOPQQDAgNHADBEAiAQ+AlF3Q4dput8QTizDyKo99R/sv3CC7BzqEjOxxsnzQIgF+rnBf0HghobWkjSVNwP8j/ekasfjp+1HDJclcNaUvs="],
      "dcql_query": {
        "credentials": [
          {
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
          }
        ]
      }
    }
  }'
```

### Response

```json
{
  "sessionId": "085bf97d-4baa-4276-97bb-0a0f5707cdc7",
  "bootstrapAuthorizationRequestUrl": "openid4vp://authorize?client_id=x509_san_dns%3Averifier2.theaustraliahack.com&request_uri=https%3A%2F%2Fverifier2.theaustraliahack.com%2Fverification-session%2F085bf97d-4baa-4276-97bb-0a0f5707cdc7%2Frequest"
}
```

### Verify Content-Type

```bash
curl -s -D - "https://verifier2.theaustraliahack.com/verification-session/{SESSION_ID}/request" -o /dev/null | grep content-type
# Returns: content-type: application/oauth-authz-req+jwt
```

---

## 2. EUDI PID (SD-JWT) - Signed Request

### Create Session

```bash
curl -X POST "https://verifier2.theaustraliahack.com/verification-session/create" \
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
          "x": "1Z2eGpdQVfWkAQQmNv8oT-lMwbhsFxWTZmhAYFHR5wY",
          "y": "tvX699C21qGEMq7zqjpEhqy2kPT8KInnbxlLZzeSXdo",
          "d": "j6-GyxLnrDSQGCljc678kmrihQFa0GR92JZXHDEQX38"
        }
      },
      "x5c": ["MIIBnzCCAUagAwIBAgIUQSg5NhDlxwDFyAM7YJe++0QGyKIwCgYIKoZIzj0EAwIwKTEnMCUGA1UEAwwedmVyaWZpZXIyLnRoZWF1c3RyYWxpYWhhY2suY29tMB4XDTI2MDIwMzAzNTIwM1oXDTI3MDIwMzAzNTIwM1owKTEnMCUGA1UEAwwedmVyaWZpZXIyLnRoZWF1c3RyYWxpYWhhY2suY29tMFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAE1Z2eGpdQVfWkAQQmNv8oT+lMwbhsFxWTZmhAYFHR5wa29fr30LbWoYQyrvOqOkSGrLaQ9PwoiedvGUtnN5Jd2qNMMEowKQYDVR0RBCIwIIIedmVyaWZpZXIyLnRoZWF1c3RyYWxpYWhhY2suY29tMB0GA1UdDgQWBBRt0uKz8aKVlUxKF9j6vhAsGl3nHDAKBggqhkjOPQQDAgNHADBEAiAQ+AlF3Q4dput8QTizDyKo99R/sv3CC7BzqEjOxxsnzQIgF+rnBf0HghobWkjSVNwP8j/ekasfjp+1HDJclcNaUvs="],
      "dcql_query": {
        "credentials": [
          {
            "id": "eudi_pid_sdjwt",
            "format": "dc+sd-jwt",
            "meta": {
              "vct_values": ["urn:eudi:pid:1"]
            },
            "claims": [
              { "path": ["family_name"] },
              { "path": ["given_name"] },
              { "path": ["birth_date"] }
            ]
          }
        ]
      }
    }
  }'
```

### SD-JWT Verification Policies

| Policy | Description | Status |
|--------|-------------|--------|
| `dc+sd-jwt/audience-check` | Verify presentation audience | ✅ |
| `dc+sd-jwt/kb-jwt_signature` | Verify holder key binding JWT | ✅ |
| `dc+sd-jwt/nonce-check` | Check presentation nonce | ✅ |
| `dc+sd-jwt/sd_hash-check` | Verify SD-JWT hash | ✅ |
| `signature` | Verify credential signature | ⚠️ |

**Known Limitation:** SD-JWT signature verification requires the issuer to publish JWKS at `/.well-known/jwt-vc-issuer/{issuer-path}`.
When using custom per-request issuer keys, the verifier cannot resolve the public key from JWKS and signature verification fails.
All VP (presentation) policies pass, confirming the wallet correctly presented the credential with proper key binding.

**Workaround:** For full signature verification, use the issuer's pre-configured IACA-signed key that is published in JWKS.

---

## 3. Mobile Driving License (mDL) - Signed Request

**Status:** ✅ Verified

### Create Session

```bash
curl -X POST "https://verifier2.theaustraliahack.com/verification-session/create" \
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
          "x": "1Z2eGpdQVfWkAQQmNv8oT-lMwbhsFxWTZmhAYFHR5wY",
          "y": "tvX699C21qGEMq7zqjpEhqy2kPT8KInnbxlLZzeSXdo",
          "d": "j6-GyxLnrDSQGCljc678kmrihQFa0GR92JZXHDEQX38"
        }
      },
      "x5c": ["MIIBnzCCAUagAwIBAgIUQSg5NhDlxwDFyAM7YJe++0QGyKIwCgYIKoZIzj0EAwIwKTEnMCUGA1UEAwwedmVyaWZpZXIyLnRoZWF1c3RyYWxpYWhhY2suY29tMB4XDTI2MDIwMzAzNTIwM1oXDTI3MDIwMzAzNTIwM1owKTEnMCUGA1UEAwwedmVyaWZpZXIyLnRoZWF1c3RyYWxpYWhhY2suY29tMFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAE1Z2eGpdQVfWkAQQmNv8oT+lMwbhsFxWTZmhAYFHR5wa29fr30LbWoYQyrvOqOkSGrLaQ9PwoiedvGUtnN5Jd2qNMMEowKQYDVR0RBCIwIIIedmVyaWZpZXIyLnRoZWF1c3RyYWxpYWhhY2suY29tMB0GA1UdDgQWBBRt0uKz8aKVlUxKF9j6vhAsGl3nHDAKBggqhkjOPQQDAgNHADBEAiAQ+AlF3Q4dput8QTizDyKo99R/sv3CC7BzqEjOxxsnzQIgF+rnBf0HghobWkjSVNwP8j/ekasfjp+1HDJclcNaUvs="],
      "dcql_query": {
        "credentials": [
          {
            "id": "mdl",
            "format": "mso_mdoc",
            "meta": {
              "doctype_value": "org.iso.18013.5.1.mDL"
            },
            "claims": [
              { "path": ["org.iso.18013.5.1", "family_name"] },
              { "path": ["org.iso.18013.5.1", "given_name"] },
              { "path": ["org.iso.18013.5.1", "document_number"] },
              { "path": ["org.iso.18013.5.1", "driving_privileges"] }
            ]
          }
        ]
      }
    }
  }'
```

---

## 3. Check Session Status

```bash
curl -s "https://verifier2.theaustraliahack.com/verification-session/{SESSION_ID}/info" | jq '{
  status,
  presentedPresentations: .presentedPresentations | keys
}'
```

### Expected Result (after successful presentation)

```json
{
  "status": "SUCCESSFUL",
  "presentedPresentations": ["eudi_pid_mdoc"]
}
```

---

# Quick Reference

## Issuance Endpoints

| Credential | Endpoint | Config ID |
|------------|----------|-----------|
| PID mDoc | `/openid4vc/mdoc/issue` | `eu.europa.ec.eudi.pid.1` |
| PID SD-JWT | `/openid4vc/sdjwt/issue` | `urn:eudi:pid:1` |
| mDL | `/openid4vc/mdoc/issue` | `org.iso.18013.5.1.mDL` |

## Verification DCQL Query Format

| Credential | Format | DocType/VCT | Namespace |
|------------|--------|-------------|-----------|
| PID mDoc | `mso_mdoc` | `eu.europa.ec.eudi.pid.1` | `eu.europa.ec.eudi.pid.1` |
| PID SD-JWT | `dc+sd-jwt` | `urn:eudi:pid:1` | N/A (flat claims) |
| mDL | `mso_mdoc` | `org.iso.18013.5.1.mDL` | `org.iso.18013.5.1` |

## Required Claims by Credential

### PID (both formats)
- `family_name` (mandatory)
- `given_name` (mandatory)
- `birth_date` (mandatory)
- `issuance_date` (mandatory)
- `expiry_date` (mandatory)
- `issuing_country` (mandatory)
- `issuing_authority` (mandatory)
- `age_over_18` (optional)

### mDL
- `family_name` (mandatory)
- `given_name` (mandatory)
- `birth_date` (mandatory)
- `issue_date` (mandatory)
- `expiry_date` (mandatory)
- `issuing_country` (mandatory)
- `issuing_authority` (mandatory)
- `document_number` (mandatory)
- `driving_privileges` (mandatory)

---

## Trigger Wallet (After Getting Offer/Session URL)

### QR Code (Recommended)
Generate QR code from the `openid-credential-offer://` or `openid4vp://authorize` URL.

### ADB (with proper escaping)
```bash
# Issuance
adb shell am start -a android.intent.action.VIEW \
  -d 'openid-credential-offer://?credential_offer_uri=https%3A%2F%2Fissuer.theaustraliahack.com%2Fdraft13%2FcredentialOffer%3Fid%3D{SESSION_ID}'

# Verification - USE SINGLE QUOTES to prevent & escaping
adb shell am start -a android.intent.action.VIEW \
  -d 'openid4vp://authorize?client_id=x509_san_dns%3Averifier2.theaustraliahack.com&request_uri=https%3A%2F%2Fverifier2.theaustraliahack.com%2Fverification-session%2F{SESSION_ID}%2Frequest'
```
