# @waltid/verify-sdk

TypeScript/JavaScript SDK for the walt.id Verify API. Simplify identity verification using verifiable credentials in your web applications.

## Installation

```bash
npm install @waltid/verify-sdk
```

## Quick Start

```typescript
import { VerifyClient } from '@waltid/verify-sdk';

// Initialize the client
const client = new VerifyClient({
  apiKey: 'your-api-key',
  baseUrl: 'https://verify.yourdomain.com'
});

// Start a verification request
const verification = await client.verifyIdentity({
  template: 'kyc-basic',
  responseMode: 'answers'
});

// Display QR code to user
console.log('Scan QR code:', verification.qrCodeUrl);
// Or use deep link for same-device flow
console.log('Deep link:', verification.deepLink);

// Poll for result
const result = await client.pollSession(verification.sessionId);
if (result.status === 'verified') {
  console.log('Verified!', result.result?.answers);
}
```

## API Reference

### VerifyClient

The main client class for interacting with the Verify API.

#### Constructor

```typescript
const client = new VerifyClient({
  apiKey: string,      // Required: Your API key
  baseUrl?: string,    // Optional: API base URL
  fetch?: typeof fetch // Optional: Custom fetch implementation
});
```

#### Methods

##### verifyIdentity(request)

Initiate an identity verification request.

```typescript
const verification = await client.verifyIdentity({
  template: 'kyc-basic',           // Template name
  responseMode: 'answers',         // 'answers' or 'raw_credentials'
  redirectUri: 'https://...',      // Optional: redirect after verification
  metadata: { userId: '12345' }    // Optional: custom metadata
});

// Returns:
{
  sessionId: string,    // Unique session ID
  qrCodeUrl: string,    // URL to QR code image
  qrCodeData: string,   // Raw QR code data
  deepLink: string,     // Deep link for same-device
  expiresAt: number     // Unix timestamp
}
```

##### getSession(sessionId)

Get the current status of a verification session.

```typescript
const status = await client.getSession('session-123');

// Returns:
{
  sessionId: string,
  status: 'pending' | 'verified' | 'failed' | 'expired',
  templateName: string,
  result?: {
    answers?: Record<string, string>,
    credentials?: Array<{
      format: string,
      vct?: string,
      doctype?: string,
      disclosedClaims: Record<string, string>
    }>
  },
  verifiedAt?: number,
  metadata?: Record<string, string>,
  expiresAt: number
}
```

##### pollSession(sessionId, intervalMs?, timeoutMs?)

Poll a session until it reaches a terminal state.

```typescript
try {
  const status = await client.pollSession(
    'session-123',
    2000,   // Poll every 2 seconds (default)
    60000   // Timeout after 60 seconds
  );
  console.log('Final status:', status.status);
} catch (error) {
  if (error instanceof PollingTimeoutError) {
    console.log('Verification timed out');
  }
}
```

##### pollSessionIterator(sessionId, intervalMs?)

Async iterator for manual polling control.

```typescript
for await (const status of client.pollSessionIterator('session-123')) {
  console.log('Status update:', status.status);
  if (status.status !== 'pending') break;
}
```

## Error Handling

The SDK provides typed errors for better error handling:

```typescript
import { VerifyClient, VerifyError, PollingTimeoutError } from '@waltid/verify-sdk';

try {
  const result = await client.pollSession(sessionId);
} catch (error) {
  if (error instanceof PollingTimeoutError) {
    // User didn't complete verification in time
    console.log('Timeout:', error.message);
  } else if (error instanceof VerifyError) {
    // API error
    console.log('API Error:', error.message);
    console.log('Status Code:', error.statusCode);
    console.log('Error Code:', error.code);
  }
}
```

## Response Modes

### answers

Returns a flat map of field names to values, as defined in the template:

```typescript
const verification = await client.verifyIdentity({
  template: 'kyc-basic',
  responseMode: 'answers'
});

// After verification:
{
  result: {
    answers: {
      full_name: 'John Doe',
      date_of_birth: '1990-01-15',
      nationality: 'US'
    }
  }
}
```

### raw_credentials

Returns the full credential data:

```typescript
const verification = await client.verifyIdentity({
  template: 'kyc-basic',
  responseMode: 'raw_credentials'
});

// After verification:
{
  result: {
    credentials: [{
      format: 'dc+sd-jwt',
      vct: 'urn:eudi:pid:1',
      disclosedClaims: {
        'given_name': 'John',
        'family_name': 'Doe',
        'birth_date': '1990-01-15'
      }
    }]
  }
}
```

## Browser Usage

The SDK works in modern browsers with native `fetch` support:

```html
<script type="module">
  import { VerifyClient } from 'https://cdn.jsdelivr.net/npm/@waltid/verify-sdk/dist/index.js';

  const client = new VerifyClient({
    apiKey: 'your-api-key'
  });

  // ... use the client
</script>
```

## React Example

```tsx
import { useState, useEffect } from 'react';
import { VerifyClient, VerificationResponse, SessionStatus } from '@waltid/verify-sdk';

const client = new VerifyClient({
  apiKey: process.env.VERIFY_API_KEY!,
  baseUrl: process.env.VERIFY_API_URL
});

function VerificationFlow() {
  const [verification, setVerification] = useState<VerificationResponse | null>(null);
  const [status, setStatus] = useState<SessionStatus | null>(null);

  const startVerification = async () => {
    const result = await client.verifyIdentity({
      template: 'kyc-basic',
      responseMode: 'answers'
    });
    setVerification(result);

    // Start polling
    for await (const statusUpdate of client.pollSessionIterator(result.sessionId)) {
      setStatus(statusUpdate);
      if (statusUpdate.status !== 'pending') break;
    }
  };

  if (status?.status === 'verified') {
    return <div>Welcome, {status.result?.answers?.full_name}!</div>;
  }

  if (verification) {
    return (
      <div>
        <img src={verification.qrCodeUrl} alt="Scan to verify" />
        <p>Status: {status?.status || 'Waiting...'}</p>
      </div>
    );
  }

  return <button onClick={startVerification}>Verify Identity</button>;
}
```

## License

Apache-2.0
