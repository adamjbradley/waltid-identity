import { test, expect } from '@playwright/test';
import { PORTAL_URL } from './helpers';

const CREDENTIALS_URL = `${PORTAL_URL}/credentials?ids=org.iso.18013.5.1.mDL`;

test('drive verification flow end-to-end', async ({ page }) => {
  test.setTimeout(60_000);

  page.on('console', msg => {
    if (msg.type() === 'log' || msg.type() === 'error' || msg.type() === 'warn') {
      console.log(`  [portal:${msg.type()}] ${msg.text()}`);
    }
  });

  page.on('request', req => {
    if (req.url().includes('/verification-session/') && req.method() === 'POST') {
      console.log(`  [NET] POST ${req.url()}`);
    }
  });

  page.on('response', res => {
    if (res.url().includes('/verification-session/') || res.url().includes('/verify')) {
      console.log(`  [NET] ${res.status()} ${res.url()}`);
    }
  });

  // Capture alert dialogs
  page.on('dialog', async dialog => {
    console.log(`  [DIALOG] ${dialog.type()}: ${dialog.message()}`);
    await dialog.accept();
  });

  // Navigate to credentials page in verify tab
  await page.goto(CREDENTIALS_URL);
  await page.waitForLoadState('load');
  await page.waitForTimeout(2_000);

  // Switch to Verify mode by clicking the Verify tab
  // The tab is a SelectButton which renders as a <button>
  const verifyTab = page.getByRole('button', { name: /Verify/i }).first();
  await verifyTab.click();
  await page.waitForTimeout(2_000);
  console.log('  URL after verify tab: ' + page.url());

  // Verify the VerificationSection is showing
  const heading = page.getByText('Customise Verification');
  await heading.waitFor({ state: 'visible', timeout: 5_000 }).catch(() => {});
  console.log('  Customise Verification visible: ' + await heading.isVisible().catch(() => false));

  // List all buttons
  const allBtns = await page.getByRole('button').allTextContents();
  console.log('  All buttons: ' + JSON.stringify(allBtns));
  await page.screenshot({ path: 'test-results/drive-verify-page.png' });

  // The Verify submit button is inside VerificationSection - it has padding: " Verify "
  const verifyBtn = page.getByRole('button', { name: /Verify/i }).last();
  if (!(await verifyBtn.isVisible().catch(() => false))) {
    console.log('  No Verify submit button visible');
    return;
  }

  // Click Verify and wait for network or dialog
  console.log('  Clicking Verify...');

  // Listen for any failed requests
  page.on('requestfailed', req => {
    console.log(`  [NET-FAIL] ${req.method()} ${req.url()} - ${req.failure()?.errorText}`);
  });

  await verifyBtn.click();
  await page.waitForTimeout(5_000);

  // Check for modal or error
  await page.screenshot({ path: 'test-results/drive-verify-after-click.png' });

  // Check for dialog (alert)
  const bodyText = await page.locator('body').innerText().catch(() => '');
  console.log('  Body (first 500): ' + bodyText.substring(0, 500));

  // Check for modal
  const modalHeading = page.getByRole('heading', { name: /Scan to Verify/i });
  const modalVisible = await modalHeading.isVisible().catch(() => false);
  console.log('  Modal visible: ' + modalVisible);

  if (modalVisible) {
    // Check for QR code
    const qrCode = page.locator('svg');
    console.log('  QR code visible: ' + await qrCode.first().isVisible().catch(() => false));
    await page.screenshot({ path: 'test-results/drive-verify-modal.png' });
    console.log('✓ Verification modal opened successfully');
  }

  console.log('✓ Done');
});
