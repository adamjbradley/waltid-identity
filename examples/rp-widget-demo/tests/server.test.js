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

describe('POST /api/age-check/webhook/:token + GET /api/age-check/status', () => {
  // Cross-process webhook: verifier-api2 calls back with no browser cookie,
  // so we can't write to the user's req.session directly. Instead:
  //  1. /api/age-check/start seeds an entry in an in-memory ageCheckByToken
  //     map keyed by a random URL token, and also saves the same triple
  //     {sessionId, token, webhookSecret} into req.session.ageCheck so the
  //     user's browser can later look the status up via /api/age-check/status.
  //  2. The webhook hits /api/age-check/webhook/<token>, bearing an
  //     `Authorization: Bearer <secret>` header (the shape verifier-api2
  //     actually sends, per KtorSessionNotifications.kt — not the
  //     X-Webhook-Secret header sketched in the plan).
  //  3. Status poll reads the map, mirrors verified into req.session.
  let app;
  let agent;
  beforeEach(() => {
    app = createApp();
    agent = request.agent(app);
  });

  async function seedSession(token, secret) {
    await agent.post('/_test/session').send({
      ageCheck: { sessionId: 's-' + token, token, webhookSecret: secret },
    }).expect(200);
    await agent.post('/_test/age-check/register').send({
      token, webhookSecret: secret,
    }).expect(200);
  }

  it('sets ageVerified=true on SUCCESSFUL webhook with age_over_21=true', async () => {
    await seedSession('tok1', 'secret-tok1');
    const web = request(app);
    const res = await web.post('/api/age-check/webhook/tok1')
      .set('Authorization', 'Bearer secret-tok1')
      .send({
        target: 'any',
        event: 'policy_results_available',
        session: {
          id: 's-tok1',
          status: 'SUCCESSFUL',
          presentedCredentials: {
            pid_1: [{ credentialData: { age_over_21: true } }],
          },
        },
      })
      .expect(200);
    expect(res.body).toEqual({ ok: true });

    const status = await agent.get('/api/age-check/status').expect(200);
    expect(status.body).toEqual({ verified: true });

    // Downstream: cart POST now succeeds for an age-restricted product
    // without any other session hydration.
    const add = await agent.post('/api/cart/items').send({ productId: 'hibiki-harmony' }).expect(200);
    expect(add.body.count).toBe(1);
  });

  it('rejects unknown token with 404', async () => {
    const web = request(app);
    await web.post('/api/age-check/webhook/unknown')
      .set('Authorization', 'Bearer whatever')
      .send({ event: 'policy_results_available', session: { status: 'SUCCESSFUL' } })
      .expect(404);
  });

  it('rejects wrong bearer secret with 401', async () => {
    await seedSession('tok2', 'correct');
    const web = request(app);
    await web.post('/api/age-check/webhook/tok2')
      .set('Authorization', 'Bearer wrong')
      .send({ event: 'policy_results_available', session: { status: 'SUCCESSFUL' } })
      .expect(401);
  });

  it('rejects missing Authorization header with 401', async () => {
    await seedSession('tok2b', 'correct');
    const web = request(app);
    await web.post('/api/age-check/webhook/tok2b')
      .send({ event: 'policy_results_available', session: { status: 'SUCCESSFUL' } })
      .expect(401);
  });

  it('sets ageVerified=false when age_over_21 is false', async () => {
    await seedSession('tok3', 'secret-tok3');
    const web = request(app);
    await web.post('/api/age-check/webhook/tok3')
      .set('Authorization', 'Bearer secret-tok3')
      .send({
        event: 'policy_results_available',
        session: {
          id: 's-tok3',
          status: 'SUCCESSFUL',
          presentedCredentials: { pid_0: [{ credentialData: { age_over_21: false } }] },
        },
      })
      .expect(200);
    const status = await agent.get('/api/age-check/status').expect(200);
    expect(status.body).toEqual({ verified: false });
  });

  it('ignores non-final events (acks 200, verified stays null)', async () => {
    await seedSession('tok4', 'secret-tok4');
    const web = request(app);
    await web.post('/api/age-check/webhook/tok4')
      .set('Authorization', 'Bearer secret-tok4')
      .send({
        event: 'presentation_received',
        session: { id: 's-tok4', status: 'ACTIVE' },
      })
      .expect(200);
    const status = await agent.get('/api/age-check/status').expect(200);
    expect(status.body).toEqual({ verified: null });
  });

  it('returns {verified: null} when no age-check session has been started', async () => {
    const fresh = request.agent(app);
    const status = await fresh.get('/api/age-check/status').expect(200);
    expect(status.body).toEqual({ verified: null });
  });

  it('deletes the ageCheckByToken entry after successful status mirror', async () => {
    await agent.post('/_test/session').send({ ageCheck: { sessionId: 's4', token: 'tok-evict', webhookSecret: 'secret-evict' } });
    await agent.post('/_test/age-check/register').send({ token: 'tok-evict', webhookSecret: 'secret-evict' });

    // Drive the webhook to SUCCESSFUL
    await request(app).post('/api/age-check/webhook/tok-evict')
      .set('Authorization', 'Bearer secret-evict')
      .send({ event: 'policy_results_available', session: { id: 's4', status: 'SUCCESSFUL', presentedCredentials: { pid_0: [{ credentialData: { age_over_21: true } }] } } })
      .expect(200);

    // First poll mirrors + evicts
    const first = await agent.get('/api/age-check/status').expect(200);
    expect(first.body).toEqual({ verified: true });

    // Second poll returns null (entry gone), but session flag remains sticky
    const second = await agent.get('/api/age-check/status').expect(200);
    expect(second.body).toEqual({ verified: null });

    // Session still ageVerified for cart adds
    const add = await agent.post('/api/cart/items').send({ productId: 'hibiki-harmony' }).expect(200);
    expect(add.body.count).toBe(1);
  });
});

describe('POST /api/psp/start + /api/psp/webhook/:token + /api/psp/status + /api/psp/issue', () => {
  // PSP-enrollment mirrors the age-check wire shape: verifier-api2
  // session-create with DCQL over the same four PID VCTs, webhook nested
  // under core_flow.notifications.webhook, Bearer auth on callback. The
  // DCQL claims are different (given_name, family_name, birth_date instead
  // of age_over_21), and the status-poll mirrors into `pspPidVerified`
  // rather than `ageVerified`. /api/psp/issue then hits issuer-api for a
  // pre-authorized credential offer once PID is on the session.
  let app;
  let agent;
  let originalFetch;
  beforeAll(() => { originalFetch = global.fetch; });
  afterAll(() => { global.fetch = originalFetch; });
  beforeEach(() => {
    app = createApp();
    agent = request.agent(app);
    global.fetch = jest.fn(async (url) => {
      if (typeof url === 'string' && url.includes('/verification-session/create')) {
        return {
          ok: true, status: 200,
          json: async () => ({
            sessionId: 'psp-sess-1',
            bootstrapAuthorizationRequestUrl: 'openid4vp://authorize?request_uri=psp',
            fullAuthorizationRequestUrl: 'openid4vp://authorize?request_uri=psp_full',
          }),
        };
      }
      throw new Error('unexpected fetch ' + url);
    });
  });

  it('returns sessionId+qrCode and forwards a PID-identity DCQL query', async () => {
    const res = await agent.post('/api/psp/start').expect(200);
    expect(res.body).toHaveProperty('sessionId', 'psp-sess-1');
    expect(res.body.qrCode).toMatch(/^openid4vp:/);
    const [url, opts] = global.fetch.mock.calls[0];
    expect(url).toMatch(/\/verification-session\/create\?rpId=/);
    const body = JSON.parse(opts.body);
    expect(body.flow_type).toBe('cross_device');
    const serialized = JSON.stringify(body.core_flow.dcql_query);
    // PID identity claims — not age_over_21.
    expect(serialized).toContain('given_name');
    expect(serialized).toContain('family_name');
    expect(serialized).toContain('birth_date');
    expect(serialized).not.toContain('age_over_21');
    // Four PID VCTs, one credential each (PR #88 workaround).
    expect(body.core_flow.dcql_query.credentials).toHaveLength(4);
    expect(body.core_flow.dcql_query.credential_sets).toBeDefined();
    expect(body.core_flow.notifications.webhook.url).toMatch(/\/api\/psp\/webhook\//);
    expect(body.core_flow.notifications.webhook.bearer_token).toBeTruthy();
  });

  it('webhook writes claims to pspEnroll map entry on SUCCESSFUL', async () => {
    await agent.post('/_test/session').send({
      pspEnroll: { sessionId: 'psp-x', token: 'psp-tok', webhookSecret: 'psp-secret' },
    }).expect(200);
    await agent.post('/_test/psp/register').send({ token: 'psp-tok', webhookSecret: 'psp-secret' }).expect(200);

    await request(app).post('/api/psp/webhook/psp-tok')
      .set('Authorization', 'Bearer psp-secret')
      .send({
        event: 'policy_results_available',
        session: {
          status: 'SUCCESSFUL',
          presentedCredentials: {
            pid_0: [{ credentialData: {
              sub: 'user-abc',
              given_name: 'Alice',
              family_name: 'Example',
              birth_date: '1990-01-01',
            } }],
          },
        },
      })
      .expect(200);

    const statusRes = await agent.get('/api/psp/status').expect(200);
    expect(statusRes.body.verified).toBe(true);
    expect(statusRes.body.claims).toMatchObject({
      sub: 'user-abc',
      given_name: 'Alice',
      family_name: 'Example',
      birth_date: '1990-01-01',
    });
  });

  it('status mirrors claims into req.session.pspPidVerified', async () => {
    await agent.post('/_test/session').send({
      pspEnroll: { sessionId: 'psp-y', token: 'psp-tok2', webhookSecret: 'sec2' },
    }).expect(200);
    await agent.post('/_test/psp/register').send({ token: 'psp-tok2', webhookSecret: 'sec2' }).expect(200);

    await request(app).post('/api/psp/webhook/psp-tok2')
      .set('Authorization', 'Bearer sec2')
      .send({
        event: 'policy_results_available',
        session: {
          status: 'SUCCESSFUL',
          presentedCredentials: { pid_0: [{ credentialData: { sub: 's1', given_name: 'B' } }] },
        },
      })
      .expect(200);

    await agent.get('/api/psp/status').expect(200);
    // Now /api/psp/issue should work because pspPidVerified is on session.
    global.fetch = jest.fn(async (url) => {
      if (typeof url === 'string' && url.includes('/openid4vc/sdjwt/issue')) {
        return {
          ok: true, status: 200,
          text: async () => 'openid-credential-offer://?credential_offer_uri=http://issuer/abc',
        };
      }
      throw new Error('unexpected fetch ' + url);
    });
    const res = await agent.post('/api/psp/issue').expect(200);
    expect(res.body.offerUri).toMatch(/^openid-credential-offer:/);
    expect(res.body.scheme).toBe('Visa');
    expect(res.body.panLastFour).toMatch(/^[0-9a-f]{4}$/);
  });

  it('/api/psp/issue 403s when PID not yet verified', async () => {
    // No pspPidVerified on the session.
    const res = await agent.post('/api/psp/issue').expect(403);
    expect(res.body).toEqual({ error: 'pid_required' });
  });

  it('/api/psp/issue 502s when issuer-api rejects the offer', async () => {
    await agent.post('/_test/session').send({
      pspPidVerified: { sub: 's-err', given_name: 'X', family_name: 'Y', birth_date: '2000-01-01' },
    }).expect(200);
    global.fetch = jest.fn(async () => ({ ok: false, status: 500, text: async () => 'boom' }));
    const res = await agent.post('/api/psp/issue').expect(502);
    expect(res.body).toMatchObject({ error: 'issuer_unavailable' });
  });

  it('GET /psp/enroll serves the static page', async () => {
    const res = await request(app).get('/psp/enroll').expect(200);
    expect(res.text).toContain('Add payment method');
    expect(res.text).toContain('Bank of Demo');
  });
});

describe('POST /api/pwa/capture + /api/pwa/capture/webhook + /api/pwa/capture-status', () => {
  // PWA capture (/cart?pwa=1 return flow): single-VCT DCQL for
  // PaymentWalletAttestation asking for [panLastFour, scheme, payeeName].
  // Success hydrates req.session.user.paymentMethod AND persists via
  // userStore.upsert (when the session user has a sub).
  const path = require('path');
  const os = require('os');
  const fs = require('fs');
  let app;
  let agent;
  let originalFetch;
  const tmpFiles = [];
  beforeAll(() => { originalFetch = global.fetch; });
  afterAll(() => {
    global.fetch = originalFetch;
    // Clean up any userStore files this suite created.
    tmpFiles.forEach((f) => { try { fs.unlinkSync(f); } catch (_) { /* ignore */ } });
  });
  beforeEach(() => {
    // Use a per-test userStore file (in the OS temp dir so nothing lands
    // in the repo) so upsert persistence is inspectable without leaking
    // state across tests.
    const tmpFile = path.join(os.tmpdir(), 'rp-pwa-test-' + Date.now() + '-' + Math.random().toString(36).slice(2) + '.json');
    tmpFiles.push(tmpFile);
    process.env.USER_STORE_FILE = tmpFile;
    app = createApp();
    agent = request.agent(app);
    global.fetch = jest.fn(async (url) => {
      if (typeof url === 'string' && url.includes('/verification-session/create')) {
        return {
          ok: true, status: 200,
          json: async () => ({
            sessionId: 'pwa-sess-1',
            bootstrapAuthorizationRequestUrl: 'openid4vp://authorize?request_uri=pwa',
            fullAuthorizationRequestUrl: 'openid4vp://authorize?request_uri=pwa_full',
          }),
        };
      }
      throw new Error('unexpected fetch ' + url);
    });
  });

  it('POST /api/pwa/capture returns sessionId+qrCode and forwards PWA-capture DCQL', async () => {
    const res = await agent.post('/api/pwa/capture').expect(200);
    expect(res.body).toHaveProperty('sessionId', 'pwa-sess-1');
    expect(res.body.qrCode).toMatch(/^openid4vp:/);
    const [, opts] = global.fetch.mock.calls[0];
    const body = JSON.parse(opts.body);
    const serialized = JSON.stringify(body.core_flow.dcql_query);
    expect(serialized).toContain('PaymentWalletAttestation');
    expect(serialized).toContain('panLastFour');
    expect(serialized).toContain('scheme');
    expect(serialized).toContain('payeeName');
    // Single VCT — single credential in DCQL.
    expect(body.core_flow.dcql_query.credentials).toHaveLength(1);
    expect(body.core_flow.notifications.webhook.url).toMatch(/\/api\/pwa\/capture\/webhook\//);
  });

  it('webhook writes claims to the capture map', async () => {
    await agent.post('/_test/session').send({
      pwaCapture: { sessionId: 'p-1', token: 'pwa-tok', webhookSecret: 'pwa-sec' },
    }).expect(200);
    await agent.post('/_test/pwa-capture/register').send({ token: 'pwa-tok', webhookSecret: 'pwa-sec' }).expect(200);

    await request(app).post('/api/pwa/capture/webhook/pwa-tok')
      .set('Authorization', 'Bearer pwa-sec')
      .send({
        event: 'policy_results_available',
        session: {
          status: 'SUCCESSFUL',
          presentedCredentials: {
            pwa_0: [{ credentialData: { panLastFour: '4242', scheme: 'Visa', payeeName: 'Bank of Demo' } }],
          },
        },
      })
      .expect(200);

    const statusRes = await agent.get('/api/pwa/capture-status').expect(200);
    expect(statusRes.body.verified).toBe(true);
    expect(statusRes.body.paymentMethod).toMatchObject({
      panLastFour: '4242', scheme: 'Visa', payeeName: 'Bank of Demo',
    });
    expect(statusRes.body.paymentMethod.addedAt).toBeGreaterThan(0);
  });

  it('hydrates req.session.user.paymentMethod and persists via userStore when user.sub present', async () => {
    // Seed a session user with a sub first (authop-style profile).
    await agent.post('/_test/session').send({
      user: { sub: 'sub-capture-1', provider: 'authop', age_over_21: true, kyc_verified: true },
      provider: 'authop',
      pwaCapture: { sessionId: 'p-2', token: 'pwa-tok-2', webhookSecret: 'secret-2' },
    }).expect(200);
    await agent.post('/_test/pwa-capture/register').send({ token: 'pwa-tok-2', webhookSecret: 'secret-2' }).expect(200);

    await request(app).post('/api/pwa/capture/webhook/pwa-tok-2')
      .set('Authorization', 'Bearer secret-2')
      .send({
        event: 'policy_results_available',
        session: {
          status: 'SUCCESSFUL',
          presentedCredentials: { pwa_0: [{ credentialData: { panLastFour: '1234', scheme: 'Visa', payeeName: 'Bank of Demo' } }] },
        },
      })
      .expect(200);

    await agent.get('/api/pwa/capture-status').expect(200);

    // Verify session user now carries paymentMethod via /api/me readback.
    const me = await agent.get('/api/me').expect(200);
    expect(me.body.user).toBeTruthy();
    expect(me.body.user.paymentMethod).toMatchObject({ panLastFour: '1234', scheme: 'Visa', payeeName: 'Bank of Demo' });

    // Verify userStore persisted. Construct a fresh UserStore pointed at
    // the same file so we're reading from disk, not an in-memory copy.
    const { UserStore } = require('../userStore');
    const store = new UserStore(process.env.USER_STORE_FILE);
    const saved = store.get('sub-capture-1');
    expect(saved).toBeTruthy();
    expect(saved.paymentMethod).toMatchObject({ panLastFour: '1234', scheme: 'Visa' });
  });

  it('capture-status ignores non-final events (returns null)', async () => {
    await agent.post('/_test/session').send({
      pwaCapture: { sessionId: 'p-3', token: 'pwa-tok-3', webhookSecret: 'sec3' },
    }).expect(200);
    await agent.post('/_test/pwa-capture/register').send({ token: 'pwa-tok-3', webhookSecret: 'sec3' }).expect(200);
    await request(app).post('/api/pwa/capture/webhook/pwa-tok-3')
      .set('Authorization', 'Bearer sec3')
      .send({ event: 'presentation_received', session: { status: 'ACTIVE' } })
      .expect(200);
    const statusRes = await agent.get('/api/pwa/capture-status').expect(200);
    expect(statusRes.body.verified).toBeNull();
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
