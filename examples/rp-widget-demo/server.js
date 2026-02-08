/**
 * Simple demo server for WaltVerify Widget SDK
 *
 * This server:
 * 1. Serves the static HTML demo page
 * 2. Provides an endpoint to generate client tokens
 *
 * In production, you would generate client tokens from your backend
 * and pass them to your frontend.
 */

const express = require('express');
const path = require('path');

const app = express();
const PORT = process.env.PORT || 3002;

// Configuration from environment
// Default sandbox credentials - work immediately without any setup
// See: docs/verify-api/sandbox-credentials.md
const VERIFY_API_URL = process.env.VERIFY_API_URL || 'http://localhost:7010';
const VERIFY_API_KEY = process.env.VERIFY_API_KEY || 'vfy_test_sandbox_demo_key_12345678';

// Serve static files
app.use(express.static(path.join(__dirname, 'public')));

// Parse JSON bodies
app.use(express.json());

/**
 * GET /api/token
 *
 * Generate a client token for the widget SDK.
 *
 * In production, this endpoint should:
 * 1. Authenticate the user/session
 * 2. Apply rate limiting
 * 3. Scope the token to specific templates if needed
 */
app.get('/api/token', async (req, res) => {
  try {
    console.log('[Token] Generating client token...');

    const response = await fetch(`${VERIFY_API_URL}/v1/widget/tokens`, {
      method: 'POST',
      headers: {
        'Authorization': `Bearer ${VERIFY_API_KEY}`,
        'Content-Type': 'application/json'
      },
      body: JSON.stringify({
        // Token valid for 15 minutes (default)
        expires_in: 900,
        // Allow any template (empty = all)
        templates: [],
        // Allow requests from any origin for demo
        allowed_origins: ['*']
      })
    });

    if (!response.ok) {
      const error = await response.text();
      console.error('[Token] API error:', response.status, error);
      return res.status(response.status).json({
        error: 'Failed to generate token',
        details: error
      });
    }

    const data = await response.json();
    console.log('[Token] Generated:', data.client_token.substring(0, 20) + '...');

    res.json({
      clientToken: data.client_token,
      expiresAt: data.expires_at
    });
  } catch (error) {
    console.error('[Token] Error:', error.message);
    res.status(500).json({
      error: 'Failed to generate token',
      message: error.message
    });
  }
});

/**
 * GET /api/config
 *
 * Return the Verify API URL for the widget SDK.
 * This allows the demo to work with different API endpoints.
 */
app.get('/api/config', (req, res) => {
  res.json({
    apiBaseUrl: VERIFY_API_URL
  });
});

// Health check
app.get('/health', (req, res) => {
  res.json({ status: 'ok' });
});

// Start server
app.listen(PORT, () => {
  console.log('');
  console.log('='.repeat(60));
  console.log('  WaltVerify Widget SDK Demo');
  console.log('='.repeat(60));
  console.log('');
  console.log(`  Demo page:   http://localhost:${PORT}`);
  console.log(`  API URL:     ${VERIFY_API_URL}`);
  console.log(`  API Key:     ${VERIFY_API_KEY.substring(0, 15)}...`);
  console.log('');
  console.log('  Make sure the Verify API is running at the configured URL.');
  console.log('');
  console.log('='.repeat(60));
  console.log('');
});
