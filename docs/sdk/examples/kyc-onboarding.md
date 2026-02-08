# KYC Onboarding Example

This guide shows how to implement Know Your Customer (KYC) verification using the Verify API SDKs.

## Use Case

Verify user identity during account registration or financial onboarding, collecting name, date of birth, and optionally address information from verifiable credentials.

## Overview

1. Configure KYC requirements based on your compliance needs
2. Create verification session requesting identity credentials
3. User presents their digital identity credential
4. Receive verified identity data for account creation

## TypeScript Implementation

### Basic KYC Verification

```typescript
import { VerifyClient, DCQLQuery } from '@waltid/verify-sdk';

const client = new VerifyClient({
  apiKey: process.env.VERIFY_API_KEY!
});

interface KYCData {
  givenName: string;
  familyName: string;
  birthDate: string;
  address?: {
    streetAddress: string;
    locality: string;
    region: string;
    postalCode: string;
    country: string;
  };
}

async function performKYC(): Promise<KYCData | null> {
  // Use pre-built KYC template
  const session = await client.verifyIdentity({
    template: 'kyc_basic',
    callbackUrl: `${process.env.BASE_URL}/api/kyc/callback`
  });

  console.log('Verification URL:', session.qrCodeUrl);

  // Poll for result
  const result = await client.pollSession(session.sessionId, {
    timeout: 600000 // 10 minutes for KYC
  });

  if (result.status !== 'verified' || !result.credentials) {
    return null;
  }

  // Extract verified claims
  const credential = result.credentials[0];
  return {
    givenName: credential.claims.given_name,
    familyName: credential.claims.family_name,
    birthDate: credential.claims.birth_date,
    address: credential.claims.address
  };
}
```

### Custom DCQL Query for KYC

```typescript
async function performEnhancedKYC(): Promise<VerificationSession> {
  const dcqlQuery: DCQLQuery = {
    credentials: [
      {
        id: 'pid_credential',
        format: 'dc+sd-jwt',
        meta: {
          vct_values: ['urn:eudi:pid:1']
        },
        claims: [
          // Required claims
          { path: ['given_name'] },
          { path: ['family_name'] },
          { path: ['birth_date'] },
          { path: ['nationality'] },

          // Optional claims (user can choose to share)
          {
            path: ['address', 'street_address'],
            intent_to_retain: false
          },
          {
            path: ['address', 'locality'],
            intent_to_retain: false
          },
          {
            path: ['address', 'postal_code'],
            intent_to_retain: false
          },
          {
            path: ['address', 'country'],
            intent_to_retain: false
          }
        ]
      }
    ]
  };

  return client.verifyIdentity({ dcql: dcqlQuery });
}
```

### Full KYC Flow with Express.js

```typescript
import express from 'express';
import { VerifyClient } from '@waltid/verify-sdk';

const app = express();
const client = new VerifyClient({ apiKey: process.env.VERIFY_API_KEY! });

// Store pending verifications
const pendingVerifications = new Map<string, {
  userId: string;
  createdAt: Date;
}>();

// Step 1: Initiate KYC
app.post('/api/kyc/start', async (req, res) => {
  const { userId, tier } = req.body;

  // Select template based on KYC tier
  const template = tier === 'enhanced' ? 'kyc_enhanced' : 'kyc_basic';

  const session = await client.verifyIdentity({
    template,
    callbackUrl: `${process.env.BASE_URL}/api/kyc/webhook`,
    metadata: {
      userId,
      tier,
      timestamp: new Date().toISOString()
    },
    expiresIn: 600 // 10 minutes
  });

  // Track pending verification
  pendingVerifications.set(session.sessionId, {
    userId,
    createdAt: new Date()
  });

  res.json({
    sessionId: session.sessionId,
    qrCodeUrl: session.qrCodeUrl,
    deepLink: session.deepLink,
    expiresAt: session.expiresAt
  });
});

// Step 2: Check status (for client polling)
app.get('/api/kyc/status/:sessionId', async (req, res) => {
  const { sessionId } = req.params;

  if (!pendingVerifications.has(sessionId)) {
    return res.status(404).json({ error: 'Session not found' });
  }

  const result = await client.getSession(sessionId);

  res.json({
    status: result.status,
    // Don't expose credential data via polling - use webhook
  });
});

// Step 3: Webhook handler
app.post('/api/kyc/webhook', express.json(), async (req, res) => {
  // Validate webhook signature (important for production)
  const signature = req.headers['x-verify-signature'];
  if (!validateWebhookSignature(req.body, signature)) {
    return res.status(401).json({ error: 'Invalid signature' });
  }

  const { sessionId, status, credentials, metadata } = req.body;
  const { userId, tier } = metadata;

  if (status === 'verified' && credentials?.length > 0) {
    const credential = credentials[0];

    // Store verified KYC data
    await db.kycRecords.create({
      data: {
        userId,
        tier,
        givenName: credential.claims.given_name,
        familyName: credential.claims.family_name,
        birthDate: credential.claims.birth_date,
        nationality: credential.claims.nationality,
        address: credential.claims.address,
        verifiedAt: new Date(),
        credentialIssuer: credential.issuer,
        verificationSessionId: sessionId
      }
    });

    // Update user status
    await db.users.update({
      where: { id: userId },
      data: {
        kycStatus: 'verified',
        kycTier: tier,
        kycVerifiedAt: new Date()
      }
    });

    // Trigger downstream processes
    await notifyComplianceTeam(userId, 'kyc_verified');
    await enableAccountFeatures(userId, tier);
  } else {
    // Handle failed verification
    await db.users.update({
      where: { id: userId },
      data: { kycStatus: 'failed' }
    });
  }

  // Clean up
  pendingVerifications.delete(sessionId);

  res.sendStatus(200);
});

function validateWebhookSignature(body: any, signature: string): boolean {
  const crypto = require('crypto');
  const expectedSignature = crypto
    .createHmac('sha256', process.env.VERIFY_WEBHOOK_SECRET!)
    .update(JSON.stringify(body))
    .digest('hex');
  return signature === expectedSignature;
}
```

## Swift Implementation

```swift
import WaltIDVerifySDK

class KYCService {
    private let client: VerifyClient

    init(apiKey: String) {
        self.client = VerifyClient(config: VerifyConfig(apiKey: apiKey))
    }

    func startKYC(tier: KYCTier) async throws -> VerificationSession {
        let template = tier == .enhanced ? "kyc_enhanced" : "kyc_basic"

        return try await client.verifyIdentity(
            VerificationRequest(
                template: template,
                expiresIn: 600
            )
        )
    }

    func pollForResult(sessionId: String) async throws -> KYCResult {
        let result = try await client.pollSession(
            sessionId: sessionId,
            timeout: 600_000,
            interval: 2_000
        )

        guard result.status == .verified,
              let credential = result.credentials?.first else {
            return KYCResult(success: false, data: nil)
        }

        return KYCResult(
            success: true,
            data: KYCData(
                givenName: credential.claims["given_name"] as? String ?? "",
                familyName: credential.claims["family_name"] as? String ?? "",
                birthDate: credential.claims["birth_date"] as? String ?? "",
                nationality: credential.claims["nationality"] as? String
            )
        )
    }
}

enum KYCTier {
    case basic
    case enhanced
}

struct KYCData {
    let givenName: String
    let familyName: String
    let birthDate: String
    let nationality: String?
}

struct KYCResult {
    let success: Bool
    let data: KYCData?
}
```

## Kotlin (Android) Implementation

```kotlin
import id.walt.verify.*
import kotlinx.coroutines.flow.*

class KYCRepository(private val apiKey: String) {
    private val client = VerifyClient(VerifyConfig(apiKey = apiKey))

    suspend fun startKYC(tier: KYCTier): VerificationSession {
        val template = when (tier) {
            KYCTier.BASIC -> "kyc_basic"
            KYCTier.ENHANCED -> "kyc_enhanced"
        }

        return client.verifyIdentity(
            VerificationRequest(
                template = template,
                expiresIn = 600
            )
        )
    }

    fun pollForResult(sessionId: String): Flow<KYCState> = flow {
        emit(KYCState.Pending)

        val result = client.pollSession(
            sessionId = sessionId,
            timeout = 600_000,
            interval = 2_000
        )

        when (result.status) {
            "verified" -> {
                val credential = result.credentials?.firstOrNull()
                if (credential != null) {
                    emit(KYCState.Verified(
                        KYCData(
                            givenName = credential.claims["given_name"] as String,
                            familyName = credential.claims["family_name"] as String,
                            birthDate = credential.claims["birth_date"] as String,
                            nationality = credential.claims["nationality"] as? String
                        )
                    ))
                } else {
                    emit(KYCState.Failed("No credential data"))
                }
            }
            "failed" -> emit(KYCState.Failed("Verification failed"))
            "expired" -> emit(KYCState.Expired)
        }
    }
}

enum class KYCTier { BASIC, ENHANCED }

data class KYCData(
    val givenName: String,
    val familyName: String,
    val birthDate: String,
    val nationality: String?
)

sealed class KYCState {
    object Pending : KYCState()
    data class Verified(val data: KYCData) : KYCState()
    data class Failed(val message: String) : KYCState()
    object Expired : KYCState()
}
```

## Available KYC Templates

| Template ID | Claims Requested | Use Case |
|-------------|------------------|----------|
| `kyc_basic` | name, birth_date | Basic account verification |
| `kyc_enhanced` | name, birth_date, nationality, address | Financial services |
| `kyc_full` | All PID claims | Regulated industries |

## Data Handling Best Practices

### Data Minimization

Only request the claims you actually need:

```typescript
// BAD: Requesting everything
const session = await client.verifyIdentity({
  template: 'kyc_full' // Gets ALL claims
});

// GOOD: Request only what you need
const session = await client.verifyIdentity({
  dcql: {
    credentials: [{
      id: 'pid',
      format: 'dc+sd-jwt',
      claims: [
        { path: ['given_name'] },
        { path: ['family_name'] },
        { path: ['birth_date'] }
        // Only requesting 3 specific claims
      ]
    }]
  }
});
```

### Data Retention

```typescript
// Store minimal data for compliance
interface KYCRecord {
  userId: string;
  verifiedAt: Date;
  expiresAt: Date; // Set retention period

  // Verified identity (encrypted at rest)
  givenName: string;
  familyName: string;
  birthDate: string;

  // Audit trail
  verificationSessionId: string;
  credentialIssuer: string;

  // DO NOT STORE: Raw credential, signatures, full credential data
}
```

### Compliance Logging

```typescript
// Log verification events for audit
await auditLog.create({
  eventType: 'kyc_verification',
  userId: user.id,
  sessionId: session.sessionId,
  result: result.status,
  timestamp: new Date(),
  ipAddress: req.ip,
  userAgent: req.headers['user-agent'],
  // Don't log PII in audit logs
});
```

## Error Handling

```typescript
async function handleKYCVerification(userId: string) {
  try {
    const session = await client.verifyIdentity({
      template: 'kyc_basic'
    });

    const result = await client.pollSession(session.sessionId);

    switch (result.status) {
      case 'verified':
        return { success: true, data: extractKYCData(result) };

      case 'failed':
        // User presented invalid or unacceptable credential
        return {
          success: false,
          error: 'verification_failed',
          message: 'Unable to verify your identity. Please try again with a valid credential.'
        };

      case 'expired':
        return {
          success: false,
          error: 'session_expired',
          message: 'Verification session expired. Please start again.'
        };
    }
  } catch (error) {
    if (error instanceof VerifyApiError) {
      switch (error.code) {
        case 'rate_limited':
          // Implement backoff
          await delay(error.retryAfter * 1000);
          return handleKYCVerification(userId);

        case 'unauthorized':
          throw new Error('API configuration error');

        default:
          throw error;
      }
    }
    throw error;
  }
}
```

## Regulatory Considerations

| Regulation | Requirement | Implementation |
|------------|-------------|----------------|
| GDPR | Data minimization | Request only needed claims |
| GDPR | Right to erasure | Implement data deletion |
| AML/KYC | Identity verification | Use verified credentials |
| AML/KYC | Record keeping | Store verification records |
| PCI DSS | Data protection | Encrypt stored data |

## Testing

```typescript
// Use test API keys for development
const client = new VerifyClient({
  apiKey: 'vfy_test_xxx' // Test key
});

// Test credentials are available in sandbox
// - Test PID credentials with various scenarios
// - Expired credentials
// - Revoked credentials
// - Invalid signatures
```
