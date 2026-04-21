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

// NOTE: the PID-identity DCQL helper used to live here, powering the
// /psp/enroll PID presentation. That flow moved to
// examples/mock-psp-demo/ when the PSP became a standalone service
// (psp.theaustraliahack.com). The RP only drives age-check + checkout now.
//
// There used to be a second DCQL helper (`buildPwaCaptureDcql`) for a
// post-enrollment "silent capture" that let the merchant stash the
// shopper's PAN-last-four + scheme on the session profile. That flow was
// removed: it wasn't standards-compliant (merchants don't discover card
// details between enrollment and checkout), the capture kickoff never
// rendered a QR so the wallet couldn't actually present, and the
// "Verifying your new payment method…" banner looped forever. Card
// details are learned at checkout via OID4VP presentation — see
// `buildPwaCheckoutDcql` below.

/**
 * PaymentWalletAttestation DCQL used at /checkout pay time. Single VCT,
 * single credential; claims [panLastFour, scheme] are what the receipt
 * page echoes back on the order confirmation.
 */
function buildPwaCheckoutDcql() {
  return buildSingletonDcql(
    ['PaymentWalletAttestation'],
    [['panLastFour'], ['scheme']],
    'dc+sd-jwt',
    'pwa',
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
 * Shared by the OID4VP-webhook flows (age-check + checkout) — they each
 * have their own Map instance but register via this helper so TTL
 * semantics stay uniform.
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
function isAgeVerified(session, minAge, userStore) {
  if (!minAge) return true;
  const u = session.user || {};
  // Session claims come from the most recent OIDC callback — first-class
  // and the fastest path.
  if (u.age_over_21 === true && minAge <= 21) return true;
  if (u.age_over_18 === true && minAge <= 18) return true;
  // Persisted profile fallback for returning logged-in users whose session
  // lost the age claim (cookie rotation, partial rehydrate) but whose sub
  // is still present. Skips when no userStore is threaded (unit tests on
  // isolated sessions, or anonymous sessions without a sub).
  if (u.sub && userStore && typeof userStore.get === 'function') {
    const stored = userStore.get(u.sub);
    if (stored) {
      if (stored.age_over_21 === true && minAge <= 21) return true;
      if (stored.age_over_18 === true && minAge <= 18) return true;
    }
  }
  // Anonymous age-check flow set the ephemeral per-session flag.
  return session.ageVerified === true;
}

/**
 * Summarise a cart's age-gate requirement: returns the max `minAge` across
 * age-restricted items, or 0 if nothing is age-restricted. Called at
 * checkout so the gate enforcement picks the tightest constraint.
 */
function cartMinAge(cart) {
  if (!cart || !Array.isArray(cart.items)) return 0;
  let max = 0;
  for (const it of cart.items) {
    if (it.ageRestricted) max = Math.max(max, Number(it.minAge) || 21);
  }
  return max;
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

// PSP-enrollment credential-offer minting moved to the standalone
// mock-psp-demo service (see examples/mock-psp-demo). The RP no longer
// speaks to issuer-api directly — the profile hover links to
// ${PUBLIC_PSP_URL}/enroll?return=... instead.
const PUBLIC_PSP_URL = process.env.PUBLIC_PSP_URL || 'https://psp.theaustraliahack.com';

// Public base for webhook callbacks verifier-api2 can reach. The verifier-api2
// server must be able to HTTP POST here when a wallet completes a presentation,
// so it has to resolve from verifier-api2's network namespace (not just the
// user's browser). For the demo stack this is the Cloudflare-tunnelled origin.
const PUBLIC_URL = process.env.PUBLIC_URL || 'https://rp.theaustraliahack.com';

// Admin access. Only users who sign in via the `keycloak` provider with
// this email become admins of the /admin surface. Default is the demo
// operator; override in .env.local for other deployments. `email_verified`
// is not enforced here — Keycloak federates to identity providers that
// generally set it, but for demo-simplicity we trust Keycloak's assertion.
const ADMIN_EMAIL = process.env.RP_ADMIN_EMAIL || 'adam_j_bradley@yahoo.com';
const ADMIN_PROVIDER = process.env.RP_ADMIN_PROVIDER || 'keycloak';

/**
 * Is the current session the RP admin? Admin identity is provider + email,
 * not a role/scope, because the demo's OIDC providers don't emit roles.
 * In production this would read a verified role claim from the id_token.
 */
function isAdmin(session) {
  if (!session) return false;
  // `provider` is stored on the session root (set at OIDC callback,
  // server.js L1215), not on session.user. `user.email` is set only by the
  // keycloak branch; authop profiles intentionally have no email.
  const u = session.user || {};
  return session.provider === ADMIN_PROVIDER && u.email === ADMIN_EMAIL;
}

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
  RP_DOMAIN,
  PUBLIC_PSP_URL
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

  // Task 17/18: /api/checkout → verifier-api2 PWA presentation session.
  // Entry shape: {orderId, total, currency, items, txData, webhookSecret,
  //               completed: boolean, order?: {...}}. The webhook writes
  //               {completed, order} on SUCCESSFUL; /api/checkout/status
  //               mirrors the order into req.session.orders + userStore
  //               and evicts the entry.
  const checkoutByToken = new Map();

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
      apiBaseUrl: config.PUBLIC_VERIFY_API_URL,
      // External PSP origin the profile-hover "Add payment method" button
      // redirects to. The client composes
      //   `${publicPspUrl}/enroll?return=${origin}/cart`
      // so the PSP (mock-psp-demo) knows where to send the shopper back.
      publicPspUrl: config.PUBLIC_PSP_URL,
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
    if (product.ageRestricted && !isAgeVerified(req.session, product.minAge, userStore)) {
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
      if (!isAgeVerified(req.session, product && product.minAge, userStore)) {
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
    // The PSP PID-presentation test helper moved to
    // examples/mock-psp-demo/tests alongside the routes it covers.
  }

  // ============================================================
  // PSP enrolment moved to examples/mock-psp-demo
  // ============================================================
  //
  // The RP no longer hosts /psp/enroll or /api/psp/*. Those routes live
  // on the standalone Bank of Demo service (psp.theaustraliahack.com).
  // The profile hover's "Add payment method" button redirects to
  // `${PUBLIC_PSP_URL}/enroll?return=${RP}/cart`; the PSP bounces the
  // shopper back to `/cart?pwa=1` after issuance, at which point the
  // PWA capture flow below takes over.

  // ============================================================
  // /cart?pwa=1 return flow
  // ============================================================
  //
  // There used to be a trio of routes here — POST /api/pwa/capture,
  // POST /api/pwa/capture/webhook/:token, GET /api/pwa/capture-status —
  // that ran a "silent" PaymentWalletAttestation presentation after the
  // shopper returned from /psp/enroll via /cart?pwa=1. Two problems:
  //
  //   1. It was architecturally broken. The kickoff never surfaced the
  //      QR / deep-link to the user, so no wallet ever answered the
  //      presentation request and the "Verifying your new payment
  //      method…" banner spun forever.
  //   2. The whole step was non-standards-compliant anyway. In an EUDI
  //      flow the merchant does not discover card metadata between
  //      enrollment and checkout — panLastFour / scheme are surfaced
  //      at checkout via the OID4VP presentation (the
  //      /api/checkout → /api/checkout/webhook path below). Showing a
  //      "Visa ****4242" line on the RP profile hover was an
  //      Apple-Pay-style on-file-card model that doesn't apply to
  //      wallet-bound PWAs.
  //
  // The /cart route still exists (defined further down) because the PSP
  // sends the shopper to `${rp}/cart?pwa=1` after issuance; the client
  // just shows a transient toast and strips the query param.

  // ============================================================
  // Checkout kickoff (Task 17)
  // ============================================================
  //
  // POST /api/checkout bundles the current cart into an order and opens a
  // verifier-api2 session asking the wallet to present a PWA (claims
  // panLastFour + scheme). Task 0 Plan B fallback: verifier-api2's
  // GeneralFlowConfig has no `nonce` field — the library generates a
  // random nonce internally on every session-create. We therefore bind
  // the VP to the order via our own `checkoutByToken` map (keyed by the
  // unguessable token embedded in the webhook URL), not via a wallet-side
  // transaction_data commitment. The checkoutByToken entry holds the
  // txData so the receipt page can render the transaction summary that
  // would have lived in the wallet's kb-JWT transaction_data_hashes had
  // the library supported RFC008.
  //
  // Error semantics:
  //  - empty cart → 400 empty_cart
  //  - verifier-api2 unreachable / rejects → 502 verifier_unavailable
  //
  // Note: there is no "does the user have a payment method" pre-check.
  // The merchant doesn't know what cards a shopper has enrolled — that
  // is discovered at presentation time. If the wallet has no
  // PaymentWalletAttestation it will reject the OID4VP request and the
  // checkout status poll will surface the decline.
  app.post('/api/checkout', async (req, res) => {
    const items = (req.session.cart && req.session.cart.items) || [];
    if (!items.length) return res.status(400).json({ error: 'empty_cart' });
    // Age-gate the order as a whole: the cart-add gate is the first line of
    // defence, but a session that's been through a partial rehydrate (e.g.
    // session.user.sub present but age claim missing) could slip through
    // there too — so enforce again at the order boundary using the same
    // store-aware isAgeVerified check.
    const requiredMinAge = cartMinAge(req.session.cart);
    if (requiredMinAge && !isAgeVerified(req.session, requiredMinAge, userStore)) {
      return res.status(403).json({ error: 'age_verification_required', minAge: requiredMinAge });
    }
    const orderId = 'ORDER-' + crypto.randomUUID();
    const cartSummary = summary(req.session.cart);
    const total = cartSummary.subtotal;
    // Server-only transaction data. Mirrors the shape that would be fed
    // into RFC008 `transaction_data` if the stack supported it — keeping
    // the field names aligned makes a future upgrade a one-line swap.
    const txData = {
      type: 'payment_data',
      credential_ids: ['pwa'],
      payee: 'Oz Bottleshop Pty Ltd',
      amount: String(total),
      currency: 'AUD',
      transaction_ref: orderId,
    };
    const token = crypto.randomBytes(16).toString('hex');
    const webhookSecret = crypto.randomBytes(32).toString('base64url');
    const webhookUrl = `${PUBLIC_URL.replace(/\/$/, '')}/api/checkout/webhook/${token}`;
    const body = {
      flow_type: 'cross_device',
      core_flow: {
        dcql_query: buildPwaCheckoutDcql(),
        signed_request: true,
        notifications: {
          webhook: { url: webhookUrl, bearer_token: webhookSecret },
        },
      },
    };
    try {
      const r = await fetch(
        `${VERIFIER_API2_URL}/verification-session/create?rpId=${encodeURIComponent(RP_ID)}`,
        { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) },
      );
      if (!r.ok) {
        console.warn('[checkout] verifier-api2 session-create failed', r.status);
        return res.status(502).json({ error: 'verifier_unavailable' });
      }
      const session = await r.json();
      const qrCode = session.bootstrapAuthorizationRequestUrl || session.fullAuthorizationRequestUrl;
      registerSessionToken(checkoutByToken, token, {
        orderId,
        total,
        currency: 'AUD',
        txData,
        items: items.slice(),
        webhookSecret,
        completed: false,
      });
      req.session.pendingOrder = { orderId, total, items: items.slice(), token };
      res.json({ orderId, sessionId: session.sessionId, qrCode });
    } catch (err) {
      console.warn('[checkout] session-create error', err.message || err);
      res.status(502).json({ error: 'verifier_unavailable' });
    }
  });

  /**
   * POST /api/checkout/webhook/:token — verifier-api2 callback for the PWA
   * presentation. Bearer-auth with the secret minted at /api/checkout,
   * then on terminal SUCCESSFUL event extract panLastFour+scheme from the
   * presented credential and record the order. The map entry's `completed`
   * flag is the signal for the status poll to mirror into the session.
   *
   * vpDigest is a sha256 over the full presentedCredentials JSON so the
   * receipt has something auditable to show ("verified by wallet X") even
   * though we don't persist the raw VP itself.
   */
  app.post('/api/checkout/webhook/:token', (req, res) => {
    const entry = checkoutByToken.get(req.params.token);
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
      entry.completed = true;
      entry.order = null; // declined — status poll will report this
      return res.json({ ok: true });
    }
    const creds = session.presentedCredentials || {};
    let pwaMeta = { panLastFour: null, scheme: null };
    for (const arr of Object.values(creds)) {
      if (Array.isArray(arr) && arr.length && arr[0] && arr[0].credentialData) {
        const cd = arr[0].credentialData;
        pwaMeta = { panLastFour: cd.panLastFour, scheme: cd.scheme };
        break;
      }
    }
    const vpDigest = crypto
      .createHash('sha256')
      .update(JSON.stringify(creds))
      .digest('hex');
    entry.completed = true;
    entry.order = {
      id: entry.orderId,
      items: entry.items,
      total: entry.total,
      currency: entry.currency || 'AUD',
      pwaMeta,
      transactionRef: entry.orderId,
      approvedAt: Date.now(),
      vpDigest,
    };
    res.json({ ok: true });
  });

  /**
   * GET /api/checkout/status — browser poll. Three terminal states:
   *   - 'completed' : webhook recorded a SUCCESSFUL order; mirror onto
   *                   session + userStore (if user.sub), clear cart +
   *                   pendingOrder, evict the map entry.
   *   - 'pending'   : kickoff happened but no terminal webhook yet OR the
   *                   webhook was non-final.
   *   - 'none'      : no pending order on this session cookie at all.
   * A declined webhook (SUCCESSFUL=false) sets completed=true with
   * order=null; we surface that as 'declined' for the UI (the page can
   * show a retry affordance).
   */
  app.get('/api/checkout/status', (req, res) => {
    const pending = req.session.pendingOrder;
    if (!pending || !pending.token) return res.json({ status: 'none' });
    const entry = checkoutByToken.get(pending.token);
    if (!entry) {
      // Entry evicted (TTL or already mirrored). If session still has the
      // pending record we treat it as completed-already; otherwise none.
      return res.json({ status: 'none' });
    }
    if (!entry.completed) return res.json({ status: 'pending' });
    if (!entry.order) {
      // Declined: clear the pending marker, keep the cart intact so the
      // user can retry with a different payment method.
      delete req.session.pendingOrder;
      checkoutByToken.delete(pending.token);
      return res.json({ status: 'declined' });
    }
    const record = entry.order;
    // Mirror onto the session so the receipt page can read it without a
    // round-trip through userStore (and so anonymous checkouts still work).
    req.session.orders = Array.isArray(req.session.orders)
      ? req.session.orders.concat([record])
      : [record];
    // Persist across sessions when authenticated. Append to any existing
    // orders on the stored profile; the userStore allowlist already
    // includes `orders`.
    if (req.session.user && req.session.user.sub) {
      try {
        const existing = userStore.get(req.session.user.sub);
        const priorOrders = (existing && Array.isArray(existing.orders)) ? existing.orders : [];
        userStore.upsert(Object.assign({}, req.session.user, {
          provider: req.session.provider || req.session.user.provider || 'authop',
          orders: priorOrders.concat([record]),
        }));
      } catch (err) {
        console.warn('[checkout-status] userStore upsert failed:', err.message);
      }
    }
    // Clear cart + pending marker; evict the map entry now that the order
    // is durable in session + store.
    if (req.session.cart) clearCart(req.session.cart);
    delete req.session.pendingOrder;
    checkoutByToken.delete(pending.token);
    res.json({ status: 'completed', orderId: record.id });
  });

  // ============================================================
  // /checkout review page (Task 16)
  // ============================================================
  //
  // Gated on cart non-emptiness: an empty-cart visit redirects home so
  // the user can't land on a review page with nothing to review. The
  // page itself fetches /api/me for {user, cart} in one round trip
  // and renders the order summary + "Pay with …" line client-side.

  app.get('/checkout', (req, res) => {
    const items = (req.session.cart && req.session.cart.items) || [];
    if (!items.length) return res.redirect('/');
    res.sendFile(path.join(__dirname, 'public', 'checkout.html'));
  });

  // /cart is the landing URL after a PSP enrollment round-trip
  // (psp.theaustraliahack.com/enroll returns the shopper to
  // `${returnUrl}?pwa=1`, and the plan-defined returnUrl is
  // `${rp}/cart`). The RP is a single-page app served from `/`, so
  // /cart is just an alias that serves the same index.html — the
  // `?pwa=1` kick-off handler inside the page detects the query on
  // any URL and fires the capture flow.
  app.get('/cart', (_req, res) => {
    res.sendFile(path.join(__dirname, 'public', 'index.html'));
  });

  // ============================================================
  // /order/:id receipt (Task 20)
  // ============================================================
  //
  // Helper: locate an order by id, preferring the current session's order
  // list and falling back to the logged-in user's persisted orders. Returns
  // undefined on miss. Demo-scale O(n) scan; order-history lengths are
  // bounded by session lifetime so this is fine.
  function findOrder(req, id) {
    const sessionOrders = Array.isArray(req.session.orders) ? req.session.orders : [];
    const hit = sessionOrders.find((o) => o && o.id === id);
    if (hit) return hit;
    const sub = req.session && req.session.user && req.session.user.sub;
    if (!sub) return undefined;
    const stored = userStore.get(sub);
    const storedOrders = (stored && Array.isArray(stored.orders)) ? stored.orders : [];
    return storedOrders.find((o) => o && o.id === id);
  }

  /**
   * GET /api/orders/:id — receipt JSON. Reads from req.session.orders
   * first, then falls back to userStore for authenticated users. Returns
   * 404 when neither has a match. The returned shape is the full order
   * record (see checkout webhook in Task 18).
   */
  app.get('/api/orders/:id', (req, res) => {
    const order = findOrder(req, req.params.id);
    if (!order) return res.status(404).json({ error: 'not_found' });
    res.json(order);
  });

  /**
   * GET /order/:id — the receipt page shell. We don't validate :id here
   * because the client immediately fetches /api/orders/:id and can render
   * a "not found" state itself; keeping the shell unconditional avoids
   * a double round-trip and lets receipts survive session rotation as
   * long as the order is in userStore.
   */
  app.get('/order/:id', (_req, res) => {
    res.sendFile(path.join(__dirname, 'public', 'order.html'));
  });

  // ---------------- OIDC login routes (multi-provider) ----------------
  // These are registered unconditionally so `/api/me` always works; the
  // actual login/callback handlers return 503 when the requested provider
  // is not configured.

  /** GET /api/me — current login state + list of enabled providers +
   *  current cart summary + most recent order (Task 21). The cart field
   *  lets the /checkout page render with a single round-trip (user +
   *  line items + total in one shot). We ensure the session has a cart
   *  initialized so summary() works even for a fresh visitor.
   *
   *  `lastOrder` is a projection of the newest order — `{id, currency,
   *  total}` only — not the full record with line items. The profile
   *  hover is the only consumer; keeping the payload narrow avoids
   *  leaking purchase history into every page that just wants user/cart. */
  app.get('/api/me', (req, res) => {
    const providers = enabledProviderNames().map((name) => ({
      name,
      label: OIDC_PROVIDERS[name].label,
      loginPath: name === 'keycloak' ? '/login' : `/login/${name}`,
    }));
    const cart = summary(req.session.cart || emptyCart());
    // Newest order from whichever source has history: session takes
    // priority (anonymous checkouts live there); authenticated users
    // fall back to the persisted userStore record so the hover survives
    // a new browser session.
    const sessionOrders = Array.isArray(req.session.orders) ? req.session.orders : [];
    let ordersSource = sessionOrders;
    if (!ordersSource.length) {
      const sub = req.session && req.session.user && req.session.user.sub;
      if (sub) {
        const stored = userStore.get(sub);
        if (stored && Array.isArray(stored.orders)) ordersSource = stored.orders;
      }
    }
    const newest = ordersSource.length ? ordersSource[ordersSource.length - 1] : null;
    const lastOrder = newest
      ? { id: newest.id, currency: newest.currency, total: newest.total }
      : null;
    res.json({
      oidcEnabled: OIDC_ENABLED,
      providers,
      user: (req.session && req.session.user) || null,
      activeProvider: (req.session && req.session.provider) || null,
      isAdmin: isAdmin(req.session),
      cart,
      lastOrder,
    });
  });

  // Admin surface. All routes below require isAdmin(session). A non-admin
  // call returns 403 rather than 404 so an admin who is logged out sees a
  // useful error instead of a confusing "page not found". The whole admin
  // subtree is gated by a single middleware to keep the check in one place.
  const requireAdmin = (req, res, next) => {
    if (!isAdmin(req.session)) return res.status(403).json({ error: 'admin_required' });
    next();
  };

  /**
   * GET /admin — serves the admin dashboard HTML. Clients render the user
   * table + actions after fetching /api/admin/users. The HTML itself is
   * not secret (no auth on the static asset); it just fails closed when
   * calling the gated API.
   */
  app.get('/admin', (req, res) => {
    if (!isAdmin(req.session)) return res.redirect('/');
    res.sendFile(path.join(__dirname, 'public', 'admin.html'));
  });

  /**
   * GET /api/admin/users — list every stored profile, newest-login first.
   * Returns full records including email (keycloak profiles) and orders.
   * Replaces the earlier ungated /api/users debug endpoint.
   */
  app.get('/api/admin/users', requireAdmin, (req, res) => {
    res.json({ count: userStore.count(), users: userStore.list() });
  });

  /**
   * DELETE /api/admin/users/:sub — purge a profile + its orders. Admin
   * cannot delete their own record (easy foot-gun); UI should gray out
   * that row as well.
   */
  app.delete('/api/admin/users/:sub', requireAdmin, (req, res) => {
    const adminSub = req.session && req.session.user && req.session.user.sub;
    if (req.params.sub === adminSub) {
      return res.status(400).json({ error: 'cannot_delete_self' });
    }
    const removed = userStore.remove(req.params.sub);
    if (!removed) return res.status(404).json({ error: 'unknown_user' });
    res.json({ ok: true, sub: req.params.sub });
  });

  /**
   * PATCH /api/admin/users/:sub { kyc_verified?, age_over_18?, age_over_21? }
   *
   * Writable fields are the three verification booleans. Anything else in
   * the body is silently stripped (userStore.adminUpdate enforces). Returns
   * 404 on unknown sub, 200 + the saved record otherwise.
   */
  app.patch('/api/admin/users/:sub', requireAdmin, (req, res) => {
    const saved = userStore.adminUpdate(req.params.sub, req.body || {});
    if (!saved) return res.status(404).json({ error: 'unknown_user' });
    res.json(saved);
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
module.exports = { createApp, config, isAgeVerified, cartMinAge };
