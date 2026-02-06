import { test, expect } from '@playwright/test';

/**
 * End-to-end tests for the Age Verification Checkout Demo.
 *
 * These tests verify the complete user flow from viewing a product
 * to completing age verification using the Verify API.
 */

test.describe('Checkout Page', () => {
  test.beforeEach(async ({ page }) => {
    await page.goto('/');
  });

  test('displays page title and product', async ({ page }) => {
    // Check page title
    await expect(page.locator('h1')).toContainText('Age Verification Demo');

    // Check product is displayed
    await expect(page.locator('text=Premium Wine Selection')).toBeVisible();
    await expect(page.locator('text=$49.99')).toBeVisible();
    await expect(page.locator('text=Age-restricted product')).toBeVisible();
  });

  test('displays age verification required message', async ({ page }) => {
    await expect(page.locator('text=Age Verification Required')).toBeVisible();
    await expect(page.locator('text=Verify your age using your digital wallet')).toBeVisible();
  });

  test('has verify age button in idle state', async ({ page }) => {
    const button = page.locator('button:has-text("Verify Age to Continue")');
    await expect(button).toBeVisible();
    await expect(button).toBeEnabled();
  });

  test('displays footer with branding', async ({ page }) => {
    await expect(page.locator('text=Powered by walt.id Verify API')).toBeVisible();
  });
});

test.describe('Verification Flow - Loading State', () => {
  test('shows loading spinner when verification starts', async ({ page }) => {
    // Mock the API to delay response
    await page.route('**/api/verify', async (route) => {
      await new Promise(resolve => setTimeout(resolve, 1000));
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          sessionId: 'vs_test123',
          qrCodeData: 'openid4vp://authorize?request_uri=https://example.com/request',
          deepLink: 'eudi-openid4vp://authorize?request_uri=https://example.com/request',
          expiresAt: new Date(Date.now() + 300000).toISOString(),
        }),
      });
    });

    await page.goto('/');

    // Click verify button
    await page.click('button:has-text("Verify Age to Continue")');

    // Should show loading state
    await expect(page.locator('text=Starting verification...')).toBeVisible();
    await expect(page.locator('.animate-spin')).toBeVisible();
  });
});

test.describe('Verification Flow - QR Code Display', () => {
  test.beforeEach(async ({ page }) => {
    // Mock the verify API
    await page.route('**/api/verify', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          sessionId: 'vs_test123abc',
          qrCodeData: 'openid4vp://authorize?client_id=verify-api&request_uri=https://verify.example.com/request/123',
          deepLink: 'eudi-openid4vp://authorize?request_uri=https://verify.example.com/request/123',
          expiresAt: new Date(Date.now() + 300000).toISOString(),
        }),
      });
    });

    // Mock session status to stay pending
    await page.route('**/api/sessions/**', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          status: 'pending',
        }),
      });
    });
  });

  test('displays QR code after starting verification (desktop)', async ({ page }) => {
    await page.goto('/');

    // Start verification
    await page.click('button:has-text("Verify Age to Continue")');

    // Wait for QR code section
    await expect(page.locator('text=Scan to Verify')).toBeVisible();

    // QR code should be rendered (SVG element from qrcode.react)
    await expect(page.locator('svg[viewBox]')).toBeVisible();

    // Instructions should be visible
    await expect(page.locator('text=Scan this QR code with your wallet app')).toBeVisible();
  });

  test('displays deep link option on desktop', async ({ page }) => {
    await page.goto('/');
    await page.click('button:has-text("Verify Age to Continue")');

    // Should show "Open in wallet app" link
    await expect(page.locator('text=Open in wallet app')).toBeVisible();
  });

  test('shows waiting indicator while pending', async ({ page }) => {
    await page.goto('/');
    await page.click('button:has-text("Verify Age to Continue")');

    await expect(page.locator('text=Waiting for verification...')).toBeVisible();
  });

  test('has cancel button during verification', async ({ page }) => {
    await page.goto('/');
    await page.click('button:has-text("Verify Age to Continue")');

    const cancelButton = page.locator('button:has-text("Cancel")');
    await expect(cancelButton).toBeVisible();
  });

  test('cancel button returns to idle state', async ({ page }) => {
    await page.goto('/');
    await page.click('button:has-text("Verify Age to Continue")');

    // Wait for QR code
    await expect(page.locator('text=Scan to Verify')).toBeVisible();

    // Click cancel
    await page.click('button:has-text("Cancel")');

    // Should return to idle state
    await expect(page.locator('text=Age Verification Required')).toBeVisible();
    await expect(page.locator('button:has-text("Verify Age to Continue")')).toBeVisible();
  });
});

test.describe('Verification Flow - Mobile', () => {
  test.use({ userAgent: 'Mozilla/5.0 (iPhone; CPU iPhone OS 14_0 like Mac OS X) AppleWebKit/605.1.15' });

  test('shows "Open in Wallet App" button on mobile', async ({ page }) => {
    // Mock the verify API
    await page.route('**/api/verify', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          sessionId: 'vs_mobile123',
          qrCodeData: 'openid4vp://authorize?request_uri=https://verify.example.com/request/456',
          deepLink: 'eudi-openid4vp://authorize?request_uri=https://verify.example.com/request/456',
          expiresAt: new Date(Date.now() + 300000).toISOString(),
        }),
      });
    });

    // Mock session status
    await page.route('**/api/sessions/**', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ status: 'pending' }),
      });
    });

    await page.goto('/');
    await page.click('button:has-text("Verify Age to Continue")');

    // On mobile, should show "Open in Wallet App" button prominently
    await expect(page.locator('a:has-text("Open in Wallet App")')).toBeVisible();
  });
});

test.describe('Verification Flow - Success', () => {
  test('displays success state when verification completes', async ({ page }) => {
    let pollCount = 0;

    // Mock the verify API
    await page.route('**/api/verify', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          sessionId: 'vs_success123',
          qrCodeData: 'openid4vp://authorize?request_uri=https://verify.example.com/request/789',
          deepLink: 'eudi-openid4vp://authorize?request_uri=https://verify.example.com/request/789',
          expiresAt: new Date(Date.now() + 300000).toISOString(),
        }),
      });
    });

    // Mock session status - return verified after 2 polls
    await page.route('**/api/sessions/**', async (route) => {
      pollCount++;
      if (pollCount >= 2) {
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({
            status: 'verified',
            result: {
              claims: {
                ageOver18: true,
                ageOver21: true,
              },
            },
          }),
        });
      } else {
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({ status: 'pending' }),
        });
      }
    });

    await page.goto('/');
    await page.click('button:has-text("Verify Age to Continue")');

    // Wait for success state
    await expect(page.locator('text=Age Verified Successfully')).toBeVisible({ timeout: 10000 });

    // Check success message
    await expect(page.locator('text=Your age has been verified')).toBeVisible();

    // Check verification details are shown
    await expect(page.locator('text=Verification Details')).toBeVisible();
    await expect(page.locator('text=Age over 21: Yes')).toBeVisible();
    await expect(page.locator('text=Age over 18: Yes')).toBeVisible();

    // Complete purchase button should be visible
    await expect(page.locator('button:has-text("Complete Purchase")')).toBeVisible();
  });

  test('has start over option after success', async ({ page }) => {
    // Mock APIs for immediate success
    await page.route('**/api/verify', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          sessionId: 'vs_success',
          qrCodeData: 'openid4vp://authorize',
          deepLink: 'eudi-openid4vp://authorize',
          expiresAt: new Date(Date.now() + 300000).toISOString(),
        }),
      });
    });

    await page.route('**/api/sessions/**', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          status: 'verified',
          result: { claims: { ageOver18: true } },
        }),
      });
    });

    await page.goto('/');
    await page.click('button:has-text("Verify Age to Continue")');

    await expect(page.locator('text=Age Verified Successfully')).toBeVisible({ timeout: 10000 });

    // Click start over
    await page.click('button:has-text("Start Over")');

    // Should return to idle state
    await expect(page.locator('text=Age Verification Required')).toBeVisible();
  });
});

test.describe('Verification Flow - Failure', () => {
  test('displays error state when verification fails', async ({ page }) => {
    // Mock the verify API
    await page.route('**/api/verify', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          sessionId: 'vs_fail123',
          qrCodeData: 'openid4vp://authorize',
          deepLink: 'eudi-openid4vp://authorize',
          expiresAt: new Date(Date.now() + 300000).toISOString(),
        }),
      });
    });

    // Mock session status - return failed
    await page.route('**/api/sessions/**', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          status: 'failed',
          error: 'Age requirement not met',
        }),
      });
    });

    await page.goto('/');
    await page.click('button:has-text("Verify Age to Continue")');

    // Wait for failure state
    await expect(page.locator('text=Verification Failed')).toBeVisible({ timeout: 10000 });

    // Try again button should be visible
    await expect(page.locator('button:has-text("Try Again")')).toBeVisible();
  });

  test('displays expired state when session expires', async ({ page }) => {
    // Mock the verify API
    await page.route('**/api/verify', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          sessionId: 'vs_expired123',
          qrCodeData: 'openid4vp://authorize',
          deepLink: 'eudi-openid4vp://authorize',
          expiresAt: new Date(Date.now() + 300000).toISOString(),
        }),
      });
    });

    // Mock session status - return expired
    await page.route('**/api/sessions/**', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          status: 'expired',
        }),
      });
    });

    await page.goto('/');
    await page.click('button:has-text("Verify Age to Continue")');

    // Wait for expired state
    await expect(page.locator('text=Verification Expired')).toBeVisible({ timeout: 10000 });

    // Try again button should be visible
    await expect(page.locator('button:has-text("Try Again")')).toBeVisible();
  });

  test('try again button returns to idle and allows retry', async ({ page }) => {
    // Mock the verify API - always returns success
    await page.route('**/api/verify', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          sessionId: `vs_retry_${Date.now()}`,
          qrCodeData: 'openid4vp://authorize',
          deepLink: 'eudi-openid4vp://authorize',
          expiresAt: new Date(Date.now() + 300000).toISOString(),
        }),
      });
    });

    // First time: fail, after that: pending
    let shouldFail = true;
    await page.route('**/api/sessions/**', async (route) => {
      if (shouldFail) {
        shouldFail = false;
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({ status: 'failed', error: 'Test failure' }),
        });
      } else {
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({ status: 'pending' }),
        });
      }
    });

    await page.goto('/');

    // First attempt - should fail
    await page.click('button:has-text("Verify Age to Continue")');
    await expect(page.locator('text=Verification Failed')).toBeVisible({ timeout: 10000 });

    // Try again - returns to idle state first
    await page.click('button:has-text("Try Again")');
    await expect(page.locator('text=Age Verification Required')).toBeVisible();
    await expect(page.locator('button:has-text("Verify Age to Continue")')).toBeVisible();

    // Click verify again - should show QR code this time
    await page.click('button:has-text("Verify Age to Continue")');
    await expect(page.locator('text=Scan to Verify')).toBeVisible({ timeout: 10000 });
  });
});

test.describe('API Error Handling', () => {
  test('handles verify API error gracefully', async ({ page }) => {
    // Mock the verify API to return error
    await page.route('**/api/verify', async (route) => {
      await route.fulfill({
        status: 500,
        contentType: 'application/json',
        body: JSON.stringify({ error: 'Internal server error' }),
      });
    });

    await page.goto('/');
    await page.click('button:has-text("Verify Age to Continue")');

    // Should show error state
    await expect(page.locator('text=Verification Failed')).toBeVisible({ timeout: 10000 });
  });

  test('handles network error gracefully', async ({ page }) => {
    // Mock the verify API to abort
    await page.route('**/api/verify', async (route) => {
      await route.abort('failed');
    });

    await page.goto('/');
    await page.click('button:has-text("Verify Age to Continue")');

    // Should show error state
    await expect(page.locator('text=Verification Failed')).toBeVisible({ timeout: 10000 });
  });
});

test.describe('Accessibility', () => {
  test('page has proper heading structure', async ({ page }) => {
    await page.goto('/');

    // H1 should be present
    const h1 = page.locator('h1');
    await expect(h1).toHaveCount(1);
    await expect(h1).toContainText('Age Verification Demo');

    // H2 for product
    await expect(page.locator('h2:has-text("Premium Wine Selection")')).toBeVisible();
  });

  test('buttons are focusable and have accessible text', async ({ page }) => {
    await page.goto('/');

    const button = page.locator('button:has-text("Verify Age to Continue")');
    await button.focus();
    await expect(button).toBeFocused();
  });

  test('verification states have clear visual indicators', async ({ page }) => {
    // Mock for success state
    await page.route('**/api/verify', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          sessionId: 'vs_a11y',
          qrCodeData: 'openid4vp://authorize',
          deepLink: 'eudi-openid4vp://authorize',
          expiresAt: new Date(Date.now() + 300000).toISOString(),
        }),
      });
    });

    await page.route('**/api/sessions/**', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          status: 'verified',
          result: { claims: { ageOver18: true } },
        }),
      });
    });

    await page.goto('/');
    await page.click('button:has-text("Verify Age to Continue")');

    // Success state should have green color indicator (heading)
    await expect(page.locator('h3.text-green-700')).toBeVisible({ timeout: 10000 });
  });
});
