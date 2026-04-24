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

The `*.theaustraliahack.com` demo stack runs on a remote Windows Docker host. Deploys are **driven from the Mac**: gradle + compose build into Mac's local docker daemon; images ship over `docker save | docker load`; `docker compose up --force-recreate` runs on the Windows daemon via a sidecar container that uses path-alignment so bind mounts resolve correctly.

**Nothing is installed on the Windows host beyond Docker Desktop itself** — no Java, no Gradle, no Node, no GHA runner. The Mac is the build machine; the Windows host is the docker runtime.

### Deploy one service

```bash
# Dry-run first to see the plan:
./scripts/deploy.sh --dry-run rp-widget-demo

# Do it:
./scripts/deploy.sh rp-widget-demo
```

### Deploy everything

```bash
./scripts/deploy.sh all
```

### List what can be deployed

```bash
./scripts/deploy.sh --list
```

### Deploy to a different target (e.g. all-local dev)

```bash
# Build AND recreate on Mac's local daemon — no remote transfer:
DOCKER_REMOTE_CONTEXT=desktop-linux ./scripts/deploy.sh rp-widget-demo
```

### How it works under the hood

For each service the script:

1. **Builds locally.** JVM services use `./gradlew :<path>:jibDockerBuild` → Mac's docker daemon. Compose services use `docker compose build <svc>` against the Mac daemon. The local image is tagged `:stable` to match the compose file's `VERSION_TAG` default.
2. **Transfers the image** via `docker save | docker load` across the two contexts. Skipped when `DOCKER_LOCAL_CONTEXT == DOCKER_REMOTE_CONTEXT` (all-local dev mode).
3. **Recreates the container** on the remote. Because compose resolves bind-mount paths on the *client*, we can't just run `docker --context windows-docker-dev compose up` (Mac paths won't exist on Windows). Instead we run a transient `docker:cli` sidecar on the Windows daemon, bind-mounting the Windows repo at `/run/desktop/mnt/host/c/Users/sshuser/Projects/waltid-identity` — which is both the sidecar's working dir AND the path the Windows daemon sees. Compose inside the sidecar resolves everything to that path, and the daemon mounts real Windows files.
4. **Waits for container health** via `docker --context windows-docker-dev inspect` polling.
5. **Reports the image digest** on the remote so you can confirm which bits landed.

### Registry + path-filter helpers

`scripts/deploy-lib.sh` holds the service registry (service name → build kind, gradle path, image tag, container name) and the path-filter → service mapping used for "affected services" detection. Run its self-test any time you edit it:

```bash
bash -c 'source scripts/deploy-lib.sh && deploy_lib_self_test'
```

### Adding a new service

1. Add a row to `_SERVICE_REGISTRY` in `scripts/deploy-lib.sh` (tab-separated: name, kind, gradle-path, image, container, healthpath).
2. Add a path-filter entry to `_PATH_FILTERS` if files-to-service isn't already covered.
3. Run the self-test; re-run `./scripts/deploy.sh --list` to confirm it's visible.

### Still-manual operations (intentionally out of scope)

The `windows-docker-dev` SSH context stays for these:
- Caddy CA rotation + trust-store refresh in verifier-api2
- Adding a new Cloudflare tunnel hostname
- `docker logs` / `docker exec` during incident response
- Editing Windows-side `.env` secrets

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
