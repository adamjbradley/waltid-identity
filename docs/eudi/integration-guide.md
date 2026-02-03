# EUDI Wallet Integration Guide

This guide explains how to configure walt.id issuer-api for compatibility with the EUDI Reference Wallet.

## Overview

The EUDI (European Digital Identity) Reference Wallet requires specific protocol versions and credential formats. This implementation supports:

- **OpenID4VCI Draft 13+** - Uses `credential_configuration_id` and `proofs` (plural)
- **mso_mdoc** - For EUDI PID and mDL credentials (ISO 18013-5)
- **dc+sd-jwt** - For EUDI PID in SD-JWT format with VCT
- **DPoP** - RFC 9449 Demonstrating Proof of Possession
- **Client Attestation** - OAuth 2.0 Attestation-Based Client Authentication

## Credential Configurations

### EUDI PID (mDoc format)

```json
{
  "eu.europa.ec.eudi.pid.1": {
    "format": "mso_mdoc",
    "doctype": "eu.europa.ec.eudi.pid.1",
    "scope": "eu.europa.ec.eudi.pid.1",
    "cryptographic_binding_methods_supported": ["cose_key"],
    "credential_signing_alg_values_supported": ["ES256"],
    "display": [
      {
        "name": "EU PID",
        "locale": "en",
        "logo": {
          "uri": "https://example.com/eudi-pid-logo.png"
        }
      }
    ],
    "claims": {
      "eu.europa.ec.eudi.pid.1": {
        "family_name": { "mandatory": true },
        "given_name": { "mandatory": true },
        "birth_date": { "mandatory": true },
        "age_over_18": { "mandatory": false },
        "issuing_country": { "mandatory": true },
        "issuing_authority": { "mandatory": true }
      }
    }
  }
}
```

### EUDI PID (SD-JWT format)

```json
{
  "urn:eu.europa.ec.eudi:pid:1": {
    "format": "dc+sd-jwt",
    "vct": "urn:eu.europa.ec.eudi:pid:1",
    "scope": "eu.europa.ec.eudi.pid.sdjwt",
    "cryptographic_binding_methods_supported": ["jwk"],
    "credential_signing_alg_values_supported": ["ES256"],
    "display": [
      {
        "name": "EU Digital Identity",
        "locale": "en"
      }
    ],
    "claims": {
      "family_name": { "mandatory": true, "sd": true },
      "given_name": { "mandatory": true, "sd": true },
      "birth_date": { "mandatory": true, "sd": true },
      "issuing_country": { "mandatory": true },
      "issuing_authority": { "mandatory": true }
    }
  }
}
```

### Mobile Driving License (mDL)

```json
{
  "org.iso.18013.5.1.mDL": {
    "format": "mso_mdoc",
    "doctype": "org.iso.18013.5.1.mDL",
    "scope": "org.iso.18013.5.1.mDL",
    "cryptographic_binding_methods_supported": ["cose_key"],
    "credential_signing_alg_values_supported": ["ES256"],
    "display": [
      {
        "name": "Mobile Driving License",
        "locale": "en"
      }
    ],
    "claims": {
      "org.iso.18013.5.1": {
        "family_name": { "mandatory": true },
        "given_name": { "mandatory": true },
        "birth_date": { "mandatory": true },
        "issue_date": { "mandatory": true },
        "expiry_date": { "mandatory": true },
        "issuing_country": { "mandatory": true },
        "issuing_authority": { "mandatory": true },
        "document_number": { "mandatory": true },
        "driving_privileges": { "mandatory": true }
      }
    }
  }
}
```

## Issuance API Examples

### Issue EUDI PID (mDoc)

```bash
curl -X POST http://localhost:7002/openid4vc/mdoc/issue \
  -H "Content-Type: application/json" \
  -d '{
    "issuerKey": {
      "type": "jwk",
      "jwk": { "kty": "EC", "crv": "P-256", ... }
    },
    "credentialConfigurationId": "eu.europa.ec.eudi.pid.1",
    "credentialData": {
      "family_name": "MUSTERMANN",
      "given_name": "ERIKA",
      "birth_date": "1984-01-26",
      "age_over_18": true,
      "issuing_country": "DE",
      "issuing_authority": "German Federal Government"
    },
    "issuerDid": "did:key:z6Mk..."
  }'
```

### Issue EUDI PID (SD-JWT)

```bash
curl -X POST http://localhost:7002/openid4vc/sdjwt/issue \
  -H "Content-Type: application/json" \
  -d '{
    "issuerKey": {
      "type": "jwk",
      "jwk": { "kty": "EC", "crv": "P-256", ... }
    },
    "credentialConfigurationId": "urn:eu.europa.ec.eudi:pid:1",
    "credentialData": {
      "family_name": "MUSTERMANN",
      "given_name": "ERIKA",
      "birth_date": "1984-01-26",
      "issuing_country": "DE",
      "issuing_authority": "German Federal Government"
    },
    "selectiveDisclosure": {
      "fields": {
        "family_name": { "sd": true },
        "given_name": { "sd": true },
        "birth_date": { "sd": true }
      }
    },
    "issuerDid": "did:key:z6Mk..."
  }'
```

## Draft 13+ Protocol Details

### Credential Request Format

EUDI wallets send requests with `credential_configuration_id` and `proofs`:

```json
{
  "credential_configuration_id": "eu.europa.ec.eudi.pid.1",
  "proofs": {
    "jwt": [
      "eyJhbGciOiJFUzI1NiIsInR5cCI6Im9wZW5pZDR2Y2ktcHJvb2Yrand0IiwiandrIjp7Li4ufX0.eyJhdWQiOiJodHRwczovL2lzc3Vlci5leGFtcGxlLmNvbSIsImlhdCI6MTcwNjg5MjgwMCwibm9uY2UiOiJjX25vbmNlX3ZhbHVlIn0.signature"
    ]
  }
}
```

**Key differences from legacy:**
- Uses `credential_configuration_id` instead of `format` field
- Uses `proofs.jwt[]` array instead of `proof.jwt` string
- Supports batch issuance with multiple proofs

### Credential Response Format

```json
{
  "credentials": [
    {
      "credential": "base64-encoded-mdoc-or-sdjwt"
    }
  ],
  "c_nonce": "new_nonce_value",
  "c_nonce_expires_in": 86400
}
```

## DPoP Support

The issuer supports RFC 9449 DPoP for enhanced security:

### Supported Algorithms
- ES256, ES384, ES512 (ECDSA)
- RS256, RS384, RS512 (RSA)

### DPoP Proof Structure

```json
{
  "typ": "dpop+jwt",
  "alg": "ES256",
  "jwk": { "kty": "EC", "crv": "P-256", "x": "...", "y": "..." }
}
{
  "jti": "unique-id",
  "htm": "POST",
  "htu": "https://issuer.example.com/token",
  "iat": 1706892800
}
```

## Troubleshooting

### Common Issues

| Issue | Solution |
|-------|----------|
| "Invalid format" error | Ensure `credential_configuration_id` matches metadata exactly |
| "Unknown credential type" | Check VCT matches for SD-JWT, doctype for mDoc |
| "Proof verification failed" | Verify proof JWT uses correct audience and nonce |
| "DPoP validation failed" | Check htm/htu match actual HTTP method/URI |

### Format String Mapping

| Credential Type | Format String | EUDI Format |
|-----------------|---------------|-------------|
| PID mDoc | `mso_mdoc` | mso_mdoc |
| PID SD-JWT | `dc+sd-jwt` | dc+sd-jwt (not vc+sd-jwt) |
| mDL | `mso_mdoc` | mso_mdoc |

### Debugging

Enable debug logging to see request/response details:

```properties
# application.properties
logging.level.id.walt.issuer=DEBUG
logging.level.id.walt.oid4vc=DEBUG
```

## Related Documentation

- [Deployment Guide](deployment-guide.md) - Production deployment configuration
- [Credential Formats](credential-formats.md) - Quick reference for formats
