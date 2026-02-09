# Age Verification iOS Example

A SwiftUI application demonstrating age-gated content access using the walt.id Verify API.

## Features

- **QR Code Verification**: Cross-device flow with QR code scanning
- **Same-Device Flow**: Deep link support for mobile wallets
- **Real-time Status Polling**: Automatic updates when verification completes
- **SwiftUI Native**: Modern declarative UI with iOS 16+ support

## Screenshot Flow

1. **Locked Content**: User sees blurred content with age verification prompt
2. **QR Code Display**: User scans QR code with wallet app or taps "Open Wallet"
3. **Wallet Interaction**: User approves sharing age credentials
4. **Unlocked Content**: Premium wine selection becomes accessible

## Requirements

- Xcode 15.0+
- iOS 16.0+ / macOS 13.0+
- Swift 5.9+
- Verify API running (default: http://localhost:7010)

## Quick Start

### 1. Clone and Navigate

```bash
cd examples/rp-ios
```

### 2. Configure Environment

Create a `.env` file or set environment variables:

```bash
export VERIFY_API_URL="http://localhost:7010"
export VERIFY_API_KEY="vfy_your_api_key"
```

Or modify `Configuration.swift` directly for development.

### 3. Build and Run

**Using Swift Package Manager (command line):**

```bash
# Build
swift build

# Run (macOS only - iOS requires Xcode)
swift run AgeVerifyApp
```

**Using Xcode:**

```bash
# Generate Xcode project
xed .
```

Then build and run in Xcode (Cmd+R).

## Project Structure

```
rp-ios/
├── Package.swift                    # Swift Package manifest
├── Sources/
│   └── AgeVerifyApp/
│       ├── AgeVerifyApp.swift       # App entry point
│       ├── Configuration.swift      # API configuration
│       ├── ContentView.swift        # Main UI
│       ├── VerificationState.swift  # State enum
│       ├── VerificationViewModel.swift  # Business logic
│       └── QRCodeGenerator.swift    # QR code utility
└── README.md
```

## How It Works

### Verification Flow

```
┌─────────────┐     ┌─────────────┐     ┌─────────────┐
│  Tap Verify │────>│  Create     │────>│  Display    │
│     Age     │     │  Session    │     │  QR Code    │
└─────────────┘     └─────────────┘     └─────────────┘
                                               │
                                               v
┌─────────────┐     ┌─────────────┐     ┌─────────────┐
│   Unlock    │<────│   Poll for  │<────│  User scans │
│   Content   │     │   Result    │     │  with wallet│
└─────────────┘     └─────────────┘     └─────────────┘
```

### Code Flow

1. **User taps "Verify My Age"**
   - `VerificationViewModel.startVerification()` is called
   - Creates `VerificationRequest` with `age_check` template

2. **Session Created**
   - `VerifyClient.verifyIdentity()` calls Verify API
   - Returns QR code data and deep link

3. **Waiting for Wallet**
   - QR code displayed using `QRCodeGenerator`
   - Optional: User taps "Open Wallet" for same-device flow
   - Polling starts with `VerifyClient.waitForSession()`

4. **Verification Complete**
   - Polling detects status change
   - State updates to `.verified` with claims
   - UI shows unlocked content

## Configuration

### API Settings

Edit `Configuration.swift`:

```swift
enum Configuration {
    // Verify API URL
    static let verifyAPIURL: URL = URL(string: "https://verify.example.com")!

    // API Key (fetch from backend in production!)
    static let apiKey: String = "vfy_your_key"

    // Verification template
    static let verificationTemplate = "age_check"
}
```

### Environment Variables

| Variable | Description | Default |
|----------|-------------|---------|
| `VERIFY_API_URL` | Verify API base URL | `http://localhost:7010` |
| `VERIFY_API_KEY` | API key for authentication | `vfy_test_sandbox_demo_key_12345678` |

The default sandbox credentials work immediately without any setup. See [Sandbox Credentials](../../docs/verify-api/sandbox-credentials.md) for details.

## Security Considerations

### Production Deployment

1. **Never embed API keys in the app**
   - Fetch tokens from your backend
   - Use short-lived session tokens

2. **Backend proxy**
   - Route all Verify API calls through your backend
   - Backend holds the API key securely

3. **Certificate pinning**
   - Consider implementing SSL pinning for production

### Example Backend Flow

```
┌─────────┐     ┌─────────────┐     ┌────────────┐
│  iOS    │────>│  Your       │────>│  Verify    │
│  App    │     │  Backend    │     │  API       │
└─────────┘     └─────────────┘     └────────────┘
     │                │
     │   Session ID   │   API Key
     │   + QR Data    │   + Request
     │<───────────────│
```

## Customization

### Different Templates

```swift
// In Configuration.swift
static let verificationTemplate = "kyc_basic"  // or "full_kyc"
```

### Custom Styling

Modify `ContentView.swift` to match your brand:

```swift
// Change accent color
.tint(.blue)

// Custom header
Image("your_logo")

// Different icons
Image(systemName: "your.icon")
```

### Handle Additional Claims

```swift
// In verifiedContentView
if let ageOver21 = claims["ageOver21"] {
    Text("Age 21+: \(ageOver21)")
}
if let name = claims["given_name"] {
    Text("Welcome, \(name)!")
}
```

## Testing

### Mock API Response

For UI testing without a running Verify API, create a mock client:

```swift
class MockVerifyClient {
    func verifyIdentity(_ request: VerificationRequest) async throws -> VerificationResponse {
        return VerificationResponse(
            sessionId: "mock_session",
            qrCodeUrl: "https://example.com/qr.png",
            qrCodeData: "openid4vp://mock",
            deepLink: "openid4vp://mock",
            expiresAt: Int64(Date().timeIntervalSince1970 + 300)
        )
    }
}
```

### SwiftUI Previews

The app includes preview support. Use Xcode's canvas to see UI states:

```swift
#Preview {
    ContentView()
}
```

## Troubleshooting

### "Network error" on Simulator

- Ensure Verify API is running on localhost
- Check App Transport Security settings for HTTP

### QR Code Not Displaying

- Verify the SDK is returning valid `qrCodeData`
- Check console for `QRCodeGenerator` errors

### Wallet Not Opening

- Ensure wallet app is installed
- Verify the deep link URL scheme is registered

### Build Errors

- Clean build folder: Cmd+Shift+K
- Reset package caches: File > Packages > Reset Package Caches

## Dependencies

- [WaltIDVerifySDK](../../waltid-verify-sdk-ios) - walt.id Verify API client

## Related Examples

- [rp-web-nextjs](../rp-web-nextjs) - Next.js web application example
- [rp-android](../rp-android) - Android Kotlin example (coming soon)

## License

Apache 2.0 - See [LICENSE](../../LICENSE) in the root directory.
