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

  test('issue from foreign issuer, hold in wallet, verify with trust policies', async ({ page, request }) => {
    // ── Step 1: Find an active foreign issuer with PID credential ──
    const issuers = await getActiveIssuers(request);
    const foreignIssuers = issuers.filter(i => i.country !== 'AU');
    expect(foreignIssuers.length, 'Need at least one active foreign issuer').toBeGreaterThan(0);

    // Find a foreign issuer that has a dc+sd-jwt credential with VCT urn:eudi:pid:1
    let foreignIssuer = null;
    let pidConfigId = '';
    for (const issuer of foreignIssuers) {
      const detail = await getIssuerDetail(request, issuer.id);
      const configs = detail.credentialConfigurations || {};
      for (const [key, config] of Object.entries(configs)) {
        const c = config as any;
        if (c.format === 'dc+sd-jwt' && c.vct === 'urn:eudi:pid:1') {
          foreignIssuer = issuer;
          pidConfigId = key;
          break;
        }
      }
      if (foreignIssuer) break;
    }
    expect(foreignIssuer, 'Need a foreign issuer with PID (dc+sd-jwt, urn:eudi:pid:1)').toBeTruthy();

    // ── Step 2: Issue credential via issuer API directly ──────────
    // Use the tenant-scoped sdjwt issuance endpoint
    const issuancePayload = {
      credentialConfigurationId: pidConfigId,
      credentialData: {
        family_name: 'Test',
        given_name: 'CrossBorder',
        birth_date: '1990-01-15',
        issuance_date: new Date().toISOString().split('T')[0],
        expiry_date: '2030-12-31',
        issuing_authority: foreignIssuer!.legalName,
        issuing_country: foreignIssuer!.country,
      },
      mapping: {
        iat: '<timestamp-seconds>',
        nbf: '<timestamp-seconds>',
        exp: '<timestamp-in-seconds:365d>',
      },
      selectiveDisclosure: {
        fields: {
          family_name: { sd: true },
          given_name: { sd: true },
          birth_date: { sd: true },
        },
      },
    };

    const issueRes = await request.post(
      `${ISSUER_API}/issuers/${foreignIssuer!.id}/openid4vc/sdjwt/issue`,
      {
        headers: { 'Content-Type': 'application/json' },
        data: issuancePayload,
      }
    );
    expect(issueRes.ok(), `Issuer API should create offer: ${issueRes.status()}`).toBeTruthy();
    const offerUrl = (await issueRes.text()).replace(/^"|"$/g, '');
    expect(offerUrl).toContain('credential_offer');

    // ── Step 3: Claim credential in wallet via API ──────────────
    const claimRes = await request.post(
      `${WALLET_API}/wallet-api/wallet/${walletId}/exchange/useOfferRequest`,
      {
        headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'text/plain' },
        data: offerUrl,
      }
    );
    expect(claimRes.ok(), 'Wallet should accept the credential offer').toBeTruthy();
    const claimedCreds = await claimRes.json();
    expect(Array.isArray(claimedCreds)).toBeTruthy();
    expect(claimedCreds.length).toBeGreaterThan(0);
    const claimedCredId = claimedCreds[0].id;

    // ── Step 4: Verify trust lists are loaded ───────────────────
    const trustStatus = await getTrustStatus(request);
    const etsiKey = Object.keys(trustStatus.sources).find(k => k.toLowerCase().includes('etsi'));
    expect(etsiKey, 'ETSI trust source should be present').toBeTruthy();
    expect(trustStatus.sources[etsiKey!].enabled).toBe(true);

    // ── Step 5: Navigate portal to verification with policies ───
    // Set up response interception for verification session URL and session ID
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
      } catch(e) { /* response body already consumed */ }
    });

    await page.goto(`${PORTAL_URL}/credentials?ids=urn:eudi:pid:1&mode=verification`);
    await page.waitForLoadState('load');

    // Wait for the EUDI badge — confirms format is set to DC+SD-JWT
    await page.locator('.bg-blue-100:text("EUDI")').waitFor({ state: 'visible', timeout: 10_000 });

    // Verify policy checkboxes are visible (all checked by default)
    await expect(page.getByText('Signature Policy')).toBeVisible();
    await expect(page.getByText('Revocation Policy')).toBeVisible();
    await expect(page.getByText('EUDI Trust List')).toBeVisible();

    // Click Verify to create verification session
    const verifyButton = page.getByRole('button', { name: /^Verify$/i }).last();
    await verifyButton.click();
    await page.waitForURL(/\/verify/, { timeout: 15_000 });

    // ── Step 6: Wait for verification QR to render ──────────────
    // The "Open in EUDI Wallet" button appears only when verifier-api2 was used and QR is ready
    await page.getByRole('button', { name: 'Open in EUDI Wallet' }).waitFor({ state: 'visible', timeout: 30_000 });
    await page.waitForTimeout(1_000);

    // If page.on('response') didn't capture the URL (e.g., response body already consumed),
    // extract it from the QR code by clicking "Copy offer URL" and reading the clipboard
    if (!verificationSessionUrl) {
      await page.context().grantPermissions(['clipboard-read', 'clipboard-write']);
      await page.getByRole('button', { name: 'Copy offer URL' }).click();
      await page.waitForTimeout(500);
      verificationSessionUrl = await page.evaluate(() => navigator.clipboard.readText());
    }

    expect(verificationSessionUrl, 'Should capture verify URL from verifier-api2 response').toBeTruthy();
    expect(verificationSessionUrl).toContain('openid4vp');

    // ── Step 7: Present credential via wallet API ───────────────
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
    expect(presentRes.ok(), 'Wallet should present the credential').toBeTruthy();

    // ── Step 8: Navigate to success page ────────────────────────
    // Extract the session ID — prefer the sessionId from the API response,
    // fall back to the state parameter in the verify URL
    const stateMatch = verificationSessionUrl.match(/[?&]state=([^&]+)/);
    const sessionId = verificationSessionId || (stateMatch ? decodeURIComponent(stateMatch[1]) : '');
    expect(sessionId, 'Should have a session ID from API response or state parameter').toBeTruthy();

    // Navigate directly to the success page
    await page.goto(`${PORTAL_URL}/success/${sessionId}?api2=true`);
    await page.waitForLoadState('load');

    // ── Step 9: Verify success page shows policy results ────────
    await page.waitForTimeout(3_000);
    await expect(page.getByRole('heading', { name: 'Presented Credentials' })).toBeVisible();

    const bodyText = await page.locator('body').innerText();
    expect(
      bodyText.includes('Signature Policy') ||
      bodyText.includes('Expiration Policy') ||
      bodyText.includes('signature') ||
      bodyText.includes('expiration')
    ).toBeTruthy();

    // ── Step 10: Verify session info via API for completeness ───
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

    const sigResult = vcPolicies.find((p: any) => (p.policy?.id || p.policy?.policy) === 'signature');
    expect(sigResult?.success).toBe(true);
  });
});
