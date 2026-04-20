const { test, expect } = require('@playwright/test');

/**
 * End-to-end tests for the OIDC RP login flow on the widget demo.
 *
 * Two modes, toggled by the presence of OIDC env vars on the server:
 *
 * 1. OIDC disabled (no KEYCLOAK_* env vars):
 *    - /api/me returns {oidcEnabled: false, user: null}
 *    - top-bar auth-status element renders nothing
 *    - /login returns 503
 *    - existing wallet-verify UI is unaffected
 *
 * 2. OIDC enabled:
 *    - /api/me returns {oidcEnabled: true, user: null} when not authed
 *    - top-bar shows "Login with Keycloak" link when not authed
 *    - /login redirects (302) to the configured issuer's authorize endpoint
 *
 * Tests that require a complete round-trip through a live OP are guarded
 * behind TEST_OIDC_FLOW=true and need the docker compose stack up with
 * Keycloak (or auth-op) reachable.
 */

test.describe('OIDC RP login — API surface', () => {
  test('/api/me returns the expected shape', async ({ request }) => {
    const res = await request.get('/api/me');
    expect(res.status()).toBe(200);
    const body = await res.json();
    expect(body).toHaveProperty('oidcEnabled');
    expect(typeof body.oidcEnabled).toBe('boolean');
    expect(body).toHaveProperty('user');
    // user is null when not authenticated; object when logged in
    if (body.user !== null) {
      expect(body.user).toHaveProperty('sub');
    }
  });

  test('/login behaviour matches OIDC configuration', async ({ request }) => {
    const me = await (await request.get('/api/me')).json();
    const res = await request.get('/login', { maxRedirects: 0 });

    if (me.oidcEnabled) {
      // Enabled: Authorization Code flow kicks off with a 302 to the issuer.
      expect(res.status()).toBe(302);
      const location = res.headers()['location'];
      expect(location, '/login should redirect to the issuer').toBeTruthy();
      // Authorize URL must carry PKCE + our client_id + our registered redirect.
      expect(location).toContain('response_type=code');
      expect(location).toContain('code_challenge_method=S256');
      expect(location).toContain('client_id=');
      expect(location).toContain('redirect_uri=');
    } else {
      // Disabled: helpful 503 rather than a cryptic 500 or crash.
      expect(res.status()).toBe(503);
    }
  });
});

test.describe('OIDC RP login — top bar UI', () => {
  test.beforeEach(async ({ page }) => {
    await page.goto('/');
  });

  test('auth-status element exists in the top bar', async ({ page }) => {
    const el = page.locator('#auth-status');
    await expect(el).toHaveCount(1);
  });

  test('renders login UI when OIDC enabled and user not authed', async ({ page, request }) => {
    const me = await (await request.get('/api/me')).json();
    test.skip(!me.oidcEnabled, 'OIDC not configured on server');
    test.skip(!!me.user, 'user is already signed in — clear session and retry');

    // Login link should appear inside #auth-status
    const loginLink = page.locator('#auth-status a', { hasText: 'Login with Keycloak' });
    await expect(loginLink).toBeVisible();
    await expect(loginLink).toHaveAttribute('href', '/login');
  });

  test('renders nothing when OIDC disabled', async ({ page, request }) => {
    const me = await (await request.get('/api/me')).json();
    test.skip(me.oidcEnabled, 'OIDC is configured; this test covers the disabled case');

    // auth-status should have no children when oidcEnabled is false
    const children = await page.locator('#auth-status > *').count();
    expect(children).toBe(0);
  });
});

test.describe('Existing wallet-verify UI is not regressed', () => {
  test('Widget page loads alongside OIDC top bar', async ({ page }) => {
    await page.goto('/');
    // Sanity: the Majestic storefront header logo is present.
    await expect(page.getByRole('link', { name: 'MAJESTIC' })).toBeVisible();
    // The OIDC top-bar slot exists *alongside* the existing content.
    await expect(page.locator('#auth-status')).toHaveCount(1);
  });

  test('/api/token still issues client tokens for the widget', async ({ request }) => {
    const res = await request.get('/api/token');
    // Will be 200 when verify-api reachable, or 5xx when not — both are acceptable
    // here; the point is /api/token routing is intact and didn't get shadowed.
    expect([200, 500, 502, 503]).toContain(res.status());
    if (res.status() === 200) {
      const body = await res.json();
      expect(body).toHaveProperty('clientToken');
    }
  });
});

/**
 * Full round-trip test. Gated on TEST_OIDC_FLOW=true because it requires
 * a reachable OP (Keycloak or auth-op), a valid user, and browser cookies
 * that can round-trip through a real identity provider.
 */
test.describe('OIDC RP login — full round trip @integration', () => {
  test.skip(({}, testInfo) => !process.env.TEST_OIDC_FLOW,
    'set TEST_OIDC_FLOW=true and start docker compose identity profile to run');

  test('login → callback → /api/me reports authenticated user', async ({ page }) => {
    const username = process.env.TEST_OIDC_USERNAME || 'adam_j_bradley';
    const password = process.env.TEST_OIDC_PASSWORD;
    test.skip(!password, 'set TEST_OIDC_PASSWORD');

    await page.goto('/login');
    await page.waitForURL(/\/realms\/|\/login\/realm/);

    // Keycloak-style login form (also works against auth-op's realm picker
    // when an OIDC realm is selected and it bounces to Keycloak)
    await page.fill('input[name="username"]', username);
    await page.fill('input[name="password"]', password);
    await page.click('input[type="submit"], button[type="submit"]');

    await page.waitForURL('**/');  // back at the RP
    const me = await (await page.request.get('/api/me')).json();
    expect(me.oidcEnabled).toBe(true);
    expect(me.user).not.toBeNull();
    expect(me.user.sub).toBeTruthy();
  });
});
