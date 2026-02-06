# Webhook Integration Example

This guide shows how to implement webhook handling for the Verify API, enabling real-time notifications when verification sessions complete.

## Use Case

Webhooks provide immediate notification when:
- A user completes identity verification
- A verification session expires
- A verification fails

This is more reliable and efficient than polling, especially for production applications.

## Webhook Events

| Event | Description |
|-------|-------------|
| `session.verified` | User successfully verified identity |
| `session.failed` | Verification failed (invalid credential, user cancelled) |
| `session.expired` | Session timed out before completion |

## Webhook Payload

```json
{
  "event": "session.verified",
  "sessionId": "sess_abc123",
  "status": "verified",
  "timestamp": "2024-01-15T10:30:00Z",
  "metadata": {
    "userId": "user_123",
    "purpose": "kyc_onboarding"
  },
  "credentials": [
    {
      "format": "dc+sd-jwt",
      "type": "urn:eudi:pid:1",
      "issuer": "https://issuer.example.com",
      "claims": {
        "given_name": "John",
        "family_name": "Doe",
        "birth_date": "1990-01-15"
      }
    }
  ]
}
```

## TypeScript Implementation

### Express.js Webhook Handler

```typescript
import express from 'express';
import crypto from 'crypto';

const app = express();

// IMPORTANT: Use raw body for signature verification
app.post('/webhooks/verify',
  express.raw({ type: 'application/json' }),
  async (req, res) => {
    // 1. Verify webhook signature
    const signature = req.headers['x-verify-signature'] as string;
    const timestamp = req.headers['x-verify-timestamp'] as string;

    if (!verifyWebhookSignature(req.body, signature, timestamp)) {
      console.error('Invalid webhook signature');
      return res.status(401).json({ error: 'Invalid signature' });
    }

    // 2. Parse the payload
    const payload = JSON.parse(req.body.toString());

    // 3. Check for replay attacks (optional but recommended)
    const eventAge = Date.now() - new Date(timestamp).getTime();
    if (eventAge > 300000) { // 5 minutes
      console.error('Webhook timestamp too old');
      return res.status(400).json({ error: 'Timestamp too old' });
    }

    // 4. Idempotency check
    const processed = await db.processedWebhooks.findUnique({
      where: { sessionId: payload.sessionId }
    });

    if (processed) {
      // Already processed, return success
      return res.sendStatus(200);
    }

    // 5. Process the event
    try {
      await handleVerificationEvent(payload);

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
      console.error('Webhook processing failed:', error);
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

  // Construct the signed payload
  const signedPayload = `${timestamp}.${body.toString()}`;

  // Compute expected signature
  const expectedSignature = crypto
    .createHmac('sha256', secret)
    .update(signedPayload)
    .digest('hex');

  // Constant-time comparison
  return crypto.timingSafeEqual(
    Buffer.from(signature),
    Buffer.from(expectedSignature)
  );
}

async function handleVerificationEvent(payload: WebhookPayload) {
  const { event, sessionId, metadata, credentials } = payload;

  switch (event) {
    case 'session.verified':
      await handleVerified(sessionId, metadata, credentials);
      break;

    case 'session.failed':
      await handleFailed(sessionId, metadata);
      break;

    case 'session.expired':
      await handleExpired(sessionId, metadata);
      break;

    default:
      console.warn('Unknown event type:', event);
  }
}

async function handleVerified(
  sessionId: string,
  metadata: Record<string, string>,
  credentials: VerifiedCredential[]
) {
  const { userId, purpose } = metadata;

  // Extract verified claims
  const credential = credentials[0];
  const verifiedData = {
    givenName: credential.claims.given_name,
    familyName: credential.claims.family_name,
    birthDate: credential.claims.birth_date
  };

  // Update user record based on purpose
  switch (purpose) {
    case 'kyc_onboarding':
      await db.users.update({
        where: { id: userId },
        data: {
          kycStatus: 'verified',
          kycVerifiedAt: new Date(),
          ...verifiedData
        }
      });
      await sendEmail(userId, 'kyc_verified');
      break;

    case 'payment_authorization':
      const transactionId = metadata.transactionId;
      await processAuthorizedPayment(transactionId);
      break;

    case 'age_verification':
      await db.users.update({
        where: { id: userId },
        data: { ageVerified: true }
      });
      break;
  }

  // Emit internal event
  await eventBus.emit('verification.completed', {
    sessionId,
    userId,
    purpose
  });
}

async function handleFailed(
  sessionId: string,
  metadata: Record<string, string>
) {
  const { userId, purpose } = metadata;

  await db.verificationAttempts.create({
    data: {
      userId,
      sessionId,
      purpose,
      status: 'failed',
      failedAt: new Date()
    }
  });

  // Notify user
  await sendEmail(userId, 'verification_failed');

  // Alert on suspicious patterns
  const recentFailures = await db.verificationAttempts.count({
    where: {
      userId,
      status: 'failed',
      failedAt: { gte: subHours(new Date(), 1) }
    }
  });

  if (recentFailures >= 3) {
    await alertSecurityTeam(userId, 'multiple_verification_failures');
  }
}

async function handleExpired(
  sessionId: string,
  metadata: Record<string, string>
) {
  const { userId, purpose } = metadata;

  await db.verificationAttempts.create({
    data: {
      userId,
      sessionId,
      purpose,
      status: 'expired',
      expiredAt: new Date()
    }
  });

  // Send reminder to complete verification
  await sendEmail(userId, 'verification_reminder');
}

interface WebhookPayload {
  event: string;
  sessionId: string;
  status: string;
  timestamp: string;
  metadata: Record<string, string>;
  credentials?: VerifiedCredential[];
}

interface VerifiedCredential {
  format: string;
  type: string;
  issuer: string;
  claims: Record<string, any>;
}
```

### Webhook Registration

```typescript
import { VerifyClient } from '@waltid/verify-sdk';

const client = new VerifyClient({ apiKey: process.env.VERIFY_API_KEY! });

// Register webhook endpoint
async function registerWebhook() {
  const webhook = await client.createWebhook({
    url: 'https://your-domain.com/webhooks/verify',
    events: ['session.verified', 'session.failed', 'session.expired'],
    secret: process.env.VERIFY_WEBHOOK_SECRET
  });

  console.log('Webhook registered:', webhook.id);
}

// List webhooks
async function listWebhooks() {
  const webhooks = await client.listWebhooks();
  return webhooks;
}

// Delete webhook
async function deleteWebhook(webhookId: string) {
  await client.deleteWebhook(webhookId);
}
```

## Swift Implementation

```swift
import Vapor
import Crypto

func configureWebhooks(_ app: Application) throws {
    app.post("webhooks", "verify") { req async throws -> HTTPStatus in
        // Get raw body and headers
        guard let body = req.body.data,
              let signature = req.headers.first(name: "x-verify-signature"),
              let timestamp = req.headers.first(name: "x-verify-timestamp") else {
            throw Abort(.badRequest)
        }

        // Verify signature
        guard verifySignature(body: body, signature: signature, timestamp: timestamp) else {
            throw Abort(.unauthorized)
        }

        // Parse payload
        let payload = try JSONDecoder().decode(WebhookPayload.self, from: body)

        // Process event
        switch payload.event {
        case "session.verified":
            try await handleVerified(payload: payload, db: req.db)
        case "session.failed":
            try await handleFailed(payload: payload, db: req.db)
        case "session.expired":
            try await handleExpired(payload: payload, db: req.db)
        default:
            req.logger.warning("Unknown webhook event: \(payload.event)")
        }

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

struct WebhookPayload: Codable {
    let event: String
    let sessionId: String
    let status: String
    let timestamp: String
    let metadata: [String: String]
    let credentials: [VerifiedCredential]?
}

struct VerifiedCredential: Codable {
    let format: String
    let type: String
    let issuer: String
    let claims: [String: AnyCodable]
}
```

## Kotlin (Spring Boot) Implementation

```kotlin
import org.springframework.web.bind.annotation.*
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

@RestController
@RequestMapping("/webhooks")
class WebhookController(
    private val webhookService: WebhookService
) {
    @PostMapping("/verify")
    fun handleWebhook(
        @RequestBody body: String,
        @RequestHeader("x-verify-signature") signature: String,
        @RequestHeader("x-verify-timestamp") timestamp: String
    ): ResponseEntity<Void> {
        // Verify signature
        if (!verifySignature(body, signature, timestamp)) {
            return ResponseEntity.status(401).build()
        }

        // Parse payload
        val payload = objectMapper.readValue(body, WebhookPayload::class.java)

        // Idempotency check
        if (webhookService.isProcessed(payload.sessionId)) {
            return ResponseEntity.ok().build()
        }

        // Process event
        when (payload.event) {
            "session.verified" -> webhookService.handleVerified(payload)
            "session.failed" -> webhookService.handleFailed(payload)
            "session.expired" -> webhookService.handleExpired(payload)
        }

        // Mark as processed
        webhookService.markProcessed(payload.sessionId)

        return ResponseEntity.ok().build()
    }

    private fun verifySignature(
        body: String,
        signature: String,
        timestamp: String
    ): Boolean {
        val secret = System.getenv("VERIFY_WEBHOOK_SECRET")
        val signedPayload = "$timestamp.$body"

        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(), "HmacSHA256"))

        val expectedSignature = mac.doFinal(signedPayload.toByteArray())
            .joinToString("") { "%02x".format(it) }

        return MessageDigest.isEqual(
            signature.toByteArray(),
            expectedSignature.toByteArray()
        )
    }
}

@Service
class WebhookService(
    private val userRepository: UserRepository,
    private val verificationRepository: VerificationRepository
) {
    fun handleVerified(payload: WebhookPayload) {
        val userId = payload.metadata["userId"] ?: return
        val credential = payload.credentials?.firstOrNull() ?: return

        userRepository.updateKycStatus(
            userId = userId,
            status = "verified",
            givenName = credential.claims["given_name"] as String,
            familyName = credential.claims["family_name"] as String
        )
    }

    fun handleFailed(payload: WebhookPayload) {
        val userId = payload.metadata["userId"] ?: return

        verificationRepository.recordFailure(
            userId = userId,
            sessionId = payload.sessionId
        )
    }

    fun handleExpired(payload: WebhookPayload) {
        val userId = payload.metadata["userId"] ?: return

        verificationRepository.recordExpiry(
            userId = userId,
            sessionId = payload.sessionId
        )
    }

    fun isProcessed(sessionId: String): Boolean {
        return verificationRepository.existsBySessionId(sessionId)
    }

    fun markProcessed(sessionId: String) {
        verificationRepository.markProcessed(sessionId)
    }
}

data class WebhookPayload(
    val event: String,
    val sessionId: String,
    val status: String,
    val timestamp: String,
    val metadata: Map<String, String>,
    val credentials: List<VerifiedCredential>?
)

data class VerifiedCredential(
    val format: String,
    val type: String,
    val issuer: String,
    val claims: Map<String, Any>
)
```

## Testing Webhooks

### Local Development with ngrok

```bash
# Start ngrok tunnel
ngrok http 3000

# Use the ngrok URL for webhook registration
# https://abc123.ngrok.io/webhooks/verify
```

### Test Webhook Delivery

```typescript
// Use SDK to send test webhooks
await client.testWebhook({
  webhookId: 'wh_abc123',
  event: 'session.verified',
  payload: {
    sessionId: 'test_session',
    credentials: [{
      format: 'dc+sd-jwt',
      type: 'urn:eudi:pid:1',
      claims: {
        given_name: 'Test',
        family_name: 'User',
        birth_date: '1990-01-01'
      }
    }]
  }
});
```

### Unit Testing

```typescript
import { createHmac } from 'crypto';

describe('Webhook Handler', () => {
  it('should verify valid signatures', () => {
    const body = JSON.stringify({ event: 'session.verified' });
    const timestamp = new Date().toISOString();
    const secret = 'test_secret';

    const signature = createHmac('sha256', secret)
      .update(`${timestamp}.${body}`)
      .digest('hex');

    expect(verifyWebhookSignature(
      Buffer.from(body),
      signature,
      timestamp
    )).toBe(true);
  });

  it('should reject invalid signatures', () => {
    const body = JSON.stringify({ event: 'session.verified' });
    const timestamp = new Date().toISOString();

    expect(verifyWebhookSignature(
      Buffer.from(body),
      'invalid_signature',
      timestamp
    )).toBe(false);
  });

  it('should handle verified events', async () => {
    const payload = {
      event: 'session.verified',
      sessionId: 'sess_123',
      metadata: { userId: 'user_123' },
      credentials: [{
        claims: { given_name: 'John', family_name: 'Doe' }
      }]
    };

    await handleVerificationEvent(payload);

    const user = await db.users.findUnique({ where: { id: 'user_123' } });
    expect(user.kycStatus).toBe('verified');
  });
});
```

## Webhook Retry Policy

The Verify API retries failed webhook deliveries:

| Attempt | Delay |
|---------|-------|
| 1 | Immediate |
| 2 | 1 minute |
| 3 | 5 minutes |
| 4 | 30 minutes |
| 5 | 2 hours |
| 6 | 8 hours |
| 7 | 24 hours |

After 7 failed attempts, the webhook is marked as failed and no more retries occur.

## Best Practices

1. **Always verify signatures** - Never trust unverified webhooks
2. **Implement idempotency** - Handle duplicate deliveries gracefully
3. **Respond quickly** - Return 200 within 30 seconds, process async if needed
4. **Use a queue** - Process webhooks asynchronously for reliability
5. **Log everything** - Keep audit trail of all webhook events
6. **Monitor failures** - Alert on webhook processing failures
7. **Handle all events** - Even if you only care about `verified`, handle others gracefully

## Troubleshooting

| Issue | Solution |
|-------|----------|
| Invalid signature | Check secret matches, verify timestamp format |
| Missing webhooks | Check endpoint is publicly accessible |
| Duplicate processing | Implement idempotency with session ID |
| Timeout errors | Process async, return 200 immediately |
| Parse errors | Validate payload structure before processing |
