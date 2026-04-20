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

      const url = client.authorizationUrl({
        scope: 'openid profile email',
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

      const userProfile = {
        sub: claims.sub,
        email: claims.email,
        name: claims.name || claims.preferred_username,
        given_name: claims.given_name,
        family_name: claims.family_name,
        birth_date: claims.birth_date || claims.birthdate,
        nationality: claims.nationality,
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
