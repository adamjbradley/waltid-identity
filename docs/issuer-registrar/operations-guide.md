# Issuer Registrar Operations Guide

## Enable the Feature

### Docker Compose

```bash
# In docker-compose/.env
ISSUER_REGISTRAR_ENABLED=true

# Rebuild and restart
./gradlew :waltid-services:waltid-issuer-api:jibDockerBuild
docker tag waltid/issuer-api:latest waltid/issuer-api:stable
cd docker-compose
docker compose --profile identity up -d issuer-api

# Verify
curl http://localhost:7002/admin/issuer
# Expected: [] (empty array)
```

### Configuration File

`docker-compose/issuer-api/config/issuer-registrar.conf`:

```hocon
storageDir = "config/issuer-tenants"
```

Tenant JSON files are stored in this directory, one file per tenant.

## Register a Tenant (Full Lifecycle)

```bash
# 1. Register
ISSUER_ID=$(curl -s -X POST http://localhost:7002/admin/issuer \
  -H 'Content-Type: application/json' \
  -d '{
    "legalName": "Test Bank",
    "country": "AU",
    "domain": "bank.issuer.example.com",
    "contactEmail": "admin@bank.com"
  }' | jq -r '.id')

echo "Created issuer: $ISSUER_ID"

# 2. Generate certificates
curl -s -X POST http://localhost:7002/admin/issuer/$ISSUER_ID/certificate/generate | jq '.signerCertificate'

# 3. Set credential catalog
curl -s -X PUT http://localhost:7002/admin/issuer/$ISSUER_ID/credentials \
  -H 'Content-Type: application/json' \
  -d '{
    "eu.europa.ec.eudi.pid.1": {
      "format": "mso_mdoc",
      "docType": "eu.europa.ec.eudi.pid.1",
      "claims": {
        "eu.europa.ec.eudi.pid.1": {
          "given_name": {}, "family_name": {}, "birth_date": {},
          "age_over_18": {}, "nationality": {}
        }
      }
    }
  }'

# 4. Verify tenant metadata
curl -s http://localhost:7002/issuers/$ISSUER_ID/draft13/.well-known/openid-credential-issuer | jq '.credential_configurations_supported | keys'

# 5. Issue a test credential
curl -s -X POST http://localhost:7002/issuers/$ISSUER_ID/openid4vc/mdoc/issue \
  -H 'Content-Type: application/json' \
  -d '{
    "credentialConfigurationId": "eu.europa.ec.eudi.pid.1",
    "mdocData": {
      "eu.europa.ec.eudi.pid.1": {
        "given_name": "Alice",
        "family_name": "Smith",
        "birth_date": "1990-05-20",
        "age_over_18": true,
        "nationality": "AU"
      }
    }
  }'
```

## Tenant Lifecycle Management

### Suspend a Tenant

Prevents new issuance sessions. Existing sessions can still complete.

```bash
curl -X PUT http://localhost:7002/admin/issuer/$ISSUER_ID \
  -H 'Content-Type: application/json' \
  -d '{"status": "SUSPENDED"}'
```

### Reactivate a Suspended Tenant

```bash
curl -X PUT http://localhost:7002/admin/issuer/$ISSUER_ID \
  -H 'Content-Type: application/json' \
  -d '{"status": "ACTIVE"}'
```

### Revoke a Tenant (Permanent)

Revocation is permanent and cannot be undone.

```bash
curl -X PUT http://localhost:7002/admin/issuer/$ISSUER_ID \
  -H 'Content-Type: application/json' \
  -d '{"status": "REVOKED"}'
```

### Delete a Tenant

Removes the tenant entirely, including cached CIProvider.

```bash
curl -X DELETE http://localhost:7002/admin/issuer/$ISSUER_ID
```

## Certificate Management

### Regenerate Certificates

Generates new IACA + Document Signer chain, invalidating the cached CIProvider.

```bash
curl -X POST http://localhost:7002/admin/issuer/$ISSUER_ID/certificate/generate
```

### Download Certificates

```bash
curl http://localhost:7002/admin/issuer/$ISSUER_ID/certificate/download | jq '.'
```

### Certificate Expiry

- **Document Signer:** 1 year from generation
- **IACA:** 5 years from generation

Monitor `signerCertificate.notAfter` in tenant detail responses.

## EUDI Wallet Trust Store

When a tenant generates an IACA certificate, that IACA must be added to the EUDI wallet's trust store for the wallet to accept mDoc credentials from that tenant.

1. Export tenant IACA cert: `GET /admin/issuer/{id}/certificate/download`
2. Extract the second entry from `x5c` array (IACA cert, base64 DER)
3. Convert to PEM:
   ```bash
   echo "-----BEGIN CERTIFICATE-----" > iaca.pem
   echo "<base64-iaca-cert>" >> iaca.pem
   echo "-----END CERTIFICATE-----" >> iaca.pem
   ```
4. Add to EUDI wallet's `res/raw/` directory
5. Rebuild EUDI wallet APK

## Backup & Recovery

Tenant data is stored as JSON files in `config/issuer-tenants/` (configurable via `storageDir`).

- Each tenant is a single JSON file: `{tenant-id}.json`
- Back up this directory to preserve all tenant configurations
- Restore by copying JSON files back and restarting the service

## Troubleshooting

| Issue | Cause | Fix |
|-------|-------|-----|
| `GET /admin/issuer` returns 404 | Feature not enabled | Set `ISSUER_REGISTRAR_ENABLED=true` |
| Tenant returns 403 | Tenant SUSPENDED or REVOKED | Check tenant status |
| Tenant returns 403 "no signing keys" | Certificates not generated | Run certificate/generate |
| Wallet rejects credential | IACA not in wallet trust store | Add IACA cert to wallet |
| Cross-tenant token 401 | Token from different tenant | Tokens are tenant-scoped, cannot be reused |
| Duplicate domain 409 | Domain already registered | Use a unique domain per tenant |
