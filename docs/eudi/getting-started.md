# EUDI Wallet Integration: Getting Started

This guide walks through the complete end-to-end flow for issuing credentials to and verifying presentations from the EUDI Reference Wallet.

## Prerequisites

### 1. Running Services

Start the walt.id identity stack:

```bash
cd docker-compose
docker compose --profile identity up -d
```

Verify services are running:

| Service | URL | Port |
|---------|-----|------|
| Issuer API | http://localhost:7002 | 7002 |
| Verifier API2 | http://localhost:7004 | 7004 |
| Web Portal | http://localhost:7102 | 7102 |

### 2. EUDI Reference Wallet

Download and install the EUDI Reference Wallet:
- **Android:** [EUDI Wallet Dev APK](https://github.com/eu-digital-identity-wallet/eudi-app-android-wallet-ui/releases)
- **iOS:** Build from [source](https://github.com/eu-digital-identity-wallet/eudi-app-ios-wallet-ui)

### 3. Network Access

The wallet needs to reach your issuer/verifier. Options:
- **Local network:** Device and server on same network
- **Public deployment:** Services exposed via reverse proxy (e.g., nginx)
- **ngrok/tunneling:** For quick testing

### 4. Trust Store Configuration

The EUDI wallet must trust your verifier's X.509 certificate. See [Wallet Trust Store Update](wallet-trust-store-update.md) for instructions.

---

## Part 1: Credential Issuance

### Step 1.1: Configure Issuer Metadata

Ensure your issuer has EUDI-compatible credential configurations in `credential-issuer-metadata.conf`:

```hocon
"eu.europa.ec.eudi.pid.1" {
  format = "mso_mdoc"
  doctype = "eu.europa.ec.eudi.pid.1"
  cryptographic_binding_methods_supported = ["cose_key"]
  credential_signing_alg_values_supported = ["ES256"]
  claims {
    "eu.europa.ec.eudi.pid.1" {
      family_name { mandatory = true }
      given_name { mandatory = true }
      birth_date { mandatory = true }
    }
  }
}
```

See [Credential Formats](credential-formats.md) for complete format specifications.

### Step 1.2: Create Credential Offer (API)

```bash
curl -X POST http://localhost:7002/openid4vc/credential-offer \
  -H "Content-Type: application/json" \
  -d '{
    "credentialConfigurationId": "eu.europa.ec.eudi.pid.1",
    "credentialData": {
      "family_name": "DOE",
      "given_name": "JOHN",
      "birth_date": "1990-01-15",
      "issuing_country": "AU",
      "issuing_authority": "Test Authority"
    }
  }'
```

Response contains an `offerUrl`:
```json
{
  "offerUrl": "openid-credential-offer://?credential_offer_uri=https://issuer.example.com/credential-offer/abc123"
}
```

### Step 1.3: Create Credential Offer (Web Portal)

Alternatively, use the Web Portal UI:

1. Navigate to http://localhost:7102
2. Select **Issue Credential**
3. Choose credential type: **EUDI PID (mDoc)**
4. Fill in the claim values
5. Click **Create Offer**
6. Scan the QR code with EUDI wallet

### Step 1.4: Accept in EUDI Wallet

1. Open EUDI Wallet app
2. Scan the credential offer QR code (or tap the offer URL)
3. Review the credential details
4. Accept the credential
5. The credential is now stored in the wallet

### Verify Issuance Success

Check the wallet's credential list - you should see the new PID credential with the claims you provided.

---

## Part 2: Credential Verification

### Step 2.1: Create Verification Session (API)

```bash
curl -X POST https://verifier2.theaustraliahack.com/verification-session/create \
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
          "x": "YOUR_PUBLIC_KEY_X",
          "y": "YOUR_PUBLIC_KEY_Y",
          "d": "YOUR_PRIVATE_KEY_D"
        }
      },
      "x5c": ["YOUR_BASE64_CERTIFICATE"],
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

Response:
```json
{
  "sessionId": "abc-123-def",
  "bootstrapAuthorizationRequestUrl": "openid4vp://authorize?client_id=...&request_uri=..."
}
```

### Step 2.2: Create Verification Session (Web Portal)

1. Navigate to http://localhost:7102
2. Select **Verify Credential**
3. Choose **EUDI PID (mDoc)**
4. Select claims to request
5. Click **Create Session**
6. A QR code is displayed

### Step 2.3: Present Credential from EUDI Wallet

1. Open EUDI Wallet app
2. Scan the verification QR code
3. Review the requested claims
4. Authorize the presentation
5. The credential is presented to the verifier

### Step 2.4: Check Verification Result

```bash
curl -s "https://verifier2.theaustraliahack.com/verification-session/{SESSION_ID}/info" \
  | jq '{status, presentedPresentations}'
```

Expected result:
```json
{
  "status": "SUCCESSFUL",
  "presentedPresentations": {
    "eudi_pid_mdoc": {
      "credentialData": {
        "family_name": "DOE",
        "given_name": "JOHN",
        "birth_date": "1990-01-15"
      }
    }
  }
}
```

---

## Quick Reference

### Supported Credential Formats

| Credential | Config ID | Format |
|------------|-----------|--------|
| EUDI PID (mDoc) | `eu.europa.ec.eudi.pid.1` | `mso_mdoc` |
| EUDI PID (SD-JWT) | `urn:eu.europa.ec.eudi:pid:1` | `dc+sd-jwt` |
| Mobile Driving License | `org.iso.18013.5.1.mDL` | `mso_mdoc` |

### Service URLs (Production Example)

| Service | URL |
|---------|-----|
| Issuer | https://issuer.theaustraliahack.com |
| Verifier (modern) | https://verifier2.theaustraliahack.com |
| Verifier (legacy) | https://verifier.theaustraliahack.com |
| Web Portal | https://portal.theaustraliahack.com |

### Key Configuration Files

| File | Purpose |
|------|---------|
| `docker-compose/issuer-api/config/credential-issuer-metadata.conf` | Credential configurations |
| `docker-compose/verifier-api2/keys/` | Verifier X.509 certificates |
| `docker-compose/.env` | Environment variables |

---

## Troubleshooting

### Issuance Issues

| Problem | Solution |
|---------|----------|
| "Unknown credential configuration" | Check `credential-issuer-metadata.conf` has the configuration ID |
| "Invalid proof" | Ensure issuer URL matches the `aud` in wallet's proof JWT |
| Wallet can't reach issuer | Check network connectivity, try ngrok for local testing |

### Verification Issues

| Problem | Solution |
|---------|----------|
| "X509SanDns cannot be used in unsigned request" | Use QR code instead of ADB; see [ADB escaping caveat](verification-testing.md#adb-shell-escaping-caveat) |
| "Certificate not trusted" | Add verifier cert to wallet trust store |
| Session stays "UNUSED" | Wallet didn't fetch the request; check URL accessibility |

### General Issues

| Problem | Solution |
|---------|----------|
| Services not starting | Run `docker compose --profile identity logs` to check errors |
| Port conflicts | Change ports in `.env` file |
| Stale containers | Run `docker compose --profile identity down && docker compose --profile identity up -d` |

---

## Related Documentation

- [Complete Examples](examples.md) - **Full validated curl examples for all flows**
- [Credential Formats](credential-formats.md) - Detailed format specifications
- [Integration Guide](integration-guide.md) - Issuer API integration details
- [Deployment Guide](deployment-guide.md) - Production deployment configuration
- [Issuance Testing](issuance-testing.md) - Issuance flow details and troubleshooting
- [Verification Testing](verification-testing.md) - Verification flow details and test results
- [Wallet Trust Store](wallet-trust-store-update.md) - Trust store configuration
- [Technical Notes](technical-notes.md) - Debugging insights and implementation details

---

## Verified Working Configuration

**Date:** 2026-02-04

| Component | Version/Config |
|-----------|----------------|
| walt.id Services | 1.0.0-SNAPSHOT |
| EUDI Wallet | Reference Implementation (Dev) |
| Credential | PID mDoc (`eu.europa.ec.eudi.pid.1`) |
| Verifier Client ID | `x509_san_dns:verifier2.theaustraliahack.com` |
| Request Mode | Signed (JAR - RFC 9101) |
| Response Mode | `direct_post` |

All verification policies passed:
- `mso_mdoc/device-auth`
- `mso_mdoc/device_key_auth`
- `mso_mdoc/issuer_auth`
- `mso_mdoc/issuer_signed_integrity`
- `mso_mdoc/mso`
