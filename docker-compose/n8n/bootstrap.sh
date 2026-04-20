#!/bin/sh
#
# n8n owner-seed bootstrap
#
# Idempotently UPDATEs the placeholder owner row that n8n's migrations
# create on first boot, so the setup wizard is bypassed and the instance
# comes up already owned by N8N_OWNER_EMAIL.
#
# See docs/plans/n8n-schema-observations.md for why this is an UPDATE
# (and not an INSERT) and why "user"/"roleSlug"/"firstName" etc. must
# be double-quoted.
#
# Env expected (set by compose in Task 4):
#   PGHOST, PGUSER, PGDATABASE, PGPASSWORD  -> consumed transparently by psql
#   N8N_OWNER_EMAIL
#   N8N_OWNER_PASSWORD
#   N8N_OWNER_FIRST_NAME
#   N8N_OWNER_LAST_NAME
#
# POSIX sh (runs in postgres:16-alpine / ash). No bashisms.

set -eu

log() {
    # stderr so it doesn't pollute any captured stdout
    printf '[n8n-bootstrap] %s\n' "$*" >&2
}

# Ensure htpasswd is available (used for the bcrypt hash below).
# postgres:16-alpine doesn't ship apache2-utils by default.
if ! command -v htpasswd >/dev/null 2>&1; then
    log "installing apache2-utils (for htpasswd)..."
    apk add --no-cache --quiet apache2-utils >/dev/null
fi

# ---------------------------------------------------------------------------
# 1. Wait until n8n's TypeORM migrations have created the "user" table.
# ---------------------------------------------------------------------------
log "waiting for n8n \"user\" table to exist..."
attempt=0
max_attempts=120   # ~10 minutes at 5s intervals
until psql -v ON_ERROR_STOP=1 -tAc \
        "SELECT to_regclass('public.\"user\"') IS NOT NULL" \
        2>/dev/null | grep -q '^t$'; do
    attempt=$((attempt + 1))
    if [ "$attempt" -ge "$max_attempts" ]; then
        log "ERROR: \"user\" table did not appear after $max_attempts attempts; giving up"
        exit 1
    fi
    sleep 5
done
log "\"user\" table is present"

# ---------------------------------------------------------------------------
# 2. Idempotency: if an owner already has a non-NULL email, we're done.
# ---------------------------------------------------------------------------
existing=$(psql -v ON_ERROR_STOP=1 -tAc \
    "SELECT COUNT(*) FROM \"user\" WHERE \"roleSlug\" = 'global:owner' AND email IS NOT NULL")

if [ "$existing" -gt 0 ]; then
    log "owner already configured -- skipping"
    exit 0
fi

# ---------------------------------------------------------------------------
# 3. Generate a bcrypt hash for the owner password.
#
# htpasswd -bnBC 10 emits a line of the form
#     :$2y$10$...hash...
# We strip the leading colon and swap the $2y prefix to $2a defensively --
# n8n's bcryptjs accepts $2a/$2b/$2y, but $2a is the most broadly compatible.
# ---------------------------------------------------------------------------
: "${N8N_OWNER_EMAIL:?N8N_OWNER_EMAIL is required}"
: "${N8N_OWNER_PASSWORD:?N8N_OWNER_PASSWORD is required}"
: "${N8N_OWNER_FIRST_NAME:?N8N_OWNER_FIRST_NAME is required}"
: "${N8N_OWNER_LAST_NAME:?N8N_OWNER_LAST_NAME is required}"

log "hashing owner password with bcrypt (cost=10)..."
# shellcheck disable=SC2016
# The single-quoted $2y / $2a in the sed expression are *literal* bcrypt
# prefixes, not shell variable references -- so we deliberately do NOT
# want parameter expansion here.
hash=$(htpasswd -bnBC 10 "" "$N8N_OWNER_PASSWORD" \
       | tr -d '\n' \
       | sed -e 's/^://' -e 's/^\$2y/\$2a/')

if [ -z "$hash" ]; then
    log "ERROR: bcrypt hash generation produced empty output"
    exit 1
fi

# ---------------------------------------------------------------------------
# 4. UPDATE the placeholder owner row.
#
# WHERE "roleSlug" = 'global:owner' AND email IS NULL is the idempotency
# guard: after the first successful run the row has a non-NULL email and
# this UPDATE no-ops. We already short-circuited above, so this is belt-
# and-braces.
#
# All user-supplied values go through psql -v parameter binding, NOT shell
# interpolation, so a hostile env var can't break out of the SQL literal.
# ---------------------------------------------------------------------------
log "seeding owner row for $N8N_OWNER_EMAIL ..."
psql -v ON_ERROR_STOP=1 \
     -v owner_email="$N8N_OWNER_EMAIL" \
     -v owner_first="$N8N_OWNER_FIRST_NAME" \
     -v owner_last="$N8N_OWNER_LAST_NAME" \
     -v owner_hash="$hash" <<'SQL'
UPDATE "user"
SET    email       = :'owner_email',
       "firstName" = :'owner_first',
       "lastName"  = :'owner_last',
       password    = :'owner_hash',
       "updatedAt" = CURRENT_TIMESTAMP(3)
WHERE  "roleSlug" = 'global:owner'
  AND  email IS NULL;
SQL

# ---------------------------------------------------------------------------
# 5. Flip userManagement.isInstanceOwnerSetUp from 'false' to 'true'.
#
# The settings row is pre-seeded by n8n migrations with value 'false' (a
# plain text boolean, NOT JSON). UPDATE is idempotent by construction.
# ---------------------------------------------------------------------------
log "flipping settings.userManagement.isInstanceOwnerSetUp to 'true'..."
psql -v ON_ERROR_STOP=1 <<'SQL'
UPDATE settings
SET    value = 'true'
WHERE  key   = 'userManagement.isInstanceOwnerSetUp';
SQL

log "success: owner seeded as $N8N_OWNER_EMAIL"
