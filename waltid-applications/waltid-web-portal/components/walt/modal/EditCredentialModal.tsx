import React from "react";
import Button from "@/components/walt/button/Button";
import BaseModal from "@/components/walt/modal/BaseModal";

type Props = {
  show: boolean;
  onClose: () => void;
  credentialSubject: any;
  setCredentialSubject: (credentialSubject: any) => void;
  credentialTitle?: string;
};

// Flatten nested credential data for form display
function flattenClaims(data: any, prefix = ''): { key: string; path: string[]; value: any; type: string }[] {
  const claims: { key: string; path: string[]; value: any; type: string }[] = [];

  for (const key in data) {
    const value = data[key];
    const currentPath = prefix ? [...prefix.split('.'), key] : [key];

    if (value !== null && typeof value === 'object' && !Array.isArray(value)) {
      // Recurse into nested objects (like namespaces)
      claims.push(...flattenClaims(value, currentPath.join('.')));
    } else {
      // Leaf value - add as editable claim
      let type = 'text';
      if (typeof value === 'boolean') type = 'checkbox';
      else if (key.includes('date') || key.includes('Date')) type = 'date';

      claims.push({
        key,
        path: currentPath,
        value,
        type,
      });
    }
  }

  return claims;
}

// Reconstruct nested object from flat claims
function unflattenClaims(claims: { path: string[]; value: any }[], originalStructure: any): any {
  const result = JSON.parse(JSON.stringify(originalStructure)); // Deep clone

  for (const claim of claims) {
    let current = result;
    for (let i = 0; i < claim.path.length - 1; i++) {
      if (!current[claim.path[i]]) {
        current[claim.path[i]] = {};
      }
      current = current[claim.path[i]];
    }
    current[claim.path[claim.path.length - 1]] = claim.value;
  }

  return result;
}

// Format claim key for display (snake_case to Title Case)
function formatClaimLabel(key: string): string {
  return key
    .replace(/_/g, ' ')
    .replace(/\b\w/g, (c) => c.toUpperCase());
}

export default function EditCredentialModal({
  show,
  onClose,
  credentialSubject,
  setCredentialSubject,
  credentialTitle,
}: Props) {
  const [claims, setClaims] = React.useState<{ key: string; path: string[]; value: any; type: string }[]>([]);

  React.useEffect(() => {
    if (credentialSubject) {
      setClaims(flattenClaims(credentialSubject));
    }
  }, [credentialSubject, show]);

  const handleClaimChange = (index: number, newValue: any) => {
    const updatedClaims = [...claims];
    updatedClaims[index] = { ...updatedClaims[index], value: newValue };
    setClaims(updatedClaims);
  };

  const handleSave = () => {
    const updated = unflattenClaims(claims, credentialSubject);
    setCredentialSubject(updated);
    onClose();
  };

  return (
    <BaseModal show={show} securedByWalt={false} onClose={onClose}>
      <div className="flex flex-col w-full max-h-[70vh]">
        <h2 className="text-xl font-semibold text-gray-900 mb-4">
          {credentialTitle ? `Edit ${credentialTitle}` : 'Edit Credential Data'}
        </h2>

        <div className="overflow-y-auto flex-1 pr-2">
          <div className="space-y-4">
            {claims.map((claim, index) => (
              <div key={claim.path.join('.')} className="flex flex-col">
                <label className="text-sm font-medium text-gray-700 mb-1">
                  {formatClaimLabel(claim.key)}
                </label>

                {claim.type === 'checkbox' ? (
                  <div className="flex items-center">
                    <input
                      type="checkbox"
                      checked={claim.value === true}
                      onChange={(e) => handleClaimChange(index, e.target.checked)}
                      className="h-4 w-4 text-primary-600 border-gray-300 rounded focus:ring-primary-500"
                    />
                    <span className="ml-2 text-sm text-gray-600">
                      {claim.value ? 'Yes' : 'No'}
                    </span>
                  </div>
                ) : claim.type === 'date' ? (
                  <input
                    type="date"
                    value={claim.value || ''}
                    onChange={(e) => handleClaimChange(index, e.target.value)}
                    className="w-full px-3 py-2 border border-gray-300 rounded-md shadow-sm focus:ring-primary-500 focus:border-primary-500"
                  />
                ) : Array.isArray(claim.value) ? (
                  <textarea
                    value={JSON.stringify(claim.value, null, 2)}
                    onChange={(e) => {
                      try {
                        handleClaimChange(index, JSON.parse(e.target.value));
                      } catch {
                        // Invalid JSON, ignore
                      }
                    }}
                    className="w-full px-3 py-2 border border-gray-300 rounded-md shadow-sm focus:ring-primary-500 focus:border-primary-500 font-mono text-sm"
                    rows={3}
                  />
                ) : (
                  <input
                    type="text"
                    value={claim.value || ''}
                    onChange={(e) => handleClaimChange(index, e.target.value)}
                    className="w-full px-3 py-2 border border-gray-300 rounded-md shadow-sm focus:ring-primary-500 focus:border-primary-500"
                  />
                )}
              </div>
            ))}
          </div>
        </div>

        <div className="flex flex-row justify-end gap-2 mt-6 pt-4 border-t">
          <Button onClick={onClose} style="link">
            Cancel
          </Button>
          <Button onClick={handleSave} style="button">
            Save
          </Button>
        </div>
      </div>
    </BaseModal>
  );
}
