import { test, expect } from '@playwright/test';
import { PORTAL_URL } from './helpers';

test.describe('Admin Navigation', () => {
  test.beforeEach(async ({ page }) => {
    await page.goto(`${PORTAL_URL}/admin/issuers`);
    await page.waitForLoadState('networkidle');
  });

  test('admin nav shows all sections', async ({ page }) => {
    // The AdminNav should display links for all admin sections
    const issuersLink = page.locator('nav button', { hasText: 'Issuers' });
    await expect(issuersLink).toBeVisible({ timeout: 10_000 });

    const rpLink = page.locator('nav button', { hasText: 'Relying Parties' });
    await expect(rpLink).toBeVisible({ timeout: 10_000 });

    // Trust Lists link is also present in the nav
    const trustLink = page.locator('nav button', { hasText: 'Trust Lists' });
    await expect(trustLink).toBeVisible({ timeout: 10_000 });
  });

  test('issuers link navigates to issuers page', async ({ page }) => {
    // First navigate away from issuers
    const rpLink = page.locator('nav button', { hasText: 'Relying Parties' });
    await rpLink.click();
    await page.waitForURL(/\/admin\/relying-parties/);

    // Now click the Issuers link
    const issuersLink = page.locator('nav button', { hasText: 'Issuers' });
    await issuersLink.click();

    await page.waitForURL(/\/admin\/issuers/);
    expect(page.url()).toContain('/admin/issuers');
  });

  test('relying parties link navigates correctly', async ({ page }) => {
    const rpLink = page.locator('nav button', { hasText: 'Relying Parties' });
    await rpLink.click();

    await page.waitForURL(/\/admin\/relying-parties/);
    expect(page.url()).toContain('/admin/relying-parties');
  });

  test('Portal link returns to homepage', async ({ page }) => {
    // The "Portal" button in the nav navigates to /
    const portalLink = page.locator('nav button', { hasText: 'Portal' });
    await expect(portalLink).toBeVisible({ timeout: 10_000 });
    await portalLink.click();

    await page.waitForURL(/^[^/]*\/$/);
    // The URL should be the root
    const url = new URL(page.url());
    expect(url.pathname).toBe('/');
  });
});
