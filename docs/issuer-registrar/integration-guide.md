# Issuer Registrar Integration Guide

## API Reference

Base URL: `http://localhost:7002` (or your issuer-api host)

### Register an Issuer

```bash
curl -X POST http://localhost:7002/admin/issuer \
  -H 'Content-Type: application/json' \
  -d '{
    "legalName": "Example Bank Ltd",
    "country": "AU",
    "domain": "issuer.example.com",
    "contactEmail": "admin@example.com",
    "contactAddress": "123 Main St, Sydney"
  }'
```

**Response (201):**
```json
{
  "id": "a1b2c3d4-...",
  "legalName": "Example Bank Ltd",
  "country": "AU",
  "domain": "issuer.example.com",
  "contactEmail": "admin@example.com",
  "status": "ACTIVE",
  "createdAt": "2024-01-15T10:30:00Z",
  "updatedAt": "2024-01-15T10:30:00Z"
}
```

### Generate Certificates

```bash
curl -X POST http://localhost:7002/admin/issuer/{id}/certificate/generate
```

Generates an IACA (root CA) + Document Signer (leaf) certificate chain plus a `ciTokenKey` for OID4VCI token signing.

### Set Credential Configurations

```bash
curl -X PUT http://localhost:7002/admin/issuer/{id}/credentials \
  -H 'Content-Type: application/json' \
  -d '{
    "eu.europa.ec.eudi.pid.1": {
      "format": "mso_mdoc",
      "docType": "eu.europa.ec.eudi.pid.1",
      "claims": {
        "eu.europa.ec.eudi.pid.1": {
          "given_name": {}, "family_name": {}, "birth_date": {}
        }
      }
    }
  }'
```

### List Issuers

```bash
curl http://localhost:7002/admin/issuer
```

### Get Issuer Detail

```bash
curl http://localhost:7002/admin/issuer/{id}
```

### Update Issuer

```bash
curl -X PUT http://localhost:7002/admin/issuer/{id} \
  -H 'Content-Type: application/json' \
  -d '{"status": "SUSPENDED"}'
```

### Delete Issuer

```bash
curl -X DELETE http://localhost:7002/admin/issuer/{id}
```

### Download Certificates

```bash
curl http://localhost:7002/admin/issuer/{id}/certificate/download
```

### Upload Existing Certificates

```bash
curl -X POST http://localhost:7002/admin/issuer/{id}/certificate/upload \
  -H 'Content-Type: application/json' \
  -d '{
    "issuerKeyJwk": {"kty":"EC","crv":"P-256","x":"...","y":"...","d":"..."},
    "x5Chain": ["<base64-leaf-cert>", "<base64-iaca-cert>"],
    "ciTokenKeyJwk": {"kty":"EC","crv":"P-256","x":"...","y":"...","d":"..."}
  }'
```

## Tenant Issuance Flow

### 1. Issue a Credential

```bash
curl -X POST http://localhost:7002/issuers/{id}/openid4vc/mdoc/issue \
  -H 'Content-Type: application/json' \
  -d '{
    "credentialConfigurationId": "eu.europa.ec.eudi.pid.1",
    "mdocData": {
      "eu.europa.ec.eudi.pid.1": {
        "given_name": "John",
        "family_name": "Doe",
        "birth_date": "1990-01-15"
      }
    }
  }'
```

Returns a credential offer URI. The wallet discovers tenant metadata at the tenant-scoped URL.

### 2. Wallet Flow

1. Wallet scans QR code containing the credential offer URI
2. Wallet resolves `credential_issuer` from the offer (contains `/issuers/{id}`)
3. Wallet fetches metadata: `GET /issuers/{id}/draft13/.well-known/openid-credential-issuer`
4. Wallet gets token: `POST /issuers/{id}/draft13/token`
5. Wallet gets credential: `POST /issuers/{id}/draft13/credential`

All URLs are tenant-scoped. The wallet follows URLs from the credential offer.

### Available Issue Endpoints

| Format | Endpoint |
|--------|----------|
| mDoc | `POST /issuers/{id}/openid4vc/mdoc/issue` |
| SD-JWT | `POST /issuers/{id}/openid4vc/sdjwt/issue` |
| JWT | `POST /issuers/{id}/openid4vc/jwt/issue` |

### Tenant Metadata

```bash
curl http://localhost:7002/issuers/{id}/draft13/.well-known/openid-credential-issuer
```

Returns OpenID4VCI metadata with only this tenant's credential configurations.

## Portal Integration

Set `NEXT_PUBLIC_ISSUER_REGISTRAR_ENABLED=true` in the portal environment to enable the admin UI at `/admin/issuers`.

When issuing credentials through the portal with a tenant selected, the portal constructs tenant-scoped URLs automatically by passing `issuerId` through the query parameters.

## Error Codes

| Status | Meaning |
|--------|---------|
| 404 | Unknown tenant ID |
| 403 | Tenant is SUSPENDED or REVOKED, or has no signing keys |
| 401 | Cross-tenant token (token signed by different tenant's key) |
| 409 | Duplicate domain on registration |
| 503 | Issuer Registrar feature not enabled |
