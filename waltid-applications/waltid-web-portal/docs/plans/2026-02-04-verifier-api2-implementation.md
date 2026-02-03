# Verifier API2 Integration Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Route EUDI verification formats (`dc+sd-jwt`, `mso_mdoc`) to Verifier API2 with DCQL queries while maintaining legacy API support.

**Architecture:** Format-based routing in verify page. EUDI formats go to API2 (`/verification-session/create`), legacy formats stay on existing API (`/openid4vc/verify`). Graceful error when API2 not configured.

**Tech Stack:** Next.js, TypeScript, Axios, Jest

---

### Task 1: Add NEXT_PUBLIC_VERIFIER2 Environment Variable

**Files:**
- Modify: `next.config.js:4-9`
- Modify: `pages/api/env.ts:9-14`
- Modify: `.env.example`

**Step 1: Write the failing test**

Add to `__tests__/credentials.test.ts`:

```typescript
describe('Environment Configuration', () => {
  it('should recognize NEXT_PUBLIC_VERIFIER2 as a valid env variable', () => {
    const envVars = [
      'NEXT_PUBLIC_VC_REPO',
      'NEXT_PUBLIC_ISSUER',
      'NEXT_PUBLIC_VERIFIER',
      'NEXT_PUBLIC_VERIFIER2',
      'NEXT_PUBLIC_WALLET',
    ];
    expect(envVars).toContain('NEXT_PUBLIC_VERIFIER2');
  });
});
```

**Step 2: Run test to verify it passes (simple assertion)**

Run: `npm test -- --testNamePattern="Environment Configuration"`
Expected: PASS (this is a documentation test)

**Step 3: Update next.config.js**

Add `NEXT_PUBLIC_VERIFIER2` to publicRuntimeConfig:

```javascript
const nextConfig = {
  reactStrictMode: false,
  output: 'standalone',
  publicRuntimeConfig: {
    NEXT_PUBLIC_VC_REPO: process.env.NEXT_PUBLIC_VC_REPO ?? "https://credentials.walt.id/",
    NEXT_PUBLIC_ISSUER: process.env.NEXT_PUBLIC_ISSUER ?? "https://issuer.portal.walt.id",
    NEXT_PUBLIC_VERIFIER: process.env.NEXT_PUBLIC_VERIFIER ?? "https://verifier.portal.walt.id",
    NEXT_PUBLIC_VERIFIER2: process.env.NEXT_PUBLIC_VERIFIER2 ?? "",
    NEXT_PUBLIC_WALLET: process.env.NEXT_PUBLIC_WALLET ?? "https://wallet.walt.id"
  },
}

module.exports = nextConfig
```

**Step 4: Update pages/api/env.ts**

Add `NEXT_PUBLIC_VERIFIER2` to the response:

```typescript
import type {NextApiRequest, NextApiResponse} from "next";

type ResponseData = {};

export default function handler(
  req: NextApiRequest,
  res: NextApiResponse<ResponseData>
) {
  res.status(200).json({
    NEXT_PUBLIC_VC_REPO: process.env.NEXT_PUBLIC_VC_REPO,
    NEXT_PUBLIC_ISSUER: process.env.NEXT_PUBLIC_ISSUER,
    NEXT_PUBLIC_VERIFIER: process.env.NEXT_PUBLIC_VERIFIER,
    NEXT_PUBLIC_VERIFIER2: process.env.NEXT_PUBLIC_VERIFIER2,
    NEXT_PUBLIC_WALLET: process.env.NEXT_PUBLIC_WALLET,
  });
}
```

**Step 5: Update .env.example**

```
NEXT_PUBLIC_VC_REPO="https://credentials.walt.id"
NEXT_PUBLIC_VERIFIER2="http://localhost:7004"
```

**Step 6: Run all tests**

Run: `npm test`
Expected: All tests pass

**Step 7: Commit**

```bash
git add next.config.js pages/api/env.ts .env.example __tests__/credentials.test.ts
git commit -m "feat: add NEXT_PUBLIC_VERIFIER2 environment variable"
```

---

### Task 2: Add isEudiFormat Helper and Tests

**Files:**
- Modify: `types/credentials.tsx`
- Modify: `__tests__/credentials.test.ts`

**Step 1: Write the failing test**

Add to `__tests__/credentials.test.ts`:

```typescript
import { isEudiFormat } from '@/types/credentials';

describe('isEudiFormat', () => {
  it('should return true for dc+sd-jwt format', () => {
    expect(isEudiFormat('dc+sd-jwt')).toBe(true);
  });

  it('should return true for mso_mdoc format', () => {
    expect(isEudiFormat('mso_mdoc')).toBe(true);
  });

  it('should return false for vc+sd-jwt format', () => {
    expect(isEudiFormat('vc+sd-jwt')).toBe(false);
  });

  it('should return false for jwt_vc_json format', () => {
    expect(isEudiFormat('jwt_vc_json')).toBe(false);
  });
});
```

**Step 2: Run test to verify it fails**

Run: `npm test -- --testNamePattern="isEudiFormat"`
Expected: FAIL with "isEudiFormat is not a function" or import error

**Step 3: Implement isEudiFormat in types/credentials.tsx**

Add after the `mapFormat` function:

```typescript
export function isEudiFormat(format: string): boolean {
  return format === 'dc+sd-jwt' || format === 'mso_mdoc';
}
```

**Step 4: Run test to verify it passes**

Run: `npm test -- --testNamePattern="isEudiFormat"`
Expected: PASS (4 tests)

**Step 5: Commit**

```bash
git add types/credentials.tsx __tests__/credentials.test.ts
git commit -m "feat: add isEudiFormat helper function"
```

---

### Task 3: Add buildDcqlQuery Helper and Tests

**Files:**
- Modify: `types/credentials.tsx`
- Modify: `__tests__/credentials.test.ts`

**Step 1: Write the failing test**

Add to `__tests__/credentials.test.ts`:

```typescript
import { buildDcqlQuery, AvailableCredential } from '@/types/credentials';

describe('buildDcqlQuery', () => {
  it('should build correct DCQL query for mso_mdoc format', () => {
    const credentials: AvailableCredential[] = [{
      id: 'eu.europa.ec.eudi.pid.1',
      title: 'EU Personal ID (mDoc)',
      offer: { doctype: 'eu.europa.ec.eudi.pid.1' }
    }];

    const result = buildDcqlQuery(credentials, 'mso_mdoc');

    expect(result).toEqual({
      credentials: [{
        id: 'eu.europa.ec.eudi.pid.1',
        format: 'mso_mdoc',
        meta: { doctype_value: 'eu.europa.ec.eudi.pid.1' }
      }]
    });
  });

  it('should build correct DCQL query for dc+sd-jwt format', () => {
    const credentials: AvailableCredential[] = [{
      id: 'urn:eudi:pid:1',
      title: 'EU Personal ID (SD-JWT)',
      offer: { vct: 'urn:eudi:pid:1' }
    }];

    const result = buildDcqlQuery(credentials, 'dc+sd-jwt');

    expect(result).toEqual({
      credentials: [{
        id: 'urn:eudi:pid:1',
        format: 'dc+sd-jwt',
        meta: { vct_values: ['urn:eudi:pid:1'] }
      }]
    });
  });

  it('should use credential id as fallback for doctype', () => {
    const credentials: AvailableCredential[] = [{
      id: 'org.iso.18013.5.1.mDL',
      title: 'Mobile Driving License',
      offer: {}
    }];

    const result = buildDcqlQuery(credentials, 'mso_mdoc');

    expect(result.credentials[0].meta.doctype_value).toBe('org.iso.18013.5.1.mDL');
  });

  it('should use default vct if not provided', () => {
    const credentials: AvailableCredential[] = [{
      id: 'some-pid',
      title: 'PID',
      offer: {}
    }];

    const result = buildDcqlQuery(credentials, 'dc+sd-jwt');

    expect(result.credentials[0].meta.vct_values).toEqual(['urn:eudi:pid:1']);
  });

  it('should handle multiple credentials', () => {
    const credentials: AvailableCredential[] = [
      { id: 'eu.europa.ec.eudi.pid.1', title: 'PID', offer: { doctype: 'eu.europa.ec.eudi.pid.1' } },
      { id: 'org.iso.18013.5.1.mDL', title: 'mDL', offer: { doctype: 'org.iso.18013.5.1.mDL' } }
    ];

    const result = buildDcqlQuery(credentials, 'mso_mdoc');

    expect(result.credentials).toHaveLength(2);
  });
});
```

**Step 2: Run test to verify it fails**

Run: `npm test -- --testNamePattern="buildDcqlQuery"`
Expected: FAIL with import error

**Step 3: Implement buildDcqlQuery in types/credentials.tsx**

Add after the `isEudiFormat` function:

```typescript
export interface DcqlCredential {
  id: string;
  format: string;
  meta: {
    doctype_value?: string;
    vct_values?: string[];
  };
}

export interface DcqlQuery {
  credentials: DcqlCredential[];
}

export function buildDcqlQuery(credentials: AvailableCredential[], format: string): DcqlQuery {
  return {
    credentials: credentials.map((credential) => {
      if (format === 'mso_mdoc') {
        return {
          id: credential.id,
          format: 'mso_mdoc',
          meta: {
            doctype_value: credential.offer.doctype || credential.id,
          },
        };
      } else {
        // dc+sd-jwt
        return {
          id: credential.id,
          format: 'dc+sd-jwt',
          meta: {
            vct_values: [credential.offer.vct || 'urn:eudi:pid:1'],
          },
        };
      }
    }),
  };
}
```

**Step 4: Run test to verify it passes**

Run: `npm test -- --testNamePattern="buildDcqlQuery"`
Expected: PASS (5 tests)

**Step 5: Commit**

```bash
git add types/credentials.tsx __tests__/credentials.test.ts
git commit -m "feat: add buildDcqlQuery helper for Verifier API2"
```

---

### Task 4: Add buildVerificationSessionRequest Helper and Tests

**Files:**
- Modify: `types/credentials.tsx`
- Modify: `__tests__/credentials.test.ts`

**Step 1: Write the failing test**

Add to `__tests__/credentials.test.ts`:

```typescript
import { buildVerificationSessionRequest } from '@/types/credentials';

describe('buildVerificationSessionRequest', () => {
  it('should build correct API2 request structure', () => {
    const dcqlQuery = {
      credentials: [{
        id: 'eu.europa.ec.eudi.pid.1',
        format: 'mso_mdoc',
        meta: { doctype_value: 'eu.europa.ec.eudi.pid.1' }
      }]
    };
    const successUri = 'https://portal/success/$id';
    const errorUri = 'https://portal/error/$id';

    const result = buildVerificationSessionRequest(dcqlQuery, successUri, errorUri);

    expect(result).toEqual({
      flow_type: 'cross_device',
      core_flow: {
        dcql_query: dcqlQuery
      },
      success_redirect_uri: successUri,
      error_redirect_uri: errorUri
    });
  });
});
```

**Step 2: Run test to verify it fails**

Run: `npm test -- --testNamePattern="buildVerificationSessionRequest"`
Expected: FAIL with import error

**Step 3: Implement buildVerificationSessionRequest in types/credentials.tsx**

Add after the `buildDcqlQuery` function:

```typescript
export interface VerificationSessionRequest {
  flow_type: string;
  core_flow: {
    dcql_query: DcqlQuery;
  };
  success_redirect_uri: string;
  error_redirect_uri: string;
}

export function buildVerificationSessionRequest(
  dcqlQuery: DcqlQuery,
  successRedirectUri: string,
  errorRedirectUri: string
): VerificationSessionRequest {
  return {
    flow_type: 'cross_device',
    core_flow: {
      dcql_query: dcqlQuery,
    },
    success_redirect_uri: successRedirectUri,
    error_redirect_uri: errorRedirectUri,
  };
}
```

**Step 4: Run test to verify it passes**

Run: `npm test -- --testNamePattern="buildVerificationSessionRequest"`
Expected: PASS

**Step 5: Commit**

```bash
git add types/credentials.tsx __tests__/credentials.test.ts
git commit -m "feat: add buildVerificationSessionRequest helper"
```

---

### Task 5: Update checkVerificationResult for API2 Support

**Files:**
- Modify: `utils/checkVerificationResult.ts`
- Modify: `__tests__/credentials.test.ts`

**Step 1: Write the failing test**

Add to `__tests__/credentials.test.ts`:

```typescript
describe('checkVerificationResult endpoint selection', () => {
  it('should use legacy endpoint path for isApi2=false', () => {
    const verifierUrl = 'http://localhost:7003';
    const sessionId = 'test-session';
    const isApi2 = false;

    // We can't easily test the actual function without mocking axios,
    // but we can test the endpoint construction logic
    const endpoint = isApi2
      ? `${verifierUrl}/verification-session/${sessionId}`
      : `${verifierUrl}/openid4vc/session/${sessionId}`;

    expect(endpoint).toBe('http://localhost:7003/openid4vc/session/test-session');
  });

  it('should use API2 endpoint path for isApi2=true', () => {
    const verifierUrl = 'http://localhost:7004';
    const sessionId = 'test-session';
    const isApi2 = true;

    const endpoint = isApi2
      ? `${verifierUrl}/verification-session/${sessionId}`
      : `${verifierUrl}/openid4vc/session/${sessionId}`;

    expect(endpoint).toBe('http://localhost:7004/verification-session/test-session');
  });
});
```

**Step 2: Run test to verify it passes (logic test)**

Run: `npm test -- --testNamePattern="checkVerificationResult endpoint"`
Expected: PASS

**Step 3: Update utils/checkVerificationResult.ts**

Replace entire file:

```typescript
import axios from 'axios';

export function getStateFromUrl(url: string) {
    try {
        const normalizedUrl = url.replace(/^openid4vp:/, 'https:').replace(/^mdoc-openid4vp:/, 'https:');
        const parsedUrl = new URL(normalizedUrl);
        return parsedUrl.searchParams.get('state');
    } catch (e) {
        const stateMatch = url.match(/[?&]state=([^&]+)/);
        return stateMatch ? decodeURIComponent(stateMatch[1]) : null;
    }
}

export async function checkVerificationResult(
    verifierURL: string,
    sessionId: string,
    isApi2: boolean = false
): Promise<boolean> {
    const endpoint = isApi2
        ? `${verifierURL}/verification-session/${encodeURIComponent(sessionId)}`
        : `${verifierURL}/openid4vc/session/${encodeURIComponent(sessionId)}`;

    return new Promise((resolve) => {
        const poll = async () => {
            try {
                const response = await axios.get(endpoint, {
                    headers: { 'accept': 'application/json' }
                });

                const data = response.data;

                // API2 uses 'state' field, legacy uses 'verificationResult'
                if (isApi2) {
                    if (data.state === 'SUCCESS') {
                        return resolve(true);
                    } else if (data.state === 'FAILED') {
                        return resolve(false);
                    }
                } else {
                    if (data.verificationResult === true) {
                        return resolve(true);
                    } else if (data.verificationResult === false) {
                        return resolve(false);
                    }
                }

                setTimeout(poll, 1000);
            } catch (error) {
                console.error("Error fetching session:", error);
                return resolve(false);
            }
        };

        poll();
    });
}
```

**Step 4: Run all tests**

Run: `npm test`
Expected: All tests pass

**Step 5: Commit**

```bash
git add utils/checkVerificationResult.ts __tests__/credentials.test.ts
git commit -m "feat: update checkVerificationResult for API2 support"
```

---

### Task 6: Update Verify Page with API2 Routing

**Files:**
- Modify: `pages/verify/index.tsx`

**Step 1: Read current verify page state**

The verify page needs these changes:
- Import new helpers
- Add error state
- Check for API2 configuration when EUDI format selected
- Route to appropriate API based on format
- Track which API was used for result polling

**Step 2: Update imports at top of pages/verify/index.tsx**

```typescript
import {useContext, useEffect, useState} from "react";
import WaltIcon from "@/components/walt/logo/WaltIcon";
import Button from "@/components/walt/button/Button";
import {CredentialsContext, EnvContext} from "@/pages/_app";
import Icon from "@/components/walt/logo/Icon";
import {useRouter} from "next/router";
import QRCode from "react-qr-code";
import axios from "axios";
import {sendToWebWallet} from "@/utils/sendToWebWallet";
import nextConfig from "@/next.config";
import BackButton from "@/components/walt/button/BackButton";
import {CredentialFormats, mapFormat, isEudiFormat, buildDcqlQuery, buildVerificationSessionRequest} from "@/types/credentials";
import {checkVerificationResult, getStateFromUrl} from "@/utils/checkVerificationResult";
```

**Step 3: Add error state and usedApi2 tracking**

After line 25 (`const [copyText, setCopyText] = useState(BUTTON_COPY_TEXT_DEFAULT);`), add:

```typescript
const [error, setError] = useState<string | null>(null);
const [usedApi2, setUsedApi2] = useState(false);
```

**Step 4: Update useEffect with API2 routing logic**

Replace the entire useEffect (lines 31-120) with:

```typescript
useEffect(() => {
  const getverifyURL = async () => {
    let vps = router.query.vps?.toString().split(',') ?? [];
    let ids = router.query.ids?.toString().split(',') ?? [];
    let format = router.query.format?.toString() ?? CredentialFormats[0];
    let credentials = AvailableCredentials.filter((cred) => {
      for (const id of ids) {
        if (id.toString() == cred.id.toString()) {
          return true;
        }
      }
      return false;
    });

    const credFormat = mapFormat(format);

    // Check if this is an EUDI format requiring API2
    if (isEudiFormat(credFormat)) {
      const verifier2Url = env.NEXT_PUBLIC_VERIFIER2 || nextConfig.publicRuntimeConfig?.NEXT_PUBLIC_VERIFIER2;

      if (!verifier2Url) {
        setError('EUDI verification requires Verifier API2 configuration. Please set NEXT_PUBLIC_VERIFIER2.');
        setLoading(false);
        return;
      }

      // Build DCQL query for API2
      const dcqlQuery = buildDcqlQuery(credentials, credFormat);
      const requestBody = buildVerificationSessionRequest(
        dcqlQuery,
        `${window.location.origin}/success/$id`,
        `${window.location.origin}/success/$id`
      );

      try {
        const response = await axios.post(
          `${verifier2Url}/verification-session/create`,
          requestBody,
          {
            headers: {
              'Content-Type': 'application/json',
            },
          }
        );

        setverifyURL(response.data);
        setUsedApi2(true);
        setLoading(false);

        const state = getStateFromUrl(response.data);
        if (state) {
          checkVerificationResult(verifier2Url, state, true).then((result) => {
            if (result) {
              router.push(`/success/${state}`);
            }
          });
        }
      } catch (err: any) {
        setError(`Verification request failed: ${err.message}`);
        setLoading(false);
      }
    } else {
      // Legacy API flow
      const standardVersion = 'draft13';
      const issuerMetadataConfigSelector = {
        'draft13': 'credential_configurations_supported',
        'draft11': 'credentials_supported',
      };

      const issuerMetadata = await axios.get(`${env.NEXT_PUBLIC_ISSUER ? env.NEXT_PUBLIC_ISSUER : nextConfig.publicRuntimeConfig!.NEXT_PUBLIC_ISSUER}/${standardVersion}/.well-known/openid-credential-issuer`);

      const request_credentials = credentials.map((credential) => {
        if (credFormat === 'vc+sd-jwt') {
          const vct = issuerMetadata.data[issuerMetadataConfigSelector[standardVersion]][`${credential.offer.type[credential.offer.type.length - 1]}_vc+sd-jwt`]?.vct;
          return { vct, format: 'vc+sd-jwt' };
        } else {
          return {
            type: credential.offer.type?.[credential.offer.type.length - 1] || credential.id,
            format: credFormat,
          };
        }
      });

      let requestBody: any = {
        request_credentials: request_credentials,
      };

      if (credFormat !== 'vc+sd-jwt') {
        requestBody.vc_policies = vps.map((vp) => {
          if (vp.includes('=')) {
            return {
              policy: vp.split('=')[0],
              args: vp.split('=')[1],
            };
          } else {
            return vp;
          }
        });
      }

      const verifierUrl = env.NEXT_PUBLIC_VERIFIER ? env.NEXT_PUBLIC_VERIFIER : nextConfig.publicRuntimeConfig!.NEXT_PUBLIC_VERIFIER;

      try {
        const response = await axios.post(
          `${verifierUrl}/openid4vc/verify`,
          requestBody,
          {
            headers: {
              successRedirectUri: `${window.location.origin}/success/$id`,
              errorRedirectUri: `${window.location.origin}/success/$id`,
            },
          }
        );

        setverifyURL(response.data);
        setUsedApi2(false);
        setLoading(false);

        const state = getStateFromUrl(response.data);
        if (state) {
          checkVerificationResult(verifierUrl, state, false).then((result) => {
            if (result) {
              router.push(`/success/${state}`);
            }
          });
        }
      } catch (err: any) {
        setError(`Verification request failed: ${err.message}`);
        setLoading(false);
      }
    }
  };
  getverifyURL();
}, []);
```

**Step 5: Update render to show error state**

Replace the QR code section (inside the flex justify-center div, around lines 159-167) with:

```typescript
<div className="flex justify-center">
  {error ? (
    <div className="text-red-600 my-10 text-center">
      <p className="font-semibold">{error}</p>
      <p className="text-sm text-gray-500 mt-2">
        Please contact your administrator.
      </p>
    </div>
  ) : loading ? (
    <div className="animate-spin rounded-full h-32 w-32 border-b-2 border-gray-900 my-10"></div>
  ) : (
    <QRCode
      className="h-full max-h-[220px] my-10"
      value={verifyURL}
      viewBox={'0 0 256 256'}
    />
  )}
</div>
```

**Step 6: Run all tests**

Run: `npm test`
Expected: All tests pass

**Step 7: Commit**

```bash
git add pages/verify/index.tsx
git commit -m "feat: add Verifier API2 routing for EUDI formats"
```

---

### Task 7: Manual Testing and Final Verification

**Step 1: Create local .env file for testing**

Create `.env.local`:

```
NEXT_PUBLIC_VC_REPO=https://credentials.walt.id
NEXT_PUBLIC_ISSUER=http://localhost:7002
NEXT_PUBLIC_VERIFIER=http://localhost:7003
NEXT_PUBLIC_VERIFIER2=http://localhost:7004
NEXT_PUBLIC_WALLET=http://localhost:7101
```

**Step 2: Start the portal**

Run: `npm run dev`
Expected: Portal starts on http://localhost:3000

**Step 3: Test EUDI mDoc verification**

1. Select "EU Personal ID (mDoc)" on home page
2. Select "mDoc (ISO 18013-5)" format
3. Click Verify
4. Verify QR code appears with `mdoc-openid4vp://` scheme

**Step 4: Test EUDI SD-JWT verification**

1. Select "EU Personal ID (SD-JWT)" on home page
2. Select "DC+SD-JWT (EUDI)" format
3. Click Verify
4. Verify QR code appears with `openid4vp://` scheme

**Step 5: Test error handling**

1. Remove NEXT_PUBLIC_VERIFIER2 from .env.local
2. Restart portal
3. Select "EU Personal ID (mDoc)" and verify
4. Should show error: "EUDI verification requires Verifier API2 configuration"

**Step 6: Test legacy format still works**

1. Select any VC repo credential
2. Select "JWT + W3C VC" format
3. Verify QR code appears (uses legacy API)

**Step 7: Run final test suite**

Run: `npm test`
Expected: All tests pass

**Step 8: Final commit**

```bash
git add -A
git commit -m "chore: add local env example for testing"
```
