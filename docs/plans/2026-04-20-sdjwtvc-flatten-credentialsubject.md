# Flatten `credentialSubject` in DC+SD-JWT issuance

## Problem

iOS wallet (EudiWalletKit) refuses to present credentials issued by the walt.id stack when the verifier's DCQL query asks for a top-level claim like `age_over_18`. The wallet surfaces "The requested document is not available in your EUDI Wallet". Android wallet (eudi-lib-android-wallet-core) presents the same credential without issue.

Root cause: the portal sends `credentialData` in W3C-VC shape (`{credentialSubject: {age_over_18: true, …}}`) for the DC+SD-JWT format. Walt.id's issuer preserves the `credentialSubject` wrapper in the signed SD-JWT, so `age_over_18` lives at `["credentialSubject", "age_over_18"]`. IETF SD-JWT VC §4 says claims sit at the **top level** of the JWT payload — there is no `credentialSubject` container.

Android's DCQL matcher (`DcqlRequestProcessor.getSdJwtVcRequestedDocuments`) only matches on VCT and passes the DCQL path through to the disclosure walker, which finds the claim wherever it is. iOS's matcher (`Openid4VpUtils.resolveClaimsForCredential`) strictly requires every requested claim path to exist at its exact location; throws `Claim not found` otherwise → the UI shows the "not available" message.

## Approach

One branch, one file. In `waltid-applications/waltid-web-portal/utils/getOfferUrl.tsx`, the `DC+SD-JWT (EUDI)` branch around line 127, hoist `credentialData.credentialSubject.*` up to the root of `credentialData` before it leaves the browser:

```ts
if (payload.credentialData?.credentialSubject) {
  const { credentialSubject, ...rest } = payload.credentialData;
  payload.credentialData = { ...rest, ...credentialSubject };
}
```

The flat claim names now line up with `selectiveDisclosure.fields` (already flat), so the walt.id signer marks each one as selectively disclosable. The resulting SD-JWT has `age_over_18` as a top-level `_sd` digest with a matching disclosure — exactly what the IETF spec, iOS DCQL matcher, and Android walker all expect.

Other branches (mDoc, `jwt_vc_json`, `SD-JWT + IETF SD-JWT VC`) stay unchanged. Backend issuer-api and iOS wallet: no code changes.

## Files modified

- `waltid-applications/waltid-web-portal/utils/getOfferUrl.tsx` — one ~4-line insertion in the DC+SD-JWT branch.

## Rollout

1. Apply edit, commit, push to `docs/ios-multi-country-design`.
2. Rebuild portal image: `docker build -f waltid-applications/waltid-web-portal/Dockerfile -t waltid/portal:stable .` (streamed to Windows daemon, ~90s).
3. Recreate container via SSH: `ssh sshuser@192.168.1.104 "cd /d C:\\Users\\sshuser\\Projects\\waltid-identity\\docker-compose && docker compose --profile identity up -d --force-recreate --no-deps web-portal"` (Caddy untouched).

Credentials issued before this deploy keep their nested shape and will keep failing on iOS. Users must delete + re-issue once.

## Verification

1. **Shape check**: issue AU myGovID via the portal against the running issuer (scripted through the demo-wallet API). Decode the returned SD-JWT. Pass if `age_over_18` appears as a top-level `_sd` digest with a matching disclosure in the `~…` tail, **not** nested inside `credentialSubject`.
2. **iOS success path**: fresh issue + present to `rp.theaustraliahack.com` age-verification QR. Pass if the wallet shows the consent sheet with `age_over_18` disclosable and the verifier returns success.
3. **Android regression**: same flow on Android. Pass if still end-to-end successful.

## Out of scope

- Patching EudiWalletKit to be permissive like the Android lib (deferred — spec-correct server is the right layer).
- Backfilling already-issued credentials — users re-issue.
- Other credential formats (`jwt_vc_json`, mDoc) — already emit claims at their expected locations.
