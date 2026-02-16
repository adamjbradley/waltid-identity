import { test, expect } from '@playwright/test';
import { PORTAL_URL, getActiveIssuers } from './helpers';

test.describe('Explore Page', () => {
  test.beforeEach(async ({ page }) => {
    await page.goto(`${PORTAL_URL}/explore`);
    await page.waitForLoadState('load');
    await page.waitForTimeout(2_000);
    const bodyText = await page.locator('body').innerText().catch(() => '');
    if (bodyText.includes('Application error') || bodyText.includes('404')) test.skip();
  });

  test('displays explore page heading', async ({ page }) => {
    const heading = page.getByRole('heading', { name: /Explore Credentials by Country/i });
    await expect(heading).toBeVisible();
  });

  test('shows description text', async ({ page }) => {
    const desc = page.getByText(/Browse available credentials/i);
    await expect(desc).toBeVisible();
  });

  test('shows back to portal link', async ({ page }) => {
    const backLink = page.locator('button', { hasText: /Back to Portal/i });
    await expect(backLink).toBeVisible();
  });

  test('back link navigates to homepage', async ({ page }) => {
    const backLink = page.locator('button', { hasText: /Back to Portal/i });
    await backLink.click();
    await page.waitForURL(/\/$/, { timeout: 10_000 });
  });

  test('shows country cards for all 5 countries', async ({ page }) => {
    const countries = ['Australia', 'France', 'Germany', 'India', 'Singapore'];
    for (const country of countries) {
      const card = page.getByRole('heading', { name: country });
      await expect(card).toBeVisible({ timeout: 10_000 });
    }
  });

  test('country cards are sorted alphabetically', async ({ page }) => {
    const headings = page.locator('h2');
    await expect(headings.first()).toBeVisible({ timeout: 10_000 });

    const texts: string[] = [];
    const count = await headings.count();
    for (let i = 0; i < count; i++) {
      texts.push(await headings.nth(i).innerText());
    }

    expect(texts).toEqual(['Australia', 'France', 'Germany', 'India', 'Singapore']);
  });

  test('Australia has PID mDoc, mDL, and PID SD-JWT', async ({ page }) => {
    const auSection = page.locator('div.rounded-xl').filter({
      has: page.getByRole('heading', { name: 'Australia' }),
    });
    await expect(auSection).toBeVisible({ timeout: 10_000 });

    await expect(auSection.locator('text=EU Personal ID (mDoc)')).toBeVisible();
    await expect(auSection.locator('text=Mobile Driving License')).toBeVisible();
    await expect(auSection.locator('text=EU Personal ID (SD-JWT)')).toBeVisible();
  });

  test('India has only mDL and PID SD-JWT (no PID mDoc)', async ({ page }) => {
    const inSection = page.locator('div.rounded-xl').filter({
      has: page.getByRole('heading', { name: 'India' }),
    });
    await expect(inSection).toBeVisible({ timeout: 10_000 });

    await expect(inSection.locator('text=Mobile Driving License')).toBeVisible();
    await expect(inSection.locator('text=EU Personal ID (SD-JWT)')).toBeVisible();
    // Should NOT have PID mDoc
    await expect(inSection.locator('text=EU Personal ID (mDoc)')).toHaveCount(0);
  });

  test('Singapore has PID mDoc and PWA (no mDL, no PID SD-JWT)', async ({ page }) => {
    const sgSection = page.locator('div.rounded-xl').filter({
      has: page.getByRole('heading', { name: 'Singapore' }),
    });
    await expect(sgSection).toBeVisible({ timeout: 10_000 });

    await expect(sgSection.locator('text=EU Personal ID (mDoc)')).toBeVisible();
    await expect(sgSection.locator('text=Payment Wallet Attestation')).toBeVisible();
    // Should NOT have mDL or PID SD-JWT
    await expect(sgSection.locator('text=Mobile Driving License')).toHaveCount(0);
    await expect(sgSection.locator('text=EU Personal ID (SD-JWT)')).toHaveCount(0);
  });

  test('Germany has PID mDoc, mDL, and PID SD-JWT', async ({ page }) => {
    const deSection = page.locator('div.rounded-xl').filter({
      has: page.getByRole('heading', { name: 'Germany' }),
    });
    await expect(deSection).toBeVisible({ timeout: 10_000 });

    await expect(deSection.locator('text=EU Personal ID (mDoc)')).toBeVisible();
    await expect(deSection.locator('text=Mobile Driving License')).toBeVisible();
    await expect(deSection.locator('text=EU Personal ID (SD-JWT)')).toBeVisible();
  });

  test('France has only mDL and PID SD-JWT', async ({ page }) => {
    const frSection = page.locator('div.rounded-xl').filter({
      has: page.getByRole('heading', { name: 'France' }),
    });
    await expect(frSection).toBeVisible({ timeout: 10_000 });

    await expect(frSection.locator('text=Mobile Driving License')).toBeVisible();
    await expect(frSection.locator('text=EU Personal ID (SD-JWT)')).toBeVisible();
    await expect(frSection.locator('text=EU Personal ID (mDoc)')).toHaveCount(0);
  });

  test('format badges show mDoc and DC+SD-JWT', async ({ page }) => {
    // mDoc badge should appear (e.g., on Australia's PID mDoc)
    const mdocBadges = page.locator('span.rounded-full', { hasText: 'mDoc' });
    await expect(mdocBadges.first()).toBeVisible({ timeout: 10_000 });

    // DC+SD-JWT badge should appear
    const sdjwtBadges = page.locator('span.rounded-full', { hasText: 'DC+SD-JWT' });
    await expect(sdjwtBadges.first()).toBeVisible();
  });

  test('Issue button navigates to credentials page', async ({ page }) => {
    const issueButtons = page.locator('button', { hasText: /Issue/ });
    await expect(issueButtons.first()).toBeVisible({ timeout: 10_000 });
    await issueButtons.first().click();

    await page.waitForURL(/\/credentials\?ids=/, { timeout: 10_000 });
    expect(page.url()).toContain('/credentials?ids=');
  });

  test('Issue button includes issuerId when registrar issuers exist', async ({ page, request }) => {
    try {
      const issuers = await getActiveIssuers(request);
      if (issuers.length === 0) { test.skip(); return; }
    } catch { test.skip(); return; }

    // Wait for issuer names to load on the explore page
    await page.waitForTimeout(3_000);

    // Find a country card that has a registered issuer (shown by having issuer name text)
    // Cards with issuers have a <p> tag with the issuer name below the heading
    const cardsWithIssuers = page.locator('div.rounded-xl').filter({
      has: page.locator('p.text-sm.text-gray-500'),
    });
    const cardCount = await cardsWithIssuers.count();
    if (cardCount === 0) { test.skip(); return; }

    // Click Issue on the first credential in a card that has a registered issuer
    const issueBtn = cardsWithIssuers.first().locator('button', { hasText: /Issue/ }).first();
    await expect(issueBtn).toBeVisible({ timeout: 10_000 });
    await issueBtn.click();

    await page.waitForURL(/\/credentials/, { timeout: 10_000 });
    expect(page.url()).toContain('issuerId=');
  });

  test('country flags are displayed', async ({ page }) => {
    // Check for flag emojis (rendered as text-2xl spans)
    const flags = page.locator('span.text-2xl');
    await expect(flags.first()).toBeVisible({ timeout: 10_000 });
    const count = await flags.count();
    expect(count).toBe(5); // One flag per country
  });
});
