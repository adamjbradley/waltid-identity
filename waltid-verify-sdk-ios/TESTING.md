# Testing the Swift/iOS SDK

## Prerequisites

- Xcode 15+ or Swift 5.9+
- macOS 12+ / iOS 15+

## Running Tests

### All Tests

Run all unit tests from the command line:

```bash
cd waltid-verify-sdk-ios
swift test
```

Or open in Xcode:

```bash
open Package.swift
# Then use Cmd+U to run tests
```

### Specific Test Targets

Run specific test classes:

```bash
swift test --filter VerifyClientTests
swift test --filter VerifyClientMockTests
```

### Integration Tests

Integration tests require a running Verify API and are disabled by default.

To enable integration tests:

```bash
RUN_INTEGRATION_TESTS=true swift test
```

With custom API URL:

```bash
RUN_INTEGRATION_TESTS=true \
VERIFY_API_URL=https://verify.example.com \
VERIFY_API_KEY=your_key \
swift test
```

## Sandbox Credentials

The SDK tests use the following sandbox credentials:

| Credential Type | Value |
|-----------------|-------|
| Test API Key | `vfy_test_sandbox_demo_key_12345678` |
| Live API Key | `vfy_live_sandbox_demo_key_12345678` |
| Default API URL | `http://localhost:7010` |

## Test Structure

- `Tests/WaltIDVerifySDKTests/VerifyClientTests.swift` - Main test file
  - `VerifyClientTests` - Configuration, types, URL construction tests
  - `VerifyClientMockTests` - Tests with mocked URLProtocol
  - `VerifyClientIntegrationTests` - Live API tests (disabled by default)

### Test Categories

| Category | Description |
|----------|-------------|
| Config tests | VerifyConfig initialization |
| Type tests | Request/response encoding/decoding |
| Error tests | VerifyError cases |
| Mock tests | Mocked HTTP requests/responses |
| Integration | Live API (requires `RUN_INTEGRATION_TESTS=true`) |

## Environment Variables

| Variable | Description | Default |
|----------|-------------|---------|
| `RUN_INTEGRATION_TESTS` | Enable integration tests | Not set (disabled) |
| `VERIFY_API_URL` | API base URL | `http://localhost:7010` |
| `VERIFY_API_KEY` | API key for tests | Sandbox test key |
