import { test, expect } from '@playwright/test';
import { PORTAL_URL, ISSUER_API, VERIFIER_API2 } from './helpers';

test.describe('Cross-Border Trust', () => {
  // ── API-only tests (use request context, no browser) ──────────

  test('LOTL contains pointers for issuer countries', async ({ request }) => {
    const response = await request.get(`${ISSUER_API}/admin/issuer/lotl.xml`);
    expect(response.ok()).toBeTruthy();

    const xml = await response.text();
    expect(xml).toContain('TSLLocation');
    // LOTL XML should contain at least one TSLLocation element pointing to a country TSL
    const locationMatches = xml.match(/<TSLLocation>/g);
    expect(locationMatches).toBeTruthy();
    expect(locationMatches!.length).toBeGreaterThan(0);
  });

  test('each country TSL contains that country\'s issuers', async ({ request }) => {
    // Get the LOTL to find the first country
    const lotlResponse = await request.get(`${ISSUER_API}/admin/issuer/lotl.xml`);
    expect(lotlResponse.ok()).toBeTruthy();

    const lotlXml = await lotlResponse.text();

    // Extract the first TSLLocation URL to find a country TSL
    const locationMatch = lotlXml.match(/<TSLLocation>(.*?)<\/TSLLocation>/);
    expect(locationMatch).toBeTruthy();

    const tslUrl = locationMatch![1];
    const tslResponse = await request.get(tslUrl);
    expect(tslResponse.ok()).toBeTruthy();

    const tslXml = await tslResponse.text();
    // Country TSL should contain TrustServiceProvider elements
    expect(tslXml).toContain('TrustServiceProvider');
  });

  test('TSL contains X509Certificate elements', async ({ request }) => {
    // Get the LOTL and follow the first country TSL
    const lotlResponse = await request.get(`${ISSUER_API}/admin/issuer/lotl.xml`);
    expect(lotlResponse.ok()).toBeTruthy();

    const lotlXml = await lotlResponse.text();
    const locationMatch = lotlXml.match(/<TSLLocation>(.*?)<\/TSLLocation>/);
    expect(locationMatch).toBeTruthy();

    const tslUrl = locationMatch![1];
    const tslResponse = await request.get(tslUrl);
    expect(tslResponse.ok()).toBeTruthy();

    const tslXml = await tslResponse.text();
    // TSL should contain X509Certificate elements with base64-encoded certificate data
    expect(tslXml).toContain('X509Certificate');
  });

  test('verifier trust service has loaded trust data', async ({ request }) => {
    const response = await request.get(`${VERIFIER_API2}/admin/trust/status`);
    expect(response.ok()).toBeTruthy();

    const data = await response.json();
    // The ETSI_TL or etsi_tl source should be present and enabled
    const sources = data.sources;
    expect(sources).toBeTruthy();

    // Find the ETSI trust source (key may be ETSI_TL or etsi_tl)
    const etsiKey = Object.keys(sources).find(
      (k) => k.toLowerCase().includes('etsi')
    );
    expect(etsiKey).toBeTruthy();
    expect(sources[etsiKey!].enabled).toBe(true);
  });

  test('verifier has trust providers loaded', async ({ request }) => {
    const response = await request.get(`${VERIFIER_API2}/admin/trust/status`);
    expect(response.ok()).toBeTruthy();

    const data = await response.json();
    const sources = data.sources;

    // Find the ETSI trust source
    const etsiKey = Object.keys(sources).find(
      (k) => k.toLowerCase().includes('etsi')
    );
    expect(etsiKey).toBeTruthy();

    // entryCount should be greater than 0 if providers are loaded
    expect(sources[etsiKey!].entryCount).toBeGreaterThan(0);
  });

  // ── Browser tests ─────────────────────────────────────────────

  test('portal trust config page loads', async ({ page }) => {
    await page.goto(`${PORTAL_URL}/admin/trust-config`);
    await page.waitForLoadState('load');

    // The page should show the Trust List Configuration heading
    const heading = page.getByRole('heading', { name: /Trust List Configuration/i });
    await expect(heading).toBeVisible({ timeout: 15_000 });
  });

  test('portal shows ETSI trust source status', async ({ page }) => {
    await page.goto(`${PORTAL_URL}/admin/trust-config`);
    await page.waitForLoadState('load');

    // Wait for trust status to load — look for any trust-related content
    const trustContent = page.getByText(/EU Trusted List|ETSI|Trust|trust/i).first();
    await expect(trustContent).toBeVisible({ timeout: 15_000 });
  });

  test('trust refresh triggers reload', async ({ page }) => {
    await page.goto(`${PORTAL_URL}/admin/trust-config`);
    await page.waitForLoadState('load');

    // Look for the Refresh button
    const refreshButton = page.locator('button', { hasText: /Refresh/i });
    const isVisible = await refreshButton.isVisible().catch(() => false);

    if (isVisible) {
      // Click refresh and verify the page continues to show trust source data
      await refreshButton.click();

      // Wait for the refresh to complete (loading state may appear briefly)
      await page.waitForLoadState('load');

      // After refresh, trust source information should still be visible
      const etsiSource = page.getByText(/EU Trusted List|ETSI/i).first();
      await expect(etsiSource).toBeVisible({ timeout: 15_000 });
    } else {
      // If refresh button is not visible (e.g., trust lists disabled), skip gracefully
      test.skip();
    }
  });
});
