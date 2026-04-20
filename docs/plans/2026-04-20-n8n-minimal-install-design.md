# n8n Minimal Automated Install — Design

**Date:** 2026-04-20
**Status:** Approved for implementation
**Branch:** `feature/n8n-install`

## Goal

Add n8n 2.x stable to the existing `docker-compose` stack as a production-minimal, automated install: TLS-terminated, Postgres-backed, owner pre-seeded, no manual UI setup wizard.

## Architecture

Add n8n as a new service in `docker-compose/docker-compose.yaml`, following conventions already used by `wallet-api` and friends.

- **Image:** `n8nio/n8n:2-stable`
- **Profile:** new `n8n` profile; also included in `all`
- **Postgres:** reuse the existing `postgres` service; create a dedicated `n8n` database and `n8n` role on first init
- **Reverse proxy:** Caddy serves `n8n.theaustraliahack.com` → `n8n:5678` with automatic TLS, matching the issuer/verifier pattern
- **Persistence:** named volume `n8n_data` for `/home/node/.n8n` (config/encryption material; workflow data lives in Postgres)
- **Bootstrap:** one-shot `n8n-bootstrap` init container that runs after n8n is healthy, SQL-inserts the owner row, and sets `settings.userManagement.isInstanceOwnerSetUp=true`. Idempotent.
- **Secrets:** `N8N_ENCRYPTION_KEY`, owner email/password, and Postgres password come from `docker-compose/.env`

Service startup chain: `postgres` (healthy) → `n8n` (migrates schema, becomes healthy) → `n8n-bootstrap` (seeds owner) → done.

## Service definitions (`docker-compose.yaml`)

```yaml
n8n:
  image: n8nio/n8n:2-stable
  profiles: [n8n, all]
  restart: unless-stopped
  depends_on:
    postgres:
      condition: service_healthy
  environment:
    DB_TYPE: postgresdb
    DB_POSTGRESDB_HOST: postgres
    DB_POSTGRESDB_PORT: 5432
    DB_POSTGRESDB_DATABASE: n8n
    DB_POSTGRESDB_USER: n8n
    DB_POSTGRESDB_PASSWORD: ${N8N_DB_PASSWORD}
    N8N_ENCRYPTION_KEY: ${N8N_ENCRYPTION_KEY}
    N8N_HOST: n8n.theaustraliahack.com
    N8N_PROTOCOL: https
    WEBHOOK_URL: https://n8n.theaustraliahack.com/
    N8N_EDITOR_BASE_URL: https://n8n.theaustraliahack.com/
    N8N_PROXY_HOPS: "1"
    GENERIC_TIMEZONE: ${N8N_TIMEZONE:-UTC}
  volumes:
    - n8n_data:/home/node/.n8n
  healthcheck:
    test: ["CMD-SHELL", "wget -qO- http://localhost:5678/healthz || exit 1"]
    interval: 10s
    timeout: 5s
    retries: 10

n8n-bootstrap:
  image: postgres:16-alpine
  profiles: [n8n, all]
  depends_on:
    n8n:
      condition: service_healthy
  environment:
    PGHOST: postgres
    PGUSER: n8n
    PGDATABASE: n8n
    PGPASSWORD: ${N8N_DB_PASSWORD}
    N8N_OWNER_EMAIL: ${N8N_OWNER_EMAIL}
    N8N_OWNER_PASSWORD: ${N8N_OWNER_PASSWORD}
    N8N_OWNER_FIRST_NAME: ${N8N_OWNER_FIRST_NAME:-Admin}
    N8N_OWNER_LAST_NAME: ${N8N_OWNER_LAST_NAME:-User}
  volumes:
    - ./n8n/bootstrap.sh:/bootstrap.sh:ro
  entrypoint: ["/bin/sh", "/bootstrap.sh"]
  restart: "no"

volumes:
  n8n_data:
```

## Bootstrap script (`docker-compose/n8n/bootstrap.sh`)

```sh
#!/bin/sh
set -eu

apk add --no-cache apache2-utils >/dev/null

# Wait for n8n schema migration to finish
until psql -tAc "SELECT 1 FROM information_schema.tables WHERE table_name='user'" | grep -q 1; do
  echo "waiting for n8n schema..."
  sleep 2
done

OWNER_EXISTS=$(psql -tAc "SELECT COUNT(*) FROM \"user\" WHERE role='global:owner'")
if [ "$OWNER_EXISTS" -gt 0 ]; then
  echo "owner already exists — skipping bootstrap"
  exit 0
fi

# bcrypt hash — n8n (bcryptjs) accepts $2a/$2b; htpasswd emits $2y, rename prefix
HASH=$(htpasswd -bnBC 10 "" "$N8N_OWNER_PASSWORD" | tr -d ':\n' | sed 's/^\$2y/\$2a/')

psql -v ON_ERROR_STOP=1 \
     -v email="$N8N_OWNER_EMAIL" \
     -v fn="$N8N_OWNER_FIRST_NAME" \
     -v ln="$N8N_OWNER_LAST_NAME" \
     -v hash="$HASH" <<'SQL'
INSERT INTO "user" (id, email, "firstName", "lastName", password, role, "createdAt", "updatedAt")
VALUES (gen_random_uuid(), :'email', :'fn', :'ln', :'hash', 'global:owner', NOW(), NOW());

INSERT INTO settings (key, value, "loadOnStartup")
VALUES ('userManagement.isInstanceOwnerSetUp', 'true', true)
ON CONFLICT (key) DO UPDATE SET value='true';
SQL

echo "n8n owner bootstrapped: $N8N_OWNER_EMAIL"
```

Uses `psql -v` parameter binding to avoid SQL injection via owner env vars.

## Caddy (`docker-compose/Caddyfile`)

Append:

```caddy
n8n.theaustraliahack.com {
    reverse_proxy n8n:5678 {
        header_up Host {host}
    }
}
```

Caddy handles WebSockets natively — n8n's editor UI works without extra config. DNS for `n8n.theaustraliahack.com` assumed to follow the same pattern as `issuer.*` / `verifier.*`.

## Postgres init (`docker-compose/postgres-init/10-n8n.sql`)

```sql
\set n8n_pw `echo "$N8N_DB_PASSWORD"`
CREATE USER n8n WITH PASSWORD :'n8n_pw';
CREATE DATABASE n8n OWNER n8n;
```

Runs on first Postgres volume init only. If the existing `postgres` service does not already mount `/docker-entrypoint-initdb.d`, add the volume mount as part of the edit.

## `.env` additions

```bash
# --- n8n ---
N8N_DB_PASSWORD=<openssl rand -hex 24>
N8N_ENCRYPTION_KEY=<openssl rand -hex 32>   # NEVER rotate after first boot
N8N_OWNER_EMAIL=adam_j_bradley@yahoo.com
N8N_OWNER_PASSWORD=<openssl rand -base64 24>
N8N_OWNER_FIRST_NAME=Adam
N8N_OWNER_LAST_NAME=Bradley
N8N_TIMEZONE=Australia/Sydney
```

Mirror the same keys (with placeholder values) in `.env.example`.

## Operator workflow

```bash
cd docker-compose

# Generate secrets (append or edit .env)
echo "N8N_DB_PASSWORD=$(openssl rand -hex 24)"        >> .env
echo "N8N_ENCRYPTION_KEY=$(openssl rand -hex 32)"     >> .env
echo "N8N_OWNER_PASSWORD=$(openssl rand -base64 24)"  >> .env
# Add N8N_OWNER_EMAIL / FIRST_NAME / LAST_NAME / TIMEZONE manually

docker compose --profile n8n pull
docker compose --profile n8n up -d
```

## Verification

1. `docker compose logs n8n-bootstrap` → `n8n owner bootstrapped: <email>`
2. `curl -sf https://n8n.theaustraliahack.com/healthz` → `200`
3. Browser → `https://n8n.theaustraliahack.com/` → login with owner credentials lands directly in editor (no setup wizard)
4. Re-run `docker compose --profile n8n up -d` → bootstrap logs `owner already exists — skipping bootstrap`

## Rollback

```bash
docker compose --profile n8n down
docker volume rm docker-compose_n8n_data
# And drop the n8n db + role from the shared postgres if a clean reset is wanted
```

Fully reversible.

## Risks and accepted trade-offs

- **Schema coupling (option 2 trade-off).** The bootstrap SQL targets n8n 2.x's `user` / `settings` tables and `global:owner` role. A future n8n schema change will fail the bootstrap container loudly on `up` — visible failure, not silent corruption. Fix path: update column names in `bootstrap.sh`.
- **Encryption key lifecycle.** Rotating `N8N_ENCRYPTION_KEY` after first boot invalidates every stored credential in n8n. The `.env` comment captures this.
- **Secrets in `.env`.** Owner password and DB password live in `.env` plaintext, consistent with the rest of the stack.
- **Bootstrap container image.** `postgres:16-alpine` plus `apache2-utils` (~10 MB extra). Runs once per `up`.

## Files

**New:**
- `docker-compose/n8n/bootstrap.sh`
- `docker-compose/postgres-init/10-n8n.sql`

**Modified:**
- `docker-compose/docker-compose.yaml` (add `n8n` service, `n8n-bootstrap` service, `n8n_data` volume; possibly add `postgres-init` volume mount to `postgres`)
- `docker-compose/Caddyfile` (add n8n site block)
- `docker-compose/.env` (add N8N_* keys)
- `docker-compose/.env.example` (mirror keys with placeholders)
