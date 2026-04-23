/**
 * Tests for the Mock PSP (Bank of Demo) service.
 *
 * Mirrors the structure of examples/rp-widget-demo/tests/server.test.js —
 * the `global.fetch = jest.fn(...)` mock shape is copied deliberately so a
 * reader jumping between the two suites finds the same patterns.
 */

const request = require('supertest');

// NODE_ENV must be non-production for the _test/* helper routes to
// register. The rp-widget-demo suite does the same.
process.env.NODE_ENV = process.env.NODE_ENV === 'production' ? 'test' : (process.env.NODE_ENV || 'test');

const { createApp, config } = require('../server');

describe('Mock PSP — basics', () => {
  let app;
  beforeAll(() => { app = createApp(); });

  describe('GET /health', () => {
    it('returns status ok', async () => {
      const res = await request(app).get('/health').expect('Content-Type', /json/).expect(200);
      expect(res.body).toEqual({ status: 'ok' });
    });
  });

  describe('config sanity', () => {
    it('exposes the expected defaults', () => {
      expect(config.ISSUER_API_URL).toMatch(/issuer-api|localhost/);
      expect(config.VERIFIER_API2_URL).toMatch(/verifier-api2|localhost/);
      expect(config.PSP_VCT).toBe('PaymentWalletAttestation');
      expect(Array.isArray(config.RP_RETURN_URL_ALLOWLIST)).toBe(true);
      // Allowlist defaults include rp.theaustraliahack.com — keeps the
      // demo functional without explicit env wiring.
      expect(config.RP_RETURN_URL_ALLOWLIST).toContain('rp.theaustraliahack.com');
    });
  });
});

describe('GET /enroll', () => {
  let app;
  beforeAll(() => { app = createApp(); });

  it('serves the HTML enrol page', async () => {
    const res = await request(app).get('/enroll').expect(200);
    expect(res.text).toContain('Bank of Demo');
    expect(res.text).toContain('Add payment method');
  });

  it('stores an allowlisted ?return= URL on the session', async () => {
    const agent = request.agent(app);
    await agent.get('/enroll?return=https://rp.theaustraliahack.com/cart').expect(200);
    const r = await agent.get('/api/psp/return-url').expect(200);
    expect(r.body.returnUrl).toBe('https://rp.theaustraliahack.com/cart');
  });

  it('silently ignores off-allowlist ?return= URLs and falls back to the default', async () => {
    const agent = request.agent(app);
    // /enroll itself stays 200 — we don't want to scare the user with a
    // 403. The bad candidate is just dropped.
    await agent.get('/enroll?return=https://evil.example.com/phish').expect(200);
    const r = await agent.get('/api/psp/return-url').expect(200);
    expect(r.body.returnUrl).not.toBe('https://evil.example.com/phish');
    // Default should be derived from the first allowlist entry.
    expect(r.body.returnUrl).toMatch(/rp\.theaustraliahack\.com/);
  });

  it('handles a malformed ?return= URL as off-allowlist (fallback to default)', async () => {
    const agent = request.agent(app);
    await agent.get('/enroll?return=not-a-url').expect(200);
    const r = await agent.get('/api/psp/return-url').expect(200);
    expect(r.body.returnUrl).toMatch(/rp\.theaustraliahack\.com/);
  });
});

describe('GET /api/psp/return-url (no prior /enroll)', () => {
  let app;
  beforeAll(() => { app = createApp(); });
  it('returns the default return URL when nothing is stored', async () => {
    const res = await request(app).get('/api/psp/return-url').expect(200);
    expect(typeof res.body.returnUrl).toBe('string');
    expect(res.body.returnUrl.length).toBeGreaterThan(0);
  });
});

describe('POST /api/psp/start + /api/psp/webhook/:token + /api/psp/status + /api/psp/issue', () => {
  // Mirrors the PID-presentation + issuance flow the rp-widget-demo used
  // to own. Happy-path walks from kick-off through webhook to status mirror
  // to issuance; negative paths cover bearer auth + PID-required + issuer
  // 5xx bubbling.
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

  it('POST /api/psp/start returns sessionId+qrCode and forwards a PID-identity DCQL', async () => {
    const res = await agent.post('/api/psp/start').expect(200);
    expect(res.body).toHaveProperty('sessionId', 'psp-sess-1');
    expect(res.body.qrCode).toMatch(/^openid4vp:/);

    const [url, opts] = global.fetch.mock.calls[0];
    expect(url).toMatch(/\/verification-session\/create\?rpId=/);
    const body = JSON.parse(opts.body);
    expect(body.flow_type).toBe('cross_device');
    const serialized = JSON.stringify(body.core_flow.dcql_query);
    expect(serialized).toContain('given_name');
    expect(serialized).toContain('family_name');
    expect(serialized).toContain('birth_date');
    expect(serialized).not.toContain('age_over_21');
    // Four PID VCTs, one credential each (PR #88 workaround).
    expect(body.core_flow.dcql_query.credentials).toHaveLength(4);
    expect(body.core_flow.notifications.webhook.url).toMatch(/\/api\/psp\/webhook\//);
    expect(body.core_flow.notifications.webhook.bearer_token).toBeTruthy();
    expect(body.core_flow.signed_request).toBe(true);
  });

  it('POST /api/psp/start returns 502 when verifier-api2 is unreachable', async () => {
    global.fetch = jest.fn(async () => { throw new Error('ECONNREFUSED'); });
    const res = await agent.post('/api/psp/start').expect(502);
    expect(res.body).toEqual({ error: 'verifier_unavailable' });
  });

  it('webhook happy path writes pidClaims on SUCCESSFUL', async () => {
    await agent.post('/_test/session').send({
      pspEnroll: { sessionId: 'psp-x', token: 'psp-tok', webhookSecret: 'psp-secret' },
    }).expect(200);
    await agent.post('/_test/psp-enroll/register').send({
      token: 'psp-tok', webhookSecret: 'psp-secret',
    }).expect(200);

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

  it('webhook rejects missing bearer with 401', async () => {
    await agent.post('/_test/psp-enroll/register').send({
      token: 'tok-missing-bearer', webhookSecret: 'sec',
    }).expect(200);
    const r = await request(app).post('/api/psp/webhook/tok-missing-bearer').send({}).expect(401);
    expect(r.body).toEqual({ error: 'bad_secret' });
  });

  it('webhook rejects wrong bearer with 401 (constant-time compare)', async () => {
    await agent.post('/_test/psp-enroll/register').send({
      token: 'tok-wrong-bearer', webhookSecret: 'real-secret',
    }).expect(200);
    const r = await request(app).post('/api/psp/webhook/tok-wrong-bearer')
      .set('Authorization', 'Bearer wrong-secret')
      .send({})
      .expect(401);
    expect(r.body).toEqual({ error: 'bad_secret' });
  });

  it('webhook 404s on unknown token', async () => {
    const r = await request(app).post('/api/psp/webhook/no-such-token')
      .set('Authorization', 'Bearer whatever')
      .send({})
      .expect(404);
    expect(r.body).toEqual({ error: 'unknown_token' });
  });

  it('webhook writes verified=false on a non-SUCCESSFUL terminal session', async () => {
    await agent.post('/_test/session').send({
      pspEnroll: { sessionId: 'psp-fail', token: 'tok-fail', webhookSecret: 'sec-fail' },
    }).expect(200);
    await agent.post('/_test/psp-enroll/register').send({
      token: 'tok-fail', webhookSecret: 'sec-fail',
    }).expect(200);

    await request(app).post('/api/psp/webhook/tok-fail')
      .set('Authorization', 'Bearer sec-fail')
      .send({ event: 'policy_results_available', session: { status: 'DECLINED' } })
      .expect(200);
    const r = await agent.get('/api/psp/status').expect(200);
    expect(r.body.verified).toBe(false);
  });

  it('status mirrors claims into req.session.pspPidVerified and /api/psp/issue succeeds', async () => {
    await agent.post('/_test/session').send({
      pspEnroll: { sessionId: 'psp-y', token: 'psp-tok2', webhookSecret: 'sec2' },
    }).expect(200);
    await agent.post('/_test/psp-enroll/register').send({
      token: 'psp-tok2', webhookSecret: 'sec2',
    }).expect(200);

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

    // First poll mirrors the verdict into the session — after this the
    // /api/psp/issue route should be unlocked.
    await agent.get('/api/psp/status').expect(200);

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
    // payeeName was dropped — RFC007 says PSP identity comes from `iss` + issuer metadata.

    // Verify the credential payload sent to issuer-api follows RFC007 §8:
    // card metadata nested under fundingSource with required `type` field, plus
    // top-level `sub` (PSU-ID analogue) and `jti`. Identity fields (given_name,
    // family_name) and PSP display name (payeeName) are NOT in the PWA.
    const issueCall = global.fetch.mock.calls.find(([u]) => String(u).includes('/openid4vc/sdjwt/issue'));
    expect(issueCall).toBeTruthy();
    const sentBody = JSON.parse(issueCall[1].body);
    expect(sentBody.credentialData).toEqual(expect.objectContaining({
      sub: expect.stringMatching(/^psu_[0-9a-f]{24}$/),
      jti: expect.stringMatching(/^urn:uuid:/),
      exp: expect.any(Number),
      fundingSource: expect.objectContaining({
        type: 'card',
        panLastFour: expect.stringMatching(/^[0-9a-f]{4}$/),
        parLastFour: expect.stringMatching(/^[0-9a-f]{4}$/),
        iin: '453201',
        scheme: 'Visa',
        currency: 'AUD',
      }),
    }));
    // exp ~5y out (RFC007 recommendation to align with card expiry)
    const nowSec = Math.floor(Date.now() / 1000);
    expect(sentBody.credentialData.exp).toBeGreaterThan(nowSec + 4 * 365 * 24 * 60 * 60);
    expect(sentBody.credentialData.given_name).toBeUndefined();
    expect(sentBody.credentialData.family_name).toBeUndefined();
    expect(sentBody.credentialData.payeeName).toBeUndefined();
    expect(sentBody.credentialData.panLastFour).toBeUndefined();
  });

  it('/api/psp/issue 403s when PID not yet verified', async () => {
    const res = await agent.post('/api/psp/issue').expect(403);
    expect(res.body).toEqual({ error: 'pid_required' });
  });

  it('/api/psp/issue 502s when issuer-api rejects the offer', async () => {
    await agent.post('/_test/session').send({
      pspPidVerified: {
        sub: 's-err', given_name: 'X', family_name: 'Y', birth_date: '2000-01-01',
      },
    }).expect(200);
    global.fetch = jest.fn(async () => ({ ok: false, status: 500, text: async () => 'boom' }));
    const res = await agent.post('/api/psp/issue').expect(502);
    expect(res.body).toMatchObject({ error: 'issuer_unavailable' });
  });

  it('/api/psp/issue produces a deterministic panLastFour for the same sub', async () => {
    await agent.post('/_test/session').send({
      pspPidVerified: { sub: 'stable-sub-123', given_name: 'A' },
    }).expect(200);
    global.fetch = jest.fn(async () => ({
      ok: true, status: 200, text: async () => 'openid-credential-offer://?x=1',
    }));
    const r1 = await agent.post('/api/psp/issue').expect(200);
    const r2 = await agent.post('/api/psp/issue').expect(200);
    expect(r1.body.panLastFour).toBe(r2.body.panLastFour);
  });
});
