import { NextResponse } from 'next/server';
import crypto from 'crypto';

const WEBHOOK_SECRET = process.env.WEBHOOK_SECRET || '';

interface WebhookPayload {
  event: 'session.verified' | 'session.failed' | 'session.expired';
  sessionId: string;
  timestamp: string;
  data: {
    status: string;
    result?: {
      claims?: Record<string, unknown>;
    };
    error?: string;
  };
}

/**
 * Verify webhook signature (HMAC-SHA256)
 */
function verifySignature(
  payload: string,
  signature: string | null,
  secret: string
): boolean {
  if (!signature || !secret) {
    return false;
  }

  const expectedSignature = crypto
    .createHmac('sha256', secret)
    .update(payload)
    .digest('hex');

  // Timing-safe comparison
  return crypto.timingSafeEqual(
    Buffer.from(signature),
    Buffer.from(expectedSignature)
  );
}

/**
 * Webhook endpoint for receiving verification results
 *
 * The Verify API sends webhooks when:
 * - session.verified: User successfully verified
 * - session.failed: Verification failed
 * - session.expired: Session expired without completion
 */
export async function POST(request: Request) {
  try {
    const rawBody = await request.text();
    const signature = request.headers.get('x-webhook-signature');

    // Verify webhook signature if secret is configured
    if (WEBHOOK_SECRET) {
      if (!verifySignature(rawBody, signature, WEBHOOK_SECRET)) {
        console.error('Invalid webhook signature');
        return NextResponse.json(
          { error: 'Invalid signature' },
          { status: 401 }
        );
      }
    }

    const payload: WebhookPayload = JSON.parse(rawBody);

    console.log('Received webhook:', {
      event: payload.event,
      sessionId: payload.sessionId,
      timestamp: payload.timestamp,
    });

    // Handle different webhook events
    switch (payload.event) {
      case 'session.verified':
        await handleVerified(payload);
        break;

      case 'session.failed':
        await handleFailed(payload);
        break;

      case 'session.expired':
        await handleExpired(payload);
        break;

      default:
        console.warn('Unknown webhook event:', payload.event);
    }

    // Always return 200 to acknowledge receipt
    return NextResponse.json({ received: true });
  } catch (error) {
    console.error('Webhook processing error:', error);
    // Return 200 even on errors to prevent retries for malformed payloads
    return NextResponse.json({ received: true, error: 'Processing error' });
  }
}

/**
 * Handle successful verification
 */
async function handleVerified(payload: WebhookPayload): Promise<void> {
  const { sessionId, data } = payload;

  console.log(`Session ${sessionId} verified successfully`);
  console.log('Verified claims:', data.result?.claims);

  // In a real application, you would:
  // 1. Update your database with the verification result
  // 2. Trigger next steps in your workflow (e.g., allow checkout)
  // 3. Send notifications to the user

  // Example: Store in database
  // await db.verifications.update({
  //   where: { sessionId },
  //   data: {
  //     status: 'verified',
  //     verifiedAt: new Date(),
  //     claims: data.result?.claims,
  //   },
  // });

  // Example: Send real-time update via WebSocket or Server-Sent Events
  // await pusher.trigger(`session-${sessionId}`, 'verified', data);
}

/**
 * Handle failed verification
 */
async function handleFailed(payload: WebhookPayload): Promise<void> {
  const { sessionId, data } = payload;

  console.log(`Session ${sessionId} verification failed`);
  console.log('Error:', data.error);

  // Update database, notify user, etc.
}

/**
 * Handle expired session
 */
async function handleExpired(payload: WebhookPayload): Promise<void> {
  const { sessionId } = payload;

  console.log(`Session ${sessionId} expired`);

  // Clean up any pending state
}
