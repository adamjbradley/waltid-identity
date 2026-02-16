# Multi-Tenant Wallet Awareness

## Overview

When `MT_WALLET_ENABLED=true`, the web wallet discovers and displays issuer/verifier identity from the protocol itself (OpenID metadata, X.509 certificates, client_id). When disabled (default), the wallet behaves identically to the standard single-tenant experience.

## Quick Start

### Enable

```bash
# In docker-compose/.env
MT_WALLET_ENABLED=true

# Restart wallet
docker compose --profile identity up -d waltid-demo-wallet
```

### Disable (default)

```bash
# In docker-compose/.env (or remove the line entirely)
MT_WALLET_ENABLED=false
```

## What Changes

| Behavior | Disabled (default) | Enabled |
|----------|-------------------|---------|
| Issuance: Issuer display | Hostname only (`from example.com`) | Issuer name from OpenID metadata, hostname below |
| Presentation: Verifier display | Nothing shown | RP domain from client_id, client_id badge |
| Portal hint params | Ignored even if present in URL | Displayed as enrichment alongside protocol data |
| Network requests | No additional requests | No additional requests (uses existing metadata) |

## Prerequisites

For full MT experience, also enable:
- `ISSUER_REGISTRAR_ENABLED=true` -- Portal tenant dropdown for issuance
- `RP_REGISTRAR_ENABLED=true` -- Portal RP dropdown for verification
- `TRUST_LISTS_ENABLED=true` -- Trust validation badges in wallet

## Identity Resolution (when enabled)

**Issuance (priority order):**
1. OpenID metadata `display[0].name` -- from tenant-scoped credential issuer metadata
2. Portal hint `issuerName` -- passed via URL from portal (bonus enrichment)
3. Hostname fallback -- extracted from credential_issuer URL (same as disabled mode)

**Verification (priority order):**
1. `client_id` domain -- parsed from `x509_san_dns:{domain}` pattern
2. Portal hint `rpName` -- passed via URL from portal (bonus enrichment)
3. `verifierHost` -- extracted from response_uri hostname
4. "Unknown verifier" -- when nothing is available

## Configuration Reference

| Setting | Default | Description |
|---------|---------|-------------|
| `MT_WALLET_ENABLED` | `false` | Enable MT issuer/verifier identity in wallet |
| `ISSUER_REGISTRAR_ENABLED` | `false` | Enable issuer tenant management in portal |
| `RP_REGISTRAR_ENABLED` | `false` | Enable RP onboarding in portal |
| `TRUST_LISTS_ENABLED` | `false` | Enable ETSI trust list validation |

## Technical Details

- **Nuxt runtime config:** `NUXT_PUBLIC_MT_WALLET_ENABLED` maps to `useRuntimeConfig().public.mtWalletEnabled`
- **Composable:** `useMtWallet()` from `libs/composables/mtWallet.ts` provides reactive flag
- **Gating pattern:** All new UI wrapped in `v-if="mtWalletEnabled"` with `v-else` preserving current behavior
- **No new API calls:** Uses existing OpenID metadata already fetched by issuance composable
