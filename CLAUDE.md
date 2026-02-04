# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Git Workflow

**IMPORTANT:** This is a fork of `walt-id/waltid-identity`. When creating pull requests:
- Create PRs on **this fork** (`adamjbradley/waltid-identity`), NOT the upstream `walt-id/waltid-identity`
- Use `gh pr create --repo adamjbradley/waltid-identity` to ensure correct target

## Project Overview

walt.id Identity is an open-source digital identity and wallet platform providing libraries, APIs, and white-label applications for credential issuance, verification, and wallet management. It supports W3C Verifiable Credentials, SD-JWT, and ISO mdoc formats via OpenID4VC/VP protocols.

## Repository Structure

```
waltid-identity/
├── waltid-libraries/       # Core multiplatform libraries (Kotlin)
│   ├── auth/               # Authentication (ktor-authnz, permissions, idpkit)
│   ├── crypto/             # Cryptography (crypto, cose, x509, KMS integrations)
│   ├── credentials/        # Credential handling (w3c, mdoc, dcql, policies)
│   ├── protocols/          # Protocol implementations (openid4vc, openid4vp)
│   ├── sdjwt/              # Selective Disclosure JWT
│   └── waltid-did/         # Decentralized Identifiers
├── waltid-services/        # Production REST APIs
│   ├── waltid-issuer-api/  # Credential issuance
│   ├── waltid-verifier-api/  # Legacy verifier (OID4VP drafts)
│   ├── waltid-verifier-api2/ # Modern verifier (OID4VP 1.0 + DCQL)
│   └── waltid-wallet-api/  # Wallet backend
├── waltid-applications/    # End-user applications
│   ├── waltid-web-wallet/  # Vue/Nuxt PWA wallet
│   ├── waltid-web-portal/  # Next.js issuer/verifier portal
│   └── waltid-cli/         # Command-line interface
├── docker-compose/         # Docker deployment configs
└── build-logic/            # Gradle build plugins
```

## Build Commands

```bash
# Full build
./gradlew clean build

# Build specific module
./gradlew :waltid-services:waltid-issuer-api:build
./gradlew :waltid-libraries:credentials:waltid-w3c-credentials:build

# Build Docker images locally (requires Java 21)
./gradlew jibDockerBuild
```

## Test Commands

```bash
# Run all tests
./gradlew allTests

# JVM tests only
./gradlew jvmTest

# Tests for specific module
./gradlew :waltid-services:waltid-wallet-api:test

# Specific test class or method
./gradlew :module-path:test --tests "com.example.TestClass"
./gradlew :module-path:test --tests "com.example.TestClass.testMethod"

# Integration and E2E tests
./gradlew :waltid-services:waltid-integration-tests:test
./gradlew :waltid-services:waltid-e2e-tests:test
```

## Docker Compose

```bash
cd docker-compose

# Start all services (MUST use --profile flag)
docker compose --profile identity pull && docker compose --profile identity up

# Available profiles: services, apps, identity, valkey, tse, opa, all
docker compose --profile all up  # Everything including vault, opa, valkey

# Start specific service with dependencies
docker compose --profile identity up waltid-demo-wallet

# Build webapp images locally
docker compose --profile identity build
```

**Note:** The `--profile` flag is required. Setting `COMPOSE_PROFILES` in `.env` does not
automatically activate profiles - you must use `docker compose --profile <name>` explicitly.

### Building Local Service Images

The docker-compose.yaml uses `VERSION_TAG` from `.env` (default: `stable`) for service images.
Gradle's jib plugin builds to the `latest` tag. To use locally built images:

```bash
# Build a service image (from repo root)
./gradlew :waltid-services:waltid-issuer-api:jibDockerBuild

# Tag as stable (or whatever VERSION_TAG is set to in .env)
docker tag waltid/issuer-api:latest waltid/issuer-api:stable

# Restart the service
cd docker-compose
docker compose --profile identity up -d issuer-api
```

**Image Tags:**
- `latest` / `1.0.0-SNAPSHOT`: Built by `./gradlew jibDockerBuild`
- `stable`: Used by docker-compose (VERSION_TAG in .env)
- Always tag locally built images to match VERSION_TAG before restarting services

**Service Ports:**
- Wallet API: 7001
- Issuer API: 7002
- Verifier API: 7003
- Verifier API2: 7004
- Demo Wallet: 7101
- Web Portal: 7102

## Technology Stack

- **Language:** Kotlin 2.3.0 (multiplatform: JVM, JS, iOS)
- **Build:** Gradle with Kotlin DSL, Java 21 for services
- **Web Framework:** Ktor 3.3.3
- **Serialization:** Kotlinx Serialization
- **Testing:** JUnit 5, Mokkery for mocking
- **Crypto:** BouncyCastle, Nimbus JOSE, Google Tink

## Code Style

- Kotlin official code style (`kotlin.code.style=official`)
- Web apps use Prettier (check individual `.prettierrc` configs)

## Architecture Notes

**Layered Design:**
- Applications depend on Services
- Services depend on Libraries
- Libraries are multiplatform-first with platform-specific implementations where needed

**Key Abstractions:**
- `waltid-digital-credentials`: Unified credential format abstraction (W3C, SD-JWT, mdoc)
- `waltid-openid4vp`: Production OpenID4VP 1.0 implementation
- `waltid-openid4vc`: Draft protocol implementations (being deprecated)

**Verification:**
- `waltid-verifier-api2` is the modern verifier using OpenID4VP 1.0 + DCQL
- `waltid-verifier-api` is legacy (draft protocols)

## EUDI Wallet Compatibility

**IMPORTANT:** EUDI wallet support requires a **custom-built Docker image** - the standard Docker Hub images do NOT include these fixes.

### Building and Deploying the Custom Issuer

```bash
# 1. Build the custom issuer image (from repo root)
./gradlew :waltid-services:waltid-issuer-api:jibDockerBuild

# 2. Tag to match docker-compose VERSION_TAG
docker tag waltid/issuer-api:latest waltid/issuer-api:stable

# 3. Force recreate the container with the new image
cd docker-compose
docker compose up -d --force-recreate issuer-api
```

### Verified EUDI Credential Formats

| Credential | Config ID | Format | VCT/DocType |
|------------|-----------|--------|-------------|
| PID mDoc | `eu.europa.ec.eudi.pid.1` | `mso_mdoc` | `eu.europa.ec.eudi.pid.1` |
| mDL | `org.iso.18013.5.1.mDL` | `mso_mdoc` | `org.iso.18013.5.1.mDL` |
| PID SD-JWT | `eu.europa.ec.eudi.pid_vc_sd_jwt` | `dc+sd-jwt` | `urn:eudi:pid:1` |

### Key Requirements

- **Format:** Use `dc+sd-jwt` (NOT `vc+sd-jwt`) for SD-JWT credentials
- **Proofs:** Use JWT proofs (NOT CWT) for all EUDI credentials
- **VCT:** SD-JWT PID must use VCT `urn:eudi:pid:1`
- **Keys:** Use valid P-256 EC keys with correct curve coordinates

### Portal Format Selection

| Portal Option | API Format |
|---------------|------------|
| DC+SD-JWT (EUDI) | `dc+sd-jwt` |
| mDoc (ISO 18013-5) | `mso_mdoc` |

### EUDI Verification Configuration

The verifier services are configured with X.509 certificates for EUDI wallet compatibility.

**verifier-api2 (modern):**
- URL: `https://verifier2.theaustraliahack.com`
- Client ID: `x509_san_dns:verifier2.theaustraliahack.com`
- Certificate: `docker-compose/verifier-api2/keys/verifier2.theaustraliahack.com.cert.pem`

**verifier-api (legacy):**
- URL: `https://verifier.theaustraliahack.com`
- Client ID: `verifier.theaustraliahack.com`
- Certificate: `docker-compose/verifier-api/config/keys/verifier.theaustraliahack.com.cert.pem`

**Important:** The EUDI wallet must have the verifier certificates in its trust store. See [`docs/eudi/wallet-trust-store-update.md`](docs/eudi/wallet-trust-store-update.md) for configuration instructions.

**Testing:** For end-to-end verification testing instructions, see [`docs/eudi/verification-testing.md`](docs/eudi/verification-testing.md).

### Manual EUDI Wallet Testing

Quick commands to test all EUDI flows with QR codes:

```bash
# 1. Create issuance offers
# PID mDoc
curl -s -X POST "https://issuer.theaustraliahack.com/openid4vc/mdoc/issue" \
  -H "Content-Type: application/json" \
  -d '{"issuerKey":{"type":"jwk","jwk":{"kty":"EC","d":"mJJv_Hzv8--BHJaJlvB9KM8XQnM9M8J7KNZ8K_z9qdc","crv":"P-256","x":"dHGO-XVe1E-tEjqLN5EFT_FHQFgXTQ-9U7TL5qm9_0g","y":"L8L7_pV9t2qn7B8DJ1_N8pEyEL_WQ8wVBM_FqA7k5tw"}},"credentialConfigurationId":"eu.europa.ec.eudi.pid.1","credentialData":{"family_name":"DOE","given_name":"JOHN","birth_date":"1990-01-15","issuance_date":"2026-02-04","expiry_date":"2031-02-04","issuing_country":"AU","issuing_authority":"Test Authority"}}' \
  | tee /tmp/pid_mdoc.txt && qrencode -o /tmp/pid_mdoc.png "$(cat /tmp/pid_mdoc.txt)" && open /tmp/pid_mdoc.png

# PID SD-JWT
curl -s -X POST "https://issuer.theaustraliahack.com/openid4vc/sdjwt/issue" \
  -H "Content-Type: application/json" \
  -d '{"issuerKey":{"type":"jwk","jwk":{"kty":"EC","d":"mJJv_Hzv8--BHJaJlvB9KM8XQnM9M8J7KNZ8K_z9qdc","crv":"P-256","x":"dHGO-XVe1E-tEjqLN5EFT_FHQFgXTQ-9U7TL5qm9_0g","y":"L8L7_pV9t2qn7B8DJ1_N8pEyEL_WQ8wVBM_FqA7k5tw"}},"credentialConfigurationId":"urn:eudi:pid:1","credentialData":{"family_name":"DOE","given_name":"JOHN","birth_date":"1990-01-15","issuance_date":"2026-02-04","expiry_date":"2031-02-04","issuing_country":"AU","issuing_authority":"Test Authority"}}' \
  | tee /tmp/pid_sdjwt.txt && qrencode -o /tmp/pid_sdjwt.png "$(cat /tmp/pid_sdjwt.txt)" && open /tmp/pid_sdjwt.png

# mDL
curl -s -X POST "https://issuer.theaustraliahack.com/openid4vc/mdoc/issue" \
  -H "Content-Type: application/json" \
  -d '{"issuerKey":{"type":"jwk","jwk":{"kty":"EC","d":"mJJv_Hzv8--BHJaJlvB9KM8XQnM9M8J7KNZ8K_z9qdc","crv":"P-256","x":"dHGO-XVe1E-tEjqLN5EFT_FHQFgXTQ-9U7TL5qm9_0g","y":"L8L7_pV9t2qn7B8DJ1_N8pEyEL_WQ8wVBM_FqA7k5tw"}},"credentialConfigurationId":"org.iso.18013.5.1.mDL","credentialData":{"family_name":"DOE","given_name":"JOHN","birth_date":"1990-01-15","issue_date":"2023-01-01","expiry_date":"2033-01-01","issuing_country":"AU","issuing_authority":"Roads and Maritime Services","document_number":"DL123456","driving_privileges":[{"vehicle_category_code":"C","issue_date":"2023-01-01","expiry_date":"2033-01-01"}]}}' \
  | tee /tmp/mdl.txt && qrencode -o /tmp/mdl.png "$(cat /tmp/mdl.txt)" && open /tmp/mdl.png

# 2. Create verification sessions (requires verifier key/cert)
# PID mDoc verification
curl -s -X POST "https://verifier2.theaustraliahack.com/verification-session/create" \
  -H "Content-Type: application/json" \
  -d '{"flow_type":"cross_device","core_flow":{"signed_request":true,"clientId":"x509_san_dns:verifier2.theaustraliahack.com","key":{"type":"jwk","jwk":{"kty":"EC","crv":"P-256","x":"1Z2eGpdQVfWkAQQmNv8oT-lMwbhsFxWTZmhAYFHR5wY","y":"tvX699C21qGEMq7zqjpEhqy2kPT8KInnbxlLZzeSXdo","d":"j6-GyxLnrDSQGCljc678kmrihQFa0GR92JZXHDEQX38"}},"x5c":["MIIBnzCCAUagAwIBAgIUQSg5NhDlxwDFyAM7YJe++0QGyKIwCgYIKoZIzj0EAwIwKTEnMCUGA1UEAwwedmVyaWZpZXIyLnRoZWF1c3RyYWxpYWhhY2suY29tMB4XDTI2MDIwMzAzNTIwM1oXDTI3MDIwMzAzNTIwM1owKTEnMCUGA1UEAwwedmVyaWZpZXIyLnRoZWF1c3RyYWxpYWhhY2suY29tMFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAE1Z2eGpdQVfWkAQQmNv8oT+lMwbhsFxWTZmhAYFHR5wa29fr30LbWoYQyrvOqOkSGrLaQ9PwoiedvGUtnN5Jd2qNMMEowKQYDVR0RBCIwIIIedmVyaWZpZXIyLnRoZWF1c3RyYWxpYWhhY2suY29tMB0GA1UdDgQWBBRt0uKz8aKVlUxKF9j6vhAsGl3nHDAKBggqhkjOPQQDAgNHADBEAiAQ+AlF3Q4dput8QTizDyKo99R/sv3CC7BzqEjOxxsnzQIgF+rnBf0HghobWkjSVNwP8j/ekasfjp+1HDJclcNaUvs="],"dcql_query":{"credentials":[{"id":"eudi_pid_mdoc","format":"mso_mdoc","meta":{"doctype_value":"eu.europa.ec.eudi.pid.1"},"claims":[{"path":["eu.europa.ec.eudi.pid.1","family_name"]},{"path":["eu.europa.ec.eudi.pid.1","given_name"]},{"path":["eu.europa.ec.eudi.pid.1","birth_date"]}]}]}}}' \
  | jq -r '.bootstrapAuthorizationRequestUrl' | tee /tmp/verify_pid.txt && qrencode -o /tmp/verify_pid.png "$(cat /tmp/verify_pid.txt)" && open /tmp/verify_pid.png

# 3. Check verification session status
curl -s "https://verifier2.theaustraliahack.com/verification-session/{SESSION_ID}/info" | jq '{status, claims: .presentedPresentations}'
```

**Prerequisites:** `qrencode` (`brew install qrencode`)

**Full examples:** See [`docs/eudi/examples.md`](docs/eudi/examples.md)

## Platform-Specific Builds

```bash
# Enable Android (requires Android SDK in local.properties)
./gradlew build -PenableAndroidBuild=true

# Enable iOS (requires kdoctor setup)
./gradlew build -PenableIosBuild=true
```
