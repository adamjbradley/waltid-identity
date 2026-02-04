# EUDI Wallet Complete Examples

**Validated:** 2026-02-04

All examples in this document have been tested against the live issuer and verifier APIs.

---

## Issuer Key (for all examples)

```json
{
  "type": "jwk",
  "jwk": {
    "kty": "EC",
    "d": "mJJv_Hzv8--BHJaJlvB9KM8XQnM9M8J7KNZ8K_z9qdc",
    "crv": "P-256",
    "kid": "test-key-1",
    "x": "dHGO-XVe1E-tEjqLN5EFT_FHQFgXTQ-9U7TL5qm9_0g",
    "y": "L8L7_pV9t2qn7B8DJ1_N8pEyEL_WQ8wVBM_FqA7k5tw"
  }
}
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

## 1. EUDI PID (mDoc)

### Request

```bash
curl -X POST "https://issuer.theaustraliahack.com/openid4vc/mdoc/issue" \
  -H "Content-Type: application/json" \
  -d '{
    "issuerKey": {
      "type": "jwk",
      "jwk": {
        "kty": "EC",
        "d": "mJJv_Hzv8--BHJaJlvB9KM8XQnM9M8J7KNZ8K_z9qdc",
        "crv": "P-256",
        "kid": "test-key-1",
        "x": "dHGO-XVe1E-tEjqLN5EFT_FHQFgXTQ-9U7TL5qm9_0g",
        "y": "L8L7_pV9t2qn7B8DJ1_N8pEyEL_WQ8wVBM_FqA7k5tw"
      }
    },
    "credentialConfigurationId": "eu.europa.ec.eudi.pid.1",
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

## 2. EUDI PID (SD-JWT)

### Request

```bash
curl -X POST "https://issuer.theaustraliahack.com/openid4vc/sdjwt/issue" \
  -H "Content-Type: application/json" \
  -d '{
    "issuerKey": {
      "type": "jwk",
      "jwk": {
        "kty": "EC",
        "d": "mJJv_Hzv8--BHJaJlvB9KM8XQnM9M8J7KNZ8K_z9qdc",
        "crv": "P-256",
        "kid": "test-key-1",
        "x": "dHGO-XVe1E-tEjqLN5EFT_FHQFgXTQ-9U7TL5qm9_0g",
        "y": "L8L7_pV9t2qn7B8DJ1_N8pEyEL_WQ8wVBM_FqA7k5tw"
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

```bash
curl -X POST "https://issuer.theaustraliahack.com/openid4vc/mdoc/issue" \
  -H "Content-Type: application/json" \
  -d '{
    "issuerKey": {
      "type": "jwk",
      "jwk": {
        "kty": "EC",
        "d": "mJJv_Hzv8--BHJaJlvB9KM8XQnM9M8J7KNZ8K_z9qdc",
        "crv": "P-256",
        "kid": "test-key-1",
        "x": "dHGO-XVe1E-tEjqLN5EFT_FHQFgXTQ-9U7TL5qm9_0g",
        "y": "L8L7_pV9t2qn7B8DJ1_N8pEyEL_WQ8wVBM_FqA7k5tw"
      }
    },
    "credentialConfigurationId": "org.iso.18013.5.1.mDL",
    "credentialData": {
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
| `sd-jwt/holder-binding` | Verify holder key binding | ✅ |
| `sd-jwt/not-before` | Check nbf claim | ✅ |
| `sd-jwt/expiration` | Check exp claim | ✅ |
| `sd-jwt/issuer-trust` | Verify issuer | ✅ |
| `signature` | Verify credential signature | ✅ |

**Note:** SD-JWT signature verification requires the issuer to publish JWKS at `/.well-known/jwt-vc-issuer/{issuer-path}`.

---

## 3. Mobile Driving License (mDL) - Signed Request

**Status:** ⚠️ Needs Investigation - mDL issuance currently has issues

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
