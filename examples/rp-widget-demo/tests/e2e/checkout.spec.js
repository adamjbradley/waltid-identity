/**
 * E2E — happy-path checkout (rp-cart-dpc Task 23).
 *
 * Walks a signed-in shopper from the storefront through the full
 * cart → checkout → pay → receipt flow without touching verifier-api2
 * or the PSP. Session is pre-hydrated via /_test/session so the user has
 * a 21+ claim (bypasses the age modal) and a paymentMethod on file
 * (unlocks the Pay button on /checkout).
 *
 * Wire:
 *   1. POST /_test/session           — seed user + paymentMethod.
 *   2. POST /api/cart/items          — real route, clears age gate via
 *                                       user.age_over_21.
 *   3. Cart drawer → Checkout link   — navigates to /checkout (real route,
 *                                       gated on non-empty cart).
 *   4. POST /api/checkout            — stubbed (no verifier-api2 in the
 *                                       Playwright webServer).
 *   5. GET  /api/checkout/status     — stubbed: first 'pending', then
 *                                       'completed' with the canned orderId.
 *   6. GET  /api/orders/ORDER-test   — stubbed with a full receipt record
 *                                       so /order/:id renders panLastFour
 *                                       + scheme.
 *
 * We don't involve the real checkout webhook flow (which would need a
 * verifier-api2 round-trip + a shared bearer secret) — the side effect
 * of completed status is simulated by stubbing /api/orders directly.
 */

const { test, expect } = require('@playwright/test');

const ORDER_ID = 'ORDER-test';

test.describe('rp-cart-dpc checkout e2e', () => {
  test('signed-in 21+ shopper: cart → /checkout → pay → receipt', async ({ page }) => {
    // Stub the SDK + token endpoints. The widget SDK isn't under test here
    // but its loading toast is absolutely positioned bottom-right and
    // overlaps the cart drawer's Checkout button when the fetch hangs
    // (no verify-api in the Playwright webServer). Resolving it quickly
    // lets #sdk-loading hide so the checkout click lands.
    await page.route('**/widget/v1/sdk.js', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/javascript',
        body: 'window.WaltVerify = { init: function(){}, verifyAge: function(){}, verify: function(){} };',
      });
    });
    await page.route('**/api/token', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          clientToken: 'ct_mock_token_123',
          expiresAt: new Date(Date.now() + 900000).toISOString(),
        }),
      });
    });

    // 1) Seed server session: user sub + age_over_21 (clears the age gate
    //    on the first add) + a paymentMethod (unlocks the Pay button).
    //    paymentMethod fields mirror the shape written by /api/pwa/capture
    //    so the checkout + receipt render logic is exercised as-in-prod.
    await page.request.post('/_test/session', {
      data: {
        user: {
          sub: 'test-sub',
          age_over_21: true,
          paymentMethod: {
            panLastFour: '4242',
            scheme: 'Visa',
            payeeName: 'Bank of Demo',
          },
        },
      },
    });

    // 2) Stub /api/checkout so we don't hit verifier-api2. Mirror the
    //    response shape from server.js — {orderId, sessionId, qrCode}.
    await page.route('**/api/checkout', async (route, request) => {
      if (request.method() !== 'POST') return route.fallback();
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          orderId: ORDER_ID,
          sessionId: 'c1',
          qrCode: 'openid4vp://authorize?fake-pwa=1',
        }),
      });
    });

    // 3) Stub /api/checkout/status — first poll pending, second+ completed.
    //    The real server would transition after a webhook from verifier-api2
    //    with presentedCredentials; here we skip straight to 'completed'
    //    since the receipt page fetches /api/orders/:id (also stubbed).
    let statusPolls = 0;
    await page.route('**/api/checkout/status', async (route) => {
      statusPolls += 1;
      const body = statusPolls < 2
        ? { status: 'pending' }
        : { status: 'completed', orderId: ORDER_ID };
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(body),
      });
    });

    // 4) Stub /api/orders/:id — the receipt page fetches this on load.
    //    Shape mirrors the `entry.order` record in the checkout webhook
    //    (see server.js POST /api/checkout/webhook/:token) so the
    //    rendering path on order.html exercises the real fields.
    await page.route('**/api/orders/' + ORDER_ID, async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          id: ORDER_ID,
          items: [{
            productId: 'hibiki-harmony',
            qty: 1,
            priceAud: 89,
            title: 'Hibiki Japanese Harmony',
            imageUrl: '\uD83E\uDD43',
            ageRestricted: true,
          }],
          total: 89,
          currency: 'AUD',
          pwaMeta: { panLastFour: '4242', scheme: 'Visa' },
          transactionRef: ORDER_ID,
          approvedAt: Date.UTC(2026, 3, 21, 10, 0),
          vpDigest: 'deadbeef'.repeat(8),
        }),
      });
    });

    await page.goto('/');

    // 5) Add first 21+ product. user.age_over_21 clears the gate, so no
    //    modal — the POST returns 200 and the drawer auto-opens.
    await page.locator('.product-card [data-add]').first().waitFor({ state: 'visible' });
    await page.locator('.product-card [data-add="hibiki-harmony"]').click();

    // Badge flips to 1 once the server ack comes back.
    await expect(page.locator('#cart-badge')).toHaveText('1', { timeout: 5000 });

    // Drawer auto-opens on add; click Checkout → /checkout.
    await expect(page.locator('#cart-drawer')).toHaveClass(/open/);
    await page.locator('#cart-checkout-btn').click();
    await page.waitForURL('**/checkout');

    // 6) /checkout should have rendered payment method (Visa ****4242) and
    //    enabled the Pay button. Click it.
    const payBtn = page.locator('#pay-btn');
    await expect(payBtn).toBeEnabled();
    await payBtn.click();

    // 7) Client polls /api/checkout/status every 1.5s; on second tick it
    //    sees 'completed' and navigates to /order/:id. Generous timeout
    //    to absorb one poll cycle + page nav.
    await page.waitForURL('**/order/' + ORDER_ID, { timeout: 8000 });

    // 8) Receipt page renders: scheme, panLastFour, and the order id as
    //    transaction ref. The order.html renders "Visa ****4242 at …".
    await expect(page.locator('#payment-line')).toContainText('Visa');
    await expect(page.locator('#payment-line')).toContainText('4242');
    await expect(page.locator('#transaction-ref')).toHaveText(ORDER_ID);
  });
});
