# EUDI Wallet Issuance Testing Guide

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

### Step 1: Create Credential Offer

```bash
curl -X POST https://issuer.theaustraliahack.com/openid4vc/credential-offer \
  -H "Content-Type: application/json" \
  -d '{
    "credentialConfigurationId": "eu.europa.ec.eudi.pid.1",
    "credentialData": {
      "family_name": "DOE",
      "given_name": "JOHN",
      "birth_date": "1990-01-15",
      "age_over_18": true,
      "issuing_country": "AU",
      "issuing_authority": "Test Authority"
    }
  }'
```

### Response

```json
{
  "credentialOffer": "openid-credential-offer://?credential_offer_uri=https://issuer.theaustraliahack.com/credential-offer/abc123",
  "credentialOfferUri": "https://issuer.theaustraliahack.com/credential-offer/abc123"
}
```

### Step 2: Trigger Wallet

**Option A: QR Code (Recommended)**
- Generate QR code from `credentialOffer` URL
- Scan with EUDI wallet

**Option B: Share URL**
- Share the `credentialOffer` URL to the wallet app

**Option C: ADB**
```bash
# Use single quotes to prevent shell interpretation
adb shell am start -a android.intent.action.VIEW \
  -d 'openid-credential-offer://?credential_offer_uri=https://issuer.theaustraliahack.com/credential-offer/abc123'
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
