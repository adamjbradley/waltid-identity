import { test, expect } from '@playwright/test';
import { PORTAL_URL } from './helpers';

test.describe('Homepage', () => {
  test.beforeEach(async ({ page }) => {
    await page.goto(PORTAL_URL);
    await page.waitForLoadState('load');
  });

  test('displays portal title', async ({ page }) => {
    const heading = page.locator('h1');
    await expect(heading).toBeVisible();
    await expect(heading).toContainText('Portal');
  });

  test('shows credential cards', async ({ page }) => {
    // Credential cards use a gradient background and are rendered in a grid
    const credentialCards = page.locator('.grid > div');
    await expect(credentialCards.first()).toBeVisible({ timeout: 10_000 });
    const count = await credentialCards.count();
    expect(count).toBeGreaterThan(0);
  });

  test('search box filters credentials', async ({ page }) => {
    // Wait for credential cards to be rendered
    const credentialCards = page.locator('.grid > div');
    await expect(credentialCards.first()).toBeVisible({ timeout: 10_000 });
    const initialCount = await credentialCards.count();
    expect(initialCount).toBeGreaterThan(0);

    // Type a search term that is unlikely to match all credentials
    const searchInput = page.locator('input[type="text"]');
    await expect(searchInput).toBeVisible();
    await searchInput.fill('zzzzz_no_match_expected');

    // After filtering, either the count drops or the "No Credential" message appears
    const noResult = page.locator('text=No Credential with that name');
    await expect(noResult).toBeVisible({ timeout: 5_000 });
    const filteredCount = await credentialCards.count();
    expect(filteredCount).toBe(0);

    // Clear the search and verify cards reappear
    await searchInput.clear();
    await expect(credentialCards.first()).toBeVisible({ timeout: 5_000 });
    const restoredCount = await credentialCards.count();
    expect(restoredCount).toBe(initialCount);
  });

  test('clicking credential navigates to credentials page', async ({ page }) => {
    // Wait for credential cards to load
    const credentialCards = page.locator('.grid > div');
    await expect(credentialCards.first()).toBeVisible({ timeout: 10_000 });

    // Click the first credential card
    await credentialCards.first().click();

    // URL should navigate to /credentials with an ids query param
    await page.waitForURL(/\/credentials/, { timeout: 10_000 });
    expect(page.url()).toContain('/credentials');
  });

  test('admin navigation is available', async ({ page }) => {
    // The admin button contains the text "Admin" and a cog icon
    const adminLink = page.locator('button', { hasText: 'Admin' });
    await expect(adminLink).toBeVisible();

    // Clicking admin navigates to the admin trust-config page
    await adminLink.click();
    await page.waitForURL(/\/admin\//, { timeout: 10_000 });
    expect(page.url()).toContain('/admin/');
  });
});
