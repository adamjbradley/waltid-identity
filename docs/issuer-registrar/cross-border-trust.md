# Cross-Border Trust Architecture

## Overview

The Issuer Registrar generates ETSI TS 119 612 compliant trust lists (TSLs) from registered issuer tenants, enabling cross-border credential verification.

## Architecture

```
LOTL (GET /admin/issuer/lotl.xml)
  -> Country TSL AU (GET /admin/issuer/tsl/AU.xml)
       Provider: Australia Post [IACA cert]
       Provider: Service NSW [IACA cert]
  -> Country TSL DE (GET /admin/issuer/tsl/DE.xml)
       Provider: Bundesdruckerei [IACA cert]
```

### LOTL (List of Trusted Lists)

- **Endpoint:** `GET {ISSUER_API}/admin/issuer/lotl.xml`
- **Format:** ETSI TS 119 612 `TrustServiceStatusList` with `OtherTSLPointer` entries
- **Dynamic:** Automatically includes all countries with `ACTIVE` issuers

### Country TSL

- **Endpoint:** `GET {ISSUER_API}/admin/issuer/tsl/{CC}.xml`
- **Format:** ETSI TS 119 612 `TrustServiceStatusList` with `TrustServiceProvider` entries
- **Certificates:** Each provider includes its IACA root certificate in `ServiceDigitalIdentity`

## Deployment Models

### Model A: LOTL Replacement (Isolated Demo)

Replace the EU LOTL URL to create a fully isolated trust domain.

```hocon
# Verifier trust-lists.conf
etsi {
    enabled = true
    lotlUrl = "https://issuer.theaustraliahack.com/admin/issuer/lotl.xml"
}
```

```env
# Docker Compose .env
TRUST_LISTS_ENABLED=true
ETSI_LOTL_URL=https://issuer.theaustraliahack.com/admin/issuer/lotl.xml
```

**When to use:** Demos, development, isolated pilots.

### Model B: Additive Import (Recommended)

Import country TSLs alongside the real EU LOTL.

```hocon
# Verifier trust-lists.conf
etsi {
    enabled = true
    lotlUrl = "https://ec.europa.eu/tools/lotl/eu-lotl.xml"
    additionalTslUrls {
        AU = "https://issuer.theaustraliahack.com/admin/issuer/tsl/AU.xml"
    }
}
```

Or at runtime:
```bash
curl -X POST http://localhost:7004/admin/trust/custom-tsls \
  -H 'Content-Type: application/json' \
  -d '{"country":"AU","url":"https://issuer.theaustraliahack.com/admin/issuer/tsl/AU.xml"}'
```

**When to use:** Production environments extending existing EU trust.

## Step-by-Step Demo

### 1. Register Issuers

```bash
curl -X POST http://localhost:7002/admin/issuer \
  -H 'Content-Type: application/json' \
  -d '{"legalName":"Australia Post","country":"AU","domain":"auspost.com.au","contactEmail":"admin@auspost.com.au"}'
```

### 2. Generate Certificates

```bash
curl -X POST http://localhost:7002/admin/issuer/{id}/certificate/generate
```

### 3. Configure Credentials

```bash
curl -X PUT http://localhost:7002/admin/issuer/{id}/credentials \
  -H 'Content-Type: application/json' \
  -d '{"eu.europa.ec.eudi.pid.1":{"format":"mso_mdoc","doctype":"eu.europa.ec.eudi.pid.1","display":[{"name":"PID (mDoc)","locale":"en"}]}}'
```

### 4. Verify Trust Lists

```bash
curl http://localhost:7002/admin/issuer/lotl.xml
curl http://localhost:7002/admin/issuer/tsl/AU.xml
```

### 5. Configure Verifier

Use Model A or Model B above.

### 6. Issue and Verify

1. Issue a credential using a registered tenant
2. Present the credential to the verifier
3. Verifier validates signing certificate against the trust list
4. Verification succeeds — issuer's IACA is in the trusted TSL
