# EUDI Wallet Issuance Testing Guide

**Last Verified:** 2026-02-05

This guide documents the end-to-end credential issuance flow to the EUDI Reference Wallet.

## Overview

| Component | Value |
|-----------|-------|
| Issuer Service | issuer-api |
| Issuer URL | `https://issuer.theaustraliahack.com` |
| Protocol | OpenID4VCI Draft 13+ |
| Supported Formats | `mso_mdoc`, `dc+sd-jwt` |

## Prerequisites

1. **issuer-api** running with EUDI-compatible configuration
2. **EUDI Wallet** installed (dev flavor)
3. Network connectivity between wallet and issuer

## Method 1: API-Based Issuance

### SD-JWT PID Issuance (Copy-Paste Ready)

```bash
curl -s -X POST "https://issuer.theaustraliahack.com/openid4vc/sdjwt/issue" \
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
      "family_name": "Smith",
      "given_name": "Alice",
      "birth_date": "1985-06-20",
      "nationality": "AU",
      "issuance_date": "2026-02-05",
      "expiry_date": "2031-02-05",
      "issuing_country": "AU",
      "issuing_authority": "Test Authority"
    }
  }'
```

### Response

Returns the credential offer URI directly:
```
openid-credential-offer://?credential_offer_uri=https%3A%2F%2Fissuer.theaustraliahack.com%2Fdraft13%2FcredentialOffer%3Fid%3D<UUID>
```

### mDoc PID Issuance (Copy-Paste Ready)

**IMPORTANT:** mDoc credentials require an `x5Chain` parameter containing a valid X.509 certificate chain for verification to work. Without it, verification fails with "x5c X509 certificate chain is empty".

```bash
curl -s -X POST "https://issuer.theaustraliahack.com/openid4vc/mdoc/issue" \
  -H "Content-Type: application/json" \
  -d '{
    "issuerKey": {
      "type": "jwk",
      "jwk": {
        "kty": "EC",
        "d": "-wSIL_tMH7-mO2NAfHn03I8ZWUHNXVzckTTb96Wsc1s",
        "crv": "P-256",
        "kid": "sW5yv0UmZ3S0dQuUrwlR9I3foREBHHFwXhGJGqGEVf0",
        "x": "Pzp6eVSAdXERqAp8q8OuDEhl2ILGAaoaQXTJ2sD2g5U",
        "y": "6dwhUAzKzKUf0kNI7f40zqhMZNT0c40O_WiqSLCTNZo"
      }
    },
    "credentialConfigurationId": "eu.europa.ec.eudi.pid.1",
    "mdocData": {
      "eu.europa.ec.eudi.pid.1": {
        "family_name": "Smith",
        "given_name": "Alice",
        "birth_date": "1985-06-20",
        "age_over_18": true,
        "issuance_date": "2026-02-05",
        "expiry_date": "2031-02-05",
        "issuing_country": "AU",
        "issuing_authority": "Test Authority"
      }
    },
    "x5Chain": [
      "-----BEGIN CERTIFICATE-----\nMIICCTCCAbCgAwIBAgIUfqyiArJZoX7M61/473UAVi2/UpgwCgYIKoZIzj0EAwIwKDELMAkGA1UEBhMCQVQxGTAXBgNVBAMMEFdhbHRpZCBUZXN0IElBQ0EwHhcNMjUwNjAyMDY0MTEzWhcNMjYwOTAyMDY0MTEzWjAzMQswCQYDVQQGEwJBVDEkMCIGA1UEAwwbV2FsdGlkIFRlc3QgRG9jdW1lbnQgU2lnbmVyMFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEPzp6eVSAdXERqAp8q8OuDEhl2ILGAaoaQXTJ2sD2g5Xp3CFQDMrMpR/SQ0jt/jTOqExk1PRzjQ79aKpIsJM1mqOBrDCBqTAfBgNVHSMEGDAWgBTxCn2nWMrE70qXb614U14BweY2azAdBgNVHQ4EFgQUx5qkOLC4lpl1xpYZGmF9HLxtp0gwDgYDVR0PAQH/BAQDAgeAMBoGA1UdEgQTMBGGD2h0dHBzOi8vd2FsdC5pZDAVBgNVHSUBAf8ECzAJBgcogYxdBQECMCQGA1UdHwQdMBswGaAXoBWGE2h0dHBzOi8vd2FsdC5pZC9jcmwwCgYIKoZIzj0EAwIDRwAwRAIgHTap3c6yCUNhDVfZWBPMKj9dCWZbrME03kh9NJTbw1ECIAvVvuGll9O21eR16SkJHHAA1pPcovhcTvF9fz9cc66M\n-----END CERTIFICATE-----"
    ]
  }'
```

**Key differences from SD-JWT:**
- Uses `mdocData` (not `credentialData`) with namespaced claims
- Requires `x5Chain` with Document Signer certificate (valid until 2026-09-02)
- Certificate must match the issuer key's public key

### Response

Returns the credential offer URI directly:
```
openid-credential-offer://?credential_offer_uri=https%3A%2F%2Fissuer.theaustraliahack.com%2Fdraft13%2FcredentialOffer%3Fid%3D<UUID>
```

### Step 2: Trigger Wallet

**Option A: QR Code (Recommended)**
- Generate QR code from `credentialOffer` URL
- Scan with EUDI wallet

**Option B: Share URL**
- Share the `credentialOffer` URL to the wallet app

**Option C: ADB (Recommended for Testing)**
```bash
# Store the offer URI in a variable, then launch
OFFER="openid-credential-offer://?credential_offer_uri=https%3A%2F%2Fissuer.theaustraliahack.com%2Fdraft13%2FcredentialOffer%3Fid%3D<UUID>"
adb shell am start -a android.intent.action.VIEW -d "'$OFFER'"
```

**One-liner for SD-JWT PID:**
```bash
OFFER=$(curl -s -X POST "https://issuer.theaustraliahack.com/openid4vc/sdjwt/issue" \
  -H "Content-Type: application/json" \
  -d '{"issuerKey":{"type":"jwk","jwk":{"kty":"EC","crv":"P-256","x":"_-t2Oc_Nra8Cgix7Nw2-_RuZt5KrgVZsK3r8aTMSsVQ","y":"nkaVInW3t_q5eB85KnULykQbprApT2RCNZZuJlNPD2Q","d":"URb-8MihTBwKpFA91vzVfcuqxj5qhjNrnhd2fARX62A","kid":"eudi-issuer-key-1"}},"credentialConfigurationId":"urn:eudi:pid:1","credentialData":{"family_name":"Smith","given_name":"Alice","birth_date":"1985-06-20","nationality":"AU","issuance_date":"2026-02-05","expiry_date":"2031-02-05","issuing_country":"AU","issuing_authority":"Test Authority"}}') \
  && adb shell am start -a android.intent.action.VIEW -d "'$OFFER'"
```

**One-liner for mDoc PID (with x5Chain for verification support):**
```bash
OFFER=$(curl -s -X POST "https://issuer.theaustraliahack.com/openid4vc/mdoc/issue" \
  -H "Content-Type: application/json" \
  -d '{"issuerKey":{"type":"jwk","jwk":{"kty":"EC","d":"-wSIL_tMH7-mO2NAfHn03I8ZWUHNXVzckTTb96Wsc1s","crv":"P-256","kid":"sW5yv0UmZ3S0dQuUrwlR9I3foREBHHFwXhGJGqGEVf0","x":"Pzp6eVSAdXERqAp8q8OuDEhl2ILGAaoaQXTJ2sD2g5U","y":"6dwhUAzKzKUf0kNI7f40zqhMZNT0c40O_WiqSLCTNZo"}},"credentialConfigurationId":"eu.europa.ec.eudi.pid.1","mdocData":{"eu.europa.ec.eudi.pid.1":{"family_name":"Smith","given_name":"Alice","birth_date":"1985-06-20","age_over_18":true,"issuance_date":"2026-02-05","expiry_date":"2031-02-05","issuing_country":"AU","issuing_authority":"Test Authority"}},"x5Chain":["-----BEGIN CERTIFICATE-----\nMIICCTCCAbCgAwIBAgIUfqyiArJZoX7M61/473UAVi2/UpgwCgYIKoZIzj0EAwIwKDELMAkGA1UEBhMCQVQxGTAXBgNVBAMMEFdhbHRpZCBUZXN0IElBQ0EwHhcNMjUwNjAyMDY0MTEzWhcNMjYwOTAyMDY0MTEzWjAzMQswCQYDVQQGEwJBVDEkMCIGA1UEAwwbV2FsdGlkIFRlc3QgRG9jdW1lbnQgU2lnbmVyMFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEPzp6eVSAdXERqAp8q8OuDEhl2ILGAaoaQXTJ2sD2g5Xp3CFQDMrMpR/SQ0jt/jTOqExk1PRzjQ79aKpIsJM1mqOBrDCBqTAfBgNVHSMEGDAWgBTxCn2nWMrE70qXb614U14BweY2azAdBgNVHQ4EFgQUx5qkOLC4lpl1xpYZGmF9HLxtp0gwDgYDVR0PAQH/BAQDAgeAMBoGA1UdEgQTMBGGD2h0dHBzOi8vd2FsdC5pZDAVBgNVHSUBAf8ECzAJBgcogYxdBQECMCQGA1UdHwQdMBswGaAXoBWGE2h0dHBzOi8vd2FsdC5pZC9jcmwwCgYIKoZIzj0EAwIDRwAwRAIgHTap3c6yCUNhDVfZWBPMKj9dCWZbrME03kh9NJTbw1ECIAvVvuGll9O21eR16SkJHHAA1pPcovhcTvF9fz9cc66M\n-----END CERTIFICATE-----"]}') \
  && adb shell am start -a android.intent.action.VIEW -d "'$OFFER'"
```

**One-liner for mDL:**

> **IMPORTANT:** The EUDI wallet requires a PID (National ID) credential before it will accept an mDL. If you attempt to issue an mDL without a PID, the wallet displays: *"Wallet needs to be activated first with a National ID"*. Issue a PID mDoc first using the command above.
```bash
OFFER=$(curl -s -X POST "https://issuer.theaustraliahack.com/openid4vc/mdoc/issue" \
  -H "Content-Type: application/json" \
  -d '{"issuerKey":{"type":"jwk","jwk":{"kty":"EC","d":"-wSIL_tMH7-mO2NAfHn03I8ZWUHNXVzckTTb96Wsc1s","crv":"P-256","kid":"sW5yv0UmZ3S0dQuUrwlR9I3foREBHHFwXhGJGqGEVf0","x":"Pzp6eVSAdXERqAp8q8OuDEhl2ILGAaoaQXTJ2sD2g5U","y":"6dwhUAzKzKUf0kNI7f40zqhMZNT0c40O_WiqSLCTNZo"}},"credentialConfigurationId":"org.iso.18013.5.1.mDL","mdocData":{"org.iso.18013.5.1":{"family_name":"Smith","given_name":"Alice","birth_date":"1985-06-20","issue_date":"2026-02-05","expiry_date":"2031-02-05","issuing_country":"AU","issuing_authority":"AU Transport","document_number":"DL123456789","portrait":[141,182,121,111,238,50,120,94,54,111,113,13,241,12,12],"driving_privileges":[{"vehicle_category_code":"B","issue_date":"2020-01-01","expiry_date":"2031-02-05"}],"un_distinguishing_sign":"AUS"}},"x5Chain":["-----BEGIN CERTIFICATE-----\nMIICCTCCAbCgAwIBAgIUfqyiArJZoX7M61/473UAVi2/UpgwCgYIKoZIzj0EAwIwKDELMAkGA1UEBhMCQVQxGTAXBgNVBAMMEFdhbHRpZCBUZXN0IElBQ0EwHhcNMjUwNjAyMDY0MTEzWhcNMjYwOTAyMDY0MTEzWjAzMQswCQYDVQQGEwJBVDEkMCIGA1UEAwwbV2FsdGlkIFRlc3QgRG9jdW1lbnQgU2lnbmVyMFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEPzp6eVSAdXERqAp8q8OuDEhl2ILGAaoaQXTJ2sD2g5Xp3CFQDMrMpR/SQ0jt/jTOqExk1PRzjQ79aKpIsJM1mqOBrDCBqTAfBgNVHSMEGDAWgBTxCn2nWMrE70qXb614U14BweY2azAdBgNVHQ4EFgQUx5qkOLC4lpl1xpYZGmF9HLxtp0gwDgYDVR0PAQH/BAQDAgeAMBoGA1UdEgQTMBGGD2h0dHBzOi8vd2FsdC5pZDAVBgNVHSUBAf8ECzAJBgcogYxdBQECMCQGA1UdHwQdMBswGaAXoBWGE2h0dHBzOi8vd2FsdC5pZC9jcmwwCgYIKoZIzj0EAwIDRwAwRAIgHTap3c6yCUNhDVfZWBPMKj9dCWZbrME03kh9NJTbw1ECIAvVvuGll9O21eR16SkJHHAA1pPcovhcTvF9fz9cc66M\n-----END CERTIFICATE-----"]}') \
  && adb shell am start -a android.intent.action.VIEW -d "'$OFFER'"
```

### Step 3: Accept in Wallet

1. Wallet displays credential offer details
2. Review the issuer and credential type
3. Tap "Accept" to proceed
4. Wallet generates proof of possession
5. Credential is issued and stored

## Method 2: Web Portal Issuance

### Step 1: Open Portal

Navigate to https://portal.theaustraliahack.com (or http://localhost:7102 for local)

### Step 2: Create Credential Offer

1. Click **Issue Credential** (or similar)
2. Select credential type:
   - **EUDI PID (mDoc)** for `eu.europa.ec.eudi.pid.1`
   - **EUDI PID (SD-JWT)** for `urn:eu.europa.ec.eudi:pid:1`
3. Fill in claim values:
   - family_name: DOE
   - given_name: JOHN
   - birth_date: 1990-01-15
4. Click **Create Offer**

### Step 3: Display QR Code

The portal displays a QR code containing the credential offer URL.

### Step 4: Scan with Wallet

1. Open EUDI wallet
2. Tap "Add Document" or QR scanner
3. Scan the QR code
4. Accept the credential

## Supported Credential Types

### EUDI PID (mDoc)

| Field | Value |
|-------|-------|
| Configuration ID | `eu.europa.ec.eudi.pid.1` |
| Format | `mso_mdoc` |
| DocType | `eu.europa.ec.eudi.pid.1` |

**Required for Issuance:**
- `mdocData` - Claims must be namespaced under `eu.europa.ec.eudi.pid.1`
- `x5Chain` - X.509 certificate chain for verification support

**Required Claims:**
- `family_name`
- `given_name`
- `birth_date`
- `issuing_country`
- `issuing_authority`

**Optional Claims:**
- `age_over_18`
- `age_over_21`
- `nationality`
- `portrait`

### EUDI PID (SD-JWT)

| Field | Value |
|-------|-------|
| Configuration ID | `urn:eudi:pid:1` |
| Format | `dc+sd-jwt` |
| VCT | `urn:eudi:pid:1` |

**Important:** Use `dc+sd-jwt` format, NOT `vc+sd-jwt`.

### Mobile Driving License (mDL)

| Field | Value |
|-------|-------|
| Configuration ID | `org.iso.18013.5.1.mDL` |
| Format | `mso_mdoc` |
| DocType | `org.iso.18013.5.1.mDL` |
| Namespace | `org.iso.18013.5.1` |

**Prerequisite:** EUDI wallet requires a PID (National ID) to be present before accepting an mDL.

**Required for Issuance:**
- `mdocData` - Claims must be namespaced under `org.iso.18013.5.1`
- `x5Chain` - X.509 certificate chain for verification support

**Required Claims:**
- `family_name`, `given_name`, `birth_date`
- `issue_date`, `expiry_date`
- `issuing_country`, `issuing_authority`
- `document_number`, `portrait`
- `driving_privileges` (array of vehicle categories)
- `un_distinguishing_sign`

## Issuer Configuration

### credential-issuer-metadata.conf

The issuer must have EUDI-compatible credential configurations:

```hocon
"eu.europa.ec.eudi.pid.1" {
  format = "mso_mdoc"
  doctype = "eu.europa.ec.eudi.pid.1"
  cryptographic_binding_methods_supported = ["cose_key"]
  credential_signing_alg_values_supported = ["ES256"]
  proof_types_supported {
    jwt {
      proof_signing_alg_values_supported = ["ES256"]
    }
  }
  claims {
    "eu.europa.ec.eudi.pid.1" {
      family_name { mandatory = true, display = [{ name = "Family Name" }] }
      given_name { mandatory = true, display = [{ name = "Given Name" }] }
      birth_date { mandatory = true, display = [{ name = "Date of Birth" }] }
      age_over_18 { mandatory = false }
      issuing_country { mandatory = true }
      issuing_authority { mandatory = true }
    }
  }
}
```

### Custom Docker Image

**IMPORTANT:** EUDI wallet compatibility requires a custom-built Docker image. The standard Docker Hub images may not include all necessary fixes.

```bash
# Build custom issuer image
./gradlew :waltid-services:waltid-issuer-api:jibDockerBuild

# Tag to match docker-compose VERSION_TAG
docker tag waltid/issuer-api:latest waltid/issuer-api:stable

# Restart with new image
cd docker-compose
docker compose up -d --force-recreate issuer-api
```

## Verification of Issuance

### Check Wallet

After successful issuance, the credential appears in the wallet's document list with:
- Credential type (e.g., "EU PID")
- Issuer name
- Claim values

### Check Issuer Logs

```bash
docker compose logs issuer-api --tail=50 | grep -E "(credential|offer|proof)"
```

Look for:
- Credential offer created
- Proof received and validated
- Credential issued

## Troubleshooting

### "Unknown credential configuration"

**Cause:** Configuration ID doesn't match issuer metadata.

**Solution:** Verify `credentialConfigurationId` exactly matches the key in `credential-issuer-metadata.conf`.

### "Invalid proof"

**Cause:** Proof JWT validation failed.

**Solutions:**
1. Check `aud` claim matches issuer URL
2. Verify `nonce` matches `c_nonce` from token response
3. Ensure proof type is `jwt` (not `cwt`)

### "Unsupported format"

**Cause:** Wallet requesting format issuer doesn't support.

**Solution:** Check `format` in credential configuration:
- mDoc: `mso_mdoc`
- SD-JWT: `dc+sd-jwt` (not `vc+sd-jwt`)

### Wallet Can't Reach Issuer

**Causes:**
1. Network connectivity issue
2. SSL certificate not trusted
3. Firewall blocking connection

**Solutions:**
1. Verify device and server on same network
2. Use proper SSL certificates (not self-signed)
3. Try ngrok for local testing

### Credential Not Appearing in Wallet

**Causes:**
1. Issuance failed silently
2. Credential format not supported by wallet

**Solutions:**
1. Check issuer logs for errors
2. Verify using supported format (`mso_mdoc` or `dc+sd-jwt`)

### mDL Issuance Fails: "Wallet needs to be activated first with a National ID"

**Cause:** The EUDI wallet requires a PID (Person Identification Data) credential before it will accept other document types like mDL.

**Solution:** Issue a PID mDoc credential first, then retry the mDL issuance. Use the "One-liner for mDoc PID" command above to issue a PID.

### mDoc Verification Fails: "x5c certificate chain is empty"

**Cause:** mDoc credential was issued without an X.509 certificate chain in the `x5Chain` parameter.

**Error from verifier:**
```
Contained x5c X509 certificate chain in Mdocs credentials is empty (no signer element)
```

**Solution:** Include the `x5Chain` parameter in the mDoc issuance request with a valid Document Signer certificate. The certificate must:
1. Be signed by a trusted IACA (Issuer Authority CA)
2. Have a public key matching the `issuerKey` used for signing
3. Be within its validity period

**Example:** See the mDoc PID Issuance section above for a working configuration with the "Waltid Test Document Signer" certificate (valid until 2026-09-02).

## OpenID4VCI Protocol Details

### Draft 13+ Changes

The EUDI wallet uses OpenID4VCI Draft 13+, which differs from earlier drafts:

| Feature | Draft 13+ | Earlier Drafts |
|---------|-----------|----------------|
| Configuration | `credential_configuration_id` | `format` field |
| Proofs | `proofs.jwt[]` array | `proof.jwt` string |
| Response | `credentials[]` array | `credential` string |

### Credential Request Format

```json
{
  "credential_configuration_id": "eu.europa.ec.eudi.pid.1",
  "proofs": {
    "jwt": [
      "eyJhbGciOiJFUzI1NiIsInR5cCI6Im9wZW5pZDR2Y2ktcHJvb2Yrand0In0..."
    ]
  }
}
```

### Credential Response Format

```json
{
  "credentials": [
    {
      "credential": "base64-encoded-mdoc"
    }
  ],
  "c_nonce": "new_nonce",
  "c_nonce_expires_in": 86400
}
```

## Related Documentation

- [Credential Formats](credential-formats.md) - Detailed format specifications
- [Integration Guide](integration-guide.md) - Issuer API integration
- [Getting Started](getting-started.md) - End-to-end flow overview
- [Verification Testing](verification-testing.md) - Verification flow testing
