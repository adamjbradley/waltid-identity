# Webhook Integration Guide

Webhooks provide real-time notifications when verification sessions complete. This is the recommended approach for production applications.

## Why Use Webhooks?

| Approach | Pros | Cons |
|----------|------|------|
| **Polling** | Simple to implement | Latency, resource-intensive |
| **Webhooks** | Instant, reliable, efficient | Requires public endpoint |

## Webhook Events

| Event | Description | Triggered When |
|-------|-------------|----------------|
| `session.verified` | User successfully verified | Wallet returns valid credentials |
| `session.failed` | Verification failed | Invalid credential, user cancelled |
| `session.expired` | Session timed out | No response before expiry |

## Webhook Payload Structure

```json
{
  "event": "session.verified",
  "sessionId": "vs_abc123def456",
  "timestamp": "2026-02-08T10:30:00Z",
  "status": "verified",
  "templateName": "kyc_basic",
  "metadata": {
    "userId": "user_123",
    "orderId": "order_456"
  },
  "result": {
    "answers": {
      "given_name": "John",
      "family_name": "Doe",
      "birth_date": "1990-01-15"
    }
  }
}
```

**Raw Credentials Mode:**

```json
{
  "event": "session.verified",
  "sessionId": "vs_abc123def456",
  "timestamp": "2026-02-08T10:30:00Z",
  "status": "verified",
  "templateName": "kyc_full",
  "metadata": {},
  "result": {
    "credentials": [{
      "format": "dc+sd-jwt",
      "vct": "urn:eudi:pid:1",
      "issuer": "https://issuer.example.com",
      "disclosedClaims": {
        "given_name": "John",
        "family_name": "Doe",
        "birth_date": "1990-01-15",
        "nationality": "DE"
      }
    }]
  }
}
```

## Webhook Security

### Signature Verification

Every webhook includes cryptographic signatures for verification:

| Header | Description |
|--------|-------------|
| `X-Verify-Signature` | HMAC-SHA256 signature |
| `X-Verify-Timestamp` | ISO 8601 timestamp |

**Signature Format:**
```
HMAC-SHA256(secret, "{timestamp}.{body}")
```

### Verification Algorithm

```typescript
import crypto from 'crypto';

function verifyWebhookSignature(
  body: Buffer,
  signature: string,
  timestamp: string,
  secret: string
): boolean {
  // Construct signed payload
  const signedPayload = `${timestamp}.${body.toString()}`;

  // Compute expected signature
  const expectedSignature = crypto
    .createHmac('sha256', secret)
    .update(signedPayload)
    .digest('hex');

  // Constant-time comparison (prevents timing attacks)
  return crypto.timingSafeEqual(
    Buffer.from(signature),
    Buffer.from(expectedSignature)
  );
}
```

### Timestamp Validation

Reject webhooks with old timestamps to prevent replay attacks:

```typescript
const MAX_AGE_SECONDS = 300; // 5 minutes

function validateTimestamp(timestamp: string): boolean {
  const webhookTime = new Date(timestamp).getTime();
  const now = Date.now();
  const ageSeconds = Math.abs(now - webhookTime) / 1000;

  return ageSeconds <= MAX_AGE_SECONDS;
}
```

## Implementation Examples

### Node.js / Express

```typescript
import express from 'express';
import crypto from 'crypto';

const app = express();

// IMPORTANT: Use raw body for signature verification
app.post('/webhooks/verify',
  express.raw({ type: 'application/json' }),
  async (req, res) => {
    const signature = req.headers['x-verify-signature'] as string;
    const timestamp = req.headers['x-verify-timestamp'] as string;

    // 1. Verify signature
    if (!verifyWebhookSignature(req.body, signature, timestamp)) {
      console.error('Invalid webhook signature');
      return res.status(401).json({ error: 'Invalid signature' });
    }

    // 2. Validate timestamp
    if (!validateTimestamp(timestamp)) {
      console.error('Webhook timestamp too old');
      return res.status(400).json({ error: 'Stale webhook' });
    }

    // 3. Parse payload
    const payload = JSON.parse(req.body.toString());

    // 4. Idempotency check
    const alreadyProcessed = await db.processedWebhooks.findUnique({
      where: { sessionId: payload.sessionId }
    });

    if (alreadyProcessed) {
      // Already processed - return success
      return res.sendStatus(200);
    }

    // 5. Process the event
    try {
      await processVerificationEvent(payload);

      // 6. Mark as processed
      await db.processedWebhooks.create({
        data: {
          sessionId: payload.sessionId,
          event: payload.event,
          processedAt: new Date()
        }
      });

      res.sendStatus(200);
    } catch (error) {
      console.error('Webhook processing error:', error);
      // Return 500 to trigger retry
      res.status(500).json({ error: 'Processing failed' });
    }
  }
);

function verifyWebhookSignature(
  body: Buffer,
  signature: string,
  timestamp: string
): boolean {
  const secret = process.env.VERIFY_WEBHOOK_SECRET!;
  const signedPayload = `${timestamp}.${body.toString()}`;

  const expectedSignature = crypto
    .createHmac('sha256', secret)
    .update(signedPayload)
    .digest('hex');

  return crypto.timingSafeEqual(
    Buffer.from(signature),
    Buffer.from(expectedSignature)
  );
}

function validateTimestamp(timestamp: string): boolean {
  const ageMs = Math.abs(Date.now() - new Date(timestamp).getTime());
  return ageMs <= 300000; // 5 minutes
}

async function processVerificationEvent(payload: WebhookPayload) {
  const { event, sessionId, metadata, result } = payload;

  switch (event) {
    case 'session.verified':
      await handleVerified(sessionId, metadata, result);
      break;
    case 'session.failed':
      await handleFailed(sessionId, metadata);
      break;
    case 'session.expired':
      await handleExpired(sessionId, metadata);
      break;
  }
}

async function handleVerified(
  sessionId: string,
  metadata: Record<string, string>,
  result: VerificationResult
) {
  const { userId } = metadata;

  // Update user with verified data
  await db.users.update({
    where: { id: userId },
    data: {
      kycStatus: 'verified',
      kycVerifiedAt: new Date(),
      givenName: result.answers?.given_name,
      familyName: result.answers?.family_name
    }
  });

  // Emit internal event
  await eventBus.emit('user.verified', { userId, sessionId });

  // Send notification
  await sendEmail(userId, 'verification_complete');
}

async function handleFailed(
  sessionId: string,
  metadata: Record<string, string>
) {
  const { userId } = metadata;

  await db.verificationAttempts.create({
    data: {
      userId,
      sessionId,
      status: 'failed',
      failedAt: new Date()
    }
  });

  await sendEmail(userId, 'verification_failed');
}

async function handleExpired(
  sessionId: string,
  metadata: Record<string, string>
) {
  const { userId } = metadata;

  await db.verificationAttempts.create({
    data: {
      userId,
      sessionId,
      status: 'expired',
      expiredAt: new Date()
    }
  });

  // Send reminder to complete verification
  await sendEmail(userId, 'verification_reminder');
}
```

### Python / Flask

```python
import hmac
import hashlib
import json
from datetime import datetime, timedelta
from flask import Flask, request, jsonify

app = Flask(__name__)

WEBHOOK_SECRET = os.environ['VERIFY_WEBHOOK_SECRET']
MAX_AGE = timedelta(minutes=5)

@app.route('/webhooks/verify', methods=['POST'])
def handle_webhook():
    signature = request.headers.get('X-Verify-Signature')
    timestamp = request.headers.get('X-Verify-Timestamp')
    body = request.get_data()

    # Verify signature
    if not verify_signature(body, signature, timestamp):
        return jsonify({'error': 'Invalid signature'}), 401

    # Validate timestamp
    if not validate_timestamp(timestamp):
        return jsonify({'error': 'Stale webhook'}), 400

    # Parse payload
    payload = json.loads(body)

    # Idempotency check
    if ProcessedWebhook.query.filter_by(session_id=payload['sessionId']).first():
        return '', 200

    # Process event
    try:
        process_event(payload)

        # Mark as processed
        db.session.add(ProcessedWebhook(
            session_id=payload['sessionId'],
            event=payload['event']
        ))
        db.session.commit()

        return '', 200
    except Exception as e:
        app.logger.error(f'Webhook processing failed: {e}')
        return jsonify({'error': 'Processing failed'}), 500


def verify_signature(body: bytes, signature: str, timestamp: str) -> bool:
    signed_payload = f"{timestamp}.{body.decode()}"
    expected = hmac.new(
        WEBHOOK_SECRET.encode(),
        signed_payload.encode(),
        hashlib.sha256
    ).hexdigest()

    return hmac.compare_digest(signature, expected)


def validate_timestamp(timestamp: str) -> bool:
    webhook_time = datetime.fromisoformat(timestamp.replace('Z', '+00:00'))
    age = abs(datetime.now(webhook_time.tzinfo) - webhook_time)
    return age <= MAX_AGE


def process_event(payload: dict):
    event = payload['event']
    session_id = payload['sessionId']
    metadata = payload.get('metadata', {})
    result = payload.get('result')

    if event == 'session.verified':
        handle_verified(session_id, metadata, result)
    elif event == 'session.failed':
        handle_failed(session_id, metadata)
    elif event == 'session.expired':
        handle_expired(session_id, metadata)
```

### Swift / Vapor

```swift
import Vapor
import Crypto

func configureWebhooks(_ app: Application) throws {
    app.post("webhooks", "verify") { req async throws -> HTTPStatus in
        guard let body = req.body.data,
              let signature = req.headers.first(name: "X-Verify-Signature"),
              let timestamp = req.headers.first(name: "X-Verify-Timestamp") else {
            throw Abort(.badRequest)
        }

        // Verify signature
        guard verifySignature(body: body, signature: signature, timestamp: timestamp) else {
            throw Abort(.unauthorized)
        }

        // Validate timestamp
        guard validateTimestamp(timestamp) else {
            throw Abort(.badRequest, reason: "Stale webhook")
        }

        // Parse payload
        let payload = try JSONDecoder().decode(WebhookPayload.self, from: body)

        // Idempotency check
        if try await ProcessedWebhook.query(on: req.db)
            .filter(\.$sessionId == payload.sessionId)
            .first() != nil {
            return .ok
        }

        // Process event
        try await processEvent(payload: payload, db: req.db)

        // Mark as processed
        try await ProcessedWebhook(
            sessionId: payload.sessionId,
            event: payload.event
        ).save(on: req.db)

        return .ok
    }
}

func verifySignature(body: ByteBuffer, signature: String, timestamp: String) -> Bool {
    let secret = Environment.get("VERIFY_WEBHOOK_SECRET")!

    var bodyData = body
    let bodyString = bodyData.readString(length: body.readableBytes) ?? ""
    let signedPayload = "\(timestamp).\(bodyString)"

    let key = SymmetricKey(data: secret.data(using: .utf8)!)
    let expectedSignature = HMAC<SHA256>.authenticationCode(
        for: signedPayload.data(using: .utf8)!,
        using: key
    )

    let expectedHex = expectedSignature.map { String(format: "%02x", $0) }.joined()
    return signature == expectedHex
}

func validateTimestamp(_ timestamp: String) -> Bool {
    guard let webhookDate = ISO8601DateFormatter().date(from: timestamp) else {
        return false
    }
    let age = abs(Date().timeIntervalSince(webhookDate))
    return age <= 300 // 5 minutes
}
```

### Kotlin / Spring Boot

```kotlin
import org.springframework.web.bind.annotation.*
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import java.time.Instant
import java.time.Duration

@RestController
@RequestMapping("/webhooks")
class WebhookController(
    private val webhookService: WebhookService,
    private val processedWebhookRepository: ProcessedWebhookRepository
) {
    private val webhookSecret = System.getenv("VERIFY_WEBHOOK_SECRET")
    private val maxAge = Duration.ofMinutes(5)

    @PostMapping("/verify")
    fun handleWebhook(
        @RequestBody body: String,
        @RequestHeader("X-Verify-Signature") signature: String,
        @RequestHeader("X-Verify-Timestamp") timestamp: String
    ): ResponseEntity<Void> {
        // Verify signature
        if (!verifySignature(body, signature, timestamp)) {
            return ResponseEntity.status(401).build()
        }

        // Validate timestamp
        if (!validateTimestamp(timestamp)) {
            return ResponseEntity.badRequest().build()
        }

        // Parse payload
        val payload = objectMapper.readValue(body, WebhookPayload::class.java)

        // Idempotency check
        if (processedWebhookRepository.existsBySessionId(payload.sessionId)) {
            return ResponseEntity.ok().build()
        }

        // Process event
        when (payload.event) {
            "session.verified" -> webhookService.handleVerified(payload)
            "session.failed" -> webhookService.handleFailed(payload)
            "session.expired" -> webhookService.handleExpired(payload)
        }

        // Mark as processed
        processedWebhookRepository.save(ProcessedWebhook(
            sessionId = payload.sessionId,
            event = payload.event
        ))

        return ResponseEntity.ok().build()
    }

    private fun verifySignature(body: String, signature: String, timestamp: String): Boolean {
        val signedPayload = "$timestamp.$body"

        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(webhookSecret.toByteArray(), "HmacSHA256"))

        val expectedSignature = mac.doFinal(signedPayload.toByteArray())
            .joinToString("") { "%02x".format(it) }

        return MessageDigest.isEqual(
            signature.toByteArray(),
            expectedSignature.toByteArray()
        )
    }

    private fun validateTimestamp(timestamp: String): Boolean {
        val webhookTime = Instant.parse(timestamp)
        val age = Duration.between(webhookTime, Instant.now()).abs()
        return age <= maxAge
    }
}
```

## Webhook Registration

### Via API

```typescript
import { VerifyClient } from '@waltid/verify-sdk';

const client = new VerifyClient({ apiKey: 'vfy_live_xxx' });

// Register webhook
const webhook = await client.createWebhook({
  url: 'https://your-domain.com/webhooks/verify',
  events: ['session.verified', 'session.failed', 'session.expired'],
  secret: 'your-webhook-secret' // Optional - auto-generated if not provided
});

console.log('Webhook ID:', webhook.id);
console.log('Secret:', webhook.secret);

// List webhooks
const webhooks = await client.listWebhooks();

// Delete webhook
await client.deleteWebhook(webhook.id);
```

### Via Portal

1. Navigate to Settings > Webhooks
2. Click "Add Endpoint"
3. Enter your endpoint URL
4. Select events to subscribe to
5. Copy the generated secret

## Retry Policy

Failed webhook deliveries are retried with exponential backoff:

| Attempt | Delay |
|---------|-------|
| 1 | Immediate |
| 2 | 1 minute |
| 3 | 5 minutes |
| 4 | 30 minutes |
| 5 | 2 hours |
| 6 | 8 hours |
| 7 | 24 hours |

After 7 failed attempts, the webhook is marked as failed.

**Return Codes:**
- `2xx` - Success, no retry
- `4xx` - Client error, no retry (except 408, 429)
- `5xx` - Server error, will retry

## Testing Webhooks

### Local Development

Use ngrok or similar to expose your local server:

```bash
ngrok http 3000

# Use the ngrok URL for webhook registration
# https://abc123.ngrok.io/webhooks/verify
```

### Test Webhook Delivery

```typescript
// Send a test webhook
await client.testWebhook({
  webhookId: 'wh_abc123',
  event: 'session.verified',
  payload: {
    sessionId: 'test_session_123',
    metadata: { userId: 'test_user' },
    result: {
      answers: {
        given_name: 'Test',
        family_name: 'User'
      }
    }
  }
});
```

### Unit Testing

```typescript
import { createHmac } from 'crypto';

describe('Webhook Handler', () => {
  const secret = 'test_secret';

  function generateSignature(body: string, timestamp: string): string {
    return createHmac('sha256', secret)
      .update(`${timestamp}.${body}`)
      .digest('hex');
  }

  it('should accept valid signatures', async () => {
    const body = JSON.stringify({ event: 'session.verified' });
    const timestamp = new Date().toISOString();
    const signature = generateSignature(body, timestamp);

    const response = await request(app)
      .post('/webhooks/verify')
      .set('X-Verify-Signature', signature)
      .set('X-Verify-Timestamp', timestamp)
      .send(body);

    expect(response.status).toBe(200);
  });

  it('should reject invalid signatures', async () => {
    const body = JSON.stringify({ event: 'session.verified' });
    const timestamp = new Date().toISOString();

    const response = await request(app)
      .post('/webhooks/verify')
      .set('X-Verify-Signature', 'invalid')
      .set('X-Verify-Timestamp', timestamp)
      .send(body);

    expect(response.status).toBe(401);
  });

  it('should reject stale timestamps', async () => {
    const body = JSON.stringify({ event: 'session.verified' });
    const oldTimestamp = new Date(Date.now() - 600000).toISOString(); // 10 min ago
    const signature = generateSignature(body, oldTimestamp);

    const response = await request(app)
      .post('/webhooks/verify')
      .set('X-Verify-Signature', signature)
      .set('X-Verify-Timestamp', oldTimestamp)
      .send(body);

    expect(response.status).toBe(400);
  });

  it('should handle duplicate deliveries', async () => {
    const body = JSON.stringify({
      event: 'session.verified',
      sessionId: 'vs_duplicate_test'
    });
    const timestamp = new Date().toISOString();
    const signature = generateSignature(body, timestamp);

    // First request
    await request(app)
      .post('/webhooks/verify')
      .set('X-Verify-Signature', signature)
      .set('X-Verify-Timestamp', timestamp)
      .send(body);

    // Second request (duplicate)
    const response = await request(app)
      .post('/webhooks/verify')
      .set('X-Verify-Signature', signature)
      .set('X-Verify-Timestamp', timestamp)
      .send(body);

    expect(response.status).toBe(200);
    // Verify only processed once
  });
});
```

## Best Practices

1. **Always verify signatures** - Never trust unverified webhooks
2. **Validate timestamps** - Prevent replay attacks with 5-minute tolerance
3. **Implement idempotency** - Handle duplicate deliveries gracefully
4. **Respond quickly** - Return 200 within 30 seconds
5. **Process asynchronously** - Queue events for background processing
6. **Log everything** - Maintain audit trail of all events
7. **Monitor failures** - Alert on repeated delivery failures
8. **Handle all events** - Even if you only care about `verified`

## Troubleshooting

| Issue | Solution |
|-------|----------|
| Invalid signature | Verify secret matches, check timestamp format |
| Missing webhooks | Ensure endpoint is publicly accessible |
| Duplicate processing | Implement idempotency with session ID |
| Timeout errors | Process async, return 200 immediately |
| Parse errors | Validate payload structure before processing |
| Retries not stopping | Return 2xx status code on success |
