# n8n Minimal Automated Install — Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Add n8n 2.x stable to the existing docker-compose stack, Postgres-backed, TLS-terminated via Caddy at `n8n.theaustraliahack.com`, with owner pre-seeded so the first-run UI wizard is skipped.

**Architecture:** One app container (`n8n`) plus two init containers (`n8n-db-init` to create the role/database on the shared Postgres, `n8n-bootstrap` to SQL-insert the owner once n8n has finished schema migration). All behind a new `n8n` docker-compose profile. Reuses the existing shared `postgres` service.

**Tech Stack:** Docker Compose, Caddy (reverse proxy, `tls internal`), PostgreSQL (shared), n8n `n8nio/n8n:2-stable`, `postgres:16-alpine` + `apache2-utils` for the init containers.

**Reference design:** `docs/plans/2026-04-20-n8n-minimal-install-design.md`

---

## Task 1: Schema discovery (throwaway sandbox)

The owner-seed SQL depends on the exact column names of `user` and `settings` tables in whichever 2.x minor we pull. Verify before writing the INSERT.

**Files:** none (throwaway)

**Step 1: Spin up a scratch n8n + postgres pair**

```bash
docker network create n8n-scratch
docker run -d --rm --name n8n-scratch-pg --network n8n-scratch \
  -e POSTGRES_USER=n8n -e POSTGRES_PASSWORD=scratch -e POSTGRES_DB=n8n postgres:16-alpine
sleep 3
docker run -d --rm --name n8n-scratch --network n8n-scratch \
  -e DB_TYPE=postgresdb -e DB_POSTGRESDB_HOST=n8n-scratch-pg \
  -e DB_POSTGRESDB_USER=n8n -e DB_POSTGRESDB_PASSWORD=scratch \
  -e DB_POSTGRESDB_DATABASE=n8n -e N8N_ENCRYPTION_KEY=scratch-key-scratch-key-scratch-key \
  n8nio/n8n:2-stable
# Wait for schema migration
until docker exec n8n-scratch wget -qO- http://localhost:5678/healthz 2>/dev/null | grep -q ok; do sleep 2; done
```

**Step 2: Inspect the user + settings tables**

```bash
docker exec n8n-scratch-pg psql -U n8n -d n8n -c '\d "user"'
docker exec n8n-scratch-pg psql -U n8n -d n8n -c '\d settings'
docker exec n8n-scratch-pg psql -U n8n -d n8n -c "SELECT * FROM settings;"
docker exec n8n-scratch-pg psql -U n8n -d n8n -c 'SELECT column_name, data_type FROM information_schema.columns WHERE table_name = '"'"'user'"'"' ORDER BY ordinal_position;'
```

**Step 3: Record observations**

Write observed column names, types, and the exact role format to `docs/plans/n8n-schema-observations.md` (create the file). Include:
- all columns on `"user"` (id type, role column shape — scalar string vs FK, nullable fields)
- the key used in `settings` for "instance owner is set up" (exact key + value shape)
- Node version + n8n version printed in `docker logs n8n-scratch | head`

**Step 4: Tear down sandbox**

```bash
docker stop n8n-scratch n8n-scratch-pg
docker network rm n8n-scratch
```

**Step 5: Commit observations**

```bash
git add docs/plans/n8n-schema-observations.md
git commit -m "docs(n8n): record n8n 2.x schema observations for bootstrap SQL"
```

---

## Task 2: Write the bootstrap script

**Files:**
- Create: `docker-compose/n8n/bootstrap.sh`

**Step 1: Write the script**

Based on the columns/keys recorded in Task 1. Template below — adjust column names and the `role` expression if observations differ.

```sh
#!/bin/sh
# n8n owner bootstrap — idempotent. Inserts global:owner row + flips
# settings.userManagement.isInstanceOwnerSetUp if the instance has no owner yet.
set -eu

apk add --no-cache apache2-utils >/dev/null

# Wait for n8n migrations to land the user table
until psql -tAc "SELECT 1 FROM information_schema.tables WHERE table_name='user'" | grep -q 1; do
  echo "waiting for n8n schema..."
  sleep 2
done

OWNER_EXISTS=$(psql -tAc "SELECT COUNT(*) FROM \"user\" WHERE role = 'global:owner'")
if [ "$OWNER_EXISTS" -gt 0 ]; then
  echo "owner already exists — skipping bootstrap"
  exit 0
fi

# bcrypt via htpasswd. n8n's bcryptjs accepts $2a/$2b. htpasswd emits $2y — rename prefix.
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
ON CONFLICT (key) DO UPDATE SET value = 'true';
SQL

echo "n8n owner bootstrapped: $N8N_OWNER_EMAIL"
```

**Step 2: Make it executable and shellcheck-clean**

```bash
chmod +x docker-compose/n8n/bootstrap.sh
docker run --rm -v "$PWD/docker-compose/n8n/bootstrap.sh:/s.sh" koalaman/shellcheck:stable /s.sh
```

Expected: no warnings (or only informational).

**Step 3: Commit**

```bash
git add docker-compose/n8n/bootstrap.sh
git commit -m "feat(n8n): bootstrap script for idempotent owner seed"
```

---

## Task 3: Write the database init script

Creates the `n8n` role and `n8n` database on the shared Postgres, idempotently. Works whether the Postgres volume is fresh or pre-existing.

**Files:**
- Create: `docker-compose/n8n/db-init.sh`

**Step 1: Write the script**

```sh
#!/bin/sh
# Create n8n role and database on the shared postgres if missing.
# Runs as POSTGRES superuser (PGUSER=$DB_USERNAME). Idempotent.
set -eu

PGPASSWORD="$POSTGRES_SUPERUSER_PASSWORD" psql -h "$PGHOST" -U "$POSTGRES_SUPERUSER" -d postgres \
  -v ON_ERROR_STOP=1 -v n8n_pw="$N8N_DB_PASSWORD" <<'SQL'
DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'n8n') THEN
    EXECUTE format('CREATE ROLE n8n LOGIN PASSWORD %L', :'n8n_pw');
  ELSE
    EXECUTE format('ALTER ROLE n8n WITH PASSWORD %L', :'n8n_pw');
  END IF;
END $$;

SELECT 'CREATE DATABASE n8n OWNER n8n'
WHERE NOT EXISTS (SELECT 1 FROM pg_database WHERE datname = 'n8n')\gexec
SQL

echo "n8n database + role ensured"
```

**Step 2: Make it executable + shellcheck**

```bash
chmod +x docker-compose/n8n/db-init.sh
docker run --rm -v "$PWD/docker-compose/n8n/db-init.sh:/s.sh" koalaman/shellcheck:stable /s.sh
```

**Step 3: Commit**

```bash
git add docker-compose/n8n/db-init.sh
git commit -m "feat(n8n): db-init script for idempotent role + database"
```

---

## Task 4: Add services to docker-compose.yaml

**Files:**
- Modify: `docker-compose/docker-compose.yaml` (add three services near existing `postgres` block around line 220, add volume to end volumes block near line 564)

**Step 1: Add the three services**

Insert after the `postgres` service definition (which ends at line 235 — after the `ports:` entry for postgres). Paste as a contiguous block:

```yaml
  n8n-db-init:
    image: postgres:16-alpine
    profiles:
      - n8n
      - all
    depends_on:
      postgres:
        condition: service_healthy
    environment:
      PGHOST: postgres
      POSTGRES_SUPERUSER: ${DB_USERNAME}
      POSTGRES_SUPERUSER_PASSWORD: ${DB_PASSWORD}
      N8N_DB_PASSWORD: ${N8N_DB_PASSWORD}
    volumes:
      - ./n8n/db-init.sh:/db-init.sh:ro
    entrypoint: ["/bin/sh", "/db-init.sh"]
    restart: "no"

  n8n:
    image: n8nio/n8n:2-stable
    profiles:
      - n8n
      - all
    restart: unless-stopped
    depends_on:
      postgres:
        condition: service_healthy
      n8n-db-init:
        condition: service_completed_successfully
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
    profiles:
      - n8n
      - all
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
```

**Step 2: Add the `n8n_data` volume**

At the bottom `volumes:` block (currently lines 564–567), append one line:

```yaml
  n8n_data:
```

**Step 3: Add the n8n upstream to the caddy `depends_on`**

Find the `caddy:` service (line 288). If its `depends_on:` lists other services, add:

```yaml
      n8n:
        condition: service_started
```

(Use `service_started`, not `service_healthy` — we don't want Caddy to block on n8n being reachable, same pattern as other services.)

**Step 4: Validate compose syntax**

```bash
cd docker-compose && docker compose --profile n8n config > /dev/null
```

Expected: no output, exit 0. Any YAML or env error surfaces here.

**Step 5: Commit**

```bash
git add docker-compose/docker-compose.yaml
git commit -m "feat(n8n): add n8n + db-init + bootstrap services to compose"
```

---

## Task 5: Add Caddy site block

**Files:**
- Modify: `docker-compose/Caddyfile` (append at end, around line 170)

**Step 1: Append the block**

```caddy
# n8n — workflow automation, internal HTTPS
n8n.theaustraliahack.com:443 {
    tls internal
    reverse_proxy http://n8n:5678
}
```

(Caddy handles WebSocket upgrades transparently; n8n's editor works without extra directives.)

**Step 2: Validate Caddyfile**

```bash
docker run --rm -v "$PWD/docker-compose/Caddyfile:/etc/caddy/Caddyfile" \
  caddy:latest caddy validate --config /etc/caddy/Caddyfile
```

Expected: `Valid configuration`.

**Step 3: Commit**

```bash
git add docker-compose/Caddyfile
git commit -m "feat(n8n): Caddy site block for n8n.theaustraliahack.com"
```

---

## Task 6: Update .env.example

**Files:**
- Modify: `docker-compose/.env.local.example` (append at end)

**Step 1: Append placeholder keys**

```bash
cat >> docker-compose/.env.local.example <<'EOF'

# --- n8n ---
# Generate: openssl rand -hex 24
N8N_DB_PASSWORD=replace-me
# Generate: openssl rand -hex 32. NEVER rotate after first boot (invalidates credentials).
N8N_ENCRYPTION_KEY=replace-me-32-hex-chars
N8N_OWNER_EMAIL=admin@example.com
# Generate: openssl rand -base64 24
N8N_OWNER_PASSWORD=replace-me
N8N_OWNER_FIRST_NAME=Admin
N8N_OWNER_LAST_NAME=User
N8N_TIMEZONE=UTC
EOF
```

**Step 2: Commit**

```bash
git add docker-compose/.env.local.example
git commit -m "docs(n8n): document required env vars in .env.local.example"
```

---

## Task 7: Populate real secrets in local .env

**Files:**
- Modify: `docker-compose/.env` (local — NOT committed; `.env` is gitignored in this repo — verify)

**Step 1: Confirm `.env` is gitignored**

```bash
git check-ignore docker-compose/.env && echo "IGNORED (safe)" || echo "NOT IGNORED — STOP"
```

If `NOT IGNORED`, stop and fix the ignore rule before writing secrets.

**Step 2: Append generated secrets**

```bash
{
  echo ""
  echo "# --- n8n ---"
  echo "N8N_DB_PASSWORD=$(openssl rand -hex 24)"
  echo "N8N_ENCRYPTION_KEY=$(openssl rand -hex 32)"
  echo "N8N_OWNER_EMAIL=adam_j_bradley@yahoo.com"
  echo "N8N_OWNER_PASSWORD=$(openssl rand -base64 24 | tr -d '=+/' | cut -c1-24)"
  echo "N8N_OWNER_FIRST_NAME=Adam"
  echo "N8N_OWNER_LAST_NAME=Bradley"
  echo "N8N_TIMEZONE=Australia/Sydney"
} >> docker-compose/.env
```

**Step 3: Record the owner password somewhere retrievable**

Show the user the generated `N8N_OWNER_PASSWORD` and confirm they have it stored (they will need it to log in).

```bash
grep "^N8N_OWNER_PASSWORD=" docker-compose/.env
```

**Step 4: No commit** (`.env` is gitignored).

---

## Task 8: Bring the stack up — end-to-end verification

**Step 1: Pull + start**

```bash
cd docker-compose
docker compose --profile n8n pull
docker compose --profile n8n up -d
```

**Step 2: Watch the dependency chain complete**

```bash
docker compose logs -f n8n-db-init n8n n8n-bootstrap
```

Expected sequence:
1. `n8n-db-init` → `n8n database + role ensured` → exits 0
2. `n8n` → logs `Editor is now accessible via: http://localhost:5678`
3. `n8n-bootstrap` → `n8n owner bootstrapped: adam_j_bradley@yahoo.com` → exits 0

Ctrl-C out of the tail.

**Step 3: Health check**

```bash
docker compose ps n8n
# STATUS should be "Up (healthy)"

curl -sk https://n8n.theaustraliahack.com/healthz
# expect: {"status":"ok"}
```

**Step 4: Log in via browser**

Visit `https://n8n.theaustraliahack.com/`. Log in with `N8N_OWNER_EMAIL` + `N8N_OWNER_PASSWORD`. Expected: land directly in the editor, **no setup wizard**.

If the setup wizard appears → bootstrap SQL did not match schema. Return to Task 1, re-observe, fix Task 2.

**Step 5: Commit a note on the successful verification**

(No files changed, but if any schema-based tweaks were needed in Task 2, re-commit those here.)

---

## Task 9: Verify idempotency

**Step 1: Re-run `up`**

```bash
docker compose --profile n8n up -d
docker compose logs n8n-bootstrap --tail 20
```

Expected in the bootstrap log: `owner already exists — skipping bootstrap`. No duplicate user row, no error.

**Step 2: Verify no duplicate owner row**

```bash
docker compose exec postgres psql -U "$DB_USERNAME" -d n8n -c \
  "SELECT COUNT(*) FROM \"user\" WHERE role='global:owner';"
```

Expected: `1`.

---

## Task 10: Update project docs

**Files:**
- Modify: `CLAUDE.md` (Service Ports table gets n8n, Feature Flags if applicable)
- Modify: `docs/claude-reference.md` (brief section on n8n integration)

**Step 1: Add n8n port to CLAUDE.md Service Ports table**

Insert under the existing Service Ports line. n8n only speaks via Caddy, so the port exposed is 443 via the hostname — note it as such:

```md
**Service Ports:** Wallet API: 7001, Issuer API: 7002, Verifier API: 7003, Verifier API2: 7004, Demo Wallet: 7101, Web Portal: 7102, n8n: https://n8n.theaustraliahack.com (via Caddy)
```

**Step 2: Add a small section to `docs/claude-reference.md`**

One ~15-line section titled `## n8n` covering: profile name (`n8n`), URL, owner bootstrap mechanism, encryption-key warning, where to find the bootstrap script.

**Step 3: Commit**

```bash
git add CLAUDE.md docs/claude-reference.md
git commit -m "docs(n8n): document n8n integration in project reference"
```

---

## Task 11: Open PR

**Step 1: Push**

```bash
git push -u origin feature/n8n-install
```

**Step 2: Open PR against this fork**

```bash
gh pr create --repo adamjbradley/waltid-identity \
  --title "feat(n8n): minimal automated n8n install in docker-compose" \
  --body "$(cat <<'EOF'
## Summary
- Adds n8n 2.x stable to the docker-compose stack under a new `n8n` profile
- Reuses the shared postgres (n8n-db-init creates role + database idempotently)
- Owner pre-seeded via init container — first login lands directly in editor, no setup wizard
- Exposed via Caddy at https://n8n.theaustraliahack.com

## Test plan
- [ ] `docker compose --profile n8n up -d` completes with all three init containers succeeding
- [ ] https://n8n.theaustraliahack.com/healthz returns ok
- [ ] Owner can log in and lands in editor (no wizard)
- [ ] Second `up -d` logs "owner already exists — skipping bootstrap"

🤖 Generated with [Claude Code](https://claude.com/claude-code)
EOF
)"
```

---

## Rollback (if anything above breaks mid-way)

```bash
cd docker-compose
docker compose --profile n8n down
docker volume rm docker-compose_n8n_data 2>/dev/null || true
docker compose exec postgres psql -U "$DB_USERNAME" -d postgres -c 'DROP DATABASE IF EXISTS n8n; DROP ROLE IF EXISTS n8n;'
```

Plus `git reset` the commits on this branch.
