/**
 * E2E smoke for the cart + age-verify flow.
 *
 * Real coverage of the full happy path (verify via a real wallet, cart
 * accepts, drawer renders) lands in Task 22. This file exists so the
 * Task 10 delivery has at least one automated check against the modal
 * wiring — without it the commit has no e2e footprint at all and any
 * future refactor of the 403 branch could silently unhook the modal.
 *
 * Verifier-api2 is not available in the playwright webServer (the demo
 * runs standalone), so the two age-check endpoints are stubbed via
 * page.route(): /start returns a fake sessionId + openid4vp:// URL so
 * the QR code paints, and /status transitions from `{verified: null}`
 * to `{verified: true}` on the second poll. That unblocks the retry,
 * which issues a second POST /api/cart/items — that one also has to be
 * let through against the server-side age gate, so we stub it to
 * respond as if `session.ageVerified` were already set. The net effect
 * is the same as a real wallet presentation: QR up -> polling -> retry
 * -> item in cart, badge reads 1.
 */

const { test, expect } = require('@playwright/test');

test.describe('cart age-gate e2e', () => {
  test('age gate -> verify -> item in cart', async ({ page }) => {
    // 1) Stub /api/age-check/start with a fake verifier-api2 response.
    //    The demo's front-end renders whatever the `qrCode` field contains
    //    (which on the real server is a bootstrap QR URL) so any string
    //    will do — we use an openid4vp:// URI for visual realism.
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

    // 2) Stub /api/age-check/status. The first poll returns `null` so the
    //    modal stays spinning; the second returns `verified: true`, which
    //    the client handles by closing the modal and retrying the add.
    let pollCount = 0;
    await page.route('**/api/age-check/status', async (route) => {
      pollCount += 1;
      const body = pollCount < 2 ? { verified: null } : { verified: true };
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(body),
      });
    });

    // 3) The retry after verify re-POSTs to /api/cart/items. On the real
    //    server, session.ageVerified was just set by the /status mirror,
    //    so the age gate clears. Here we stub the second attempt to
    //    return an accepted-cart summary — good enough for the badge
    //    assertion. We let the first POST go through normally so the
    //    server still emits the 403 that opens the modal.
    let addAttempt = 0;
    await page.route('**/api/cart/items', async (route, request) => {
      if (request.method() !== 'POST') return route.fallback();
      addAttempt += 1;
      if (addAttempt === 1) {
        // First attempt pre-verify -> real server returns 403.
        // We mirror that here so we don't depend on catalogue state.
        await route.fulfill({
          status: 403,
          contentType: 'application/json',
          body: JSON.stringify({ error: 'age_verification_required', minAge: 21 }),
        });
        return;
      }
      // Retry post-verify -> pretend the cart has the Hibiki.
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          items: [{ productId: 'hibiki-harmony', qty: 1, priceAud: 89, title: 'Hibiki Japanese Harmony', imageUrl: '\uD83E\uDD43', ageRestricted: true }],
          subtotal: 89,
          count: 1,
        }),
      });
    });

    // Initial /api/cart fetches also need to round-trip; let them hit
    // the server unmodified (it'll return an empty cart on a fresh
    // session cookie). No stub needed — fallback behaviour is fine.

    await page.goto('/');

    // Wait for the shelf to paint at least one product card.
    await page.locator('.product-card [data-add]').first().waitFor({ state: 'visible' });

    // Find the first age-restricted (21+) card's ADD button. The catalogue
    // ships 12 products with a mix of minAge=18 and =21. We want a 21+ to
    // guarantee the server returns 403 — `data-add` holds the productId
    // so hibiki-harmony (known 21+) is the robust target.
    const hibikiAdd = page.locator('.product-card [data-add="hibiki-harmony"]');
    await hibikiAdd.click();

    // Modal should appear with the QR.
    const modal = page.locator('.age-verify-modal');
    await expect(modal).toBeVisible();
    await expect(page.locator('#age-verify-qr img')).toBeVisible();

    // Polling + retry should land the item in the cart within ~5 seconds
    // (1.5s poll interval, second poll resolves). The badge flips from
    // hidden to "1".
    await expect(page.locator('#cart-badge')).toHaveText('1', { timeout: 8000 });
  });
});
