# SDK Integration Guide

This guide covers detailed integration with the Verify API SDKs for JavaScript/TypeScript, iOS (Swift), and Android (Kotlin).

## Table of Contents

1. [JavaScript/TypeScript SDK](#javascripttypescript-sdk)
2. [iOS Swift SDK](#ios-swift-sdk)
3. [Android Kotlin SDK](#android-kotlin-sdk)
4. [Cross-Platform Patterns](#cross-platform-patterns)

---

## JavaScript/TypeScript SDK

### Installation

```bash
npm install @waltid/verify-sdk
# or
yarn add @waltid/verify-sdk
# or
pnpm add @waltid/verify-sdk
```

### Configuration

```typescript
import { VerifyClient } from '@waltid/verify-sdk';

const client = new VerifyClient({
  apiKey: process.env.VERIFY_API_KEY!,
  baseUrl: 'https://verify.yourdomain.com', // Optional, defaults to production
  timeout: 30000 // Request timeout in ms
});
```

### Basic Verification

```typescript
// Create verification session
const session = await client.verifyIdentity({
  template: 'kyc_basic',
  responseMode: 'answers', // or 'raw_credentials'
  metadata: {
    userId: 'user_123',
    orderId: 'order_456'
  }
});

// Session response
console.log(session.sessionId);  // vs_abc123
console.log(session.qrCodeUrl);  // URL to QR code image
console.log(session.qrCodeData); // Raw OID4VP URL
console.log(session.deepLink);   // Mobile deep link
console.log(session.expiresAt);  // Expiration timestamp
```

### Polling for Results

```typescript
// Simple polling with timeout
const result = await client.pollSession(session.sessionId, {
  timeout: 300000,  // 5 minutes
  interval: 2000    // Check every 2 seconds
});

// Using async iterator for status updates
for await (const status of client.pollSessionIterator(session.sessionId)) {
  console.log('Current status:', status.status);
  updateUI(status);

  if (status.status !== 'pending') break;
}
```

### Getting Session Status

```typescript
const status = await client.getSession(session.sessionId);

switch (status.status) {
  case 'pending':
    console.log('Waiting for user...');
    break;
  case 'verified':
    console.log('Verified!', status.result);
    break;
  case 'failed':
    console.log('Failed');
    break;
  case 'expired':
    console.log('Session expired');
    break;
}
```

### React Integration

```tsx
import { useState, useEffect } from 'react';
import { VerifyClient, VerificationResponse, SessionStatus } from '@waltid/verify-sdk';

const client = new VerifyClient({
  apiKey: process.env.NEXT_PUBLIC_VERIFY_API_KEY!
});

function VerificationFlow() {
  const [verification, setVerification] = useState<VerificationResponse | null>(null);
  const [status, setStatus] = useState<SessionStatus | null>(null);
  const [isLoading, setIsLoading] = useState(false);

  const startVerification = async () => {
    setIsLoading(true);
    try {
      const result = await client.verifyIdentity({
        template: 'kyc_basic',
        responseMode: 'answers'
      });
      setVerification(result);

      // Start polling
      for await (const statusUpdate of client.pollSessionIterator(result.sessionId)) {
        setStatus(statusUpdate);
        if (statusUpdate.status !== 'pending') break;
      }
    } finally {
      setIsLoading(false);
    }
  };

  if (status?.status === 'verified') {
    return (
      <div className="success">
        <h2>Verified!</h2>
        <p>Welcome, {status.result?.answers?.given_name}!</p>
      </div>
    );
  }

  if (verification) {
    return (
      <div className="verification">
        <img src={verification.qrCodeUrl} alt="Scan to verify" />
        <p>Scan with your wallet app</p>
        <p>Status: {status?.status || 'Waiting...'}</p>

        {/* Same-device button */}
        <a href={verification.deepLink} className="btn">
          Open in Wallet
        </a>
      </div>
    );
  }

  return (
    <button onClick={startVerification} disabled={isLoading}>
      {isLoading ? 'Loading...' : 'Verify Identity'}
    </button>
  );
}
```

### Next.js API Route

```typescript
// pages/api/verify/start.ts
import type { NextApiRequest, NextApiResponse } from 'next';
import { VerifyClient } from '@waltid/verify-sdk';

const client = new VerifyClient({
  apiKey: process.env.VERIFY_API_KEY!
});

export default async function handler(req: NextApiRequest, res: NextApiResponse) {
  if (req.method !== 'POST') {
    return res.status(405).json({ error: 'Method not allowed' });
  }

  const { template, userId } = req.body;

  const session = await client.verifyIdentity({
    template: template || 'kyc_basic',
    callbackUrl: `${process.env.BASE_URL}/api/verify/callback`,
    metadata: { userId }
  });

  res.json({
    sessionId: session.sessionId,
    qrCodeUrl: session.qrCodeUrl,
    deepLink: session.deepLink
  });
}
```

### Express.js Integration

```typescript
import express from 'express';
import { VerifyClient } from '@waltid/verify-sdk';

const app = express();
const client = new VerifyClient({ apiKey: process.env.VERIFY_API_KEY! });

// Start verification
app.post('/api/verify', async (req, res) => {
  const session = await client.verifyIdentity({
    template: req.body.template,
    callbackUrl: `${process.env.BASE_URL}/api/verify/webhook`
  });

  res.json(session);
});

// Poll status
app.get('/api/verify/:sessionId', async (req, res) => {
  const status = await client.getSession(req.params.sessionId);
  res.json(status);
});

// Webhook handler
app.post('/api/verify/webhook', express.json(), async (req, res) => {
  const { sessionId, status, credentials, metadata } = req.body;

  if (status === 'verified') {
    // Process verified user
    await processVerification(metadata.userId, credentials);
  }

  res.sendStatus(200);
});
```

### Browser Usage (CDN)

```html
<script type="module">
  import { VerifyClient } from 'https://cdn.jsdelivr.net/npm/@waltid/verify-sdk/dist/index.js';

  const client = new VerifyClient({
    apiKey: 'your-publishable-key'
  });

  async function startVerification() {
    const session = await client.verifyIdentity({
      template: 'age_check'
    });

    document.getElementById('qr').src = session.qrCodeUrl;

    const result = await client.pollSession(session.sessionId);
    alert(result.status === 'verified' ? 'Verified!' : 'Failed');
  }
</script>
```

---

## iOS Swift SDK

### Installation

**Swift Package Manager:**

```swift
// Package.swift
dependencies: [
    .package(url: "https://github.com/walt-id/waltid-verify-sdk-ios", from: "1.0.0")
]
```

Or via Xcode: File > Add Package Dependencies > Enter repository URL

### Configuration

```swift
import WaltIDVerifySDK

let config = VerifyConfig(
    apiKey: "vfy_test_xxx",
    baseURL: URL(string: "https://verify.example.com")!
)
let client = VerifyClient(config: config)
```

### Basic Verification

```swift
// Create verification session
let request = VerificationRequest(
    template: "kyc_basic",
    responseMode: "direct_post",
    redirectUri: "myapp://verification-callback",
    metadata: ["userId": "123", "purpose": "onboarding"]
)

let session = try await client.verifyIdentity(request)

print("Session ID: \(session.sessionId)")
print("QR Code URL: \(session.qrCodeUrl)")
print("Deep Link: \(session.deepLink)")
```

### Polling for Results

```swift
// Wait for completion with timeout
let result = try await client.waitForSession(
    session.sessionId,
    pollingInterval: 2,  // seconds
    timeout: 300         // 5 minutes
)

switch result.status {
case "verified":
    print("Verified!")
    if let credentials = result.result?.credentials {
        for credential in credentials {
            print("Format: \(credential.format)")
            print("Claims: \(credential.disclosedClaims)")
        }
    }
case "failed":
    print("Verification failed")
case "expired":
    print("Session expired")
default:
    print("Unknown status")
}
```

### SwiftUI Integration

```swift
import SwiftUI
import WaltIDVerifySDK

struct VerificationView: View {
    @StateObject private var viewModel = VerificationViewModel()

    var body: some View {
        VStack(spacing: 20) {
            switch viewModel.state {
            case .idle:
                Button("Start Verification") {
                    Task { await viewModel.startVerification() }
                }
                .buttonStyle(.borderedProminent)

            case .loading:
                ProgressView("Starting...")

            case .pending(let session):
                VStack {
                    Text("Scan with your wallet")
                        .font(.headline)

                    AsyncImage(url: URL(string: session.qrCodeUrl)) { image in
                        image
                            .resizable()
                            .aspectRatio(contentMode: .fit)
                    } placeholder: {
                        ProgressView()
                    }
                    .frame(width: 250, height: 250)

                    // Same-device flow
                    Link("Open in Wallet", destination: URL(string: session.deepLink)!)
                        .buttonStyle(.borderedProminent)
                }

            case .verified(let result):
                VStack {
                    Image(systemName: "checkmark.circle.fill")
                        .font(.system(size: 60))
                        .foregroundColor(.green)

                    Text("Verified!")
                        .font(.headline)

                    if let name = result.answers?["given_name"] {
                        Text("Welcome, \(name)")
                    }
                }

            case .failed(let message):
                VStack {
                    Image(systemName: "xmark.circle.fill")
                        .font(.system(size: 60))
                        .foregroundColor(.red)

                    Text(message)
                        .foregroundColor(.secondary)

                    Button("Try Again") {
                        Task { await viewModel.startVerification() }
                    }
                }
            }
        }
        .padding()
    }
}

@MainActor
class VerificationViewModel: ObservableObject {
    @Published var state: VerificationState = .idle

    private let client = VerifyClient(config: VerifyConfig(
        apiKey: Configuration.verifyApiKey
    ))

    func startVerification() async {
        state = .loading

        do {
            let session = try await client.verifyIdentity(
                VerificationRequest(template: "kyc_basic")
            )
            state = .pending(session)

            // Start polling
            let result = try await client.waitForSession(session.sessionId)

            if result.status == "verified" {
                state = .verified(result.result ?? SessionResult())
            } else {
                state = .failed("Verification \(result.status)")
            }
        } catch VerifyError.timeout {
            state = .failed("Verification timed out")
        } catch {
            state = .failed(error.localizedDescription)
        }
    }
}

enum VerificationState {
    case idle
    case loading
    case pending(VerificationResponse)
    case verified(SessionResult)
    case failed(String)
}
```

### Universal Links / Deep Link Handling

```swift
// SceneDelegate.swift or App.swift
func scene(_ scene: UIScene, openURLContexts URLContexts: Set<UIOpenURLContext>) {
    guard let url = URLContexts.first?.url else { return }

    if url.scheme == "myapp" && url.host == "verification-callback" {
        // Extract session ID from URL if needed
        let sessionId = url.queryItems?["session_id"]
        NotificationCenter.default.post(
            name: .verificationComplete,
            object: nil,
            userInfo: ["sessionId": sessionId]
        )
    }
}
```

### Error Handling

```swift
do {
    let session = try await client.verifyIdentity(request)
} catch let error as VerifyError {
    switch error {
    case .requestFailed(let statusCode, let message):
        print("API error \(statusCode): \(message ?? "Unknown")")
    case .timeout:
        print("Request timed out")
    case .networkError(let underlyingError):
        print("Network error: \(underlyingError)")
    case .encodingError(let underlyingError):
        print("Encoding error: \(underlyingError)")
    case .decodingError(let underlyingError):
        print("Decoding error: \(underlyingError)")
    case .invalidURL:
        print("Invalid URL configuration")
    }
}
```

---

## Android Kotlin SDK

### Installation

**Gradle (Kotlin DSL):**

```kotlin
// build.gradle.kts
dependencies {
    implementation("id.walt:waltid-verify-sdk-android:1.0.0-SNAPSHOT")
}

// Repository (if not on Maven Central)
repositories {
    maven("https://maven.walt.id/repository/releases")
}
```

### Configuration

```kotlin
import id.walt.verify.sdk.*

val client = VerifyClient(VerifyConfig(
    apiKey = BuildConfig.VERIFY_API_KEY,
    baseUrl = "https://verify.example.com"
))
```

### Basic Verification

```kotlin
// Create verification session
val session = client.verifyIdentity(VerificationRequest(
    template = "kyc_basic",
    responseMode = "answers",
    metadata = mapOf(
        "userId" to "123",
        "purpose" to "onboarding"
    )
))

println("Session ID: ${session.sessionId}")
println("QR Code: ${session.qrCodeUrl}")
println("Deep Link: ${session.deepLink}")
```

### Polling for Results

```kotlin
// Simple polling
val result = client.pollSession(
    sessionId = session.sessionId,
    intervalMs = 2000,
    timeoutMs = 300_000
)

when {
    result.isVerified -> {
        println("Verified!")
        result.result?.answers?.forEach { (key, value) ->
            println("$key: $value")
        }
    }
    result.isFailed -> println("Verification failed")
    result.isExpired -> println("Session expired")
}

// Polling with status updates
val status = client.pollSessionWithUpdates(
    sessionId = session.sessionId,
    intervalMs = 2000,
    timeoutMs = 300_000
) { currentStatus ->
    // Called on each poll
    runOnUiThread {
        statusText.text = "Status: ${currentStatus.status}"
    }
}
```

### Jetpack Compose Integration

```kotlin
import androidx.compose.runtime.*
import androidx.compose.material3.*
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import coil.compose.AsyncImage
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

@Composable
fun VerificationScreen(
    viewModel: VerificationViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        when (val currentState = state) {
            is VerificationState.Idle -> {
                Button(onClick = { viewModel.startVerification() }) {
                    Text("Start Verification")
                }
            }

            is VerificationState.Loading -> {
                CircularProgressIndicator()
                Spacer(modifier = Modifier.height(16.dp))
                Text("Starting verification...")
            }

            is VerificationState.Pending -> {
                Text(
                    "Scan with your wallet",
                    style = MaterialTheme.typography.headlineSmall
                )

                Spacer(modifier = Modifier.height(16.dp))

                AsyncImage(
                    model = currentState.session.qrCodeUrl,
                    contentDescription = "Verification QR Code",
                    modifier = Modifier.size(250.dp)
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Same-device flow
                OutlinedButton(
                    onClick = {
                        val intent = Intent(
                            Intent.ACTION_VIEW,
                            Uri.parse(currentState.session.deepLink)
                        )
                        context.startActivity(intent)
                    }
                ) {
                    Icon(Icons.Default.OpenInNew, null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Open in Wallet")
                }
            }

            is VerificationState.Verified -> {
                Icon(
                    Icons.Default.CheckCircle,
                    contentDescription = null,
                    tint = Color.Green,
                    modifier = Modifier.size(64.dp)
                )

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    "Verified!",
                    style = MaterialTheme.typography.headlineSmall
                )

                currentState.result.answers?.get("given_name")?.let { name ->
                    Text("Welcome, $name")
                }
            }

            is VerificationState.Failed -> {
                Icon(
                    Icons.Default.Error,
                    contentDescription = null,
                    tint = Color.Red,
                    modifier = Modifier.size(64.dp)
                )

                Spacer(modifier = Modifier.height(16.dp))

                Text(currentState.message)

                Spacer(modifier = Modifier.height(16.dp))

                Button(onClick = { viewModel.startVerification() }) {
                    Text("Try Again")
                }
            }
        }
    }
}

@HiltViewModel
class VerificationViewModel @Inject constructor(
    private val client: VerifyClient
) : ViewModel() {

    private val _state = MutableStateFlow<VerificationState>(VerificationState.Idle)
    val state = _state.asStateFlow()

    fun startVerification() {
        viewModelScope.launch {
            _state.value = VerificationState.Loading

            try {
                val session = client.verifyIdentity(
                    VerificationRequest(template = "kyc_basic")
                )
                _state.value = VerificationState.Pending(session)

                val result = client.pollSession(session.sessionId)

                _state.value = when {
                    result.isVerified -> VerificationState.Verified(result.result!!)
                    else -> VerificationState.Failed("Verification ${result.status}")
                }
            } catch (e: PollingTimeoutException) {
                _state.value = VerificationState.Failed("Verification timed out")
            } catch (e: VerifyException) {
                _state.value = VerificationState.Failed(e.message ?: "Unknown error")
            }
        }
    }

    override fun onCleared() {
        client.close()
    }
}

sealed class VerificationState {
    object Idle : VerificationState()
    object Loading : VerificationState()
    data class Pending(val session: VerificationResponse) : VerificationState()
    data class Verified(val result: SessionResult) : VerificationState()
    data class Failed(val message: String) : VerificationState()
}
```

### Deep Link Handling

```kotlin
// AndroidManifest.xml
<activity android:name=".MainActivity">
    <intent-filter>
        <action android:name="android.intent.action.VIEW" />
        <category android:name="android.intent.category.DEFAULT" />
        <category android:name="android.intent.category.BROWSABLE" />
        <data
            android:scheme="myapp"
            android:host="verification-callback" />
    </intent-filter>
</activity>

// MainActivity.kt
override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

    intent?.data?.let { uri ->
        if (uri.scheme == "myapp" && uri.host == "verification-callback") {
            val sessionId = uri.getQueryParameter("session_id")
            // Handle callback
        }
    }
}
```

### Error Handling

```kotlin
try {
    val session = client.verifyIdentity(request)
    val result = client.pollSession(session.sessionId)
} catch (e: VerifyException) {
    when (e.statusCode) {
        401 -> showError("Invalid API key")
        404 -> showError("Template not found")
        429 -> {
            // Rate limited - implement backoff
            delay(e.retryAfter ?: 60_000)
            retry()
        }
        else -> showError("Error: ${e.message}")
    }
} catch (e: PollingTimeoutException) {
    showError("Verification timed out")
}
```

---

## Cross-Platform Patterns

### Response Modes

Both modes work identically across all SDKs:

**Answers Mode (Default):**
```typescript
// Returns mapped claim values
{
  "answers": {
    "full_name": "John Doe",
    "date_of_birth": "1990-01-15"
  }
}
```

**Raw Credentials Mode:**
```typescript
// Returns full credential data
{
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
```

### Custom DCQL Queries

All SDKs support custom DCQL queries for specific claim requirements:

```typescript
// TypeScript
const session = await client.verifyIdentity({
  dcql: {
    credentials: [{
      id: 'pid',
      format: 'dc+sd-jwt',
      meta: { vct_values: ['urn:eudi:pid:1'] },
      claims: [
        { path: ['given_name'] },
        { path: ['birth_date'], filter: { maximum: '2006-01-01' } }
      ]
    }]
  }
});
```

```swift
// Swift
let request = VerificationRequest(
    dcql: DCQLQuery(
        credentials: [
            DCQLCredential(
                id: "pid",
                format: "dc+sd-jwt",
                meta: ["vct_values": ["urn:eudi:pid:1"]],
                claims: [
                    DCQLClaim(path: ["given_name"]),
                    DCQLClaim(path: ["birth_date"])
                ]
            )
        ]
    )
)
```

```kotlin
// Kotlin
val request = VerificationRequest(
    dcql = DCQLQuery(
        credentials = listOf(
            DCQLCredential(
                id = "pid",
                format = "dc+sd-jwt",
                meta = mapOf("vct_values" to listOf("urn:eudi:pid:1")),
                claims = listOf(
                    DCQLClaim(path = listOf("given_name")),
                    DCQLClaim(path = listOf("birth_date"))
                )
            )
        )
    )
)
```

### Session Lifecycle

All SDKs follow the same session lifecycle:

```
Created --> Pending --> Verified
                   \--> Failed
                   \--> Expired
```

| Status | Description |
|--------|-------------|
| `pending` | Waiting for user to scan and respond |
| `verified` | User successfully verified |
| `failed` | Verification failed (invalid credential, cancelled) |
| `expired` | Session timed out (default: 10 minutes) |

### Best Practices

1. **Store API keys securely** - Use environment variables or secure storage
2. **Implement proper error handling** - Handle all error cases gracefully
3. **Use webhooks in production** - More reliable than polling
4. **Set appropriate timeouts** - 5 minutes for payments, 10 minutes for KYC
5. **Clean up sessions** - Cancel unused sessions to free resources
6. **Log verification attempts** - Maintain audit trail for compliance
