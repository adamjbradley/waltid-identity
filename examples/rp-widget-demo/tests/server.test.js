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

    it('exposes publicPspUrl so the client can redirect to the external PSP', async () => {
      const response = await request(app).get('/api/config').expect(200);
      expect(typeof response.body.publicPspUrl).toBe('string');
      expect(response.body.publicPspUrl.length).toBeGreaterThan(0);
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

// The PSP-enrollment test block (POST /api/psp/start + /api/psp/webhook
// + /api/psp/status + /api/psp/issue, plus GET /psp/enroll) used to live
// here. Those routes moved to examples/mock-psp-demo; their tests moved
// with them (see examples/mock-psp-demo/tests/server.test.js).

// NOTE: the POST /api/pwa/capture + webhook + status test block used to
// live here. The capture flow was removed (it wasn't standards-compliant
// and the client-side banner never rendered a QR, so no wallet could
// actually present) — there are no replacement routes to test. Card
// metadata is discovered at checkout via OID4VP presentation and the
// checkout webhook tests further down cover that path.

describe('GET /checkout + GET /api/me', () => {
  // Task 16 — the /checkout review page gates on cart non-emptiness and the
  // new /api/me response shape combines {user, cart} so the page's on-load
  // fetch only needs one round-trip. /api/me pre-existed (provider list);
  // we additively return a cart summary so the checkout page can render
  // items + total + payment-method line without a second call.
  let app;
  let agent;
  beforeEach(() => {
    app = createApp();
    agent = request.agent(app);
  });

  it('GET /checkout with empty cart redirects to /', async () => {
    const res = await agent.get('/checkout').expect(302);
    expect(res.headers.location).toBe('/');
  });

  it('GET /checkout with items returns 200 HTML', async () => {
    await agent.post('/_test/session').send({ ageVerified: true }).expect(200);
    await agent.post('/api/cart/items').send({ productId: 'hibiki-harmony' }).expect(200);
    const res = await agent.get('/checkout').expect(200);
    expect(res.headers['content-type']).toMatch(/html/);
    expect(res.text).toContain('<!DOCTYPE html>');
    expect(res.text.toLowerCase()).toContain('checkout');
  });

  it('GET /api/me returns user + cart summary', async () => {
    // Seed a user and an item.
    await agent.post('/_test/session').send({
      user: { sub: 'u1', provider: 'authop', age_over_21: true },
      ageVerified: true,
    }).expect(200);
    await agent.post('/api/cart/items').send({ productId: 'hibiki-harmony' }).expect(200);

    const res = await agent.get('/api/me').expect(200);
    expect(res.body.user).toMatchObject({ sub: 'u1' });
    expect(res.body.cart).toMatchObject({ count: 1, subtotal: expect.any(Number) });
    expect(res.body.cart.items).toHaveLength(1);
    expect(res.body.cart.items[0]).toMatchObject({ productId: 'hibiki-harmony', qty: 1 });
  });

  it('GET /api/me returns empty cart + null user for fresh session', async () => {
    const fresh = request.agent(app);
    const res = await fresh.get('/api/me').expect(200);
    expect(res.body.user).toBeNull();
    expect(res.body.cart).toEqual({ items: [], subtotal: 0, count: 0 });
  });
});

describe('POST /api/checkout', () => {
  // Task 17 — kicks a PWA presentation session on verifier-api2 with the
  // orderId bound as `core_flow.state` (see Task 0 Plan B fallback; a true
  // RFC008 transaction_data_hashes kb-JWT binding is pending verifier-api2
  // support). Tests assert:
  //  - 400 empty_cart when the session has no cart items.
  //  - Happy path returns {orderId, sessionId, qrCode} and the upstream
  //    DCQL asks for PaymentWalletAttestation with panLastFour + scheme.
  //  - The webhookUrl embeds a per-order token and the bearer is minted.
  //
  // There is no pre-check for an on-session payment method — the merchant
  // doesn't know which PaymentWalletAttestation the wallet holds until it
  // presents. If none is held, the wallet rejects and the status poll
  // surfaces the decline.
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
            sessionId: 'ck-sess-1',
            bootstrapAuthorizationRequestUrl: 'openid4vp://authorize?request_uri=ck',
            fullAuthorizationRequestUrl: 'openid4vp://authorize?request_uri=ck_full',
          }),
        };
      }
      throw new Error('unexpected fetch ' + url);
    });
  });

  async function seedCartAndPayment() {
    await agent.post('/_test/session').send({
      user: { sub: 'sub-ck-1', provider: 'authop', age_over_21: true },
      ageVerified: true,
    }).expect(200);
    await agent.post('/api/cart/items').send({ productId: 'hibiki-harmony' }).expect(200);
  }

  it('400 empty_cart when session cart is empty', async () => {
    await agent.post('/_test/session').send({
      user: { sub: 'u-empty' },
    }).expect(200);
    const res = await agent.post('/api/checkout').expect(400);
    expect(res.body).toEqual({ error: 'empty_cart' });
    expect(global.fetch).not.toHaveBeenCalled();
  });

  it('happy path returns {orderId, sessionId, qrCode} and forwards PWA DCQL', async () => {
    await seedCartAndPayment();
    const res = await agent.post('/api/checkout').expect(200);
    expect(res.body.orderId).toMatch(/^ORDER-/);
    expect(res.body.sessionId).toBe('ck-sess-1');
    expect(res.body.qrCode).toMatch(/^openid4vp:/);

    expect(global.fetch).toHaveBeenCalledTimes(1);
    const [url, opts] = global.fetch.mock.calls[0];
    expect(url).toMatch(/\/verification-session\/create\?rpId=/);
    const body = JSON.parse(opts.body);
    expect(body.flow_type).toBe('cross_device');
    const serialized = JSON.stringify(body.core_flow.dcql_query);
    expect(serialized).toContain('PaymentWalletAttestation');
    expect(serialized).toContain('panLastFour');
    expect(serialized).toContain('scheme');
    expect(body.core_flow.dcql_query.credentials).toHaveLength(1);
    // Webhook binds back into the per-order token map.
    expect(body.core_flow.notifications.webhook.url).toMatch(/\/api\/checkout\/webhook\//);
    expect(body.core_flow.notifications.webhook.bearer_token).toBeTruthy();
  });

  it('502 when verifier-api2 rejects session-create', async () => {
    await seedCartAndPayment();
    global.fetch = jest.fn(async () => ({ ok: false, status: 500, text: async () => 'boom' }));
    const res = await agent.post('/api/checkout').expect(502);
    expect(res.body).toMatchObject({ error: 'verifier_unavailable' });
  });
});

describe('POST /api/checkout/webhook/:token + GET /api/checkout/status', () => {
  // Task 18 — webhook records an order, status poll mirrors it onto the
  // session + userStore and clears the cart. Same Bearer + constant-time
  // compare pattern as the other OID4VP webhooks. `entry.completed` +
  // `entry.order` are written by the webhook; the status poll evicts the
  // map entry once it has mirrored to req.session.orders.
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
    tmpFiles.forEach((f) => { try { fs.unlinkSync(f); } catch (_) { /* ignore */ } });
  });
  beforeEach(() => {
    const tmpFile = path.join(os.tmpdir(), 'rp-ck-test-' + Date.now() + '-' + Math.random().toString(36).slice(2) + '.json');
    tmpFiles.push(tmpFile);
    process.env.USER_STORE_FILE = tmpFile;
    app = createApp();
    agent = request.agent(app);
    // Stub verifier-api2 session-create so happy-path kickoffs succeed
    // deterministically (matches the Task 17 suite pattern).
    global.fetch = jest.fn(async (url) => {
      if (typeof url === 'string' && url.includes('/verification-session/create')) {
        return {
          ok: true, status: 200,
          json: async () => ({
            sessionId: 'ck-sess-1',
            bootstrapAuthorizationRequestUrl: 'openid4vp://authorize?request_uri=ck',
            fullAuthorizationRequestUrl: 'openid4vp://authorize?request_uri=ck_full',
          }),
        };
      }
      throw new Error('unexpected fetch ' + url);
    });
  });

  async function seedAndKickCheckout() {
    await agent.post('/_test/session').send({
      user: { sub: 'sub-ck-flow', provider: 'authop', age_over_21: true },
      provider: 'authop',
      ageVerified: true,
    }).expect(200);
    await agent.post('/api/cart/items').send({ productId: 'hibiki-harmony' }).expect(200);
    const res = await agent.post('/api/checkout').expect(200);
    return res.body;
  }

  function successfulWebhookBody() {
    return {
      event: 'policy_results_available',
      session: {
        status: 'SUCCESSFUL',
        presentedCredentials: {
          pwa_0: [{ credentialData: { panLastFour: '4242', scheme: 'Visa' } }],
        },
      },
    };
  }

  it('rejects webhook with no Authorization header (401)', async () => {
    const { orderId } = await seedAndKickCheckout();
    // Grab the token by inspecting the upstream webhook URL we forwarded.
    const [, opts] = global.fetch.mock.calls[0];
    const body = JSON.parse(opts.body);
    const webhookUrl = body.core_flow.notifications.webhook.url;
    const token = webhookUrl.split('/').pop();
    await request(app).post('/api/checkout/webhook/' + token)
      .send(successfulWebhookBody())
      .expect(401);
  });

  it('rejects webhook with wrong bearer (401)', async () => {
    await seedAndKickCheckout();
    const [, opts] = global.fetch.mock.calls[0];
    const token = JSON.parse(opts.body).core_flow.notifications.webhook.url.split('/').pop();
    await request(app).post('/api/checkout/webhook/' + token)
      .set('Authorization', 'Bearer wrong')
      .send(successfulWebhookBody())
      .expect(401);
  });

  it('rejects unknown token with 404', async () => {
    await request(app).post('/api/checkout/webhook/unknown-token')
      .set('Authorization', 'Bearer whatever')
      .send(successfulWebhookBody())
      .expect(404);
  });

  it('full flow: webhook -> status poll clears cart, records order on session + userStore', async () => {
    const { orderId } = await seedAndKickCheckout();
    const [, opts] = global.fetch.mock.calls[0];
    const reqBody = JSON.parse(opts.body);
    const webhookUrl = reqBody.core_flow.notifications.webhook.url;
    const token = webhookUrl.split('/').pop();
    const bearer = reqBody.core_flow.notifications.webhook.bearer_token;

    // Status poll before webhook: pending.
    const pendingRes = await agent.get('/api/checkout/status').expect(200);
    expect(pendingRes.body).toEqual({ status: 'pending' });

    // Fire the webhook.
    await request(app).post('/api/checkout/webhook/' + token)
      .set('Authorization', 'Bearer ' + bearer)
      .send(successfulWebhookBody())
      .expect(200);

    // Status poll after webhook: completed; mirrors order + clears cart.
    const completedRes = await agent.get('/api/checkout/status').expect(200);
    expect(completedRes.body).toEqual({ status: 'completed', orderId });

    // Cart is cleared.
    const cart = await agent.get('/api/cart').expect(200);
    expect(cart.body).toEqual({ items: [], subtotal: 0, count: 0 });

    // Order lives on session.orders[0].
    const me = await agent.get('/api/me').expect(200);
    // pendingOrder was cleared.
    // Session orders present via /api/me has no dedicated field; inspect via
    // admin /api/users on the store which was persisted for authop sub.
    const users = await agent.get('/api/users').expect(200);
    const saved = users.body.users.find((u) => u.sub === 'sub-ck-flow');
    expect(saved).toBeTruthy();
    expect(Array.isArray(saved.orders)).toBe(true);
    expect(saved.orders).toHaveLength(1);
    expect(saved.orders[0]).toMatchObject({
      id: orderId,
      currency: 'AUD',
      transactionRef: orderId,
      pwaMeta: { panLastFour: '4242', scheme: 'Visa' },
    });
    expect(saved.orders[0].total).toBeGreaterThan(0);
    expect(saved.orders[0].items).toHaveLength(1);

    // Second status poll: no pending order, returns a sensible default.
    const repeat = await agent.get('/api/checkout/status').expect(200);
    expect(repeat.body.status === 'none' || repeat.body.status === 'completed').toBe(true);
  });

  it('status poll with no pending order returns {status:none}', async () => {
    const fresh = request.agent(app);
    const res = await fresh.get('/api/checkout/status').expect(200);
    expect(res.body).toEqual({ status: 'none' });
  });

  it('status is pending between kickoff and webhook', async () => {
    await seedAndKickCheckout();
    const res = await agent.get('/api/checkout/status').expect(200);
    expect(res.body).toEqual({ status: 'pending' });
  });

  it('non-final webhook event leaves status pending', async () => {
    await seedAndKickCheckout();
    const [, opts] = global.fetch.mock.calls[0];
    const reqBody = JSON.parse(opts.body);
    const token = reqBody.core_flow.notifications.webhook.url.split('/').pop();
    const bearer = reqBody.core_flow.notifications.webhook.bearer_token;
    await request(app).post('/api/checkout/webhook/' + token)
      .set('Authorization', 'Bearer ' + bearer)
      .send({ event: 'presentation_received', session: { status: 'ACTIVE' } })
      .expect(200);
    const res = await agent.get('/api/checkout/status').expect(200);
    expect(res.body).toEqual({ status: 'pending' });
  });
});

describe('GET /api/orders/:id + GET /order/:id (Task 20)', () => {
  // /api/orders/:id is the receipt JSON endpoint — reads req.session.orders
  // first, then falls back to the user's persisted orders in userStore.
  // /order/:id always serves the static receipt page (client fetches the
  // JSON after render); the :id is never validated server-side so a bookmark
  // or share link never 404s on the page shell.
  //
  // userStore is loaded at createApp() time, so any test that needs the
  // store populated *from outside* the app has to seed the file before
  // constructing the app. Each test picks its own tmpFile for isolation.
  const path = require('path');
  const os = require('os');
  const fs = require('fs');
  const { UserStore } = require('../userStore');
  const tmpFiles = [];
  afterAll(() => {
    tmpFiles.forEach((f) => { try { fs.unlinkSync(f); } catch (_) { /* ignore */ } });
  });

  function freshAppWithStoreSeeding(seedFn) {
    const tmpFile = path.join(os.tmpdir(), 'rp-order-test-' + Date.now() + '-' + Math.random().toString(36).slice(2) + '.json');
    tmpFiles.push(tmpFile);
    process.env.USER_STORE_FILE = tmpFile;
    if (typeof seedFn === 'function') {
      const store = new UserStore(tmpFile);
      seedFn(store);
    }
    const app = createApp();
    const agent = request.agent(app);
    return { app, agent };
  }

  const seededOrder = (id = 'ORDER-abc123') => ({
    id,
    items: [{ productId: 'hibiki-harmony', qty: 1, priceAud: 120, title: 'Hibiki Harmony', imageUrl: '\u{1F943}', ageRestricted: true }],
    total: 120,
    currency: 'AUD',
    pwaMeta: { panLastFour: '4242', scheme: 'Visa' },
    transactionRef: id,
    approvedAt: Date.UTC(2026, 3, 21, 14, 32, 0),
    vpDigest: 'deadbeef'.repeat(8),
  });

  it('GET /api/orders/<unknown> returns 404', async () => {
    const { agent } = freshAppWithStoreSeeding();
    const res = await agent.get('/api/orders/ORDER-nope').expect(404);
    expect(res.body).toMatchObject({ error: 'not_found' });
  });

  it('GET /api/orders/<id> reads from session orders', async () => {
    const { agent } = freshAppWithStoreSeeding();
    const order = seededOrder('ORDER-session-1');
    await agent.post('/_test/session').send({ orders: [order] }).expect(200);
    const res = await agent.get('/api/orders/ORDER-session-1').expect(200);
    expect(res.body).toMatchObject({
      id: 'ORDER-session-1',
      total: 120,
      currency: 'AUD',
      pwaMeta: { panLastFour: '4242', scheme: 'Visa' },
      transactionRef: 'ORDER-session-1',
    });
    expect(res.body.items).toHaveLength(1);
  });

  it('GET /api/orders/<id> falls back to userStore for logged-in user', async () => {
    const order = seededOrder('ORDER-store-1');
    const { agent } = freshAppWithStoreSeeding((store) => {
      store.upsert({ sub: 'user-store-sub', provider: 'authop', orders: [order] });
    });
    // Seed session so the server identifies the user.
    await agent.post('/_test/session').send({
      user: { sub: 'user-store-sub', provider: 'authop' },
    }).expect(200);
    const res = await agent.get('/api/orders/ORDER-store-1').expect(200);
    expect(res.body).toMatchObject({
      id: 'ORDER-store-1',
      total: 120,
      currency: 'AUD',
      pwaMeta: { panLastFour: '4242', scheme: 'Visa' },
    });
  });

  it('GET /order/:id serves the HTML shell regardless of id validity', async () => {
    const { agent } = freshAppWithStoreSeeding();
    const res = await agent.get('/order/anyString').expect(200);
    expect(res.headers['content-type']).toMatch(/html/);
    expect(res.text).toContain('<!DOCTYPE html>');
    expect(res.text.toLowerCase()).toContain('order');
  });
});

describe('GET /api/me lastOrder (Task 21)', () => {
  // The profile hover shows a single-line "Last order: <id> — <currency>
  // <total>" entry pulled from /api/me. Only {id, currency, total} are
  // surfaced — item-level detail would both bloat the /api/me payload
  // and leak purchase history into any code path that just wanted
  // user + cart for a header render.
  const path = require('path');
  const os = require('os');
  const fs = require('fs');
  const { UserStore } = require('../userStore');
  const tmpFiles = [];
  afterAll(() => {
    tmpFiles.forEach((f) => { try { fs.unlinkSync(f); } catch (_) { /* ignore */ } });
  });

  function freshAppWithStoreSeeding(seedFn) {
    const tmpFile = path.join(os.tmpdir(), 'rp-me-last-test-' + Date.now() + '-' + Math.random().toString(36).slice(2) + '.json');
    tmpFiles.push(tmpFile);
    process.env.USER_STORE_FILE = tmpFile;
    if (typeof seedFn === 'function') {
      const store = new UserStore(tmpFile);
      seedFn(store);
    }
    const app = createApp();
    const agent = request.agent(app);
    return { app, agent };
  }

  it('lastOrder is null when session has no orders', async () => {
    const { agent } = freshAppWithStoreSeeding();
    const res = await agent.get('/api/me').expect(200);
    expect(res.body.lastOrder).toBeNull();
  });

  it('lastOrder reflects most recent session order (id, currency, total only)', async () => {
    const { agent } = freshAppWithStoreSeeding();
    const orders = [
      { id: 'ORDER-a', items: [], total: 10, currency: 'AUD', pwaMeta: { panLastFour: '0000', scheme: 'Visa' }, transactionRef: 'ORDER-a', approvedAt: 1, vpDigest: 'x' },
      { id: 'ORDER-b', items: [{ productId: 'x', qty: 1, priceAud: 50, title: 'x', imageUrl: 'x', ageRestricted: false }], total: 50, currency: 'AUD', pwaMeta: { panLastFour: '4242', scheme: 'Visa' }, transactionRef: 'ORDER-b', approvedAt: 2, vpDigest: 'y' },
    ];
    await agent.post('/_test/session').send({ orders }).expect(200);
    const res = await agent.get('/api/me').expect(200);
    expect(res.body.lastOrder).toEqual({ id: 'ORDER-b', currency: 'AUD', total: 50 });
    // Items deliberately omitted for privacy + payload size.
    expect(res.body.lastOrder.items).toBeUndefined();
  });

  it('lastOrder reads from userStore when session has none but user is logged in', async () => {
    const order = { id: 'ORDER-persisted', items: [], total: 77, currency: 'AUD', pwaMeta: { panLastFour: '4242', scheme: 'Visa' }, transactionRef: 'ORDER-persisted', approvedAt: 1, vpDigest: 'z' };
    const { agent } = freshAppWithStoreSeeding((store) => {
      store.upsert({ sub: 'me-lastorder-sub', provider: 'authop', orders: [order] });
    });
    await agent.post('/_test/session').send({ user: { sub: 'me-lastorder-sub', provider: 'authop' } }).expect(200);
    const res = await agent.get('/api/me').expect(200);
    expect(res.body.lastOrder).toEqual({ id: 'ORDER-persisted', currency: 'AUD', total: 77 });
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
