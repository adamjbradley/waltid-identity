import { test, expect } from '@playwright/test';

/**
 * End-to-end tests for the OIDC RP login flow in the Next.js demo.
 *
 * The landing page (`/`) offers two independent entry points:
 *   1. "Sign in with Keycloak" — kicks off the Auth.js v5 Keycloak provider
 *   2. "Start age verification" — links to /checkout (wallet-based, unchanged)
 *
 * After successful OIDC login the user lands on `/login` which renders
 * their profile from the server-side `auth()` session.
 *
 * Tests that need a real OP round-trip are grouped under @integration and
 * skipped unless TEST_OIDC_FLOW=true.
 */

test.describe('Landing page — two entry points', () => {
  test.beforeEach(async ({ page }) => {
    await page.goto('/');
  });

  test('renders both OIDC and wallet verification cards', async ({ page }) => {
    await expect(page.getByRole('heading', { name: 'Sign in with Keycloak' })).toBeVisible();
    await expect(page.getByRole('heading', { name: 'Verify with wallet' })).toBeVisible();
  });

  test('OIDC card has a Keycloak sign-in form', async ({ page }) => {
    const signInForm = page.locator('form').filter({ hasText: 'Sign in with Keycloak' });
    await expect(signInForm.locator('button[type="submit"]')).toBeVisible();
  });

  test('Wallet card links to /checkout', async ({ page }) => {
    const walletLink = page.getByRole('link', { name: /age verification/i });
    await expect(walletLink).toHaveAttribute('href', '/checkout');
  });

  test('domain + tagline are shown', async ({ page }) => {
    await expect(page.getByRole('heading', { name: 'rp.theaustraliahack.com' })).toBeVisible();
    await expect(page.getByText(/two independent identity paths/i)).toBeVisible();
  });
});

test.describe('Auth.js endpoints respond', () => {
  // These tests are tolerant of a dev server that was started without
  // AUTH_SECRET / AUTH_KEYCLOAK_* — Auth.js then returns 500 rather than
  // routing. We assert the route is wired (not a 404) and shape-check only
  // on the happy-path 200 response.

  test('/api/auth/session is wired', async ({ request }) => {
    const res = await request.get('/api/auth/session');
    expect(res.status(), 'route must exist').not.toBe(404);
    if (res.status() === 200) {
      const body = await res.json();
      expect(typeof body).toBe('object');
    }
  });

  test('/api/auth/providers is wired and lists keycloak when configured', async ({ request }) => {
    const res = await request.get('/api/auth/providers');
    expect(res.status(), 'route must exist').not.toBe(404);
    if (res.status() === 200) {
      const body = await res.json();
      expect(body).toHaveProperty('keycloak');
      expect(body.keycloak).toHaveProperty('id', 'keycloak');
    }
  });
});

test.describe('/login requires authentication', () => {
  test('unauthenticated request is redirected to signin', async ({ request }) => {
    const res = await request.get('/login', { maxRedirects: 0 });
    // Next.js redirect() yields either 302 or 307; signin landing is served
    // under /api/auth/signin — the redirect target must reference it.
    expect([302, 307]).toContain(res.status());
    const loc = res.headers()['location'] ?? '';
    expect(loc).toContain('/api/auth/signin');
  });
});

test.describe('/checkout (wallet path) — regression', () => {
  test.beforeEach(async ({ page }) => {
    await page.goto('/checkout');
  });

  test('shows the wallet age-verification UI unchanged', async ({ page }) => {
    await expect(page.locator('h1')).toContainText('Age Verification Demo');
    await expect(page.getByText('Premium Wine Selection')).toBeVisible();
    await expect(page.getByRole('button', { name: 'Verify Age to Continue' })).toBeVisible();
  });
});

/**
 * Full round-trip against a live OP. Requires:
 *   - docker compose identity profile (Keycloak) or auth-op stack running
 *   - AUTH_KEYCLOAK_ISSUER pointing at the OP
 *   - a user registered in the OP with TEST_OIDC_USERNAME / TEST_OIDC_PASSWORD
 */
test.describe('OIDC RP login — full round trip @integration', () => {
  test.skip(({}, testInfo) => !process.env.TEST_OIDC_FLOW,
    'set TEST_OIDC_FLOW=true and bring up the identity stack to run');

  test('signin → callback → /login shows authenticated profile', async ({ page }) => {
    const username = process.env.TEST_OIDC_USERNAME || 'adam_j_bradley';
    const password = process.env.TEST_OIDC_PASSWORD;
    test.skip(!password, 'set TEST_OIDC_PASSWORD');

    await page.goto('/');
    await page.locator('form').filter({ hasText: 'Sign in with Keycloak' }).locator('button').click();

    // Follow Auth.js → Keycloak authorize page
    await page.waitForURL(/\/protocol\/openid-connect\/auth|\/login\/realm/);

    await page.fill('input[name="username"]', username);
    await page.fill('input[name="password"]', password);
    await page.click('input[type="submit"], button[type="submit"]');

    // Back at the RP on /login (post-auth landing)
    await page.waitForURL('**/login');
    await expect(page.getByRole('heading', { name: 'Signed in' })).toBeVisible();
    // Email or name should be shown — both are rendered when available
    const userInfo = page.locator('dd');
    await expect(userInfo.first()).toBeVisible();
  });

  test('sign out clears session and lands on /', async ({ page, context }) => {
    const username = process.env.TEST_OIDC_USERNAME || 'adam_j_bradley';
    const password = process.env.TEST_OIDC_PASSWORD;
    test.skip(!password, 'set TEST_OIDC_PASSWORD');

    // Log in first
    await page.goto('/');
    await page.locator('form').filter({ hasText: 'Sign in with Keycloak' }).locator('button').click();
    await page.waitForURL(/\/protocol\/openid-connect\/auth|\/login\/realm/);
    await page.fill('input[name="username"]', username);
    await page.fill('input[name="password"]', password);
    await page.click('input[type="submit"], button[type="submit"]');
    await page.waitForURL('**/login');

    // Sign out from the login page
    await page.getByRole('button', { name: 'Sign out' }).click();
    await page.waitForURL('**/');

    // Session endpoint must report unauthenticated afterward
    const res = await page.request.get('/api/auth/session');
    const body = await res.json();
    expect(body.user).toBeFalsy();
  });
});
