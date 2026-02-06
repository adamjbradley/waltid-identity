# Payment Wallet Attestation (PWA)

Payment Wallet Attestation (PWA) enables Payment Service Providers (PSPs) to issue credentials that bind payment funding sources (cards, bank accounts) to EUDI wallets, following [EWC RFC007](https://github.com/EWC-consortium/eudi-wallet-rfcs/blob/main/ewc-rfc007-issue-payment-wallet-attestation.md).

> **IMPORTANT: PWA is DISABLED by default.** This feature requires explicit opt-in and has zero impact on existing credential issuance flows when disabled.

## Overview

PWA allows users to:
1. Authenticate with their PSP (bank, card issuer)
2. Select which payment instruments to bind to their wallet
3. Receive a `PaymentWalletAttestation` credential containing funding source details
4. Use this credential for Strong Customer Authentication (SCA) in payment flows

## Default State

**PWA is DISABLED by default.** The following table shows where the default is configured:

| Location | Default Value | Purpose |
|----------|---------------|---------|
| `config/pwa.conf` | `enabled = false` | Base configuration |
| `docker-compose.yaml` | `${PWA_ENABLED:-false}` | Docker environment (defaults to `false`) |
| `.env.local` | Does not exist | Local overrides (gitignored) |
| `.env.local.example` | `# PWA_ENABLED=true` | Template only (commented out) |

When disabled:
- `PaymentWalletAttestation` credential type is NOT registered
- Token responses do NOT include `authorization_details`
- PSP adapter is NOT instantiated
- Zero impact on existing credential issuance flows

## Quick Start

### Enable PWA Feature

**Option 1: Environment Variable (Recommended)**
```bash
# In docker-compose directory
echo "PWA_ENABLED=true" >> .env.local
docker compose --profile identity up -d issuer-api
```

**Option 2: Config File**
```hocon
# In config/pwa.conf
enabled = true
```

**Option 3: Features Config**
```hocon
# In config/_features.conf
enabledFeatures = [
    pwa
]
```

### Disable PWA Feature

To disable after enabling:

```bash
# Option 1: Remove or comment out in .env.local
# PWA_ENABLED=true

# Option 2: Explicitly set to false
echo "PWA_ENABLED=false" >> .env.local

# Option 3: Delete .env.local entirely
rm docker-compose/.env.local

# Then restart the service
docker compose --profile identity up -d issuer-api
```

### Verify PWA is Enabled

```bash
curl http://localhost:7002/.well-known/openid-credential-issuer | jq '.credential_configurations_supported.PaymentWalletAttestation'
```

You should see the PaymentWalletAttestation credential configuration.

## Configuration

### pwa.conf

```hocon
# Master switch - overridable via PWA_ENABLED environment variable
enabled = false
enabled = ${?PWA_ENABLED}

# PSP adapter: "mock" for testing, or fully qualified class name for production
pspAdapterType = "mock"
pspAdapterType = ${?PWA_PSP_ADAPTER}

# Credential configuration ID
credentialConfigurationId = "PaymentWalletAttestation"
```

### Environment Variables

| Variable | Default | Description |
|----------|---------|-------------|
| `PWA_ENABLED` | `false` | Enable/disable PWA feature |
| `PWA_PSP_ADAPTER` | `mock` | PSP adapter implementation |

## Architecture

```
┌─────────────────┐     ┌─────────────────┐     ┌─────────────────┐
│   EUDI Wallet   │────▶│   Issuer API    │────▶│   PSP Adapter   │
└─────────────────┘     └─────────────────┘     └─────────────────┘
                               │                        │
                               │                        ▼
                               │                ┌─────────────────┐
                               │                │  PSP Backend    │
                               │                │  (Cards/Accts)  │
                               │                └─────────────────┘
                               ▼
                        ┌─────────────────┐
                        │  SD-JWT VC with │
                        │  funding_source │
                        └─────────────────┘
```

## Token Response with Authorization Details

When PWA is enabled (feature is on) AND the session has resolved funding sources, the token response includes `authorization_details`:

```json
{
  "access_token": "...",
  "token_type": "bearer",
  "expires_in": 864000,
  "c_nonce": "...",
  "authorization_details": [
    {
      "type": "openid_credential",
      "credential_configuration_id": "PaymentWalletAttestation",
      "credential_identifiers": [
        "pwa_visa_1234",
        "pwa_mastercard_5678",
        "pwa_sepa_9012"
      ]
    }
  ]
}
```

The wallet can then request credentials using any of the `credential_identifiers`.

## Credential Format

The `PaymentWalletAttestation` credential uses `dc+sd-jwt` format with the following claims:

```json
{
  "vct": "PaymentWalletAttestation",
  "funding_source": {
    "type": "card",
    "pan_last_four": "1234",
    "iin": "411111",
    "scheme": "visa",
    "currency": "EUR",
    "icon": "https://example.com/visa-icon.png"
  }
}
```

### Funding Source Types

| Type | Required Fields | Optional Fields |
|------|-----------------|-----------------|
| `card` | `type`, `pan_last_four` | `iin`, `scheme`, `currency`, `icon`, `alias_id` |
| `account` | `type`, `iban_last_four` | `bic`, `scheme`, `currency`, `icon`, `alias_id` |

## PSP Adapter Interface

To integrate with your payment infrastructure, implement the `PspAdapter` interface:

```kotlin
interface PspAdapter {
    suspend fun resolveFundingSources(
        subject: String,
        attestationIssuer: String? = null
    ): List<FundingSource>

    suspend fun validateFundingSource(fundingSourceId: String): Boolean

    suspend fun getFundingSource(fundingSourceId: String): FundingSource?
}
```

See [Implementing a Custom PSP Adapter](./custom-psp-adapter.md) for details.

## Testing with Mock Adapter

The `MockPspAdapter` provides sample funding sources for testing:

| Credential Identifier | Type | Scheme | Last 4 |
|-----------------------|------|--------|--------|
| `pwa_visa_1234` | Card | Visa | 1234 |
| `pwa_mastercard_5678` | Card | Mastercard | 5678 |
| `pwa_sepa_9012` | Account | SEPA | 9012 |

## Security Considerations

1. **Authentication Required**: PWA issuance requires user authentication with the PSP
2. **Funding Source Validation**: Always validate funding sources before issuance
3. **Selective Disclosure**: Use SD-JWT to allow selective disclosure of funding source details
4. **Token Binding**: Use DPoP for token binding when available

## Related Documentation

- [EWC RFC007: Issue Payment Wallet Attestation](https://github.com/EWC-consortium/eudi-wallet-rfcs/blob/main/ewc-rfc007-issue-payment-wallet-attestation.md)
- [EWC RFC008: Payment Transaction with Payment Wallet Attestation](https://github.com/EWC-consortium/eudi-wallet-rfcs/blob/main/ewc-rfc008-payment-transaction-with-pwa.md)
- [OpenID4VCI Specification](https://openid.net/specs/openid-4-verifiable-credential-issuance-1_0.html)
