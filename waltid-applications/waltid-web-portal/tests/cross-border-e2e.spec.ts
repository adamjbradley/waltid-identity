/**
 * Cross-Border E2E Tests: Issue → Hold → Verify with ETSI Trust Policies
 *
 * Two scenarios run in serial order:
 * 1. Without custom TSL — ETSI trust check fails, verification page shows failure
 * 2. With custom TSL imported — all policies pass, verification succeeds
 *
 * Prerequisites:
 * - Docker compose running (ISSUER_REGISTRAR_ENABLED=true, TRUST_LISTS_ENABLED=true)
 * - At least one foreign issuer tenant with PID credential (dc+sd-jwt, urn:eudi:pid:1)
 */
import { test, expect } from '@playwright/test';
import {
  PORTAL_URL,
  WALLET_API,
  ISSUER_API,
  VERIFIER_API2,
  setupWalletAuth,
  getActiveIssuers,
  getIssuerDetail,
  getTrustStatus,
  importCustomTsl,
  removeCustomTsl,
  refreshTrustLists,
  getCustomTsls,
} from './helpers';

// Run tests in this file serially — test 2 depends on TSL state set up after test 1
test.describe.configure({ mode: 'serial' });

// These tests involve trust list refresh which can take >60s
test.setTimeout(180_000);

// Shared state across serial tests
let walletId: string;
let token: string;
let foreignIssuer: { id: string; legalName: string; country: string } | null = null;

/**
 * Issue a credential from a foreign tenant and claim it in the wallet.
 * Returns the claimed credential ID and the issuer's country code.
 */
async function issueAndClaim(page: any, request: any): Promise<{ credId: string; country: string }> {
  // ── Find an active foreign issuer with PID credential ──
  const issuers = await getActiveIssuers(request);
  const candidates = issuers.filter(i => i.country !== 'AU');
  expect(candidates.length, 'Need at least one active foreign issuer').toBeGreaterThan(0);

  if (!foreignIssuer) {
    for (const issuer of candidates) {
      const detail = await getIssuerDetail(request, issuer.id);
      const configs = detail.credentialConfigurations || {};
      const credList = (configs as any).credentials || Object.values(configs);
      for (const c of credList as any[]) {
        if (c.format === 'dc+sd-jwt' && c.vct === 'urn:eudi:pid:1') {
          foreignIssuer = { id: issuer.id, legalName: issuer.legalName, country: issuer.country };
          break;
        }
      }
      if (foreignIssuer) break;
    }
  }
  expect(foreignIssuer, 'Need a foreign issuer with PID (dc+sd-jwt, urn:eudi:pid:1)').toBeTruthy();

  // ── Navigate portal to issuance with tenant pre-selected ──
  await page.goto(
    `${PORTAL_URL}/credentials?ids=urn:eudi:pid:1&mode=issuance&issuerId=${foreignIssuer!.id}`
  );
  await page.waitForLoadState('load');
  await page.locator('.bg-blue-100:text("EUDI")').waitFor({ state: 'visible', timeout: 10_000 });

  const tenantSelect = page.locator('[data-testid="tenant-select"]');
  if (await tenantSelect.isVisible()) {
    const selectedValue = await tenantSelect.inputValue();
    expect(selectedValue).toBe(foreignIssuer!.id);
  }

  // ── Issue and capture offer URL ──
  let offerUrl = '';
  page.on('response', async (resp: any) => {
    try {
      if (resp.request().method() === 'POST' && resp.url().includes('/issue') && resp.ok()) {
        const text = await resp.text();
        const cleaned = text.replace(/^"|"$/g, '');
        if (cleaned.includes('credential_offer')) {
          offerUrl = cleaned;
        }
      }
    } catch (_) { /* response body may be consumed */ }
  });

  const issueButton = page.getByRole('button', { name: /^Issue$/i }).last();
  await expect(issueButton).toBeEnabled({ timeout: 5_000 });
  await issueButton.click();

  await page.waitForURL(/\/offer/, { timeout: 15_000 });
  await expect(page.getByRole('heading', { name: 'Claim Your Credential' })).toBeVisible({ timeout: 10_000 });

  const copyLink = page.getByText('Copy offer URL');
  await copyLink.waitFor({ state: 'visible', timeout: 30_000 });
  await page.waitForTimeout(2_000);

  if (!offerUrl) {
    await page.context().grantPermissions(['clipboard-read', 'clipboard-write']);
    await copyLink.click();
    await page.waitForTimeout(500);
    offerUrl = await page.evaluate(() => navigator.clipboard.readText());
  }

  expect(offerUrl, 'Should have a credential offer URL').toBeTruthy();

  // ── Claim in wallet ──
  let walletOfferUrl = offerUrl;
  const uriMatch = offerUrl.match(/credential_offer_uri=([^&]+)/);
  if (uriMatch) {
    const credOfferUri = decodeURIComponent(uriMatch[1]);
    const localUri = credOfferUri.replace(/http:\/\/(issuer-api|caddy):7002/, ISSUER_API);
    const offerRes = await request.get(localUri);
    if (offerRes.ok()) {
      const offerJson = await offerRes.text();
      walletOfferUrl = `openid-credential-offer://?credential_offer=${encodeURIComponent(offerJson)}`;
    }
  }

  const claimRes = await request.post(
    `${WALLET_API}/wallet-api/wallet/${walletId}/exchange/useOfferRequest`,
    {
      headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'text/plain' },
      data: walletOfferUrl,
    }
  );
  expect(claimRes.ok(), `Wallet should accept the offer: ${claimRes.status()}`).toBeTruthy();
  const claimedCreds = await claimRes.json();
  expect(claimedCreds.length).toBeGreaterThan(0);

  return { credId: claimedCreds[0].id, country: foreignIssuer!.country };
}

/**
 * Create a verification session, present the credential, and return the session ID.
 */
async function verifyCredential(page: any, request: any, credId: string): Promise<string> {
  let verificationSessionUrl = '';
  let verificationSessionId = '';
  page.on('response', async (resp: any) => {
    try {
      if (resp.request().method() === 'POST' && resp.url().includes('verification-session') && resp.ok()) {
        const text = await resp.text();
        const body = JSON.parse(text);
        if (body.bootstrapAuthorizationRequestUrl) verificationSessionUrl = body.bootstrapAuthorizationRequestUrl;
        if (body.sessionId) verificationSessionId = body.sessionId;
      }
    } catch (_) { /* response body already consumed */ }
  });

  await page.goto(`${PORTAL_URL}/credentials?ids=urn:eudi:pid:1&mode=verification`);
  await page.waitForLoadState('load');
  await page.locator('.bg-blue-100:text("EUDI")').waitFor({ state: 'visible', timeout: 10_000 });

  await expect(page.getByText('Signature Policy')).toBeVisible();
  await expect(page.getByText('EUDI Trust List')).toBeVisible();

  const verifyButton = page.getByRole('button', { name: /^Verify$/i }).last();
  await verifyButton.click();
  await page.waitForURL(/\/verify/, { timeout: 15_000 });

  await page.getByRole('button', { name: 'Open Local Wallet' }).waitFor({ state: 'visible', timeout: 30_000 });
  await page.waitForTimeout(1_000);

  if (!verificationSessionUrl) {
    await page.context().grantPermissions(['clipboard-read', 'clipboard-write']);
    await page.getByRole('button', { name: 'Copy offer URL' }).click();
    await page.waitForTimeout(500);
    verificationSessionUrl = await page.evaluate(() => navigator.clipboard.readText());
  }

  expect(verificationSessionUrl, 'Should capture verify URL').toBeTruthy();

  // Present credential via wallet API
  const presentRes = await request.post(
    `${WALLET_API}/wallet-api/wallet/${walletId}/exchange/usePresentationRequest`,
    {
      headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' },
      data: {
        presentationRequest: verificationSessionUrl,
        selectedCredentials: [credId],
      },
    }
  );
  expect(presentRes.ok(), `Wallet should present the credential: ${presentRes.status()}`).toBeTruthy();

  const stateMatch = verificationSessionUrl.match(/[?&]state=([^&]+)/);
  const sessionId = verificationSessionId || (stateMatch ? decodeURIComponent(stateMatch[1]) : '');
  expect(sessionId, 'Should have a session ID').toBeTruthy();
  return sessionId;
}

test.describe('Cross-Border Issuance → Hold → Verify', () => {
  test.beforeAll(async ({ request }) => {
    const auth = await setupWalletAuth(request);
    walletId = auth.walletId;
    token = auth.token;
  });

  test('verification fails when issuer TSL is not loaded', async ({ page, request }) => {
    // ── Ensure custom TSLs for foreign issuers are removed ──
    const issuers = await getActiveIssuers(request);
    const foreignCountries = [...new Set(issuers.filter(i => i.country !== 'AU').map(i => i.country))];
    expect(foreignCountries.length).toBeGreaterThan(0);

    const customTsls = await getCustomTsls(request);
    const loadedCountries = (customTsls as any).customTsls?.map((t: any) => t.country) || Object.keys(customTsls);
    for (const country of foreignCountries) {
      if (loadedCountries.includes(country)) {
        await removeCustomTsl(request, country);
      }
    }

    // Verify the foreign country TSLs are removed (no full refresh needed —
    // removal takes effect immediately for subsequent verifications)
    const updatedTsls = await getCustomTsls(request);
    const remaining = (updatedTsls as any).customTsls?.map((t: any) => t.country) || Object.keys(updatedTsls);
    for (const country of foreignCountries) {
      expect(remaining).not.toContain(country);
    }

    // ── Issue + claim ──
    const { credId } = await issueAndClaim(page, request);

    // ── Verify — should fail on ETSI trust ──
    const sessionId = await verifyCredential(page, request, credId);

    await page.goto(`${PORTAL_URL}/success/${sessionId}?api2=true`);
    await page.waitForLoadState('load');
    await page.waitForTimeout(3_000);

    // Should show failure page
    await expect(page.getByText('Verification Failed').first()).toBeVisible({ timeout: 10_000 });
    await expect(page.getByText(/ETSI Trust List/i).first()).toBeVisible();

    // API should confirm etsi-trusted-issuer policy failed
    const sessionInfo = await request.get(`${VERIFIER_API2}/verification-session/${sessionId}/info`);
    expect(sessionInfo.ok()).toBeTruthy();
    const session = await sessionInfo.json();
    const vcPolicies = session.policyResults?.vc_policies || [];
    const etsiPolicy = vcPolicies.find((p: any) =>
      (p.policy?.id || p.policy?.policy || '') === 'etsi-trusted-issuer'
    );
    expect(etsiPolicy, 'etsi-trusted-issuer policy should be in results').toBeTruthy();
    expect(etsiPolicy.success, 'etsi-trusted-issuer should FAIL without TSL').toBe(false);
  });

  test('verification succeeds after importing issuer TSL', async ({ page, request }) => {
    // ── Import the custom TSL for the foreign issuer's country ──
    expect(foreignIssuer, 'Foreign issuer should be set from previous test').toBeTruthy();
    const country = foreignIssuer!.country;

    // The issuer-api generates TSL XML at /admin/issuer/tsl/{CC}.xml
    // Use the Docker-internal URL so the verifier container can fetch it
    const tslUrl = `http://caddy:7002/admin/issuer/tsl/${country}.xml`;
    const imported = await importCustomTsl(request, country, tslUrl);
    expect(imported, `Should import custom TSL for ${country}`).toBeTruthy();

    // Refresh trust lists to load the new TSL
    await refreshTrustLists(request);

    // Wait for async refresh to settle
    await page.waitForTimeout(3_000);

    // Verify the TSL is now loaded
    const trustStatus = await getTrustStatus(request);
    const etsiKey = Object.keys(trustStatus.sources).find(k => k.toLowerCase().includes('etsi'));
    expect(etsiKey).toBeTruthy();
    expect(trustStatus.sources[etsiKey!].entryCount).toBeGreaterThan(0);

    // ── Issue + claim a fresh credential ──
    const { credId } = await issueAndClaim(page, request);

    // ── Verify — should now pass all policies ──
    const sessionId = await verifyCredential(page, request, credId);

    await page.goto(`${PORTAL_URL}/success/${sessionId}?api2=true`);
    await page.waitForLoadState('load');
    await page.waitForTimeout(3_000);

    // Should show success page with presented credentials
    await expect(page.getByRole('heading', { name: 'Presented Credentials' })).toBeVisible({ timeout: 10_000 });

    // API should confirm all policies pass
    const sessionInfo = await request.get(`${VERIFIER_API2}/verification-session/${sessionId}/info`);
    expect(sessionInfo.ok()).toBeTruthy();
    const session = await sessionInfo.json();
    const vcPolicies = session.policyResults?.vc_policies || [];

    const policyIds = vcPolicies.map((p: any) => p.policy?.id || p.policy?.policy || 'unknown');
    expect(policyIds).toContain('signature');
    expect(policyIds).toContain('expiration');
    expect(policyIds).toContain('revoked-status-list');
    expect(policyIds).toContain('etsi-trusted-issuer');

    // All policies should pass
    for (const policy of vcPolicies) {
      const policyId = policy.policy?.id || policy.policy?.policy || 'unknown';
      expect(policy.success, `Policy "${policyId}" should pass`).toBe(true);
    }
  });
});
