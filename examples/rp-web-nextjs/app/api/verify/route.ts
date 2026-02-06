import { NextResponse } from 'next/server';

const VERIFY_API_KEY = process.env.VERIFY_API_KEY || 'vfy_test_xxx';
const VERIFY_API_URL = process.env.VERIFY_API_URL || 'http://localhost:7010';

interface VerifyRequest {
  template: string;
}

interface VerifyApiResponse {
  sessionId: string;
  authorizationRequest: string;
  expiresAt: string;
}

export async function POST(request: Request) {
  try {
    const body: VerifyRequest = await request.json();
    const { template } = body;

    if (!template) {
      return NextResponse.json(
        { error: 'Template is required' },
        { status: 400 }
      );
    }

    // Call Verify API to start verification session
    const response = await fetch(`${VERIFY_API_URL}/v1/verify/identity`, {
      method: 'POST',
      headers: {
        'Authorization': `Bearer ${VERIFY_API_KEY}`,
        'Content-Type': 'application/json',
      },
      body: JSON.stringify({
        template,
        // Optional: pass webhook URL if configured
        webhookUrl: process.env.WEBHOOK_URL,
      }),
    });

    if (!response.ok) {
      const errorText = await response.text();
      console.error('Verify API error:', response.status, errorText);
      return NextResponse.json(
        { error: 'Failed to start verification' },
        { status: response.status }
      );
    }

    const data: VerifyApiResponse = await response.json();

    // Transform response for frontend
    // The authorizationRequest is the OpenID4VP URL that can be:
    // - Encoded in QR code for cross-device flow
    // - Used as deep link for same-device flow
    const qrCodeData = data.authorizationRequest;

    // Create deep link for same-device flow
    // This converts the openid4vp:// URL to a universal link format
    // that wallet apps can handle
    const deepLink = data.authorizationRequest;

    return NextResponse.json({
      sessionId: data.sessionId,
      qrCodeData,
      deepLink,
      expiresAt: data.expiresAt,
    });
  } catch (error) {
    console.error('Error starting verification:', error);
    return NextResponse.json(
      { error: 'Internal server error' },
      { status: 500 }
    );
  }
}
