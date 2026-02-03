# EUDI Wallet Deployment Guide

This guide covers deploying walt.id services for EUDI wallet compatibility.

## Prerequisites

- Docker and Docker Compose
- Java 21 (for building from source)
- Valid TLS certificates (for production)

## Quick Start with Docker Compose

```bash
cd docker-compose

# Pull and start the identity stack
docker compose --profile identity pull
docker compose --profile identity up
```

### Service Ports

| Service | Port | Description |
|---------|------|-------------|
| Issuer API | 7002 | OpenID4VCI issuer endpoints |
| Verifier API | 7003 | OpenID4VP verifier endpoints |
| Wallet API | 7001 | Wallet backend API |
| Demo Wallet | 7101 | Web wallet UI |
| Web Portal | 7102 | Issuer/verifier portal |

## Configuration

### Issuer Metadata Configuration

Edit `docker-compose/issuer-api/config/credential-issuer-metadata.conf`:

```hocon
credentialIssuer = "https://issuer.example.com"
credentialEndpoint = "https://issuer.example.com/openid4vc/credential"
tokenEndpoint = "https://issuer.example.com/openid4vc/token"
authorizationEndpoint = "https://issuer.example.com/openid4vc/authorize"

# DPoP support
dpopSigningAlgValuesSupported = ["ES256", "ES384", "ES512"]

# Credential configurations
credentialConfigurationsSupported {
  "eu.europa.ec.eudi.pid.1" {
    format = "mso_mdoc"
    doctype = "eu.europa.ec.eudi.pid.1"
    cryptographicBindingMethodsSupported = ["cose_key"]
    credentialSigningAlgValuesSupported = ["ES256"]
    display = [
      {
        name = "EU PID"
        locale = "en"
      }
    ]
  }

  "org.iso.18013.5.1.mDL" {
    format = "mso_mdoc"
    doctype = "org.iso.18013.5.1.mDL"
    cryptographicBindingMethodsSupported = ["cose_key"]
    credentialSigningAlgValuesSupported = ["ES256"]
    display = [
      {
        name = "Mobile Driving License"
        locale = "en"
      }
    ]
  }

  "urn:eu.europa.ec.eudi:pid:1" {
    format = "dc+sd-jwt"
    vct = "urn:eu.europa.ec.eudi:pid:1"
    cryptographicBindingMethodsSupported = ["jwk"]
    credentialSigningAlgValuesSupported = ["ES256"]
    display = [
      {
        name = "EU Digital Identity"
        locale = "en"
      }
    ]
  }
}
```

### Environment Variables

Create `docker-compose/.env.local`:

```env
# Issuer configuration
WALTID_ISSUER_URL=https://issuer.example.com
WALTID_ISSUER_PORT=7002

# Database
WALTID_DB_URL=jdbc:postgresql://db:5432/waltid
WALTID_DB_USER=waltid
WALTID_DB_PASSWORD=your-secure-password

# TLS (for production)
WALTID_TLS_ENABLED=true
WALTID_TLS_CERT_PATH=/certs/server.crt
WALTID_TLS_KEY_PATH=/certs/server.key
```

## Building Custom Docker Images

To include customizations:

```bash
# Build all service images
./gradlew jibDockerBuild

# Build specific service
./gradlew :waltid-services:waltid-issuer-api:jibDockerBuild
```

## Production Considerations

### TLS/SSL

For production, configure TLS:

1. Obtain certificates from a trusted CA
2. Configure reverse proxy (nginx/traefik) or application TLS
3. Ensure all endpoints use HTTPS

### Scaling

For high availability:

```yaml
# docker-compose.override.yml
services:
  waltid-issuer-api:
    deploy:
      replicas: 3
    environment:
      - WALTID_CLUSTER_ENABLED=true
      - WALTID_REDIS_URL=redis://redis:6379
```

### Monitoring

Enable metrics endpoint:

```hocon
# application.conf
metrics {
  enabled = true
  endpoint = "/metrics"
}
```

## Verification

### Test Issuer Metadata

```bash
curl https://issuer.example.com/openid4vc/draft13/.well-known/openid-credential-issuer | jq
```

Expected response includes:
- `credential_issuer`
- `credential_endpoint`
- `credential_configurations_supported`
- `dpop_signing_alg_values_supported`

### Generate Test Credential Offer

```bash
curl -X POST https://issuer.example.com/openid4vc/mdoc/issue \
  -H "Content-Type: application/json" \
  -d '{
    "issuerKey": { "type": "jwk", "jwk": {...} },
    "credentialConfigurationId": "eu.europa.ec.eudi.pid.1",
    "credentialData": {...},
    "issuerDid": "did:key:..."
  }'
```

Returns: `openid-credential-offer://...`

### EUDI Wallet Test

1. Open EUDI Reference Wallet app
2. Scan the QR code from the credential offer
3. Complete issuance flow
4. Verify credential appears in wallet

## Troubleshooting

### Container Logs

```bash
docker compose --profile identity logs waltid-issuer-api -f
```

### Health Checks

```bash
curl http://localhost:7002/health
```

### Common Issues

| Issue | Solution |
|-------|----------|
| Connection refused | Verify container is running and port mappings |
| TLS errors | Check certificate chain and hostname |
| Credential format mismatch | Verify metadata configuration matches issuance request |
| "Invalid audience" | Ensure issuer URL matches metadata `credentialIssuer` |

## Security Checklist

- [ ] TLS enabled for all endpoints
- [ ] Strong database passwords
- [ ] Firewall configured for service ports
- [ ] Rate limiting enabled
- [ ] Logging configured for audit trail
- [ ] Secrets managed securely (not in env vars for production)
