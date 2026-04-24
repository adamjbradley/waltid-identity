#!/usr/bin/env bash
# Shared deploy helpers: service registry + path-filter mapping.
# Sourced by scripts/deploy.sh and by future drift-check tooling.
#
# Design note: the service registry is authored as a single text block
# rather than associative arrays so bash 3.2 (stock macOS) can source it.
# GHA self-hosted runners on Windows use git-bash (also 3.2-lineage).

set -euo pipefail

# ---------------------------------------------------------------------
# Service registry.
#
# Columns, tab-separated:
#   1 svc        — canonical service name (matches docker-compose service key)
#   2 kind       — "jib" (Gradle jib to local daemon) | "compose" (docker compose build)
#   3 gradle     — Gradle project path (jib only; "-" otherwise)
#   4 image      — image name without tag (for docker tag + digest reporting)
#   5 container  — running container name (from docker-compose.yaml)
#   6 healthpath — HTTP path to probe post-deploy, or "-" to rely on docker health
#
# Only services we own + actively redeploy. Third-party images
# (valkey, vault, caddy, keycloak, n8n*, postgres, opa) are not here by
# design — they're pulled from upstream registries on compose up and
# never rebuilt by this pipeline.
# ---------------------------------------------------------------------
# shellcheck disable=SC2034  # consumed by the readers below
_SERVICE_REGISTRY=$(cat <<'EOF'
auth-op	jib	:waltid-services:waltid-auth-op	waltid/auth-op	docker-compose-auth-op-1	-
issuer-api	jib	:waltid-services:waltid-issuer-api	waltid/issuer-api	docker-compose-issuer-api-1	-
verifier-api	jib	:waltid-services:waltid-verifier-api	waltid/verifier-api	docker-compose-verifier-api-1	-
verifier-api2	jib	:waltid-services:waltid-verifier-api2	waltid/verifier-api2	docker-compose-verifier-api2-1	-
wallet-api	jib	:waltid-services:waltid-wallet-api	waltid/wallet-api	docker-compose-wallet-api-1	-
verify-api	jib	:waltid-services:waltid-verify-api	waltid/verify-api	docker-compose-verify-api-1	-
web-portal	compose	-	waltid/portal	docker-compose-web-portal-1	-
waltid-demo-wallet	compose	-	waltid/waltid-demo-wallet	docker-compose-waltid-demo-wallet-1	-
waltid-dev-wallet	compose	-	waltid/waltid-dev-wallet	docker-compose-waltid-dev-wallet-1	-
vc-repo	compose	-	waltid/vc-repository	docker-compose-vc-repo-1	-
rp-widget-demo	compose	-	docker-compose-rp-widget-demo	rp-widget-example-1	-
mock-psp	compose	-	docker-compose-mock-psp	mock-psp-1	-
rp-nextjs-demo	compose	-	docker-compose-rp-nextjs-demo	rp-nextjs-example-1	-
verify-portal	compose	-	docker-compose-verify-portal	docker-compose-verify-portal-1	-
EOF
)

# ---------------------------------------------------------------------
# Path-filter rules: glob → service. First match wins. Evaluated in order.
#
# A tracked file change matching <glob> triggers deploy of <service>.
# `*` means "full stack reconcile via compose up -d" — used for
# docker-compose.yaml itself and any file that affects every container.
# ---------------------------------------------------------------------
# shellcheck disable=SC2034
_PATH_FILTERS=$(cat <<'EOF'
docker-compose/docker-compose.yaml	*
docker-compose/Caddyfile	caddy
docker-compose/.env	*
docker-compose/auth-op/	auth-op
docker-compose/issuer-api/	issuer-api
docker-compose/verifier-api2/	verifier-api2
docker-compose/verifier-api/	verifier-api
docker-compose/wallet-api/	wallet-api
examples/rp-widget-demo/	rp-widget-demo
examples/mock-psp-demo/	mock-psp
examples/rp-nextjs-demo/	rp-nextjs-demo
waltid-applications/waltid-web-portal/	web-portal
waltid-applications/waltid-web-wallet/apps/waltid-demo-wallet/	waltid-demo-wallet
waltid-applications/waltid-web-wallet/apps/waltid-dev-wallet/	waltid-dev-wallet
waltid-services/waltid-auth-op/	auth-op
waltid-services/waltid-issuer-api/	issuer-api
waltid-services/waltid-verifier-api2/	verifier-api2
waltid-services/waltid-verifier-api/	verifier-api
waltid-services/waltid-wallet-api/	wallet-api
waltid-services/waltid-verify-api/	verify-api
EOF
)

# ---------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------

# svc_list — print every service name, one per line.
svc_list() {
    echo "$_SERVICE_REGISTRY" | awk -F'\t' 'NF>=5 {print $1}'
}

# svc_exists <name> — exit 0 iff <name> is a known service.
svc_exists() {
    local name="$1"
    echo "$_SERVICE_REGISTRY" | awk -F'\t' -v n="$name" 'NF>=5 && $1==n {found=1} END {exit !found}'
}

# svc_field <name> <column>  — print the given registry column for a service.
# Columns: 1=svc 2=kind 3=gradle 4=image 5=container 6=healthpath
svc_field() {
    local name="$1" col="$2"
    echo "$_SERVICE_REGISTRY" | awk -F'\t' -v n="$name" -v c="$col" 'NF>=5 && $1==n {print $c; exit}'
}

# svc_kind <name> — "jib" | "compose"
svc_kind()      { svc_field "$1" 2; }
svc_gradle()    { svc_field "$1" 3; }
svc_image()     { svc_field "$1" 4; }
svc_container() { svc_field "$1" 5; }
svc_health()    { svc_field "$1" 6; }

# affected_services_from_paths — read changed-file paths on stdin, write
# matching service names on stdout (deduped, stable order).
#
# A `*` match means "full stack" — emit a literal "*" so deploy.sh can
# decide how to handle it (currently: compose up --build on everything).
affected_services_from_paths() {
    # awk's -v doesn't handle embedded newlines; pass the multi-line filter
    # block via the environment instead.
    _PATH_FILTERS="$_PATH_FILTERS" awk -F'\t' '
        BEGIN {
            # Parse _PATH_FILTERS into parallel arrays.
            n = split(ENVIRON["_PATH_FILTERS"], rows, "\n")
            for (i=1; i<=n; i++) {
                if (rows[i] == "") continue
                split(rows[i], f, "\t")
                glob[i] = f[1]; target[i] = f[2]; nfilters = i
            }
        }
        {
            path = $0
            for (i=1; i<=nfilters; i++) {
                # Prefix match (anchored at start). globs end with "/" for dir prefixes,
                # or are exact paths for single files.
                g = glob[i]
                if (index(path, g) == 1) {
                    if (!seen[target[i]]) {
                        print target[i]
                        seen[target[i]] = 1
                    }
                    # Full-stack match trumps everything else — stop processing this path.
                    if (target[i] == "*") exit
                    next
                }
            }
        }
    '
}

# self_test — sanity assertions; run during CI and during dev.
deploy_lib_self_test() {
    local failures=0
    local svc
    for svc in auth-op rp-widget-demo mock-psp verifier-api2 web-portal; do
        if ! svc_exists "$svc"; then
            echo "self-test FAIL: expected known service: $svc" >&2
            failures=$((failures + 1))
        fi
    done
    if svc_exists "nonexistent-service-xyz"; then
        echo "self-test FAIL: svc_exists returned true for bogus name" >&2
        failures=$((failures + 1))
    fi
    # Path filter assertions.
    local got
    got=$(printf 'examples/rp-widget-demo/server.js\n' | affected_services_from_paths)
    if [ "$got" != "rp-widget-demo" ]; then
        echo "self-test FAIL: rp-widget-demo/server.js mapped to '$got', expected 'rp-widget-demo'" >&2
        failures=$((failures + 1))
    fi
    got=$(printf 'docker-compose/docker-compose.yaml\n' | affected_services_from_paths)
    if [ "$got" != "*" ]; then
        echo "self-test FAIL: docker-compose.yaml mapped to '$got', expected '*'" >&2
        failures=$((failures + 1))
    fi
    # Multi-file change across two services → both, deduped.
    got=$(printf 'examples/rp-widget-demo/server.js\nwaltid-services/waltid-auth-op/src/main/kotlin/x.kt\n' \
          | affected_services_from_paths | sort | tr '\n' ',' | sed 's/,$//')
    if [ "$got" != "auth-op,rp-widget-demo" ]; then
        echo "self-test FAIL: multi-file mapping produced '$got'" >&2
        failures=$((failures + 1))
    fi
    # Unknown path → empty output.
    got=$(printf 'README.md\n' | affected_services_from_paths)
    if [ -n "$got" ]; then
        echo "self-test FAIL: unrelated path 'README.md' matched service '$got'" >&2
        failures=$((failures + 1))
    fi
    if [ $failures -gt 0 ]; then
        echo "deploy-lib self-test: $failures failures" >&2
        return 1
    fi
    echo "deploy-lib self-test: OK"
}
