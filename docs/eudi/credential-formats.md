# EUDI Credential Formats Quick Reference

## Format Summary

| Credential | Configuration ID | Format | Type Field | Status |
|------------|------------------|--------|------------|--------|
| EUDI PID (mDoc) | `eu.europa.ec.eudi.pid.1` | `mso_mdoc` | doctype | ✅ Verified |
| EUDI PID (SD-JWT) | `urn:eudi:pid:1` | `dc+sd-jwt` | vct | ✅ Verified |
| mDL | `org.iso.18013.5.1.mDL` | `mso_mdoc` | doctype | ⚠️ Needs Investigation |

## EUDI PID (mso_mdoc)

```json
{
  "credential_configuration_id": "eu.europa.ec.eudi.pid.1",
  "format": "mso_mdoc",
  "doctype": "eu.europa.ec.eudi.pid.1",
  "namespace": "eu.europa.ec.eudi.pid.1"
}
```

### Required Claims

| Claim | Namespace | Type |
|-------|-----------|------|
| family_name | eu.europa.ec.eudi.pid.1 | tstr |
| given_name | eu.europa.ec.eudi.pid.1 | tstr |
| birth_date | eu.europa.ec.eudi.pid.1 | full-date |
| issuing_country | eu.europa.ec.eudi.pid.1 | tstr |
| issuing_authority | eu.europa.ec.eudi.pid.1 | tstr |

### Optional Claims

| Claim | Namespace | Type |
|-------|-----------|------|
| age_over_18 | eu.europa.ec.eudi.pid.1 | bool |
| age_over_21 | eu.europa.ec.eudi.pid.1 | bool |
| nationality | eu.europa.ec.eudi.pid.1 | tstr |
| resident_country | eu.europa.ec.eudi.pid.1 | tstr |
| portrait | eu.europa.ec.eudi.pid.1 | bstr |

## EUDI PID (dc+sd-jwt)

```json
{
  "credential_configuration_id": "urn:eudi:pid:1",
  "format": "dc+sd-jwt",
  "vct": "urn:eudi:pid:1"
}
```

### SD-JWT Structure

```
<header>.<payload>.<signature>~<disclosure1>~<disclosure2>~...
```

### Standard Claims

| Claim | SD | Required |
|-------|----|----------|
| iss | No | Yes |
| iat | No | Yes |
| exp | No | Recommended |
| vct | No | Yes |
| cnf | No | Yes (holder binding) |
| family_name | Yes | Yes |
| given_name | Yes | Yes |
| birth_date | Yes | Yes |
| issuing_country | No | Yes |
| issuing_authority | No | Yes |

## Mobile Driving License (mDL)

```json
{
  "credential_configuration_id": "org.iso.18013.5.1.mDL",
  "format": "mso_mdoc",
  "doctype": "org.iso.18013.5.1.mDL",
  "namespace": "org.iso.18013.5.1"
}
```

### Required Claims (ISO 18013-5)

| Claim | Namespace | Type |
|-------|-----------|------|
| family_name | org.iso.18013.5.1 | tstr |
| given_name | org.iso.18013.5.1 | tstr |
| birth_date | org.iso.18013.5.1 | full-date |
| issue_date | org.iso.18013.5.1 | full-date |
| expiry_date | org.iso.18013.5.1 | full-date |
| issuing_country | org.iso.18013.5.1 | tstr |
| issuing_authority | org.iso.18013.5.1 | tstr |
| document_number | org.iso.18013.5.1 | tstr |
| driving_privileges | org.iso.18013.5.1 | array |

### Driving Privileges Structure

```json
{
  "driving_privileges": [
    {
      "vehicle_category_code": "B",
      "issue_date": "2023-01-15",
      "expiry_date": "2033-01-15"
    }
  ]
}
```

## Issuance Request Examples

### EUDI PID mDoc

```bash
curl -X POST http://localhost:7002/openid4vc/mdoc/issue \
  -H "Content-Type: application/json" \
  -d '{
    "issuerKey": {"type": "jwk", "jwk": {...}},
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

### EUDI PID SD-JWT

```bash
curl -X POST http://localhost:7002/openid4vc/sdjwt/issue \
  -H "Content-Type: application/json" \
  -d '{
    "issuerKey": {"type": "jwk", "jwk": {...}},
    "credentialConfigurationId": "urn:eudi:pid:1",
    "credentialData": {
      "family_name": "MUSTERMANN",
      "given_name": "ERIKA",
      "birth_date": "1984-01-26",
      "issuing_country": "DE",
      "issuing_authority": "German Federal Government"
    },
    "selectiveDisclosure": {
      "fields": {
        "family_name": {"sd": true},
        "given_name": {"sd": true},
        "birth_date": {"sd": true}
      }
    },
    "issuerDid": "did:key:z6Mk..."
  }'
```

### mDL

```bash
curl -X POST http://localhost:7002/openid4vc/mdoc/issue \
  -H "Content-Type: application/json" \
  -d '{
    "issuerKey": {"type": "jwk", "jwk": {...}},
    "credentialConfigurationId": "org.iso.18013.5.1.mDL",
    "credentialData": {
      "family_name": "MUSTERMANN",
      "given_name": "ERIKA",
      "birth_date": "1984-01-26",
      "issue_date": "2023-01-15",
      "expiry_date": "2033-01-15",
      "issuing_country": "DE",
      "issuing_authority": "Kraftfahrt-Bundesamt",
      "document_number": "T22000129",
      "driving_privileges": [
        {
          "vehicle_category_code": "B",
          "issue_date": "2023-01-15",
          "expiry_date": "2033-01-15"
        }
      ]
    },
    "issuerDid": "did:key:z6Mk..."
  }'
```

## Format Comparison

| Feature | mso_mdoc | dc+sd-jwt |
|---------|----------|-----------|
| Encoding | CBOR | JSON (JWT) |
| Signature | COSE_Sign1 | JWS |
| Selective Disclosure | Mobile Security Object | SD claims with hashes |
| Binding | cose_key | cnf claim |
| Type Identifier | doctype | vct |
| Standard | ISO 18013-5 | IETF SD-JWT-VC |

## Protocol Versions

| Spec | Version | Format String |
|------|---------|---------------|
| OpenID4VCI | Draft 13+ | Uses credential_configuration_id |
| OpenID4VP | 1.0 | Uses DCQL for mDoc |
| SD-JWT-VC | Draft 28 | dc+sd-jwt |
| mDoc | ISO 18013-5 | mso_mdoc |
