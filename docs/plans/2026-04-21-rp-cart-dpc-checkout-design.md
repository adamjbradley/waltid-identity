# RP Storefront: Real Cart + Memoized Age Gate + DPC Checkout

**Date:** 2026-04-21
**Status:** Design — validated via brainstorming, not yet implemented
**Scope:** `examples/rp-widget-demo` (+ new `issuer-api` tenant, + Caddy/tunnel entry for the mock PSP)

## Goals

1. **Age gate memoization** — when the user has already proven `age_over_21` (via OIDC login or an anonymous OID4VP step in this session), never prompt them again for that session.
2. **Real add-to-cart** — server-authoritative cart state, mini-cart UI, add/remove/edit.
3. **Mock checkout using a DPC-style flow** — the EUDI pattern: one-off Payment Wallet Attestation (PWA) enrollment with a mock PSP, then a per-transaction OID4VP presentation with `transaction_data` at checkout (RFC007 + RFC008).

## Non-goals (YAGNI)

- Order history UI — orders live in session + userStore; no `/orders` list page.
- Multiple payment methods — one PWA per user.
- Shipping / tax / promo codes — flat `subtotal = total`.
- Anonymous-cart → logged-in-cart merging.
- Quantity editing beyond +1 / −1 / remove — no bundles or variants.
- Real PSP KYC or funding-source vetting — the mock PSP only checks the wallet has a PID.
- Real payment rails — the wallet's signed confirmation is the "payment" for the demo.

## Standards alignment

The checkout models the pattern converging in EUDI/EWC payment work as of April 2026:

- **EWC RFC007 — Payment Wallet Attestation** (`github.com/EWC-consortium/eudi-wallet-rfcs/blob/main/payment-rfcs/ewc-rfc007-payment-wallet-attestation.md`). Defines the issuance of a durable PWA credential from a PSP to a wallet, via OID4VCI Pre-Authorized Code flow. PSP-initiated variant is used here.
- **EWC RFC008 — Payment Data Confirmation** (`github.com/EWC-consortium/eudi-wallet-rfcs/blob/main/payment-rfcs/ewc-rfc008-payment-data-confirmation.md`). Defines the checkout-time OID4VP request carrying a `transaction_data` parameter (payee, amount, currency, ref). Wallet presents the existing PWA plus a key-binding JWT whose hash commits to the transaction_data.
- **EMVCo DPC** — still evolving (their 2026 work is a Digital Identity and Payments Task Force plus EUDI-Wallet-in-3DS). No separately-issued transaction-bound credential is called for; the OID4VP `transaction_data` binding provides transaction specificity. Our demo does not emit an EMVCo DPC credential — it uses the RFC007+RFC008 combination as the spec-consistent stand-in.

## Architecture

```
  user ─── rp.theaustraliahack.com            (storefront, cart, checkout UI, userStore)
       ─── auth-op.theaustraliahack.com       (identity login → age_over_21 claim)
       ─── psp.theaustraliahack.com           (NEW — mock "Bank of Demo" PWA issuer)
       ─── verifier-api2.theaustraliahack.com (OID4VP sessions: age-only gate, PWA metadata, checkout with transaction_data)
       ─── issuer-api.theaustraliahack.com    (hosts the new PSP tenant alongside existing identity tenants)
```

The PSP surface is a **new vhost that fronts the existing issuer-api**. No new Kotlin service — one more tenant JSON, one more Cloudflare tunnel ingress rule, a small HTML page served as a static asset for the `/enroll` landing. Keeps the stack simple.

## Feature 1 — Age gate memoization

### Decision tree on "Add to cart"

```
product.ageRestricted?        ─── no ──► add silently
        │ yes
        ▼
session.user?.age_over_21 === true?  ─── yes ──► add silently
        │ no
        ▼
session.ageVerified === true?        ─── yes ──► add silently
        │ no
        ▼
session.ageVerified === false?       ─── yes ──► block ("21+ only")
        │ not set
        ▼
fire OID4VP age-only session → on resolve set ageVerified → retry add
```

Product flag `ageRestricted: true` on every item in the current 12-item catalogue. The flag exists so non-alcohol items can sit alongside later without rewiring.

### OID4VP request for the anonymous gate

Single-purpose session against verifier-api2, same `rp.theaustraliahack.com` RP identity as the existing demo, but a **distinct DCQL query**:

```json
{
  "credentials": [
    { "id": "pid_0", "format": "dc+sd-jwt",
      "meta": { "vct_values": ["urn:eudi:pid:1"] },
      "claims": [{ "path": ["age_over_21"] }] },
    { "id": "pid_1", "format": "dc+sd-jwt",
      "meta": { "vct_values": ["urn:au:gov:mygovid:pid:1"] },
      "claims": [{ "path": ["age_over_21"] }] }
    // … one per VCT in the catalog …
  ],
  "credential_sets": [
    { "required": true,
      "options": [["pid_0"], ["pid_1"], ["pid_2"], ["pid_3"]] }
  ]
}
```

Per-VCT singletons + credential_sets — the same shape PR #88 landed in auth-op's composer, for the same reason (EUDI iOS wallet-kit `CredentialQuery.docType` only reads the first VCT of a multi-VCT `vct_values`).

### UX

- Inline toast on the product card: *"This product is 21+. Verify with your wallet."* with a `[Verify]` button.
- Click `[Verify]` → small modal with QR (desktop) or deep-link (mobile), showing a spinner while polling verifier-api2's `/info` endpoint.
- On `SUCCESSFUL` with `age_over_21 == true` → modal collapses, item is added, header cart badge bumps.
- On `SUCCESSFUL` with `age_over_21 == false` → "Sorry, this is a 21+ store" — session.ageVerified = false, no further retries until session reset.
- On wallet abort / timeout → modal closes, `ageVerified` stays unset, user can retry.

### Session state transitions

| From | Event | To |
|---|---|---|
| unset | OID4VP success, claim=true | `true` |
| unset | OID4VP success, claim=false | `false` |
| unset | OID4VP abort/timeout | unset |
| `true`/`false` | — | sticky for session lifetime |

Stored only in `req.session.ageVerified`. No userStore write — the flag is ephemeral on purpose so a new session re-verifies (avoids stale proofs for the demo).

## Feature 2 — Real cart

### Data model (server-side, express-session-scoped)

```js
req.session.cart = {
  items: [
    {
      productId: "japanese-whisky-hibiki",
      qty: 1,
      priceAud: 180,
      title: "Hibiki Harmony",
      imageUrl: "/img/hibiki.jpg",
      ageRestricted: true
    }
  ],
  updatedAt: 1713...  // epoch ms
}
```

Line items are **denormalized** at add-time — `priceAud`, `title`, `imageUrl` captured once so the cart is stable if the catalogue mutates mid-session. The catalogue itself stays a constant in `server.js` (same 12 items PR #87 introduced).

### REST API

| Method | Path | Body | Notes |
|---|---|---|---|
| `GET` | `/api/cart` | — | Returns `{ items, subtotal, count }`. |
| `POST` | `/api/cart/items` | `{productId, qty?}` | Adds or increments. **Age-gated server-side.** |
| `PATCH` | `/api/cart/items/:productId` | `{qty}` | `qty=0` behaves as DELETE. |
| `DELETE` | `/api/cart/items/:productId` | — | |
| `POST` | `/api/cart/clear` | — | Post-checkout reset. |

Server-side enforcement — POST/PATCH re-check `session.user?.age_over_21 || session.ageVerified` before mutating. Return **403** if the user tries to bypass; the client's age-verify flow then fires. The front-end gate is a UX nicety; the server gate is the truth.

### UI

- **Header:** cart icon + count badge. Count fetched on page load and after every mutation.
- **Drawer:** right-side slide-in triggered by the icon. Renders line items, qty +/- buttons, `×` remove, subtotal, `[Checkout]` primary CTA, `[Keep shopping]` close.
- **Empty state:** illustration + "Your cart is empty" + link back to the shelf.
- **Product card:** existing `[Add to cart]` wires to `POST /api/cart/items`. On 200, badge bumps; on 403, the age-verify flow from Feature 1 fires, then retries.

### Persistence

Session-only. Survives page refreshes (express-session cookie). Does **not** survive logout. Logged-out user can still fill a cart (anonymous). YAGNI: no anonymous → logged-in cart merge.

## Feature 3 — Mock PSP + PWA enrollment (one-off)

### PSP surface — reuse issuer-api via a new tenant

New file `docker-compose/issuer-api/config/issuer-tenants/bank-of-demo.json`:

```json
{
  "id": "psp.bankofdemo",
  "displayName": "Bank of Demo",
  "country": "AU",
  "credentialConfigurations": {
    "PaymentWalletAttestation": {
      "format": "dc+sd-jwt",
      "vct": "PaymentWalletAttestation",
      "credentialSubject": {
        "sub":           { "mandatory": true },
        "iat":           { "mandatory": true },
        "exp":           { "mandatory": true },
        "panLastFour":   { "mandatory": true,  "display": [{"name":"Card last 4","locale":"en"}] },
        "iin":           { "mandatory": true,  "display": [{"name":"Issuer Identification Number","locale":"en"}] },
        "scheme":        { "mandatory": true,  "display": [{"name":"Scheme","locale":"en"}] },
        "currency":      { "mandatory": true },
        "payeeName":     { "mandatory": true }
      }
    }
  }
}
```

VCT `PaymentWalletAttestation` — matches the repo's existing `PWA_ENABLED` feature flag convention (`docker-compose/issuer-api/config/issuer-tenants/a84e7c3a-….json` already uses it).

### Tunnel / Caddy

One new ingress rule: `psp.theaustraliahack.com` → `host.docker.internal:7002/issuer/psp.bankofdemo/...` (reusing the ISSUER_API port). Matches the pattern used for every other vhost on the stack. Added via two Cloudflare API calls (DNS CNAME + tunnel config PUT), same procedure documented in memory.

### Enrollment flow (one-off, ~30-60s user time)

```
RP profile hover ──► [Add payment method] click
  → 302 to psp.theaustraliahack.com/enroll?return=<rp-cart-url>
  → PSP mini-page (static HTML served by issuer-api or a small Ktor route):
     ├─ step 1: present PID via OID4VP (wallet proves identity; PSP captures sub)
     └─ step 2: build pre-authorized-code credential offer for PaymentWalletAttestation
                with demo card data — panLastFour derived from hash(sub), scheme "Visa",
                iin "453201", currency "AUD", payeeName "Bank of Demo"
  → wallet consumes the offer → PWA lives in wallet
  → 302 back to rp.theaustraliahack.com/cart?pwa=1
```

### RP metadata capture (silent, post-enrollment)

On landing `/cart?pwa=1`:

1. RP fires an OID4VP request to verifier-api2 for `PaymentWalletAttestation` VCT, claims `[panLastFour, scheme, payeeName]`.
2. Wallet presents; RP reads the disclosed claims.
3. RP writes `profile.paymentMethod = { panLastFour, scheme, payeeName, addedAt }` to **userStore** (the persistent store PR #81 introduced).
4. Profile hover now renders "Payment method: Visa ending 4242".

### Edge cases

- **User already has a PWA** — PSP shows "You already have a card enrolled" + `[Replace]`. Replace issues a fresh credential with new `cnf`; RP re-captures metadata.
- **Wallet has no PID** — step 1 fails; PSP surfaces "You need an identity credential first" + link to `issuer.theaustraliahack.com`.
- **User aborts mid-enrollment** — RP never sees `?pwa=1`, userStore unchanged. Profile still says "Add payment method".

### Trust

PSP signs PWAs with its own signing key — reuses the existing issuer-tenant signing machinery. Verifier-api2 already trusts the AU custom TSL that includes `Bank of Demo`; no new trust plumbing.

## Feature 4 — Checkout (RFC008 pattern)

### Flow

```
cart drawer → [Checkout] → /checkout review page
             (line items, total, "Pay with: Visa ****4242")
  → [Pay with EUDI Wallet] click
  → POST /api/checkout {cart, pwaMeta} → server creates orderRef,
        kicks off verifier-api2 session with transaction_data
  → modal: QR (desktop) / deep-link (mobile) + polling spinner
  → wallet renders "Oz Bottleshop Pty Ltd requests AUD 149.95 — Approve?"
  → user biometric-approves → VP posted back to verifier-api2
  → verifier-api2 webhook → RP records order, clears cart, returns orderId
  → client redirects to /order/:orderId
```

### OID4VP request shape

```json
{
  "dcql_query": {
    "credentials": [{
      "id": "pwa",
      "format": "dc+sd-jwt",
      "meta": { "vct_values": ["PaymentWalletAttestation"] },
      "claims": [
        { "path": ["panLastFour"] },
        { "path": ["scheme"] }
      ]
    }]
  },
  "transaction_data": [{
    "type": "payment_data",
    "credential_ids": ["pwa"],
    "payee": "Oz Bottleshop Pty Ltd",
    "amount": "149.95",
    "currency": "AUD",
    "transaction_ref": "ORDER-<uuid-v4>"
  }]
}
```

Per RFC008, the wallet's key-binding JWT includes `transaction_data_hashes[0]` = SHA-256 of the base64url-encoded `transaction_data[0]`. The RP side recomputes and equality-checks during response handling; mismatch = reject.

### Technical risk to validate before implementation

**Does verifier-api2 accept `transaction_data` in `/verification-session/create` today?** The verifier library in this repo (`waltid-libraries/protocols/waltid-openid4vp-verifier`) targets OID4VP 1.0; `transaction_data` is a Draft 22+ addition. A first-task 30-minute spike answers this:

- **Plan A — it works** (or needs a few-line passthrough): extend verifier-api2 session-create to accept `transaction_data`, propagate into the signed request object, verify the hash on response.
- **Plan B — not supported, need fallback**: smuggle transaction_data into the DCQL as a synthetic claim the wallet echoes. Not spec-compliant, demo-only, clearly documented. Flag a TODO to upgrade when verifier-api2 gains native support.

### Order record

```js
req.session.orders = [
  {
    id: "ORDER-<uuid>",
    items: [...],
    total: 149.95,
    currency: "AUD",
    pwaMeta: { panLastFour: "4242", scheme: "Visa" },
    transactionRef: "ORDER-<uuid>",
    approvedAt: 1713...,
    vpDigest: "<sha256 of presented VP>"
  }
]
```

Mirrored to `userStore.orders[]` when user is logged in (persistent across sessions). Anonymous orders stay session-only.

`/order/:orderId` renders a receipt page — items, total, "Paid with Visa ****4242", transaction ref, timestamp, small "Verified by EUDI Wallet" badge.

### Post-checkout

- `req.session.cart` cleared.
- Profile hover shows "Last order: ORDER-…(link) — AUD 149.95".
- No email/SMS.
- No real payment rail call — the wallet-signed `transaction_data` confirmation is the demo's "payment".

## Cross-cutting: auth-op already provides age_over_21

The auth-op citizens realm already requests `age_over_21` in its scope catalog (see `docker-compose/auth-op/config/realms.conf` after PR #86). When the user logs in through auth-op, `req.session.user.age_over_21` is populated. Feature 1's gate reads that first. No changes to auth-op or the realm config are required for the age memoization — it's entirely an RP-side change.

## Implementation sequencing

0. **Spike** — confirm verifier-api2 support for `transaction_data` (Plan A vs Plan B).
1. **Product catalogue** — add `ageRestricted: true` flag; stub `paymentMethod` display on profile.
2. **Feature 2 cart** — REST API, drawer UI, mini-cart icon. Ship behind no flag; existing shelf "Add to cart" buttons start doing real things.
3. **Feature 1 age gate** — server-side 403 path, client-side age-verify modal, session state, integration with cart POST.
4. **Feature 3 mock PSP** — new issuer-api tenant, Cloudflare tunnel entry, `/enroll` mini-page, enrollment flow, RP-side `/cart?pwa=1` handler + silent metadata capture.
5. **Feature 4 checkout** — `/checkout` page, `POST /api/checkout`, verifier-api2 session with `transaction_data`, webhook handler, `/order/:id` receipt page.
6. **Testing** — Playwright end-to-end: anon→age gate→add→login→checkout (needs-PSP-enrollment path) and pre-enrolled user fast-path.

Ships as a single PR — the surfaces are interlocked and half-shipping any one leaves a confusing UX. A bundled review is saner than four separate reviews of disembodied components.
