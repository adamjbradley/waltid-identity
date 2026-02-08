# Sandbox Credentials

The Verify API includes pre-configured sandbox credentials for development and testing. These credentials are automatically created when the API starts and work immediately without any setup.

## Sandbox API Keys

| Environment | API Key | Use Case |
|-------------|---------|----------|
| **Test** | `vfy_test_sandbox_demo_key_12345678` | Development, integration testing |
| **Live** | `vfy_live_sandbox_demo_key_12345678` | Production-like testing |

## Sandbox Organization

The sandbox credentials belong to a demo organization called "Sandbox Demo" with the following characteristics:

- **Name**: Sandbox Demo
- **Templates**: Access to all system templates
- **Rate Limit**: 1000 requests/hour (generous for testing)
- **Expiration**: Never expires

## Available Templates

The sandbox organization has access to all system templates:

| Template | Description | Use Case |
|----------|-------------|----------|
| `age_check` | Verify user is 18+ | Age-gated content |
| `full_kyc` | Complete identity verification | Full KYC onboarding |
| `basic_identity` | Name verification only | Simple identity checks |
| `mdl_verification` | Mobile driving license | License verification |
| `transaction_binding` | Payment wallet attestation | Payment authorization |

## Quick Start Examples

### JavaScript/TypeScript

```typescript
import { VerifyClient } from '@waltid/verify-sdk';

const client = new VerifyClient({
  apiKey: 'vfy_test_sandbox_demo_key_12345678',
  baseUrl: 'http://localhost:7010'
});

const session = await client.verifyIdentity({
  template: 'age_check'
});

console.log('QR Code:', session.qrCodeUrl);
```

### Swift (iOS)

```swift
import WaltIDVerifySDK

let config = VerifyConfig(
    apiKey: "vfy_test_sandbox_demo_key_12345678",
    baseURL: URL(string: "http://localhost:7010")!
)
let client = VerifyClient(config: config)

let session = try await client.verifyIdentity(
    VerificationRequest(template: "age_check")
)
print("QR Code: \(session.qrCodeUrl)")
```

### Kotlin (Android)

```kotlin
import id.walt.verify.sdk.*

val client = VerifyClient(VerifyConfig(
    apiKey = "vfy_test_sandbox_demo_key_12345678",
    baseUrl = "http://10.0.2.2:7010"  // Android emulator localhost
))

val session = client.verifyIdentity(
    VerificationRequest(template = "age_check")
)
println("QR Code: ${session.qrCodeUrl}")
```

### cURL

```bash
# Create a verification session
curl -X POST http://localhost:7010/v1/verify/identity \
  -H "Authorization: Bearer vfy_test_sandbox_demo_key_12345678" \
  -H "Content-Type: application/json" \
  -d '{"template": "age_check"}'

# Get session status
curl http://localhost:7010/v1/sessions/vs_xxx \
  -H "Authorization: Bearer vfy_test_sandbox_demo_key_12345678"
```

## Widget SDK

For the Widget SDK, get a client token from your backend:

```javascript
// Backend (Node.js)
const response = await fetch('http://localhost:7010/v1/widget/tokens', {
  method: 'POST',
  headers: {
    'Authorization': 'Bearer vfy_test_sandbox_demo_key_12345678',
    'Content-Type': 'application/json'
  },
  body: JSON.stringify({ expires_in: 900 })
});

const { client_token } = await response.json();

// Frontend
WaltVerify.init({
  clientToken: client_token
});

WaltVerify.verifyAge({ minAge: 18 });
```

## Test vs Live Environment

| Feature | Test (`vfy_test_*`) | Live (`vfy_live_*`) |
|---------|---------------------|---------------------|
| Session prefix | `vs_test_*` | `vs_live_*` |
| Usage tracking | Logged but not billed | Logged and billed |
| Webhooks | Full functionality | Full functionality |
| Rate limits | 1000/hour | 1000/hour |

Both keys work identically for the sandbox organization. The distinction exists to help test your environment detection logic.

## Security Notice

These sandbox credentials are for **development and testing only**. They are:

- **Publicly documented** - Anyone can use them
- **Shared** - No isolation between users
- **Not for production** - Do not use in production applications

For production use, create your own organization and generate unique API keys through the developer portal.

## Troubleshooting

### "Invalid API key"

Ensure you're using the exact key: `vfy_test_sandbox_demo_key_12345678`

Check for:
- Extra spaces or newlines
- Incorrect prefix (`vfy_test_` vs `vfy_live_`)
- Typos in the key

### "Template not found"

The sandbox has access to system templates only. Custom templates must be created through the API.

### "Rate limit exceeded"

The sandbox has a generous 1000 requests/hour limit. If you hit this:
- Wait for the rate limit to reset (1 hour)
- Use shorter polling intervals
- Batch requests where possible

## Related Documentation

- [Quick Start](./quickstart.md) - Get verified in 5 minutes
- [SDK Integration](./sdk-integration.md) - Detailed SDK documentation
- [API Reference](./api-reference.md) - REST API documentation
