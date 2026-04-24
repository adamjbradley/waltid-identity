#!/usr/bin/env bash
# scripts/deploy.sh — Mac-driven deploy to the remote Windows docker host.
#
# Design: Mac is the orchestrator. Source lives on Mac; gradle + docker
# compose are invoked from here with DOCKER_CONTEXT pointing at the
# remote daemon, so the build context tar-streams to Windows and lands
# as a local image on that daemon. No save/load when the build context
# is the same as the recreate context (the default). The remote
# compose-up then runs inside a transient sidecar with the
# path-alignment bind mount so compose's client-side bind-mount
# resolution produces paths the daemon can actually mount.
#
# Nothing is installed on the Windows host beyond what was already
# there (Docker Desktop). No runners, no JDK, no git-for-windows — the
# host is a docker runtime, not a build machine.
#
# Usage:
#   scripts/deploy.sh <service>
#   scripts/deploy.sh all
#   scripts/deploy.sh --list
#   scripts/deploy.sh --dry-run <service>
#
# Environment (override as needed):
#   DOCKER_BUILD_CONTEXT   — context the build writes to. Default windows-docker-dev.
#                            Set to desktop-linux if you want to build in Mac's local
#                            daemon and then ship via save/load (transfer activates
#                            automatically when BUILD_CONTEXT ≠ REMOTE_CONTEXT).
#   DOCKER_REMOTE_CONTEXT  — context that runs the recreated container.
#                            Default windows-docker-dev.
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

DOCKER_BUILD_CONTEXT=${DOCKER_BUILD_CONTEXT:-windows-docker-dev}
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

remote=$DOCKER_REMOTE_CONTEXT  local=$DOCKER_BUILD_CONTEXT  mount=$REMOTE_REPO_MOUNT
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

    # Build context: must resolve. Remote: must resolve (unless it's the
    # same as build, in which case no transfer op happens).
    docker context inspect "$DOCKER_BUILD_CONTEXT" >/dev/null 2>&1 \
        || die "build docker context '$DOCKER_BUILD_CONTEXT' not found (docker context ls)"
    if [ "$DOCKER_REMOTE_CONTEXT" != "$DOCKER_BUILD_CONTEXT" ]; then
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
    log "build context:   $DOCKER_BUILD_CONTEXT"
    log "remote context:  $DOCKER_REMOTE_CONTEXT"
    log "remote mount:    $REMOTE_REPO_MOUNT"
}

# remote_op — true when the build context differs from the remote
# context (i.e. a save|load transfer is required).
remote_op() { [ "$DOCKER_REMOTE_CONTEXT" != "$DOCKER_BUILD_CONTEXT" ]; }

# needs_sidecar — true when the remote daemon cannot see this client's
# filesystem, so `docker compose up` would resolve bind mounts to paths
# the daemon can't mount. Detected by inspecting the remote context's
# endpoint: ssh:// or tcp:// → cross-machine → sidecar needed; unix:// /
# npipe:// → same machine → direct compose up works.
#
# The sidecar workaround runs compose inside a transient docker:cli
# container on the remote daemon, with a path-aligned bind mount so
# compose's client-side resolution produces daemon-valid paths.
needs_sidecar() {
    local endpoint
    endpoint=$(docker context inspect "$DOCKER_REMOTE_CONTEXT" \
        --format '{{.Endpoints.docker.Host}}' 2>/dev/null || echo "")
    case "$endpoint" in
        ssh://*|tcp://*) return 0 ;;
        *)               return 1 ;;
    esac
}

# ---------------------------------------------------------------------
# Build — against DOCKER_BUILD_CONTEXT (default: remote Windows daemon)
# ---------------------------------------------------------------------
build_jib() {
    local svc="$1" gradle_path="$2" image="$3"
    (
        cd "$REPO_ROOT"
        # jib reads DOCKER_CONTEXT for its target daemon. Default config
        # points it at the remote Windows daemon, so the layers stream
        # over SSH — no Mac docker daemon required.
        # shellcheck disable=SC2086  # GRADLE_EXTRA is intentionally word-split
        run env DOCKER_CONTEXT="$DOCKER_BUILD_CONTEXT" ./gradlew "${gradle_path}:jibDockerBuild" $GRADLE_EXTRA
    )
    # Normalise to :stable — matches compose's VERSION_TAG=stable default.
    run docker --context "$DOCKER_BUILD_CONTEXT" tag "${image}:latest" "${image}:stable"
}

build_compose() {
    local svc="$1" image="$2"
    (
        cd "$COMPOSE_DIR"
        # docker compose build streams the build-context tarball to the
        # daemon specified by --context. The compose file + context
        # directories are read on this machine; the actual docker build
        # runs on the daemon.
        run docker --context "$DOCKER_BUILD_CONTEXT" compose --profile "$COMPOSE_PROFILE" build "$svc"
    )
    # Both tags so both the `image: ${VERSION_TAG:-latest}` lookup in
    # compose and any downstream `:stable` reference resolve.
    run docker --context "$DOCKER_BUILD_CONTEXT" tag "${image}:latest" "${image}:stable"
}

# ---------------------------------------------------------------------
# Transfer — docker save | docker load
# ---------------------------------------------------------------------
# Ship the freshly built image from LOCAL to REMOTE daemon. Save both
# :latest and :stable tags so both lookups resolve on the remote:
#   - Services with `image: waltid/x:${VERSION_TAG}` expect :stable
#     (Windows .env sets VERSION_TAG=stable)
#   - Compose services without an `image:` directive auto-name as
#     `docker-compose-<svc>:latest` and expect :latest.
# Both tags point at the same image ID; save preserves both in one stream.
# Skipped when LOCAL == REMOTE.
transfer_image() {
    local image="$1"
    if ! remote_op; then
        log "skip transfer (local == remote)"
        return 0
    fi
    local label="save ${image}:{latest,stable} on $DOCKER_BUILD_CONTEXT → load on $DOCKER_REMOTE_CONTEXT"
    run_pipe "$label" \
        docker --context "$DOCKER_BUILD_CONTEXT" save "${image}:latest" "${image}:stable" \
        "|" docker --context "$DOCKER_REMOTE_CONTEXT" load
}

# ---------------------------------------------------------------------
# Recreate — remote compose up via sidecar with path alignment
# ---------------------------------------------------------------------
# The sidecar runs `docker compose up` inside a transient container on
# the remote daemon, reading the compose file from the Windows repo
# bind-mounted at a daemon-visible path. But the Windows git clone may
# be behind — its compose file can lack services (e.g. `mock-psp:` not
# present at Windows HEAD `2bc62e888`). We sync *just* docker-compose.yaml
# from Mac to Windows before every deploy so the sidecar always reads the
# current authoritative file. We intentionally DON'T touch `.env`, the
# relying-parties JSONs, or anything else the Windows side mutates — only
# the structural compose manifest.
sync_remote_compose_file() {
    if $DRY_RUN; then
        log "DRY-RUN  (would sync docker-compose.yaml Mac → $DOCKER_REMOTE_CONTEXT)"
        return 0
    fi
    log "+ sync docker-compose.yaml Mac → $DOCKER_REMOTE_CONTEXT"
    docker --context "$DOCKER_REMOTE_CONTEXT" run --rm -i \
        -v "$REMOTE_COMPOSE_DIR:/target" \
        alpine sh -c 'cat > /target/docker-compose.yaml' \
        < "$COMPOSE_DIR/docker-compose.yaml"
}

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
            run docker --context "$DOCKER_BUILD_CONTEXT" compose --profile "$COMPOSE_PROFILE" up -d --force-recreate --no-deps "$svc_arg"
        else
            run docker --context "$DOCKER_BUILD_CONTEXT" compose --profile "$COMPOSE_PROFILE" up -d
        fi
    )
}

recreate_service() {
    local svc="$1"
    local container
    container=$(svc_container "$svc")
    # Services use explicit `container_name:` in docker-compose.yaml.
    # If the existing container was created by a compose invocation with
    # a different project label (e.g. historic ad-hoc runs), the new
    # compose up will fail with "container name already in use" — even
    # with --force-recreate, because compose only recreates containers
    # it recognises as belonging to its own project. Force-remove the
    # container first so the up step creates it fresh.
    if [ -n "$container" ]; then
        if $DRY_RUN; then
            log "DRY-RUN  (would force-remove stale container $container if present)"
        else
            log "+ force-remove stale container $container (if any)"
            docker --context "$DOCKER_REMOTE_CONTEXT" rm -f "$container" >/dev/null 2>&1 || true
        fi
    fi
    if needs_sidecar; then
        sync_remote_compose_file
        sidecar_compose_up "$svc"
    else
        local_compose_up "$svc"
    fi
}

recreate_full_stack() {
    if needs_sidecar; then
        sync_remote_compose_file
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
