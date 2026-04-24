# CLAUDE.md

## Git Workflow

**IMPORTANT:** This is a fork of `walt-id/waltid-identity`. When creating pull requests:
- Create PRs on **this fork** (`adamjbradley/waltid-identity`), NOT the upstream `walt-id/waltid-identity`
- Use `gh pr create --repo adamjbradley/waltid-identity` to ensure correct target

## Project Overview

walt.id Identity is an open-source digital identity and wallet platform for credential issuance, verification, and wallet management. Supports W3C VCs, SD-JWT, and ISO mdoc via OpenID4VC/VP.

## Repository Structure

```
waltid-libraries/          # Core multiplatform Kotlin libraries (auth, crypto, credentials, protocols, sdjwt, did)
waltid-services/           # REST APIs (issuer-api, verifier-api, verifier-api2, wallet-api)
waltid-applications/       # End-user apps (web-wallet: Vue/Nuxt, web-portal: Next.js, cli)
docker-compose/            # Docker deployment configs
build-logic/               # Gradle build plugins
```

## Build & Test

```bash
./gradlew clean build                                          # Full build
./gradlew :waltid-services:waltid-issuer-api:build             # Specific module
./gradlew jibDockerBuild                                       # Docker images (Java 21)
./gradlew allTests                                             # All tests
./gradlew :module-path:test --tests "com.example.TestClass"    # Specific test
cd waltid-applications/waltid-web-portal && npx jest --no-coverage  # Portal tests
```

## Docker Compose

```bash
cd docker-compose
docker compose --profile identity pull && docker compose --profile identity up
# Profiles: services, apps, identity, valkey, tse, opa, all
```

`--profile` flag is required. `VERSION_TAG` in `.env` defaults to `stable`. Tag local builds: `docker tag waltid/issuer-api:latest waltid/issuer-api:stable`

**Service Ports:** Wallet API: 7001, Issuer API: 7002, Verifier API: 7003, Verifier API2: 7004, Demo Wallet: 7101, Web Portal: 7102, n8n: https://n8n.theaustraliahack.com (via Caddy)

### Rebuilding Individual Images

```bash
# Portal (Next.js) — build from REPO ROOT, image name is waltid/portal
docker build -f waltid-applications/waltid-web-portal/Dockerfile -t waltid/portal:stable .

# Kotlin services (via Gradle jib) — run from repo root
./gradlew :waltid-services:waltid-issuer-api:jibDockerBuild
docker tag waltid/issuer-api:latest waltid/issuer-api:stable

# Then restart the service
cd docker-compose && docker compose --profile identity up -d --force-recreate <service-name>
```

## Deployment (live demo stack on the Windows host)

The `*.theaustraliahack.com` demo stack runs on a Windows Docker host. Deploys go through a GitHub Actions self-hosted runner living on that host — no SSH, no manual build-and-recreate dance.

**Auto-deploy on merge to main.** Any PR that touches a deployable path (`examples/**`, `waltid-services/**`, `waltid-applications/**`, `waltid-libraries/**`, `docker-compose/**`) triggers `.github/workflows/deploy-demo-stack.yml`. The workflow's `detect` job diffs the commit range, maps changed paths to services via `scripts/deploy-lib.sh`, and fans out to a matrix. Docs-only PRs never fire.

**Manual deploy (during Claude sessions, before a PR merges):**

```bash
# Deploy one service:
gh workflow run deploy-demo-stack.yml -f service=rp-widget-demo

# Deploy everything:
gh workflow run deploy-demo-stack.yml -f service=all

# Tail the run:
gh run watch
```

`gh workflow run` accepts the input enum defined in the workflow file — keep it in sync with `_SERVICE_REGISTRY` in `scripts/deploy-lib.sh`. The `deploy-lib.sh` self-test verifies core services exist in the registry; run it locally with:

```bash
bash -c 'source scripts/deploy-lib.sh && deploy_lib_self_test'
```

**When a deploy fails**, the GHA run log shows the failing step (preflight / gradle / compose / health-wait). The previous container keeps running until the `docker compose up -d --force-recreate` succeeds, so a failed build doesn't take down the demo. To roll back: find the last-good workflow run in the GHA UI and "Re-run all jobs" — it redeploys that run's commit SHA.

**Runner install** is a one-time Phase 1 step on the Windows host (see `docs/plans/` or the runner registration page under the repo's GitHub Settings → Actions → Runners). Runner labels: `[self-hosted, windows, waltid-demo]`. Requires Java 21 on `JAVA_HOME` and Docker Desktop running.

**What's NOT managed by the runner** (still manual, still uses the `windows-docker-dev` SSH context):
- Caddy CA rotation + trust-store refresh in verifier-api2
- Adding a new Cloudflare tunnel hostname
- Direct container introspection / logs (`docker logs`, `docker exec`) during incident response

### Internal HTTPS (Caddy)

Caddy provides internal TLS for `issuer.theaustraliahack.com:443` so verifier-api2 can reach status list URLs embedded in credentials. The caddy root CA cert (`caddy-root-ca.crt`) is imported into the verifier-api2 JVM truststore at startup. If Caddy regenerates its CA, re-extract:

```bash
docker compose cp caddy:/data/caddy/pki/authorities/local/root.crt ./caddy-root-ca.crt
```

## Technology Stack

Kotlin 2.3.0 (multiplatform), Gradle/Kotlin DSL, Java 21, Ktor 3.3.3, Kotlinx Serialization, JUnit 5/Mokkery, BouncyCastle/Nimbus JOSE/Tink

## Architecture

- Applications → Services → Libraries (layered)
- `verifier-api2` = modern (OID4VP 1.0 + DCQL); `verifier-api` = legacy
- EUDI wallets require signed JAR requests via `verifier-api2`

## Feature Flags

All in `docker-compose/.env`, default `false`:

| Flag | Controls |
|------|----------|
| `TRUST_LISTS_ENABLED` | ETSI trust list validation |
| `OPENID_FEDERATION_ENABLED` | OpenID Federation trust source |
| `ISSUER_REGISTRAR_ENABLED` | Multi-tenant issuer onboarding |
| `RP_REGISTRAR_ENABLED` | Relying party onboarding |
| `MT_WALLET_ENABLED` | MT identity in wallet |
| `PWA_ENABLED` | Payment Wallet Attestation |

## Feature Details

For EUDI wallet compatibility, trust lists, registrars, PWA, and other feature-specific docs, read [`docs/claude-reference.md`](docs/claude-reference.md) on-demand.
