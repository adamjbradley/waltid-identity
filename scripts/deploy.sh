#!/usr/bin/env bash
# scripts/deploy.sh — Mac-driven deploy to the remote Windows docker host.
#
# Design: Mac builds (it has Java, Gradle, Node). Image ships to the
# remote daemon via `docker save | docker load`. The remote compose-up
# runs via a sidecar container that bind-mounts the Windows repo at a
# path the daemon can resolve — this is the "path-alignment trick"
# needed because compose resolves bind mounts on the client, and the
# remote daemon can't see Mac's filesystem.
#
# Nothing is installed on the Windows host beyond what was already
# there (Docker Desktop). No runners, no JDK, no git for windows —
# the host is a docker runtime, not a build machine.
#
# Usage:
#   scripts/deploy.sh <service>
#   scripts/deploy.sh all
#   scripts/deploy.sh --list
#   scripts/deploy.sh --dry-run <service>
#
# Environment (override as needed):
#   DOCKER_LOCAL_CONTEXT   — docker context for builds. Default desktop-linux.
#   DOCKER_REMOTE_CONTEXT  — docker context for recreate. Default windows-docker-dev.
#                            Set to the SAME value as LOCAL to do an all-local deploy
#                            (useful for Mac-only dev without a remote daemon).
#   REMOTE_REPO_MOUNT      — absolute path INSIDE the remote daemon where the Windows
#                            repo is visible. Default /run/desktop/mnt/host/c/Users/sshuser/Projects/waltid-identity
#   COMPOSE_PROFILE        — default "all" (matches the live demo stack).
#   WAIT_TIMEOUT           — seconds to wait for container health. Default 120.
#   GRADLE_EXTRA           — extra args passed to gradlew.
#   SIDECAR_IMAGE          — docker CLI image used for remote compose-up. Default docker:cli.

set -euo pipefail

SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
REPO_ROOT=$(cd "$SCRIPT_DIR/.." && pwd)

# shellcheck source-path=SCRIPTDIR
# shellcheck source=deploy-lib.sh
source "$SCRIPT_DIR/deploy-lib.sh"

DOCKER_LOCAL_CONTEXT=${DOCKER_LOCAL_CONTEXT:-desktop-linux}
DOCKER_REMOTE_CONTEXT=${DOCKER_REMOTE_CONTEXT:-windows-docker-dev}
REMOTE_REPO_MOUNT=${REMOTE_REPO_MOUNT:-/run/desktop/mnt/host/c/Users/sshuser/Projects/waltid-identity}
COMPOSE_PROFILE=${COMPOSE_PROFILE:-all}
WAIT_TIMEOUT=${WAIT_TIMEOUT:-120}
GRADLE_EXTRA=${GRADLE_EXTRA:-}
SIDECAR_IMAGE=${SIDECAR_IMAGE:-docker:cli}

COMPOSE_DIR="$REPO_ROOT/docker-compose"
REMOTE_COMPOSE_DIR="$REMOTE_REPO_MOUNT/docker-compose"

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
    cat >&2 <<EOF
usage: $0 [--dry-run|--list] <service|all>

known services:
$(svc_list | sed 's/^/  /')

remote=$DOCKER_REMOTE_CONTEXT  local=$DOCKER_LOCAL_CONTEXT  mount=$REMOTE_REPO_MOUNT
EOF
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
# Pipeline variant: `run_pipe "<label>" cmd1 args… "|" cmd2 args…`
# Splits on the literal string "|" and executes `cmd1 … | cmd2 …` as a
# real shell pipe (no eval, preserves quoting). Dry-run just prints the label.
run_pipe() {
    local label="$1"; shift
    if $DRY_RUN; then
        printf '[deploy] DRY-RUN  (pipe: %s)\n' "$label"
        return 0
    fi
    log "+ $label"
    local -a left=() right=()
    local mode=left
    local arg
    for arg in "$@"; do
        if [ "$arg" = "|" ]; then
            mode=right
            continue
        fi
        if [ "$mode" = "left" ]; then
            left+=("$arg")
        else
            right+=("$arg")
        fi
    done
    "${left[@]}" | "${right[@]}"
}

# ---------------------------------------------------------------------
# Pre-flight
# ---------------------------------------------------------------------
preflight() {
    command -v docker >/dev/null || die "docker not on PATH"
    command -v git >/dev/null    || die "git not on PATH"

    # Local context: must resolve. Remote: must resolve (unless it's the
    # same as local, in which case no remote ops happen).
    docker context inspect "$DOCKER_LOCAL_CONTEXT" >/dev/null 2>&1 \
        || die "local docker context '$DOCKER_LOCAL_CONTEXT' not found (docker context ls)"
    if [ "$DOCKER_REMOTE_CONTEXT" != "$DOCKER_LOCAL_CONTEXT" ]; then
        docker context inspect "$DOCKER_REMOTE_CONTEXT" >/dev/null 2>&1 \
            || die "remote docker context '$DOCKER_REMOTE_CONTEXT' not found (docker context ls)"
    fi

    [ -d "$COMPOSE_DIR" ]        || die "compose dir not found: $COMPOSE_DIR"
    [ -f "$COMPOSE_DIR/docker-compose.yaml" ] || die "docker-compose.yaml not found in $COMPOSE_DIR"

    local dirty
    dirty=$(cd "$REPO_ROOT" && git status --porcelain | wc -l | tr -d ' ')
    if [ "$dirty" -gt 0 ]; then
        warn "working tree has $dirty uncommitted/untracked changes; proceeding anyway"
    fi
    log "git HEAD:        $(cd "$REPO_ROOT" && git rev-parse --short HEAD) ($(cd "$REPO_ROOT" && git log -1 --format='%s' | head -c 80))"
    log "local context:   $DOCKER_LOCAL_CONTEXT"
    log "remote context:  $DOCKER_REMOTE_CONTEXT"
    log "remote mount:    $REMOTE_REPO_MOUNT"
}

# remote_op — true when we actually cross docker contexts. Lets the
# script also be used for all-local deploys on a dev machine.
remote_op() { [ "$DOCKER_REMOTE_CONTEXT" != "$DOCKER_LOCAL_CONTEXT" ]; }

# ---------------------------------------------------------------------
# Build — always against LOCAL context
# ---------------------------------------------------------------------
build_jib() {
    local svc="$1" gradle_path="$2" image="$3"
    (
        cd "$REPO_ROOT"
        # jib reads the current docker context. Set it explicitly via the
        # DOCKER_CONTEXT env var so the build lands in the local daemon.
        # shellcheck disable=SC2086  # GRADLE_EXTRA is intentionally word-split
        run env DOCKER_CONTEXT="$DOCKER_LOCAL_CONTEXT" ./gradlew "${gradle_path}:jibDockerBuild" $GRADLE_EXTRA
    )
    # Normalise to :stable — matches compose's VERSION_TAG=stable default.
    run docker --context "$DOCKER_LOCAL_CONTEXT" tag "${image}:latest" "${image}:stable"
}

build_compose() {
    local svc="$1" image="$2"
    (
        cd "$COMPOSE_DIR"
        run docker --context "$DOCKER_LOCAL_CONTEXT" compose --profile "$COMPOSE_PROFILE" build "$svc"
    )
    # Compose build tags :latest — normalise to :stable so the transfer +
    # compose-up on the remote can pull from a single canonical tag.
    run docker --context "$DOCKER_LOCAL_CONTEXT" tag "${image}:latest" "${image}:stable"
}

# ---------------------------------------------------------------------
# Transfer — docker save | docker load
# ---------------------------------------------------------------------
# Ship the freshly built image from LOCAL to REMOTE daemon.
# Skipped when LOCAL == REMOTE. Both build_jib and build_compose are
# responsible for producing "${image}:stable" in the local daemon
# before we get here.
transfer_image() {
    local image="$1"
    if ! remote_op; then
        log "skip transfer (local == remote)"
        return 0
    fi
    local label="save ${image}:stable on $DOCKER_LOCAL_CONTEXT → load on $DOCKER_REMOTE_CONTEXT"
    run_pipe "$label" \
        docker --context "$DOCKER_LOCAL_CONTEXT" save "${image}:stable" \
        "|" docker --context "$DOCKER_REMOTE_CONTEXT" load
}

# ---------------------------------------------------------------------
# Recreate — remote compose up via sidecar with path alignment
# ---------------------------------------------------------------------
# The remote daemon can't resolve Mac-side bind-mount paths. Trick: run
# `docker compose up` inside a sidecar container that bind-mounts the
# Windows repo at a path the daemon DOES know, and is the same path as
# the sidecar's working-dir. Compose resolves bind mounts to
# "$REMOTE_REPO_MOUNT/docker-compose/…" which is valid on the daemon.
sidecar_compose_up() {
    local svc_arg="$1"  # "<svc>" or empty for full-stack reconcile
    local cmd_args=(
        --rm
        -v /var/run/docker.sock:/var/run/docker.sock
        -v "$REMOTE_REPO_MOUNT:$REMOTE_REPO_MOUNT"
        -w "$REMOTE_COMPOSE_DIR"
        "$SIDECAR_IMAGE"
        docker compose --profile "$COMPOSE_PROFILE" up -d
    )
    if [ -n "$svc_arg" ]; then
        cmd_args+=(--force-recreate --no-deps "$svc_arg")
    fi
    run docker --context "$DOCKER_REMOTE_CONTEXT" run "${cmd_args[@]}"
}

# For a local deploy: just run compose directly, no sidecar needed.
local_compose_up() {
    local svc_arg="$1"
    (
        cd "$COMPOSE_DIR"
        if [ -n "$svc_arg" ]; then
            run docker --context "$DOCKER_LOCAL_CONTEXT" compose --profile "$COMPOSE_PROFILE" up -d --force-recreate --no-deps "$svc_arg"
        else
            run docker --context "$DOCKER_LOCAL_CONTEXT" compose --profile "$COMPOSE_PROFILE" up -d
        fi
    )
}

recreate_service() {
    local svc="$1"
    if remote_op; then
        sidecar_compose_up "$svc"
    else
        local_compose_up "$svc"
    fi
}

recreate_full_stack() {
    if remote_op; then
        sidecar_compose_up ""
    else
        local_compose_up ""
    fi
}

# ---------------------------------------------------------------------
# Verify
# ---------------------------------------------------------------------
wait_container_up() {
    local container="$1"
    local deadline=$(( $(date +%s) + WAIT_TIMEOUT ))

    if $DRY_RUN; then
        log "DRY-RUN  (would wait up to ${WAIT_TIMEOUT}s for $container on $DOCKER_REMOTE_CONTEXT)"
        return 0
    fi

    while [ "$(date +%s)" -lt "$deadline" ]; do
        local state health
        state=$(docker --context "$DOCKER_REMOTE_CONTEXT" inspect --format '{{.State.Status}}' "$container" 2>/dev/null || echo missing)
        health=$(docker --context "$DOCKER_REMOTE_CONTEXT" inspect --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}' "$container" 2>/dev/null || echo missing)
        case "$state:$health" in
            running:healthy|running:none)
                log "$container up (state=$state health=$health)"
                return 0
                ;;
            running:starting)
                sleep 2
                ;;
            running:unhealthy)
                warn "$container unhealthy; still waiting (may recover)"
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
        log "DRY-RUN  (would report digest for $image:stable on $DOCKER_REMOTE_CONTEXT)"
        return 0
    fi
    local digest
    digest=$(docker --context "$DOCKER_REMOTE_CONTEXT" image inspect --format '{{.Id}}' "${image}:stable" 2>/dev/null || echo "unknown")
    log "remote ${image}:stable digest=$digest"
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
        compose) build_compose "$svc" "$image" ;;
        *)       die "unknown kind '$kind' for $svc (bug in deploy-lib.sh)" ;;
    esac

    transfer_image "$image"
    recreate_service "$svc"
    wait_container_up "$container"
    report_image_digest "$image"
    log "==> $svc deployed"
}

deploy_all() {
    local svc image kind
    while IFS= read -r svc; do
        kind=$(svc_kind "$svc")
        image=$(svc_image "$svc")
        log "==> building $svc ($kind)"
        case "$kind" in
            jib)     build_jib "$svc" "$(svc_gradle "$svc")" "$image" ;;
            compose) build_compose "$svc" "$image" ;;
        esac
        transfer_image "$image"
    done < <(svc_list)

    log "==> reconciling full stack on remote"
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
