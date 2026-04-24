#!/usr/bin/env bash
# scripts/deploy.sh — idempotent per-service deploy for the waltid demo stack.
#
# Designed to run on the GHA self-hosted runner installed on the Windows host
# where the docker-compose stack lives. Invoked with a single canonical service
# name (or the literal word `all` for a full reconcile). Every run is "bring
# this service to match the current git working tree" — if everything already
# matches, the run is a no-op aside from health-check I/O.
#
# Usage:
#   scripts/deploy.sh <service>
#   scripts/deploy.sh all
#   scripts/deploy.sh --list
#   scripts/deploy.sh --dry-run <service>
#
# Environment:
#   COMPOSE_DIR   — defaults to ./docker-compose
#   COMPOSE_PROFILE — defaults to "all" (what the live demo uses)
#   WAIT_TIMEOUT  — seconds to wait for container health (default 90)
#   GRADLE_EXTRA  — extra args passed to gradlew (e.g. "--no-daemon")

set -euo pipefail

SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
REPO_ROOT=$(cd "$SCRIPT_DIR/.." && pwd)

# shellcheck source-path=SCRIPTDIR
# shellcheck source=deploy-lib.sh
source "$SCRIPT_DIR/deploy-lib.sh"

COMPOSE_DIR=${COMPOSE_DIR:-"$REPO_ROOT/docker-compose"}
COMPOSE_PROFILE=${COMPOSE_PROFILE:-all}
WAIT_TIMEOUT=${WAIT_TIMEOUT:-90}
GRADLE_EXTRA=${GRADLE_EXTRA:-}

DRY_RUN=false
if [ "${1:-}" = "--dry-run" ]; then
    DRY_RUN=true
    shift
fi

if [ "${1:-}" = "--list" ]; then
    svc_list
    exit 0
fi

SERVICE=${1:-}
if [ -z "$SERVICE" ]; then
    echo "usage: $0 [--dry-run|--list] <service|all>" >&2
    echo "known services:" >&2
    svc_list | sed 's/^/  /' >&2
    exit 64
fi

# ---------------------------------------------------------------------
# Logging
# ---------------------------------------------------------------------
log()  { printf '[deploy] %s\n' "$*"; }
warn() { printf '[deploy] WARN: %s\n' "$*" >&2; }
die()  { printf '[deploy] ERROR: %s\n' "$*" >&2; exit 1; }
run()  {
    if $DRY_RUN; then
        printf '[deploy] DRY-RUN  %s\n' "$*"
    else
        log "+ $*"
        "$@"
    fi
}

# ---------------------------------------------------------------------
# Pre-flight
# ---------------------------------------------------------------------
preflight() {
    command -v docker >/dev/null || die "docker not on PATH"
    command -v git >/dev/null    || die "git not on PATH"
    [ -d "$COMPOSE_DIR" ]        || die "compose dir not found: $COMPOSE_DIR"
    [ -f "$COMPOSE_DIR/docker-compose.yaml" ] || die "docker-compose.yaml not found in $COMPOSE_DIR"
    # A dirty git tree isn't fatal — runner checkouts are clean, local dry-runs
    # may be dirty — but note it loudly so post-mortem is easier.
    local dirty
    dirty=$(cd "$REPO_ROOT" && git status --porcelain | wc -l | tr -d ' ')
    if [ "$dirty" -gt 0 ]; then
        warn "working tree has $dirty uncommitted/untracked changes; proceeding anyway"
    fi
    log "git HEAD: $(cd "$REPO_ROOT" && git rev-parse --short HEAD) ($(cd "$REPO_ROOT" && git log -1 --format='%s' | head -c 80))"
}

# ---------------------------------------------------------------------
# Build
# ---------------------------------------------------------------------
# Build a JVM service via Gradle jib. Writes to the local docker daemon
# (jibDockerBuild) and retags :latest to :stable (matches the
# VERSION_TAG=stable convention the compose file uses).
build_jib() {
    local svc="$1"
    local gradle_path="$2"
    local image="$3"
    (
        cd "$REPO_ROOT"
        # shellcheck disable=SC2086  # GRADLE_EXTRA is intentionally word-split
        run ./gradlew "${gradle_path}:jibDockerBuild" $GRADLE_EXTRA
    )
    run docker tag "${image}:latest" "${image}:stable"
}

# Build a compose service. Context + Dockerfile are in docker-compose.yaml.
build_compose() {
    local svc="$1"
    (
        cd "$COMPOSE_DIR"
        run docker compose --profile "$COMPOSE_PROFILE" build "$svc"
    )
}

# ---------------------------------------------------------------------
# Recreate
# ---------------------------------------------------------------------
recreate_service() {
    local svc="$1"
    (
        cd "$COMPOSE_DIR"
        run docker compose --profile "$COMPOSE_PROFILE" up -d --force-recreate --no-deps "$svc"
    )
}

recreate_full_stack() {
    (
        cd "$COMPOSE_DIR"
        run docker compose --profile "$COMPOSE_PROFILE" up -d
    )
}

# ---------------------------------------------------------------------
# Verify
# ---------------------------------------------------------------------
# Poll `docker inspect` for container health OR plain running state.
# Exits 0 when healthy/running, non-zero on timeout.
wait_container_up() {
    local container="$1"
    local deadline=$(( $(date +%s) + WAIT_TIMEOUT ))

    if $DRY_RUN; then
        log "DRY-RUN  (would wait up to ${WAIT_TIMEOUT}s for $container)"
        return 0
    fi

    while [ "$(date +%s)" -lt "$deadline" ]; do
        local state health
        state=$(docker inspect --format '{{.State.Status}}' "$container" 2>/dev/null || echo missing)
        health=$(docker inspect --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}' "$container" 2>/dev/null || echo missing)
        case "$state:$health" in
            running:healthy|running:none)
                log "$container is up (state=$state health=$health)"
                return 0
                ;;
            running:starting)
                sleep 2
                ;;
            running:unhealthy)
                warn "$container reports unhealthy; continuing to wait (may recover)"
                sleep 3
                ;;
            *)
                sleep 2
                ;;
        esac
    done
    die "timeout after ${WAIT_TIMEOUT}s waiting for $container (state=$state health=$health)"
}

report_image_digest() {
    local image="$1"
    if $DRY_RUN; then
        log "DRY-RUN  (would report digest for $image:stable)"
        return 0
    fi
    local digest
    digest=$(docker image inspect --format '{{.Id}}' "${image}:stable" 2>/dev/null || echo "unknown")
    log "image ${image}:stable digest=$digest"
}

# ---------------------------------------------------------------------
# Orchestration
# ---------------------------------------------------------------------
deploy_single() {
    local svc="$1"
    svc_exists "$svc" || die "unknown service: $svc (use --list)"

    local kind gradle image container
    kind=$(svc_kind "$svc")
    gradle=$(svc_gradle "$svc")
    image=$(svc_image "$svc")
    container=$(svc_container "$svc")

    log "==> $svc (kind=$kind image=$image container=$container)"

    case "$kind" in
        jib)     build_jib "$svc" "$gradle" "$image" ;;
        compose) build_compose "$svc" ;;
        *)       die "unknown kind '$kind' for $svc (bug in deploy-lib.sh)" ;;
    esac

    recreate_service "$svc"
    wait_container_up "$container"
    report_image_digest "$image"
    log "==> $svc deployed"
}

deploy_all() {
    local svc
    # Independent builds first (parallelizable if we want later), then one
    # compose-up reconciles the whole stack in dependency order.
    while IFS= read -r svc; do
        local kind image
        kind=$(svc_kind "$svc")
        image=$(svc_image "$svc")
        log "==> building $svc ($kind)"
        case "$kind" in
            jib)     build_jib "$svc" "$(svc_gradle "$svc")" "$image" ;;
            compose) build_compose "$svc" ;;
        esac
    done < <(svc_list)

    log "==> reconciling full stack (compose up -d)"
    recreate_full_stack
    log "==> full-stack deploy done"
}

# ---------------------------------------------------------------------
# Entry
# ---------------------------------------------------------------------
preflight

if [ "$SERVICE" = "all" ] || [ "$SERVICE" = "*" ]; then
    deploy_all
else
    deploy_single "$SERVICE"
fi

log "done."
