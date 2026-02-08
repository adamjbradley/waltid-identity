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

    const expiryDate = new Date(response.body.expiresAt);
    const now = new Date();
    expect(expiryDate.getTime()).toBeGreaterThan(now.getTime());
  });
});
