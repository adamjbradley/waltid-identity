# EUDI Wallet Integration Guide

This document captures the credential format requirements for integrating with the EU Digital Identity (EUDI) reference wallet.

## Credential Configuration Requirements

### SD-JWT PID (Personal Identification Data)

The EUDI wallet expects the following exact values:

| Field | Required Value | Notes |
|-------|----------------|-------|
| `credential_configuration_id` | `urn:eudi:pid:1` | Must match exactly - wallet uses this to identify PID credentials |
| `vct` | `urn:eudi:pid:1` | Verifiable Credential Type for SD-JWT |
| `format` | `dc+sd-jwt` | Digital Credentials SD-JWT format (EUDI-compatible) |
| `cryptographic_binding_methods_supported` | `["jwk"]` | JWK binding for key proofs |
| `proof_types_supported` | Required when binding methods specified | See below |

### mDoc PID

| Field | Required Value | Notes |
|-------|----------------|-------|
| `doctype` | `eu.europa.ec.eudi.pid.1` | ISO 18013-5 document type |
| `format` | `mso_mdoc` | Mobile Security Object format |
| `cryptographic_binding_methods_supported` | `["cose_key"]` | COSE key binding |
| `proof_types_supported` | Required when binding methods specified | See below |

## Critical: proof_types_supported Requirement

**When `cryptographic_binding_methods_supported` is specified, you MUST also specify `proof_types_supported`.**

This is an OID4VCI specification requirement. The issuer will reject metadata that specifies binding methods without proof types.

### Example Configuration

```hocon
"urn:eudi:pid:1" = {
    format = "dc+sd-jwt"
    cryptographic_binding_methods_supported = ["jwk"]
    credential_signing_alg_values_supported = ["ES256"]
    proof_types_supported = { jwt = { proof_signing_alg_values_supported = ["ES256"] } }
    vct = "urn:eudi:pid:1"
    display = [
        {
            name = "EU Personal ID"
            locale = "en"
            logo = { url = "https://example.com/pid-logo.png", alt_text = "PID Logo" }
        }
    ]
}
```

## Configuration Override Behavior

**Important:** The `credential-issuer-metadata.conf` file **overrides** the default values from `CredentialTypeConfig.kt`.

If you define a credential in the config file, you must include ALL required fields - the code defaults will NOT be merged in.

### Files Involved

| File | Purpose |
|------|---------|
| `docker-compose/issuer-api/config/credential-issuer-metadata.conf` | Runtime config (overrides code) |
| `waltid-services/waltid-issuer-api/src/main/kotlin/id/walt/issuer/config/CredentialTypeConfig.kt` | Default values (fallback) |

## EUDI Wallet Document Identifiers

From the EUDI wallet source code (`DocumentIdentifier.kt`):

```kotlin
data object SdJwtPid : DocumentIdentifier {
    override val formatType: FormatType
        get() = "urn:eudi:pid:1"
}

data object MdocPid : DocumentIdentifier {
    override val formatType: FormatType
        get() = "eu.europa.ec.eudi.pid.1"
}
```

The wallet uses these identifiers to:
1. Match credential offers to known document types
2. Determine how to process and store credentials
3. Display appropriate UI for each credential type

## Common Errors and Fixes

### "Proof types must be specified if cryptographic binding methods are specified"

**Cause:** Missing `proof_types_supported` in credential configuration

**Fix:** Add proof types for each credential that specifies binding methods:
```hocon
proof_types_supported = { jwt = { proof_signing_alg_values_supported = ["ES256"] } }
```

### "No available documents" in wallet

**Cause:** Credential configuration ID doesn't match what wallet expects

**Fix:** Use exact identifiers:
- SD-JWT PID: `urn:eudi:pid:1`
- mDoc PID: `eu.europa.ec.eudi.pid.1`

### Wallet fetches metadata but fails silently

**Possible causes:**
1. `vct` value doesn't match `credential_configuration_id` for SD-JWT
2. Missing required fields in credential configuration
3. Issuer URL mismatch in wallet configuration

## Testing Credential Issuance

1. Generate a credential offer:
```bash
curl -X POST "https://issuer.example.com/draft13/openid4vc/credentialOffer" \
  -H "Content-Type: application/json" \
  -d '{
    "credentials": ["urn:eudi:pid:1"],
    "grants": {
      "urn:ietf:params:oauth:grant-type:pre-authorized_code": {
        "pre-authorized_code": "test-code"
      }
    }
  }'
```

2. Verify the credential offer contains correct configuration ID
3. Check issuer metadata endpoint returns correct `proof_types_supported`
4. Scan QR code with EUDI wallet

## Test Coverage

Test files for EUDI PID issuance are located in:

| File | Type | Coverage |
|------|------|----------|
| `waltid-issuer-api/src/test/kotlin/id/walt/eudi/EudiIssuanceTest.kt` | Unit | DPoP, Draft 13+ format, credential offers, sessions |
| `waltid-e2e-tests/src/test/kotlin/id/walt/eudi/EudiPidSdJwtE2ETest.kt` | E2E | SD-JWT PID full flow, VCT validation |
| `waltid-e2e-tests/src/test/kotlin/id/walt/eudi/EudiPidMdocE2ETest.kt` | E2E | mDoc PID full flow, doctype validation |
| `waltid-e2e-tests/src/test/kotlin/id/walt/eudi/EudiMdlE2ETest.kt` | E2E | mDL issuance flow |

### Running EUDI Tests

```bash
# Run EUDI unit tests
./gradlew :waltid-services:waltid-issuer-api:test --tests "id.walt.eudi.*"

# Run EUDI E2E tests (requires running services)
./gradlew :waltid-services:waltid-e2e-tests:test --tests "id.walt.eudi.*"
```

### Test vs Production Credential IDs

**Warning:** The E2E tests use different credential configuration IDs than the EUDI wallet expects:

| Format | E2E Test Value | EUDI Wallet Expects |
|--------|----------------|---------------------|
| SD-JWT | `urn:eu.europa.ec.eudi:pid:1` | `urn:eudi:pid:1` |
| mDoc | `eu.europa.ec.eudi.pid.1` | `eu.europa.ec.eudi.pid.1` (matches) |

The SD-JWT discrepancy exists because:
- E2E tests were written against an earlier spec version
- EUDI wallet hardcodes `urn:eudi:pid:1` in `DocumentIdentifier.kt`

For production EUDI wallet compatibility, use the values in this guide, not the E2E test constants.

## References

- [EUDI Wallet Reference Implementation](https://github.com/eu-digital-identity-wallet)
- [OID4VCI Specification](https://openid.net/specs/openid-4-verifiable-credential-issuance-1_0.html)
- [SD-JWT VC Type Metadata](https://www.ietf.org/archive/id/draft-ietf-oauth-sd-jwt-vc-08.html)
