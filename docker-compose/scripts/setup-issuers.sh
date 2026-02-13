#!/usr/bin/env bash
#
# setup-issuers.sh — Create/update multi-tenant issuers for 5 countries
#
# Usage: ./setup-issuers.sh [ISSUER_API_BASE]
#   Default ISSUER_API_BASE: http://localhost:7002

set -euo pipefail

API="${1:-http://localhost:7002}"
ADMIN="$API/admin/issuer"

echo "=== Issuer Setup Script ==="
echo "API: $API"
echo ""

# Helper: create issuer, generate certificate, set credentials
create_issuer() {
  local legal_name="$1"
  local country="$2"
  local domain="$3"
  shift 3
  local creds_json="$1"

  echo "--- Creating issuer: $legal_name ($country) ---"

  # Register
  local id
  id=$(curl -sf -X POST "$ADMIN" \
    -H 'Content-Type: application/json' \
    -d "{\"legalName\": \"$legal_name\", \"country\": \"$country\", \"domain\": \"$domain\", \"contactEmail\": \"admin@$domain\"}" \
    | jq -r '.id')

  if [ -z "$id" ] || [ "$id" = "null" ]; then
    echo "  ERROR: Failed to create issuer $legal_name"
    return 1
  fi
  echo "  Created: $id"

  # Generate certificate
  echo "  Generating certificate..."
  curl -sf -X POST "$ADMIN/$id/certificate/generate" > /dev/null
  echo "  Certificate generated"

  # Set credentials
  echo "  Setting credential configurations..."
  curl -sf -X PUT "$ADMIN/$id/credentials" \
    -H 'Content-Type: application/json' \
    -d "$creds_json" > /dev/null
  echo "  Credentials configured"

  echo "  Done: $legal_name"
  echo ""
}

# Helper: update existing issuer credentials
update_issuer() {
  local issuer_id="$1"
  local legal_name="$2"
  local creds_json="$3"

  echo "--- Updating issuer: $legal_name ($issuer_id) ---"

  curl -sf -X PUT "$ADMIN/$issuer_id/credentials" \
    -H 'Content-Type: application/json' \
    -d "$creds_json" > /dev/null
  echo "  Credentials updated"
  echo ""
}

# ===================================================================
# 1. Australia (AU) — PID mDoc, mDL, PID SD-JWT
# ===================================================================
AU_CREDS='{"credentials":[{"configId":"eu.europa.ec.eudi.pid.1","format":"mso_mdoc","doctype":"eu.europa.ec.eudi.pid.1"},{"configId":"urn:eudi:pid:1","format":"dc+sd-jwt","vct":"urn:eudi:pid:1"},{"configId":"org.iso.18013.5.1.mDL","format":"mso_mdoc","doctype":"org.iso.18013.5.1.mDL"}]}'

create_issuer "Australian Digital Identity Office" "AU" "digitalid.gov.au" "$AU_CREDS"

# ===================================================================
# 2. Germany (DE) — PID mDoc, mDL, PID SD-JWT
# ===================================================================
DE_CREDS='{"credentials":[{"configId":"eu.europa.ec.eudi.pid.1","format":"mso_mdoc","doctype":"eu.europa.ec.eudi.pid.1"},{"configId":"urn:eudi:pid:1","format":"dc+sd-jwt","vct":"urn:eudi:pid:1"},{"configId":"org.iso.18013.5.1.mDL","format":"mso_mdoc","doctype":"org.iso.18013.5.1.mDL"}]}'

create_issuer "Bundesdruckerei GmbH" "DE" "bdr.de" "$DE_CREDS"

# ===================================================================
# 3. France (FR) — mDL, PID SD-JWT
# ===================================================================
FR_CREDS='{"credentials":[{"configId":"urn:eudi:pid:1","format":"dc+sd-jwt","vct":"urn:eudi:pid:1"},{"configId":"org.iso.18013.5.1.mDL","format":"mso_mdoc","doctype":"org.iso.18013.5.1.mDL"}]}'

create_issuer "ANTS France" "FR" "ants.gouv.fr" "$FR_CREDS"

# ===================================================================
# 4. Update India (IN) — add mDL to existing credentials
# ===================================================================
echo "--- Looking up existing India issuer ---"
IN_ID=$(curl -sf "$ADMIN" | jq -r '.[] | select(.country == "IN" and .status == "ACTIVE") | .id' | head -1)

if [ -n "$IN_ID" ] && [ "$IN_ID" != "null" ]; then
  IN_CREDS='{"credentials":[{"configId":"urn:eudi:pid:1","format":"dc+sd-jwt","vct":"urn:eudi:pid:1"},{"configId":"org.iso.18013.5.1.mDL","format":"mso_mdoc","doctype":"org.iso.18013.5.1.mDL"}]}'
  update_issuer "$IN_ID" "India issuer" "$IN_CREDS"
else
  echo "  No existing IN issuer found — creating new one"
  IN_CREDS='{"credentials":[{"configId":"urn:eudi:pid:1","format":"dc+sd-jwt","vct":"urn:eudi:pid:1"},{"configId":"org.iso.18013.5.1.mDL","format":"mso_mdoc","doctype":"org.iso.18013.5.1.mDL"}]}'
  IN_CREDS='{"credentials":[{"configId":"urn:eudi:pid:1","format":"dc+sd-jwt","vct":"urn:eudi:pid:1"},{"configId":"org.iso.18013.5.1.mDL","format":"mso_mdoc","doctype":"org.iso.18013.5.1.mDL"}]}'
  create_issuer "Unique Identification Authority of India" "IN" "uidai.gov.in" "$IN_CREDS"
fi

# ===================================================================
# 5. Update Singapore (SG) — replace with PID mDoc + PWA
# ===================================================================
echo "--- Looking up existing Singapore issuer ---"
SG_ID=$(curl -sf "$ADMIN" | jq -r '.[] | select(.country == "SG" and .status == "ACTIVE") | .id' | head -1)

if [ -n "$SG_ID" ] && [ "$SG_ID" != "null" ]; then
  SG_CREDS='{"credentials":[{"configId":"eu.europa.ec.eudi.pid.1","format":"mso_mdoc","doctype":"eu.europa.ec.eudi.pid.1"},{"configId":"PaymentWalletAttestation","format":"dc+sd-jwt","vct":"PaymentWalletAttestation"}]}'
  update_issuer "$SG_ID" "Singapore issuer" "$SG_CREDS"
else
  echo "  No existing SG issuer found — creating new one"
  SG_CREDS='{"credentials":[{"configId":"eu.europa.ec.eudi.pid.1","format":"mso_mdoc","doctype":"eu.europa.ec.eudi.pid.1"},{"configId":"PaymentWalletAttestation","format":"dc+sd-jwt","vct":"PaymentWalletAttestation"}]}'
  create_issuer "Government Technology Agency" "SG" "tech.gov.sg" "$SG_CREDS"
fi

# ===================================================================
# Summary
# ===================================================================
echo "=== Setup Complete ==="
echo ""
echo "Registered issuers:"
curl -sf "$ADMIN" | jq -r '.[] | select(.status == "ACTIVE") | "  \(.country) | \(.legalName) | \(.id) | creds: \(.credentialCount)"'
echo ""
echo "Done!"
