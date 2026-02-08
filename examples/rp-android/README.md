# Verify API Example - KYC Onboarding (Android)

This example Android application demonstrates how to integrate with the walt.id Verify API
for multi-step KYC (Know Your Customer) identity verification using verifiable credentials.

## Features

- **Multi-Step Flow**: Sequential verification steps (identity, document, address)
- **QR Code Verification**: Cross-device flow with QR code scanning
- **Real-time Status**: Live polling for verification status updates
- **Modern UI**: Built with Jetpack Compose and Material 3
- **Error Handling**: Graceful failure states with retry options

## Screenshots

The app includes three main screens:

1. **Welcome Screen** - Overview of the verification process
2. **Verification Screen** - QR code display and status polling
3. **Result Screen** - Success/failure outcome with verified data

## Prerequisites

- Android Studio Hedgehog (2023.1.1) or later
- JDK 17
- Android SDK with API level 26+ (Android 8.0+)
- Verify API running (default: http://localhost:7010)

## Quick Start

### 1. Clone and Open

```bash
# Navigate to the example
cd examples/rp-android

# Open in Android Studio
open -a "Android Studio" .
```

### 2. Configure API Settings

The example comes pre-configured with sandbox credentials that work immediately. Edit `app/build.gradle.kts` to customize:

```kotlin
defaultConfig {
    // For emulator, use 10.0.2.2 (localhost from emulator)
    buildConfigField("String", "VERIFY_API_URL", "\"http://10.0.2.2:7010\"")
    // Sandbox demo key - works without any setup
    buildConfigField("String", "VERIFY_API_KEY", "\"vfy_test_sandbox_demo_key_12345678\"")
}
```

See [Sandbox Credentials](../../docs/verify-api/sandbox-credentials.md) for details.

For a physical device, use your machine's local network IP:
```kotlin
buildConfigField("String", "VERIFY_API_URL", "\"http://192.168.1.100:7010\"")
```

### 3. Build and Run

```bash
# Build the project
./gradlew build

# Install on connected device/emulator
./gradlew installDebug
```

Or use Android Studio's Run button.

## Project Structure

```
rp-android/
├── app/
│   ├── src/main/
│   │   ├── java/id/walt/verify/example/
│   │   │   ├── MainActivity.kt          # Entry point
│   │   │   ├── KYCOnboardingApp.kt       # Navigation setup
│   │   │   ├── KYCViewModel.kt           # Business logic
│   │   │   └── ui/
│   │   │       ├── screens/
│   │   │       │   ├── WelcomeScreen.kt      # Start screen
│   │   │       │   ├── VerificationScreen.kt # QR & polling
│   │   │       │   └── ResultScreen.kt       # Outcome screen
│   │   │       └── theme/                    # Material 3 theme
│   │   ├── res/
│   │   │   └── ...                       # Resources
│   │   └── AndroidManifest.xml
│   └── build.gradle.kts
├── build.gradle.kts
├── settings.gradle.kts
└── README.md
```

## How It Works

### Verification Flow

1. **User starts KYC onboarding**
   - Welcome screen explains the multi-step process
   - User taps "Start Verification"

2. **For each verification step:**
   - App creates a session via Verify API
   - QR code is displayed for wallet scanning
   - User scans with their credential wallet
   - App polls for verification result

3. **On completion:**
   - Success: Shows verified data from all steps
   - Failure: Provides guidance and retry option

### SDK Integration

The app uses the `waltid-verify-sdk-android` SDK:

```kotlin
// Initialize client
val client = VerifyClient(VerifyConfig(
    apiKey = BuildConfig.VERIFY_API_KEY,
    baseUrl = BuildConfig.VERIFY_API_URL
))

// Start verification
val verification = client.verifyIdentity(VerificationRequest(
    template = "kyc-basic",
    responseMode = "answers"
))

// Display QR code
displayQrCode(verification.qrCodeData)

// Poll for result
val result = client.pollSessionWithUpdates(
    sessionId = verification.sessionId,
    intervalMs = 2000
) { status ->
    updateUI(status)
}
```

## Customization

### Verification Steps

Modify the steps in `KYCViewModel.kt`:

```kotlin
val verificationSteps = listOf(
    VerificationStep(
        id = "identity",
        title = "Identity Verification",
        description = "Verify your basic identity",
        template = "kyc-basic",  // Your template name
        icon = "person"
    ),
    // Add more steps...
)
```

### Templates

The `template` field corresponds to templates configured in your Verify API.
Common templates:
- `kyc-basic` - Name, date of birth
- `document-check` - ID document verification
- `address-proof` - Proof of address

### Theming

Customize colors in `ui/theme/Color.kt`:

```kotlin
val Primary = Color(0xFF1976D2)  // Your brand color
val Secondary = Color(0xFF26A69A)
```

## Network Configuration

### Emulator

The app uses `10.0.2.2` which maps to the host machine's localhost from the Android emulator.

### Physical Device

1. Ensure device and computer are on the same network
2. Update `VERIFY_API_URL` with your machine's IP address
3. The `network_security_config.xml` allows cleartext for local development

### Production

For production:
1. Use HTTPS with a valid certificate
2. Remove cleartext permissions from network security config
3. Store API key securely (e.g., in secrets manager or secure preferences)

## Testing

### Unit Tests

```bash
./gradlew test
```

### Instrumentation Tests

```bash
./gradlew connectedAndroidTest
```

### Manual Testing

1. Start the Verify API on your machine
2. Ensure templates are configured
3. Have a wallet app with test credentials ready
4. Run the app and complete the flow

## Troubleshooting

### "Failed to start verification"

- Check Verify API is running at the configured URL
- Verify API key is correct
- Check device has network access to API server

### QR Code Not Scanning

- Ensure wallet app supports OpenID4VP
- Check QR code data format matches wallet expectations
- Try the deep link alternative

### Polling Timeout

- Default timeout is 2 minutes
- Adjust `timeoutMs` in `pollSessionWithUpdates` if needed
- Check wallet app is responding to verification requests

### Network Issues on Physical Device

- Verify device and API server are on same network
- Check firewall rules allow connections
- Try accessing API URL in device browser first

## SDK Dependency

This example includes the SDK source code directly (`app/src/main/java/id/walt/verify/sdk/`).
This approach was chosen because the SDK is a standalone JVM library with Ktor dependencies
that work well on Android.

When the SDK is published to Maven, you can replace the embedded source with:

```kotlin
dependencies {
    // Replace embedded SDK with Maven dependency:
    implementation("id.walt:waltid-verify-sdk-android:1.0.0")
}
```

And remove the `app/src/main/java/id/walt/verify/sdk/` directory.

## License

Apache License 2.0
