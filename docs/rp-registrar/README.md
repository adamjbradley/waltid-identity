# RP Registrar (Multi-Tenant Verification)

## Overview

The RP Registrar allows multiple organizations to share a single verifier-api2 deployment, each with independent X.509 certificates, client identifiers, and compliance metadata.

## Feature Flag

Controlled by `RP_REGISTRAR_ENABLED` (default: `false`). Zero runtime impact when disabled.

| Location | Default |
|----------|---------|
| `docker-compose/.env` | `RP_REGISTRAR_ENABLED=false` |

## Enable

```bash
RP_REGISTRAR_ENABLED=true
docker compose --profile identity up -d verifier-api2
curl http://localhost:7004/admin/rp
```

## Key Endpoints

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/admin/rp` | List all RPs |
| `POST` | `/admin/rp` | Register a new RP |
| `GET` | `/admin/rp/{id}` | Get RP details |
| `PUT` | `/admin/rp/{id}` | Update RP |
| `DELETE` | `/admin/rp/{id}` | Delete RP |
| `POST` | `/admin/rp/{id}/certificate/generate` | Generate X.509 certificate |
| `GET` | `/admin/rp/{id}/certificate/download` | Download certificate (PEM) |
| `PUT` | `/admin/rp/{id}/status` | Change status |

## Registration Flow

### 1. Register

```bash
curl -X POST http://localhost:7004/admin/rp \
  -H 'Content-Type: application/json' \
  -d '{"legalName":"Example Verifier","country":"AU","domain":"verifier.example.com","contactEmail":"admin@example.com","dataProtectionOfficer":"Jane Doe","privacyPolicyUrl":"https://example.com/privacy","legalBasis":"GDPR Art. 6(1)(a)"}'
```

### 2. Generate Certificate

```bash
curl -X POST http://localhost:7004/admin/rp/{id}/certificate/generate
```

Certificate includes: `CN={legalName}, C={country}`, SAN `DNS:{domain}`, ECDSA P-256, EKU `id-kp-clientAuth`.

### 3. Activate

```bash
curl -X PUT http://localhost:7004/admin/rp/{id}/status \
  -H 'Content-Type: application/json' \
  -d '{"status":"ACTIVE"}'
```

### 4. Verify Using RP

```bash
curl -X POST http://localhost:7004/openid4vc/verify \
  -H 'Content-Type: application/json' \
  -d '{"rpId":"{rp-id}","request_credentials":[...],"signed_request":true}'
```

## Status Lifecycle

| Status | Description |
|--------|-------------|
| **PENDING** | Newly registered |
| **ACTIVE** | Operational |
| **SUSPENDED** | Temporarily disabled |

## Portal Integration

- **Admin page:** `/admin/relying-parties`
- **Verification dropdown:** RP selection in verification setup

See [portal-guide.md](portal-guide.md) for UI documentation.
