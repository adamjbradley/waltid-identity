/**
 * E2E — anonymous age-gate → cart (rp-cart-dpc Task 22).
 *
 * End-to-end coverage of the "shopper without an OIDC session tries to add
 * an age-restricted item" path. Sibling to cart.spec.js (the Task 10 smoke
 * — that one keeps the cart-items POST stubbed for isolation; this one
 * lets the real server-side age gate fire and transition through the
 * /_test/session helper so the retry POST round-trips for real).
 *
 * Wire:
 *   1. /api/age-check/start          — stubbed (no verifier-api2 in the
 *                                       Playwright webServer).
 *   2. /api/age-check/status (1st)   — stubbed {verified:null}  ; modal
 *                                       stays spinning.
 *   3. /_test/session (ageVerified)  — server session gets flipped before
 *                                       the second poll returns.
 *   4. /api/age-check/status (2nd+)  — stubbed {verified:true}  ; client
 *                                       closes the modal + retries add.
 *   5. POST /api/cart/items (retry)  — real server route, succeeds because
 *                                       session.ageVerified is now true.
 *
 * The first POST /api/cart/items is NOT stubbed — we rely on the real
 * server's 403 for the age gate, which is the behaviour under test.
 */

const { test, expect } = require('@playwright/test');

test.describe('rp-cart-dpc age-gate e2e', () => {
  test('anonymous shopper: add 21+ → age modal → verify → item in cart', async ({ page }) => {
    // 1) Stub verifier-api2 bootstrap. Any truthy qrCode string works —
    //    the client wraps it into a QR image and a deep-link anchor.
    await page.route('**/api/age-check/start', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          sessionId: 's1',
          qrCode: 'openid4vp://authorize?fake=1',
        }),
      });
    });

    // 2) Status polling. First poll: still waiting. Second+ poll: flip the
    //    real server session state via /_test/session so the retry POST
    //    to /api/cart/items will clear the age gate, THEN report verified.
    //    Order matters — if we reported verified before flipping the
    //    session, the retry could race ahead of /_test/session and 403
    //    again.
    let pollCount = 0;
    await page.route('**/api/age-check/status', async (route) => {
      pollCount += 1;
      if (pollCount < 2) {
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({ verified: null }),
        });
        return;
      }
      // page.request shares cookies with the browser, so this flips the
      // same session the /api/cart/items retry will read from.
      await page.request.post('/_test/session', { data: { ageVerified: true } });
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ verified: true }),
      });
    });

    await page.goto('/');

    // Wait for the shelf to paint at least one product card.
    await page.locator('.product-card [data-add]').first().waitFor({ state: 'visible' });

    // Hibiki is 21+ — guarantees a server-side 403 even on a fresh session.
    await page.locator('.product-card [data-add="hibiki-harmony"]').click();

    // Modal opens with the stubbed QR.
    const modal = page.locator('.age-verify-modal');
    await expect(modal).toBeVisible();
    await expect(page.locator('#age-verify-qr img')).toBeVisible();

    // After the second poll resolves verified:true the modal closes and
    // addToCart retries. The badge flips from hidden to "1" once the
    // retry POST returns a non-empty summary. Generous timeout because
    // the poll interval is 1.5s and we need two ticks + the retry.
    await expect(page.locator('#cart-badge')).toHaveText('1', { timeout: 8000 });

    // Drawer should have auto-opened on the successful add (shopify-style
    // side-cart confirmation, per addToCart()). Item row renders with the
    // catalogue title.
    await expect(page.locator('#cart-drawer')).toHaveClass(/open/);
    await expect(page.locator('.cart-item-title')).toContainText('Hibiki');
  });
});
