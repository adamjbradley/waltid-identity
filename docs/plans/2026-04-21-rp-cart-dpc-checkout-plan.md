# RP Cart + Age Gate + DPC Checkout Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Add a server-authoritative cart, memoize age verification across a session, and wire a mock "Bank of Demo" PSP plus a checkout flow that presents a Payment Wallet Attestation with `transaction_data` (EWC RFC007 + RFC008) — all in `examples/rp-widget-demo`.

**Architecture:** Single Node/Express service (`rp-widget-demo`) gains a `PRODUCT_CATALOGUE` module, session-scoped cart state, a 403-gated POST that triggers an age-only OID4VP presentation via verifier-api2, and a `/checkout` page that fires an OID4VP session carrying `transaction_data`. The mock PSP is a new `issuer-api` tenant (`bank-of-demo.json`) exposed at `psp.theaustraliahack.com`, issuing `PaymentWalletAttestation` VCs via pre-authorized-code OID4VCI. All user-visible receipts/profiles persist via the existing `userStore` when logged in; anonymous orders are session-scoped.

**Tech Stack:** Node 20, Express 4, express-session, Jest + supertest for unit/integration, Playwright for e2e. Kotlin side: only a new tenant JSON + (possibly) a small passthrough in verifier-api2 depending on Task 0 spike.

**Design reference:** `docs/plans/2026-04-21-rp-cart-dpc-checkout-design.md`

---

## Conventions

- All commands run from `examples/rp-widget-demo/` unless stated. That directory is `cd`'d into for every Task.
- Commit after every task using the message shown. Never batch commits across tasks — the review granularity is one task = one commit.
- Use the existing `createApp()` export in `server.js` as the supertest entry point (see `tests/server.test.js`).
- Keep the front-end in the existing `public/index.html` single-page shell where possible; create `public/checkout.html` and `public/order.html` as small standalone documents when a separate route is clearer than an SPA section.

---

## Task 0 — resolved (2026-04-21)

**Verdict: Plan B (fallback).**

Grep across `waltid-libraries/protocols/waltid-openid4vp-verifier/` and `waltid-services/waltid-verifier-api2/` turns up exactly one reference to `transaction_data`, and it's commented-out:

```
waltid-libraries/protocols/waltid-openid4vp-verifier/src/commonMain/kotlin/
  id/walt/openid4vp/verifier/handlers/sessioncreation/VerificationSessionCreator.kt:208
  //val transactionData : List < String >? = null, // List of base64url encoded JSON strings
```

Wallet side (`waltid-openid4vp-wallet`, `waltid-dcql`) has zero references. The spec-compliant RFC008 path (signed JAR carries `transaction_data`, wallet renders amount/payee, returns a kb-JWT with `transaction_data_hashes`) is not available in this stack without a multi-week library patch.

**Decision for the demo:** use a standard PWA OID4VP presentation with a per-order nonce generated server-side, and render the transaction details on the RP's `/checkout` page (not in the wallet). The wallet-side UX shows a generic "Present your payment credential" prompt; the RP-side confirmation makes the narrative clear. Document the limitation in the `/checkout` page and in the design doc.

**Plan B shape for Task 17** (overrides the Plan A shape in the design doc):
- `POST /api/checkout`:
  - Generate `orderId = "ORDER-" + uuid`, compute `total`.
  - Create a verifier-api2 session for a plain `PaymentWalletAttestation` DCQL presentation, passing the orderId as the session nonce.
  - Store `req.session.pendingOrder = { orderId, total, items, nonce, txData }` — `txData` kept server-side only, used at receipt rendering.
- `POST /api/checkout/webhook/:orderId`:
  - Verify webhook secret (constant-time compare).
  - Verify the VP's kb-JWT nonce equals the session nonce we issued (standard OID4VP binding — weaker than RFC008 but what the library supports today).
  - Persist order, clear cart, return orderId.
- `/order/:id` receipt page displays the full transaction summary that would have been in `transaction_data`, labelled "Authorized by EUDI Wallet presentation — transaction binding via RFC008 `transaction_data` not yet supported by verifier-api2 (tracked separately)."

**Task 19 deleted.** No verifier-api2 passthrough needed for the demo. A separate future ticket should upgrade both library sides when walt.id's OID4VP library catches up with Draft 22+ `transaction_data`.

---

## Task 0: Spike — does verifier-api2 accept `transaction_data`?

**Why before code:** Task 18 forks sharply based on this answer.

**Files:**
- Read: `waltid-libraries/protocols/waltid-openid4vp-verifier/src/commonMain/kotlin/id/walt/openid4vp/verifier/session/**`
- Read: `waltid-services/waltid-verifier-api2/src/main/kotlin/id/walt/openid4vp/verifier/**`

**Step 1:** Grep for `transaction_data` and `transactionData` across both modules. Note whether the session-create request body model has a field, whether the signed Request Object includes it, and whether response-side verification hashes it.

**Step 2:** Write findings to `docs/plans/2026-04-21-rp-cart-dpc-checkout-plan.md` under a "Task 0 — resolved" section appended below this table, with one of:

- **Plan A — native support confirmed.** Task 18 passes `transaction_data` in the POST body. Task 19 deleted.
- **Plan A' — small passthrough needed.** List the ~15-line diff to add. Task 19 adds that diff, with its own tests against verifier-api2.
- **Plan B — fallback.** Smuggle transaction data into DCQL as a synthetic claim; add a TODO to upgrade later. Task 18 uses this shape; Task 19 deleted.

**Step 3 (commit):**
```bash
cd /Users/adambradley/Projects/Mastercard/India/waltid-identity/.worktrees/rp-cart-dpc
git add docs/plans/2026-04-21-rp-cart-dpc-checkout-plan.md
git commit -m "docs(plan): resolve Task 0 transaction_data spike"
```

---

## Task 1: Server-side catalogue module

**Why:** Price + title must come from server truth on cart add. Client catalogue stays for rendering.

**Files:**
- Create: `examples/rp-widget-demo/catalogue.js`
- Test:  `examples/rp-widget-demo/tests/catalogue.test.js`

**Step 1: Write the failing test**

```js
// tests/catalogue.test.js
const { CATALOGUE, getProduct } = require('../catalogue');

describe('catalogue', () => {
  it('has 12 products', () => {
    expect(CATALOGUE).toHaveLength(12);
  });
  it('every product has id, name, priceSingle, minAge, ageRestricted', () => {
    for (const p of CATALOGUE) {
      expect(p).toMatchObject({
        id: expect.any(String),
        name: expect.any(String),
        priceSingle: expect.any(Number),
        minAge: expect.any(Number),
        ageRestricted: true,
      });
    }
  });
  it('getProduct returns the entry by id', () => {
    expect(getProduct('hibiki-harmony')).toBeTruthy();
    expect(getProduct('hibiki-harmony').name).toMatch(/Hibiki/);
  });
  it('getProduct returns null for unknown id', () => {
    expect(getProduct('does-not-exist')).toBeNull();
  });
});
```

**Step 2: Run to confirm fail**
```bash
cd examples/rp-widget-demo && npx jest tests/catalogue.test.js
```
Expected: `Cannot find module '../catalogue'`.

**Step 3: Implement**

Copy the 12-entry `PRODUCT_CATALOGUE` array from `public/index.html` (currently at the `const PRODUCT_CATALOGUE = [...]` declaration) into `catalogue.js`, add `ageRestricted: true` to every entry (keep existing `minAge: 18/21`), and export.

```js
// catalogue.js
const CATALOGUE = [
  { id: 'hibiki-harmony', name: 'Hibiki Japanese Harmony', meta: 'Japan · Blended Japanese Whisky · 70cl · 43% ABV', icon: '\uD83E\uDD43', priceMix: 79.05, priceSingle: 89.00, was: 99.00, ratingStars: 5, ratingCount: 412, minAge: 21, ageRestricted: true, savePct: 10, tags: ['japanese', 'japanese whisky', 'whisky', 'blended'] },
  // ... copy all 12 entries, adding ageRestricted: true ...
];

const byId = Object.fromEntries(CATALOGUE.map(p => [p.id, p]));

function getProduct(id) {
  return byId[id] || null;
}

module.exports = { CATALOGUE, getProduct };
```

**Step 4: Run to confirm pass**
```bash
npx jest tests/catalogue.test.js
```

**Step 5: Commit**
```bash
git add examples/rp-widget-demo/catalogue.js examples/rp-widget-demo/tests/catalogue.test.js
git commit -m "feat(rp): server-side catalogue module"
```

---

## Task 2: Client catalogue loads from server module

**Why:** Keep one source of truth. Client renders from the same data.

**Files:**
- Modify: `examples/rp-widget-demo/server.js:463` area (serve catalogue as JSON) and a new route `GET /api/catalogue`.
- Modify: `examples/rp-widget-demo/public/index.html` — replace inline `PRODUCT_CATALOGUE` literal with `await fetch('/api/catalogue').then(r => r.json())`.
- Test: extend `tests/server.test.js` with a `GET /api/catalogue` case.

**Step 1: Failing test**

Append to `tests/server.test.js`:
```js
describe('GET /api/catalogue', () => {
  it('returns 12 products with ageRestricted flags', async () => {
    const res = await request(app).get('/api/catalogue').expect(200);
    expect(res.body).toHaveLength(12);
    expect(res.body[0]).toHaveProperty('ageRestricted', true);
  });
});
```

**Step 2: Run → fail (404).**

**Step 3: Implement — in `server.js` before the SPA catch-all:**
```js
const { CATALOGUE } = require('./catalogue');
app.get('/api/catalogue', (_req, res) => res.json(CATALOGUE));
```

And replace the 12-entry literal in `public/index.html` with a fetch:
```js
let PRODUCT_CATALOGUE = [];
async function loadCatalogue() {
  const res = await fetch('/api/catalogue');
  PRODUCT_CATALOGUE = await res.json();
}
// call loadCatalogue() before renderShelf()
```

**Step 4: Run → pass.** Also manually smoke-test: `npm start`, browse `/`, confirm shelf still renders.

**Step 5: Commit**
```bash
git add -p   # review carefully
git commit -m "feat(rp): expose catalogue via /api/catalogue, load client-side"
```

---

## Task 3: Cart state — `GET /api/cart` returns empty cart for fresh session

**Files:**
- Create: `examples/rp-widget-demo/cart.js` (pure functions; no Express coupling)
- Test: `examples/rp-widget-demo/tests/cart.test.js`
- Modify: `server.js` — wire `GET /api/cart`

**Step 1: Failing test**

```js
// tests/cart.test.js
const request = require('supertest');
const { createApp } = require('../server');

describe('cart API', () => {
  let app, agent;
  beforeEach(() => { app = createApp(); agent = request.agent(app); });

  it('GET /api/cart on fresh session returns empty', async () => {
    const res = await agent.get('/api/cart').expect(200);
    expect(res.body).toEqual({ items: [], subtotal: 0, count: 0 });
  });
});
```

**Step 2: Run → fail (404).**

**Step 3: Implement**

```js
// cart.js
function emptyCart() { return { items: [], updatedAt: Date.now() }; }
function summary(cart) {
  const count = cart.items.reduce((n, i) => n + i.qty, 0);
  const subtotal = Math.round(cart.items.reduce((n, i) => n + i.qty * i.priceAud, 0) * 100) / 100;
  return { items: cart.items, subtotal, count };
}
module.exports = { emptyCart, summary };
```

In `server.js` add route:
```js
const { emptyCart, summary } = require('./cart');
app.get('/api/cart', (req, res) => {
  req.session.cart = req.session.cart || emptyCart();
  res.json(summary(req.session.cart));
});
```

**Step 4: Run → pass.**

**Step 5: Commit**
```bash
git add examples/rp-widget-demo/cart.js examples/rp-widget-demo/tests/cart.test.js examples/rp-widget-demo/server.js
git commit -m "feat(rp): GET /api/cart returns empty-cart summary"
```

---

## Task 4: `POST /api/cart/items` — server-side age enforcement

**Files:**
- Modify: `cart.js` (add `addItem` helper)
- Modify: `server.js` (new route + age gate)
- Modify: `tests/cart.test.js`

**Step 1: Failing test**

```js
it('POST /api/cart/items without age verification returns 403 for 21+ product', async () => {
  const res = await agent.post('/api/cart/items').send({ productId: 'hibiki-harmony' }).expect(403);
  expect(res.body).toEqual({ error: 'age_verification_required', minAge: 21 });
});

it('POST /api/cart/items with session.ageVerified adds item', async () => {
  // hydrate session
  await agent.post('/_test/session').send({ ageVerified: true });  // helper endpoint, NODE_ENV!==production only
  const res = await agent.post('/api/cart/items').send({ productId: 'hibiki-harmony' }).expect(200);
  expect(res.body.count).toBe(1);
  expect(res.body.items[0]).toMatchObject({ productId: 'hibiki-harmony', qty: 1, priceAud: 89.00 });
});
```

**Step 2: Run → fail.**

**Step 3: Implement**

Add to `cart.js`:
```js
function addItem(cart, product, qty = 1) {
  const existing = cart.items.find(i => i.productId === product.id);
  if (existing) existing.qty += qty;
  else cart.items.push({
    productId: product.id, qty, priceAud: product.priceSingle,
    title: product.name, imageUrl: product.icon, ageRestricted: product.ageRestricted
  });
  cart.updatedAt = Date.now();
  return cart;
}
module.exports = { emptyCart, summary, addItem };
```

Age gate helper:
```js
function isAgeVerified(session, minAge) {
  if (!minAge) return true;
  if (session.user && session.user.age_over_21 === true && minAge <= 21) return true;
  if (session.user && session.user.age_over_18 === true && minAge <= 18) return true;
  return session.ageVerified === true;
}
```

Route + dev-only session-hydrate helper (guarded by `NODE_ENV !== 'production'`):
```js
const { getProduct } = require('./catalogue');
const { addItem } = require('./cart');

if (process.env.NODE_ENV !== 'production') {
  app.post('/_test/session', (req, res) => {
    Object.assign(req.session, req.body);
    res.json({ ok: true });
  });
}

app.post('/api/cart/items', (req, res) => {
  const product = getProduct(req.body.productId);
  if (!product) return res.status(404).json({ error: 'unknown_product' });
  if (product.ageRestricted && !isAgeVerified(req.session, product.minAge)) {
    return res.status(403).json({ error: 'age_verification_required', minAge: product.minAge });
  }
  req.session.cart = req.session.cart || emptyCart();
  addItem(req.session.cart, product, req.body.qty || 1);
  res.json(summary(req.session.cart));
});
```

**Step 4: Run → pass.**

**Step 5: Commit**
```bash
git add examples/rp-widget-demo/cart.js examples/rp-widget-demo/server.js examples/rp-widget-demo/tests/cart.test.js
git commit -m "feat(rp): POST /api/cart/items with server-side age gate"
```

---

## Task 5: `PATCH`/`DELETE`/`clear` cart endpoints

**Files:**
- Modify: `cart.js` (add `setQty`, `removeItem`)
- Modify: `server.js`
- Modify: `tests/cart.test.js`

**Step 1: Failing tests** — add three cases covering PATCH sets qty, PATCH with qty=0 removes, DELETE removes, POST /api/cart/clear empties.

**Step 2: Run → fail.**

**Step 3: Implement** the helpers and three routes. Re-check age gate on PATCH (increasing qty on an age-restricted item still requires verification).

**Step 4: Run → pass.**

**Step 5: Commit**
```bash
git commit -m "feat(rp): PATCH/DELETE/clear cart endpoints"
```

---

## Task 6: Age-gate kickoff — `POST /api/age-check/start`

**Why:** Start a verifier-api2 session for an age-only presentation.

**Files:**
- Modify: `server.js` (new route)
- Modify: `tests/server.test.js` — mock the verifier-api2 proxy response

**Step 1: Failing test** — POST `/api/age-check/start` → expect 200, body has `{ sessionId, qrCode }`.

**Step 2: Run → fail.**

**Step 3: Implement**

```js
app.post('/api/age-check/start', async (req, res) => {
  const dcql = buildAgeOnlyDcql();  // per-VCT + credential_sets, see design
  const r = await fetch(`${VERIFIER_API2_URL}/verification-session/create?rpId=${encodeURIComponent(RP_ID)}`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ dcql_query: dcql, webhookUrl: `${PUBLIC_URL}/api/age-check/webhook`, webhookSecret: AGE_WEBHOOK_SECRET }),
  });
  const session = await r.json();
  req.session.ageCheckSessionId = session.id;
  res.json({ sessionId: session.id, qrCode: session.qrCode });
});
```

Implement `buildAgeOnlyDcql()` with the same per-VCT + `credential_sets` shape PR #88 landed in auth-op's `buildDcqlQuery`. Claim: `[{ path: ["age_over_21"] }]`.

**Step 4: Run → pass.**

**Step 5: Commit**
```bash
git commit -m "feat(rp): age-only OID4VP kickoff endpoint"
```

---

## Task 7: Age-check webhook — set `session.ageVerified`

**Files:**
- Modify: `server.js`
- Modify: `tests/server.test.js`

**Step 1: Failing test** — POST `/api/age-check/webhook` with a fake `SUCCESSFUL` body containing `age_over_21=true`, then GET `/api/cart` (same agent) should show that `session.ageVerified` is true (via a follow-up `POST /api/cart/items` that now succeeds).

**Step 2: Run → fail (route missing).**

**Step 3: Implement** webhook handler. Verify `X-Webhook-Secret`. Read the claim from `presentedCredentials[...].credentialData.age_over_21`. Set `req.session.ageVerified` to the boolean. Reject if verifier-session does not match `req.session.ageCheckSessionId`.

Note: webhook is cross-session — use session-id in the URL or store a `sessionId → cookieOwner` map in memory. Simplest pattern mirrors auth-op: embed a random token in the webhookUrl path, store it in session, compare on callback.

**Step 4: Run → pass.**

**Step 5: Commit**
```bash
git commit -m "feat(rp): age-check webhook sets session.ageVerified"
```

---

## Task 8: UI — cart drawer skeleton + mini-cart icon

**Files:**
- Modify: `public/index.html` — add `.cart-icon` in `.header-actions`, `.cart-drawer` DOM, CSS.

**Step 1 (no failing unit test — pure DOM):** Add the HTML + CSS for:
- `.cart-icon` button in the header showing an SVG cart + `.cart-badge` span.
- `.cart-drawer` sliding in from the right. Empty-state illustration + "Keep shopping" button + items list placeholder + "Checkout" primary CTA.

**Step 2: Wire the toggle**
```js
const drawer = document.querySelector('.cart-drawer');
document.querySelector('.cart-icon').addEventListener('click', () => drawer.classList.toggle('open'));
document.querySelector('.cart-drawer .close').addEventListener('click', () => drawer.classList.remove('open'));
```

**Step 3: Smoke test** — `npm start`, open `/`, click cart icon, drawer opens.

**Step 4: Commit**
```bash
git commit -m "feat(rp): cart drawer + mini-cart icon chrome"
```

---

## Task 9: UI — cart drawer renders items, refreshes on mutation

**Files:**
- Modify: `public/index.html` — cart rendering, event handlers.

**Step 1:** Add `async function refreshCart()` that fetches `/api/cart` and populates drawer list + badge count.

**Step 2:** Hook the existing product-card `[Add to cart]` buttons. On click: `POST /api/cart/items` → if 200 call `refreshCart()`, if 403 fire the age-verify modal (Task 10).

**Step 3:** Wire qty +/- and × in drawer items to PATCH/DELETE endpoints with optimistic UI.

**Step 4:** Call `refreshCart()` on page load so a revisit with items still in session shows them.

**Step 5:** Playwright smoke — open drawer, add item from shelf (after setting `ageVerified=true` via `/_test/session`), see item appear in drawer. Add this to `tests/e2e/cart.spec.js`.

**Step 6: Commit**
```bash
git commit -m "feat(rp): cart drawer rendering + CRUD wiring"
```

---

## Task 10: UI — age-verify modal

**Files:**
- Modify: `public/index.html`

**Step 1:** Add a `<dialog class="age-verify">` with a QR code `<div>` and spinner.

**Step 2:** On product-card `Add` 403, open dialog, `POST /api/age-check/start`, render QR from `qrCode` field.

**Step 3:** Poll `GET /api/age-check/status` every 1.5s (new trivial endpoint that reads session flag). On `verified=true` → close dialog → retry original `POST /api/cart/items`. On `verified=false` → show "Sorry, 21+ only".

**Step 4:** Playwright e2e — intercept `/api/age-check/start` with a mock, stub status transition, confirm modal flow. Add to `tests/e2e/age-gate.spec.js`.

**Step 5: Commit**
```bash
git commit -m "feat(rp): age-verify modal + retry-on-verify flow"
```

---

## Task 11 — resolved (2026-04-21)

**Verdict: reuse existing tenant.**

The existing `docker-compose/issuer-api/config/issuer-tenants/a84e7c3a-b399-48e9-9345-2d8f062c614f.json` (State Bank of India) already has a `PaymentWalletAttestation` credential configuration. Generating a fresh Bank-of-Demo tenant adds ~1 hour of key-generation + x5c chain plumbing for zero demo benefit — so we instead override `PSP_TENANT_ID` in `docker-compose.yaml` to point at the SBI tenant UUID by default.

The enrolled PWA will be signed by the SBI signing key and the wallet will show "State Bank of India" as the issuer. The RP widget's enrollment copy still labels the surface as "Bank of Demo" since that's the conceptual role. Production would split per-bank tenants with their own keys + x5c chains.

Operators wanting a separate tenant override `PSP_TENANT_ID` in `.env.local`.

---

## Task 12 — resolved (2026-04-21)

**Verdict: deferred.**

`psp.theaustraliahack.com` vhost was planned as visual separation. With Task 11 reusing the existing SBI tenant, enrollment redirects flow through `issuer.theaustraliahack.com` directly. The demo is complete; the dedicated PSP vhost is a cosmetic improvement tracked separately.

If the vhost is wanted later: Caddyfile block + Cloudflare DNS CNAME + tunnel ingress rule. Process documented in `infrastructure.md` under "Managing routes via the Cloudflare API".

---

## Task 11: Mock PSP tenant JSON

**Files:**
- Create: `docker-compose/issuer-api/config/issuer-tenants/bank-of-demo.json`

**Step 1:** Copy the shape of `docker-compose/issuer-api/config/issuer-tenants/a84e7c3a-b399-48e9-9345-2d8f062c614f.json` (which already uses VCT `PaymentWalletAttestation`) into a new file with:
- tenant id: `psp.bankofdemo`
- display name: `Bank of Demo`
- country: `AU`
- single credential config for `PaymentWalletAttestation` with claims: `sub`, `iat`, `exp`, `panLastFour`, `iin`, `scheme`, `currency`, `payeeName`.

No code changes; this is pure config.

**Step 2: Deploy to remote.** Per memory (infrastructure.md §"Syncing Mac edits…"):
```bash
cd /Users/adambradley/Projects/Mastercard/India/waltid-identity/.worktrees/rp-cart-dpc
tar -cf - docker-compose/issuer-api/config/issuer-tenants/bank-of-demo.json | ssh sshuser@192.168.1.104 \
  'C:\Progra~1\Git\bin\bash.exe -c "cd /c/Users/sshuser/Projects/waltid-identity && tar -xvf -"'
ssh sshuser@192.168.1.104 'cd C:\Users\sshuser\Projects\waltid-identity\docker-compose && docker compose --profile identity restart issuer-api'
```

**Step 3: Smoke test** — `curl https://issuer.theaustraliahack.com/issuer/psp.bankofdemo/.well-known/openid-credential-issuer` should return the new tenant's metadata.

**Step 4: Commit**
```bash
git commit -m "feat(psp): Bank of Demo tenant for PaymentWalletAttestation issuance"
```

---

## Task 12: PSP vhost — Cloudflare tunnel + Caddy

**Files:**
- Modify: `docker-compose/Caddyfile` (add `psp.theaustraliahack.com` block)
- Add tunnel ingress rule via Cloudflare API (two calls per infrastructure.md §"Managing routes via the Cloudflare API")

**Step 1:** Append to `Caddyfile`:
```
psp.theaustraliahack.com {
  reverse_proxy issuer-api:${ISSUER_API_PORT:-7002}
}
```

**Step 2:** Push Caddyfile to remote using the `docker exec -i ... cat > /path` pattern (bind-mount fix from memory) — or the rsync-over-ssh tar trick from infrastructure.md. Then `docker restart docker-compose-caddy-1` (memory: `admin off` means no reload).

**Step 3:** DNS + tunnel update — two Cloudflare API calls from Doppler creds (`CLOUDFLARE_GLOBAL_API_KEY` + `CLOUDFLARE_EMAIL` for DNS, `CLOUDFLARE_API_TOKEN` for tunnel config). Tunnel UUID `9ea17645-1b2b-4296-b629-00af78c2d0c8` per memory. CNAME to `<uuid>.cfargotunnel.com` proxied.

**Step 4: Smoke test** — `curl https://psp.theaustraliahack.com/issuer/psp.bankofdemo/.well-known/openid-credential-issuer` returns 200 with tenant metadata.

**Step 5: Commit**
```bash
git commit -m "feat(infra): psp.theaustraliahack.com vhost for mock PSP"
```

---

## Task 13: PSP `/enroll` page

**Why:** A small HTML surface that drives the PID-presentation → PWA-issuance sequence. Served by the RP widget (simplest; no new service) at `rp.theaustraliahack.com/psp/enroll`, styled to look like the mock bank — despite sitting under the RP domain, users never enter banking creds; all trust is via wallet presentation. The `psp.theaustraliahack.com` vhost is the credential issuance API only.

**Files:**
- Create: `public/psp-enroll.html`
- Modify: `server.js` — serve it + add `POST /api/psp/start` (kicks PID OID4VP session) and `POST /api/psp/issue` (creates the credential offer at issuer-api once PID is verified)

**Step 1: Test** — `GET /psp/enroll` returns 200 HTML.
**Step 2: Impl** — route + static file.
**Step 3: Test** — `POST /api/psp/start` returns `{qrCode, sessionId}`.
**Step 4: Impl** — kick a verifier-api2 session for PID VCT with claims `[given_name, family_name, birth_date]`. On webhook, store `sub` + identity summary in `req.session.pspPidVerified`.
**Step 5: Test** — `POST /api/psp/issue` (PID already verified) returns `{offerUri}` pointing at an issuer-api credential offer.
**Step 6: Impl** — call `issuer-api/openid4vc/jwt/issue` (or the existing pre-authorized-code offer endpoint) for the `PaymentWalletAttestation` credential with mock data: `panLastFour = <sha256(sub) first 4 hex>`, `scheme = 'Visa'`, `iin = '453201'`, `currency = 'AUD'`, `payeeName = 'Bank of Demo'`.
**Step 7: Front-end** — `psp-enroll.html` shows two steps ("Verify your identity" / "Get your payment credential"), QR codes for each, auto-advances on success, renders a "Return to shop" button pointing at `rp.theaustraliahack.com/cart?pwa=1`.

**Step 8: Commit**
```bash
git commit -m "feat(rp): mock PSP enrollment page + PID→PWA issuance"
```

---

## Task 14: RP side — "Add payment method" in profile hover

**Files:**
- Modify: `public/index.html` — profile hover markup + styles
- Modify: `server.js` — the `userStore` profile already carries claims; add a field `paymentMethod` defaulting undefined

**Step 1:** In the profile hover popover (introduced in PR #87), after the claims list, add a section:
- If `profile.paymentMethod`: show `<div>Payment method: <strong>{scheme} ending {panLastFour}</strong></div>` + small `[Replace]` link → `/psp/enroll`.
- Else: `[Add payment method] → /psp/enroll`.

**Step 2:** CSS — match the existing glass styling.

**Step 3:** Smoke test — logged-in user with no payment method sees the button; clicking navigates to `/psp/enroll`.

**Step 4: Commit**
```bash
git commit -m "feat(rp): profile hover exposes Add/Replace payment method"
```

---

## Task 15: RP side — capture PWA metadata on `/cart?pwa=1`

**Status: RESOLVED — deleted. 2026-04-21.**

The capture flow was implemented (commit `ed0591dae`, landed alongside
the PSP move in `dcb5c6936` / `274c0d0e0`) but was subsequently removed
in full. Two reasons:

1. **Architecturally broken** — the kickoff minted a verifier-api2
   session but never rendered the QR / deep-link on the page, so no
   wallet could answer the presentation request. The "Verifying your
   new payment method…" banner looped for 30s and then timed out. The
   task description ("no deep-link/QR here — the wallet that just
   finished issuance is already the presenter") assumed a silent-
   presentation primitive that OID4VP doesn't define.

2. **Not standards-compliant** — even if the flow had worked, the
   merchant doesn't legitimately discover card metadata between
   enrollment and checkout. `panLastFour` / `scheme` are surfaced at
   checkout via the pay-session OID4VP presentation (Task 17/18); the
   RP profile hover does not need to render them.

Deleted:
- `server.js`: `/api/pwa/capture`, webhook, status routes + map + DCQL helper + `/_test/pwa-capture/register`
- `userStore.js`: `paymentMethod` from the authop allowlist
- `public/index.html`: banner DOM/CSS, `startPwaCapture`, profile-hover card-summary branch
- `tests/server.test.js`: four PWA-capture tests

Added in its place: on `?pwa=1`, the client flashes a 3.5s toast
("Payment method added. It lives in your wallet.") and strips the
query param. No server round-trip. See the design doc §
"RP metadata capture (REMOVED)".

---

## Task 16: `/checkout` review page

**Files:**
- Create: `public/checkout.html`
- Modify: `server.js` — `GET /checkout` serves the file if cart non-empty (else 302 `/`).

**Step 1:** Failing test — GET /checkout with empty cart → 302. With items → 200 HTML.
**Step 2:** Impl route.
**Step 3:** `checkout.html` DOM renders cart items, total, "Pay with ${scheme} ****${panLastFour}" badge (or "Add payment method" CTA if none), big `[Pay with EUDI Wallet]` primary button.
**Step 4:** Script fetches `/api/cart` and `/api/config` (extend config endpoint to expose `paymentMethod` from session profile) on load.
**Step 5: Commit**
```bash
git commit -m "feat(rp): /checkout review page"
```

---

## Task 17: Checkout kickoff — `POST /api/checkout`

**Files:**
- Modify: `server.js` — new route
- Modify: `tests/server.test.js`
- May depend on Task 19 depending on Task 0's verdict.

**Step 1: Failing test** — with an authenticated session, verified PWA, non-empty cart, POST returns `{orderId, qrCode, sessionId}`.

**Step 2: Impl:**
```js
app.post('/api/checkout', async (req, res) => {
  const cart = req.session.cart;
  if (!cart || !cart.items.length) return res.status(400).json({ error: 'empty_cart' });
  const orderId = 'ORDER-' + crypto.randomUUID();
  const total = summary(cart).subtotal;
  const txData = {
    type: 'payment_data',
    credential_ids: ['pwa'],
    payee: 'Oz Bottleshop Pty Ltd',
    amount: String(total),
    currency: 'AUD',
    transaction_ref: orderId
  };
  const session = await verifierCreateSession({
    dcql: buildPwaDcql(),
    transactionData: [txData],
    webhookUrl: `${PUBLIC_URL}/api/checkout/webhook/${orderId}`,
    webhookSecret: CHECKOUT_SECRET
  });
  req.session.pendingOrder = { orderId, total, txData, items: cart.items };
  res.json({ orderId, qrCode: session.qrCode, sessionId: session.id });
});
```

`buildPwaDcql` — credentials: `[{id:'pwa', format:'dc+sd-jwt', meta:{vct_values:['PaymentWalletAttestation']}, claims:[{path:['panLastFour']},{path:['scheme']}]}]` — no credential_sets needed for a single VCT single cred.

**Step 3: Run → pass.**
**Step 4: Commit**
```bash
git commit -m "feat(rp): POST /api/checkout with transaction_data"
```

---

## Task 18: Checkout webhook — record order, clear cart

**Files:**
- Modify: `server.js`
- Modify: `userStore.js` — `orders: [...]` on profile
- Modify: `tests/server.test.js`

**Step 1: Failing test** — webhook POST with `SUCCESSFUL` + the expected VP + key-binding JWT hash matching the session's transaction_data → 200; subsequent `GET /api/cart` empty; `userStore.get(sub).orders[0]` populated.

**Step 2: Impl:**
- Verify webhook secret (constant-time compare).
- Verify key-binding JWT `transaction_data_hashes[0]` matches `sha256(base64url(txData))` from `req.session.pendingOrder.txData`.
- Push order into `req.session.orders`; if authenticated, `userStore.pushOrder(sub, order)`.
- Clear `req.session.cart` and `req.session.pendingOrder`.

**Step 3: Run → pass.**
**Step 4: Commit**
```bash
git commit -m "feat(rp): checkout webhook records order + clears cart"
```

---

## Task 19: *(conditional — based on Task 0 outcome)*

If Task 0 resulted in Plan A': add the small verifier-api2 passthrough patch here, with tests. Skip otherwise.

---

## Task 20: `/order/:id` receipt page

**Files:**
- Create: `public/order.html`
- Modify: `server.js` — `GET /order/:id` serves HTML; `GET /api/orders/:id` returns JSON.

**Step 1: Failing test** — `GET /api/orders/<known>` returns order JSON; `GET /api/orders/<unknown>` returns 404.
**Step 2: Impl** — read from `req.session.orders` first, then `userStore.get(sub).orders`.
**Step 3: Front-end** — `order.html` renders items, total, "Paid with Visa ****4242", transaction ref, timestamp, "Verified by EUDI Wallet" badge.
**Step 4: Commit**
```bash
git commit -m "feat(rp): /order/:id receipt page"
```

---

## Task 21: Profile hover shows last order

**Files:**
- Modify: `public/index.html` — profile hover after payment-method section renders "Last order: ORDER-xxx — AUD 149.95" with a link to `/order/:id`.
**Step 1:** Pure UI task. Smoke test.
**Step 2: Commit**
```bash
git commit -m "feat(rp): profile hover shows last order"
```

---

## Task 22: Playwright e2e — anonymous age-gate path

**Files:**
- Create: `tests/e2e/age-gate.spec.js`

**Step 1:** Test flow:
- Open `/`.
- Click "Add to cart" on `hibiki-harmony`.
- Expect age-verify modal visible.
- Stub `/api/age-check/status` to return `{verified: true}` after 2s.
- Assert cart badge becomes `1`.
- Open drawer, assert item present.

Use Playwright `page.route()` to mock verifier-api2 endpoints.

**Step 2:** Run → green.
**Step 3: Commit**
```bash
git commit -m "test(rp): e2e age-gate anonymous path"
```

---

## Task 23: Playwright e2e — full happy-path checkout

**Files:**
- Create: `tests/e2e/checkout.spec.js`

**Step 1:** Test flow:
- Hydrate session via `/_test/session` with `user: {age_over_21: true, sub: 'test-sub'}` and `paymentMethod: {panLastFour: '4242', scheme: 'Visa'}`.
- Add item.
- Open drawer → Click Checkout → page is `/checkout`.
- Stub `/api/checkout` to return `{orderId: 'ORDER-test', qrCode: '<png>', sessionId: 's1'}`.
- Stub `/api/checkout/status/s1` to return `{status: 'SUCCESSFUL', orderId: 'ORDER-test'}`.
- Click "Pay with EUDI Wallet".
- Assert redirect to `/order/ORDER-test`.
- Assert receipt shows expected items and total.

**Step 2:** Run → green.
**Step 3: Commit**
```bash
git commit -m "test(rp): e2e happy-path checkout"
```

---

## Task 24: Final — ship prep

**Step 1:** `npm run test:all` passes in the worktree.
**Step 2:** Manual smoke on the live stack: browse `rp.theaustraliahack.com`, anonymous age-gate, login through auth-op, enroll at psp.theaustraliahack.com, checkout. Use iPhone + the per-VCT DCQL fix from PR #88. Capture two screenshots (cart drawer + receipt) to `docker-compose/` (convention used in repo already).
**Step 3:** Open PR against fork main:
```bash
gh pr create --repo adamjbradley/waltid-identity --base main \
  --title "feat(rp): cart, memoized age gate, DPC checkout (RFC007+RFC008)" \
  --body "$(cat docs/plans/2026-04-21-rp-cart-dpc-checkout-design.md | head -40)"
```

---

## Rollback path

All work is behind the worktree branch `feature/rp-cart-dpc`. If any stage goes sideways:
- Worktree files are isolated — reset the branch, the rest of the stack stays intact.
- For the PSP tenant JSON (Task 11) and Caddy+tunnel (Task 12), rollback is: remove the tenant file on the remote, restart `issuer-api`; revert the Caddyfile block, restart `caddy-1`; reverse the two Cloudflare API calls to drop the DNS record + tunnel rule.
- For the deployed RP image (after Task 24 builds it): retag `:stable` to the previous sha + SSH remote recreate — same pattern documented in `infrastructure.md` §"Never run docker compose up from the Mac".

---

## Progress checklist

- [ ] Task 0: `transaction_data` spike
- [ ] Task 1: server catalogue module
- [ ] Task 2: `/api/catalogue` + client load
- [ ] Task 3: `GET /api/cart`
- [ ] Task 4: `POST /api/cart/items` + age gate
- [ ] Task 5: PATCH/DELETE/clear cart
- [ ] Task 6: `/api/age-check/start`
- [ ] Task 7: `/api/age-check/webhook`
- [ ] Task 8: cart drawer chrome
- [ ] Task 9: cart drawer wiring
- [ ] Task 10: age-verify modal
- [ ] Task 11: PSP tenant JSON
- [ ] Task 12: PSP vhost (Caddy + tunnel)
- [ ] Task 13: `/psp/enroll` flow
- [ ] Task 14: "Add payment method" UI
- [ ] Task 15: PWA metadata capture
- [ ] Task 16: `/checkout` page
- [ ] Task 17: `POST /api/checkout`
- [ ] Task 18: checkout webhook
- [ ] Task 19: *(cond.)* verifier-api2 passthrough
- [ ] Task 20: `/order/:id` receipt
- [ ] Task 21: profile "Last order"
- [ ] Task 22: e2e age-gate
- [ ] Task 23: e2e checkout
- [ ] Task 24: ship prep + PR
