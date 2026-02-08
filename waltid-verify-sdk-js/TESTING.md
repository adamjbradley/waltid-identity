# Testing the JavaScript/TypeScript SDK

## Prerequisites

- Node.js 18+
- npm or yarn

## Install Dependencies

```bash
cd waltid-verify-sdk-js
npm install
```

## Running Tests

### Unit Tests

Run all unit tests with mocked HTTP responses:

```bash
npm test
```

Or for just unit tests without integration tests:

```bash
npm run test:unit
```

### Test Coverage

Generate test coverage report:

```bash
npm run test:coverage
```

Coverage report will be available in `coverage/` directory.

### Integration Tests

Integration tests require a running Verify API. Start the API first:

```bash
# From docker-compose directory
docker compose --profile identity up -d waltid-verify-api
```

Then run integration tests:

```bash
npm run test:integration
```

Or with custom API URL:

```bash
VERIFY_API_URL=https://verify.example.com VERIFY_API_KEY=your_key npm run test:integration
```

## Sandbox Credentials

The SDK tests use the following sandbox credentials:

| Credential Type | Value |
|-----------------|-------|
| Test API Key | `vfy_test_sandbox_demo_key_12345678` |
| Live API Key | `vfy_live_sandbox_demo_key_12345678` |
| Default API URL | `http://localhost:7010` |

## Test Structure

- `src/__tests__/VerifyClient.test.ts` - Main test file
  - Configuration tests
  - Request/response type tests
  - verifyIdentity tests
  - getSession tests
  - pollSession tests
  - Error handling tests
  - Integration tests (skipped by default)

## Environment Variables

| Variable | Description | Default |
|----------|-------------|---------|
| `RUN_INTEGRATION_TESTS` | Enable integration tests | `false` |
| `VERIFY_API_URL` | API base URL | `http://localhost:7010` |
| `VERIFY_API_KEY` | API key for tests | Sandbox test key |
