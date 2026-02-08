#!/bin/bash
# Verify API Isolation Check
# Run this BEFORE and AFTER any Verify API changes
#
# This script ensures the Verify API does not modify any existing services or libraries.
# It checks for modifications in three states:
#   1. Uncommitted changes (working directory)
#   2. Staged changes (git index)
#   3. Committed changes on the branch (compared to main)

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

cd "$REPO_ROOT"

echo "=== Verify API Isolation Check ==="
echo ""
echo "Working directory: $REPO_ROOT"
echo "Current branch: $(git branch --show-current)"
echo ""

# Protected paths that must not be modified
PROTECTED_PATHS=(
  "waltid-services/waltid-issuer-api"
  "waltid-services/waltid-verifier-api"
  "waltid-services/waltid-verifier-api2"
  "waltid-services/waltid-wallet-api"
  "waltid-libraries"
)

ISOLATION_VIOLATED=false

# Function to check for modifications in protected paths
check_modifications() {
  local description="$1"
  shift
  local git_diff_args=("$@")

  local modified_files
  modified_files=$(git diff --name-only "${git_diff_args[@]}" -- "${PROTECTED_PATHS[@]}" 2>/dev/null || echo "")

  if [ -n "$modified_files" ]; then
    echo "ERROR: ISOLATION VIOLATION ($description):"
    echo "$modified_files" | sed 's/^/  - /'
    echo ""
    ISOLATION_VIOLATED=true
  fi
}

echo "Checking for modifications to protected services..."
echo ""

# Check 1: Uncommitted changes (working directory)
check_modifications "uncommitted changes"

# Check 2: Staged changes (git index)
check_modifications "staged changes" --cached

# Check 3: Committed changes on branch (compared to main)
if git rev-parse --verify main >/dev/null 2>&1; then
  check_modifications "committed changes vs main" main...HEAD
else
  echo "Warning: 'main' branch not found, skipping branch comparison"
fi

if [ "$ISOLATION_VIOLATED" = true ]; then
  echo ""
  echo "FAILED: The Verify API must NOT modify any existing services or libraries."
  echo ""
  echo "Protected paths:"
  for path in "${PROTECTED_PATHS[@]}"; do
    echo "  - $path"
  done
  exit 1
fi

echo "OK: No existing services modified"
echo ""

# Run existing service tests
echo "=== Running Service Tests ==="
echo ""

run_test() {
  local service_name="$1"
  local gradle_task="$2"
  local test_output
  local test_exit_code

  echo "Running $service_name tests..."

  # Capture output and exit code
  test_output=$(./gradlew "$gradle_task" 2>&1) && test_exit_code=0 || test_exit_code=$?

  if [ $test_exit_code -ne 0 ]; then
    echo ""
    echo "FAILED: $service_name tests failed"
    echo ""
    echo "=== Test Output ==="
    echo "$test_output"
    echo "==================="
    exit 1
  fi

  echo "OK: $service_name tests pass"
}

run_test "issuer-api" ":waltid-services:waltid-issuer-api:test"
run_test "verifier-api2" ":waltid-services:waltid-verifier-api2:test"

echo ""
echo "=== Isolation Verified ==="
echo ""
echo "All checks passed. The Verify API maintains isolation from existing services."
