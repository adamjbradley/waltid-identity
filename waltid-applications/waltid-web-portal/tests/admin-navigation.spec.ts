import { test, expect } from '@playwright/test';
import { PORTAL_URL } from './helpers';

test.describe('Admin Navigation', () => {
  test.beforeEach(async ({ page }) => {
    await page.goto(`${PORTAL_URL}/admin/issuers`);
    await page.waitForLoadState('load');
  });

  test('admin nav shows all sections', async ({ page }) => {
    // The AdminNav should display links for all admin sections
    // Use getByRole with exact name to avoid matching tab buttons like "Issuers (3)"
    const issuersLink = page.getByRole('button', { name: 'Issuers', exact: true });
    await expect(issuersLink).toBeVisible({ timeout: 10_000 });

    const rpLink = page.getByRole('button', { name: 'Relying Parties', exact: true });
    await expect(rpLink).toBeVisible({ timeout: 10_000 });

    // Trust Lists link is also present in the nav
    const trustLink = page.getByRole('button', { name: 'Trust Lists', exact: true });
    await expect(trustLink).toBeVisible({ timeout: 10_000 });
  });

  test('issuers link navigates to issuers page', async ({ page }) => {
    // First navigate away from issuers
    const rpLink = page.getByRole('button', { name: 'Relying Parties', exact: true });
    await rpLink.click();
    await page.waitForURL(/\/admin\/relying-parties/);

    // Now click the Issuers link
    const issuersLink = page.getByRole('button', { name: 'Issuers', exact: true });
    await issuersLink.click();

    await page.waitForURL(/\/admin\/issuers/);
    expect(page.url()).toContain('/admin/issuers');
  });

  test('relying parties link navigates correctly', async ({ page }) => {
    const rpLink = page.getByRole('button', { name: 'Relying Parties', exact: true });
    await rpLink.click();

    await page.waitForURL(/\/admin\/relying-parties/);
    expect(page.url()).toContain('/admin/relying-parties');
  });

  test('Portal link returns to homepage', async ({ page }) => {
    // The "Portal" button in the nav navigates to /
    const portalLink = page.getByRole('button', { name: 'Portal', exact: true });
    await expect(portalLink).toBeVisible({ timeout: 10_000 });
    await portalLink.click();

    await page.waitForURL('**/', { timeout: 15_000 });
    // The URL should be the root
    const url = new URL(page.url());
    expect(url.pathname).toBe('/');
  });
});
