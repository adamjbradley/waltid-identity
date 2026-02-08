/**
 * End-to-end tests for the WaltVerify Widget SDK Demo
 *
 * These tests verify the complete user flow for:
 * - Page load and initial state
 * - Widget initialization
 * - Modal verification flow
 * - Inline verification flow
 * - Theme customization
 * - Error handling
 */

const { test, expect } = require('@playwright/test');

test.describe('Widget Demo Page', () => {
  test.beforeEach(async ({ page }) => {
    await page.goto('/');
  });

  test('displays the demo page title and branding', async ({ page }) => {
    // Check page title
    await expect(page).toHaveTitle(/VintageVino/);

    // Check header branding
    await expect(page.locator('.logo')).toContainText('VintageVino');
  });

  test('displays hero section with age verification message', async ({ page }) => {
    const hero = page.locator('.hero');
    await expect(hero).toBeVisible();
    await expect(hero.locator('h1')).toContainText('Premium Wines');
    await expect(hero.locator('p')).toContainText('Age verification required');
  });

  test('displays product cards', async ({ page }) => {
    const productCards = page.locator('.product-card');
    await expect(productCards).toHaveCount(3);

    // First product should be Chateau Margaux
    await expect(productCards.first()).toContainText('Chateau Margaux');
    await expect(productCards.first()).toContainText('$299.00');
  });

  test('products have Add to Cart buttons', async ({ page }) => {
    const addToCartButtons = page.locator('.product-card button');
    await expect(addToCartButtons).toHaveCount(3);

    // Buttons should indicate age requirement
    await expect(addToCartButtons.first()).toContainText('18+');
    await expect(addToCartButtons.nth(1)).toContainText('21+');
  });

  test('displays integration code section', async ({ page }) => {
    await expect(page.locator('text=Quick Integration Guide')).toBeVisible();
    await expect(page.locator('text=Step 1: Include the SDK')).toBeVisible();
    await expect(page.locator('text=Step 2: Get a Client Token')).toBeVisible();
    await expect(page.locator('text=Step 3: Initialize and Verify')).toBeVisible();
  });

  test('displays footer with branding', async ({ page }) => {
    const footer = page.locator('footer');
    await expect(footer).toContainText('WaltVerify Widget SDK Demo');
    await expect(footer).toContainText('walt.id');
  });
});

test.describe('SDK Loading', () => {
  test('shows loading indicator initially', async ({ page }) => {
    // Intercept SDK request to delay loading
    await page.route('**/widget/v1/sdk.js', async (route) => {
      await new Promise(resolve => setTimeout(resolve, 100));
      await route.abort();
    });

    await page.goto('/');

    // Loading indicator should be visible briefly
    await expect(page.locator('#sdk-loading')).toBeVisible();
    await expect(page.locator('text=Loading SDK')).toBeVisible();
  });

  test('hides loading indicator when SDK loads successfully', async ({ page }) => {
    // Mock successful SDK load
    await page.route('**/widget/v1/sdk.js', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/javascript',
        body: `
          window.WaltVerify = {
            init: function() { console.log('Mock SDK initialized'); },
            verifyAge: function() { return Promise.resolve(); },
            verify: function() { return Promise.resolve(); }
          };
        `
      });
    });

    // Mock token endpoint
    await page.route('**/api/token', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          clientToken: 'ct_mock_token_123',
          expiresAt: new Date(Date.now() + 900000).toISOString()
        })
      });
    });

    await page.goto('/');

    // Wait for SDK to initialize and loading to hide
    await expect(page.locator('#sdk-loading')).toBeHidden({ timeout: 5000 });
  });

  test('shows error when SDK fails to load', async ({ page }) => {
    // Mock SDK load failure
    await page.route('**/widget/v1/sdk.js', async (route) => {
      await route.abort('failed');
    });

    await page.goto('/');

    // Should show error in loading indicator
    await expect(page.locator('text=SDK Error')).toBeVisible({ timeout: 5000 });
  });
});

test.describe('API Endpoints', () => {
  test('GET /health returns ok status', async ({ page }) => {
    const response = await page.request.get('/health');
    expect(response.ok()).toBeTruthy();

    const data = await response.json();
    expect(data).toEqual({ status: 'ok' });
  });

  test('GET /api/config returns API URL', async ({ page }) => {
    const response = await page.request.get('/api/config');
    expect(response.ok()).toBeTruthy();

    const data = await response.json();
    expect(data).toHaveProperty('apiBaseUrl');
    expect(data.apiBaseUrl).toContain('localhost');
  });

  test('GET /api/token calls Verify API', async ({ page }) => {
    // This test verifies the endpoint exists and returns expected structure
    // It may fail if the Verify API is not running - that's expected

    const response = await page.request.get('/api/token');

    if (response.ok()) {
      const data = await response.json();
      expect(data).toHaveProperty('clientToken');
      expect(data.clientToken).toMatch(/^ct_/);
      expect(data).toHaveProperty('expiresAt');
    } else {
      // API not available - verify error format is correct
      const data = await response.json();
      expect(data).toHaveProperty('error');
    }
  });
});

test.describe('Inline Verification Mode', () => {
  test.beforeEach(async ({ page }) => {
    // Mock SDK and token
    await page.route('**/widget/v1/sdk.js', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/javascript',
        body: `
          window.WaltVerify = {
            init: function(config) {
              console.log('Mock SDK initialized with:', config);
            },
            verifyAge: function(options) {
              console.log('verifyAge called with:', options);
              return Promise.resolve();
            },
            verify: function(options) {
              console.log('verify called with:', options);
              // Simulate QR code display
              if (options.container) {
                const container = document.querySelector(options.container);
                if (container) {
                  container.innerHTML = '<div data-testid="mock-qr">Mock QR Code for ' + options.template + '</div>';
                }
              }
              return Promise.resolve();
            }
          };
        `
      });
    });

    await page.route('**/api/token', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          clientToken: 'ct_mock_token_123',
          expiresAt: new Date(Date.now() + 900000).toISOString()
        })
      });
    });

    await page.goto('/');
    await page.waitForLoadState('networkidle');
  });

  test('displays inline verification section', async ({ page }) => {
    await expect(page.locator('text=Inline Verification Mode')).toBeVisible();
    await expect(page.locator('#inline-verification-container')).toBeVisible();
  });

  test('has tabs for different verification types', async ({ page }) => {
    const tabs = page.locator('.tab');
    await expect(tabs).toHaveCount(3);
    await expect(tabs.nth(0)).toContainText('Age Verification');
    await expect(tabs.nth(1)).toContainText('KYC Basic');
    await expect(tabs.nth(2)).toContainText('Custom Template');
  });

  test('age verification tab is active by default', async ({ page }) => {
    await expect(page.locator('.tab.active')).toContainText('Age Verification');
    await expect(page.locator('#tab-age')).toBeVisible();
  });

  test('switches tabs correctly', async ({ page }) => {
    // Click KYC tab
    await page.click('.tab:has-text("KYC Basic")');
    await expect(page.locator('#tab-kyc')).toBeVisible();
    await expect(page.locator('#tab-age')).toBeHidden();

    // Click Custom tab
    await page.click('.tab:has-text("Custom Template")');
    await expect(page.locator('#tab-custom')).toBeVisible();
    await expect(page.locator('#tab-kyc')).toBeHidden();
  });

  test('starts inline verification when button clicked', async ({ page }) => {
    // Wait for SDK to be ready
    await page.waitForFunction(() => typeof window.WaltVerify !== 'undefined');

    // Click the inline verification button
    await page.click('#tab-age button');

    // Mock QR code should appear in container
    await expect(page.locator('[data-testid="mock-qr"]')).toBeVisible({ timeout: 5000 });
  });
});

test.describe('Theme Customization', () => {
  test.beforeEach(async ({ page }) => {
    await page.goto('/');
  });

  test('displays theme customization section', async ({ page }) => {
    await expect(page.locator('text=Theme Customization')).toBeVisible();
    await expect(page.locator('text=Widget Theme Options')).toBeVisible();
  });

  test('has color picker inputs', async ({ page }) => {
    await expect(page.locator('#theme-primary')).toBeVisible();
    await expect(page.locator('#theme-background')).toBeVisible();
    await expect(page.locator('#theme-text')).toBeVisible();
    await expect(page.locator('#theme-radius')).toBeVisible();
  });

  test('has default color values', async ({ page }) => {
    expect(await page.locator('#theme-primary').inputValue()).toBe('#7c2d12');
    expect(await page.locator('#theme-background').inputValue()).toBe('#ffffff');
    expect(await page.locator('#theme-text').inputValue()).toBe('#1f2937');
    expect(await page.locator('#theme-radius').inputValue()).toBe('12px');
  });

  test('displays code preview with theme values', async ({ page }) => {
    // Theme code block is in the Theme Customization section - find the one with theme config
    const codeBlocks = page.locator('.code-block');
    const count = await codeBlocks.count();

    // Find the code block that contains theme configuration
    let foundThemeBlock = false;
    for (let i = 0; i < count; i++) {
      const text = await codeBlocks.nth(i).textContent();
      if (text.includes('primaryColor')) {
        foundThemeBlock = true;
        await expect(codeBlocks.nth(i)).toContainText('#7c2d12');
        break;
      }
    }
    expect(foundThemeBlock).toBe(true);
  });

  test('has reset to default button', async ({ page }) => {
    const resetButton = page.locator('button:has-text("Reset to Default")');
    await expect(resetButton).toBeVisible();
  });
});

test.describe('Modal Verification Flow', () => {
  test.beforeEach(async ({ page }) => {
    // Set up mocks for SDK and token
    await page.route('**/widget/v1/sdk.js', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/javascript',
        body: `
          window.WaltVerify = {
            init: function(config) {
              console.log('Mock SDK initialized');
            },
            verifyAge: function(options) {
              console.log('verifyAge called with minAge:', options.minAge);
              // Simulate modal behavior
              if (options.onSuccess) {
                setTimeout(() => {
                  options.onSuccess({ verified: true, age: options.minAge + 5 });
                }, 500);
              }
              return Promise.resolve();
            },
            verify: function(options) {
              return Promise.resolve();
            }
          };
        `
      });
    });

    await page.route('**/api/token', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          clientToken: 'ct_mock_token_123',
          expiresAt: new Date(Date.now() + 900000).toISOString()
        })
      });
    });

    await page.goto('/');
    await page.waitForLoadState('networkidle');
  });

  test('clicking Add to Cart triggers age verification', async ({ page }) => {
    // Wait for SDK to be ready
    await page.waitForFunction(() => typeof window.WaltVerify !== 'undefined');

    // Listen for console messages from mock SDK
    const consoleMessages = [];
    page.on('console', msg => consoleMessages.push(msg.text()));

    // Click Add to Cart on first product
    await page.click('.product-card:first-child button');

    // Wait a moment for the SDK to be called
    await page.waitForTimeout(200);

    // Verify the SDK was called with correct minAge
    expect(consoleMessages.some(msg => msg.includes('verifyAge') && msg.includes('18'))).toBeTruthy();
  });

  test('second product requires 21+ age verification', async ({ page }) => {
    await page.waitForFunction(() => typeof window.WaltVerify !== 'undefined');

    const consoleMessages = [];
    page.on('console', msg => consoleMessages.push(msg.text()));

    // Click Add to Cart on second product (whiskey - 21+)
    await page.click('.product-card:nth-child(2) button');

    await page.waitForTimeout(200);

    expect(consoleMessages.some(msg => msg.includes('verifyAge') && msg.includes('21'))).toBeTruthy();
  });

  test('shows verification result after success', async ({ page }) => {
    await page.waitForFunction(() => typeof window.WaltVerify !== 'undefined');

    // Click Add to Cart on first product
    await page.click('.product-card:first-child button');

    // Wait for success callback and result display
    await expect(page.locator('#modal-result')).toBeVisible({ timeout: 5000 });
    await expect(page.locator('#modal-result-content')).toContainText('verified');
  });
});

test.describe('Responsive Design', () => {
  test('adapts layout on mobile viewport', async ({ page }) => {
    await page.setViewportSize({ width: 375, height: 667 });
    await page.goto('/');

    // Page should still be functional
    await expect(page.locator('.logo')).toBeVisible();
    await expect(page.locator('.product-card')).toHaveCount(3);
  });

  test('product grid stacks on narrow viewport', async ({ page }) => {
    await page.setViewportSize({ width: 320, height: 568 });
    await page.goto('/');

    // Products should be visible and usable
    const firstProduct = page.locator('.product-card').first();
    await expect(firstProduct).toBeVisible();
    await expect(firstProduct.locator('button')).toBeVisible();
  });
});

test.describe('Accessibility', () => {
  test.beforeEach(async ({ page }) => {
    await page.goto('/');
  });

  test('page has proper heading structure', async ({ page }) => {
    // H1 should be present in hero
    const h1 = page.locator('h1');
    await expect(h1).toBeVisible();

    // H2s should be present for sections
    const h2Count = await page.locator('h2').count();
    expect(h2Count).toBeGreaterThan(0);
  });

  test('color inputs are focusable', async ({ page }) => {
    const primaryColor = page.locator('#theme-primary');
    await primaryColor.focus();
    await expect(primaryColor).toBeFocused();
  });

  test('buttons are focusable and clickable', async ({ page }) => {
    const firstButton = page.locator('.product-card button').first();
    await firstButton.focus();
    await expect(firstButton).toBeFocused();
  });

  test('links have accessible text', async ({ page }) => {
    const links = page.locator('a');
    const count = await links.count();

    for (let i = 0; i < count; i++) {
      const link = links.nth(i);
      const text = await link.textContent();
      expect(text.trim().length).toBeGreaterThan(0);
    }
  });
});

test.describe('Error States', () => {
  test('shows alert when SDK not ready and button clicked', async ({ page }) => {
    // Block SDK loading
    await page.route('**/widget/v1/sdk.js', async (route) => {
      // Don't fulfill - let it hang
      await new Promise(() => {});
    });

    await page.goto('/');

    // Listen for dialog/alert
    page.on('dialog', async dialog => {
      expect(dialog.message()).toContain('SDK is still loading');
      await dialog.dismiss();
    });

    // Try to click button before SDK loads
    await page.click('.product-card:first-child button');
  });

  test('handles token API error gracefully', async ({ page }) => {
    // Mock SDK to load successfully
    await page.route('**/widget/v1/sdk.js', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/javascript',
        body: 'window.WaltVerify = { init: function() {}, verifyAge: function() {}, verify: function() {} };'
      });
    });

    // Mock token endpoint to fail
    await page.route('**/api/token', async (route) => {
      await route.fulfill({
        status: 500,
        contentType: 'application/json',
        body: JSON.stringify({ error: 'Internal server error' })
      });
    });

    await page.goto('/');

    // Should show error state in loading indicator
    await expect(page.locator('text=SDK Error')).toBeVisible({ timeout: 5000 });
  });
});
