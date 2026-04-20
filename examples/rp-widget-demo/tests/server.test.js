/**
 * Unit and Integration Tests for the Widget SDK Demo Server
 *
 * These tests verify the server endpoints work correctly:
 * - GET /health - Health check endpoint
 * - GET /api/config - Returns API configuration
 * - GET /api/token - Generates client tokens (requires mocking)
 */

const request = require('supertest');
const { createApp, config } = require('../server');

describe('Widget Demo Server', () => {
  let app;

  beforeAll(() => {
    app = createApp();
  });

  describe('GET /health', () => {
    it('returns status ok', async () => {
      const response = await request(app)
        .get('/health')
        .expect('Content-Type', /json/)
        .expect(200);

      expect(response.body).toEqual({ status: 'ok' });
    });
  });

  describe('GET /api/config', () => {
    it('returns API base URL configuration', async () => {
      const response = await request(app)
        .get('/api/config')
        .expect('Content-Type', /json/)
        .expect(200);

      expect(response.body).toHaveProperty('apiBaseUrl');
      expect(response.body.apiBaseUrl).toBe(config.VERIFY_API_URL);
    });

    it('returns default sandbox URL when env not set', async () => {
      const response = await request(app)
        .get('/api/config')
        .expect(200);

      // Default should be localhost:7010
      expect(response.body.apiBaseUrl).toMatch(/localhost:7010|7010/);
    });
  });

  describe('Configuration', () => {
    it('uses default sandbox API key', () => {
      expect(config.VERIFY_API_KEY).toBe('vfy_test_sandbox_demo_key_12345678');
    });

    it('uses default sandbox API URL', () => {
      expect(config.VERIFY_API_URL).toBe('http://localhost:7010');
    });

    it('API key follows expected format', () => {
      expect(config.VERIFY_API_KEY).toMatch(/^vfy_/);
    });
  });

  describe('Static file serving', () => {
    it('serves index.html at root', async () => {
      const response = await request(app)
        .get('/')
        .expect('Content-Type', /html/)
        .expect(200);

      expect(response.text).toContain('<!DOCTYPE html>');
      expect(response.text).toContain('WaltVerify');
    });

    it('serves the demo page with expected content', async () => {
      const response = await request(app)
        .get('/')
        .expect(200);

      // Check for key demo content
      expect(response.text).toContain('MAJESTIC');
      expect(response.text).toContain('Age Verification');
    });
  });

  describe('GET /api/catalogue', () => {
    it('returns 12 products with ageRestricted flags', async () => {
      const res = await request(app).get('/api/catalogue').expect(200);
      expect(res.body).toHaveLength(12);
      expect(res.body[0]).toHaveProperty('ageRestricted', true);
    });
  });
});

describe('GET /api/token - Error Handling', () => {
  let app;
  let originalFetch;

  beforeAll(() => {
    app = createApp();
    originalFetch = global.fetch;
  });

  afterEach(() => {
    // Restore fetch after each test
    global.fetch = originalFetch;
  });

  afterAll(() => {
    // Ensure fetch is fully restored after all tests in this block
    global.fetch = originalFetch;
  });

  it('handles API connection errors gracefully', async () => {
    // Mock fetch to simulate connection error
    global.fetch = jest.fn().mockRejectedValue(new Error('ECONNREFUSED'));

    const response = await request(app)
      .get('/api/token')
      .expect('Content-Type', /json/)
      .expect(500);

    expect(response.body).toHaveProperty('error');
    expect(response.body.error).toBe('Failed to generate token');
    expect(response.body).toHaveProperty('message');
  });

  it('handles API 401 unauthorized error', async () => {
    // Mock fetch to return 401
    global.fetch = jest.fn().mockResolvedValue({
      ok: false,
      status: 401,
      text: async () => 'Invalid API key'
    });

    const response = await request(app)
      .get('/api/token')
      .expect('Content-Type', /json/)
      .expect(401);

    expect(response.body).toHaveProperty('error');
    expect(response.body.error).toBe('Failed to generate token');
    expect(response.body).toHaveProperty('details');
  });

  it('handles API 500 server error', async () => {
    // Mock fetch to return 500
    global.fetch = jest.fn().mockResolvedValue({
      ok: false,
      status: 500,
      text: async () => 'Internal server error'
    });

    const response = await request(app)
      .get('/api/token')
      .expect('Content-Type', /json/)
      .expect(500);

    expect(response.body).toHaveProperty('error');
    expect(response.body).toHaveProperty('details');
  });

  it('returns clientToken when API call succeeds', async () => {
    // Mock successful API response
    const mockToken = 'ct_test_mock_client_token_123';
    const mockExpiry = new Date(Date.now() + 900000).toISOString();

    global.fetch = jest.fn().mockResolvedValue({
      ok: true,
      json: async () => ({
        client_token: mockToken,
        expires_at: mockExpiry
      })
    });

    const response = await request(app)
      .get('/api/token')
      .expect('Content-Type', /json/)
      .expect(200);

    expect(response.body).toHaveProperty('clientToken');
    expect(response.body.clientToken).toBe(mockToken);
    expect(response.body).toHaveProperty('expiresAt');
    expect(response.body.expiresAt).toBe(mockExpiry);
  });

  it('calls the correct API endpoint', async () => {
    global.fetch = jest.fn().mockResolvedValue({
      ok: true,
      json: async () => ({
        client_token: 'ct_test',
        expires_at: new Date().toISOString()
      })
    });

    await request(app).get('/api/token');

    expect(global.fetch).toHaveBeenCalledWith(
      expect.stringContaining('/v1/widget/tokens'),
      expect.objectContaining({
        method: 'POST',
        headers: expect.objectContaining({
          'Authorization': expect.stringContaining('Bearer'),
          'Content-Type': 'application/json'
        })
      })
    );
  });

  it('sends correct request body to API', async () => {
    global.fetch = jest.fn().mockResolvedValue({
      ok: true,
      json: async () => ({
        client_token: 'ct_test',
        expires_at: new Date().toISOString()
      })
    });

    await request(app).get('/api/token');

    const fetchCall = global.fetch.mock.calls[0];
    const requestBody = JSON.parse(fetchCall[1].body);

    expect(requestBody).toHaveProperty('expires_in', 900);
    expect(requestBody).toHaveProperty('templates', []);
    expect(requestBody).toHaveProperty('allowed_origins', ['*']);
  });
});

describe('POST /api/age-check/start', () => {
  // verifier-api2's session-create contract (verified against
  // waltid-auth-op/src/main/kotlin/id/walt/authop/upstream/Verifier2Client.kt
  // which in turn cites VerificationSessionSetupData.kt line refs) uses
  // `flow_type: "cross_device"` + a `core_flow` envelope, snake_case
  // `dcql_query`, and webhook credentials nested under
  // `core_flow.notifications.webhook.{url, bearer_token}`. The tests assert
  // that real wire shape — not the sketch in the plan which was written
  // before the upstream contract was double-checked.
  let app;
  let originalFetch;
  beforeAll(() => {
    app = createApp();
    originalFetch = global.fetch;
  });
  afterAll(() => { global.fetch = originalFetch; });

  beforeEach(() => {
    global.fetch = jest.fn(async (url, opts) => {
      if (typeof url === 'string' && url.includes('/verification-session/create')) {
        return {
          ok: true,
          status: 200,
          json: async () => ({
            sessionId: 'sess-abc',
            bootstrapAuthorizationRequestUrl: 'openid4vp://authorize?request_uri=foo',
            fullAuthorizationRequestUrl: 'openid4vp://authorize?request_uri=foo_full',
            creationTarget: null,
          }),
        };
      }
      throw new Error('unexpected fetch ' + url);
    });
  });

  it('returns sessionId and qrCode and forwards an age-only DCQL query', async () => {
    const agent = request.agent(app);
    const res = await agent.post('/api/age-check/start').expect(200);
    expect(res.body).toHaveProperty('sessionId', 'sess-abc');
    expect(res.body).toHaveProperty('qrCode');
    // QR code should be the bootstrap URL for cross-device (falls back to full).
    expect(res.body.qrCode).toMatch(/^openid4vp:/);

    // Inspect the payload we sent upstream.
    expect(global.fetch).toHaveBeenCalledTimes(1);
    const [url, opts] = global.fetch.mock.calls[0];
    expect(url).toMatch(/\/verification-session\/create\?rpId=/);
    const body = JSON.parse(opts.body);
    expect(body.flow_type).toBe('cross_device');
    expect(body.core_flow).toBeDefined();
    expect(body.core_flow.dcql_query).toBeDefined();
    // Age-only claim across the four EUDI-compat PID VCTs, expressed as
    // per-VCT singletons + credential_sets (PR #88 workaround for the EUDI
    // iOS wallet-kit's first-VCT-only matcher).
    const serialized = JSON.stringify(body.core_flow.dcql_query);
    expect(serialized).toContain('age_over_21');
    expect(serialized).toContain('urn:eudi:pid:1');
    expect(serialized).toContain('urn:au:gov:mygovid:pid:1');
    expect(serialized).toContain('urn:in:gov:aadhaar:pid:1');
    expect(serialized).toContain('urn:uk:gov:govuk-one-login:pid:1');
    expect(body.core_flow.dcql_query.credential_sets).toBeDefined();
    expect(body.core_flow.dcql_query.credentials).toHaveLength(4);
    // Webhook registration nests under notifications.webhook (verifier-api2
    // KtorSessionNotifications contract: url + bearer_token SerialName).
    expect(body.core_flow.notifications).toBeDefined();
    expect(body.core_flow.notifications.webhook).toBeDefined();
    expect(body.core_flow.notifications.webhook.url).toMatch(/\/api\/age-check\/webhook\//);
    expect(body.core_flow.notifications.webhook.bearer_token).toBeTruthy();
  });

  it('502s when verifier-api2 rejects the session-create', async () => {
    global.fetch = jest.fn(async () => ({
      ok: false,
      status: 500,
      text: async () => 'boom',
      json: async () => ({}),
    }));
    const agent = request.agent(app);
    const res = await agent.post('/api/age-check/start').expect(502);
    expect(res.body).toEqual({ error: 'verifier_unavailable' });
  });

  it('502s when verifier-api2 is unreachable', async () => {
    global.fetch = jest.fn(async () => { throw new Error('ECONNREFUSED'); });
    const agent = request.agent(app);
    const res = await agent.post('/api/age-check/start').expect(502);
    expect(res.body).toEqual({ error: 'verifier_unavailable' });
  });
});

describe('Integration Tests with Sandbox API', () => {
  let app;
  let sandboxAvailable = false;
  const VERIFY_API_URL = process.env.VERIFY_API_URL || 'http://localhost:7010';

  beforeAll(async () => {
    app = createApp();

    // Check if the sandbox API is available by testing the widget tokens endpoint.
    // We use a POST with no body to get a 400 or 401 (which confirms the endpoint exists).
    // A 404 means the endpoint doesn't exist (wrong API).
    // Connection refused means the server isn't running.
    try {
      const response = await fetch(`${VERIFY_API_URL}/v1/widget/tokens`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({})
      });
      // Endpoint exists if we get any response other than 404
      sandboxAvailable = response.status !== 404;
    } catch {
      sandboxAvailable = false;
    }

    if (!sandboxAvailable) {
      console.log('\n  Sandbox API not available at', VERIFY_API_URL);
      console.log('  Integration tests will be skipped.\n');
    }
  });

  it('successfully generates client token from sandbox API', async () => {
    if (!sandboxAvailable) {
      console.log('    [SKIP] Sandbox API not running');
      return;
    }

    const response = await request(app)
      .get('/api/token')
      .expect('Content-Type', /json/)
      .expect(200);

    expect(response.body).toHaveProperty('clientToken');
    expect(response.body.clientToken).toMatch(/^ct_/);
    expect(response.body).toHaveProperty('expiresAt');
  });

  it('verifies token expiry is in the future', async () => {
    if (!sandboxAvailable) {
      console.log('    [SKIP] Sandbox API not running');
      return;
    }

    const response = await request(app)
      .get('/api/token')
      .expect(200);

    // expiresAt is epoch seconds, convert to milliseconds for Date comparison
    const expiryMs = response.body.expiresAt * 1000;
    const expiryDate = new Date(expiryMs);
    const now = new Date();
    expect(expiryDate.getTime()).toBeGreaterThan(now.getTime());
  });
});
