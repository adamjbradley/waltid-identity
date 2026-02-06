# Age Verification Example

This guide shows how to implement age verification using the Verify API SDKs.

## Use Case

Verify that a user is over a certain age (e.g., 18, 21) without revealing their exact birth date or other personal information.

## Overview

1. Create a verification session with age requirements
2. Display QR code or deep link to user
3. User scans with their wallet and shares age proof
4. Receive verification result via polling or webhook

## TypeScript Implementation

### Basic Age Check (18+)

```typescript
import { VerifyClient } from '@waltid/verify-sdk';

const client = new VerifyClient({
  apiKey: process.env.VERIFY_API_KEY!
});

async function verifyAge18Plus(): Promise<boolean> {
  // Create verification session using pre-built template
  const session = await client.verifyIdentity({
    template: 'age_over_18'
  });

  console.log('Please scan this QR code with your wallet:');
  console.log(session.qrCodeUrl);

  // Poll for result (timeout after 5 minutes)
  const result = await client.pollSession(session.sessionId, {
    timeout: 300000,
    interval: 2000
  });

  return result.status === 'verified';
}
```

### Custom Age Threshold

```typescript
async function verifyAgeOver(minimumAge: number): Promise<boolean> {
  // Calculate the cutoff date for minimum age
  const today = new Date();
  const cutoffDate = new Date(
    today.getFullYear() - minimumAge,
    today.getMonth(),
    today.getDate()
  );

  // Create session with custom DCQL query
  const session = await client.verifyIdentity({
    dcql: {
      credentials: [{
        id: 'age_credential',
        format: 'dc+sd-jwt',
        claims: [{
          path: ['birth_date'],
          filter: {
            type: 'string',
            format: 'date',
            maximum: cutoffDate.toISOString().split('T')[0]
          }
        }]
      }]
    }
  });

  const result = await client.pollSession(session.sessionId);
  return result.status === 'verified';
}

// Usage
const isOver21 = await verifyAgeOver(21);
```

### Express.js Integration

```typescript
import express from 'express';
import { VerifyClient } from '@waltid/verify-sdk';

const app = express();
const client = new VerifyClient({ apiKey: process.env.VERIFY_API_KEY! });

// Start verification
app.post('/api/verify-age', async (req, res) => {
  const session = await client.verifyIdentity({
    template: 'age_over_18',
    callbackUrl: `${process.env.BASE_URL}/api/verify-age/callback`,
    metadata: {
      userId: req.user.id,
      purpose: 'age-restricted-purchase'
    }
  });

  res.json({
    sessionId: session.sessionId,
    qrCode: session.qrCodeUrl,
    deepLink: session.deepLink
  });
});

// Check status (for polling)
app.get('/api/verify-age/:sessionId', async (req, res) => {
  const result = await client.getSession(req.params.sessionId);
  res.json(result);
});

// Webhook callback
app.post('/api/verify-age/callback', express.json(), async (req, res) => {
  const { sessionId, status, credentials } = req.body;

  if (status === 'verified') {
    // Update user record
    await db.users.update({
      where: { id: req.body.metadata.userId },
      data: { ageVerified: true, ageVerifiedAt: new Date() }
    });
  }

  res.sendStatus(200);
});
```

## Swift Implementation

### Basic Age Check

```swift
import WaltIDVerifySDK

class AgeVerificationService {
    private let client: VerifyClient

    init(apiKey: String) {
        self.client = VerifyClient(config: VerifyConfig(apiKey: apiKey))
    }

    func verifyAgeOver18() async throws -> AgeVerificationResult {
        // Create session
        let session = try await client.verifyIdentity(
            VerificationRequest(template: "age_over_18")
        )

        // Return session for UI to display QR code
        return AgeVerificationResult(
            sessionId: session.sessionId,
            qrCodeUrl: session.qrCodeUrl,
            deepLink: session.deepLink
        )
    }

    func checkStatus(sessionId: String) async throws -> VerificationStatus {
        let result = try await client.getSession(sessionId: sessionId)
        return result.status
    }
}

struct AgeVerificationResult {
    let sessionId: String
    let qrCodeUrl: String
    let deepLink: String
}
```

### SwiftUI View

```swift
import SwiftUI
import WaltIDVerifySDK

struct AgeVerificationView: View {
    @StateObject private var viewModel = AgeVerificationViewModel()

    var body: some View {
        VStack(spacing: 20) {
            switch viewModel.state {
            case .idle:
                Button("Verify Age") {
                    Task { await viewModel.startVerification() }
                }
                .buttonStyle(.borderedProminent)

            case .pending(let session):
                Text("Scan with your wallet")
                    .font(.headline)

                AsyncImage(url: URL(string: session.qrCodeUrl)) { image in
                    image.resizable().aspectRatio(contentMode: .fit)
                } placeholder: {
                    ProgressView()
                }
                .frame(width: 250, height: 250)

                // Same-device flow
                Link("Open in Wallet", destination: URL(string: session.deepLink)!)

            case .verified:
                Image(systemName: "checkmark.circle.fill")
                    .font(.system(size: 60))
                    .foregroundColor(.green)
                Text("Age Verified!")
                    .font(.headline)

            case .failed(let error):
                Image(systemName: "xmark.circle.fill")
                    .font(.system(size: 60))
                    .foregroundColor(.red)
                Text(error)
                    .foregroundColor(.secondary)
            }
        }
        .padding()
    }
}

@MainActor
class AgeVerificationViewModel: ObservableObject {
    @Published var state: VerificationState = .idle

    private let service = AgeVerificationService(
        apiKey: Configuration.verifyApiKey
    )

    func startVerification() async {
        do {
            let session = try await service.verifyAgeOver18()
            state = .pending(session)

            // Start polling
            await pollForResult(sessionId: session.sessionId)
        } catch {
            state = .failed(error.localizedDescription)
        }
    }

    private func pollForResult(sessionId: String) async {
        for _ in 0..<150 { // 5 minutes with 2s intervals
            try? await Task.sleep(nanoseconds: 2_000_000_000)

            let status = try? await service.checkStatus(sessionId: sessionId)

            switch status {
            case .verified:
                state = .verified
                return
            case .failed:
                state = .failed("Verification failed")
                return
            case .expired:
                state = .failed("Session expired")
                return
            default:
                continue
            }
        }

        state = .failed("Verification timed out")
    }
}

enum VerificationState {
    case idle
    case pending(AgeVerificationResult)
    case verified
    case failed(String)
}
```

## Kotlin (Android) Implementation

### Basic Age Check

```kotlin
import id.walt.verify.VerifyClient
import id.walt.verify.VerifyConfig
import id.walt.verify.VerificationRequest

class AgeVerificationRepository(
    private val apiKey: String
) {
    private val client = VerifyClient(VerifyConfig(apiKey = apiKey))

    suspend fun startAgeVerification(): VerificationSession {
        return client.verifyIdentity(
            VerificationRequest(template = "age_over_18")
        )
    }

    suspend fun checkStatus(sessionId: String): VerificationResult {
        return client.getSession(sessionId)
    }

    suspend fun pollUntilComplete(
        sessionId: String,
        timeoutMs: Long = 300_000,
        intervalMs: Long = 2_000
    ): VerificationResult {
        return client.pollSession(
            sessionId = sessionId,
            timeout = timeoutMs,
            interval = intervalMs
        )
    }
}
```

### Jetpack Compose UI

```kotlin
import androidx.compose.runtime.*
import androidx.compose.material3.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import coil.compose.AsyncImage

@Composable
fun AgeVerificationScreen(
    viewModel: AgeVerificationViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()

    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        when (val currentState = state) {
            is VerificationState.Idle -> {
                Button(onClick = { viewModel.startVerification() }) {
                    Text("Verify Age")
                }
            }

            is VerificationState.Pending -> {
                Text(
                    text = "Scan with your wallet",
                    style = MaterialTheme.typography.headlineSmall
                )

                Spacer(modifier = Modifier.height(16.dp))

                AsyncImage(
                    model = currentState.session.qrCodeUrl,
                    contentDescription = "Verification QR Code",
                    modifier = Modifier.size(250.dp)
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Same-device flow button
                val context = LocalContext.current
                OutlinedButton(
                    onClick = {
                        val intent = Intent(Intent.ACTION_VIEW,
                            Uri.parse(currentState.session.deepLink))
                        context.startActivity(intent)
                    }
                ) {
                    Text("Open in Wallet App")
                }
            }

            is VerificationState.Verified -> {
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = null,
                    tint = Color.Green,
                    modifier = Modifier.size(60.dp)
                )
                Text("Age Verified!")
            }

            is VerificationState.Failed -> {
                Icon(
                    imageVector = Icons.Default.Error,
                    contentDescription = null,
                    tint = Color.Red,
                    modifier = Modifier.size(60.dp)
                )
                Text(currentState.message)
            }
        }
    }
}

@HiltViewModel
class AgeVerificationViewModel @Inject constructor(
    private val repository: AgeVerificationRepository
) : ViewModel() {

    private val _state = MutableStateFlow<VerificationState>(VerificationState.Idle)
    val state: StateFlow<VerificationState> = _state.asStateFlow()

    fun startVerification() {
        viewModelScope.launch {
            try {
                val session = repository.startAgeVerification()
                _state.value = VerificationState.Pending(session)

                // Poll for result
                val result = repository.pollUntilComplete(session.sessionId)

                _state.value = when (result.status) {
                    "verified" -> VerificationState.Verified
                    else -> VerificationState.Failed("Verification failed")
                }
            } catch (e: Exception) {
                _state.value = VerificationState.Failed(e.message ?: "Unknown error")
            }
        }
    }
}

sealed class VerificationState {
    object Idle : VerificationState()
    data class Pending(val session: VerificationSession) : VerificationState()
    object Verified : VerificationState()
    data class Failed(val message: String) : VerificationState()
}
```

## Available Age Templates

| Template ID | Description | Credentials Accepted |
|-------------|-------------|---------------------|
| `age_over_13` | COPPA compliance | PID, mDL |
| `age_over_18` | Adult content, tobacco | PID, mDL |
| `age_over_21` | Alcohol (US) | PID, mDL |
| `age_over_25` | Car rental | PID, mDL |

## Best Practices

1. **Use templates when possible** - Pre-built templates are optimized and maintained
2. **Implement timeouts** - Sessions expire after 10 minutes by default
3. **Handle all states** - pending, verified, failed, and expired
4. **Use webhooks for production** - More reliable than polling
5. **Store verification results** - Cache the verification to avoid re-verification
6. **Request minimum data** - Age proofs don't reveal exact birth date

## Security Considerations

- Never store the full credential data - only the verification result
- Validate webhook signatures in production
- Use HTTPS for all callback URLs
- Implement rate limiting on your verification endpoints
- Log verification attempts for audit purposes
