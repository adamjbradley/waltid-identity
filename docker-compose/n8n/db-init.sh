#!/bin/sh
#
# n8n DB + role init
#
# Idempotently creates the "n8n" Postgres role and "n8n" database on the
# shared postgres service, before the n8n service starts. Re-running is
# safe: if the role already exists its password is ALTERed to match
# N8N_DB_PASSWORD (handles rotation); if the database already exists it
# is left as-is.
#
# Runs in postgres:16-alpine (BusyBox ash). POSIX sh only, no bashisms.
#
# Env expected (set by compose in Task 4):
#   PGHOST                      (e.g. "postgres")
#   POSTGRES_SUPERUSER          superuser role, from DB_USERNAME
#   POSTGRES_SUPERUSER_PASSWORD superuser password, from DB_PASSWORD
#   N8N_DB_PASSWORD             password to set on the "n8n" role
#

set -eu

log() {
    # stderr, so if this ever gets piped into something we don't corrupt stdout
    printf '[n8n-db-init] %s\n' "$*" >&2
}

: "${PGHOST:?PGHOST is required}"
: "${POSTGRES_SUPERUSER:?POSTGRES_SUPERUSER is required}"
: "${POSTGRES_SUPERUSER_PASSWORD:?POSTGRES_SUPERUSER_PASSWORD is required}"
: "${N8N_DB_PASSWORD:?N8N_DB_PASSWORD is required}"

# psql picks this up transparently — keep it out of argv so it doesn't leak
# into `ps` output.
PGPASSWORD="$POSTGRES_SUPERUSER_PASSWORD"
export PGPASSWORD

log "waiting for postgres at $PGHOST to accept connections..."
attempt=0
until pg_isready -h "$PGHOST" -U "$POSTGRES_SUPERUSER" >/dev/null 2>&1; do
    attempt=$((attempt + 1))
    if [ "$attempt" -ge 60 ]; then
        log "ERROR: postgres not ready after ~2 minutes; giving up"
        exit 1
    fi
    sleep 2
done
log "postgres is accepting connections"

# -----------------------------------------------------------------------------
# Role: CREATE if missing, ALTER password otherwise.
#
# We use the psql \gexec pattern rather than a DO block so that psql's
# :'n8n_pw' variable interpolation happens on the client side in plain
# SQL text (well-defined). Interpolation inside a DO $$...$$ body is
# technically supported but subtle, so we avoid it.
#
# quote_literal() protects the password from SQL injection on the server
# side, and :'n8n_pw' already adds a layer of single-quote escaping on
# the client side.
# -----------------------------------------------------------------------------
log "ensuring 'n8n' role..."
psql -h "$PGHOST" \
     -U "$POSTGRES_SUPERUSER" \
     -d postgres \
     -v ON_ERROR_STOP=1 \
     -v n8n_pw="$N8N_DB_PASSWORD" \
     --no-psqlrc \
     --quiet <<'SQL'
SELECT CASE
           WHEN EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'n8n')
               THEN 'ALTER ROLE n8n WITH LOGIN PASSWORD ' || quote_literal(:'n8n_pw')
           ELSE 'CREATE ROLE n8n WITH LOGIN PASSWORD ' || quote_literal(:'n8n_pw')
       END AS cmd
\gexec
SQL

# -----------------------------------------------------------------------------
# Database: CREATE if missing.
#
# CREATE DATABASE cannot run inside a transaction or a DO block, so
# \gexec is the idiomatic idempotent pattern.
# -----------------------------------------------------------------------------
log "ensuring 'n8n' database..."
psql -h "$PGHOST" \
     -U "$POSTGRES_SUPERUSER" \
     -d postgres \
     -v ON_ERROR_STOP=1 \
     --no-psqlrc \
     --quiet <<'SQL'
SELECT 'CREATE DATABASE n8n OWNER n8n'
WHERE NOT EXISTS (SELECT 1 FROM pg_database WHERE datname = 'n8n')
\gexec
SQL

log "success: n8n role + database are ready"
