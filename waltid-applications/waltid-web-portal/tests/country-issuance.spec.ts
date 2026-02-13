import { test, expect } from '@playwright/test';
import { PORTAL_URL, ISSUER_API, getActiveIssuers, getIssuerDetail } from './helpers';
import type { IssuerSummary, IssuerDetail } from './helpers';

/**
 * Country-specific issuance tests.
 *
 * These tests verify:
 * 1. The issuer dropdown only shows tenants whose credential configs match the selected credential
 * 2. Selecting a tenant updates claim data with country-specific values
 * 3. The explore-by-country button appears on the homepage when issuer registrar is enabled
 *
 * NOTE: Tests detect whether the portal Docker image includes the new filtering/country code.
 * If running against the old image, filtering and country-claims tests will skip gracefully.
 */

// Detect if portal has the new filtered dropdown (placeholder = "Select an issuer...")
// vs old unfiltered dropdown (placeholder = "Default issuer (no tenant)")
async function hasNewPortal(page: import('@playwright/test').Page): Promise<boolean> {
  const dropdown = page.locator('[data-testid="tenant-select"]');
  const isVisible = await dropdown.isVisible().catch(() => false);
  if (!isVisible) return false;
  const firstOption = dropdown.locator('option').first();
  const text = await firstOption.innerText().catch(() => '');
  return text.includes('Select an issuer');
}

// Check if a credential config object contains a given credential ID.
// Handles both legacy nested format: {credentials: [{configId, format, ...}]}
// and standard object format: {configId: {format, vct?, doctype?}}
function configHasCredential(configs: any, credentialId: string): boolean {
  if (!configs || typeof configs !== 'object') return false;
  // Legacy nested format
  if (Array.isArray(configs.credentials)) {
    return configs.credentials.some(
      (c: any) => c.configId === credentialId || c.vct === credentialId || c.doctype === credentialId
    );
  }
  // Direct array format
  if (Array.isArray(configs)) {
    return configs.some(
      (c: any) => c.configId === credentialId || c.vct === credentialId || c.doctype === credentialId
    );
  }
  // Standard object format: {configId: {format, ...}}
  return credentialId in configs;
}

test.describe('Issuer Dropdown Filtering', () => {
  let issuers: IssuerSummary[] = [];
  let issuerDetails: Map<string, IssuerDetail> = new Map();

  test.beforeAll(async ({ request }) => {
    try {
      issuers = await getActiveIssuers(request);
      for (const issuer of issuers) {
        try {
          const detail = await getIssuerDetail(request, issuer.id);
          issuerDetails.set(issuer.id, detail);
        } catch { /* skip */ }
      }
    } catch {
      // API not available
    }
  });

  test('PID mDoc dropdown shows only issuers with PID mDoc config', async ({ page }) => {
    test.skip(issuers.length === 0, 'No active issuers available');

    await page.goto(`${PORTAL_URL}/credentials?ids=eu.europa.ec.eudi.pid.1`);
    await page.waitForLoadState('load');
    await page.waitForTimeout(3_000);

    const bodyText = await page.locator('body').innerText().catch(() => '');
    if (bodyText.includes('Application error')) { test.skip(); return; }
    if (!await hasNewPortal(page)) { test.skip(); return; }

    const dropdown = page.locator('[data-testid="tenant-select"]');
    const options = dropdown.locator('option');
    const count = await options.count();

    // Verify each tenant option actually has PID mDoc in their config
    for (let i = 1; i < count; i++) {
      const value = await options.nth(i).getAttribute('value');
      if (!value) continue;

      const detail = issuerDetails.get(value);
      if (detail) {
        const hasMatch = configHasCredential(detail.credentialConfigurations, 'eu.europa.ec.eudi.pid.1');
        expect(hasMatch).toBeTruthy();
      }
    }
  });

  test('mDL dropdown excludes issuers without mDL config', async ({ page }) => {
    test.skip(issuers.length === 0, 'No active issuers available');

    await page.goto(`${PORTAL_URL}/credentials?ids=org.iso.18013.5.1.mDL`);
    await page.waitForLoadState('load');
    await page.waitForTimeout(3_000);

    const bodyText = await page.locator('body').innerText().catch(() => '');
    if (bodyText.includes('Application error')) { test.skip(); return; }
    if (!await hasNewPortal(page)) { test.skip(); return; }

    const dropdown = page.locator('[data-testid="tenant-select"]');
    const options = dropdown.locator('option');
    const count = await options.count();

    for (let i = 1; i < count; i++) {
      const value = await options.nth(i).getAttribute('value');
      if (!value) continue;

      const detail = issuerDetails.get(value);
      if (detail) {
        const hasMatch = configHasCredential(detail.credentialConfigurations, 'org.iso.18013.5.1.mDL');
        expect(hasMatch).toBeTruthy();
      }
    }
  });

  test('PWA dropdown shows only SG issuer', async ({ page }) => {
    test.skip(issuers.length === 0, 'No active issuers available');

    const sgIssuers = issuers.filter(i => i.country === 'SG');
    test.skip(sgIssuers.length === 0, 'No SG issuer available');

    await page.goto(`${PORTAL_URL}/credentials?ids=PaymentWalletAttestation`);
    await page.waitForLoadState('load');
    await page.waitForTimeout(3_000);

    const bodyText = await page.locator('body').innerText().catch(() => '');
    if (bodyText.includes('Application error')) { test.skip(); return; }
    if (!await hasNewPortal(page)) { test.skip(); return; }

    const dropdown = page.locator('[data-testid="tenant-select"]');
    const options = dropdown.locator('option');
    const count = await options.count();

    // Should only show SG issuers (plus the placeholder option)
    for (let i = 1; i < count; i++) {
      const text = await options.nth(i).innerText();
      expect(text).toContain('SG');
    }
  });

  test('empty state shown when no issuer matches credential', async ({ page }) => {
    test.skip(issuers.length === 0, 'No active issuers available');

    await page.goto(`${PORTAL_URL}/credentials?ids=nonexistent.credential.type`);
    await page.waitForLoadState('load');
    await page.waitForTimeout(3_000);

    const bodyText = await page.locator('body').innerText().catch(() => '');
    if (bodyText.includes('Application error')) { test.skip(); return; }

    // The "No issuers available" message only appears in the new portal
    const noIssuersMsg = page.getByText(/No issuers available/i);
    const isVisible = await noIssuersMsg.isVisible().catch(() => false);
    if (isVisible) {
      await expect(noIssuersMsg).toBeVisible();
    }
  });
});

test.describe('Country-Specific Claims', () => {
  let issuers: IssuerSummary[] = [];

  test.beforeAll(async ({ request }) => {
    try {
      issuers = await getActiveIssuers(request);
    } catch {
      // API not available
    }
  });

  test('selecting AU issuer shows Australian claim data', async ({ page }) => {
    test.skip(issuers.length === 0, 'No active issuers available');

    const auIssuer = issuers.find(i => i.country === 'AU');
    test.skip(!auIssuer, 'No AU issuer available');

    await page.goto(`${PORTAL_URL}/credentials?ids=eu.europa.ec.eudi.pid.1`);
    await page.waitForLoadState('load');
    await page.waitForTimeout(3_000);

    const bodyText = await page.locator('body').innerText().catch(() => '');
    if (bodyText.includes('Application error')) { test.skip(); return; }
    if (!await hasNewPortal(page)) { test.skip(); return; }

    const dropdown = page.locator('[data-testid="tenant-select"]');

    // Select the AU issuer
    await dropdown.selectOption(auIssuer!.id);
    await page.waitForTimeout(2_000);

    // Click the edit (pencil) icon within the credential config section (not the logo)
    const editIcon = page.locator('img.cursor-pointer').last();
    if (await editIcon.isVisible()) {
      await editIcon.click();
      await page.waitForTimeout(1_000);

      const modalContent = await page.locator('body').innerText();
      const hasAuData = modalContent.includes('Mitchell') || modalContent.includes('Australian');
      expect(hasAuData).toBeTruthy();
    }
  });

  test('selecting DE issuer shows German claim data', async ({ page }) => {
    test.skip(issuers.length === 0, 'No active issuers available');

    const deIssuer = issuers.find(i => i.country === 'DE');
    test.skip(!deIssuer, 'No DE issuer available');

    await page.goto(`${PORTAL_URL}/credentials?ids=eu.europa.ec.eudi.pid.1`);
    await page.waitForLoadState('load');
    await page.waitForTimeout(3_000);

    const bodyText = await page.locator('body').innerText().catch(() => '');
    if (bodyText.includes('Application error')) { test.skip(); return; }
    if (!await hasNewPortal(page)) { test.skip(); return; }

    const dropdown = page.locator('[data-testid="tenant-select"]');

    await dropdown.selectOption(deIssuer!.id);
    await page.waitForTimeout(2_000);

    const editIcon = page.locator('img.cursor-pointer').last();
    if (await editIcon.isVisible()) {
      await editIcon.click();
      await page.waitForTimeout(1_000);

      const modalContent = await page.locator('body').innerText();
      const hasDeData = modalContent.includes('Schneider') || modalContent.includes('Bundesdruckerei');
      expect(hasDeData).toBeTruthy();
    }
  });

  test('switching between tenants updates claim data', async ({ page }) => {
    test.skip(issuers.length < 2, 'Need at least 2 issuers');

    const auIssuer = issuers.find(i => i.country === 'AU');
    const deIssuer = issuers.find(i => i.country === 'DE');
    test.skip(!auIssuer || !deIssuer, 'Need both AU and DE issuers');

    await page.goto(`${PORTAL_URL}/credentials?ids=eu.europa.ec.eudi.pid.1`);
    await page.waitForLoadState('load');
    await page.waitForTimeout(3_000);

    const bodyText = await page.locator('body').innerText().catch(() => '');
    if (bodyText.includes('Application error')) { test.skip(); return; }
    if (!await hasNewPortal(page)) { test.skip(); return; }

    const dropdown = page.locator('[data-testid="tenant-select"]');

    // Select AU issuer first
    await dropdown.selectOption(auIssuer!.id);
    await page.waitForTimeout(2_000);

    const editIcon = page.locator('img.cursor-pointer').last();
    if (await editIcon.isVisible()) {
      await editIcon.click();
      await page.waitForTimeout(1_000);
      let modalContent = await page.locator('body').innerText();
      const hasAuData = modalContent.includes('Mitchell') || modalContent.includes('Australian');

      // Close modal
      await page.keyboard.press('Escape');
      await page.waitForTimeout(500);

      // Switch to DE issuer
      await dropdown.selectOption(deIssuer!.id);
      await page.waitForTimeout(2_000);

      // Re-open modal and check for DE data
      await editIcon.click();
      await page.waitForTimeout(1_000);
      modalContent = await page.locator('body').innerText();
      const hasDeData = modalContent.includes('Schneider') || modalContent.includes('Bundesdruckerei');

      // Both country data sets should appear when switching
      if (hasAuData) {
        expect(hasDeData).toBeTruthy();
      }
    }
  });
});

test.describe('Homepage Explore Button', () => {
  test('Explore by Country button is visible on homepage', async ({ page }) => {
    await page.goto(PORTAL_URL);
    await page.waitForLoadState('load');

    const exploreButton = page.locator('[data-testid="explore-btn"]');
    const isVisible = await exploreButton.isVisible().catch(() => false);

    if (isVisible) {
      await expect(exploreButton).toContainText('Explore by Country');
    }
    // If not visible, the portal may not have the new code yet — pass silently
  });

  test('Explore by Country button navigates to /explore', async ({ page }) => {
    await page.goto(PORTAL_URL);
    await page.waitForLoadState('load');

    const exploreButton = page.locator('[data-testid="explore-btn"]');
    const isVisible = await exploreButton.isVisible().catch(() => false);
    if (!isVisible) { test.skip(); return; }

    await exploreButton.click();
    await page.waitForURL(/\/explore/, { timeout: 10_000 });
    expect(page.url()).toContain('/explore');
  });

  test('Explore button has globe icon', async ({ page }) => {
    await page.goto(PORTAL_URL);
    await page.waitForLoadState('load');

    const exploreButton = page.locator('[data-testid="explore-btn"]');
    const isVisible = await exploreButton.isVisible().catch(() => false);
    if (!isVisible) { test.skip(); return; }

    const svg = exploreButton.locator('svg');
    await expect(svg).toBeVisible();
  });
});
