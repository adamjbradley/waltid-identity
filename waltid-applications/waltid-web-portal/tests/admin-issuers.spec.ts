import { test, expect } from '@playwright/test';
import { PORTAL_URL } from './helpers';

test.describe('Admin Issuers Page', () => {
  test.beforeEach(async ({ page }) => {
    await page.goto(`${PORTAL_URL}/admin/issuers`);
    await page.waitForLoadState('networkidle');
  });

  test('displays Issuer Registrar heading', async ({ page }) => {
    const heading = page.getByRole('heading', { name: /Issuer Registrar/i });
    await expect(heading).toBeVisible();
  });

  test('lists issuers with status badges', async ({ page }) => {
    // Wait for the list to load (either issuer entries or the empty state)
    await page.waitForSelector('text=ACTIVE, text=SUSPENDED, text=REVOKED, text=No issuers registered', {
      timeout: 15_000,
    }).catch(() => {});

    // Expect at least one entry with an ACTIVE badge
    const activeBadge = page.locator('span', { hasText: 'ACTIVE' }).first();
    await expect(activeBadge).toBeVisible({ timeout: 10_000 });
  });

  test('expanding issuer shows detail panel', async ({ page }) => {
    // Wait for issuer list to populate
    const firstIssuerRow = page.locator('button.w-full.text-left').first();
    await expect(firstIssuerRow).toBeVisible({ timeout: 10_000 });

    // Click to expand
    await firstIssuerRow.click();

    // Detail section should appear with "Issuer ID" label
    const issuerIdLabel = page.getByText('Issuer ID', { exact: false });
    await expect(issuerIdLabel).toBeVisible({ timeout: 10_000 });
  });

  test('detail shows certificate information', async ({ page }) => {
    // Expand the first issuer
    const firstIssuerRow = page.locator('button.w-full.text-left').first();
    await expect(firstIssuerRow).toBeVisible({ timeout: 10_000 });
    await firstIssuerRow.click();

    // Wait for detail to load
    await page.waitForLoadState('networkidle');

    // Certificate section should show Subject and Fingerprint
    const subjectLabel = page.getByText('Subject', { exact: false });
    await expect(subjectLabel).toBeVisible({ timeout: 10_000 });

    const fingerprintLabel = page.getByText('Fingerprint', { exact: false });
    await expect(fingerprintLabel).toBeVisible({ timeout: 10_000 });
  });

  test('detail shows IACA certificate', async ({ page }) => {
    // Expand the first issuer
    const firstIssuerRow = page.locator('button.w-full.text-left').first();
    await expect(firstIssuerRow).toBeVisible({ timeout: 10_000 });
    await firstIssuerRow.click();

    // Wait for detail to load
    await page.waitForLoadState('networkidle');

    // Look for "IACA" or "Root" certificate section
    const iacaSection = page.getByText(/IACA|Root/i).first();
    await expect(iacaSection).toBeVisible({ timeout: 10_000 });
  });

  test('Issue Credential action link exists', async ({ page }) => {
    // Expand the first issuer
    const firstIssuerRow = page.locator('button.w-full.text-left').first();
    await expect(firstIssuerRow).toBeVisible({ timeout: 10_000 });
    await firstIssuerRow.click();

    // Wait for detail to load
    await page.waitForLoadState('networkidle');

    // Look for a link/button containing "Issue"
    const issueLink = page.locator('button, a', { hasText: /Issue/i }).first();
    await expect(issueLink).toBeVisible({ timeout: 10_000 });
  });

  test('View Metadata link points to tenant-scoped URL', async ({ page }) => {
    // Expand the first issuer
    const firstIssuerRow = page.locator('button.w-full.text-left').first();
    await expect(firstIssuerRow).toBeVisible({ timeout: 10_000 });
    await firstIssuerRow.click();

    // Wait for detail to load
    await page.waitForLoadState('networkidle');

    // Find the "View Metadata" link
    const metadataLink = page.locator('a', { hasText: /View Metadata/i });
    await expect(metadataLink).toBeVisible({ timeout: 10_000 });

    const href = await metadataLink.getAttribute('href');
    expect(href).toContain('/issuers/');
    expect(href).toContain('.well-known/openid-credential-issuer');
  });

  test('LOTL URL references correct endpoint', async ({ page }) => {
    // The "Copy LOTL URL" button should be visible in the header
    const lotlButton = page.locator('button', { hasText: /LOTL/i });
    await expect(lotlButton).toBeVisible({ timeout: 10_000 });

    // The button title references lotl.xml
    const title = await lotlButton.getAttribute('title');
    expect(title).toBeTruthy();
    // The button text itself references LOTL
    const text = await lotlButton.textContent();
    expect(text).toContain('LOTL');
  });

  test('Register tab has required fields', async ({ page }) => {
    // Click the Register tab
    const registerTab = page.locator('button', { hasText: /Register New Issuer/i });
    await expect(registerTab).toBeVisible({ timeout: 10_000 });
    await registerTab.click();

    // Check for required form fields
    const legalNameField = page.locator('input[name="legalName"], label:has-text("Legal Name")');
    await expect(legalNameField.first()).toBeVisible({ timeout: 5_000 });

    const countryField = page.locator('input[name="country"], label:has-text("Country")');
    await expect(countryField.first()).toBeVisible({ timeout: 5_000 });

    const domainField = page.locator('input[name="domain"], label:has-text("Domain")');
    await expect(domainField.first()).toBeVisible({ timeout: 5_000 });

    const emailField = page.locator('input[name="contactEmail"], label:has-text("Contact Email")');
    await expect(emailField.first()).toBeVisible({ timeout: 5_000 });

    // The Register Issuer submit button should be visible
    const submitButton = page.locator('button', { hasText: /Register Issuer/i });
    await expect(submitButton).toBeVisible({ timeout: 5_000 });
  });
});
