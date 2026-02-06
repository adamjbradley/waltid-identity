# Verify API SDKs

Official SDKs for integrating with the Verify API multi-tenant verification gateway.

## Available SDKs

| Platform | Package | Installation |
|----------|---------|--------------|
| JavaScript/TypeScript | `@waltid/verify-sdk` | `npm install @waltid/verify-sdk` |
| iOS (Swift) | `WaltIDVerifySDK` | Swift Package Manager |
| Android (Kotlin) | `id.walt.verify:sdk` | Gradle/Maven |

## Quick Start

### 1. Get API Key

Sign up at the Verify API portal to get your API key:
- Test keys: `vfy_test_xxx` (sandbox, no billing)
- Live keys: `vfy_live_xxx` (production)

### 2. Install SDK

**JavaScript/TypeScript:**
```bash
npm install @waltid/verify-sdk
```

**iOS (Swift Package Manager):**
```swift
dependencies: [
    .package(url: "https://github.com/walt-id/waltid-verify-sdk-ios", from: "1.0.0")
]
```

**Android (Gradle):**
```kotlin
implementation("id.walt.verify:sdk:1.0.0")
```

### 3. Verify Identity

**TypeScript:**
```typescript
import { VerifyClient } from '@waltid/verify-sdk';

const client = new VerifyClient({ apiKey: 'vfy_test_xxx' });

// Start verification
const session = await client.verifyIdentity({
  template: 'age_check'
});

// Display QR code to user
console.log('Scan QR:', session.qrCodeUrl);

// Wait for result
const result = await client.pollSession(session.sessionId);
console.log('Verified:', result.status === 'verified');
```

**Swift:**
```swift
let client = VerifyClient(config: VerifyConfig(apiKey: "vfy_test_xxx"))

let session = try await client.verifyIdentity(
    VerificationRequest(template: "age_check")
)
print("QR Code: \(session.qrCodeUrl)")
```

**Kotlin:**
```kotlin
val client = VerifyClient(VerifyConfig(apiKey = "vfy_test_xxx"))

val session = client.verifyIdentity(
    VerificationRequest(template = "age_check")
)
println("QR Code: ${session.qrCodeUrl}")
```

## Common Use Cases

- [Age Verification](./examples/age-verification.md)
- [KYC Onboarding](./examples/kyc-onboarding.md)
- [Payment Authorization](./examples/payment-auth.md)
- [Webhook Integration](./examples/webhooks.md)

## SDK Features

All SDKs provide:

- **Session Management**: Create, poll, and manage verification sessions
- **Template Support**: Use pre-configured verification templates
- **Custom Requests**: Build custom DCQL queries for specific credential requirements
- **Webhook Handling**: Utilities for validating and processing webhook events
- **Error Handling**: Typed errors with detailed information
- **TypeScript Types**: Full type definitions for TypeScript users

## API Reference

### VerifyClient

The main client class for interacting with the Verify API.

#### Constructor

```typescript
new VerifyClient(config: VerifyConfig)
```

| Parameter | Type | Description |
|-----------|------|-------------|
| `config.apiKey` | `string` | Your API key (required) |
| `config.baseUrl` | `string` | API base URL (optional, defaults to production) |
| `config.timeout` | `number` | Request timeout in ms (optional, defaults to 30000) |

#### Methods

| Method | Description |
|--------|-------------|
| `verifyIdentity(request)` | Start a new verification session |
| `getSession(sessionId)` | Get session status and results |
| `pollSession(sessionId, options)` | Poll until session completes |
| `listTemplates()` | List available verification templates |
| `cancelSession(sessionId)` | Cancel an active session |

### Types

```typescript
interface VerificationRequest {
  template?: string;           // Template ID to use
  dcql?: DCQLQuery;           // Custom DCQL query
  callbackUrl?: string;       // Webhook URL for results
  metadata?: Record<string, string>; // Custom metadata
  expiresIn?: number;         // Session expiry in seconds
}

interface VerificationSession {
  sessionId: string;
  status: 'pending' | 'verified' | 'failed' | 'expired';
  qrCodeUrl: string;
  deepLink: string;
  expiresAt: string;
}

interface VerificationResult {
  sessionId: string;
  status: 'verified' | 'failed' | 'expired';
  credentials?: VerifiedCredential[];
  error?: VerificationError;
  completedAt: string;
}
```

## Error Handling

All SDKs use consistent error types:

```typescript
try {
  const session = await client.verifyIdentity({ template: 'invalid' });
} catch (error) {
  if (error instanceof VerifyApiError) {
    console.error('API Error:', error.code, error.message);
    // error.code: 'invalid_template' | 'rate_limited' | 'unauthorized' | ...
  }
}
```

| Error Code | Description |
|------------|-------------|
| `unauthorized` | Invalid or missing API key |
| `invalid_template` | Template not found or not authorized |
| `rate_limited` | Too many requests |
| `session_not_found` | Session ID does not exist |
| `session_expired` | Session has expired |

## Rate Limits

| Plan | Requests/min | Sessions/month |
|------|--------------|----------------|
| Free | 10 | 100 |
| Starter | 60 | 1,000 |
| Pro | 300 | 10,000 |
| Enterprise | Custom | Custom |

## Support

- **Documentation**: [docs.waltid.com](https://docs.waltid.com)
- **GitHub Issues**: [github.com/walt-id/waltid-identity](https://github.com/walt-id/waltid-identity)
- **Email**: support@walt.id
