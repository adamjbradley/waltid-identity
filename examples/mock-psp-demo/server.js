/**
 * Mock PSP ("Bank of Demo") — standalone Payment Wallet Attestation issuer.
 *
 * Deployment shape is intentionally production-faithful: the RP at
 * rp.theaustraliahack.com redirects the shopper here
 * (psp.theaustraliahack.com) to enrol a payment method. The PSP drives the
 * PID presentation + credential issuance, then sends the shopper back to
 * the RP where the PWA capture flow takes over.
 *
 * Split out of examples/rp-widget-demo/server.js — see the git log for the
 * pre-split revision if you need to understand the monolithic original.
 */

const crypto = require('crypto');
const express = require('express');
const session = require('express-session');
const path = require('path');

/**
 * Per-VCT DCQL singleton query helper. For any ordered list of VCTs + claim
 * paths, emits one `credentials` entry per VCT with a single-element
 * `vct_values` array and the shared claim list, then ORs them under
 * `credential_sets.options`. PR #88 workaround for the EUDI iOS wallet-kit's
 * first-VCT-only matcher — splitting the VCTs into separate credentials
 * keeps both EUDI and non-EUDI wallets matchable.
 *
 * Mirrors the same helper in rp-widget-demo; kept in-file rather than
 * extracted to a shared package because the two services deploy
 * independently and YAGNI — a helper this small is cheaper duplicated.
 */
function buildSingletonDcql(vcts, claimPaths, format = 'dc+sd-jwt', idPrefix = 'cred') {
  return {
    credentials: vcts.map((vct, i) => ({
      id: `${idPrefix}_${i}`,
      format,
      meta: { vct_values: [vct] },
      claims: claimPaths.map((p) => ({ path: Array.isArray(p) ? p : [p] })),
    })),
    credential_sets: [{
      required: true,
      options: vcts.map((_, i) => [`${idPrefix}_${i}`]),
    }],
  };
}

/**
 * PID identity DCQL — asks for given_name, family_name, birth_date across
 * the four PID VCTs. Used by Step 1 of /enroll (PID presentation).
 */
function buildPidIdentityDcql() {
  const vcts = [
    'urn:eudi:pid:1',
    'urn:au:gov:mygovid:pid:1',
    'urn:in:gov:aadhaar:pid:1',
    'urn:uk:gov:govuk-one-login:pid:1',
  ];
  return buildSingletonDcql(
    vcts,
    [['given_name'], ['family_name'], ['birth_date']],
    'dc+sd-jwt',
    'pid',
  );
}

/**
 * In-memory token map TTL. Same discipline as rp-widget-demo — an entry
 * lives at most SESSION_TOKEN_TTL_MS, with unref() so an idle event loop
 * can exit even with pending evictions.
 */
const SESSION_TOKEN_TTL_MS = 10 * 60 * 1000;
function registerSessionToken(map, token, entry) {
  map.set(token, entry);
  setTimeout(() => {
    const current = map.get(token);
    if (current === entry) map.delete(token);
  }, SESSION_TOKEN_TTL_MS).unref();
}

// Configuration — read from env with deployment-friendly defaults. Docker
// compose wires these; local dev can run with the defaults for anything
// that doesn't need real upstream reachability.
const ISSUER_API_URL = process.env.ISSUER_API_URL || 'http://issuer-api:7002';
// Reuses the existing PaymentWalletAttestation-issuing tenant from the
// rp-widget-demo flow. A dedicated "Bank of Demo" tenant (with its own
// signing key + DID) is a separate follow-up; splitting the service is
// the structural precondition.
const PSP_TENANT_ID = process.env.PSP_TENANT_ID || 'a84e7c3a-b399-48e9-9345-2d8f062c614f';
const PSP_VCT = process.env.PSP_VCT || 'PaymentWalletAttestation';

const VERIFIER_API2_URL = process.env.VERIFIER_API2_URL || 'http://verifier-api2:7004';
const RP_ID = process.env.RP_ID || 'a1b2c3d4-e5f6-4a7b-8c9d-0e1f2a3b4c5d';

// Public base for webhook callbacks verifier-api2 can reach. The verifier
// must POST here from its own network namespace so this has to resolve
// externally, not just from the shopper's browser.
const PUBLIC_URL = process.env.PUBLIC_URL || 'https://psp.theaustraliahack.com';

const SESSION_SECRET = process.env.SESSION_SECRET || 'dev-psp-session-secret-change-me';

// Allowlist of hosts that may appear in `?return=<url>`. Defence against
// using the PSP as an open redirector: only domains the operator trusts
// can be bounced back to after enrolment. Comma-separated, case-insensitive,
// bare host match (any port, any path).
const RP_RETURN_URL_ALLOWLIST = (process.env.RP_RETURN_URL_ALLOWLIST
  || 'rp.theaustraliahack.com,localhost')
  .split(',')
  .map((s) => s.trim().toLowerCase())
  .filter(Boolean);

// Default return URL — first entry in the allowlist, normalised to https
// unless it's localhost. Used when the shopper lands on /enroll without a
// return param (so the "return to shop" button still has a target).
function defaultReturnUrl() {
  const first = RP_RETURN_URL_ALLOWLIST[0];
  if (!first) return '/';
  const scheme = first === 'localhost' ? 'http' : 'https';
  return `${scheme}://${first}`;
}

function isAllowlistedReturnUrl(candidate) {
  try {
    const u = new URL(candidate);
    const host = u.hostname.toLowerCase();
    return RP_RETURN_URL_ALLOWLIST.includes(host);
  } catch (_) {
    return false;
  }
}

const config = {
  ISSUER_API_URL,
  PSP_TENANT_ID,
  PSP_VCT,
  VERIFIER_API2_URL,
  RP_ID,
  PUBLIC_URL,
  RP_RETURN_URL_ALLOWLIST,
};

/**
 * Build and configure the Express app. Exported as a factory so jest tests
 * can spin a fresh app per suite (which also gives each its own
 * pspEnrollByToken map — no cross-test state).
 */
function createApp() {
  const app = express();

  // In-memory tokens for the PID-presentation webhook. Shape mirrors
  // rp-widget-demo's `pspEnrollByToken`: keyed by an unguessable URL token,
  // value is `{webhookSecret, verified: null|bool, claims, pidClaims?}`.
  const pspEnrollByToken = new Map();

  // Trust proxy so secure cookies work behind Caddy/Cloudflare.
  app.set('trust proxy', 1);

  app.use(session({
    name: 'psp.sid',
    secret: SESSION_SECRET,
    resave: false,
    saveUninitialized: false,
    cookie: {
      httpOnly: true,
      secure: process.env.NODE_ENV === 'production',
      sameSite: 'lax',
      maxAge: 1000 * 60 * 60 * 2, // 2 hours — enrolment is a short ceremony
    },
  }));

  app.use(express.json());
  app.use(express.static(path.join(__dirname, 'public')));

  /**
   * GET /enroll — the main entry point the RP redirects to. Records a
   * validated `?return=<url>` on the session so the client-side enrol page
   * knows where to send the shopper after the credential has been added.
   *
   * Invalid / off-allowlist returns are silently discarded in favour of the
   * default (rather than 403) because from the RP's perspective a bad param
   * is just a configuration error and the user shouldn't see a scary page.
   */
  app.get('/enroll', (req, res) => {
    const candidate = req.query && req.query.return;
    if (typeof candidate === 'string' && candidate.length > 0) {
      if (isAllowlistedReturnUrl(candidate)) {
        req.session.returnUrl = candidate;
      } else {
        // Untrusted origin. Don't store — the client will fall back to
        // defaultReturnUrl(). This is an intentional silent-ignore rather
        // than 403: bad input here is almost always misconfiguration.
        console.warn(`[psp] rejecting off-allowlist return URL: ${candidate}`);
      }
    }
    res.sendFile(path.join(__dirname, 'public', 'enroll.html'));
  });

  /**
   * GET /api/psp/return-url — tiny helper for the client to discover the
   * post-enrol destination without needing to re-parse the original URL.
   * Falls back to `defaultReturnUrl()` when no return was stored.
   */
  app.get('/api/psp/return-url', (req, res) => {
    const stored = req.session && req.session.returnUrl;
    res.json({ returnUrl: stored || defaultReturnUrl() });
  });

  /**
   * POST /api/psp/start — kick a cross-device OID4VP session asking for PID
   * identity claims (given_name, family_name, birth_date). Wire shape
   * matches verifier-api2's session-create route
   * (Verification2Session / KtorSessionNotifications): flow_type
   * `cross_device`, core_flow carrying dcql_query + signed_request + the
   * webhook callback with Bearer token.
   */
  app.post('/api/psp/start', async (req, res) => {
    const token = crypto.randomBytes(16).toString('hex');
    const webhookSecret = crypto.randomBytes(32).toString('base64url');
    const webhookUrl = `${PUBLIC_URL.replace(/\/$/, '')}/api/psp/webhook/${token}`;
    const body = {
      flow_type: 'cross_device',
      core_flow: {
        dcql_query: buildPidIdentityDcql(),
        signed_request: true,
        notifications: {
          webhook: {
            url: webhookUrl,
            bearer_token: webhookSecret,
          },
        },
      },
    };
    try {
      const r = await fetch(
        `${VERIFIER_API2_URL}/verification-session/create?rpId=${encodeURIComponent(RP_ID)}`,
        {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify(body),
        },
      );
      if (!r.ok) {
        console.warn('[psp] verifier-api2 session-create failed', r.status);
        return res.status(502).json({ error: 'verifier_unavailable' });
      }
      const session = await r.json();
      // Fall back to full URL when cross-device bootstrap isn't emitted.
      const qrCode = session.bootstrapAuthorizationRequestUrl || session.fullAuthorizationRequestUrl;
      registerSessionToken(pspEnrollByToken, token, {
        webhookSecret,
        verified: null,
        claims: null,
        pidClaims: null,
      });
      req.session.pspEnroll = { sessionId: session.sessionId, token, webhookSecret };
      res.json({ sessionId: session.sessionId, qrCode });
    } catch (err) {
      console.warn('[psp] session-create error', err.message || err);
      res.status(502).json({ error: 'verifier_unavailable' });
    }
  });

  /**
   * POST /api/psp/webhook/:token — verifier-api2 callback for the PID
   * presentation. Bearer-auth (KtorSessionNotifications WebhookNotifier),
   * constant-time compare, terminal-event gate. On SUCCESSFUL we capture
   * {sub, given_name, family_name, birth_date} onto the map entry under
   * `pidClaims` for the status poll to mirror.
   */
  app.post('/api/psp/webhook/:token', (req, res) => {
    const entry = pspEnrollByToken.get(req.params.token);
    if (!entry) return res.status(404).json({ error: 'unknown_token' });
    const authHeader = req.header('authorization') || '';
    if (!/^Bearer\s+/i.test(authHeader)) {
      return res.status(401).json({ error: 'bad_secret' });
    }
    const provided = Buffer.from(authHeader.replace(/^Bearer\s+/i, '').trim(), 'utf8');
    const expected = Buffer.from(entry.webhookSecret || '', 'utf8');
    if (provided.length !== expected.length || !crypto.timingSafeEqual(provided, expected)) {
      return res.status(401).json({ error: 'bad_secret' });
    }
    if (req.body.event !== 'policy_results_available') return res.json({ ok: true });
    const session = req.body.session || {};
    if (session.status !== 'SUCCESSFUL') {
      entry.verified = false;
      return res.json({ ok: true });
    }
    const creds = session.presentedCredentials || {};
    for (const arr of Object.values(creds)) {
      if (Array.isArray(arr) && arr.length && arr[0] && arr[0].credentialData) {
        const cd = arr[0].credentialData;
        const sub = cd.sub || cd.subject || arr[0].sub || null;
        entry.verified = true;
        entry.pidClaims = {
          sub,
          given_name: cd.given_name,
          family_name: cd.family_name,
          birth_date: cd.birth_date,
        };
        // Back-compat mirror under `claims` — the rp-widget-demo status
        // handler used to read it from there; keeping both hedges against
        // any consumer downstream that hasn't been updated.
        entry.claims = entry.pidClaims;
        return res.json({ ok: true });
      }
    }
    entry.verified = false;
    res.json({ ok: true });
  });

  /**
   * GET /api/psp/status — browser poll. Mirrors the PID claims into
   * req.session.pspPidVerified once the webhook has written a terminal
   * verdict, and evicts the map entry.
   */
  app.get('/api/psp/status', (req, res) => {
    const tok = req.session.pspEnroll && req.session.pspEnroll.token;
    if (!tok) return res.json({ verified: null });
    const entry = pspEnrollByToken.get(tok);
    if (!entry) return res.json({ verified: null });
    if (entry.verified === true) {
      req.session.pspPidVerified = entry.pidClaims || entry.claims || {};
      pspEnrollByToken.delete(tok);
      return res.json({ verified: true, claims: req.session.pspPidVerified });
    }
    if (entry.verified === false) {
      pspEnrollByToken.delete(tok);
      return res.json({ verified: false, claims: null });
    }
    res.json({ verified: null });
  });

  /**
   * POST /api/psp/issue — once PID is on the session, hit issuer-api for
   * a pre-authorized-code credential offer for the PaymentWalletAttestation
   * VCT. Demo PAN is derived deterministically from sha256(sub) so repeat
   * enrolments show a stable "card ending …" value.
   *
   * Issuer-api route for a tenant-scoped pre-authorized offer:
   *   POST /issuers/{issuerId}/openid4vc/sdjwt/issue
   * Response body is the offer URI as plain text (possibly JSON-quoted).
   */
  app.post('/api/psp/issue', async (req, res) => {
    if (!req.session.pspPidVerified) return res.status(403).json({ error: 'pid_required' });
    const holderSub = req.session.pspPidVerified.sub || 'anonymous';
    const hash = crypto.createHash('sha256').update(String(holderSub)).digest('hex');
    // RFC007 §8 claim layout:
    // - `sub` is a PSP-assigned non-sensitive identifier (PSU-ID analogue), not the holder's
    //   wallet subject. Derive a stable pseudo-PSU-ID from sha256(holderSub) so the same
    //   enrolment path keeps a stable ID without leaking the wallet's sub upstream.
    // - `jti` is per-credential unique (RFC007 §8, OPTIONAL but recommended for revocation).
    // - All payment metadata MUST live under the `fundingSource` nested object with a
    //   required `type` field ("card"/"account"/"any"). Top-level card fields are
    //   spec-noncompliant and rejected by strict verifiers.
    // - `given_name`/`family_name`/`payeeName` are NOT part of PWA. Identity is carried
    //   by the PID presented at enrolment; the issuer's legal name comes from `iss` +
    //   issuer metadata `display`. Stripped here.
    const pseudoPsuId = `psu_${hash.slice(0, 24)}`;
    const panLastFour = hash.slice(0, 4);
    // RFC007 §8 RECOMMENDED claim: last 4 chars of the EMV Payment Account
    // Reference. Derived deterministically from the same PSU hash so repeat
    // enrolments for the same holder reproduce the same value. Real PSPs
    // would source this from their EMV tokenisation vendor.
    const parLastFour = hash.slice(4, 8);
    const scheme = 'Visa';
    const iin = '453201';
    const currency = 'AUD';
    const jti = `urn:uuid:${crypto.randomUUID()}`;
    // RFC007 §8 RECOMMENDS aligning `exp` with the card's expiry. Real PSPs
    // read the card's actual expiry from their tokenisation vendor; the demo
    // picks a stable 5-year window — longer than walt.id's default 365 days
    // so the PWA outlives a typical card rotation cycle without re-enrol.
    const now = Math.floor(Date.now() / 1000);
    const exp = now + 5 * 365 * 24 * 60 * 60;

    const offerReq = {
      credentialConfigurationId: PSP_VCT,
      vct: PSP_VCT,
      credentialData: {
        sub: pseudoPsuId,
        jti,
        // exp is a registered claim; walt.id's defaultPayloadProperties only
        // sets exp when its `expirationDate` arg is passed (CIProvider doesn't),
        // so this value is preserved at the top level of the SD-JWT rather than
        // being made selectively-disclosable.
        exp,
        fundingSource: {
          type: 'card',
          panLastFour,
          parLastFour,
          iin,
          scheme,
          currency,
          aliasId: `pwa_${scheme.toLowerCase()}_${panLastFour}`,
        },
      },
      // Nested-children selective disclosure: each fundingSource.* field is its
      // own SD-JWT disclosure. Critical for DCQL matching — the EUDI iOS wallet
      // walks `disclosuresPerClaimPath` (not cleartext claims) when evaluating
      // DCQL paths like ["fundingSource","panLastFour"]. Without per-field
      // disclosures the wallet's matcher sees no claim at that path and
      // responds with "The requested document is not available".
      //
      // fundingSource itself stays in cleartext (sd:false) so the nested _sd
      // digests sit INSIDE the fundingSource object — preserving the nested
      // shape. sub + jti are also SD'd so the holder isn't forced to reveal
      // their pseudo-PSU-ID on presentation.
      selectiveDisclosure: {
        fields: {
          sub: { sd: true },
          jti: { sd: true },
          fundingSource: {
            sd: false,
            children: {
              fields: {
                type: { sd: true },
                panLastFour: { sd: true },
                parLastFour: { sd: true },
                iin: { sd: true },
                scheme: { sd: true },
                currency: { sd: true },
                aliasId: { sd: true },
              },
            },
          },
        },
      },
      authenticationMethod: 'PRE_AUTHORIZED',
    };

    try {
      const url = `${ISSUER_API_URL}/issuers/${encodeURIComponent(PSP_TENANT_ID)}/openid4vc/sdjwt/issue`;
      const r = await fetch(url, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(offerReq),
      });
      if (!r.ok) {
        console.warn('[psp] issuer-api offer failed', r.status);
        return res.status(502).json({ error: 'issuer_unavailable', status: r.status });
      }
      const raw = await r.text();
      let offerUri = raw;
      try {
        const parsed = JSON.parse(raw);
        if (typeof parsed === 'string') offerUri = parsed;
        else if (parsed && typeof parsed === 'object') offerUri = parsed.offerUri || parsed.uri || raw;
      } catch (_) { /* plain string body */ }
      // Return display-only metadata for the enrolment page (NOT the credential content).
      // panLastFour + scheme are echoed so the page can show "Visa ending …" while the
      // wallet consumes the offer. payeeName is dropped — PSP identity is inferred from
      // the issuer metadata (`iss` + `display.name`) per RFC007.
      res.json({ offerUri, panLastFour, scheme });
    } catch (err) {
      console.warn('[psp] offer error', err.message || err);
      res.status(502).json({ error: 'issuer_unavailable' });
    }
  });

  // Dev/test session-hydration helpers. Guarded on NODE_ENV so production
  // builds never expose them. Same pattern as rp-widget-demo's _test routes.
  if (process.env.NODE_ENV !== 'production') {
    app.post('/_test/session', (req, res) => {
      Object.assign(req.session, req.body);
      res.json({ ok: true });
    });
    app.post('/_test/psp-enroll/register', (req, res) => {
      const { token, webhookSecret } = req.body || {};
      if (!token || !webhookSecret) return res.status(400).json({ error: 'missing_fields' });
      registerSessionToken(pspEnrollByToken, token, {
        webhookSecret,
        verified: null,
        claims: null,
        pidClaims: null,
      });
      res.json({ ok: true });
    });
  }

  app.get('/health', (_req, res) => {
    res.json({ status: 'ok' });
  });

  return app;
}

/**
 * Start the server only when this file is the main module — keeps tests
 * (which `require('./server')` for the factory) from also opening a port.
 */
function startServer() {
  const PORT = process.env.PORT || 7006;
  const app = createApp();
  app.listen(PORT, () => {
    console.log('');
    console.log('='.repeat(60));
    console.log('  Mock PSP — Bank of Demo');
    console.log('='.repeat(60));
    console.log(`  Listening on:     http://localhost:${PORT}`);
    console.log(`  Issuer API:       ${ISSUER_API_URL}`);
    console.log(`  Verifier API2:    ${VERIFIER_API2_URL}`);
    console.log(`  PSP tenant:       ${PSP_TENANT_ID}`);
    console.log(`  Public URL:       ${PUBLIC_URL}`);
    console.log(`  Return allowlist: ${RP_RETURN_URL_ALLOWLIST.join(', ')}`);
    console.log('='.repeat(60));
    console.log('');
  });
  return app;
}

if (require.main === module) {
  startServer();
}

module.exports = { createApp, config };
