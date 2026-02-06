# Payment Authorization Example

This guide shows how to implement payment authorization using the Verify API SDKs, requiring user identity verification before processing high-value or sensitive transactions.

## Use Case

Verify user identity before authorizing:
- High-value transactions (e.g., over $1,000)
- First-time payments to new recipients
- International transfers
- Cryptocurrency withdrawals
- Account setting changes

## Overview

1. Detect transaction requiring verification
2. Create verification session linked to transaction
3. User verifies identity with digital credential
4. Process transaction only after successful verification

## TypeScript Implementation

### Transaction Guard

```typescript
import { VerifyClient } from '@waltid/verify-sdk';

const client = new VerifyClient({
  apiKey: process.env.VERIFY_API_KEY!
});

interface Transaction {
  id: string;
  amount: number;
  currency: string;
  recipientId: string;
  type: 'payment' | 'withdrawal' | 'transfer';
}

interface AuthorizationResult {
  authorized: boolean;
  transactionId: string;
  verificationSessionId?: string;
  reason?: string;
}

class PaymentAuthorizationService {
  private readonly HIGH_VALUE_THRESHOLD = 1000;

  async authorizeTransaction(
    userId: string,
    transaction: Transaction
  ): Promise<AuthorizationResult> {
    // Determine if verification is required
    const requiresVerification = this.requiresVerification(transaction);

    if (!requiresVerification) {
      return {
        authorized: true,
        transactionId: transaction.id
      };
    }

    // Create verification session
    const session = await client.verifyIdentity({
      template: 'payment_authorization',
      callbackUrl: `${process.env.BASE_URL}/api/payments/verify-callback`,
      metadata: {
        transactionId: transaction.id,
        userId,
        amount: transaction.amount.toString(),
        currency: transaction.currency
      },
      expiresIn: 300 // 5 minutes for payment auth
    });

    // Store pending authorization
    await db.pendingAuthorizations.create({
      data: {
        transactionId: transaction.id,
        sessionId: session.sessionId,
        userId,
        amount: transaction.amount,
        currency: transaction.currency,
        status: 'pending',
        expiresAt: new Date(session.expiresAt)
      }
    });

    return {
      authorized: false,
      transactionId: transaction.id,
      verificationSessionId: session.sessionId,
      reason: 'verification_required'
    };
  }

  private requiresVerification(transaction: Transaction): boolean {
    // High value transactions
    if (transaction.amount >= this.HIGH_VALUE_THRESHOLD) {
      return true;
    }

    // All withdrawals
    if (transaction.type === 'withdrawal') {
      return true;
    }

    return false;
  }
}
```

### Express.js API Endpoints

```typescript
import express from 'express';
import { VerifyClient } from '@waltid/verify-sdk';

const app = express();
const client = new VerifyClient({ apiKey: process.env.VERIFY_API_KEY! });
const authService = new PaymentAuthorizationService();

// Initiate payment with authorization check
app.post('/api/payments', async (req, res) => {
  const { userId } = req.user;
  const { amount, currency, recipientId } = req.body;

  // Create transaction record
  const transaction = await db.transactions.create({
    data: {
      userId,
      amount,
      currency,
      recipientId,
      status: 'pending_authorization'
    }
  });

  // Check if authorization required
  const authResult = await authService.authorizeTransaction(userId, {
    id: transaction.id,
    amount,
    currency,
    recipientId,
    type: 'payment'
  });

  if (authResult.authorized) {
    // Process immediately
    await processPayment(transaction);
    return res.json({
      status: 'completed',
      transactionId: transaction.id
    });
  }

  // Get session details for client
  const session = await client.getSession(authResult.verificationSessionId!);

  res.json({
    status: 'verification_required',
    transactionId: transaction.id,
    verification: {
      sessionId: session.sessionId,
      qrCodeUrl: session.qrCodeUrl,
      deepLink: session.deepLink,
      expiresAt: session.expiresAt
    }
  });
});

// Check verification status
app.get('/api/payments/:transactionId/status', async (req, res) => {
  const { transactionId } = req.params;

  const transaction = await db.transactions.findUnique({
    where: { id: transactionId },
    include: { pendingAuthorization: true }
  });

  if (!transaction) {
    return res.status(404).json({ error: 'Transaction not found' });
  }

  if (transaction.status === 'completed') {
    return res.json({
      status: 'completed',
      completedAt: transaction.completedAt
    });
  }

  if (transaction.pendingAuthorization) {
    const session = await client.getSession(
      transaction.pendingAuthorization.sessionId
    );

    return res.json({
      status: transaction.status,
      verification: {
        status: session.status,
        expiresAt: session.expiresAt
      }
    });
  }

  res.json({ status: transaction.status });
});

// Webhook handler for verification results
app.post('/api/payments/verify-callback', express.json(), async (req, res) => {
  const { sessionId, status, credentials, metadata } = req.body;
  const { transactionId, userId } = metadata;

  const transaction = await db.transactions.findUnique({
    where: { id: transactionId }
  });

  if (!transaction || transaction.status !== 'pending_authorization') {
    return res.status(400).json({ error: 'Invalid transaction state' });
  }

  if (status === 'verified') {
    // Verify the identity matches the account holder
    const credential = credentials[0];
    const verifiedName = `${credential.claims.given_name} ${credential.claims.family_name}`;

    const user = await db.users.findUnique({ where: { id: userId } });

    // Optional: Name matching check
    if (!nameMatches(user.fullName, verifiedName)) {
      await db.transactions.update({
        where: { id: transactionId },
        data: {
          status: 'authorization_failed',
          failureReason: 'identity_mismatch'
        }
      });

      await alertFraudTeam(transactionId, 'identity_mismatch');
      return res.sendStatus(200);
    }

    // Process the authorized payment
    await processPayment(transaction);

    await db.transactions.update({
      where: { id: transactionId },
      data: {
        status: 'completed',
        authorizedAt: new Date(),
        completedAt: new Date()
      }
    });

    // Log for compliance
    await auditLog.create({
      eventType: 'payment_authorized',
      transactionId,
      userId,
      amount: transaction.amount,
      verificationSessionId: sessionId
    });
  } else {
    await db.transactions.update({
      where: { id: transactionId },
      data: {
        status: 'authorization_failed',
        failureReason: status === 'expired' ? 'timeout' : 'verification_failed'
      }
    });
  }

  // Clean up pending authorization
  await db.pendingAuthorizations.delete({
    where: { sessionId }
  });

  res.sendStatus(200);
});

// Cancel transaction
app.delete('/api/payments/:transactionId', async (req, res) => {
  const { transactionId } = req.params;

  const transaction = await db.transactions.findUnique({
    where: { id: transactionId },
    include: { pendingAuthorization: true }
  });

  if (transaction?.pendingAuthorization) {
    await client.cancelSession(transaction.pendingAuthorization.sessionId);
  }

  await db.transactions.update({
    where: { id: transactionId },
    data: { status: 'cancelled' }
  });

  res.json({ status: 'cancelled' });
});
```

### React Component

```tsx
import React, { useState, useEffect } from 'react';
import QRCode from 'qrcode.react';

interface PaymentAuthProps {
  transactionId: string;
  amount: number;
  currency: string;
  onComplete: () => void;
  onCancel: () => void;
}

export function PaymentAuthorization({
  transactionId,
  amount,
  currency,
  onComplete,
  onCancel
}: PaymentAuthProps) {
  const [verification, setVerification] = useState<{
    sessionId: string;
    qrCodeUrl: string;
    deepLink: string;
    expiresAt: string;
  } | null>(null);
  const [status, setStatus] = useState<'loading' | 'pending' | 'verified' | 'failed'>('loading');
  const [timeLeft, setTimeLeft] = useState(300);

  useEffect(() => {
    // Load verification session
    fetch(`/api/payments/${transactionId}/status`)
      .then(res => res.json())
      .then(data => {
        if (data.status === 'completed') {
          setStatus('verified');
          onComplete();
        } else if (data.verification) {
          setVerification(data.verification);
          setStatus('pending');
        }
      });
  }, [transactionId]);

  // Poll for status
  useEffect(() => {
    if (status !== 'pending' || !verification) return;

    const interval = setInterval(async () => {
      const res = await fetch(`/api/payments/${transactionId}/status`);
      const data = await res.json();

      if (data.status === 'completed') {
        setStatus('verified');
        onComplete();
      } else if (data.status === 'authorization_failed') {
        setStatus('failed');
      }
    }, 2000);

    return () => clearInterval(interval);
  }, [status, verification, transactionId]);

  // Countdown timer
  useEffect(() => {
    if (status !== 'pending') return;

    const interval = setInterval(() => {
      setTimeLeft(prev => {
        if (prev <= 1) {
          setStatus('failed');
          return 0;
        }
        return prev - 1;
      });
    }, 1000);

    return () => clearInterval(interval);
  }, [status]);

  const formatTime = (seconds: number) => {
    const mins = Math.floor(seconds / 60);
    const secs = seconds % 60;
    return `${mins}:${secs.toString().padStart(2, '0')}`;
  };

  if (status === 'loading') {
    return <div className="loading">Loading...</div>;
  }

  if (status === 'verified') {
    return (
      <div className="auth-success">
        <div className="icon">&#10004;</div>
        <h2>Payment Authorized</h2>
        <p>Your payment of {currency} {amount.toFixed(2)} has been processed.</p>
      </div>
    );
  }

  if (status === 'failed') {
    return (
      <div className="auth-failed">
        <div className="icon">&#10006;</div>
        <h2>Authorization Failed</h2>
        <p>We couldn't verify your identity. Please try again.</p>
        <button onClick={onCancel}>Cancel Payment</button>
      </div>
    );
  }

  return (
    <div className="auth-pending">
      <h2>Verify Your Identity</h2>
      <p>
        To authorize this payment of <strong>{currency} {amount.toFixed(2)}</strong>,
        please scan the QR code with your wallet app.
      </p>

      <div className="qr-container">
        {verification && (
          <QRCode value={verification.qrCodeUrl} size={200} />
        )}
      </div>

      <p className="timer">
        Time remaining: <strong>{formatTime(timeLeft)}</strong>
      </p>

      <div className="mobile-option">
        <p>Or open directly:</p>
        <a
          href={verification?.deepLink}
          className="wallet-button"
        >
          Open Wallet App
        </a>
      </div>

      <button className="cancel-button" onClick={onCancel}>
        Cancel
      </button>
    </div>
  );
}
```

## Swift Implementation

```swift
import WaltIDVerifySDK

class PaymentAuthorizationService {
    private let client: VerifyClient
    private let highValueThreshold: Decimal = 1000

    init(apiKey: String) {
        self.client = VerifyClient(config: VerifyConfig(apiKey: apiKey))
    }

    func authorizePayment(
        amount: Decimal,
        currency: String,
        transactionId: String
    ) async throws -> AuthorizationResult {
        guard amount >= highValueThreshold else {
            return .authorized
        }

        let session = try await client.verifyIdentity(
            VerificationRequest(
                template: "payment_authorization",
                metadata: [
                    "transactionId": transactionId,
                    "amount": "\(amount)",
                    "currency": currency
                ],
                expiresIn: 300
            )
        )

        return .verificationRequired(
            sessionId: session.sessionId,
            qrCodeUrl: session.qrCodeUrl,
            deepLink: session.deepLink
        )
    }

    func waitForAuthorization(sessionId: String) async throws -> Bool {
        let result = try await client.pollSession(
            sessionId: sessionId,
            timeout: 300_000
        )
        return result.status == .verified
    }
}

enum AuthorizationResult {
    case authorized
    case verificationRequired(sessionId: String, qrCodeUrl: String, deepLink: String)
}
```

## Kotlin (Android) Implementation

```kotlin
import id.walt.verify.*

class PaymentAuthorizationService(apiKey: String) {
    private val client = VerifyClient(VerifyConfig(apiKey = apiKey))
    private val highValueThreshold = BigDecimal("1000")

    suspend fun authorizePayment(
        amount: BigDecimal,
        currency: String,
        transactionId: String
    ): AuthorizationResult {
        if (amount < highValueThreshold) {
            return AuthorizationResult.Authorized
        }

        val session = client.verifyIdentity(
            VerificationRequest(
                template = "payment_authorization",
                metadata = mapOf(
                    "transactionId" to transactionId,
                    "amount" to amount.toString(),
                    "currency" to currency
                ),
                expiresIn = 300
            )
        )

        return AuthorizationResult.VerificationRequired(
            sessionId = session.sessionId,
            qrCodeUrl = session.qrCodeUrl,
            deepLink = session.deepLink
        )
    }

    suspend fun waitForAuthorization(sessionId: String): Boolean {
        val result = client.pollSession(
            sessionId = sessionId,
            timeout = 300_000
        )
        return result.status == "verified"
    }
}

sealed class AuthorizationResult {
    object Authorized : AuthorizationResult()
    data class VerificationRequired(
        val sessionId: String,
        val qrCodeUrl: String,
        val deepLink: String
    ) : AuthorizationResult()
}
```

## Fraud Prevention Integration

```typescript
interface FraudSignals {
  deviceFingerprint: string;
  ipAddress: string;
  behaviorScore: number;
  previousVerifications: number;
}

async function authorizeWithFraudCheck(
  userId: string,
  transaction: Transaction,
  signals: FraudSignals
): Promise<AuthorizationResult> {
  // Calculate risk score
  const riskScore = await calculateRiskScore(transaction, signals);

  // Determine verification level based on risk
  let template: string;

  if (riskScore > 0.8) {
    // High risk: Require full identity verification
    template = 'payment_authorization_full';
  } else if (riskScore > 0.5 || transaction.amount >= 5000) {
    // Medium risk: Standard verification
    template = 'payment_authorization';
  } else if (transaction.amount >= 1000) {
    // Low risk but high value: Light verification
    template = 'payment_authorization_light';
  } else {
    // No verification needed
    return { authorized: true, transactionId: transaction.id };
  }

  const session = await client.verifyIdentity({
    template,
    metadata: {
      transactionId: transaction.id,
      userId,
      riskScore: riskScore.toString(),
      ...signals
    }
  });

  return {
    authorized: false,
    transactionId: transaction.id,
    verificationSessionId: session.sessionId,
    reason: 'verification_required'
  };
}
```

## Best Practices

1. **Set appropriate timeouts** - Payment authorization should be quick (5 minutes max)
2. **Always use webhooks** - Don't rely solely on polling for production
3. **Implement idempotency** - Handle duplicate webhook deliveries
4. **Log everything** - Audit trail is essential for compliance
5. **Graceful degradation** - Have fallback authorization methods
6. **User communication** - Clear messaging about why verification is needed

## Security Considerations

- Validate that the verified identity matches the account holder
- Implement rate limiting on verification attempts
- Monitor for suspicious patterns (multiple failed verifications)
- Encrypt transaction data in transit and at rest
- Use webhook signatures to validate callbacks
