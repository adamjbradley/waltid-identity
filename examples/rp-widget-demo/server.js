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

const crypto = require('crypto');
const express = require('express');
const session = require('express-session');
const path = require('path');
const { Issuer, generators } = require('openid-client');
const { UserStore } = require('./userStore');
const { CATALOGUE, getProduct } = require('./catalogue');
const { emptyCart, summary, addItem, setQty, removeItem, clearCart } = require('./cart');

/**
 * Per-VCT DCQL singleton query helper. For any ordered list of VCTs + claim
 * paths, emits one `credentials` entry per VCT with a single-element
 * `vct_values` array and the shared claim list, then ORs them under
 * `credential_sets.options`. This is the PR #88 workaround for the EUDI iOS
 * wallet-kit's first-VCT-only matcher — splitting the VCTs into separate
 * credentials keeps both EUDI and non-EUDI wallets matchable.
 *
 * For a single-VCT requirement (e.g. PaymentWalletAttestation) pass a one-
 * element `vcts` array; the helper still emits credential_sets so the shape
 * stays uniform on the wire.
 */
function buildSingletonDcql(vcts, claimPaths, format = 'dc+sd-jwt', idPrefix = 'cred') {
  return {
    credentials: vcts.map((vct, i) => ({
      id: `${idPrefix}_${i}`,
      format,
      meta: { vct_values: [vct] },
      claims: claimPaths.map((path) => ({ path: Array.isArray(path) ? path : [path] })),
    })),
    credential_sets: [{
      required: true,
      options: vcts.map((_, i) => [`${idPrefix}_${i}`]),
    }],
  };
}

/**
 * Age-only DCQL over the four EUDI-compat PID VCTs. Used by the
 * /api/age-check/* flow.
 */
function buildAgeOnlyDcql() {
  const vcts = [
    'urn:eudi:pid:1',
    'urn:au:gov:mygovid:pid:1',
    'urn:in:gov:aadhaar:pid:1',
    'urn:uk:gov:govuk-one-login:pid:1',
  ];
  return buildSingletonDcql(vcts, [['age_over_21']], 'dc+sd-jwt', 'pid');
}

/**
 * PID identity DCQL — asks for given_name, family_name, birth_date across
 * the four PID VCTs. Used by the /psp/enroll Step 1 (PID presentation).
 */
function buildPidIdentityDcql() {
  const vcts = [
    'urn:eudi:pid:1',
    'urn:au:gov:mygovid:pid:1',
    'urn:in:gov:aadhaar:pid:1',
    'urn:uk:gov:govuk-one-login:pid:1',
  ];
  return buildSingletonDcql(
    vcts,
    [['given_name'], ['family_name'], ['birth_date']],
    'dc+sd-jwt',
    'pid',
  );
}

/**
 * In-memory token maps leak if nothing ever evicts them. The status-poll
 * handler deletes once a terminal verdict has been mirrored into the
 * session cookie, but a user who closes the tab before polling would leave
 * the entry stranded. This TTL is the fallback for that case: after
 * SESSION_TOKEN_TTL_MS the entry is unconditionally removed. `unref()` on
 * the timeout handle keeps an idle event loop from being held open purely
 * by pending evictions (otherwise a freshly-started server with outstanding
 * tokens couldn't exit cleanly).
 *
 * Shared by all three OID4VP-webhook flows (age-check, psp-enroll PID,
 * pwa-capture) — they each have their own Map instance but register via
 * this helper so TTL semantics stay uniform.
 */
const SESSION_TOKEN_TTL_MS = 10 * 60 * 1000;
function registerSessionToken(map, token, entry) {
  map.set(token, entry);
  setTimeout(() => {
    const current = map.get(token);
    // Only delete if still the same entry (not replaced mid-flight)
    if (current === entry) map.delete(token);
  }, SESSION_TOKEN_TTL_MS).unref();
}
// Backwards-compatible alias. Old call sites (and the _test helper) keep
// using the original name; new code prefers `registerSessionToken`.
const registerAgeCheck = registerSessionToken;

/**
 * Does this session satisfy the `minAge` requirement? The demo treats a
 * verified OIDC claim (`age_over_18` / `age_over_21`) as equivalent to a
 * direct age-check flow that set `session.ageVerified = true`. Anything
 * else — including a missing `minAge` being falsy — returns true so
 * unrestricted products skip the gate entirely.
 */
function isAgeVerified(session, minAge) {
  if (!minAge) return true;
  if (session.user && session.user.age_over_21 === true && minAge <= 21) return true;
  if (session.user && session.user.age_over_18 === true && minAge <= 18) return true;
  return session.ageVerified === true;
}

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
const RP_ID = process.env.RP_ID || 'a1b2c3d4-e5f6-4a7b-8c9d-0e1f2a3b4c5d';
const RP_CLIENT_ID = process.env.RP_CLIENT_ID || '';
const RP_DOMAIN = process.env.RP_DOMAIN || '';

// Verifier API2 internal URL (for proxying verification-session responses from wallets)
const VERIFIER_API2_URL = process.env.VERIFIER_API2_URL || 'http://verifier-api2:7004';

// Issuer API internal URL (for PSP-enrollment credential-offer minting).
// The PSP tenant id maps to a multi-tenant issuer record set up on the
// remote stack (Task 11 in the rp-cart-dpc plan). `PSP_TENANT_ID` selects
// the tenant; `PSP_VCT` is the credential type the /psp/enroll flow issues.
const ISSUER_API_URL = process.env.ISSUER_API_URL || 'http://issuer-api:7002';
const PSP_TENANT_ID = process.env.PSP_TENANT_ID || 'psp.bankofdemo';
const PSP_VCT = process.env.PSP_VCT || 'PaymentWalletAttestation';

// Public base for webhook callbacks verifier-api2 can reach. The verifier-api2
// server must be able to HTTP POST here when a wallet completes a presentation,
// so it has to resolve from verifier-api2's network namespace (not just the
// user's browser). For the demo stack this is the Cloudflare-tunnelled origin.
const PUBLIC_URL = process.env.PUBLIC_URL || 'https://rp.theaustraliahack.com';

// OIDC Providers — multiple are supported side-by-side. Each one needs its
// own ISSUER/CLIENT_ID/CLIENT_SECRET trio; a provider is only enabled when
// all three are set. Callback paths are /callback (for the default keycloak
// provider, kept for backwards compat) and /callback/<name> for others.
const SESSION_SECRET = process.env.SESSION_SECRET || 'dev-session-secret-change-me';
// Public base URL of this app (used as the OIDC redirect_uri base)
const PUBLIC_BASE_URL = process.env.PUBLIC_BASE_URL || '';

// Path to the file-backed user profile registry. Defaulted under the
// container WORKDIR (/usr/src/app) but overridable for tests / local dev.
// When bind-mounted from the host, profiles survive container recycles.
// The actual UserStore instance is constructed inside `createApp()` so
// tests can set `USER_STORE_FILE` per-test and get a fresh on-disk
// registry; binding it here at module-load would lock every test into
// the same file.
function resolveUserStoreFile() {
  return process.env.USER_STORE_FILE || path.join(__dirname, 'data', 'users.json');
}

const OIDC_PROVIDERS = {
  keycloak: {
    label: 'Keycloak',
    issuer: process.env.KEYCLOAK_ISSUER || '',
    clientId: process.env.KEYCLOAK_CLIENT_ID || '',
    clientSecret: process.env.KEYCLOAK_CLIENT_SECRET || '',
    // Legacy callback path (pre-dates the multi-provider support).
    callbackPath: '/callback',
  },
  authop: {
    label: 'auth-op',
    issuer: process.env.AUTHOP_ISSUER || '',
    clientId: process.env.AUTHOP_CLIENT_ID || '',
    clientSecret: process.env.AUTHOP_CLIENT_SECRET || '',
    callbackPath: '/callback/authop',
  },
};

function providerEnabled(name) {
  const p = OIDC_PROVIDERS[name];
  return !!(p && p.issuer && p.clientId && p.clientSecret);
}

function enabledProviderNames() {
  return Object.keys(OIDC_PROVIDERS).filter(providerEnabled);
}

const OIDC_ENABLED = enabledProviderNames().length > 0;

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
 * Lazily discover each provider's issuer and build a cached openid-client.
 * Result is a Promise keyed by provider name.
 */
const oidcClientPromises = {};
function getOidcClient(name) {
  if (!providerEnabled(name)) return null;
  if (!oidcClientPromises[name]) {
    const p = OIDC_PROVIDERS[name];
    oidcClientPromises[name] = Issuer.discover(p.issuer).then((issuer) => {
      console.log(`[OIDC:${name}] Discovered issuer: ${issuer.metadata.issuer}`);
      return new issuer.Client({
        client_id: p.clientId,
        client_secret: p.clientSecret,
        redirect_uris: [oidcRedirectUri(name)],
        response_types: ['code'],
        // client_secret_basic is universally supported (auth-op registers
        // this client with that method; Keycloak accepts both).
        token_endpoint_auth_method: 'client_secret_basic',
      });
    }).catch((err) => {
      console.error(`[OIDC:${name}] Issuer discovery failed:`, err.message);
      delete oidcClientPromises[name];
      throw err;
    });
  }
  return oidcClientPromises[name];
}

function oidcRedirectUri(name) {
  const base = PUBLIC_BASE_URL || `http://localhost:${process.env.PORT || 4000}`;
  const path = (OIDC_PROVIDERS[name] && OIDC_PROVIDERS[name].callbackPath) || `/callback/${name}`;
  return `${base.replace(/\/$/, '')}${path}`;
}

/**
 * Create and configure Express application
 * @returns {express.Application} Configured Express app
 */
function createApp() {
  const app = express();

  // Cross-process state for age-check webhooks. Verifier-api2 calls us back
  // from its own network namespace with no browser cookie, so we can't touch
  // the user's req.session directly from the webhook handler. Keyed by a
  // random URL token minted on /api/age-check/start; the same triple lives
  // in req.session.ageCheck so the user's later /api/age-check/status poll
  // knows which entry belongs to them. Scoped per createApp() call so jest
  // test suites that build a fresh app don't inherit state from earlier ones.
  const ageCheckByToken = new Map();

  // Parallel map for the PSP-enrollment PID presentation. Same keying +
  // TTL discipline as ageCheckByToken — kept separate so the status poll
  // doesn't need to disambiguate "which flow owns this token".
  const pspEnrollByToken = new Map();

  // Per-app userStore. See resolveUserStoreFile() — the file path is
  // evaluated here so each call to createApp() can pick up a fresh
  // USER_STORE_FILE env (required for test isolation).
  const userStoreFile = resolveUserStoreFile();
  const userStore = new UserStore(userStoreFile);
  if (process.env.NODE_ENV !== 'test') {
    console.log(`[userStore] loaded ${userStore.count()} profile(s) from ${userStoreFile}`);
  }

  // Trust proxy so secure cookies work behind Caddy/Cloudflare
  app.set('trust proxy', 1);

  // Session middleware — required for OIDC state/code_verifier and user session
  app.use(session({
    name: 'rp.sid',
    secret: SESSION_SECRET,
    resave: false,
    saveUninitialized: false,
    cookie: {
      httpOnly: true,
      secure: process.env.NODE_ENV === 'production',
      sameSite: 'lax',
      maxAge: 1000 * 60 * 60 * 8, // 8 hours
    },
  }));

  // Proxy verification-session requests to verifier-api2.
  // The EUDI wallet POSTs VP tokens to response_uri which uses the RP's domain.
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

  /**
   * GET /api/catalogue
   *
   * Returns the server-side product catalogue. Both the storefront shelf
   * and cart validation consume this so there's one source of truth.
   */
  app.get('/api/catalogue', (_req, res) => res.json(CATALOGUE));

  // Ensure every /api/cart* handler sees a real cart — replaces the five
  // copies of `req.session.cart = req.session.cart || emptyCart()` that
  // used to live in each handler.
  const ensureCart = (req, _res, next) => { req.session.cart = req.session.cart || emptyCart(); next(); };
  app.use('/api/cart', ensureCart);

  /**
   * GET /api/cart
   *
   * Returns the current session cart as a wire-shaped summary. Initialises
   * an empty cart on first call so every subsequent handler can rely on
   * `req.session.cart` existing.
   */
  app.get('/api/cart', (req, res) => {
    res.json(summary(req.session.cart));
  });

  /**
   * POST /api/cart/items  { productId, qty? }
   *
   * Server-side age gate: the client can easily be bypassed, so we re-check
   * every add here. 404 for unknown products, 403 with `minAge` when the
   * product is age-restricted and the session isn't verified.
   */
  app.post('/api/cart/items', (req, res) => {
    const product = getProduct(req.body.productId);
    if (!product) return res.status(404).json({ error: 'unknown_product' });
    const qty = Number(req.body.qty ?? 1);
    if (!Number.isFinite(qty) || qty <= 0) return res.status(400).json({ error: 'invalid_qty' });
    if (product.ageRestricted && !isAgeVerified(req.session, product.minAge)) {
      return res.status(403).json({ error: 'age_verification_required', minAge: product.minAge });
    }
    addItem(req.session.cart, product, qty);
    res.json(summary(req.session.cart));
  });

  /**
   * PATCH /api/cart/items/:productId  { qty }
   *
   * 404 when the line isn't in the cart, 400 on non-numeric / negative qty.
   * Re-runs the age gate when the quantity is going UP on an age-restricted
   * line so a stale `ageVerified=false` session can't bump qty past the
   * original add.
   */
  app.patch('/api/cart/items/:productId', (req, res) => {
    const existing = req.session.cart.items.find(i => i.productId === req.params.productId);
    if (!existing) return res.status(404).json({ error: 'item_not_in_cart' });
    const newQty = Number(req.body.qty);
    if (!Number.isFinite(newQty) || newQty < 0) return res.status(400).json({ error: 'invalid_qty' });
    if (newQty > existing.qty && existing.ageRestricted) {
      const product = getProduct(req.params.productId);
      if (!isAgeVerified(req.session, product && product.minAge)) {
        return res.status(403).json({ error: 'age_verification_required', minAge: (product && product.minAge) || 21 });
      }
    }
    setQty(req.session.cart, req.params.productId, newQty);
    res.json(summary(req.session.cart));
  });

  app.delete('/api/cart/items/:productId', (req, res) => {
    removeItem(req.session.cart, req.params.productId);
    res.json(summary(req.session.cart));
  });

  app.post('/api/cart/clear', (req, res) => {
    clearCart(req.session.cart);
    res.json(summary(req.session.cart));
  });

  /**
   * POST /api/age-check/start
   *
   * Kick an age-only OID4VP session on verifier-api2 for the four EUDI-compat
   * PID VCTs, return the QR payload for the browser.
   *
   * Wire shape for the upstream POST is the one actually implemented by
   * verifier-api2's session-create route (verified against
   * `waltid-services/waltid-auth-op/src/main/kotlin/id/walt/authop/upstream/
   *  Verifier2Client.kt`, which documents the line refs into
   *  VerificationSessionSetupData.kt):
   *
   *   { flow_type: "cross_device",
   *     core_flow: {
   *       dcql_query: {...},
   *       signed_request: true,      // EUDI wallets require a signed JAR
   *       notifications: {
   *         webhook: { url, bearer_token }
   *       }
   *     } }
   *
   * We embed a random `token` in the webhook URL so a later handler (Task 7)
   * can look up the in-memory map by path parameter (no session cookie
   * arrives with the verifier-api2 → RP callback). Shared secret is minted
   * here and stored in both `ageCheckByToken` (for the webhook bearer
   * compare) and `req.session.ageCheck` (for the user's later status poll).
   */
  app.post('/api/age-check/start', async (req, res) => {
    const token = crypto.randomBytes(16).toString('hex');
    const webhookSecret = crypto.randomBytes(32).toString('base64url');
    const webhookUrl = `${PUBLIC_URL.replace(/\/$/, '')}/api/age-check/webhook/${token}`;
    const body = {
      flow_type: 'cross_device',
      core_flow: {
        dcql_query: buildAgeOnlyDcql(),
        signed_request: true,
        notifications: {
          webhook: {
            url: webhookUrl,
            bearer_token: webhookSecret,
          },
        },
      },
    };
    try {
      const r = await fetch(
        `${VERIFIER_API2_URL}/verification-session/create?rpId=${encodeURIComponent(RP_ID)}`,
        {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify(body),
        }
      );
      if (!r.ok) {
        console.warn('[age-check] verifier-api2 session-create failed', r.status);
        return res.status(502).json({ error: 'verifier_unavailable' });
      }
      const session = await r.json();
      // verifier-api2 returns `sessionId` + `bootstrapAuthorizationRequestUrl`
      // (cross-device QR target, may be null on same-device) +
      // `fullAuthorizationRequestUrl`. Degrade to full URL if bootstrap
      // is absent so the caller always gets something scannable.
      const qrCode = session.bootstrapAuthorizationRequestUrl || session.fullAuthorizationRequestUrl;
      registerAgeCheck(ageCheckByToken, token, { webhookSecret, verified: null });
      req.session.ageCheck = { sessionId: session.sessionId, token, webhookSecret };
      res.json({ sessionId: session.sessionId, qrCode });
    } catch (err) {
      console.warn('[age-check] session-create error', err.message || err);
      res.status(502).json({ error: 'verifier_unavailable' });
    }
  });

  /**
   * POST /api/age-check/webhook/:token
   *
   * verifier-api2 → us server-to-server callback. Bearer-auth with the
   * shared secret minted at /api/age-check/start (constant-time compare),
   * then on the terminal `policy_results_available` event read the boolean
   * `age_over_21` out of the first credential's `credentialData` and
   * memoize it.
   *
   * Body shape is `{target, event, session: {...}}` where session mirrors
   * a serialized Verification2Session (verified against
   * `waltid-libraries/protocols/waltid-openid4vp-verifier/.../Verification2Session.kt`,
   * fields `id`, `status`, `presentedCredentials`, and
   * `waltid-services/waltid-auth-op/.../VpFlowRoutes.kt:handleVpWebhook`
   * which implements the same contract).
   *
   * Auth header is `Authorization: Bearer <secret>` (KtorSessionNotifications
   * WebhookNotifier) — NOT `X-Webhook-Secret` as the plan sketched.
   */
  app.post('/api/age-check/webhook/:token', (req, res) => {
    const entry = ageCheckByToken.get(req.params.token);
    // Unknown token vs bad-secret are observably different anyway (verifier
    // would POST to a nonexistent path if the token were truly wrong), so
    // 404 is both informative and honest. 401 is reserved for a known token
    // with a bad / missing bearer.
    if (!entry) return res.status(404).json({ error: 'unknown_token' });
    const authHeader = req.header('authorization') || '';
    if (!/^Bearer\s+/i.test(authHeader)) {
      return res.status(401).json({ error: 'bad_secret' });
    }
    const provided = Buffer.from(authHeader.replace(/^Bearer\s+/i, '').trim(), 'utf8');
    const expected = Buffer.from(entry.webhookSecret || '', 'utf8');
    if (
      provided.length !== expected.length ||
      !crypto.timingSafeEqual(provided, expected)
    ) {
      return res.status(401).json({ error: 'bad_secret' });
    }
    // Gate on terminal event — earlier events (presentation_received,
    // validating_received_request, ...) we ACK without touching `verified`.
    // This mirrors the auth-op handler's contract and keeps us from
    // prematurely toggling state based on a non-final signal.
    if (req.body.event !== 'policy_results_available') {
      return res.json({ ok: true });
    }
    const session = req.body.session || {};
    if (session.status !== 'SUCCESSFUL') {
      entry.verified = false;
      return res.json({ ok: true });
    }
    // Walk `presentedCredentials: Map<String, List<DigitalCredential>>`,
    // take the age_over_21 claim from the first credential of the first
    // matched bucket. DCQL credential_sets means only one bucket will
    // actually populate, so order doesn't matter in practice.
    const creds = session.presentedCredentials || {};
    let claim;
    for (const arr of Object.values(creds)) {
      if (Array.isArray(arr) && arr.length && arr[0] && arr[0].credentialData) {
        if (arr[0].credentialData.age_over_21 !== undefined) {
          claim = arr[0].credentialData.age_over_21;
          break;
        }
      }
    }
    entry.verified = claim === true;
    res.json({ ok: true });
  });

  /**
   * GET /api/age-check/status
   *
   * Browser-facing poll. Mirrors `verified` out of the in-memory map into
   * `req.session.ageVerified` so subsequent cart POSTs pick it up via the
   * existing `isAgeVerified(session, minAge)` gate. `{verified: null}`
   * means "no terminal webhook yet" (still waiting on the wallet) OR
   * "no age-check session ever started on this cookie".
   */
  app.get('/api/age-check/status', (req, res) => {
    const tok = req.session.ageCheck && req.session.ageCheck.token;
    if (!tok) return res.json({ verified: null });
    const entry = ageCheckByToken.get(tok);
    if (!entry) return res.json({ verified: null });
    if (entry.verified === true || entry.verified === false) {
      req.session.ageVerified = entry.verified;
      ageCheckByToken.delete(tok); // eviction: verdict is now on the session cookie
    }
    res.json({ verified: entry.verified });
  });

  // Dev-only session hydration helper for tests. Lets supertest seed
  // `ageVerified` / `user` without round-tripping a real OIDC login.
  // Guarded on NODE_ENV to keep it out of production builds.
  if (process.env.NODE_ENV !== 'production') {
    app.post('/_test/session', (req, res) => {
      Object.assign(req.session, req.body);
      res.json({ ok: true });
    });
    // Companion helper for the age-check webhook suite: lets supertest
    // hydrate `ageCheckByToken` without having to stand up a real
    // /api/age-check/start call (which would need a verifier-api2 mock
    // for every webhook test). Same NODE_ENV guard.
    app.post('/_test/age-check/register', (req, res) => {
      const { token, webhookSecret } = req.body || {};
      if (!token || !webhookSecret) {
        return res.status(400).json({ error: 'missing_fields' });
      }
      registerAgeCheck(ageCheckByToken, token, { webhookSecret, verified: null });
      res.json({ ok: true });
    });
    // Same pattern for the PSP-enrollment PID flow.
    app.post('/_test/psp/register', (req, res) => {
      const { token, webhookSecret } = req.body || {};
      if (!token || !webhookSecret) return res.status(400).json({ error: 'missing_fields' });
      registerSessionToken(pspEnrollByToken, token, { webhookSecret, verified: null, claims: null });
      res.json({ ok: true });
    });
  }

  // ============================================================
  // PSP enrollment (PID presentation → PaymentWalletAttestation)
  // ============================================================
  //
  // Two-step ceremony served at /psp/enroll:
  //   1. User presents PID via OID4VP. We pull {given_name, family_name,
  //      birth_date} + `sub` into `req.session.pspPidVerified`.
  //   2. RP calls issuer-api's credential-offer endpoint to mint a
  //      PaymentWalletAttestation, hands the resulting offerUri back as a
  //      QR/deep link the wallet consumes.
  //
  // Step 2 has no polling on the RP side — the user clicks "I've added it"
  // and is routed to /cart?pwa=1 where the Task 15 capture flow picks up
  // the newly-issued credential via a fresh OID4VP presentation request.

  /** GET /psp/enroll — serves the static mini-page. */
  app.get('/psp/enroll', (_req, res) => {
    res.sendFile(path.join(__dirname, 'public', 'psp-enroll.html'));
  });

  /**
   * POST /api/psp/start — kick an OID4VP session asking for PID identity
   * claims (given_name, family_name, birth_date). Wire shape mirrors
   * /api/age-check/start, differing only in the DCQL.
   */
  app.post('/api/psp/start', async (req, res) => {
    const token = crypto.randomBytes(16).toString('hex');
    const webhookSecret = crypto.randomBytes(32).toString('base64url');
    const webhookUrl = `${PUBLIC_URL.replace(/\/$/, '')}/api/psp/webhook/${token}`;
    const body = {
      flow_type: 'cross_device',
      core_flow: {
        dcql_query: buildPidIdentityDcql(),
        signed_request: true,
        notifications: {
          webhook: {
            url: webhookUrl,
            bearer_token: webhookSecret,
          },
        },
      },
    };
    try {
      const r = await fetch(
        `${VERIFIER_API2_URL}/verification-session/create?rpId=${encodeURIComponent(RP_ID)}`,
        { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) },
      );
      if (!r.ok) {
        console.warn('[psp] verifier-api2 session-create failed', r.status);
        return res.status(502).json({ error: 'verifier_unavailable' });
      }
      const session = await r.json();
      const qrCode = session.bootstrapAuthorizationRequestUrl || session.fullAuthorizationRequestUrl;
      registerSessionToken(pspEnrollByToken, token, { webhookSecret, verified: null, claims: null });
      req.session.pspEnroll = { sessionId: session.sessionId, token, webhookSecret };
      res.json({ sessionId: session.sessionId, qrCode });
    } catch (err) {
      console.warn('[psp] session-create error', err.message || err);
      res.status(502).json({ error: 'verifier_unavailable' });
    }
  });

  /**
   * POST /api/psp/webhook/:token — verifier-api2 callback for the PID
   * presentation. On SUCCESSFUL we capture {sub, given_name, family_name,
   * birth_date} into the map entry for the status poll to mirror.
   */
  app.post('/api/psp/webhook/:token', (req, res) => {
    const entry = pspEnrollByToken.get(req.params.token);
    if (!entry) return res.status(404).json({ error: 'unknown_token' });
    const authHeader = req.header('authorization') || '';
    if (!/^Bearer\s+/i.test(authHeader)) {
      return res.status(401).json({ error: 'bad_secret' });
    }
    const provided = Buffer.from(authHeader.replace(/^Bearer\s+/i, '').trim(), 'utf8');
    const expected = Buffer.from(entry.webhookSecret || '', 'utf8');
    if (provided.length !== expected.length || !crypto.timingSafeEqual(provided, expected)) {
      return res.status(401).json({ error: 'bad_secret' });
    }
    if (req.body.event !== 'policy_results_available') return res.json({ ok: true });
    const session = req.body.session || {};
    if (session.status !== 'SUCCESSFUL') {
      entry.verified = false;
      return res.json({ ok: true });
    }
    const creds = session.presentedCredentials || {};
    for (const arr of Object.values(creds)) {
      if (Array.isArray(arr) && arr.length && arr[0] && arr[0].credentialData) {
        const cd = arr[0].credentialData;
        // sub may arrive as `sub` or fall back to credentialData's own id.
        const sub = cd.sub || cd.subject || arr[0].sub || null;
        entry.verified = true;
        entry.claims = {
          sub: sub,
          given_name: cd.given_name,
          family_name: cd.family_name,
          birth_date: cd.birth_date,
        };
        return res.json({ ok: true });
      }
    }
    // No credential data surfaced — treat as a declined presentation.
    entry.verified = false;
    res.json({ ok: true });
  });

  /**
   * GET /api/psp/status — browser poll. When the webhook has written a
   * terminal verdict, mirrors the claims into `req.session.pspPidVerified`
   * and evicts the map entry.
   */
  app.get('/api/psp/status', (req, res) => {
    const tok = req.session.pspEnroll && req.session.pspEnroll.token;
    if (!tok) return res.json({ verified: null });
    const entry = pspEnrollByToken.get(tok);
    if (!entry) return res.json({ verified: null });
    if (entry.verified === true) {
      req.session.pspPidVerified = entry.claims || {};
      pspEnrollByToken.delete(tok);
      return res.json({ verified: true, claims: entry.claims || null });
    }
    if (entry.verified === false) {
      pspEnrollByToken.delete(tok);
      return res.json({ verified: false, claims: null });
    }
    res.json({ verified: null });
  });

  /**
   * POST /api/psp/issue — once PID is verified on the session, ask
   * issuer-api for a pre-authorized-code credential offer for the
   * PaymentWalletAttestation VCT.
   *
   * Demo card data is derived deterministically from the presented `sub`
   * so a repeat enrollment shows the same "card ending …" value. In
   * production the PSP would mint a real PAN here; for the demo we're
   * just showing the ceremony.
   */
  app.post('/api/psp/issue', async (req, res) => {
    if (!req.session.pspPidVerified) return res.status(403).json({ error: 'pid_required' });
    const holderSub = req.session.pspPidVerified.sub || 'anonymous';
    const hash = crypto.createHash('sha256').update(String(holderSub)).digest('hex');
    const panLastFour = hash.slice(0, 4);
    const scheme = 'Visa';
    const iin = '453201';
    const currency = 'AUD';
    const payeeName = 'Bank of Demo';

    // The issuer-api route for a tenant-scoped pre-authorized credential
    // offer is POST /issuers/{issuerId}/openid4vc/sdjwt/issue. It returns
    // the offer URI as a plain string body. We pass only the config id,
    // VCT, and credentialData — the tenant's configured issuerKey and
    // DID are enriched server-side (TenantIssuerRoutes.enrichRequestWithTenantKeys).
    const offerReq = {
      credentialConfigurationId: PSP_VCT,
      vct: PSP_VCT,
      credentialData: {
        panLastFour,
        scheme,
        iin,
        currency,
        payeeName,
        given_name: req.session.pspPidVerified.given_name,
        family_name: req.session.pspPidVerified.family_name,
      },
      authenticationMethod: 'PRE_AUTHORIZED',
    };

    try {
      const url = `${ISSUER_API_URL}/issuers/${encodeURIComponent(PSP_TENANT_ID)}/openid4vc/sdjwt/issue`;
      const r = await fetch(url, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(offerReq),
      });
      if (!r.ok) {
        console.warn('[psp] issuer-api offer failed', r.status);
        return res.status(502).json({ error: 'issuer_unavailable', status: r.status });
      }
      // Response is the offer URI as plain text (possibly JSON-quoted).
      const raw = await r.text();
      let offerUri = raw;
      try {
        const parsed = JSON.parse(raw);
        if (typeof parsed === 'string') offerUri = parsed;
        else if (parsed && typeof parsed === 'object') offerUri = parsed.offerUri || parsed.uri || raw;
      } catch (_) { /* plain string body */ }
      res.json({ offerUri, panLastFour, scheme });
    } catch (err) {
      console.warn('[psp] offer error', err.message || err);
      res.status(502).json({ error: 'issuer_unavailable' });
    }
  });

  // ---------------- OIDC login routes (multi-provider) ----------------
  // These are registered unconditionally so `/api/me` always works; the
  // actual login/callback handlers return 503 when the requested provider
  // is not configured.

  /** GET /api/me — current login state + list of enabled providers. */
  app.get('/api/me', (req, res) => {
    const providers = enabledProviderNames().map((name) => ({
      name,
      label: OIDC_PROVIDERS[name].label,
      loginPath: name === 'keycloak' ? '/login' : `/login/${name}`,
    }));
    res.json({
      oidcEnabled: OIDC_ENABLED,
      providers,
      user: (req.session && req.session.user) || null,
      activeProvider: (req.session && req.session.provider) || null,
    });
  });

  /**
   * GET /api/users — list of all known users persisted by the RP, newest
   * login first. Read-only admin view for the demo; if/when this moves
   * beyond demo use it should be gated by a role / auth check.
   *
   * Intentionally returns the full profile including email and DOB — the
   * stack is a private demo and this endpoint is the fastest way to
   * confirm that the upsert hook fires on every successful OIDC callback.
   */
  app.get('/api/users', (req, res) => {
    res.json({ count: userStore.count(), users: userStore.list() });
  });

  /**
   * GET /login          — alias for the first enabled provider (back-compat:
   *                        keycloak when that is configured).
   * GET /login/:name    — kick off Authorization Code flow with PKCE against
   *                        the named provider.
   */
  app.get(['/login', '/login/:name'], async (req, res) => {
    const name = req.params.name || 'keycloak';
    if (!providerEnabled(name)) {
      return res.status(503).send(`OIDC provider "${name}" not configured`);
    }
    try {
      const client = await getOidcClient(name);
      const code_verifier = generators.codeVerifier();
      const code_challenge = generators.codeChallenge(code_verifier);
      const state = generators.state();
      const nonce = generators.nonce();

      req.session.oidc = { name, code_verifier, state, nonce };

      // Scope selection. `auth-op` honours a scope catalog (KYC + age
      // attestations — see docs/plans/2026-04-20-rp-scope-hints-design.md);
      // other providers like Keycloak just get the OIDC standard scopes.
      // AUTH_SCOPES env lets an operator override the authop request scope
      // without a rebuild (e.g. to test a KYC-only flow).
      const scope = (name === 'authop')
        ? (process.env.AUTH_SCOPES || 'openid kyc age_over_18 age_over_21')
        : 'openid profile email';
      const url = client.authorizationUrl({
        scope,
        code_challenge,
        code_challenge_method: 'S256',
        state,
        nonce,
        redirect_uri: oidcRedirectUri(name),
      });
      res.redirect(url);
    } catch (err) {
      console.error('[OIDC] /login error:', err.message);
      res.status(500).send('OIDC login unavailable');
    }
  });

  /**
   * GET /callback           — keycloak provider's callback (historic path).
   * GET /callback/:name     — named provider's callback (e.g. /callback/authop).
   * Both exchange the code for tokens and populate the session.
   */
  async function handleCallback(req, res, providerName) {
    if (!providerEnabled(providerName)) {
      return res.status(503).send(`OIDC provider "${providerName}" not configured`);
    }
    try {
      const client = await getOidcClient(providerName);
      const saved = req.session.oidc;
      if (!saved) return res.status(400).send('Missing OIDC session state');
      if (saved.name && saved.name !== providerName) {
        return res.status(400).send(
          `OIDC provider mismatch: session started ${saved.name}, callback is ${providerName}`
        );
      }

      const params = client.callbackParams(req);
      const tokenSet = await client.callback(
        oidcRedirectUri(providerName),
        params,
        { code_verifier: saved.code_verifier, state: saved.state, nonce: saved.nonce }
      );
      const claims = tokenSet.claims();

      // Strip JWT-framing claims (not useful in the UI hover) before
      // surfacing the full claim set. Keeps transport metadata out of
      // `displayClaims` while everything else — including the
      // `preferences` composite from auth-op's post-consent n8n
      // workflow — stays for the hover panel to render.
      const { iss, aud, iat, exp, nbf, nonce, auth_time, at_hash, jti, azp, ...displayClaims } = claims;

      // User profile shape. The auth-op scope catalog guarantees the
      // id_token only ever carries {sub, kyc_verified, age_over_18,
      // age_over_21, preferences?} for the authop provider — PII transits
      // auth-op for consent display but never lands in our id_token.
      // Keycloak has no such contract; we persist its standard
      // profile/email claims so the widget still renders a name. The
      // userStore layer also enforces the boolean-only allowlist for
      // authop records (defence in depth).
      //
      // Both branches carry `claims: displayClaims` so the profile
      // popover can render the complete set the OP projected, regardless
      // of which named fields the server-side filter kept.
      const userProfile = (providerName === 'authop')
        ? {
            sub: claims.sub,
            kyc_verified: Boolean(claims.kyc_verified),
            age_over_18: Boolean(claims.age_over_18),
            age_over_21: Boolean(claims.age_over_21),
            claims: displayClaims,
          }
        : {
            sub: claims.sub,
            email: claims.email,
            name: claims.name || claims.preferred_username,
            given_name: claims.given_name,
            family_name: claims.family_name,
            claims: displayClaims,
          };
      // If the userStore already has a paymentMethod for this sub, fold it
      // into the session profile so the hover popover reflects previously-
      // enrolled payment methods on every login. The hover reads
      // `user.paymentMethod`, so this is the hydration point for it.
      try {
        const existing = userStore.get && userStore.get(claims.sub);
        if (existing && existing.paymentMethod) {
          userProfile.paymentMethod = existing.paymentMethod;
        }
      } catch (_) { /* getter missing — treated as no existing profile */ }

      req.session.user = userProfile;
      req.session.idToken = tokenSet.id_token;
      req.session.provider = providerName;
      delete req.session.oidc;

      // Persist the profile for this sub across sessions. Upsert semantics:
      // new claims overlay the existing record, loginCount bumps, first/last
      // seen timestamps update. The store is the source of truth for the
      // /api/users admin listing and for any future "welcome back" UX.
      try {
        userStore.upsert(Object.assign({}, userProfile, { provider: providerName }));
      } catch (err) {
        console.warn(`[userStore] upsert failed for sub=${claims.sub}:`, err.message);
      }

      res.redirect('/');
    } catch (err) {
      console.error(`[OIDC:${providerName}] /callback error:`, err.message);
      res.status(500).send('Login callback failed');
    }
  }
  app.get('/callback', (req, res) => handleCallback(req, res, 'keycloak'));
  app.get('/callback/:name', (req, res) => handleCallback(req, res, req.params.name));

  /** POST /logout — clear local session and chain to the OP's end_session. */
  app.post('/logout', async (req, res) => {
    const idToken = req.session && req.session.idToken;
    const providerName = req.session && req.session.provider;
    // OPs match post_logout_redirect_uri against registered patterns. auth-op
    // uses strict prefix matching for `.../*` patterns, so the candidate must
    // include the trailing slash; Keycloak accepts both. Always emit with a
    // trailing slash to stay compatible with both.
    const base = (PUBLIC_BASE_URL || `http://localhost:${process.env.PORT || 4000}`).replace(/\/$/, '');
    const returnTo = `${base}/`;
    req.session.destroy(() => {
      res.clearCookie('rp.sid');
      if (!providerName || !providerEnabled(providerName)) return res.redirect('/');
      getOidcClient(providerName)
        .then((client) => {
          const url = client.endSessionUrl({
            id_token_hint: idToken,
            post_logout_redirect_uri: returnTo,
          });
          res.redirect(url);
        })
        .catch(() => res.redirect('/'));
    });
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
    const enabled = enabledProviderNames();
    if (enabled.length) {
      console.log(`  OIDC providers: ${enabled.length}`);
      enabled.forEach((name) => {
        const p = OIDC_PROVIDERS[name];
        console.log(`    - ${name} (${p.label}): ${p.issuer}`);
        console.log(`      redirect: ${oidcRedirectUri(name)}`);
      });
    } else {
      console.log(`  OIDC providers: none (set {KEYCLOAK,AUTHOP}_ISSUER + _CLIENT_ID + _CLIENT_SECRET)`);
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
