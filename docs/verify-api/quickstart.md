# Quick Start: Get Verified in 5 Minutes

This guide gets you from zero to a working identity verification in under 5 minutes.

## Prerequisites

- Node.js 18+ (for JavaScript) or Xcode 15+ (for iOS) or Android Studio (for Android)
- Verify API running locally (or access to a deployed instance)

## Sandbox Credentials

For development and testing, use the pre-configured sandbox credentials that work immediately without any setup:

| Environment | API Key |
|-------------|---------|
| Test | `vfy_test_sandbox_demo_key_12345678` |
| Live | `vfy_live_sandbox_demo_key_12345678` |

These are automatically created when the Verify API starts. See [Sandbox Credentials](./sandbox-credentials.md) for details.

## Step 1: Install the SDK

Choose your platform:

**JavaScript/TypeScript:**
```bash
npm install @waltid/verify-sdk
```

**iOS (Swift Package Manager):**
```swift
// Package.swift
dependencies: [
    .package(url: "https://github.com/walt-id/waltid-verify-sdk-ios", from: "1.0.0")
]
```

**Android (Gradle):**
```kotlin
// build.gradle.kts
dependencies {
    implementation("id.walt:waltid-verify-sdk-android:1.0.0-SNAPSHOT")
}
```

## Step 2: Initialize the Client

**JavaScript/TypeScript:**
```typescript
import { VerifyClient } from '@waltid/verify-sdk';

const client = new VerifyClient({
  apiKey: 'vfy_test_sandbox_demo_key_12345678'  // Sandbox demo key
});
```

**Swift:**
```swift
import WaltIDVerifySDK

let client = VerifyClient(config: VerifyConfig(
    apiKey: "vfy_test_sandbox_demo_key_12345678"  // Sandbox demo key
))
```

**Kotlin:**
```kotlin
import id.walt.verify.sdk.*

val client = VerifyClient(VerifyConfig(
    apiKey = "vfy_test_sandbox_demo_key_12345678"  // Sandbox demo key
))
```

## Step 3: Create a Verification Session

**JavaScript/TypeScript:**
```typescript
const session = await client.verifyIdentity({
  template: 'age_check'
});

console.log('Session ID:', session.sessionId);
console.log('QR Code URL:', session.qrCodeUrl);
console.log('Deep Link:', session.deepLink);
```

**Swift:**
```swift
let session = try await client.verifyIdentity(
    VerificationRequest(template: "age_check")
)
print("QR Code: \(session.qrCodeUrl)")
```

**Kotlin:**
```kotlin
val session = client.verifyIdentity(
    VerificationRequest(template = "age_check")
)
println("QR Code: ${session.qrCodeUrl}")
```

## Step 4: Display the QR Code

The `qrCodeUrl` returns a ready-to-display PNG image:

**HTML:**
```html
<img src="${session.qrCodeUrl}" alt="Scan to verify" />
```

**React:**
```tsx
<img src={session.qrCodeUrl} alt="Scan to verify" />
```

**SwiftUI:**
```swift
AsyncImage(url: URL(string: session.qrCodeUrl)) { image in
    image.resizable().aspectRatio(contentMode: .fit)
} placeholder: {
    ProgressView()
}
.frame(width: 250, height: 250)
```

**Jetpack Compose:**
```kotlin
AsyncImage(
    model = session.qrCodeUrl,
    contentDescription = "Verification QR Code",
    modifier = Modifier.size(250.dp)
)
```

## Step 5: Wait for the Result

**Option A: Polling (Simple)**

**JavaScript/TypeScript:**
```typescript
const result = await client.pollSession(session.sessionId, {
  timeout: 300000, // 5 minutes
  interval: 2000   // Check every 2 seconds
});

if (result.status === 'verified') {
  console.log('User is verified!');
  console.log('Answers:', result.result?.answers);
}
```

**Swift:**
```swift
let result = try await client.waitForSession(
    session.sessionId,
    timeout: 300
)

if result.status == "verified" {
    print("User is verified!")
}
```

**Kotlin:**
```kotlin
val result = client.pollSession(
    sessionId = session.sessionId,
    timeoutMs = 300_000
)

if (result.isVerified) {
    println("User is verified!")
}
```

**Option B: Webhooks (Production)**

Register a webhook to receive instant notifications:

```typescript
// When creating the session
const session = await client.verifyIdentity({
  template: 'age_check',
  callbackUrl: 'https://your-domain.com/api/verify/callback'
});

// In your webhook handler
app.post('/api/verify/callback', (req, res) => {
  const { sessionId, status, credentials } = req.body;

  if (status === 'verified') {
    // Process the verified user
  }

  res.sendStatus(200);
});
```

## Step 6: Handle the Result

**Session Status Values:**

| Status | Description |
|--------|-------------|
| `pending` | Waiting for user to scan and verify |
| `verified` | User successfully verified |
| `failed` | Verification failed (invalid credential, user cancelled) |
| `expired` | Session timed out (default: 10 minutes) |

**Complete Example (TypeScript):**

```typescript
import { VerifyClient } from '@waltid/verify-sdk';

async function verifyUser() {
  const client = new VerifyClient({
    apiKey: process.env.VERIFY_API_KEY!
  });

  // Create session
  const session = await client.verifyIdentity({
    template: 'age_check',
    metadata: { userId: 'user_123' }
  });

  console.log('Please scan the QR code:');
  console.log(session.qrCodeUrl);

  // Wait for result
  try {
    const result = await client.pollSession(session.sessionId);

    switch (result.status) {
      case 'verified':
        console.log('Verification successful!');
        console.log('Is adult:', result.result?.answers?.is_adult);
        break;
      case 'failed':
        console.log('Verification failed');
        break;
      case 'expired':
        console.log('Session expired');
        break;
    }
  } catch (error) {
    console.error('Error:', error);
  }
}

verifyUser();
```

## Same-Device Flow

For mobile apps, use the deep link to open the wallet directly:

**Swift:**
```swift
if let url = URL(string: session.deepLink) {
    await UIApplication.shared.open(url)
}
```

**Kotlin:**
```kotlin
val intent = Intent(Intent.ACTION_VIEW, Uri.parse(session.deepLink))
startActivity(intent)
```

**JavaScript (React Native):**
```typescript
import { Linking } from 'react-native';
await Linking.openURL(session.deepLink);
```

## Using Templates

Pre-built templates for common use cases:

| Template | Use Case | Required Claims |
|----------|----------|-----------------|
| `age_check` | Age verification (18+) | `age_over_18` |
| `age_over_21` | US alcohol age | `age_over_21` |
| `kyc_basic` | Basic identity | `given_name`, `family_name`, `birth_date` |
| `kyc_full` | Full KYC | All PID claims |
| `payment_authorization` | Payment binding | PWA claims |

## Custom Claims

For specific claim requirements, use a custom DCQL query:

```typescript
const session = await client.verifyIdentity({
  dcql: {
    credentials: [{
      id: 'pid',
      format: 'dc+sd-jwt',
      meta: { vct_values: ['urn:eudi:pid:1'] },
      claims: [
        { path: ['given_name'] },
        { path: ['family_name'] },
        { path: ['nationality'] }
      ]
    }]
  }
});
```

## Error Handling

```typescript
import { VerifyClient, VerifyError, PollingTimeoutError } from '@waltid/verify-sdk';

try {
  const result = await client.pollSession(sessionId);
} catch (error) {
  if (error instanceof PollingTimeoutError) {
    console.log('User did not complete verification in time');
  } else if (error instanceof VerifyError) {
    console.log('API Error:', error.message);
    console.log('Status Code:', error.statusCode);
  }
}
```

## Next Steps

- [SDK Integration Guide](./sdk-integration.md) - Detailed SDK documentation
- [Webhook Integration](./webhook-integration.md) - Production-ready async flow
- [Example Scenarios](./example-scenarios.md) - All 7 implementation patterns
- [API Reference](./api-reference.md) - Direct REST API usage

## Test Credentials

In test mode (`vfy_test_*` keys), you can use the sandbox wallet with pre-configured test credentials:

1. Download the EUDI Reference Wallet
2. Import test PID credentials
3. Scan QR codes from test sessions

See the [EUDI Testing Guide](../eudi/getting-started.md) for detailed instructions.
