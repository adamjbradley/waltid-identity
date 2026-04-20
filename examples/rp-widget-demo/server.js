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
const session = require('express-session');
const path = require('path');
const { Issuer, generators } = require('openid-client');
const { UserStore } = require('./userStore');
const { CATALOGUE, getProduct } = require('./catalogue');
const { emptyCart, summary, addItem, setQty, removeItem, clearCart } = require('./cart');

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
const RP_ID = process.env.RP_ID || '';
const RP_CLIENT_ID = process.env.RP_CLIENT_ID || '';
const RP_DOMAIN = process.env.RP_DOMAIN || '';

// Verifier API2 internal URL (for proxying verification-session responses from wallets)
const VERIFIER_API2_URL = process.env.VERIFIER_API2_URL || 'http://verifier-api2:7004';

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
const USER_STORE_FILE = process.env.USER_STORE_FILE || path.join(__dirname, 'data', 'users.json');
const userStore = new UserStore(USER_STORE_FILE);
console.log(`[userStore] loaded ${userStore.count()} profile(s) from ${USER_STORE_FILE}`);

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

  /**
   * GET /api/cart
   *
   * Returns the current session cart as a wire-shaped summary. Initialises
   * an empty cart on first call so every subsequent handler can rely on
   * `req.session.cart` existing.
   */
  app.get('/api/cart', (req, res) => {
    req.session.cart = req.session.cart || emptyCart();
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
    if (product.ageRestricted && !isAgeVerified(req.session, product.minAge)) {
      return res.status(403).json({ error: 'age_verification_required', minAge: product.minAge });
    }
    req.session.cart = req.session.cart || emptyCart();
    addItem(req.session.cart, product, req.body.qty || 1);
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
    req.session.cart = req.session.cart || emptyCart();
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
    req.session.cart = req.session.cart || emptyCart();
    removeItem(req.session.cart, req.params.productId);
    res.json(summary(req.session.cart));
  });

  app.post('/api/cart/clear', (req, res) => {
    req.session.cart = req.session.cart || emptyCart();
    clearCart(req.session.cart);
    res.json(summary(req.session.cart));
  });

  // Dev-only session hydration helper for tests. Lets supertest seed
  // `ageVerified` / `user` without round-tripping a real OIDC login.
  // Guarded on NODE_ENV to keep it out of production builds.
  if (process.env.NODE_ENV !== 'production') {
    app.post('/_test/session', (req, res) => {
      Object.assign(req.session, req.body);
      res.json({ ok: true });
    });
  }

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
