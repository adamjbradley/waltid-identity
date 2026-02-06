import { NextResponse } from 'next/server';

const VERIFY_API_KEY = process.env.VERIFY_API_KEY || 'vfy_test_xxx';
const VERIFY_API_URL = process.env.VERIFY_API_URL || 'http://localhost:7010';

interface SessionResponse {
  session_id: string;
  status: 'pending' | 'verified' | 'failed' | 'expired';
  template_name: string;
  result?: {
    claims?: Record<string, unknown>;
  } | null;
  verified_at?: number | null;
  metadata?: Record<string, string> | null;
  expires_at: number;
  error?: string;
}

export async function GET(
  request: Request,
  { params }: { params: { id: string } }
) {
  try {
    const sessionId = params.id;

    if (!sessionId) {
      return NextResponse.json(
        { error: 'Session ID is required' },
        { status: 400 }
      );
    }

    // Fetch session status from Verify API
    const response = await fetch(`${VERIFY_API_URL}/v1/sessions/${sessionId}`, {
      headers: {
        'Authorization': `Bearer ${VERIFY_API_KEY}`,
      },
    });

    if (!response.ok) {
      if (response.status === 404) {
        return NextResponse.json(
          { error: 'Session not found' },
          { status: 404 }
        );
      }

      const errorText = await response.text();
      console.error('Verify API error:', response.status, errorText);
      return NextResponse.json(
        { error: 'Failed to fetch session status' },
        { status: response.status }
      );
    }

    const data: SessionResponse = await response.json();

    // Transform snake_case API response to camelCase for frontend
    return NextResponse.json({
      sessionId: data.session_id,
      status: data.status,
      templateName: data.template_name,
      result: data.result,
      verifiedAt: data.verified_at,
      metadata: data.metadata,
      expiresAt: data.expires_at,
      error: data.error,
    });
  } catch (error) {
    console.error('Error fetching session:', error);
    return NextResponse.json(
      { error: 'Internal server error' },
      { status: 500 }
    );
  }
}
