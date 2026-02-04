# Verifier API2 Integration for EUDI Verification

## Summary

Integrate Verifier API2 into the web portal to enable EUDI wallet verification. Route EUDI formats (`dc+sd-jwt`, `mso_mdoc`) to API2 using DCQL queries while maintaining legacy API support for other formats.

## Architecture

### Format-Based Routing

| Format | API | Endpoint |
|--------|-----|----------|
| `dc+sd-jwt` | Verifier API2 | `/verification-session/create` |
| `mso_mdoc` | Verifier API2 | `/verification-session/create` |
| `vc+sd-jwt` | Legacy Verifier | `/openid4vc/verify` |
| `jwt_vc_json` | Legacy Verifier | `/openid4vc/verify` |

### Environment Variables

- `NEXT_PUBLIC_VERIFIER` - Legacy Verifier API (port 7003) - existing
- `NEXT_PUBLIC_VERIFIER2` - Verifier API2 (port 7004) - new

### Request Flow

1. User selects credential + format on home page
2. Verify page determines if format is EUDI (`dc+sd-jwt` or `mso_mdoc`)
3. If EUDI format:
   - Check if `NEXT_PUBLIC_VERIFIER2` is configured
   - If not configured: show error, don't proceed
   - If configured: build DCQL query, POST to API2
4. If legacy format:
   - Use existing logic with `NEXT_PUBLIC_VERIFIER`

## DCQL Query Format

Verifier API2 uses DCQL (Digital Credentials Query Language) queries:

```typescript
// API2 request structure
{
  "flow_type": "cross_device",
  "core_flow": {
    "dcql_query": {
      "credentials": [
        {
          "id": "credential-id",
          "format": "mso_mdoc" | "dc+sd-jwt",
          "meta": {
            "doctype_value": "eu.europa.ec.eudi.pid.1"  // for mso_mdoc
            // OR
            "vct_values": ["urn:eudi:pid:1"]  // for dc+sd-jwt
          }
        }
      ]
    }
  },
  "success_redirect_uri": "https://portal/success/$id",
  "error_redirect_uri": "https://portal/success/$id"
}
```

### DCQL Query Builder

```typescript
function buildDcqlCredential(credential, format) {
  if (format === 'mso_mdoc') {
    return {
      id: credential.id,
      format: 'mso_mdoc',
      meta: { doctype_value: credential.offer.doctype || credential.id }
    };
  } else if (format === 'dc+sd-jwt') {
    return {
      id: credential.id,
      format: 'dc+sd-jwt',
      meta: { vct_values: [credential.offer.vct || 'urn:eudi:pid:1'] }
    };
  }
}
```

## Error Handling

### Configuration Validation

```typescript
const isEudiFormat = (format: string) =>
  format === 'dc+sd-jwt' || format === 'mso_mdoc';

const verifier2Url = env.NEXT_PUBLIC_VERIFIER2 ||
  nextConfig.publicRuntimeConfig?.NEXT_PUBLIC_VERIFIER2;

if (isEudiFormat(credFormat) && !verifier2Url) {
  setError('EUDI verification requires Verifier API2 configuration');
  setLoading(false);
  return;
}
```

### Error State Display

```tsx
const [error, setError] = useState<string | null>(null);

{error ? (
  <div className="text-red-600 my-10 text-center">
    <p className="font-semibold">{error}</p>
    <p className="text-sm text-gray-500 mt-2">
      Please contact your administrator.
    </p>
  </div>
) : loading ? (
  <Spinner />
) : (
  <QRCode value={verifyURL} />
)}
```

## Result Polling

Adapt existing `checkVerificationResult` utility to support both APIs:

```typescript
export async function checkVerificationResult(
  verifierUrl: string,
  state: string,
  isApi2: boolean = false
): Promise<boolean> {
  const endpoint = isApi2
    ? `${verifierUrl}/verification-session/${state}`
    : `${verifierUrl}/openid4vc/session/${state}`;

  // Existing polling logic with updated endpoint
}
```

Track which API was used in verify page state to pass correct parameters.

## Files to Modify

| File | Changes |
|------|---------|
| `pages/verify/index.tsx` | Add API2 routing logic, DCQL builder, error state |
| `utils/checkVerificationResult.ts` | Add `isApi2` parameter, API2 endpoint path |
| `pages/_app.tsx` | Add `NEXT_PUBLIC_VERIFIER2` to EnvContext |
| `next.config.js` | Add `NEXT_PUBLIC_VERIFIER2` to publicRuntimeConfig |
| `.env.example` | Document `NEXT_PUBLIC_VERIFIER2` variable |

## Testing

### Unit Tests

Extend existing `credentials.test.ts`:
- Test `isEudiFormat` returns true for `dc+sd-jwt`, `mso_mdoc`
- Test `buildDcqlQuery` generates correct DCQL structure

### Manual Testing

1. Select PID mDoc → verify QR uses `mdoc-openid4vp://` scheme
2. Select PID SD-JWT → verify request has DCQL query format
3. Remove `NEXT_PUBLIC_VERIFIER2` → verify graceful error message
4. Select JWT format → verify legacy API still works
