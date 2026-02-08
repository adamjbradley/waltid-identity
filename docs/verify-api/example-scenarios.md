# Example Scenarios

This guide covers all 7 example implementation patterns for the Verify API, from simple age checks to complex multi-step orchestrations.

## Table of Contents

1. [Age Verification at Checkout](#1-age-verification-at-checkout)
2. [Full KYC Onboarding](#2-full-kyc-onboarding)
3. [Payment Authorization with PWA](#3-payment-authorization-with-pwa)
4. [Webhook-based Async Flow](#4-webhook-based-async-flow)
5. [Polling-based Sync Flow](#5-polling-based-sync-flow)
6. [Same-Device Deep Link Flow](#6-same-device-deep-link-flow)
7. [Cross-Device QR Code Flow](#7-cross-device-qr-code-flow)

---

## 1. Age Verification at Checkout

**Use Case:** An e-commerce site selling age-restricted products (alcohol, tobacco, adult content) needs to verify the customer is 18+ before completing the purchase.

**Complexity:** Simple, single credential

**Template:** `age_check`

### Overview

```
Customer       Your Site       Verify API      Wallet
    |              |               |             |
    |--Add to cart-|               |             |
    |              |               |             |
    |--Checkout--->|               |             |
    |              |--Start session|             |
    |              |<--QR code-----|             |
    |<--Show QR----|               |             |
    |              |               |             |
    |--Scan QR----------------------|----------->|
    |              |               |<--Present---|
    |              |<--Webhook-----|             |
    |<--Complete---|               |             |
```

### Implementation

**Backend (Node.js/Express):**

```typescript
import express from 'express';
import { VerifyClient } from '@waltid/verify-sdk';

const app = express();
const client = new VerifyClient({ apiKey: process.env.VERIFY_API_KEY! });

// Start age verification at checkout
app.post('/api/checkout/verify-age', async (req, res) => {
  const { orderId, userId } = req.body;

  // Check if already verified recently
  const user = await db.users.findUnique({ where: { id: userId } });
  if (user?.ageVerifiedAt && isRecent(user.ageVerifiedAt, 30)) { // 30 days
    return res.json({ verified: true, cached: true });
  }

  // Start new verification
  const session = await client.verifyIdentity({
    template: 'age_check',
    callbackUrl: `${process.env.BASE_URL}/api/checkout/age-callback`,
    metadata: { orderId, userId }
  });

  // Store pending verification
  await db.orders.update({
    where: { id: orderId },
    data: {
      verificationSessionId: session.sessionId,
      status: 'pending_age_verification'
    }
  });

  res.json({
    verified: false,
    sessionId: session.sessionId,
    qrCodeUrl: session.qrCodeUrl,
    deepLink: session.deepLink,
    expiresAt: session.expiresAt
  });
});

// Webhook handler
app.post('/api/checkout/age-callback', express.json(), async (req, res) => {
  const { sessionId, status, metadata } = req.body;
  const { orderId, userId } = metadata;

  if (status === 'verified') {
    // Update user age verification status
    await db.users.update({
      where: { id: userId },
      data: { ageVerifiedAt: new Date() }
    });

    // Complete the order
    await db.orders.update({
      where: { id: orderId },
      data: { status: 'age_verified' }
    });

    // Notify frontend via WebSocket or polling
    await notifyClient(orderId, 'age_verified');
  } else {
    await db.orders.update({
      where: { id: orderId },
      data: { status: 'age_verification_failed' }
    });
  }

  res.sendStatus(200);
});
```

**Frontend (React):**

```tsx
function AgeVerificationModal({ orderId, onComplete }: Props) {
  const [session, setSession] = useState<VerificationSession | null>(null);
  const [status, setStatus] = useState<'loading' | 'pending' | 'verified' | 'failed'>('loading');

  useEffect(() => {
    startVerification();
  }, [orderId]);

  async function startVerification() {
    const response = await fetch('/api/checkout/verify-age', {
      method: 'POST',
      body: JSON.stringify({ orderId, userId: currentUser.id })
    });

    const data = await response.json();

    if (data.verified) {
      setStatus('verified');
      onComplete();
      return;
    }

    setSession(data);
    setStatus('pending');

    // Start polling
    pollForResult(data.sessionId);
  }

  async function pollForResult(sessionId: string) {
    for (let i = 0; i < 150; i++) { // 5 min at 2s intervals
      await new Promise(r => setTimeout(r, 2000));

      const response = await fetch(`/api/orders/${orderId}/status`);
      const order = await response.json();

      if (order.status === 'age_verified') {
        setStatus('verified');
        onComplete();
        return;
      } else if (order.status === 'age_verification_failed') {
        setStatus('failed');
        return;
      }
    }
    setStatus('failed');
  }

  return (
    <Modal open={status !== 'verified'}>
      {status === 'pending' && session && (
        <div className="age-verification">
          <h2>Verify Your Age</h2>
          <p>You must be 18+ to purchase this item.</p>

          <img src={session.qrCodeUrl} alt="Scan to verify" />

          <a href={session.deepLink} className="btn">
            Open in Wallet App
          </a>
        </div>
      )}

      {status === 'failed' && (
        <div className="verification-failed">
          <p>Age verification failed. Please try again.</p>
          <button onClick={startVerification}>Retry</button>
        </div>
      )}
    </Modal>
  );
}
```

---

## 2. Full KYC Onboarding

**Use Case:** A fintech app requires full identity verification for regulatory compliance during account registration.

**Complexity:** Orchestrated, multi-step flow

**Template:** `kyc_full` or custom orchestration

### Overview

This scenario demonstrates a multi-step verification flow:
1. Identity verification (name, DOB, nationality)
2. Address verification
3. Optional: Payment method binding

```
User           Your App        Verify API         Wallet
  |               |                |                 |
  |--Register---->|                |                 |
  |               |--Create orch---|                 |
  |               |<--Step 1 QR----|                 |
  |<--Show step 1-|                |                 |
  |               |                |                 |
  |--Scan------------------------------|------------>|
  |               |                |<--Identity------|
  |               |<--Step complete|                 |
  |               |<--Step 2 QR----|                 |
  |<--Show step 2-|                |                 |
  |               |                |                 |
  |--Scan------------------------------|------------>|
  |               |                |<--Address-------|
  |               |<--All complete-|                 |
  |<--Account OK--|                |                 |
```

### Implementation

**Backend:**

```typescript
import { VerifyClient } from '@waltid/verify-sdk';

const client = new VerifyClient({ apiKey: process.env.VERIFY_API_KEY! });

// KYC Flow Controller
class KYCService {
  async startOnboarding(userId: string) {
    // Create orchestration
    const orchestration = await client.createOrchestration({
      name: `kyc_onboarding_${userId}`,
      steps: [
        {
          id: 'identity',
          type: 'identity',
          template: 'kyc_basic',
          config: {
            required_claims: ['given_name', 'family_name', 'birth_date', 'nationality']
          }
        },
        {
          id: 'address',
          type: 'identity',
          template: 'address_verification',
          depends_on: ['identity']
        }
      ],
      on_complete: {
        webhook: `${process.env.BASE_URL}/api/kyc/complete`
      }
    });

    // Start the orchestration
    const session = await client.startOrchestration({
      orchestrationId: orchestration.id,
      metadata: { userId }
    });

    // Store orchestration state
    await db.kycSessions.create({
      data: {
        userId,
        orchestrationId: orchestration.id,
        sessionId: session.sessionId,
        currentStep: 'identity',
        status: 'in_progress'
      }
    });

    return {
      sessionId: session.sessionId,
      currentStep: 'identity',
      verification: {
        qrCodeUrl: session.qrCodeUrl,
        deepLink: session.deepLink
      }
    };
  }

  async handleStepComplete(payload: OrchestrationEvent) {
    const { orchestration_session_id, step_id, step_result, next_step } = payload;

    // Get KYC session
    const kycSession = await db.kycSessions.findFirst({
      where: { sessionId: orchestration_session_id }
    });

    if (!kycSession) return;

    // Store step result
    await db.kycStepResults.create({
      data: {
        kycSessionId: kycSession.id,
        stepId: step_id,
        result: step_result,
        completedAt: new Date()
      }
    });

    // Update current step
    if (next_step) {
      await db.kycSessions.update({
        where: { id: kycSession.id },
        data: { currentStep: next_step }
      });
    }
  }

  async handleComplete(payload: OrchestrationEvent) {
    const { orchestration_session_id, metadata } = payload;
    const { userId } = metadata;

    // Get all step results
    const kycSession = await db.kycSessions.findFirst({
      where: { sessionId: orchestration_session_id },
      include: { stepResults: true }
    });

    // Extract verified data
    const identityResult = kycSession.stepResults.find(r => r.stepId === 'identity');
    const addressResult = kycSession.stepResults.find(r => r.stepId === 'address');

    // Create verified profile
    await db.verifiedProfiles.create({
      data: {
        userId,
        givenName: identityResult.result.answers.given_name,
        familyName: identityResult.result.answers.family_name,
        birthDate: identityResult.result.answers.birth_date,
        nationality: identityResult.result.answers.nationality,
        address: addressResult?.result.answers,
        verifiedAt: new Date()
      }
    });

    // Update user status
    await db.users.update({
      where: { id: userId },
      data: {
        kycStatus: 'verified',
        kycVerifiedAt: new Date()
      }
    });

    // Enable account features
    await enableAccountFeatures(userId);
  }
}
```

**Frontend:**

```tsx
function KYCOnboarding() {
  const [step, setStep] = useState<'identity' | 'address' | 'complete'>('identity');
  const [session, setSession] = useState<VerificationSession | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    startKYC();
  }, []);

  async function startKYC() {
    const response = await fetch('/api/kyc/start', { method: 'POST' });
    const data = await response.json();
    setSession(data.verification);
    setStep(data.currentStep);
    pollForStepComplete(data.sessionId);
  }

  async function pollForStepComplete(sessionId: string) {
    while (true) {
      await new Promise(r => setTimeout(r, 2000));

      const response = await fetch(`/api/kyc/status/${sessionId}`);
      const status = await response.json();

      if (status.status === 'complete') {
        setStep('complete');
        return;
      }

      if (status.currentStep !== step) {
        setStep(status.currentStep);
        setSession(status.verification);
      }

      if (status.status === 'failed') {
        setError('Verification failed');
        return;
      }
    }
  }

  return (
    <div className="kyc-onboarding">
      <StepIndicator currentStep={step} steps={['identity', 'address', 'complete']} />

      {step === 'identity' && session && (
        <div className="step">
          <h2>Step 1: Verify Your Identity</h2>
          <p>Scan with your wallet to share your identity information.</p>
          <QRCode url={session.qrCodeUrl} />
          <DeepLinkButton href={session.deepLink} />
        </div>
      )}

      {step === 'address' && session && (
        <div className="step">
          <h2>Step 2: Verify Your Address</h2>
          <p>Now share your address information to complete verification.</p>
          <QRCode url={session.qrCodeUrl} />
          <DeepLinkButton href={session.deepLink} />
        </div>
      )}

      {step === 'complete' && (
        <div className="success">
          <CheckIcon />
          <h2>Verification Complete!</h2>
          <p>Your account is now fully verified.</p>
          <button onClick={() => navigate('/dashboard')}>
            Continue to Dashboard
          </button>
        </div>
      )}

      {error && <ErrorMessage message={error} />}
    </div>
  );
}
```

---

## 3. Payment Authorization with PWA

**Use Case:** A high-value transaction requires identity verification and payment method binding using Payment Wallet Attestation (PWA).

**Complexity:** Transaction binding with PWA credential

**Template:** `payment_authorization`

### Overview

```
User           Merchant          Verify API        Wallet
  |               |                  |               |
  |--Checkout---->|                  |               |
  |               |--Payment auth----|               |
  |               |<--QR + txn hash--|               |
  |<--Show QR-----|                  |               |
  |               |                  |               |
  |--Scan------------------------------|------------>|
  |               |                  |<--PWA + sig---|
  |               |<--Authorized-----|               |
  |<--Complete----|                  |               |
```

### Implementation

```typescript
// Payment authorization service
class PaymentAuthorizationService {
  private client = new VerifyClient({ apiKey: process.env.VERIFY_API_KEY! });

  async authorizePayment(transaction: Transaction) {
    // Only require verification for high-value transactions
    if (transaction.amount < 1000) {
      return { authorized: true };
    }

    // Create payment verification session
    const session = await this.client.verifyPayment({
      template: 'payment_authorization',
      transaction: {
        amount: transaction.amount.toString(),
        currency: transaction.currency,
        merchant_name: transaction.merchantName,
        merchant_id: transaction.merchantId,
        reference: transaction.id
      },
      callbackUrl: `${process.env.BASE_URL}/api/payments/callback`,
      metadata: {
        transactionId: transaction.id,
        userId: transaction.userId
      }
    });

    // Store pending authorization
    await db.pendingAuthorizations.create({
      data: {
        transactionId: transaction.id,
        sessionId: session.sessionId,
        status: 'pending',
        expiresAt: new Date(session.expiresAt)
      }
    });

    return {
      authorized: false,
      verificationRequired: true,
      sessionId: session.sessionId,
      qrCodeUrl: session.qrCodeUrl,
      deepLink: session.deepLink
    };
  }

  async handleCallback(payload: WebhookPayload) {
    const { sessionId, status, result, metadata } = payload;
    const { transactionId, userId } = metadata;

    if (status !== 'verified') {
      await db.transactions.update({
        where: { id: transactionId },
        data: { status: 'authorization_failed' }
      });
      return;
    }

    // Verify PWA credential
    const credential = result.credentials?.find(
      c => c.vct === 'PaymentWalletAttestation'
    );

    if (!credential) {
      throw new Error('PWA credential not found');
    }

    // Extract payment binding
    const fundingSource = credential.disclosedClaims.funding_source;

    // Verify the credential matches the payment method on file
    const user = await db.users.findUnique({
      where: { id: userId },
      include: { paymentMethods: true }
    });

    const matchingMethod = user.paymentMethods.find(
      pm => pm.lastFour === fundingSource.panLastFour
    );

    if (!matchingMethod) {
      await db.transactions.update({
        where: { id: transactionId },
        data: {
          status: 'authorization_failed',
          failureReason: 'payment_method_mismatch'
        }
      });
      return;
    }

    // Complete the transaction
    await db.transactions.update({
      where: { id: transactionId },
      data: {
        status: 'authorized',
        authorizedAt: new Date(),
        paymentMethodId: matchingMethod.id
      }
    });

    // Process the payment
    await this.processPayment(transactionId);
  }
}
```

---

## 4. Webhook-based Async Flow

**Use Case:** Production application that needs reliable, real-time notifications without maintaining open connections.

**Pattern:** Backend-to-backend communication via webhooks

### Overview

```
Frontend        Backend          Verify API
    |              |                 |
    |--Start------>|                 |
    |              |--Create session-|
    |              |<--Session ID----|
    |<--QR code----|                 |
    |              |                 |
    [User scans and verifies]
    |              |                 |
    |              |<===WEBHOOK======|
    |              |--Process--------|
    |              |--Notify client--|
    |<==Push/Poll==|                 |
```

### Implementation

**Backend:**

```typescript
import express from 'express';
import { VerifyClient } from '@waltid/verify-sdk';
import { WebSocketServer } from 'ws';

const app = express();
const client = new VerifyClient({ apiKey: process.env.VERIFY_API_KEY! });
const wss = new WebSocketServer({ noServer: true });

// Track connected clients
const clients = new Map<string, WebSocket>();

// Start verification
app.post('/api/verify', async (req, res) => {
  const { template, userId } = req.body;

  const session = await client.verifyIdentity({
    template,
    callbackUrl: `${process.env.BASE_URL}/webhooks/verify`,
    metadata: { userId }
  });

  res.json({
    sessionId: session.sessionId,
    qrCodeUrl: session.qrCodeUrl,
    deepLink: session.deepLink
  });
});

// Webhook handler (receives async notification)
app.post('/webhooks/verify', express.json(), async (req, res) => {
  // Verify signature
  const signature = req.headers['x-verify-signature'] as string;
  if (!verifySignature(req.body, signature)) {
    return res.status(401).send('Invalid signature');
  }

  const { sessionId, status, result, metadata } = req.body;
  const { userId } = metadata;

  // Process the verification result
  if (status === 'verified') {
    await db.users.update({
      where: { id: userId },
      data: {
        verified: true,
        verifiedData: result.answers
      }
    });
  }

  // Notify connected client via WebSocket
  const ws = clients.get(userId);
  if (ws && ws.readyState === WebSocket.OPEN) {
    ws.send(JSON.stringify({
      type: 'verification_result',
      sessionId,
      status,
      result
    }));
  }

  // Return 200 immediately
  res.sendStatus(200);
});

// WebSocket connection for real-time updates
wss.on('connection', (ws, req) => {
  const userId = req.url.split('/').pop();
  clients.set(userId, ws);

  ws.on('close', () => {
    clients.delete(userId);
  });
});
```

**Frontend:**

```typescript
class VerificationClient {
  private ws: WebSocket | null = null;

  async startVerification(template: string): Promise<VerificationSession> {
    // Connect WebSocket for real-time updates
    this.connectWebSocket();

    // Start verification
    const response = await fetch('/api/verify', {
      method: 'POST',
      body: JSON.stringify({ template, userId: this.userId })
    });

    return response.json();
  }

  private connectWebSocket() {
    this.ws = new WebSocket(`wss://api.example.com/ws/${this.userId}`);

    this.ws.onmessage = (event) => {
      const data = JSON.parse(event.data);

      if (data.type === 'verification_result') {
        this.handleVerificationResult(data);
      }
    };
  }

  private handleVerificationResult(data: any) {
    if (data.status === 'verified') {
      this.onVerified(data.result);
    } else {
      this.onFailed(data.status);
    }
  }
}
```

---

## 5. Polling-based Sync Flow

**Use Case:** Simple integration without webhook infrastructure, suitable for development and simple use cases.

**Pattern:** Client polls for status updates

### Overview

```
Frontend        Backend          Verify API
    |              |                 |
    |--Start------>|                 |
    |              |--Create session-|
    |              |<--Session ID----|
    |<--QR code----|                 |
    |              |                 |
    |--Poll status>|                 |
    |              |--Get session--->|
    |              |<--Pending-------|
    |<--Pending----|                 |
    |              |                 |
    [User scans and verifies]
    |              |                 |
    |--Poll status>|                 |
    |              |--Get session--->|
    |              |<--Verified------|
    |<--Verified---|                 |
```

### Implementation

**Using SDK Polling:**

```typescript
import { VerifyClient } from '@waltid/verify-sdk';

const client = new VerifyClient({ apiKey: 'vfy_test_xxx' });

async function verifyWithPolling() {
  // Create session
  const session = await client.verifyIdentity({
    template: 'kyc_basic'
  });

  console.log('Scan QR code:', session.qrCodeUrl);

  // Poll until complete (SDK handles the loop)
  try {
    const result = await client.pollSession(session.sessionId, {
      timeout: 300000,  // 5 minutes
      interval: 2000    // Poll every 2 seconds
    });

    if (result.status === 'verified') {
      console.log('Verified!', result.result?.answers);
      return result;
    } else {
      console.log('Failed:', result.status);
      return null;
    }
  } catch (error) {
    if (error instanceof PollingTimeoutError) {
      console.log('Verification timed out');
    }
    throw error;
  }
}
```

**Manual Polling:**

```typescript
async function manualPolling(sessionId: string, options = {}) {
  const { timeout = 300000, interval = 2000 } = options;
  const startTime = Date.now();

  while (Date.now() - startTime < timeout) {
    const response = await fetch(`/v1/sessions/${sessionId}`, {
      headers: { 'Authorization': `Bearer ${apiKey}` }
    });

    const session = await response.json();

    // Check for terminal state
    if (session.status !== 'pending') {
      return session;
    }

    // Wait before next poll
    await new Promise(resolve => setTimeout(resolve, interval));
  }

  throw new Error('Polling timeout');
}
```

**With Status Updates (Async Iterator):**

```typescript
async function pollWithUpdates(sessionId: string) {
  for await (const status of client.pollSessionIterator(sessionId)) {
    // Called on each poll
    updateProgressUI(status);

    if (status.status !== 'pending') {
      return status;
    }
  }
}
```

---

## 6. Same-Device Deep Link Flow

**Use Case:** Mobile app where the user verifies on the same device, using deep links to switch between your app and the wallet app.

**Pattern:** App-to-app communication via deep links

### Overview

```
Your App        Wallet App       Verify API
    |               |                |
    |--Start verification----------->|
    |<--Deep link + session----------|
    |               |                |
    |--Open deep link->              |
    |               |--Request URI-->|
    |               |<--VP Request---|
    |               |                |
    |               [User approves]  |
    |               |                |
    |               |--VP Response-->|
    |<--Redirect----|                |
    |               |                |
    |--Check status----------------->|
    |<--Verified---------------------|
```

### iOS Implementation

```swift
import WaltIDVerifySDK
import UIKit

class VerificationManager {
    private let client = VerifyClient(config: VerifyConfig(
        apiKey: Configuration.verifyApiKey
    ))

    func startSameDeviceVerification() async throws -> VerificationSession {
        let session = try await client.verifyIdentity(
            VerificationRequest(
                template: "kyc_basic",
                redirectUri: "myapp://verification-callback"
            )
        )

        // Open wallet app
        if let url = URL(string: session.deepLink) {
            await UIApplication.shared.open(url)
        }

        return session
    }

    func handleCallback(url: URL) async throws -> SessionStatus? {
        // Parse callback URL
        guard let components = URLComponents(url: url, resolvingAgainstBaseURL: false),
              let sessionId = components.queryItems?.first(where: { $0.name == "session_id" })?.value else {
            return nil
        }

        // Check result
        return try await client.getSession(sessionId)
    }
}

// SceneDelegate or App
func scene(_ scene: UIScene, openURLContexts URLContexts: Set<UIOpenURLContext>) {
    guard let url = URLContexts.first?.url,
          url.scheme == "myapp",
          url.host == "verification-callback" else {
        return
    }

    Task {
        let result = try await verificationManager.handleCallback(url: url)
        handleVerificationResult(result)
    }
}
```

### Android Implementation

```kotlin
import id.walt.verify.sdk.*
import android.content.Intent
import android.net.Uri

class VerificationActivity : ComponentActivity() {
    private val client = VerifyClient(VerifyConfig(apiKey = BuildConfig.VERIFY_API_KEY))
    private var currentSessionId: String? = null

    fun startSameDeviceVerification() {
        lifecycleScope.launch {
            val session = client.verifyIdentity(
                VerificationRequest(
                    template = "kyc_basic",
                    redirectUri = "myapp://verification-callback"
                )
            )

            currentSessionId = session.sessionId

            // Open wallet app
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(session.deepLink))
            startActivity(intent)
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)

        intent?.data?.let { uri ->
            if (uri.scheme == "myapp" && uri.host == "verification-callback") {
                handleCallback(uri)
            }
        }
    }

    private fun handleCallback(uri: Uri) {
        val sessionId = uri.getQueryParameter("session_id") ?: currentSessionId

        sessionId?.let { id ->
            lifecycleScope.launch {
                val result = client.getSession(id)
                handleVerificationResult(result)
            }
        }
    }

    private fun handleVerificationResult(result: SessionStatus) {
        when {
            result.isVerified -> showSuccess(result.result)
            result.isFailed -> showError("Verification failed")
            else -> showError("Unexpected status: ${result.status}")
        }
    }
}

// AndroidManifest.xml
<activity android:name=".VerificationActivity">
    <intent-filter>
        <action android:name="android.intent.action.VIEW" />
        <category android:name="android.intent.category.DEFAULT" />
        <category android:name="android.intent.category.BROWSABLE" />
        <data
            android:scheme="myapp"
            android:host="verification-callback" />
    </intent-filter>
</activity>
```

---

## 7. Cross-Device QR Code Flow

**Use Case:** Desktop or laptop user verifies identity by scanning a QR code with their mobile wallet.

**Pattern:** QR code displayed on one device, scanned on another

### Overview

```
Desktop Browser    Mobile Wallet    Verify API
       |                |               |
       |--Start------------------------>|
       |<--QR code + session------------|
       |                |               |
       |[Display QR]    |               |
       |                |               |
       |    [User scans QR]             |
       |                |--Request----->|
       |                |<--VP Request--|
       |                |               |
       |                [User approves] |
       |                |               |
       |                |--VP Response->|
       |                |<--Success-----|
       |                |               |
       |<--Webhook/Poll-----------------|
       |[Update UI]     |               |
```

### Implementation

**Backend:**

```typescript
import { VerifyClient } from '@waltid/verify-sdk';

const client = new VerifyClient({ apiKey: process.env.VERIFY_API_KEY! });

// Create verification session
app.post('/api/verify/start', async (req, res) => {
  const session = await client.verifyIdentity({
    template: req.body.template,
    callbackUrl: `${process.env.BASE_URL}/api/verify/webhook`,
    metadata: {
      userId: req.user.id,
      browserSessionId: req.sessionID
    }
  });

  // Store session mapping
  await redis.set(`verify:${session.sessionId}`, req.sessionID, 'EX', 600);

  res.json({
    sessionId: session.sessionId,
    qrCodeUrl: session.qrCodeUrl,
    // Note: No deep link for cross-device flow
    expiresAt: session.expiresAt
  });
});

// Webhook handler
app.post('/api/verify/webhook', async (req, res) => {
  const { sessionId, status, result, metadata } = req.body;

  // Get browser session
  const browserSessionId = await redis.get(`verify:${sessionId}`);

  // Publish result to browser via SSE or WebSocket
  await publishToSession(browserSessionId, {
    type: 'verification_complete',
    status,
    result
  });

  res.sendStatus(200);
});
```

**Frontend:**

```tsx
import { useState, useEffect } from 'react';
import QRCode from 'qrcode.react';

function CrossDeviceVerification() {
  const [session, setSession] = useState<VerificationSession | null>(null);
  const [status, setStatus] = useState<'loading' | 'waiting' | 'verified' | 'failed'>('loading');
  const [timeLeft, setTimeLeft] = useState(600); // 10 minutes

  useEffect(() => {
    startVerification();
  }, []);

  async function startVerification() {
    const response = await fetch('/api/verify/start', {
      method: 'POST',
      body: JSON.stringify({ template: 'kyc_basic' })
    });

    const data = await response.json();
    setSession(data);
    setStatus('waiting');

    // Connect to SSE for real-time updates
    const eventSource = new EventSource(`/api/verify/events/${data.sessionId}`);

    eventSource.onmessage = (event) => {
      const message = JSON.parse(event.data);

      if (message.type === 'verification_complete') {
        setStatus(message.status === 'verified' ? 'verified' : 'failed');
        eventSource.close();
      }
    };

    // Fallback: Poll for updates
    startPolling(data.sessionId);
  }

  async function startPolling(sessionId: string) {
    for (let i = 0; i < 300; i++) { // 10 min at 2s intervals
      await new Promise(r => setTimeout(r, 2000));

      const response = await fetch(`/api/verify/status/${sessionId}`);
      const data = await response.json();

      if (data.status !== 'pending') {
        setStatus(data.status === 'verified' ? 'verified' : 'failed');
        return;
      }
    }
  }

  // Countdown timer
  useEffect(() => {
    if (status !== 'waiting') return;

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

  return (
    <div className="cross-device-verification">
      {status === 'loading' && (
        <div className="loading">
          <Spinner />
          <p>Preparing verification...</p>
        </div>
      )}

      {status === 'waiting' && session && (
        <div className="qr-display">
          <h2>Scan with your wallet</h2>
          <p>Open your wallet app and scan this QR code</p>

          <div className="qr-container">
            <QRCode
              value={session.qrCodeUrl}
              size={300}
              level="M"
            />
          </div>

          <p className="timer">
            Time remaining: <strong>{formatTime(timeLeft)}</strong>
          </p>

          <p className="instructions">
            Using mobile? Open your wallet app and use the built-in scanner.
          </p>
        </div>
      )}

      {status === 'verified' && (
        <div className="success">
          <CheckIcon size={64} />
          <h2>Verification Complete!</h2>
          <p>Your identity has been verified successfully.</p>
          <button onClick={() => window.location.reload()}>
            Continue
          </button>
        </div>
      )}

      {status === 'failed' && (
        <div className="failed">
          <ErrorIcon size={64} />
          <h2>Verification Failed</h2>
          <p>We couldn't verify your identity. Please try again.</p>
          <button onClick={startVerification}>
            Try Again
          </button>
        </div>
      )}
    </div>
  );
}
```

---

## Summary

| Scenario | Complexity | Best For |
|----------|------------|----------|
| 1. Age Verification | Simple | Quick age checks at checkout |
| 2. Full KYC | Complex | Regulated industries, onboarding |
| 3. PWA Payment | Medium | High-value transactions |
| 4. Webhook Async | Medium | Production deployments |
| 5. Polling Sync | Simple | Development, simple integrations |
| 6. Same-Device | Medium | Mobile apps |
| 7. Cross-Device | Medium | Desktop/laptop users |

## Next Steps

- [Quick Start](./quickstart.md) - Get started in 5 minutes
- [SDK Integration](./sdk-integration.md) - Detailed SDK documentation
- [Webhook Integration](./webhook-integration.md) - Webhook setup and security
- [API Reference](./api-reference.md) - REST API documentation
