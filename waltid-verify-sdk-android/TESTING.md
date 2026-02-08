# Testing the Kotlin/Android SDK

## Prerequisites

- JDK 17+
- Gradle 9.0+

## Running Tests

### All Tests

Run all unit tests:

```bash
cd waltid-verify-sdk-android
gradle test
```

Or with the wrapper:

```bash
./gradlew test
```

### Specific Test Classes

Run specific test classes:

```bash
gradle test --tests "id.walt.verify.sdk.VerifyClientTest"
gradle test --tests "id.walt.verify.sdk.VerifyClientIntegrationTest"
```

Run specific test methods:

```bash
gradle test --tests "*VerifyConfigTests*"
gradle test --tests "*should create config with valid API key*"
```

### Test Report

After running tests, view the HTML report at:

```
build/reports/tests/test/index.html
```

### Integration Tests

Integration tests require a running Verify API and are disabled by default.

To enable integration tests:

```bash
RUN_INTEGRATION_TESTS=true gradle test
```

With custom API URL:

```bash
RUN_INTEGRATION_TESTS=true \
VERIFY_API_URL=https://verify.example.com \
VERIFY_API_KEY=your_key \
gradle test
```

## Sandbox Credentials

The SDK tests use the following sandbox credentials:

| Credential Type | Value |
|-----------------|-------|
| Test API Key | `vfy_test_sandbox_demo_key_12345678` |
| Live API Key | `vfy_live_sandbox_demo_key_12345678` |
| Default API URL | `http://localhost:7010` |

## Test Structure

- `src/test/kotlin/id/walt/verify/sdk/VerifyClientTest.kt` - Main test file
  - `VerifyConfigTests` - Configuration tests
  - `VerificationRequestTests` - Request serialization tests
  - `VerificationResponseTests` - Response deserialization tests
  - `SessionStatusTests` - Status parsing and state checks
  - `ExceptionTests` - Exception types and messages
  - `VerifyClientMockTests` - Tests with MockEngine
  - `ResourceManagementTests` - Closeable interface tests

- `src/test/kotlin/id/walt/verify/sdk/VerifyClientIntegrationTest.kt` - Integration tests

### Test Categories

| Category | Description |
|----------|-------------|
| Config tests | VerifyConfig initialization and validation |
| Request tests | JSON serialization of requests |
| Response tests | JSON deserialization of responses |
| Status tests | Session status flags (isPending, isVerified, etc.) |
| Exception tests | VerifyException, PollingTimeoutException |
| Mock tests | HTTP request/response with Ktor MockEngine |
| Integration | Live API (requires `RUN_INTEGRATION_TESTS=true`) |

## Environment Variables

| Variable | Description | Default |
|----------|-------------|---------|
| `RUN_INTEGRATION_TESTS` | Enable integration tests | Not set (disabled) |
| `VERIFY_API_URL` | API base URL | `http://localhost:7010` |
| `VERIFY_API_KEY` | API key for tests | Sandbox test key |

## Dependencies

Test dependencies (automatically included):

- JUnit 5 (Jupiter)
- Kotlinx Coroutines Test
- Ktor Client Mock Engine
