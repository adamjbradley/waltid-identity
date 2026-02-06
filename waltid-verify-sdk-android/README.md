# walt.id Verify SDK for Android

A Kotlin SDK for integrating identity verification using verifiable credentials in Android applications.

## Features

- Simple API for initiating verification requests
- Session polling with configurable timeout and interval
- Supports both cross-device (QR code) and same-device (deep link) flows
- Type-safe data classes for all API responses
- Coroutine-based async API
- Built on Ktor HTTP client with OkHttp engine

## Installation

Add the dependency to your `build.gradle.kts`:

```kotlin
dependencies {
    implementation("id.walt:waltid-verify-sdk-android:1.0.0-SNAPSHOT")
}
```

Or build from source:

```bash
./gradlew :waltid-verify-sdk-android:build
```

## Quick Start

```kotlin
import id.walt.verify.sdk.*
import kotlinx.coroutines.runBlocking

fun main() = runBlocking {
    // Initialize the client
    val client = VerifyClient(VerifyConfig(
        apiKey = "your-api-key",
        baseUrl = "https://verify.yourdomain.com"
    ))

    // Start verification
    val verification = client.verifyIdentity(VerificationRequest(
        template = "kyc-basic",
        responseMode = "answers"
    ))

    println("Session ID: ${verification.sessionId}")
    println("QR Code URL: ${verification.qrCodeUrl}")
    println("Deep Link: ${verification.deepLink}")

    // Poll for result
    val result = client.pollSession(verification.sessionId)

    when {
        result.isVerified -> {
            println("Verification successful!")
            result.result?.answers?.forEach { (key, value) ->
                println("  $key: $value")
            }
        }
        result.isFailed -> println("Verification failed")
        result.isExpired -> println("Session expired")
    }

    // Clean up
    client.close()
}
```

## API Reference

### VerifyConfig

Configuration for the VerifyClient.

```kotlin
data class VerifyConfig(
    val apiKey: String,          // Required: API key for authentication
    val baseUrl: String = "..."  // Optional: Base URL of the Verify API
)
```

### VerificationRequest

Request to initiate identity verification.

```kotlin
data class VerificationRequest(
    val template: String,           // Required: Template name
    val responseMode: String?,      // Optional: "answers" or "raw_credentials"
    val redirectUri: String?,       // Optional: Redirect URI for same-device flow
    val metadata: Map<String, String>?  // Optional: Custom metadata
)
```

### VerificationResponse

Response from initiating a verification request.

```kotlin
data class VerificationResponse(
    val sessionId: String,   // Unique session ID (vs_xxxx format)
    val qrCodeUrl: String,   // URL to QR code image
    val qrCodeData: String,  // Raw QR code data (openid4vp:// URL)
    val deepLink: String,    // Deep link for same-device flow
    val expiresAt: Long      // Expiration timestamp (epoch millis)
)
```

### SessionStatus

Session status with verification result.

```kotlin
data class SessionStatus(
    val sessionId: String,
    val status: String,              // "pending", "verified", "failed", "expired"
    val templateName: String,
    val result: SessionResult?,      // Present when verified
    val verifiedAt: Long?,
    val metadata: Map<String, String>?,
    val expiresAt: Long
) {
    val isPending: Boolean    // Check if still waiting
    val isVerified: Boolean   // Check if completed successfully
    val isFailed: Boolean     // Check if failed
    val isExpired: Boolean    // Check if expired
    val isTerminal: Boolean   // Check if reached terminal state
}
```

### SessionResult

Verification result data.

```kotlin
data class SessionResult(
    val answers: Map<String, String>?,     // When responseMode is "answers"
    val credentials: List<Credential>?     // When responseMode is "raw_credentials"
)
```

### Credential

Disclosed credential data.

```kotlin
data class Credential(
    val format: String,                    // e.g., "dc+sd-jwt", "mso_mdoc"
    val vct: String?,                      // Verifiable Credential Type (SD-JWT)
    val doctype: String?,                  // Document type (mDoc)
    val disclosedClaims: Map<String, String>
)
```

## Usage Examples

### Basic Verification Flow

```kotlin
val client = VerifyClient(VerifyConfig(apiKey = "your-key"))

// Start verification
val verification = client.verifyIdentity(VerificationRequest(
    template = "kyc-basic"
))

// Show QR code to user (e.g., using a QR code library)
displayQrCode(verification.qrCodeUrl)

// Wait for completion
val status = client.pollSession(verification.sessionId)
handleResult(status)

client.close()
```

### Same-Device Flow (Android)

```kotlin
val verification = client.verifyIdentity(VerificationRequest(
    template = "kyc-basic",
    redirectUri = "myapp://verification-callback"
))

// Open wallet app via deep link
val intent = Intent(Intent.ACTION_VIEW, Uri.parse(verification.deepLink))
startActivity(intent)
```

### Polling with UI Updates

```kotlin
val status = client.pollSessionWithUpdates(
    sessionId = verification.sessionId,
    intervalMs = 2000,
    timeoutMs = 120000
) { status ->
    runOnUiThread {
        statusText.text = "Status: ${status.status}"
    }
}

runOnUiThread {
    when {
        status.isVerified -> showSuccess(status.result)
        status.isFailed -> showError("Verification failed")
        status.isExpired -> showError("Session expired")
    }
}
```

### Error Handling

```kotlin
try {
    val verification = client.verifyIdentity(request)
    val status = client.pollSession(verification.sessionId)
} catch (e: VerifyException) {
    when (e.statusCode) {
        401 -> showError("Invalid API key")
        404 -> showError("Template not found")
        else -> showError("Error: ${e.message}")
    }
} catch (e: PollingTimeoutException) {
    showError("Verification timed out")
}
```

### Using with ViewModel

```kotlin
class VerificationViewModel : ViewModel() {
    private val client = VerifyClient(VerifyConfig(apiKey = BuildConfig.VERIFY_API_KEY))

    private val _state = MutableStateFlow<VerificationState>(VerificationState.Idle)
    val state: StateFlow<VerificationState> = _state

    fun startVerification(template: String) {
        viewModelScope.launch {
            _state.value = VerificationState.Loading

            try {
                val verification = client.verifyIdentity(
                    VerificationRequest(template = template)
                )
                _state.value = VerificationState.WaitingForScan(verification)

                val status = client.pollSession(verification.sessionId)
                _state.value = when {
                    status.isVerified -> VerificationState.Success(status.result!!)
                    else -> VerificationState.Failed(status.status)
                }
            } catch (e: Exception) {
                _state.value = VerificationState.Error(e.message ?: "Unknown error")
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
    data class WaitingForScan(val verification: VerificationResponse) : VerificationState()
    data class Success(val result: SessionResult) : VerificationState()
    data class Failed(val status: String) : VerificationState()
    data class Error(val message: String) : VerificationState()
}
```

## Response Modes

### answers (default)

Returns mapped claim values based on the template configuration:

```kotlin
val request = VerificationRequest(
    template = "kyc-basic",
    responseMode = "answers"
)

// Result:
status.result?.answers
// { "full_name": "John Doe", "date_of_birth": "1990-01-15" }
```

### raw_credentials

Returns full credential data with all disclosed claims:

```kotlin
val request = VerificationRequest(
    template = "kyc-full",
    responseMode = "raw_credentials"
)

// Result:
status.result?.credentials?.forEach { credential ->
    println("Format: ${credential.format}")
    println("Type: ${credential.vct ?: credential.doctype}")
    credential.disclosedClaims.forEach { (claim, value) ->
        println("  $claim: $value")
    }
}
```

## License

Apache License 2.0
