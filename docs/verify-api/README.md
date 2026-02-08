# Verify API Documentation

The Verify API is a multi-tenant SaaS gateway that simplifies digital identity verification for Relying Parties (RPs). It transforms what would be a 6-12 month integration effort into a single API call.

## Key Benefits

- **30-minute integration** instead of months of protocol development
- **Template-based verification** for common use cases (age check, KYC, payment authorization)
- **Raw credential access** when you need full credential data
- **Orchestration support** for multi-step verification flows
- **Multi-tenant SaaS** with logical isolation and usage-based billing

## Documentation

| Guide | Description |
|-------|-------------|
| [Quick Start](./quickstart.md) | Get verified in 5 minutes |
| [SDK Integration](./sdk-integration.md) | JavaScript, iOS, and Android SDK guides |
| [Webhook Integration](./webhook-integration.md) | Async notification flow with signature verification |
| [API Reference](./api-reference.md) | Direct REST API usage for custom integrations |
| [Example Scenarios](./example-scenarios.md) | All 7 implementation patterns |

## SDKs

| Platform | Package | Documentation |
|----------|---------|---------------|
| JavaScript/TypeScript | `@waltid/verify-sdk` | [waltid-verify-sdk-js](../../waltid-verify-sdk-js/README.md) |
| iOS (Swift) | `WaltIDVerifySDK` | [waltid-verify-sdk-ios](../../waltid-verify-sdk-ios/README.md) |
| Android (Kotlin) | `id.walt:waltid-verify-sdk-android` | [waltid-verify-sdk-android](../../waltid-verify-sdk-android/README.md) |

## Example Applications

| Platform | Description | Location |
|----------|-------------|----------|
| Next.js Web | React-based web integration | [examples/rp-web-nextjs](../../examples/rp-web-nextjs/) |
| iOS | SwiftUI mobile app | [examples/rp-ios](../../examples/rp-ios/) |
| Android | Jetpack Compose app | [examples/rp-android](../../examples/rp-android/) |

## Architecture Overview

```
+------------------+     +------------------+     +------------------+
|   Your App       |---->|   Verify API     |---->|   User's Wallet  |
|   (RP)           |<----|   Gateway        |<----|   (EUDI, etc.)   |
+------------------+     +------------------+     +------------------+
                                |
                                v
                         +------------------+
                         | verifier-api2    |
                         | (OID4VP Engine)  |
                         +------------------+
```

The Verify API acts as a gateway layer that:
1. Accepts simple verification requests from your application
2. Translates them into OID4VP protocol flows
3. Communicates with user wallets
4. Returns structured verification results

## Response Modes

### `answers` Mode (Default)

Returns a flat map of field names to values, as defined in the template:

```json
{
  "status": "verified",
  "result": {
    "answers": {
      "full_name": "John Doe",
      "date_of_birth": "1990-01-15",
      "is_adult": true
    }
  }
}
```

### `raw_credentials` Mode

Returns the full credential data for advanced use cases:

```json
{
  "status": "verified",
  "result": {
    "credentials": [{
      "format": "dc+sd-jwt",
      "vct": "urn:eudi:pid:1",
      "disclosedClaims": {
        "given_name": "John",
        "family_name": "Doe",
        "birth_date": "1990-01-15"
      }
    }]
  }
}
```

## Pre-built Templates

| Template | Description | Claims |
|----------|-------------|--------|
| `age_check` | Verify user is 18+ | `age_over_18` |
| `kyc_basic` | Basic identity verification | `given_name`, `family_name`, `birth_date` |
| `kyc_full` | Full KYC with address | All PID claims |
| `payment_authorization` | Payment binding | PWA claims |

## Quick Example

```typescript
import { VerifyClient } from '@waltid/verify-sdk';

const client = new VerifyClient({
  apiKey: 'vfy_live_xxx'
});

// Start verification
const session = await client.verifyIdentity({
  template: 'age_check'
});

// Display QR code to user
console.log('Scan:', session.qrCodeUrl);

// Wait for result
const result = await client.pollSession(session.sessionId);

if (result.status === 'verified') {
  console.log('User is verified!');
}
```

## Service Ports

| Service | Port | Purpose |
|---------|------|---------|
| verify-api | 7010 | API Gateway |
| verifier-api2 | 7004 | OID4VP Engine |

## Support

- **GitHub Issues**: [github.com/walt-id/waltid-identity](https://github.com/walt-id/waltid-identity)
- **Email**: support@walt.id

## License

Apache License 2.0
