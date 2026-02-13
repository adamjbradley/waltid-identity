/**
 * Cross-Border E2E Test: Issue → Hold → Verify with ETSI Trust Policies
 *
 * Prerequisites:
 * - Docker compose running from worktree (ISSUER_REGISTRAR_ENABLED=true, TRUST_LISTS_ENABLED=true)
 * - Issuer-api baseUrl set to Docker-internal hostname (http://issuer-api:7002) so the
 *   wallet container can reach it. The test resolves credential_offer_uri via localhost.
 * - Custom TSLs imported for tenant issuer countries (e.g. IN, SG) using Docker-internal URLs
 *   (http://issuer-api:7002/admin/issuer/tsl/{CC}.xml)
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
} from './helpers';

test.describe('Cross-Border Issuance → Hold → Verify', () => {
  let walletId: string;
  let token: string;

  test.beforeAll(async ({ request }) => {
    const auth = await setupWalletAuth(request);
    walletId = auth.walletId;
    token = auth.token;
  });

  test('issue via portal, hold in wallet, verify via portal with trust policies', async ({ page, request }) => {
    // ── Step 1: Find an active foreign issuer with PID credential ──
    const issuers = await getActiveIssuers(request);
    const foreignIssuers = issuers.filter(i => i.country !== 'AU');
    expect(foreignIssuers.length, 'Need at least one active foreign issuer').toBeGreaterThan(0);

    let foreignIssuer: typeof issuers[0] | null = null;
    for (const issuer of foreignIssuers) {
      const detail = await getIssuerDetail(request, issuer.id);
      const configs = detail.credentialConfigurations || {};
      const credList = (configs as any).credentials || Object.values(configs);
      for (const c of credList as any[]) {
        if (c.format === 'dc+sd-jwt' && c.vct === 'urn:eudi:pid:1') {
          foreignIssuer = issuer;
          break;
        }
      }
      if (foreignIssuer) break;
    }
    expect(foreignIssuer, 'Need a foreign issuer with PID (dc+sd-jwt, urn:eudi:pid:1)').toBeTruthy();

    // ── Step 2: Navigate portal to issuance with tenant pre-selected ──
    await page.goto(
      `${PORTAL_URL}/credentials?ids=urn:eudi:pid:1&mode=issuance&issuerId=${foreignIssuer!.id}`
    );
    await page.waitForLoadState('load');

    // Verify the EUDI badge is visible (confirms DC+SD-JWT format)
    await page.locator('.bg-blue-100:text("EUDI")').waitFor({ state: 'visible', timeout: 10_000 });

    // Verify the tenant dropdown shows the foreign issuer
    const tenantSelect = page.locator('[data-testid="tenant-select"]');
    if (await tenantSelect.isVisible()) {
      const selectedValue = await tenantSelect.inputValue();
      expect(selectedValue).toBe(foreignIssuer!.id);
    }

    // ── Step 3: Click Issue and capture offer URL from the offer page ──
    let offerUrl = '';
    page.on('response', async resp => {
      try {
        if (resp.request().method() === 'POST' && resp.url().includes('/issue') && resp.ok()) {
          const text = await resp.text();
          // The offer URL is returned as a quoted string
          const cleaned = text.replace(/^"|"$/g, '');
          if (cleaned.includes('credential_offer')) {
            offerUrl = cleaned;
          }
        }
      } catch (_) { /* response body may be consumed */ }
    });

    // The page has two "Issue" buttons: the mode toggle (top) and the action button (bottom).
    // Target the action button — it has the primary bg color class.
    const issueButton = page.getByRole('button', { name: /^Issue$/i }).last();
    await expect(issueButton).toBeEnabled({ timeout: 5_000 });
    await issueButton.click();

    // Wait for offer page to load
    await page.waitForURL(/\/offer/, { timeout: 15_000 });
    await expect(page.getByRole('heading', { name: 'Claim Your Credential' })).toBeVisible({ timeout: 10_000 });

    // Wait for QR / offer URL to be ready — the "Copy offer URL" link appears immediately,
    // but "Open in EUDI Wallet" only appears when the EUDI-format offer is generated.
    // Try EUDI button first, fall back to Copy offer URL.
    const copyLink = page.getByText('Copy offer URL');
    await copyLink.waitFor({ state: 'visible', timeout: 30_000 });
    await page.waitForTimeout(2_000);

    // Grab offer URL from clipboard or response interception
    if (!offerUrl) {
      await page.context().grantPermissions(['clipboard-read', 'clipboard-write']);
      await copyLink.click();
      await page.waitForTimeout(500);
      offerUrl = await page.evaluate(() => navigator.clipboard.readText());
    }

    expect(offerUrl, 'Should have a credential offer URL from the portal').toBeTruthy();
    expect(offerUrl).toContain('credential_offer');

    // ── Step 4: Claim credential in wallet via API ──────────────
    // The issuer-api baseUrl uses the Docker-internal hostname (issuer-api:7002)
    // so the wallet container can reach it. The credential_offer_uri also uses this
    // hostname, which the test runner (host) can't resolve directly.
    // Resolve the offer via localhost and send inline to the wallet.
    let walletOfferUrl = offerUrl;
    const uriMatch = offerUrl.match(/credential_offer_uri=([^&]+)/);
    if (uriMatch) {
      const credOfferUri = decodeURIComponent(uriMatch[1]);
      const localUri = credOfferUri.replace(/http:\/\/issuer-api:7002/, ISSUER_API);
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
    if (!claimRes.ok()) {
      console.error('Wallet claim failed:', claimRes.status(), await claimRes.text());
    }
    expect(claimRes.ok(), `Wallet should accept the offer: ${claimRes.status()}`).toBeTruthy();
    const claimedCreds = await claimRes.json();
    expect(Array.isArray(claimedCreds)).toBeTruthy();
    expect(claimedCreds.length).toBeGreaterThan(0);
    const claimedCredId = claimedCreds[0].id;

    // ── Step 5: Verify trust lists are loaded ───────────────────
    const trustStatus = await getTrustStatus(request);
    const etsiKey = Object.keys(trustStatus.sources).find(k => k.toLowerCase().includes('etsi'));
    expect(etsiKey, 'ETSI trust source should be present').toBeTruthy();
    expect(trustStatus.sources[etsiKey!].enabled).toBe(true);

    // ── Step 6: Navigate portal to verification with policies ───
    let verificationSessionUrl = '';
    let verificationSessionId = '';
    page.on('response', async resp => {
      try {
        if (resp.request().method() === 'POST' && resp.url().includes('verification-session') && resp.ok()) {
          const text = await resp.text();
          const body = JSON.parse(text);
          if (body.bootstrapAuthorizationRequestUrl) {
            verificationSessionUrl = body.bootstrapAuthorizationRequestUrl;
          }
          if (body.sessionId) {
            verificationSessionId = body.sessionId;
          }
        }
      } catch (_) { /* response body already consumed */ }
    });

    await page.goto(`${PORTAL_URL}/credentials?ids=urn:eudi:pid:1&mode=verification`);
    await page.waitForLoadState('load');

    // Wait for the EUDI badge — confirms format is set to DC+SD-JWT
    await page.locator('.bg-blue-100:text("EUDI")').waitFor({ state: 'visible', timeout: 10_000 });

    // Verify policy checkboxes are visible (all checked by default)
    await expect(page.getByText('Signature Policy')).toBeVisible();
    await expect(page.getByText('Revocation Policy')).toBeVisible();
    await expect(page.getByText('EUDI Trust List')).toBeVisible();

    // Click Verify to create verification session via the portal
    const verifyButton = page.getByRole('button', { name: /^Verify$/i }).last();
    await verifyButton.click();
    await page.waitForURL(/\/verify/, { timeout: 15_000 });

    // ── Step 7: Wait for verification QR to render ──────────────
    await page.getByRole('button', { name: 'Open in EUDI Wallet' }).waitFor({ state: 'visible', timeout: 30_000 });
    await page.waitForTimeout(1_000);

    // If response interception didn't capture the URL, grab from clipboard
    if (!verificationSessionUrl) {
      await page.context().grantPermissions(['clipboard-read', 'clipboard-write']);
      await page.getByRole('button', { name: 'Copy offer URL' }).click();
      await page.waitForTimeout(500);
      verificationSessionUrl = await page.evaluate(() => navigator.clipboard.readText());
    }

    expect(verificationSessionUrl, 'Should capture verify URL from portal').toBeTruthy();
    expect(verificationSessionUrl).toContain('openid4vp');

    // ── Step 8: Present credential via wallet API ───────────────
    // (Wallet is a mobile app — API simulates presentation)
    const presentRes = await request.post(
      `${WALLET_API}/wallet-api/wallet/${walletId}/exchange/usePresentationRequest`,
      {
        headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' },
        data: {
          presentationRequest: verificationSessionUrl,
          selectedCredentials: [claimedCredId],
        },
      }
    );
    expect(presentRes.ok(), `Wallet should present the credential: ${presentRes.status()}`).toBeTruthy();

    // ── Step 9: Navigate to success page via portal ─────────────
    const stateMatch = verificationSessionUrl.match(/[?&]state=([^&]+)/);
    const sessionId = verificationSessionId || (stateMatch ? decodeURIComponent(stateMatch[1]) : '');
    expect(sessionId, 'Should have a session ID').toBeTruthy();

    await page.goto(`${PORTAL_URL}/success/${sessionId}?api2=true`);
    await page.waitForLoadState('load');

    // ── Step 10: Verify success page shows policy results ───────
    await page.waitForTimeout(3_000);
    await expect(page.getByRole('heading', { name: 'Presented Credentials' })).toBeVisible();

    const bodyText = await page.locator('body').innerText();
    expect(
      bodyText.includes('Signature Policy') ||
      bodyText.includes('Expiration Policy') ||
      bodyText.includes('signature') ||
      bodyText.includes('expiration')
    ).toBeTruthy();

    // ── Step 11: Verify policy results via API (etsi-trusted-issuer) ─
    const sessionInfo = await request.get(
      `${VERIFIER_API2}/verification-session/${sessionId}/info`
    );
    expect(sessionInfo.ok()).toBeTruthy();
    const session = await sessionInfo.json();

    expect(session.policyResults).toBeTruthy();
    expect(session.policyResults.vc_policies).toBeTruthy();
    const vcPolicies = session.policyResults.vc_policies;

    const policyIds = vcPolicies.map((p: any) => p.policy?.id || p.policy?.policy || 'unknown');
    expect(policyIds).toContain('signature');
    expect(policyIds).toContain('expiration');
    expect(policyIds).toContain('revoked-status-list');
    expect(policyIds).toContain('etsi-trusted-issuer');

    // All policies should pass — including etsi-trusted-issuer (x5c cert matching)
    for (const policy of vcPolicies) {
      const policyId = policy.policy?.id || policy.policy?.policy || 'unknown';
      expect(policy.success, `Policy "${policyId}" should pass`).toBe(true);
    }
  });
});
