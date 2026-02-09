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

// Configuration from environment
// Default sandbox credentials - work immediately without any setup
// See: docs/verify-api/sandbox-credentials.md
const VERIFY_API_URL = process.env.VERIFY_API_URL || 'http://localhost:7010';
const VERIFY_API_KEY = process.env.VERIFY_API_KEY || 'vfy_test_sandbox_demo_key_12345678';

// PUBLIC_VERIFY_API_URL is what the browser uses to load the SDK
// This must be browser-accessible (e.g., http://localhost:7010 or https://verify-api.example.com)
// VERIFY_API_URL is for server-to-server calls (can be Docker internal hostname)
const PUBLIC_VERIFY_API_URL = process.env.PUBLIC_VERIFY_API_URL || VERIFY_API_URL;

// Registered RP configuration (from verifier-api2 RP Registrar)
const RP_ID = process.env.RP_ID || '';
const RP_CLIENT_ID = process.env.RP_CLIENT_ID || '';
const RP_DOMAIN = process.env.RP_DOMAIN || '';

// Verifier API2 internal URL (for proxying verification-session responses from wallets)
const VERIFIER_API2_URL = process.env.VERIFIER_API2_URL || 'http://verifier-api2:7004';

// Export for use in tests
const config = {
  VERIFY_API_URL,
  VERIFY_API_KEY,
  PUBLIC_VERIFY_API_URL,
  RP_ID,
  RP_CLIENT_ID,
  RP_DOMAIN
};

/**
 * Create and configure Express application
 * @returns {express.Application} Configured Express app
 */
function createApp() {
  const app = express();

  // Proxy verification-session requests to verifier-api2
  // The EUDI wallet POSTs VP tokens to response_uri which uses the RP's domain.
  // This proxy forwards those requests to verifier-api2 which owns the session.
  // Uses raw http.request to stream bytes without encoding/compression issues.
  const http = require('http');
  app.all('/verification-session/*', (req, res) => {
    const target = new URL(`${VERIFIER_API2_URL}${req.originalUrl}`);
    console.log(`[Proxy] ${req.method} ${req.originalUrl} -> ${target.href}`);

    const proxyReq = http.request({
      hostname: target.hostname,
      port: target.port,
      path: target.pathname + target.search,
      method: req.method,
      headers: {
        ...req.headers,
        host: target.host,
      },
    }, (proxyRes) => {
      console.log(`[Proxy] Response: ${proxyRes.statusCode}`);
      res.writeHead(proxyRes.statusCode, proxyRes.headers);
      proxyRes.pipe(res);
    });

    proxyReq.on('error', (err) => {
      console.error(`[Proxy] Error: ${err.message}`);
      res.status(502).json({ error: 'Proxy error', message: err.message });
    });

    req.pipe(proxyReq);
  });

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

      const response = await fetch(`${config.VERIFY_API_URL}/v1/widget/tokens`, {
        method: 'POST',
        headers: {
          'Authorization': `Bearer ${config.VERIFY_API_KEY}`,
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
   * Return the PUBLIC Verify API URL for the widget SDK.
   * This URL must be browser-accessible (not Docker internal hostname).
   */
  app.get('/api/config', (req, res) => {
    const response = {
      apiBaseUrl: config.PUBLIC_VERIFY_API_URL
    };
    // Include RP config if configured
    if (config.RP_ID) {
      response.rp = {
        id: config.RP_ID,
        clientId: config.RP_CLIENT_ID,
        domain: config.RP_DOMAIN
      };
    }
    res.json(response);
  });

  // Health check
  app.get('/health', (req, res) => {
    res.json({ status: 'ok' });
  });

  return app;
}

/**
 * Start the server (only when run directly, not when imported)
 */
function startServer() {
  const PORT = process.env.PORT || 3002;
  const app = createApp();

  app.listen(PORT, () => {
    console.log('');
    console.log('='.repeat(60));
    console.log('  WaltVerify Widget SDK Demo');
    console.log('='.repeat(60));
    console.log('');
    console.log(`  Demo page:   http://localhost:${PORT}`);
    console.log(`  API URL:     ${config.VERIFY_API_URL}`);
    console.log(`  API Key:     ${config.VERIFY_API_KEY.substring(0, 15)}...`);
    if (config.RP_ID) {
      console.log(`  RP ID:       ${config.RP_ID}`);
      console.log(`  RP Client:   ${config.RP_CLIENT_ID}`);
      console.log(`  RP Domain:   ${config.RP_DOMAIN}`);
    }
    console.log('');
    console.log('  Make sure the Verify API is running at the configured URL.');
    console.log('');
    console.log('='.repeat(60));
    console.log('');
  });

  return app;
}

// Only start server when run directly (not when required as a module)
if (require.main === module) {
  startServer();
}

// Export for testing
module.exports = { createApp, config };
