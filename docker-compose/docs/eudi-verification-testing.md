# EUDI Wallet Verification Testing Guide

## Overview

This document describes how to test the OpenID4VP verification flow between the walt.id verifier services and the EUDI Reference Wallet.

## Prerequisites

1. Docker and Docker Compose installed
2. EUDI Reference Wallet app (dev flavor) installed on Android device
3. Wallet configured with `https://issuer.theaustraliahack.com/draft13` as issuer
4. Device connected via USB with ADB access

## Services

| Service | URL | Purpose |
|---------|-----|---------|
| verifier-api | https://verifier.theaustraliahack.com | Legacy verifier (draft protocols) |
| verifier-api2 | https://verifier2.theaustraliahack.com | Modern verifier (OID4VP 1.0 + DCQL) |
| web-portal | https://portal.theaustraliahack.com | Web UI for issuing/verifying |

## Step 1: Start Services

```bash
cd docker-compose

# Pull and start services
docker compose --profile identity pull
docker compose --profile identity up -d

# Verify services are running
docker compose --profile identity ps
```

## Step 2: Issue Credential to Wallet

1. Open the EUDI Wallet app
2. Tap "Add Document"
3. Select the issuer (theaustraliahack.com)
4. Choose "PID mDoc" (eu.europa.ec.eudi.pid.1)
5. Complete the issuance flow
6. Verify the credential appears in your wallet

## Step 3: Test Verification with verifier-api2

### Option A: Using curl

```bash
# Create a verification session for PID mDoc
curl -X POST https://verifier2.theaustraliahack.com/verification-session/create \
  -H "Content-Type: application/json" \
  -d '{
    "core": {
      "dcqlQuery": {
        "credentials": [{
          "id": "pid_mdoc",
          "format": "mso_mdoc",
          "meta": {
            "doctype_value": "eu.europa.ec.eudi.pid.1"
          },
          "claims": [
            {"path": ["eu.europa.ec.eudi.pid.1", "family_name"]},
            {"path": ["eu.europa.ec.eudi.pid.1", "given_name"]},
            {"path": ["eu.europa.ec.eudi.pid.1", "birth_date"]}
          ]
        }]
      }
    }
  }'
```

Response will include:
- `id`: Session ID
- `url`: QR code content for wallet to scan

### Option B: Using web portal

1. Go to https://portal.theaustraliahack.com
2. Navigate to Verifier section
3. Create a verification request for PID
4. Display the QR code

### Step 4: Complete Verification

1. Open EUDI Wallet app
2. Tap the QR scanner icon
3. Scan the QR code from step 3
4. Review the requested claims
5. Confirm to share the data
6. Verification should complete successfully

## Step 5: Test with verifier-api (legacy)

The legacy verifier uses a different URL format:

```bash
# Create a verification session
curl -X POST https://verifier.theaustraliahack.com/openid4vc/verify \
  -H "Content-Type: application/json" \
  -H "authorizeBaseUrl: openid4vp://authorize" \
  -d '{
    "request_credentials": [
      {
        "format": "mso_mdoc",
        "doctype": "eu.europa.ec.eudi.pid.1"
      }
    ]
  }'
```

## Troubleshooting

### Check logs

```bash
# Verifier API logs
docker logs docker-compose-verifier-api-1 --tail 100

# Verifier API2 logs
docker logs docker-compose-verifier-api2-1 --tail 100

# Wallet logs (via ADB)
adb logcat -d | grep -iE "EUDI|openid4vp|presentation|verifier"
```

### Common Issues

1. **QR code not scanning**: Check URL scheme is `openid4vp://` or `eudi-openid4vp://`
2. **Untrusted verifier**: Ensure wallet has verifier certificate in trust store
3. **Session expired**: Sessions expire after 5 minutes by default
4. **Invalid credential format**: Verify DCQL query matches issued credential format

### Verify Certificate Trust

```bash
# Check if wallet has the verifier certificates
adb shell ls /data/data/eu.europa.ec.euidi.dev/files/ | grep pem
```

## OpenID4VP Schemes Supported by EUDI Wallet

| Scheme | Description |
|--------|-------------|
| `openid4vp://` | Standard OpenID4VP |
| `eudi-openid4vp://` | EUDI-specific scheme |
| `mdoc-openid4vp://` | ISO 18013-7 mDoc scheme |
| `haip://` | HAIP scheme |

## Session Status Codes

| Status | Description |
|--------|-------------|
| `pending` | Session created, waiting for wallet |
| `request_sent` | Wallet received request |
| `response_received` | Wallet sent presentation |
| `successful` | Verification completed successfully |
| `unsuccessful` | Verification failed |
