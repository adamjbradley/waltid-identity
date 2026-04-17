# iOS wallet: multi-country flavors (EUDI / AU / IN) → TestFlight

## Context

The Android wallet ships three flavors — `dev` (EUDI), `au`, `in` — built from one codebase with per-flavor source directories. Testers install all three side-by-side from a single GitHub release and demo cross-border verification against `theaustraliahack.com`.

iOS has no equivalent. The repo has dev/release bundle IDs (`eu.europa.ec.euidi.dev` / `eu.europa.ec.euidi`) but no country variants. This plan brings iOS to feature parity via TestFlight, installable side-by-side as three apps.

## Approach

One target in `EudiReferenceWallet.xcodeproj`. Three schemes driven by xcconfig files, compile-time branching via `SWIFT_ACTIVE_COMPILATION_CONDITIONS`. Mirrors Android's per-flavor source directories.

Rejected alternatives: separate targets sharing a Swift Package (too much refactoring); runtime flavor switch (breaks side-by-side install).

## File layout

```
Wallet/
  Config/
    ConfigProtocol.swift       # shared interface
    ConfigEUDI.swift           # #if FLAVOR_EUDI
    ConfigAU.swift             # #if FLAVOR_AU
    ConfigIN.swift             # #if FLAVOR_IN
    ConfigFactory.swift        # returns right Config at runtime
  Certificate/
    rp_theaustraliahack_EUDI.pem
    rp_theaustraliahack_AU.pem
    rp_theaustraliahack_IN.pem
  Assets.xcassets/
    AppIcon-EUDI.appiconset/
    AppIcon-AU.appiconset/
    AppIcon-IN.appiconset/

Config/
  EUDI.xcconfig   # PRODUCT_BUNDLE_IDENTIFIER, AppIcon, FLAVOR_EUDI
  AU.xcconfig
  IN.xcconfig

fastlane/
  .env.eudi
  .env.au
  .env.in
```

## What differs per flavor

| | EUDI | AU | IN |
|---|---|---|---|
| Bundle ID | `eu.europa.ec.euidi.dev` | `eu.europa.ec.euidi.au` | `eu.europa.ec.euidi.in` |
| Display name | EUDI Wallet | AU Wallet | IN Wallet |
| App icon | AppIcon-EUDI | AppIcon-AU | AppIcon-IN |
| Trusted VCTs | `[urn:eudi:pid:1]` | `[urn:eudi:pid:1, urn:au:gov:mygovid:pid:1, urn:au:gov:dl:1, urn:au:gov:medicare:1]` | `[urn:eudi:pid:1, urn:in:gov:dl:1, urn:in:gov:pan:1]` |

`rpCaCertificateName` and `issuerURL` are split per-flavor up front so they can diverge later without a code change; initial content is identical.

Not varied: verifier URL, SD-JWT handling, UI, deep-link scheme.

## Shared `WalletConfig` protocol

```swift
protocol WalletConfig {
    var rpCaCertificateName: String { get }
    var issuerURL: URL { get }
    var verifierURL: URL { get }
    var trustedCredentialVCTs: [String] { get }
}
```

Three implementations conform, each behind `#if FLAVOR_*`. Consumed by reader-auth trust loader, issuer/verifier clients, DCQL matcher.

## Fastlane + TestFlight

The existing `deploy` lane in `fastlane/Fastfile` is already env-driven (`APP_SCHEME`, `APP_BUNDLE_ID`, `APP_PROVISION_PROFILE`, etc.). Add:

- Three `.env.<flavor>` files with per-flavor overrides
- New `deploy_all` wrapper that runs `deploy` three times, one env file per flavor

`upload_to_testflight` produces three independent beta apps. Testers get three invite links; installing all three gives side-by-side icons.

## Prerequisites (human)

1. Create App Store Connect API key (App Manager role). Note Key ID + Issuer ID, download `.p8`.
2. Register 3 App IDs in Apple Developer Portal — or approve `fastlane produce` doing it.
3. Agreements signed in App Store Connect.

Distribution cert already exists: `Apple Distribution: Adelaidensis Pty Ltd (8YR6HDPX5S)`.

## Execution order (automated)

1. Worktree + branch `feature/multi-country-ios-flavors`
2. Split `Config.swift` → `ConfigProtocol` + `ConfigEUDI` + `ConfigFactory`; add PEM files
3. Add `AU.xcconfig`, `IN.xcconfig`, `EUDI.xcconfig`; duplicate schemes
4. Stub `ConfigAU.swift` + `ConfigIN.swift` with VCT arrays
5. Import AU/IN AppIcon asset sets
6. Add `.env.*` and `deploy_all` lane
7. `fastlane produce` per bundle ID (if approved)
8. `fastlane deploy_all` → three IPAs to TestFlight

## Verification

- `xcodebuild -scheme Wallet-<flavor> -configuration Release build` succeeds for each
- `fastlane deploy` with each `.env.*` completes, IPA lands in App Store Connect within minutes
- On device: three icons, each flavor's VCT list drives what's shown on the home screen
- `adb`-equivalent on iOS: `xcrun devicectl device install app` confirms install for debug paths

## Rollback

xcconfig changes are additive; existing `Wallet` scheme keeps building unchanged. Worst case revert the feature branch — the original dev build is untouched. TestFlight uploads can be expired, not deleted, so a bad build just stops being downloadable.

## Out of scope

- Refactoring per-flavor business logic beyond config (match what Android does: config-only diffs for now)
- Production App Store submission (TestFlight beta only)
- Expanding trusted issuers per flavor (add once issuer-api multi-tenant routing lands for AU/IN paths)
