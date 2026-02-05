# Claims Editor UI Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Add a collapsible claims editor UI to the portal verification flow, allowing users to customize which claims are requested from the EUDI wallet.

**Architecture:** Create a new React component `ClaimsEditor` that displays editable claim paths per credential. The component converts between dot-notation display (e.g., `eu.europa.ec.eudi.pid.1.family_name`) and array paths (e.g., `["eu.europa.ec.eudi.pid.1", "family_name"]`). State flows from VerificationSection → ClaimsEditor → buildDcqlQuery.

**Tech Stack:** React 18, TypeScript, Tailwind CSS, Next.js 14

---

## Task 1: Create ClaimsEditor Component Shell

**Files:**
- Create: `waltid-applications/waltid-web-portal/components/walt/forms/ClaimsEditor.tsx`

**Step 1: Create the component file with TypeScript types**

```typescript
import React, { useState } from 'react';
import { ClaimDefinition } from '@/types/credentials';
import { ChevronDownIcon, ChevronUpIcon, XMarkIcon, PlusIcon } from '@heroicons/react/24/outline';

interface ClaimsEditorProps {
  credentialTitle: string;
  credentialId: string;
  claims: ClaimDefinition[];
  onChange: (claims: ClaimDefinition[]) => void;
}

// Convert array path to dot notation for display
function pathToString(path: string[]): string {
  return path.join('.');
}

// Convert dot notation string back to array path
function stringToPath(str: string): string[] {
  return str.split('.').filter(s => s.length > 0);
}

export default function ClaimsEditor({
  credentialTitle,
  credentialId,
  claims,
  onChange,
}: ClaimsEditorProps) {
  const [isExpanded, setIsExpanded] = useState(false);

  const handleClaimChange = (index: number, value: string) => {
    const newClaims = [...claims];
    newClaims[index] = { path: stringToPath(value) };
    onChange(newClaims);
  };

  const handleRemoveClaim = (index: number) => {
    const newClaims = claims.filter((_, i) => i !== index);
    onChange(newClaims);
  };

  const handleAddClaim = () => {
    onChange([...claims, { path: [] }]);
  };

  return (
    <div className="border border-gray-200 rounded-lg mt-4">
      {/* Header - always visible */}
      <button
        type="button"
        onClick={() => setIsExpanded(!isExpanded)}
        className="w-full flex items-center justify-between p-3 text-left hover:bg-gray-50 rounded-lg"
      >
        <span className="text-sm font-medium text-gray-700">
          Requested Claims ({claims.length})
        </span>
        {isExpanded ? (
          <ChevronUpIcon className="h-5 w-5 text-gray-500" />
        ) : (
          <ChevronDownIcon className="h-5 w-5 text-gray-500" />
        )}
      </button>

      {/* Expandable content */}
      {isExpanded && (
        <div className="px-3 pb-3 space-y-2">
          {claims.map((claim, index) => (
            <div key={index} className="flex items-center gap-2">
              <input
                type="text"
                value={pathToString(claim.path)}
                onChange={(e) => handleClaimChange(index, e.target.value)}
                className="flex-1 px-3 py-2 text-sm border border-gray-300 rounded-md focus:ring-primary-500 focus:border-primary-500"
                placeholder="namespace.claim_name"
              />
              <button
                type="button"
                onClick={() => handleRemoveClaim(index)}
                className="p-2 text-gray-400 hover:text-red-500"
                title="Remove claim"
              >
                <XMarkIcon className="h-5 w-5" />
              </button>
            </div>
          ))}
          <button
            type="button"
            onClick={handleAddClaim}
            className="flex items-center gap-1 text-sm text-primary-600 hover:text-primary-700 mt-2"
          >
            <PlusIcon className="h-4 w-4" />
            Add Claim
          </button>
        </div>
      )}
    </div>
  );
}
```

**Step 2: Verify the component compiles**

Run: `cd waltid-applications/waltid-web-portal && npx tsc --noEmit`
Expected: No TypeScript errors

**Step 3: Commit**

```bash
git add waltid-applications/waltid-web-portal/components/walt/forms/ClaimsEditor.tsx
git commit -m "feat: add ClaimsEditor component shell"
```

---

## Task 2: Add Claims State to AvailableCredential

**Files:**
- Modify: `waltid-applications/waltid-web-portal/types/credentials.tsx:1-15`

**Step 1: Update AvailableCredential type to track editable claims**

The `defaultClaims` already exists. We need to ensure `AvailableCredential` can carry editable claims state. Check current type:

```typescript
export type AvailableCredential = {
  id: string;
  title: string;
  selectedFormat?: String;
  selectedDID?: String;
  offer: any;
  defaultClaims?: ClaimDefinition[];
  // Add this for tracking user-edited claims
  editedClaims?: ClaimDefinition[];
};
```

**Step 2: Verify TypeScript compiles**

Run: `cd waltid-applications/waltid-web-portal && npx tsc --noEmit`
Expected: No errors

**Step 3: Commit**

```bash
git add waltid-applications/waltid-web-portal/types/credentials.tsx
git commit -m "feat: add editedClaims to AvailableCredential type"
```

---

## Task 3: Update buildDcqlQuery to Use Edited Claims

**Files:**
- Modify: `waltid-applications/waltid-web-portal/types/credentials.tsx:91-120`

**Step 1: Update buildDcqlQuery to prefer editedClaims over defaultClaims**

```typescript
export function buildDcqlQuery(credentials: AvailableCredential[], format: string): DcqlQuery {
  return {
    credentials: credentials.map((credential) => {
      // Prefer user-edited claims, then default claims, then fallback
      const claims = credential.editedClaims?.map(c => ({ path: c.path })) ||
        credential.defaultClaims?.map(c => ({ path: c.path })) ||
        getDefaultClaimsForCredential(credential.id, format);

      if (format === 'mso_mdoc') {
        return {
          id: credential.id,
          format: 'mso_mdoc',
          meta: {
            doctype_value: credential.offer.doctype || credential.id,
          },
          claims,
        };
      } else {
        // dc+sd-jwt
        return {
          id: credential.id,
          format: 'dc+sd-jwt',
          meta: {
            vct_values: [credential.offer.vct || 'urn:eudi:pid:1'],
          },
          claims,
        };
      }
    }),
  };
}
```

**Step 2: Verify TypeScript compiles**

Run: `cd waltid-applications/waltid-web-portal && npx tsc --noEmit`
Expected: No errors

**Step 3: Commit**

```bash
git add waltid-applications/waltid-web-portal/types/credentials.tsx
git commit -m "feat: buildDcqlQuery prefers editedClaims over defaults"
```

---

## Task 4: Integrate ClaimsEditor into RowCredential

**Files:**
- Modify: `waltid-applications/waltid-web-portal/components/walt/credential/RowCredential.tsx`

**Step 1: Import ClaimsEditor and add claims state management**

Add to imports at top:
```typescript
import ClaimsEditor from '@/components/walt/forms/ClaimsEditor';
import { ClaimDefinition, isEudiFormat, mapFormat } from '@/types/credentials';
```

**Step 2: Add claims state and handler inside RowCredential component**

After the existing useState declarations (around line 24-25), add:
```typescript
// Initialize claims from defaultClaims or empty array
const [claims, setClaims] = React.useState<ClaimDefinition[]>(
  credentialToEdit.defaultClaims || []
);
```

**Step 3: Update useEffect to include editedClaims when propagating state**

Modify the useEffect (around line 27-47) to include editedClaims:
```typescript
React.useEffect(() => {
  setCredentialsToIssue(
    credentialsToIssue.map((credential) => {
      if (credential.offer.id == credentialToEdit.offer.id) {
        let updatedCredential = { ...credential };

        if (credentialSubject !== credential.offer.credentialSubject) {
          updatedCredential.offer.credentialSubject = credentialSubject;
        }
        updatedCredential.selectedFormat = selectedFormat;
        updatedCredential.selectedDID = selectedDID;
        // Add edited claims
        updatedCredential.editedClaims = claims;

        return updatedCredential;
      } else {
        let updatedCredential = { ...credential };
        updatedCredential.selectedFormat = selectedFormat;
        return updatedCredential;
      }
    })
  );
}, [credentialSubject, selectedFormat, selectedDID, claims]);
```

**Step 4: Add ClaimsEditor component to JSX**

After the EUDI badge span (around line 67), before the PencilSquareIcon, add conditionally:
```tsx
{/* Render ClaimsEditor only for EUDI formats */}
{(() => {
  try {
    const format = mapFormat(selectedFormat);
    if (isEudiFormat(format)) {
      return (
        <ClaimsEditor
          credentialTitle={credentialToEdit.title}
          credentialId={credentialToEdit.id}
          claims={claims}
          onChange={setClaims}
        />
      );
    }
    return null;
  } catch {
    return null;
  }
})()}
```

**Step 5: Verify TypeScript compiles and build succeeds**

Run: `cd waltid-applications/waltid-web-portal && npm run build`
Expected: Build succeeds with no errors

**Step 6: Commit**

```bash
git add waltid-applications/waltid-web-portal/components/walt/credential/RowCredential.tsx
git commit -m "feat: integrate ClaimsEditor into RowCredential for EUDI formats"
```

---

## Task 5: Visual Testing and Polish

**Files:**
- Modify: `waltid-applications/waltid-web-portal/components/walt/forms/ClaimsEditor.tsx` (if needed)

**Step 1: Rebuild Docker container and verify UI**

Run:
```bash
cd docker-compose
docker compose --profile identity up -d --build web-portal
```

**Step 2: Manual UI verification**

1. Open http://localhost:7102
2. Click "Verify Credentials"
3. Select "EU Personal ID (mDoc)"
4. Select "mDoc (ISO 18013-5)" format
5. Verify: ClaimsEditor appears with 3 default claims
6. Click to expand claims section
7. Verify: Can edit claim paths
8. Verify: Can remove claims with × button
9. Verify: Can add new claim with + Add Claim button
10. Click Verify and confirm QR code generates

**Step 3: Commit any polish changes**

```bash
git add -A
git commit -m "style: polish ClaimsEditor UI" # if changes needed
```

---

## Task 6: End-to-End Wallet Test

**Step 1: Test mDoc PID verification with custom claims**

1. In portal, select EU Personal ID (mDoc)
2. Edit claims to only request `family_name`
3. Generate QR code
4. Scan with EUDI wallet
5. Verify: Wallet shows only family_name in request

**Step 2: Test SD-JWT PID verification**

1. In portal, select EU Personal ID (SD-JWT)
2. Keep default claims
3. Generate QR code
4. Scan with EUDI wallet
5. Verify: Wallet presents credential successfully

**Step 3: Document test results**

If tests pass, update the plan file status.

---

## Summary

| Task | Description | Estimated Steps |
|------|-------------|-----------------|
| 1 | Create ClaimsEditor component | 3 steps |
| 2 | Add editedClaims to type | 3 steps |
| 3 | Update buildDcqlQuery | 3 steps |
| 4 | Integrate into RowCredential | 6 steps |
| 5 | Visual testing | 3 steps |
| 6 | E2E wallet test | 3 steps |

**Total:** 6 tasks, ~21 steps
