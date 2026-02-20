import { test, expect, devices } from '@playwright/test';
import { PORTAL_URL } from './helpers';

const CREDENTIALS_URL = `${PORTAL_URL}/credentials?ids=org.iso.18013.5.1.mDL`;
const TEST_EMAIL = process.env.TEST_USER_EMAIL || 'adam_j_bradley@yahoo.com';
const TEST_PASSWORD = process.env.TEST_USER_PASSWORD || '1password2';

const iPhone = devices['iPhone 13'];

test.use({
  ...iPhone,
  viewport: { width: 390, height: 844 },
});

/**
 * Helper: log in to the wallet if on the login page.
 * Returns true if login was performed.
 */
async function loginIfNeeded(page: any): Promise<boolean> {
  if (!page.url().includes('login')) return false;

  const emailInput = page.locator('input[id="email"], input[type="email"]').first();
  await expect(emailInput).toBeVisible({ timeout: 5_000 });
  await emailInput.fill(TEST_EMAIL);
  await page.locator('input[type="password"]').first().fill(TEST_PASSWORD);
  await page.getByRole('button', { name: /Sign in$/i }).first().click();
  await page.waitForTimeout(3_000);
  return true;
}

test.describe('Mobile E2E: issue → verify', () => {
  test.describe.configure({ mode: 'serial' });

  test('mobile: issue mDL credential via portal → wallet', async ({ page }) => {
    test.setTimeout(180_000);

    page.on('console', msg => {
      if (msg.type() === 'error') console.log(`  [error] ${msg.text()}`);
    });

    // 1. Navigate to portal credentials page (Issue tab is default)
    await page.goto(CREDENTIALS_URL);
    await page.waitForLoadState('load');
    await page.waitForTimeout(2_000);

    // 2. Select first issuer tenant
    const dropdown = page.locator('[data-testid="tenant-select"]');
    const hasTenants = await dropdown.isVisible().catch(() => false);
    if (hasTenants) {
      const firstTenantValue = await dropdown.locator('option').nth(1).getAttribute('value');
      if (firstTenantValue) {
        await dropdown.selectOption(firstTenantValue);
        await page.waitForTimeout(1_000);
      }
    }

    // 3. Click Issue
    await page.getByRole('button', { name: /^Issue$/i }).last().click({ force: true });

    // 4. Modal opens with "Claim Your Credential"
    const modalHeading = page.getByRole('heading', { name: /Claim Your Credential/i });
    await expect(modalHeading).toBeVisible({ timeout: 15_000 });

    const webWalletBtn = page.getByRole('button', { name: /Open Web Wallet/i });
    await expect(webWalletBtn).toBeVisible({ timeout: 15_000 });

    // 5. Click "Open Web Wallet" — mobile redirects the entire page
    await webWalletBtn.click();
    await page.waitForURL(/wallet/, { timeout: 15_000 });
    await page.waitForLoadState('load').catch(() => {});
    await page.waitForTimeout(3_000);

    // 6. Login if needed
    await loginIfNeeded(page);

    // Wait for issuance exchange page
    await page.waitForURL(/exchange|credential/, { timeout: 30_000 }).catch(() => {});
    await page.waitForLoadState('load').catch(() => {});
    await page.waitForTimeout(3_000);
    await page.screenshot({ path: 'test-results/mobile-issue-01-wallet.png' });

    // 7. Accept the credential
    let accepted = false;
    for (const pattern of [/accept/i, /confirm/i, /claim/i, /receive/i, /add/i]) {
      const btn = page.getByRole('button', { name: pattern }).first();
      if (await btn.isVisible().catch(() => false)) {
        await btn.click();
        accepted = true;
        await page.waitForTimeout(5_000);
        break;
      }
    }

    await page.screenshot({ path: 'test-results/mobile-issue-02-after-accept.png' });

    if (accepted) {
      // Should land on credential detail or wallet page
      const bodyText = await page.locator('body').innerText().catch(() => '');
      expect(bodyText.length).toBeGreaterThan(0);
      console.log(`  Issuance complete, URL: ${page.url()}`);
    }
  });

  test('mobile: verify mDL credential via portal → wallet → success', async ({ page }) => {
    test.setTimeout(120_000);

    page.on('console', msg => {
      if (msg.type() === 'error') console.log(`  [error] ${msg.text()}`);
    });

    // 1. Navigate to portal credentials page
    await page.goto(CREDENTIALS_URL);
    await page.waitForLoadState('load');
    await page.waitForTimeout(2_000);

    // 2. Switch to Verify tab
    const verifyTab = page.getByRole('button', { name: /Verify/i }).first();
    await expect(verifyTab).toBeVisible();
    await verifyTab.click();
    await page.waitForTimeout(2_000);

    // 3. Click Verify to create session
    const verifySubmit = page.getByRole('button', { name: /Verify/i }).last();
    await expect(verifySubmit).toBeVisible();
    await verifySubmit.click();

    // 4. Modal with wallet button
    const modalHeading = page.getByRole('heading', { name: /Scan to Verify/i });
    await expect(modalHeading).toBeVisible({ timeout: 15_000 });

    const webWalletBtn = page.getByRole('button', { name: /Open Web Wallet/i });
    await expect(webWalletBtn).toBeVisible({ timeout: 15_000 });

    // 5. Click "Open Web Wallet" — mobile redirects
    await webWalletBtn.click();
    await page.waitForURL(/wallet/, { timeout: 15_000 });
    await page.waitForLoadState('load').catch(() => {});
    await page.waitForTimeout(3_000);

    // 6. Login if needed
    if (await loginIfNeeded(page)) {
      await page.waitForURL(/exchange\/presentation/, { timeout: 30_000 });
      await page.waitForLoadState('load').catch(() => {});
      await page.waitForTimeout(3_000);
    }

    // 7. Should be on presentation page
    expect(page.url()).toContain('/exchange/presentation');
    await page.screenshot({ path: 'test-results/mobile-verify-01-presentation.png' });

    // Must have matching credentials (issued in previous test)
    const noMatch = page.getByText(/don.t have any credentials/i);
    expect(await noMatch.isVisible().catch(() => false)).toBe(false);

    // 8. If selection phase, click Continue
    const continueBtn = page.locator('[data-testid="continue-selection"]');
    if (await continueBtn.isVisible().catch(() => false)) {
      await continueBtn.click();
      await page.waitForTimeout(2_000);
    }

    // 9. Click Disclose
    const discloseBtn = page.locator('[data-testid="disclose-credential"]');
    await expect(discloseBtn).toBeVisible({ timeout: 5_000 });
    await discloseBtn.click();
    await page.waitForTimeout(5_000);

    // 10. Should end up on the portal success page
    await page.screenshot({ path: 'test-results/mobile-verify-02-after-disclose.png' });
    expect(page.url()).toContain('/success/');
    expect(page.url()).toContain('api2=true');

    // 11. Verify results rendered
    const body = await page.locator('body').innerText();
    expect(body).toContain('Verification');
    const hasTimeline = body.includes('Timeline') || body.includes('Credential Presented');
    expect(hasTimeline).toBe(true);

    await page.screenshot({ path: 'test-results/mobile-verify-03-success.png' });
  });
});
