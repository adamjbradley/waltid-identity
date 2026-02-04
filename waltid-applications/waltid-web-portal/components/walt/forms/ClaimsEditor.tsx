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
