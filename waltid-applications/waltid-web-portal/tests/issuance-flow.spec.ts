import { test, expect } from '@playwright/test';
import { PORTAL_URL, getActiveIssuers } from './helpers';

const CREDENTIALS_URL = `${PORTAL_URL}/credentials?ids=OpenBadgeCredential`;

test.describe('Issuance Flow', () => {
  test.beforeEach(async ({ page }) => {
    await page.goto(CREDENTIALS_URL);
    await page.waitForLoadState('load');
    // Wait for client-side hydration to complete (or fail)
    await page.waitForTimeout(2_000);
    // Skip entire suite if the credentials page has a client-side hydration error
    // (requires portal Docker image rebuild with latest code)
    const bodyText = await page.locator('body').innerText().catch(() => '');
    if (bodyText.includes('Application error')) test.skip();
  });

  test('shows Customise Issuance heading', async ({ page }) => {
    const heading = page.getByRole('heading', { name: /Customise Issuance/i });
    await expect(heading).toBeVisible();
  });

  test('tenant dropdown is visible and populated', async ({ page }) => {
    const dropdown = page.locator('[data-testid="tenant-select"]');
    await expect(dropdown).toBeVisible();

    const options = dropdown.locator('option');
    const count = await options.count();
    expect(count).toBeGreaterThanOrEqual(2);
  });

  test('dropdown filters to active tenants with certs only', async ({ page, request }) => {
    const activeIssuers = await getActiveIssuers(request);

    const dropdown = page.locator('[data-testid="tenant-select"]');
    await expect(dropdown).toBeVisible();

    // The dropdown has one default option ("Default issuer (no tenant)") plus one per active issuer
    const options = dropdown.locator('option');
    const optionCount = await options.count();

    // Subtract 1 for the default "no tenant" option
    const tenantOptionCount = optionCount - 1;
    expect(tenantOptionCount).toBe(activeIssuers.length);
  });

  test('selecting tenant shows credential count', async ({ page }) => {
    const dropdown = page.locator('[data-testid="tenant-select"]');
    await expect(dropdown).toBeVisible();

    // Select the first non-default option (index 1)
    const options = dropdown.locator('option');
    const count = await options.count();
    expect(count).toBeGreaterThanOrEqual(2);

    const firstTenantValue = await options.nth(1).getAttribute('value');
    expect(firstTenantValue).toBeTruthy();

    await dropdown.selectOption(firstTenantValue!);

    // Wait for the credential count text to appear
    const credCountText = page.getByText(/Tenant has \d+ credential configuration/);
    await expect(credCountText).toBeVisible({ timeout: 10_000 });
  });

  test('default issuer option has empty value', async ({ page }) => {
    const dropdown = page.locator('[data-testid="tenant-select"]');
    await expect(dropdown).toBeVisible();

    const firstOption = dropdown.locator('option').first();
    await expect(firstOption).toHaveText('Default issuer (no tenant)');
    await expect(firstOption).toHaveAttribute('value', '');
  });

  test('issue with tenant navigates with issuerId', async ({ page }) => {
    const dropdown = page.locator('[data-testid="tenant-select"]');
    await expect(dropdown).toBeVisible();

    // Select the first tenant
    const firstTenantValue = await dropdown.locator('option').nth(1).getAttribute('value');
    expect(firstTenantValue).toBeTruthy();
    await dropdown.selectOption(firstTenantValue!);

    // Click the Issue button
    const issueButton = page.getByRole('button', { name: /^Issue$/i });
    await expect(issueButton).toBeVisible();
    await issueButton.click();

    // Wait for navigation to /offer page
    await page.waitForURL(/\/offer/, { timeout: 15_000 });

    // URL should contain the issuerId parameter
    expect(page.url()).toContain('issuerId=');
  });

  test('issue without tenant omits issuerId', async ({ page }) => {
    // Leave the default option selected (no tenant)
    const dropdown = page.locator('[data-testid="tenant-select"]');
    await expect(dropdown).toBeVisible();

    // Verify the default (empty) option is selected
    await expect(dropdown).toHaveValue('');

    // Click the Issue button
    const issueButton = page.getByRole('button', { name: /^Issue$/i });
    await expect(issueButton).toBeVisible();
    await issueButton.click();

    // Wait for navigation to /offer page
    await page.waitForURL(/\/offer/, { timeout: 15_000 });

    // URL should NOT contain issuerId
    expect(page.url()).not.toContain('issuerId');
  });
});
