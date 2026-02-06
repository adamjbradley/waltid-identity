# WaltID Verify SDK for iOS

Swift SDK for integrating with the walt.id Verify API in iOS and macOS applications.

## Requirements

- iOS 15.0+ / macOS 12.0+
- Swift 5.9+
- Xcode 15.0+

## Installation

### Swift Package Manager

Add the following to your `Package.swift` file:

```swift
dependencies: [
    .package(url: "https://github.com/walt-id/waltid-verify-sdk-ios.git", from: "1.0.0")
]
```

Or add it via Xcode:
1. File > Add Package Dependencies...
2. Enter the repository URL
3. Select the version and add to your target

## Quick Start

```swift
import WaltIDVerifySDK

// Initialize the client
let config = VerifyConfig(
    apiKey: "your-api-key",
    baseURL: URL(string: "https://verify.example.com")!
)
let client = VerifyClient(config: config)

// Create a verification request
let request = VerificationRequest(
    template: "identity-verification",
    metadata: ["user_id": "12345"]
)

// Start verification
do {
    let response = try await client.verifyIdentity(request)
    print("Session ID: \(response.sessionId)")
    print("QR Code URL: \(response.qrCodeUrl)")
    print("Deep Link: \(response.deepLink)")
} catch {
    print("Error: \(error)")
}
```

## Usage

### Initialize the Client

```swift
import WaltIDVerifySDK

let config = VerifyConfig(
    apiKey: "your-api-key",
    baseURL: URL(string: "https://verify.example.com")!
)
let client = VerifyClient(config: config)
```

### Start a Verification Session

```swift
let request = VerificationRequest(
    template: "identity-verification",
    responseMode: "direct_post",
    redirectUri: "myapp://callback",
    metadata: ["user_id": "12345", "session_type": "kyc"]
)

let response = try await client.verifyIdentity(request)

// Display QR code for cross-device flow
imageView.load(url: URL(string: response.qrCodeUrl)!)

// Or use deep link for same-device flow
if let url = URL(string: response.deepLink) {
    await UIApplication.shared.open(url)
}
```

### Check Session Status

```swift
let status = try await client.getSession(sessionId)

switch status.status {
case "pending":
    print("Waiting for user to complete verification")
case "verified":
    print("Verification successful!")
    if let result = status.result {
        // Access verified credentials
        for credential in result.credentials ?? [] {
            print("Format: \(credential.format)")
            print("Claims: \(credential.disclosedClaims)")
        }
    }
case "failed":
    print("Verification failed")
case "expired":
    print("Session expired")
default:
    print("Unknown status: \(status.status)")
}
```

### Poll for Completion

```swift
// Wait up to 5 minutes for verification to complete
do {
    let finalStatus = try await client.waitForSession(
        sessionId,
        pollingInterval: 2,  // Check every 2 seconds
        timeout: 300         // 5 minute timeout
    )

    if finalStatus.status == "verified" {
        print("Verification complete!")
    }
} catch VerifyError.timeout {
    print("Verification timed out")
}
```

## SwiftUI Example

```swift
import SwiftUI
import WaltIDVerifySDK

struct VerificationView: View {
    @State private var verificationResponse: VerificationResponse?
    @State private var sessionStatus: SessionStatus?
    @State private var isLoading = false
    @State private var error: String?

    let client: VerifyClient

    var body: some View {
        VStack(spacing: 20) {
            if isLoading {
                ProgressView("Loading...")
            } else if let response = verificationResponse {
                // Display QR code
                AsyncImage(url: URL(string: response.qrCodeUrl)) { image in
                    image
                        .resizable()
                        .aspectRatio(contentMode: .fit)
                        .frame(width: 200, height: 200)
                } placeholder: {
                    ProgressView()
                }

                Text("Scan with your wallet app")
                    .font(.headline)

                // Same-device flow button
                Button("Open in Wallet") {
                    if let url = URL(string: response.deepLink) {
                        UIApplication.shared.open(url)
                    }
                }
                .buttonStyle(.borderedProminent)

                // Status indicator
                if let status = sessionStatus {
                    Text("Status: \(status.status)")
                        .foregroundColor(statusColor(status.status))
                }
            } else {
                Button("Start Verification") {
                    Task { await startVerification() }
                }
                .buttonStyle(.borderedProminent)
            }

            if let error = error {
                Text(error)
                    .foregroundColor(.red)
            }
        }
        .padding()
    }

    func startVerification() async {
        isLoading = true
        error = nil

        do {
            let request = VerificationRequest(template: "identity-verification")
            verificationResponse = try await client.verifyIdentity(request)

            // Start polling for status
            if let sessionId = verificationResponse?.sessionId {
                Task { await pollStatus(sessionId) }
            }
        } catch {
            self.error = error.localizedDescription
        }

        isLoading = false
    }

    func pollStatus(_ sessionId: String) async {
        do {
            sessionStatus = try await client.waitForSession(sessionId)
        } catch {
            self.error = error.localizedDescription
        }
    }

    func statusColor(_ status: String) -> Color {
        switch status {
        case "verified": return .green
        case "failed", "expired": return .red
        default: return .orange
        }
    }
}
```

## API Reference

### VerifyConfig

Configuration for the VerifyClient.

| Property | Type | Description |
|----------|------|-------------|
| `apiKey` | `String` | API key for authentication |
| `baseURL` | `URL` | Base URL of the Verify API |

### VerificationRequest

Request to initiate identity verification.

| Property | Type | Description |
|----------|------|-------------|
| `template` | `String` | Template name to use for verification |
| `responseMode` | `String?` | Response mode (e.g., "direct_post") |
| `redirectUri` | `String?` | Redirect URI for callback |
| `metadata` | `[String: String]?` | Additional metadata for the session |

### VerificationResponse

Response from initiating a verification session.

| Property | Type | Description |
|----------|------|-------------|
| `sessionId` | `String` | Unique session identifier |
| `qrCodeUrl` | `String` | URL for the QR code image |
| `qrCodeData` | `String` | Raw data encoded in the QR code |
| `deepLink` | `String` | Deep link for same-device flow |
| `expiresAt` | `Int64` | Unix timestamp when session expires |

### SessionStatus

Status of a verification session.

| Property | Type | Description |
|----------|------|-------------|
| `sessionId` | `String` | Unique session identifier |
| `status` | `String` | Current status (pending, verified, failed, expired) |
| `templateName` | `String` | Name of the template used |
| `result` | `SessionResult?` | Verification result (when verified) |
| `verifiedAt` | `Int64?` | Unix timestamp when verified |
| `metadata` | `[String: String]?` | Additional metadata |
| `expiresAt` | `Int64` | Unix timestamp when session expires |

### VerifyClient Methods

| Method | Description |
|--------|-------------|
| `verifyIdentity(_:)` | Initiate an identity verification session |
| `getSession(_:)` | Get the status of a verification session |
| `waitForSession(_:pollingInterval:timeout:)` | Poll for session completion |

### VerifyError

| Case | Description |
|------|-------------|
| `requestFailed(statusCode:message:)` | API request failed |
| `timeout` | Request or polling timed out |
| `networkError(Error)` | Network error occurred |
| `encodingError(Error)` | Failed to encode request |
| `decodingError(Error)` | Failed to decode response |
| `invalidURL` | Invalid URL |

## Error Handling

```swift
do {
    let response = try await client.verifyIdentity(request)
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
        print("Invalid URL")
    }
} catch {
    print("Unexpected error: \(error)")
}
```

## License

Apache 2.0
