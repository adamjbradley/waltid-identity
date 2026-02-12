import { test, expect } from '@playwright/test';
import { PORTAL_URL } from './helpers';

test.describe('Admin Relying Parties Page', () => {
  test.beforeEach(async ({ page }) => {
    await page.goto(`${PORTAL_URL}/admin/relying-parties`);
    await page.waitForLoadState('networkidle');
  });

  test('displays RP Registrar heading', async ({ page }) => {
    const heading = page.getByRole('heading', { name: /Relying Party Registrar/i });
    await expect(heading).toBeVisible();
  });

  test('lists RPs with status badges', async ({ page }) => {
    // Wait for the list to load
    await page.waitForSelector('text=ACTIVE, text=SUSPENDED, text=REVOKED, text=No relying parties', {
      timeout: 15_000,
    }).catch(() => {});

    // Expect at least one entry with an ACTIVE badge
    const activeBadge = page.locator('span', { hasText: 'ACTIVE' }).first();
    await expect(activeBadge).toBeVisible({ timeout: 10_000 });
  });

  test('expanding RP shows detail with clientId', async ({ page }) => {
    // Wait for RP list to populate
    const firstRpRow = page.locator('button.w-full.text-left').first();
    await expect(firstRpRow).toBeVisible({ timeout: 10_000 });

    // Click to expand
    await firstRpRow.click();

    // Wait for detail to load
    await page.waitForLoadState('networkidle');

    // Detail section should show Client ID with x509_san_dns: prefix
    const clientIdLabel = page.getByText('Client ID', { exact: false });
    await expect(clientIdLabel).toBeVisible({ timeout: 10_000 });

    const x509Text = page.getByText('x509_san_dns:', { exact: false });
    await expect(x509Text).toBeVisible({ timeout: 10_000 });
  });

  test('detail shows compliance section', async ({ page }) => {
    // Expand the first RP
    const firstRpRow = page.locator('button.w-full.text-left').first();
    await expect(firstRpRow).toBeVisible({ timeout: 10_000 });
    await firstRpRow.click();

    // Wait for detail to load
    await page.waitForLoadState('networkidle');

    // Compliance fields should be visible
    const privacyPolicy = page.getByText('Privacy Policy', { exact: false });
    await expect(privacyPolicy).toBeVisible({ timeout: 10_000 });

    const dataRetention = page.getByText('Data Retention', { exact: false });
    await expect(dataRetention).toBeVisible({ timeout: 10_000 });

    const lawfulBasis = page.getByText('Lawful Basis', { exact: false });
    await expect(lawfulBasis).toBeVisible({ timeout: 10_000 });
  });

  test('Verify as RP link exists', async ({ page }) => {
    // Expand the first RP
    const firstRpRow = page.locator('button.w-full.text-left').first();
    await expect(firstRpRow).toBeVisible({ timeout: 10_000 });
    await firstRpRow.click();

    // Wait for detail to load
    await page.waitForLoadState('networkidle');

    // Look for the "Verify as this RP" link
    const verifyLink = page.locator('a, button', { hasText: /Verify/i }).first();
    await expect(verifyLink).toBeVisible({ timeout: 10_000 });
  });

  test('detail shows certificate information', async ({ page }) => {
    // Expand the first RP
    const firstRpRow = page.locator('button.w-full.text-left').first();
    await expect(firstRpRow).toBeVisible({ timeout: 10_000 });
    await firstRpRow.click();

    // Wait for detail to load
    await page.waitForLoadState('networkidle');

    // Certificate section should show Subject and Expires
    const subjectLabel = page.getByText('Subject', { exact: false });
    await expect(subjectLabel).toBeVisible({ timeout: 10_000 });

    const expiresLabel = page.getByText('Expires', { exact: false });
    await expect(expiresLabel).toBeVisible({ timeout: 10_000 });
  });

  test('Download Certificate button present', async ({ page }) => {
    // Expand the first RP
    const firstRpRow = page.locator('button.w-full.text-left').first();
    await expect(firstRpRow).toBeVisible({ timeout: 10_000 });
    await firstRpRow.click();

    // Wait for detail to load
    await page.waitForLoadState('networkidle');

    // Look for the "Download Certificate" button
    const downloadButton = page.locator('[data-testid="download-cert"]');
    await expect(downloadButton).toBeVisible({ timeout: 10_000 });

    const text = await downloadButton.textContent();
    expect(text).toContain('Download');
  });

  test('certificate shows x509_san_dns format', async ({ page }) => {
    // Expand the first RP
    const firstRpRow = page.locator('button.w-full.text-left').first();
    await expect(firstRpRow).toBeVisible({ timeout: 10_000 });
    await firstRpRow.click();

    // Wait for detail to load
    await page.waitForLoadState('networkidle');

    // The clientId value should match the x509_san_dns:* pattern
    const clientIdValue = page.locator('dd', { hasText: /^x509_san_dns:/ });
    await expect(clientIdValue).toBeVisible({ timeout: 10_000 });

    const clientIdText = await clientIdValue.textContent();
    expect(clientIdText).toMatch(/^x509_san_dns:.+/);
  });

  test('Register tab validates required fields', async ({ page }) => {
    // Click the Register tab
    const registerTab = page.locator('button', { hasText: /Register New RP/i });
    await expect(registerTab).toBeVisible({ timeout: 10_000 });
    await registerTab.click();

    // Check for required form fields
    const legalNameField = page.locator('input[name="legalName"], label:has-text("Legal Name")');
    await expect(legalNameField.first()).toBeVisible({ timeout: 5_000 });

    const domainField = page.locator('input[name="domain"], label:has-text("Domain")');
    await expect(domainField.first()).toBeVisible({ timeout: 5_000 });

    const countryField = page.locator('input[name="country"], label:has-text("Country")');
    await expect(countryField.first()).toBeVisible({ timeout: 5_000 });

    const emailField = page.locator('input[name="contactEmail"], label:has-text("Contact Email")');
    await expect(emailField.first()).toBeVisible({ timeout: 5_000 });

    // The Register Relying Party submit button should be present but disabled
    // (since required fields are empty)
    const submitButton = page.locator('button', { hasText: /Register Relying Party/i });
    await expect(submitButton).toBeVisible({ timeout: 5_000 });
    await expect(submitButton).toBeDisabled();
  });
});
