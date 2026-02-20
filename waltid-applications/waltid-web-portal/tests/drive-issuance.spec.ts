import { test, expect } from '@playwright/test';
import { PORTAL_URL } from './helpers';

const CREDENTIALS_URL = `${PORTAL_URL}/credentials?ids=org.iso.18013.5.1.mDL`;

test('drive issuance flow end-to-end', async ({ page, context }) => {
  test.setTimeout(180_000);

  // Enable console log forwarding
  page.on('console', msg => {
    if (msg.type() === 'log' || msg.type() === 'error') {
      console.log(`  [portal] ${msg.text()}`);
    }
  });

  // Intercept network requests to issuer
  page.on('request', req => {
    if (req.url().includes('/openid4vc/') && req.method() === 'POST') {
      const headers = req.headers();
      console.log(`  [NET] POST ${req.url()}`);
      console.log(`  [NET] statusCallbackUri: ${headers['statuscallbackuri'] || headers['statusCallbackUri'] || 'NOT SET'}`);
    }
  });

  await page.goto(CREDENTIALS_URL);
  await page.waitForLoadState('load');
  await page.waitForTimeout(2_000);

  // Select first tenant
  const dropdown = page.locator('[data-testid="tenant-select"]');
  if (!(await dropdown.isVisible().catch(() => false))) {
    console.log('✗ No tenant dropdown');
    return;
  }
  const firstTenantValue = await dropdown.locator('option').nth(1).getAttribute('value');
  if (!firstTenantValue) { console.log('✗ No tenants'); return; }
  await dropdown.selectOption(firstTenantValue);
  console.log(`✓ Selected tenant: ${firstTenantValue}`);
  await page.waitForTimeout(1_000);

  // Click Issue
  await page.getByRole('button', { name: /^Issue$/i }).last().click({ force: true });

  const modalHeading = page.getByRole('heading', { name: /Claim Your Credential/i });
  try { await modalHeading.waitFor({ state: 'visible', timeout: 15_000 }); }
  catch { console.log('✗ Modal did not open'); return; }
  console.log('✓ Modal opened');

  // Wait for wallet button
  const webWalletBtn = page.getByRole('button', { name: /Open Web Wallet/i });
  try { await webWalletBtn.waitFor({ state: 'visible', timeout: 15_000 }); }
  catch { console.log('✗ No wallet button'); return; }

  // Open popup
  const popupPromise = context.waitForEvent('page', { timeout: 15_000 });
  await webWalletBtn.click();
  let walletPage: any;
  try { walletPage = await popupPromise; }
  catch { console.log('✗ No popup'); return; }

  await walletPage.waitForLoadState('networkidle', { timeout: 30_000 }).catch(() => {});
  console.log('✓ Wallet: ' + walletPage.url());

  // Login
  const emailInput = walletPage.locator('input[id="email"], input[type="email"]').first();
  if (await emailInput.isVisible().catch(() => false)) {
    await emailInput.fill('adam_j_bradley@yahoo.com');
    await walletPage.locator('input[type="password"]').first().fill('1password2');
    await walletPage.getByRole('button', { name: /Sign in$/i }).first().click();
    console.log('  Signing in...');
    // Wait for redirect to exchange/issuance page after login
    await walletPage.waitForURL(/exchange|credential/, { timeout: 30_000 }).catch(() => {});
    await walletPage.waitForLoadState('networkidle', { timeout: 15_000 }).catch(() => {});
    await walletPage.waitForTimeout(2_000);
    console.log('  Wallet after login: ' + walletPage.url());
    await walletPage.screenshot({ path: 'test-results/drive-wallet-after-login.png' });
  }

  // List buttons
  const btns = await walletPage.getByRole('button').allTextContents();
  console.log('  Wallet buttons: ' + JSON.stringify(btns));

  // Try accept
  let accepted = false;
  for (const pattern of [/accept/i, /confirm/i, /claim/i, /receive/i, /add/i, /continue/i]) {
    const btn = walletPage.getByRole('button', { name: pattern }).first();
    if (await btn.isVisible().catch(() => false)) {
      console.log(`✓ Clicking: ${pattern}`);
      await btn.click();
      accepted = true;
      await walletPage.waitForTimeout(5_000);
      await walletPage.screenshot({ path: 'test-results/drive-wallet-after-accept.png' });
      console.log('  After accept: ' + walletPage.url());
      // Check for more buttons
      const btns2 = await walletPage.getByRole('button').allTextContents();
      console.log('  Buttons now: ' + JSON.stringify(btns2));
      break;
    }
  }

  if (!accepted) {
    // Maybe it auto-accepted, check page content
    const bodyText = await walletPage.locator('body').innerText().catch(() => '');
    console.log('  Wallet body (first 500): ' + bodyText.substring(0, 500));
    await walletPage.screenshot({ path: 'test-results/drive-wallet-current.png' });
  }

  // Wait for issuer callback
  console.log('  Waiting 10s for issuer callback...');
  await page.waitForTimeout(10_000);

  const checkState = async (label: string) => {
    const s = await page.getByText('Credential Issued').isVisible().catch(() => false);
    const c = await page.getByText('Wallet closed').isVisible().catch(() => false);
    const f = await page.getByText('Issuance Failed').isVisible().catch(() => false);
    const w = await page.getByText('Waiting for credential acceptance').isVisible().catch(() => false);
    console.log(`  [${label}] success:${s} closed:${c} failed:${f} waiting:${w}`);
    await page.screenshot({ path: `test-results/drive-${label}.png` });
  };

  await checkState('after-wait');

  // Close popup if still open
  if (!walletPage.isClosed()) {
    await walletPage.close();
    console.log('✓ Closed popup');
    await page.waitForTimeout(5_000);
    await checkState('after-close');
  }

  console.log('✓ Done');
});
