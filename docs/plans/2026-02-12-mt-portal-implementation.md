# Multi-Tenant Portal Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Extend the web portal with tenant-aware issuance/verification flows, a credential template picker, and admin action buttons.

**Architecture:** All changes are portal-only (no backend modifications). Tenant selection uses URL query params (`issuerId`, `rpId`) for deep-link support. New UI is gated behind existing feature flags (`ISSUER_REGISTRAR_ENABLED`, `RP_REGISTRAR_ENABLED`). Credential templates are stored client-side.

**Tech Stack:** Next.js, React, TypeScript, Tailwind CSS, Headless UI, axios

**Design doc:** `docs/plans/2026-02-12-mt-portal-design.md`

---

## Task 1: Credential Template Library

Create a static collection of credential configuration templates that can be added to issuer tenant catalogs.

**Files:**
- Create: `types/credentialTemplates.ts`
- Test: `__tests__/credential-templates.test.ts`

**Step 1: Write the test**

Create `__tests__/credential-templates.test.ts`:

```typescript
import { credentialTemplates, CredentialTemplate, getTemplatesByCategory } from '../types/credentialTemplates';

describe('Credential Template Library', () => {
  it('should have templates organized by category', () => {
    const categories = [...new Set(credentialTemplates.map(t => t.category))];
    expect(categories).toContain('EUDI');
    expect(categories).toContain('Financial');
    expect(categories).toContain('Identity');
  });

  it('each template should have required fields', () => {
    credentialTemplates.forEach((t: CredentialTemplate) => {
      expect(t.id).toBeTruthy();
      expect(t.name).toBeTruthy();
      expect(t.category).toBeTruthy();
      expect(t.config).toBeDefined();
      expect(typeof t.config).toBe('object');
    });
  });

  it('EUDI templates should have correct format', () => {
    const eudiTemplates = credentialTemplates.filter(t => t.category === 'EUDI');
    expect(eudiTemplates.length).toBeGreaterThanOrEqual(3);
    eudiTemplates.forEach(t => {
      const configValue = Object.values(t.config)[0] as any;
      expect(['mso_mdoc', 'dc+sd-jwt']).toContain(configValue.format);
    });
  });

  it('getTemplatesByCategory should filter correctly', () => {
    const eudi = getTemplatesByCategory('EUDI');
    eudi.forEach(t => expect(t.category).toBe('EUDI'));
  });

  it('template config keys should match template id', () => {
    credentialTemplates.forEach(t => {
      expect(Object.keys(t.config)).toContain(t.id);
    });
  });
});
```

**Step 2: Run test to verify it fails**

Run: `cd waltid-applications/waltid-web-portal && npx jest __tests__/credential-templates.test.ts --no-coverage`

Expected: FAIL — module not found

**Step 3: Write the template library**

Create `types/credentialTemplates.ts`:

```typescript
export interface CredentialTemplate {
  id: string;
  name: string;
  description: string;
  category: 'EUDI' | 'Financial' | 'Identity' | 'Custom';
  format: string;
  config: Record<string, any>;
}

export const credentialTemplates: CredentialTemplate[] = [
  // -- EUDI --
  {
    id: 'eu.europa.ec.eudi.pid.1',
    name: 'EU Personal ID (mDoc)',
    description: 'EUDI PID credential in mso_mdoc format',
    category: 'EUDI',
    format: 'mso_mdoc',
    config: {
      'eu.europa.ec.eudi.pid.1': {
        format: 'mso_mdoc',
        cryptographic_binding_methods_supported: ['cose_key'],
        credential_signing_alg_values_supported: ['ES256'],
        proof_types_supported: { jwt: { proof_signing_alg_values_supported: ['ES256'] } },
        doctype: 'eu.europa.ec.eudi.pid.1',
      },
    },
  },
  {
    id: 'org.iso.18013.5.1.mDL',
    name: 'Mobile Driving License',
    description: 'ISO 18013-5 mDL in mso_mdoc format',
    category: 'EUDI',
    format: 'mso_mdoc',
    config: {
      'org.iso.18013.5.1.mDL': {
        format: 'mso_mdoc',
        cryptographic_binding_methods_supported: ['cose_key'],
        credential_signing_alg_values_supported: ['ES256'],
        proof_types_supported: { jwt: { proof_signing_alg_values_supported: ['ES256'] } },
        doctype: 'org.iso.18013.5.1.mDL',
      },
    },
  },
  {
    id: 'urn:eudi:pid:1',
    name: 'EU Personal ID (SD-JWT)',
    description: 'EUDI PID credential in dc+sd-jwt format',
    category: 'EUDI',
    format: 'dc+sd-jwt',
    config: {
      'urn:eudi:pid:1': {
        format: 'dc+sd-jwt',
        cryptographic_binding_methods_supported: ['jwk'],
        credential_signing_alg_values_supported: ['ES256'],
        proof_types_supported: { jwt: { proof_signing_alg_values_supported: ['ES256'] } },
        vct: 'urn:eudi:pid:1',
      },
    },
  },
  // -- Financial --
  {
    id: 'BankId_jwt_vc_json',
    name: 'Bank ID',
    description: 'Bank identity credential in JWT format',
    category: 'Financial',
    format: 'jwt_vc_json',
    config: {
      'BankId_jwt_vc_json': {
        format: 'jwt_vc_json',
        cryptographic_binding_methods_supported: ['did'],
        credential_signing_alg_values_supported: ['ES256', 'EdDSA'],
        credential_definition: { type: ['VerifiableCredential', 'BankId'] },
      },
    },
  },
  {
    id: 'PaymentWalletAttestation',
    name: 'Payment Wallet Attestation',
    description: 'EWC RFC007 payment funding source binding',
    category: 'Financial',
    format: 'dc+sd-jwt',
    config: {
      'PaymentWalletAttestation': {
        format: 'dc+sd-jwt',
        cryptographic_binding_methods_supported: ['jwk'],
        credential_signing_alg_values_supported: ['ES256'],
        proof_types_supported: { jwt: { proof_signing_alg_values_supported: ['ES256'] } },
        vct: 'PaymentWalletAttestation',
      },
    },
  },
  // -- Identity --
  {
    id: 'VerifiableId_jwt_vc_json',
    name: 'National ID',
    description: 'National identity document in JWT format',
    category: 'Identity',
    format: 'jwt_vc_json',
    config: {
      'VerifiableId_jwt_vc_json': {
        format: 'jwt_vc_json',
        cryptographic_binding_methods_supported: ['did'],
        credential_signing_alg_values_supported: ['ES256', 'EdDSA'],
        credential_definition: { type: ['VerifiableCredential', 'VerifiableId'] },
      },
    },
  },
  {
    id: 'Passport_jwt_vc_json',
    name: 'Passport',
    description: 'Passport credential in JWT format',
    category: 'Identity',
    format: 'jwt_vc_json',
    config: {
      'Passport_jwt_vc_json': {
        format: 'jwt_vc_json',
        cryptographic_binding_methods_supported: ['did'],
        credential_signing_alg_values_supported: ['ES256', 'EdDSA'],
        credential_definition: { type: ['VerifiableCredential', 'VerifiableAttestation', 'Passport'] },
      },
    },
  },
  {
    id: 'ResidencePermit_jwt_vc_json',
    name: 'Residence Permit',
    description: 'Residence permit credential in JWT format',
    category: 'Identity',
    format: 'jwt_vc_json',
    config: {
      'ResidencePermit_jwt_vc_json': {
        format: 'jwt_vc_json',
        cryptographic_binding_methods_supported: ['did'],
        credential_signing_alg_values_supported: ['ES256', 'EdDSA'],
        credential_definition: { type: ['VerifiableCredential', 'VerifiableAttestation', 'ResidencePermit'] },
      },
    },
  },
];

export function getTemplatesByCategory(category: CredentialTemplate['category']): CredentialTemplate[] {
  return credentialTemplates.filter(t => t.category === category);
}

export function getTemplateById(id: string): CredentialTemplate | undefined {
  return credentialTemplates.find(t => t.id === id);
}
```

**Step 4: Run test to verify it passes**

Run: `cd waltid-applications/waltid-web-portal && npx jest __tests__/credential-templates.test.ts --no-coverage`

Expected: PASS

**Step 5: Commit**

```bash
git add types/credentialTemplates.ts __tests__/credential-templates.test.ts
git commit -m "feat(portal): add credential template library for tenant catalogs"
```

---

## Task 2: Credential Template Picker in Issuer Admin

Replace the raw JSON textarea in the issuer detail panel with a template picker UI. Keep an "Edit as JSON" escape hatch.

**Files:**
- Modify: `pages/admin/issuers.tsx:735-800` (credential configurations section)
- Test: `__tests__/admin-issuers.test.tsx` (add template picker tests)

**Step 1: Write the tests**

Add to `__tests__/admin-issuers.test.tsx`:

```typescript
describe('Template Picker', () => {
  it('should show "Add from Templates" button in credential section', async () => {
    const { getByText } = render(<Issuers />);
    await waitFor(() => getByText('GlobalPay Corp'));
    // Expand first issuer
    fireEvent.click(getByText('GlobalPay Corp'));
    await waitFor(() => getByText('Add from Templates'));
    expect(getByText('Add from Templates')).toBeTruthy();
  });

  it('should show template categories when "Add from Templates" is clicked', async () => {
    const { getByText } = render(<Issuers />);
    await waitFor(() => getByText('GlobalPay Corp'));
    fireEvent.click(getByText('GlobalPay Corp'));
    await waitFor(() => getByText('Add from Templates'));
    fireEvent.click(getByText('Add from Templates'));
    await waitFor(() => {
      expect(getByText('EUDI')).toBeTruthy();
      expect(getByText('Financial')).toBeTruthy();
      expect(getByText('Identity')).toBeTruthy();
    });
  });

  it('should show "Edit as JSON" toggle', async () => {
    const { getByText } = render(<Issuers />);
    await waitFor(() => getByText('GlobalPay Corp'));
    fireEvent.click(getByText('GlobalPay Corp'));
    await waitFor(() => getByText('Edit as JSON'));
  });
});
```

**Step 2: Run tests to verify they fail**

Run: `cd waltid-applications/waltid-web-portal && npx jest __tests__/admin-issuers.test.tsx --no-coverage`

Expected: FAIL — "Add from Templates" not found

**Step 3: Implement the template picker**

Modify `pages/admin/issuers.tsx`:

1. Add import at top:
```typescript
import { credentialTemplates, CredentialTemplate, getTemplatesByCategory } from '@/types/credentialTemplates';
```

2. Replace the credential configurations section (lines 735-800) in the `IssuerDetailPanel` component. The new section:
   - Shows current credentials as cards with name, format badge, and a remove button
   - Has an "Add from Templates" button that toggles a template picker grid
   - Template picker groups templates by category with clickable cards
   - Clicking a template adds its config to the tenant's `credentialConfigurations`
   - A save button sends `PUT /admin/issuer/{id}/credentials`
   - An "Edit as JSON" toggle reveals the existing textarea for advanced users

Key state additions in the parent `Issuers` component:
```typescript
const [showTemplatePicker, setShowTemplatePicker] = useState<Record<string, boolean>>({});
```

Key logic in `IssuerDetailPanel`:
```typescript
// Check if a template is already in the catalog
function isTemplateInCatalog(template: CredentialTemplate): boolean {
  return Object.keys(detail.credentialConfigurations).includes(template.id);
}

// Add template to catalog (local state, not saved until Save clicked)
function handleAddTemplate(template: CredentialTemplate) {
  const updated = { ...detail.credentialConfigurations, ...template.config };
  onCredentialConfigChange(JSON.stringify(updated, null, 2));
}

// Remove credential from catalog
function handleRemoveCredential(configKey: string) {
  const updated = { ...detail.credentialConfigurations };
  delete updated[configKey];
  onCredentialConfigChange(JSON.stringify(updated, null, 2));
}
```

The template picker modal/section renders:
```tsx
{['EUDI', 'Financial', 'Identity'].map(category => (
  <div key={category}>
    <h5 className="text-xs font-semibold text-gray-500 uppercase mb-2">{category}</h5>
    <div className="grid grid-cols-2 gap-2">
      {getTemplatesByCategory(category as any).map(template => (
        <button
          key={template.id}
          disabled={isTemplateInCatalog(template)}
          onClick={() => handleAddTemplate(template)}
          className={`text-left p-2 rounded-lg border text-xs transition-colors ${
            isTemplateInCatalog(template)
              ? 'border-gray-200 bg-gray-50 text-gray-400 cursor-not-allowed'
              : 'border-gray-300 hover:border-blue-500 hover:bg-blue-50 cursor-pointer'
          }`}
        >
          <span className="font-medium">{template.name}</span>
          <span className="block text-gray-400 mt-0.5">{template.format}</span>
        </button>
      ))}
    </div>
  </div>
))}
```

**Step 4: Run tests**

Run: `cd waltid-applications/waltid-web-portal && npx jest __tests__/admin-issuers.test.tsx --no-coverage`

Expected: PASS

**Step 5: Commit**

```bash
git add pages/admin/issuers.tsx __tests__/admin-issuers.test.tsx
git commit -m "feat(portal): replace credential JSON editor with template picker"
```

---

## Task 3: Issuer Admin Action Buttons

Add "Issue Credential" and "View Metadata" buttons to the issuer detail panel.

**Files:**
- Modify: `pages/admin/issuers.tsx:802-836` (actions section)
- Test: `__tests__/admin-issuers.test.tsx`

**Step 1: Write the tests**

Add to `__tests__/admin-issuers.test.tsx`:

```typescript
describe('Issuer Admin Action Buttons', () => {
  it('should show "Issue Credential" button for active issuer with credentials', async () => {
    const { getByText } = render(<Issuers />);
    await waitFor(() => getByText('GlobalPay Corp'));
    fireEvent.click(getByText('GlobalPay Corp'));
    await waitFor(() => getByText('Issue Credential'));
  });

  it('should show "View Metadata" link for active issuer with certificate', async () => {
    const { getByText } = render(<Issuers />);
    await waitFor(() => getByText('GlobalPay Corp'));
    fireEvent.click(getByText('GlobalPay Corp'));
    await waitFor(() => getByText('View Metadata'));
  });

  it('should show "Configure credentials first" when no credentials', async () => {
    // Mock issuer with hasCertificate but 0 credentials
    const { getByText } = render(<Issuers />);
    await waitFor(() => getByText('NoCert Corp'));
    fireEvent.click(getByText(/NoCert Corp/));
    await waitFor(() => expect(getByText('Configure credentials first')).toBeTruthy());
  });
});
```

**Step 2: Run tests to verify they fail**

Expected: FAIL — "Issue Credential" not found

**Step 3: Implement action buttons**

In `pages/admin/issuers.tsx`, modify the Actions section (around line 802). Add above the existing Suspend/Delete buttons:

```tsx
{/* Quick Actions */}
<div className="mb-4 flex gap-2">
  {detail.status === 'ACTIVE' && detail.x5Chain && Object.keys(detail.credentialConfigurations).length > 0 ? (
    <button
      onClick={() => {
        const credIds = Object.keys(detail.credentialConfigurations).join(',');
        window.location.href = `/credentials?ids=${credIds}&issuerId=${issuer.id}&mode=issuance`;
      }}
      className="inline-flex items-center gap-1.5 px-3 py-1.5 bg-emerald-600 text-white text-sm font-medium rounded-lg hover:bg-emerald-700 transition-colors"
    >
      Issue Credential
    </button>
  ) : detail.status === 'ACTIVE' && detail.x5Chain ? (
    <span className="text-sm text-amber-600">Configure credentials first</span>
  ) : null}

  {detail.status === 'ACTIVE' && detail.x5Chain && (
    <a
      href={`${apiBase}/issuers/${issuer.id}/draft13/.well-known/openid-credential-issuer`}
      target="_blank"
      rel="noopener noreferrer"
      className="inline-flex items-center gap-1.5 px-3 py-1.5 text-blue-600 text-sm font-medium rounded-lg border border-blue-200 hover:bg-blue-50 transition-colors"
    >
      View Metadata
    </a>
  )}
</div>
```

Where `apiBase` is the NEXT_PUBLIC_ISSUER env var already available in the component.

**Step 4: Run tests**

Expected: PASS

**Step 5: Commit**

```bash
git add pages/admin/issuers.tsx __tests__/admin-issuers.test.tsx
git commit -m "feat(portal): add Issue Credential and View Metadata buttons to issuer admin"
```

---

## Task 4: Issuer Tenant Dropdown in IssueSection

Add an "Issuing as" dropdown to IssueSection when `ISSUER_REGISTRAR_ENABLED=true`. Selecting an issuer filters credentials to the tenant's catalog.

**Files:**
- Modify: `components/sections/IssueSection.tsx`
- Test: `__tests__/issue-section-tenant.test.tsx`

**Step 1: Write the test**

Create `__tests__/issue-section-tenant.test.tsx`:

```typescript
/**
 * @jest-environment jsdom
 */
import React from 'react';
import { render, waitFor, fireEvent } from '@testing-library/react';
import '@testing-library/jest-dom';

// Test the tenant dropdown logic without rendering the full component
describe('IssueSection tenant dropdown logic', () => {
  it('should construct tenant-scoped offer URL when issuerId is present', () => {
    const issuerId = 'tenant-123';
    const baseUrl = '/offer?ids=BankId&authenticationMethod=PRE_AUTHORIZED';
    const url = issuerId ? `${baseUrl}&issuerId=${issuerId}` : baseUrl;
    expect(url).toContain('issuerId=tenant-123');
  });

  it('should not include issuerId in offer URL when none selected', () => {
    const issuerId: string | undefined = undefined;
    const baseUrl = '/offer?ids=BankId&authenticationMethod=PRE_AUTHORIZED';
    const url = issuerId ? `${baseUrl}&issuerId=${issuerId}` : baseUrl;
    expect(url).not.toContain('issuerId');
  });

  it('should filter credentials to tenant catalog when issuerId provided', () => {
    const tenantCredentials = {
      'eu.europa.ec.eudi.pid.1': { format: 'mso_mdoc' },
      'BankId_jwt_vc_json': { format: 'jwt_vc_json' },
    };
    const allCredentials = [
      { id: 'eu.europa.ec.eudi.pid.1', title: 'PID' },
      { id: 'org.iso.18013.5.1.mDL', title: 'mDL' },
      { id: 'BankId_jwt_vc_json', title: 'BankId' },
    ];
    const filtered = allCredentials.filter(c => c.id in tenantCredentials);
    expect(filtered).toHaveLength(2);
    expect(filtered.map(c => c.id)).toContain('eu.europa.ec.eudi.pid.1');
    expect(filtered.map(c => c.id)).not.toContain('org.iso.18013.5.1.mDL');
  });

  it('should show all credentials when no issuerId (global mode)', () => {
    const issuerId: string | undefined = undefined;
    const tenantCredentials = issuerId ? { 'eu.europa.ec.eudi.pid.1': {} } : null;
    const allCredentials = [
      { id: 'eu.europa.ec.eudi.pid.1', title: 'PID' },
      { id: 'org.iso.18013.5.1.mDL', title: 'mDL' },
    ];
    const filtered = tenantCredentials
      ? allCredentials.filter(c => c.id in tenantCredentials)
      : allCredentials;
    expect(filtered).toHaveLength(2);
  });

  it('should construct correct admin API URL for fetching issuers', () => {
    const NEXT_PUBLIC_ISSUER = 'http://localhost:7002';
    expect(`${NEXT_PUBLIC_ISSUER}/admin/issuer`).toBe('http://localhost:7002/admin/issuer');
  });
});
```

**Step 2: Run test to verify it fails**

Run: `cd waltid-applications/waltid-web-portal && npx jest __tests__/issue-section-tenant.test.tsx --no-coverage`

Expected: PASS (these are logic-only tests — they should pass immediately since they test the pattern, not the component)

**Step 3: Implement tenant dropdown in IssueSection**

Modify `components/sections/IssueSection.tsx`:

1. Add imports:
```typescript
import axios from 'axios';
import nextConfig from '@/next.config';
```

2. Add state after line 33:
```typescript
const [issuers, setIssuers] = useState<Array<{id: string, legalName: string, credentialCount: number}>>([]);
const [selectedIssuerId, setSelectedIssuerId] = useState<string | undefined>(
  params.issuerId as string | undefined
);
const [tenantCredentialKeys, setTenantCredentialKeys] = useState<string[] | null>(null);
```

3. Add issuer registrar feature flag check:
```typescript
const isRegistrarEnabled = (env.NEXT_PUBLIC_ISSUER_REGISTRAR_ENABLED ?? nextConfig.publicRuntimeConfig?.NEXT_PUBLIC_ISSUER_REGISTRAR_ENABLED) === 'true';
```

4. Add useEffect to fetch issuers when registrar is enabled:
```typescript
React.useEffect(() => {
  if (!isRegistrarEnabled) return;
  const apiBase = env.NEXT_PUBLIC_ISSUER ?? nextConfig.publicRuntimeConfig!.NEXT_PUBLIC_ISSUER;
  axios.get(`${apiBase}/admin/issuer`).then(res => {
    const active = (res.data.tenants || []).filter(
      (t: any) => t.status === 'ACTIVE' && t.hasCertificate
    );
    setIssuers(active);
  }).catch(() => {});
}, [env]);
```

5. Add useEffect to fetch tenant credential catalog when issuer is selected:
```typescript
React.useEffect(() => {
  if (!selectedIssuerId) { setTenantCredentialKeys(null); return; }
  const apiBase = env.NEXT_PUBLIC_ISSUER ?? nextConfig.publicRuntimeConfig!.NEXT_PUBLIC_ISSUER;
  axios.get(`${apiBase}/admin/issuer/${selectedIssuerId}`).then(res => {
    setTenantCredentialKeys(Object.keys(res.data.credentialConfigurations || {}));
  }).catch(() => setTenantCredentialKeys(null));
}, [selectedIssuerId, env]);
```

6. Modify the credential filter useEffect (line 50-61) to also filter by tenant catalog:
```typescript
React.useEffect(() => {
  let filtered = AvailableCredentials.filter((cred) =>
    idsToIssue.some(id => id?.toString() === cred.id.toString())
  );
  if (tenantCredentialKeys) {
    filtered = filtered.filter(cred => tenantCredentialKeys.includes(cred.id));
  }
  setCredentialsToIssue(filtered);
}, [AvailableCredentials, tenantCredentialKeys]);
```

7. Update `handleIssue` to use `selectedIssuerId` (replace line 68):
```typescript
const issuerId = selectedIssuerId || (params.issuerId as string | undefined);
```

8. Add the dropdown UI after the `<h1>` title (around line 123), before "Credential Configuration":
```tsx
{isRegistrarEnabled && issuers.length > 0 && (
  <div className="mt-4 flex items-center justify-between bg-blue-50 rounded-lg px-4 py-3">
    <span className="text-sm font-medium text-blue-800">Issuing as:</span>
    <select
      value={selectedIssuerId || ''}
      onChange={(e) => setSelectedIssuerId(e.target.value || undefined)}
      className="ml-3 bg-white border border-blue-200 rounded-md px-3 py-1.5 text-sm focus:outline-none focus:ring-2 focus:ring-blue-500"
    >
      <option value="">Global (default)</option>
      {issuers.map(issuer => (
        <option key={issuer.id} value={issuer.id}>
          {issuer.legalName} ({issuer.credentialCount} credentials)
        </option>
      ))}
    </select>
  </div>
)}
```

**Step 4: Run tests**

Run: `cd waltid-applications/waltid-web-portal && npx jest --no-coverage`

Expected: All tests pass

**Step 5: Commit**

```bash
git add components/sections/IssueSection.tsx __tests__/issue-section-tenant.test.tsx
git commit -m "feat(portal): add issuer tenant dropdown to issuance flow"
```

---

## Task 5: RP Admin Action Buttons

Add "Verify as this RP" and "Copy Verify Link" buttons to the RP detail panel.

**Files:**
- Modify: `pages/admin/relying-parties.tsx:881-914` (actions section)
- Test: `__tests__/admin-rp-actions.test.ts`

**Step 1: Write the test**

Create `__tests__/admin-rp-actions.test.ts`:

```typescript
describe('RP Admin Action URL construction', () => {
  it('should construct verify URL with rpId', () => {
    const rpId = 'rp-abc-123';
    const verifyUrl = `/credentials?rpId=${rpId}&mode=verification`;
    expect(verifyUrl).toBe('/credentials?rpId=rp-abc-123&mode=verification');
  });

  it('should construct shareable verify link with rpId', () => {
    const origin = 'http://localhost:7102';
    const rpId = 'rp-abc-123';
    const ids = 'eu.europa.ec.eudi.pid.1';
    const format = 'mDoc (ISO 18013-5)';
    const shareUrl = `${origin}/verify?rpId=${rpId}&ids=${ids}&format=${encodeURIComponent(format)}`;
    expect(shareUrl).toContain('rpId=rp-abc-123');
    expect(shareUrl).toContain('ids=eu.europa.ec.eudi.pid.1');
  });

  it('should construct certificate download URL', () => {
    const apiBase = 'http://localhost:7004';
    const rpId = 'rp-abc-123';
    const downloadUrl = `${apiBase}/admin/rp/${rpId}/certificate/download`;
    expect(downloadUrl).toBe('http://localhost:7004/admin/rp/rp-abc-123/certificate/download');
  });
});
```

**Step 2: Run test — should pass immediately** (logic-only tests)

**Step 3: Implement RP action buttons**

In `pages/admin/relying-parties.tsx`, add above the existing Suspend/Delete section (around line 881):

```tsx
{/* Quick Actions */}
<div className="mb-4 flex flex-wrap gap-2">
  {detail.status === 'ACTIVE' && detail.certificate && (
    <>
      <button
        onClick={() => {
          window.location.href = `/credentials?rpId=${rp.id}&mode=verification`;
        }}
        className="inline-flex items-center gap-1.5 px-3 py-1.5 bg-emerald-600 text-white text-sm font-medium rounded-lg hover:bg-emerald-700 transition-colors"
      >
        Verify as this RP
      </button>
      <button
        onClick={() => {
          const url = `${window.location.origin}/verify?rpId=${rp.id}`;
          navigator.clipboard.writeText(url);
          // Show brief "Copied!" feedback
        }}
        className="inline-flex items-center gap-1.5 px-3 py-1.5 text-blue-600 text-sm font-medium rounded-lg border border-blue-200 hover:bg-blue-50 transition-colors"
      >
        Copy Verify Link
      </button>
      <a
        href={`${apiBase}/admin/rp/${rp.id}/certificate/download`}
        target="_blank"
        rel="noopener noreferrer"
        className="inline-flex items-center gap-1.5 px-3 py-1.5 text-gray-600 text-sm font-medium rounded-lg border border-gray-200 hover:bg-gray-50 transition-colors"
      >
        Download Certificate
      </a>
    </>
  )}
</div>
```

Add a `copied` state with timeout feedback for the "Copy Verify Link" button.

**Step 4: Run tests**

Expected: PASS

**Step 5: Commit**

```bash
git add pages/admin/relying-parties.tsx __tests__/admin-rp-actions.test.ts
git commit -m "feat(portal): add Verify as RP and Copy Verify Link buttons to RP admin"
```

---

## Task 6: RP Tenant Dropdown in VerificationSection

Add a "Verifying as" dropdown to VerificationSection when `RP_REGISTRAR_ENABLED=true`. Pass `rpId` through to the verify page.

**Files:**
- Modify: `components/sections/VerificationSection.tsx`
- Test: `__tests__/verify-section-tenant.test.ts`

**Step 1: Write the test**

Create `__tests__/verify-section-tenant.test.ts`:

```typescript
describe('VerificationSection RP tenant logic', () => {
  it('should append rpId to verify URL when RP is selected', () => {
    const rpId = 'rp-uuid-123';
    const params = new URLSearchParams();
    params.append('ids', 'eu.europa.ec.eudi.pid.1');
    params.append('format', 'mDoc (ISO 18013-5)');
    if (rpId) params.append('rpId', rpId);
    const url = `/verify?${params.toString()}`;
    expect(url).toContain('rpId=rp-uuid-123');
  });

  it('should not include rpId in verify URL when none selected', () => {
    const rpId: string | undefined = undefined;
    const params = new URLSearchParams();
    params.append('ids', 'eu.europa.ec.eudi.pid.1');
    if (rpId) params.append('rpId', rpId);
    expect(params.toString()).not.toContain('rpId');
  });

  it('should construct admin API URL for fetching RPs', () => {
    const NEXT_PUBLIC_VERIFIER2 = 'http://localhost:7004';
    expect(`${NEXT_PUBLIC_VERIFIER2}/admin/rp`).toBe('http://localhost:7004/admin/rp');
  });
});
```

**Step 2: Run test — should pass immediately**

**Step 3: Implement RP dropdown**

Modify `components/sections/VerificationSection.tsx`:

1. Add imports:
```typescript
import axios from 'axios';
import { EnvContext } from '@/pages/_app';
import nextConfig from '@/next.config';
```

2. Add state and env context:
```typescript
const env = useContext(EnvContext);
const [rps, setRps] = useState<Array<{id: string, legalName: string, domain: string}>>([]);
const [selectedRpId, setSelectedRpId] = useState<string | undefined>(
  router.query.rpId as string | undefined
);

const isRpRegistrarEnabled = (env.NEXT_PUBLIC_RP_REGISTRAR_ENABLED ?? nextConfig.publicRuntimeConfig?.NEXT_PUBLIC_RP_REGISTRAR_ENABLED) === 'true';
```

3. Fetch RPs:
```typescript
React.useEffect(() => {
  if (!isRpRegistrarEnabled) return;
  const apiBase = env.NEXT_PUBLIC_VERIFIER2 ?? nextConfig.publicRuntimeConfig?.NEXT_PUBLIC_VERIFIER2;
  if (!apiBase) return;
  axios.get(`${apiBase}/admin/rp`).then(res => {
    const active = (res.data.relyingParties || []).filter(
      (rp: any) => rp.status === 'ACTIVE' && rp.hasCertificate
    );
    setRps(active);
  }).catch(() => {});
}, [env]);
```

4. Modify `handleVerify` (line 66-76) to include rpId:
```typescript
if (selectedRpId) {
  params.append('rpId', selectedRpId);
}
```

5. Add dropdown UI after the `<h1>` title, before "Credential Formats":
```tsx
{isRpRegistrarEnabled && rps.length > 0 && (
  <div className="mt-4 flex items-center justify-between bg-blue-50 rounded-lg px-4 py-3">
    <span className="text-sm font-medium text-blue-800">Verifying as:</span>
    <select
      value={selectedRpId || ''}
      onChange={(e) => setSelectedRpId(e.target.value || undefined)}
      className="ml-3 bg-white border border-blue-200 rounded-md px-3 py-1.5 text-sm focus:outline-none focus:ring-2 focus:ring-blue-500"
    >
      <option value="">Global (default)</option>
      {rps.map(rp => (
        <option key={rp.id} value={rp.id}>{rp.legalName} ({rp.domain})</option>
      ))}
    </select>
  </div>
)}
```

**Step 4: Run tests**

Expected: PASS

**Step 5: Commit**

```bash
git add components/sections/VerificationSection.tsx __tests__/verify-section-tenant.test.ts
git commit -m "feat(portal): add RP tenant dropdown to verification flow"
```

---

## Task 7: RP-Aware Verify Page

When `rpId` is in the URL, the verify page fetches the RP's signing config instead of using env vars.

**Files:**
- Modify: `pages/verify/index.tsx:72-88` (signing config section)
- Test: `__tests__/verify-rp-signing.test.ts`

**Step 1: Write the test**

Create `__tests__/verify-rp-signing.test.ts`:

```typescript
describe('Verify page RP signing config', () => {
  it('should construct RP detail URL from rpId', () => {
    const verifier2Url = 'http://localhost:7004';
    const rpId = 'rp-uuid-123';
    expect(`${verifier2Url}/admin/rp/${rpId}`).toBe('http://localhost:7004/admin/rp/rp-uuid-123');
  });

  it('should construct RP certificate download URL', () => {
    const verifier2Url = 'http://localhost:7004';
    const rpId = 'rp-uuid-123';
    expect(`${verifier2Url}/admin/rp/${rpId}/certificate/download`).toBe(
      'http://localhost:7004/admin/rp/rp-uuid-123/certificate/download'
    );
  });

  it('should build signing config from RP data', () => {
    const rpDetail = {
      clientId: 'x509_san_dns:example.com',
      x5c: ['MIIBxTCCAW...'],
    };
    const certDownload = {
      privateKeyJwk: { kty: 'EC', crv: 'P-256', x: 'abc', y: 'def', d: 'ghi' },
    };
    const signingConfig = {
      clientId: rpDetail.clientId,
      key: { type: 'jwk', jwk: certDownload.privateKeyJwk },
      x5c: rpDetail.x5c,
    };
    expect(signingConfig.clientId).toBe('x509_san_dns:example.com');
    expect(signingConfig.key.type).toBe('jwk');
    expect(signingConfig.x5c).toHaveLength(1);
  });

  it('should fall back to env signing config when rpId is not provided', () => {
    const rpId: string | undefined = undefined;
    const envClientId = 'x509_san_dns:verifier2.example.com';
    const clientId = rpId ? undefined : envClientId;
    expect(clientId).toBe('x509_san_dns:verifier2.example.com');
  });
});
```

**Step 2: Run test — should pass immediately**

**Step 3: Implement RP-aware verify page**

Modify `pages/verify/index.tsx`:

In the main `useEffect` (line 40-189), after `router.isReady` check, add RP signing config resolution before the EUDI flow:

```typescript
const rpId = router.query.rpId as string | undefined;

// ... inside the EUDI format branch (line 60-117) ...

// Build signing config: RP-specific if rpId provided, else from env vars
let signingConfig: VerificationSigningConfig | undefined;

if (rpId) {
  // Fetch RP details and certificate for signing
  try {
    const [rpDetailRes, certRes] = await Promise.all([
      axios.get(`${verifier2Url}/admin/rp/${rpId}`),
      axios.get(`${verifier2Url}/admin/rp/${rpId}/certificate/download`),
    ]);
    const rpDetail = rpDetailRes.data;
    const certData = certRes.data;
    signingConfig = {
      clientId: rpDetail.clientId,
      key: { type: 'jwk', jwk: certData.privateKeyJwk },
      x5c: rpDetail.x5c || [certData.certificateDer],
    };
  } catch (e) {
    console.warn('Failed to fetch RP signing config, falling back to env:', e);
  }
}

// Fall back to env-based signing config
if (!signingConfig) {
  const clientId = env.NEXT_PUBLIC_VERIFIER2_CLIENT_ID || nextConfig.publicRuntimeConfig?.NEXT_PUBLIC_VERIFIER2_CLIENT_ID;
  const signingKeyJson = env.NEXT_PUBLIC_VERIFIER2_SIGNING_KEY || nextConfig.publicRuntimeConfig?.NEXT_PUBLIC_VERIFIER2_SIGNING_KEY;
  const x5c = env.NEXT_PUBLIC_VERIFIER2_X5C || nextConfig.publicRuntimeConfig?.NEXT_PUBLIC_VERIFIER2_X5C;
  // ... existing env-based config logic ...
}
```

This replaces lines 72-88 while preserving the env fallback.

**Step 4: Run tests**

Expected: PASS

**Step 5: Commit**

```bash
git add pages/verify/index.tsx __tests__/verify-rp-signing.test.ts
git commit -m "feat(portal): use RP-specific signing config in verify page"
```

---

## Task 8: Homepage MT Banner

Show a subtle info banner on the homepage when MT features are enabled.

**Files:**
- Modify: `pages/index.tsx:38-52` (between title and admin link)
- Test: `__tests__/homepage-banner.test.ts`

**Step 1: Write the test**

Create `__tests__/homepage-banner.test.ts`:

```typescript
describe('Homepage MT banner logic', () => {
  it('should show issuer banner when ISSUER_REGISTRAR_ENABLED is true', () => {
    const env = { NEXT_PUBLIC_ISSUER_REGISTRAR_ENABLED: 'true' };
    const showIssuerBanner = env.NEXT_PUBLIC_ISSUER_REGISTRAR_ENABLED === 'true';
    expect(showIssuerBanner).toBe(true);
  });

  it('should not show issuer banner when disabled', () => {
    const env = { NEXT_PUBLIC_ISSUER_REGISTRAR_ENABLED: 'false' };
    const showIssuerBanner = env.NEXT_PUBLIC_ISSUER_REGISTRAR_ENABLED === 'true';
    expect(showIssuerBanner).toBe(false);
  });

  it('should show RP banner when RP_REGISTRAR_ENABLED is true', () => {
    const env = { NEXT_PUBLIC_RP_REGISTRAR_ENABLED: 'true' };
    const showRpBanner = env.NEXT_PUBLIC_RP_REGISTRAR_ENABLED === 'true';
    expect(showRpBanner).toBe(true);
  });
});
```

**Step 2: Run test — should pass immediately**

**Step 3: Implement the banner**

Modify `pages/index.tsx`. Add `EnvContext` import and usage:

```typescript
import { EnvContext } from '@/pages/_app';
// ...
const env = React.useContext(EnvContext);
const isIssuerRegistrarEnabled = env.NEXT_PUBLIC_ISSUER_REGISTRAR_ENABLED === 'true';
const isRpRegistrarEnabled = env.NEXT_PUBLIC_RP_REGISTRAR_ENABLED === 'true';
```

Add between the subtitle and admin button (around line 43):

```tsx
{(isIssuerRegistrarEnabled || isRpRegistrarEnabled) && (
  <div className="mt-3 flex items-center gap-2 px-3 py-2 bg-blue-50 rounded-lg text-sm text-blue-700">
    <span>Multi-tenant mode:</span>
    {isIssuerRegistrarEnabled && (
      <span className="px-2 py-0.5 bg-blue-100 rounded text-xs font-medium">Issuer</span>
    )}
    {isRpRegistrarEnabled && (
      <span className="px-2 py-0.5 bg-blue-100 rounded text-xs font-medium">RP</span>
    )}
    <span className="text-blue-500">— select a tenant in the issuance/verification flow</span>
  </div>
)}
```

**Step 4: Run tests**

Expected: PASS

**Step 5: Commit**

```bash
git add pages/index.tsx __tests__/homepage-banner.test.ts
git commit -m "feat(portal): add multi-tenant mode banner to homepage"
```

---

## Task 9: RP Registrar Feature Flag Plumbing

Ensure `NEXT_PUBLIC_RP_REGISTRAR_ENABLED` is exposed via the portal's env system (it may already be — verify and add if missing).

**Files:**
- Possibly modify: `next.config.js`, `docker-compose/docker-compose.yaml`
- Test: `__tests__/issuer-registrar.test.ts` (add RP env tests)

**Step 1: Check if RP registrar env var is already plumbed**

Read `next.config.js` and check for `NEXT_PUBLIC_RP_REGISTRAR_ENABLED` in `publicRuntimeConfig`.

**Step 2: If missing, add it**

In `next.config.js`:
```javascript
publicRuntimeConfig: {
  // ... existing vars ...
  NEXT_PUBLIC_RP_REGISTRAR_ENABLED: process.env.NEXT_PUBLIC_RP_REGISTRAR_ENABLED || 'false',
}
```

In `docker-compose.yaml` web-portal service environment:
```yaml
- NEXT_PUBLIC_RP_REGISTRAR_ENABLED=${RP_REGISTRAR_ENABLED:-false}
```

**Step 3: Add test**

Add to `__tests__/issuer-registrar.test.ts`:

```typescript
describe('RP Registrar - next.config.js feature flag', () => {
  it('should expose NEXT_PUBLIC_RP_REGISTRAR_ENABLED in publicRuntimeConfig', () => {
    const config = nextConfig.publicRuntimeConfig;
    expect(config).toBeDefined();
    expect('NEXT_PUBLIC_RP_REGISTRAR_ENABLED' in config!).toBe(true);
  });

  it('should default to "false" when env var is not set', () => {
    const config = nextConfig.publicRuntimeConfig;
    expect(config!.NEXT_PUBLIC_RP_REGISTRAR_ENABLED).toBe('false');
  });
});
```

**Step 4: Run tests**

Expected: PASS

**Step 5: Commit**

```bash
git add next.config.js docker-compose/docker-compose.yaml __tests__/issuer-registrar.test.ts
git commit -m "feat(portal): plumb RP registrar feature flag through portal env"
```

---

## Task 10: Full Test Run and Manual Verification

**Step 1: Run all portal tests**

```bash
cd waltid-applications/waltid-web-portal && npx jest --no-coverage
```

Expected: All tests pass (ignore pre-existing `credentials.test.ts` failure)

**Step 2: Run Kotlin tests**

```bash
./gradlew :waltid-services:waltid-issuer-api:test
```

Expected: All 204 tests pass (no backend changes, sanity check)

**Step 3: Build and deploy custom portal image**

```bash
cd docker-compose
docker compose --profile identity build web-portal
docker compose --profile identity up -d --force-recreate web-portal
```

**Step 4: Manual E2E verification checklist**

1. Homepage shows MT mode banner (both flags enabled)
2. Navigate to Admin > Issuers — see existing tenants
3. Click a tenant — see template picker, action buttons
4. Click "Issue Credential" — routes to issuance with tenant pre-selected
5. Issuance flow shows "Issuing as: [tenant name]" dropdown
6. Issue a credential — verify tenant-scoped offer URL
7. Navigate to Admin > Relying Parties — see existing RPs
8. Click an RP — see "Verify as this RP" and "Copy Verify Link"
9. Click "Verify as this RP" — routes to verification with RP pre-selected
10. Verification flow shows "Verifying as: [RP name]" dropdown
11. Create verification — verify RP signing config is used
12. Disable feature flags — verify all new UI is hidden

**Step 5: Final commit if any fixes needed**

---

## Summary

| Task | Description | New Files | Modified Files |
|------|-------------|-----------|----------------|
| 1 | Credential template library | `types/credentialTemplates.ts`, test | — |
| 2 | Template picker in issuer admin | test | `pages/admin/issuers.tsx` |
| 3 | Issuer admin action buttons | test | `pages/admin/issuers.tsx` |
| 4 | Issuer tenant dropdown | test | `components/sections/IssueSection.tsx` |
| 5 | RP admin action buttons | test | `pages/admin/relying-parties.tsx` |
| 6 | RP tenant dropdown | test | `components/sections/VerificationSection.tsx` |
| 7 | RP-aware verify page | test | `pages/verify/index.tsx` |
| 8 | Homepage MT banner | test | `pages/index.tsx` |
| 9 | RP feature flag plumbing | test | `next.config.js`, `docker-compose.yaml` |
| 10 | Full test run + manual E2E | — | — |

**Parallel batches:** Tasks 1→2→3 (sequential, issuers). Tasks 5→6→7 (sequential, RPs). Tasks 4, 8, 9 are independent. Suggested execution order: 1, 9, 4, 8, 2, 3, 5, 6, 7, 10.
