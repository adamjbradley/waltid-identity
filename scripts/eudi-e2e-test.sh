#!/bin/bash
#
# EUDI Wallet E2E Testing Script
#
# Automates issuance and verification flows via ADB intents.
# Usage:
#   ./scripts/eudi-e2e-test.sh list              # List available credentials
#   ./scripts/eudi-e2e-test.sh issue <type>      # Issue credential to wallet
#   ./scripts/eudi-e2e-test.sh verify <type>     # Verify credential from wallet
#   ./scripts/eudi-e2e-test.sh e2e <type>        # Full issue + verify flow
#
set -euo pipefail

# Configuration
ISSUER_URL="https://issuer.theaustraliahack.com"
VERIFIER_URL="https://verifier2.theaustraliahack.com"
WALLET_PACKAGE="eu.europa.ec.euidi.dev"
CLIENT_ID="x509_san_dns:verifier2.theaustraliahack.com"

# Verifier signing key and certificate
VERIFIER_KEY='{
  "type": "jwk",
  "jwk": {
    "kty": "EC",
    "crv": "P-256",
    "x": "1Z2eGpdQVfWkAQQmNv8oT-lMwbhsFxWTZmhAYFHR5wY",
    "y": "tvX699C21qGEMq7zqjpEhqy2kPT8KInnbxlLZzeSXdo",
    "d": "j6-GyxLnrDSQGCljc678kmrihQFa0GR92JZXHDEQX38"
  }
}'
VERIFIER_X5C='["MIIBnzCCAUagAwIBAgIUQSg5NhDlxwDFyAM7YJe++0QGyKIwCgYIKoZIzj0EAwIwKTEnMCUGA1UEAwwedmVyaWZpZXIyLnRoZWF1c3RyYWxpYWhhY2suY29tMB4XDTI2MDIwMzAzNTIwM1oXDTI3MDIwMzAzNTIwM1owKTEnMCUGA1UEAwwedmVyaWZpZXIyLnRoZWF1c3RyYWxpYWhhY2suY29tMFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAE1Z2eGpdQVfWkAQQmNv8oT+lMwbhsFxWTZmhAYFHR5wa29fr30LbWoYQyrvOqOkSGrLaQ9PwoiedvGUtnN5Jd2qNMMEowKQYDVR0RBCIwIIIedmVyaWZpZXIyLnRoZWF1c3RyYWxpYWhhY2suY29tMB0GA1UdDgQWBBRt0uKz8aKVlUxKF9j6vhAsGl3nHDAKBggqhkjOPQQDAgNHADBEAiAQ+AlF3Q4dput8QTizDyKo99R/sv3CC7BzqEjOxxsnzQIgF+rnBf0HghobWkjSVNwP8j/ekasfjp+1HDJclcNaUvs="]'

# Issuer key for mDoc (matches x5Chain certificate)
MDOC_ISSUER_KEY='{
  "type": "jwk",
  "jwk": {
    "kty": "EC",
    "d": "-wSIL_tMH7-mO2NAfHn03I8ZWUHNXVzckTTb96Wsc1s",
    "crv": "P-256",
    "kid": "sW5yv0UmZ3S0dQuUrwlR9I3foREBHHFwXhGJGqGEVf0",
    "x": "Pzp6eVSAdXERqAp8q8OuDEhl2ILGAaoaQXTJ2sD2g5U",
    "y": "6dwhUAzKzKUf0kNI7f40zqhMZNT0c40O_WiqSLCTNZo"
  }
}'

# Issuer key for SD-JWT
SDJWT_ISSUER_KEY='{
  "type": "jwk",
  "jwk": {
    "kty": "EC",
    "crv": "P-256",
    "x": "_-t2Oc_Nra8Cgix7Nw2-_RuZt5KrgVZsK3r8aTMSsVQ",
    "y": "nkaVInW3t_q5eB85KnULykQbprApT2RCNZZuJlNPD2Q",
    "d": "URb-8MihTBwKpFA91vzVfcuqxj5qhjNrnhd2fARX62A",
    "kid": "eudi-issuer-key-1"
  }
}'

# X.509 certificate chain for mDoc issuance (valid until 2026-09-02)
X5CHAIN='["-----BEGIN CERTIFICATE-----\nMIICCTCCAbCgAwIBAgIUfqyiArJZoX7M61/473UAVi2/UpgwCgYIKoZIzj0EAwIwKDELMAkGA1UEBhMCQVQxGTAXBgNVBAMMEFdhbHRpZCBUZXN0IElBQ0EwHhcNMjUwNjAyMDY0MTEzWhcNMjYwOTAyMDY0MTEzWjAzMQswCQYDVQQGEwJBVDEkMCIGA1UEAwwbV2FsdGlkIFRlc3QgRG9jdW1lbnQgU2lnbmVyMFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEPzp6eVSAdXERqAp8q8OuDEhl2ILGAaoaQXTJ2sD2g5Xp3CFQDMrMpR/SQ0jt/jTOqExk1PRzjQ79aKpIsJM1mqOBrDCBqTAfBgNVHSMEGDAWgBTxCn2nWMrE70qXb614U14BweY2azAdBgNVHQ4EFgQUx5qkOLC4lpl1xpYZGmF9HLxtp0gwDgYDVR0PAQH/BAQDAgeAMBoGA1UdEgQTMBGGD2h0dHBzOi8vd2FsdC5pZDAVBgNVHSUBAf8ECzAJBgcogYxdBQECMCQGA1UdHwQdMBswGaAXoBWGE2h0dHBzOi8vd2FsdC5pZC9jcmwwCgYIKoZIzj0EAwIDRwAwRAIgHTap3c6yCUNhDVfZWBPMKj9dCWZbrME03kh9NJTbw1ECIAvVvuGll9O21eR16SkJHHAA1pPcovhcTvF9fz9cc66M\n-----END CERTIFICATE-----"]'

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# ============================================================================
# Helper Functions
# ============================================================================

log_info() {
  echo -e "${BLUE}ℹ️  $1${NC}"
}

log_success() {
  echo -e "${GREEN}✅ $1${NC}"
}

log_warning() {
  echo -e "${YELLOW}⚠️  $1${NC}"
}

log_error() {
  echo -e "${RED}❌ $1${NC}"
}

wait_for_user() {
  local action="$1"
  echo ""
  echo -e "${YELLOW}👆 Please $action in the wallet${NC}"
  read -p "   Press Enter when done... "
}

# ============================================================================
# Pre-flight Checks
# ============================================================================

check_dependencies() {
  local missing=()

  command -v curl >/dev/null 2>&1 || missing+=("curl")
  command -v jq >/dev/null 2>&1 || missing+=("jq")
  command -v adb >/dev/null 2>&1 || missing+=("adb")

  if [ ${#missing[@]} -gt 0 ]; then
    log_error "Missing dependencies: ${missing[*]}"
    exit 1
  fi
}

check_adb_device() {
  if ! adb devices 2>/dev/null | grep -q "device$"; then
    log_error "No ADB device connected"
    echo "   Run 'adb devices' to check connection"
    exit 1
  fi
  log_success "ADB device connected"
}

check_wallet_installed() {
  if ! adb shell pm list packages 2>/dev/null | grep -q "$WALLET_PACKAGE"; then
    log_error "EUDI wallet not installed ($WALLET_PACKAGE)"
    echo "   Install the EUDI wallet dev flavor first"
    exit 1
  fi
  log_success "EUDI wallet installed"
}

check_services() {
  log_info "Checking services..."

  if ! curl -s --connect-timeout 5 "${ISSUER_URL}/.well-known/openid-credential-issuer" >/dev/null 2>&1; then
    log_error "Issuer not reachable: $ISSUER_URL"
    exit 1
  fi
  log_success "Issuer reachable"

  if ! curl -s --connect-timeout 5 "${VERIFIER_URL}/health" >/dev/null 2>&1; then
    # Try alternate health check
    if ! curl -s --connect-timeout 5 "${VERIFIER_URL}/" >/dev/null 2>&1; then
      log_error "Verifier not reachable: $VERIFIER_URL"
      exit 1
    fi
  fi
  log_success "Verifier reachable"
}

preflight_checks() {
  echo "Running pre-flight checks..."
  echo ""
  check_dependencies
  check_adb_device
  check_wallet_installed
  check_services
  echo ""
}

# ============================================================================
# Credential Payloads
# ============================================================================

get_issue_payload() {
  local type="$1"
  local today=$(date +%Y-%m-%d)
  local expiry=$(date -v+5y +%Y-%m-%d 2>/dev/null || date -d "+5 years" +%Y-%m-%d)

  case "$type" in
    pid-mdoc)
      cat <<EOF
{
  "issuerKey": $MDOC_ISSUER_KEY,
  "credentialConfigurationId": "eu.europa.ec.eudi.pid.1",
  "mdocData": {
    "eu.europa.ec.eudi.pid.1": {
      "family_name": "Smith",
      "given_name": "Alice",
      "birth_date": "1985-06-20",
      "age_over_18": true,
      "issuance_date": "$today",
      "expiry_date": "$expiry",
      "issuing_country": "AU",
      "issuing_authority": "Test Authority"
    }
  },
  "x5Chain": $X5CHAIN
}
EOF
      ;;
    pid-sdjwt)
      cat <<EOF
{
  "issuerKey": $SDJWT_ISSUER_KEY,
  "credentialConfigurationId": "urn:eudi:pid:1",
  "credentialData": {
    "family_name": "Smith",
    "given_name": "Alice",
    "birth_date": "1985-06-20",
    "nationality": "AU",
    "issuance_date": "$today",
    "expiry_date": "$expiry",
    "issuing_country": "AU",
    "issuing_authority": "Test Authority"
  }
}
EOF
      ;;
    mdl)
      cat <<EOF
{
  "issuerKey": $MDOC_ISSUER_KEY,
  "credentialConfigurationId": "org.iso.18013.5.1.mDL",
  "mdocData": {
    "org.iso.18013.5.1": {
      "family_name": "Smith",
      "given_name": "Alice",
      "birth_date": "1985-06-20",
      "issue_date": "$today",
      "expiry_date": "$expiry",
      "issuing_country": "AU",
      "issuing_authority": "AU Transport",
      "document_number": "DL123456789",
      "portrait": [141,182,121,111,238,50,120,94,54,111,113,13,241,12,12],
      "driving_privileges": [{"vehicle_category_code":"B","issue_date":"2020-01-01","expiry_date":"$expiry"}],
      "un_distinguishing_sign": "AUS"
    }
  },
  "x5Chain": $X5CHAIN
}
EOF
      ;;
    *)
      log_error "Unknown credential type: $type"
      exit 1
      ;;
  esac
}

get_verify_payload() {
  local type="$1"

  case "$type" in
    pid-mdoc)
      cat <<EOF
{
  "flow_type": "cross_device",
  "core_flow": {
    "signed_request": true,
    "clientId": "$CLIENT_ID",
    "key": $VERIFIER_KEY,
    "x5c": $VERIFIER_X5C,
    "dcql_query": {
      "credentials": [{
        "id": "eudi_pid_mdoc",
        "format": "mso_mdoc",
        "meta": {
          "doctype_value": "eu.europa.ec.eudi.pid.1"
        },
        "claims": [
          { "path": ["eu.europa.ec.eudi.pid.1", "family_name"] },
          { "path": ["eu.europa.ec.eudi.pid.1", "given_name"] },
          { "path": ["eu.europa.ec.eudi.pid.1", "birth_date"] }
        ]
      }]
    }
  }
}
EOF
      ;;
    pid-sdjwt)
      cat <<EOF
{
  "flow_type": "cross_device",
  "core_flow": {
    "signed_request": true,
    "clientId": "$CLIENT_ID",
    "key": $VERIFIER_KEY,
    "x5c": $VERIFIER_X5C,
    "dcql_query": {
      "credentials": [{
        "id": "eudi_pid_sdjwt",
        "format": "dc+sd-jwt",
        "meta": {
          "vct_values": ["urn:eudi:pid:1"]
        },
        "claims": [
          { "path": ["given_name"] },
          { "path": ["family_name"] }
        ]
      }]
    }
  }
}
EOF
      ;;
    mdl)
      cat <<EOF
{
  "flow_type": "cross_device",
  "core_flow": {
    "signed_request": true,
    "clientId": "$CLIENT_ID",
    "key": $VERIFIER_KEY,
    "x5c": $VERIFIER_X5C,
    "dcql_query": {
      "credentials": [{
        "id": "mdl",
        "format": "mso_mdoc",
        "meta": {
          "doctype_value": "org.iso.18013.5.1.mDL"
        },
        "claims": [
          { "path": ["org.iso.18013.5.1", "family_name"] },
          { "path": ["org.iso.18013.5.1", "given_name"] },
          { "path": ["org.iso.18013.5.1", "birth_date"] }
        ]
      }]
    }
  }
}
EOF
      ;;
    *)
      log_error "Unknown credential type: $type"
      exit 1
      ;;
  esac
}

get_issue_endpoint() {
  local type="$1"
  case "$type" in
    pid-mdoc|mdl)
      echo "mdoc"
      ;;
    pid-sdjwt)
      echo "sdjwt"
      ;;
    *)
      log_error "Unknown credential type: $type"
      exit 1
      ;;
  esac
}

# ============================================================================
# Issue Command
# ============================================================================

do_issue() {
  local type="$1"
  local endpoint=$(get_issue_endpoint "$type")
  local payload=$(get_issue_payload "$type")

  log_info "Issuing $type credential..."

  # Special warning for mDL
  if [ "$type" = "mdl" ]; then
    log_warning "mDL requires a PID credential first!"
    echo "   If wallet shows 'Wallet needs to be activated first with a National ID',"
    echo "   run: ./scripts/eudi-e2e-test.sh issue pid-mdoc"
    echo ""
  fi

  # Call issuer API
  local response=$(curl -s -X POST "${ISSUER_URL}/openid4vc/${endpoint}/issue" \
    -H "Content-Type: application/json" \
    -d "$payload")

  # Check if response looks like an offer URI
  if [[ "$response" != openid-credential-offer://* ]]; then
    log_error "Unexpected response from issuer:"
    echo "$response" | jq . 2>/dev/null || echo "$response"
    exit 1
  fi

  log_success "Credential offer created"
  echo "   Offer: ${response:0:80}..."

  # Send to wallet via ADB
  log_info "Sending offer to wallet..."
  adb shell am start -a android.intent.action.VIEW -d "'$response'" 2>/dev/null

  wait_for_user "accept the credential"

  log_success "Issuance complete for $type"
}

# ============================================================================
# Verify Command
# ============================================================================

do_verify() {
  local type="$1"
  local payload=$(get_verify_payload "$type")

  log_info "Creating verification session for $type..."

  # Create verification session
  local response=$(curl -s -X POST "${VERIFIER_URL}/verification-session/create" \
    -H "Content-Type: application/json" \
    -d "$payload")

  # Extract session ID
  local session_id=$(echo "$response" | jq -r '.sessionId // empty')

  if [ -z "$session_id" ]; then
    log_error "Failed to create verification session:"
    echo "$response" | jq . 2>/dev/null || echo "$response"
    exit 1
  fi

  log_success "Verification session created: $session_id"

  # Build the authorization URL with proper escaping
  # Note: \& is required for ADB shell to not interpret & as background operator
  local client_id_encoded="x509_san_dns%3Averifier2.theaustraliahack.com"
  local request_uri_encoded="https%3A%2F%2Fverifier2.theaustraliahack.com%2Fverification-session%2F${session_id}%2Frequest"

  log_info "Sending verification request to wallet..."
  adb shell am start -a android.intent.action.VIEW \
    -d "openid4vp://authorize?client_id=${client_id_encoded}\&request_uri=${request_uri_encoded}" 2>/dev/null

  wait_for_user "share the credentials"

  # Check verification status
  log_info "Checking verification result..."
  local status_response=$(curl -s "${VERIFIER_URL}/verification-session/${session_id}/info")
  local status=$(echo "$status_response" | jq -r '.status // "UNKNOWN"')

  if [ "$status" = "SUCCESSFUL" ]; then
    log_success "Verification SUCCESSFUL for $type"
    echo ""
    echo "Session details:"
    echo "$status_response" | jq '{status, attempted}'
  else
    log_error "Verification FAILED for $type"
    echo "Status: $status"
    echo ""
    echo "Full response:"
    echo "$status_response" | jq .
    exit 1
  fi
}

# ============================================================================
# E2E Command
# ============================================================================

do_e2e() {
  local type="$1"

  echo "========================================"
  echo "  EUDI E2E Test: $type"
  echo "========================================"
  echo ""

  # For mDL, we need PID first
  if [ "$type" = "mdl" ]; then
    log_warning "mDL requires PID credential first"
    echo ""
    echo "Phase 1: Issue PID mDoc"
    echo "------------------------"
    do_issue "pid-mdoc"
    echo ""
  fi

  echo "Phase $( [ "$type" = "mdl" ] && echo "2" || echo "1" ): Issue $type"
  echo "------------------------"
  do_issue "$type"
  echo ""

  echo "Phase $( [ "$type" = "mdl" ] && echo "3" || echo "2" ): Verify $type"
  echo "------------------------"
  do_verify "$type"
  echo ""

  echo "========================================"
  log_success "E2E test completed successfully!"
  echo "========================================"
}

# ============================================================================
# List Command
# ============================================================================

do_list() {
  echo ""
  echo "Available credential types:"
  echo ""
  echo "  ID          Description                      Format"
  echo "  ----------  -------------------------------  ----------"
  echo "  pid-mdoc    EU Personal ID (mDoc)            mso_mdoc"
  echo "  pid-sdjwt   EU Personal ID (SD-JWT)          dc+sd-jwt"
  echo "  mdl         Mobile Driving License           mso_mdoc"
  echo ""
  echo "Usage:"
  echo "  ./scripts/eudi-e2e-test.sh issue <type>    Issue credential"
  echo "  ./scripts/eudi-e2e-test.sh verify <type>   Verify credential"
  echo "  ./scripts/eudi-e2e-test.sh e2e <type>      Full issue + verify"
  echo ""
  echo "Examples:"
  echo "  ./scripts/eudi-e2e-test.sh issue pid-mdoc"
  echo "  ./scripts/eudi-e2e-test.sh verify pid-sdjwt"
  echo "  ./scripts/eudi-e2e-test.sh e2e mdl"
  echo ""
  echo "Note: mDL requires a PID credential to be present in the wallet first."
  echo ""
}

# ============================================================================
# Main
# ============================================================================

usage() {
  echo "EUDI Wallet E2E Testing Script"
  echo ""
  echo "Usage: $0 <command> [type]"
  echo ""
  echo "Commands:"
  echo "  list              List available credential types"
  echo "  issue <type>      Issue credential to wallet"
  echo "  verify <type>     Verify credential from wallet"
  echo "  e2e <type>        Full issue + verify flow"
  echo ""
  echo "Run '$0 list' for available credential types."
}

main() {
  if [ $# -lt 1 ]; then
    usage
    exit 1
  fi

  local command="$1"
  local type="${2:-}"

  case "$command" in
    list)
      do_list
      ;;
    issue)
      if [ -z "$type" ]; then
        log_error "Missing credential type"
        echo "Run '$0 list' for available types"
        exit 1
      fi
      preflight_checks
      do_issue "$type"
      ;;
    verify)
      if [ -z "$type" ]; then
        log_error "Missing credential type"
        echo "Run '$0 list' for available types"
        exit 1
      fi
      preflight_checks
      do_verify "$type"
      ;;
    e2e)
      if [ -z "$type" ]; then
        log_error "Missing credential type"
        echo "Run '$0 list' for available types"
        exit 1
      fi
      preflight_checks
      do_e2e "$type"
      ;;
    -h|--help|help)
      usage
      ;;
    *)
      log_error "Unknown command: $command"
      usage
      exit 1
      ;;
  esac
}

main "$@"
