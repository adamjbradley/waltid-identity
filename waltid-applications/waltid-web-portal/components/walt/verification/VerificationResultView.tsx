import {useState} from "react";
import {CheckCircleIcon, XCircleIcon, ExclamationTriangleIcon} from "@heroicons/react/24/outline";
import WaltIcon from "@/components/walt/logo/WaltIcon";

interface FlowParticipant {
  role: string;
  name: string;
  detail?: string;
}

const VCT_DISPLAY_NAMES: Record<string, string> = {
  'urn:eudi:pid:1': 'EU Personal ID',
  'eu.europa.ec.eudi.pid.1': 'EU Personal ID (mDoc)',
  'org.iso.18013.5.1.mDL': 'Mobile Driving Licence',
  'PaymentWalletAttestation': 'Payment Wallet Attestation',
};

const POLICY_DESCRIPTIONS: Record<string, { label: string; passText: string; failText: string }> = {
  'signature': {
    label: 'Signature Verification',
    passText: 'Credential signature is valid and verified against issuer public key',
    failText: 'Credential signature is invalid or could not be verified',
  },
  'expiration': {
    label: 'Expiration Check',
    passText: 'Credential has not expired',
    failText: 'Credential has expired — the validity period has ended',
  },
  'not-before': {
    label: 'Not-Before Check',
    passText: 'Credential is within its valid time period',
    failText: 'Credential is not yet valid — the not-before date has not been reached',
  },
  'revoked-status-list': {
    label: 'Revocation Check',
    passText: 'Credential has not been revoked by the issuer',
    failText: 'Credential has been revoked by the issuer',
  },
  'etsi-trusted-issuer': {
    label: 'ETSI Trust List Verification',
    passText: 'Issuer is registered in a trusted EU Trust Service List',
    failText: 'Issuer is NOT found in any trusted EU Trust Service List',
  },
  'allowed-issuer': {
    label: 'Allowed Issuer Check',
    passText: 'Issuer is in the list of allowed issuers',
    failText: 'Issuer is NOT in the list of allowed issuers',
  },
  'schema': {
    label: 'Schema Validation',
    passText: 'Credential conforms to the expected JSON schema',
    failText: 'Credential does not conform to the expected JSON schema',
  },
  'webhook': {
    label: 'Webhook Validation',
    passText: 'External webhook validated the credential',
    failText: 'External webhook rejected the credential',
  },
  'regex': {
    label: 'Regex Validation',
    passText: 'Credential fields match required patterns',
    failText: 'Credential fields do not match required patterns',
  },
};

function getPolicyInfo(policyName: string) {
  return POLICY_DESCRIPTIONS[policyName] ?? {
    label: policyName.charAt(0).toUpperCase() + policyName.slice(1).replace(/-/g, ' '),
    passText: 'Policy check passed',
    failText: 'Policy check failed',
  };
}

function parseApi2Session(session: any) {
  // Extract flow participants
  const flowParticipants: FlowParticipant[] = [];

  let issuerName = 'Unknown Issuer';
  let issuerDetail = '';
  if (session.presentedCredentials) {
    for (const [, credList] of Object.entries(session.presentedCredentials) as [string, any[]][]) {
      for (const cred of credList) {
        const data = cred.credentialData || {};
        const allClaims: Record<string, any> = {};
        for (const [k, v] of Object.entries(data)) {
          if (typeof v === 'object' && v !== null && !Array.isArray(v) && k !== 'cnf') {
            Object.assign(allClaims, v);
          } else {
            allClaims[k] = v;
          }
        }

        if (allClaims.issuing_authority) {
          issuerName = allClaims.issuing_authority;
          issuerDetail = data.iss || cred.docType || '';
        } else if (data.iss) {
          try {
            const issUrl = new URL(data.iss);
            issuerName = issUrl.host;
            issuerDetail = data.iss;
          } catch {
            issuerName = data.iss.length > 30 ? data.iss.substring(0, 30) + '...' : data.iss;
            issuerDetail = data.iss;
          }
        }

        if (issuerName === 'Unknown Issuer' && cred.signature?.x5cList?.length > 0) {
          try {
            const certB64 = cred.signature.x5cList[cred.signature.x5cList.length - 1];
            const certBinary = Buffer.from(certB64, 'base64').toString('binary');
            let readable = '';
            for (let ci = 0; ci < certBinary.length; ci++) {
              const code = certBinary.charCodeAt(ci);
              readable += (code >= 32 && code < 127) ? certBinary[ci] : '\x00';
            }
            const iacaMatch = readable.match(/([A-Z][A-Za-z0-9 .'()-]+?)\s+IACA/);
            if (iacaMatch) {
              issuerName = iacaMatch[1].trim();
            } else {
              const dsMatch = readable.match(/([A-Z][A-Za-z0-9 .'()-]+?)\s+Document Signer/);
              if (dsMatch) issuerName = dsMatch[1].trim();
            }
          } catch { /* keep Unknown Issuer */ }
        }

        const country = allClaims.issuing_country;
        if (country && issuerName !== 'Unknown Issuer') {
          issuerDetail = `${country} — ${issuerDetail || data.iss || ''}`;
        }
        if (cred.docType) issuerDetail = issuerDetail || cred.docType;
      }
    }
  }
  flowParticipants.push({ role: 'Issuer', name: issuerName, detail: issuerDetail });
  flowParticipants.push({ role: 'Holder', name: 'Digital Wallet', detail: 'Credential holder' });

  let verifierName = 'Unknown Verifier';
  let verifierDetail = '';
  const clientId = session.bootstrapAuthorizationRequest?.client_id || session.authorizationRequest?.client_id;
  if (clientId) {
    verifierDetail = clientId;
    if (clientId.startsWith('x509_san_dns:')) {
      verifierName = clientId.replace('x509_san_dns:', '');
    } else {
      try { verifierName = new URL(clientId).host; } catch { verifierName = clientId; }
    }
  }
  flowParticipants.push({ role: 'Verifier', name: verifierName, detail: verifierDetail });

  // Extract credentials
  const credentials: any[] = [];
  if (session.presentedCredentials) {
    for (const [, credList] of Object.entries(session.presentedCredentials) as [string, any[]][]) {
      for (const cred of credList) {
        const data = cred.credentialData || {};
        const displayCred: any = {
          vct: data.vct || cred.docType,
          type: data.type,
          credentialSubject: {} as Record<string, string>,
        };
        const skipKeys = new Set(['iss', 'iat', 'nbf', 'exp', 'cnf', 'vct', 'type', '_sd', '_sd_alg', 'docType']);
        for (const [k, v] of Object.entries(data)) {
          if (typeof v === 'object' && v !== null && !Array.isArray(v) && k !== 'cnf') {
            for (const [nk, nv] of Object.entries(v as Record<string, any>)) {
              if (typeof nv === 'string' || typeof nv === 'boolean') {
                displayCred.credentialSubject[nk] = String(nv);
              }
            }
          } else if (!skipKeys.has(k) && (typeof v === 'string' || typeof v === 'boolean')) {
            displayCred.credentialSubject[k] = String(v);
          }
        }
        credentials.push(displayCred);
      }
    }
  }

  // Build policy results
  const policyResults: Array<{ policyResults: Array<{ policy: string; is_success: boolean; error?: string }> }> = [];
  const vpPolicies: Array<{ policy: string; is_success: boolean }> = [];
  if (session.policyResults?.vp_policies) {
    for (const [, policiesMap] of Object.entries(session.policyResults.vp_policies) as [string, any][]) {
      for (const [policyName, result] of Object.entries(policiesMap) as [string, any][]) {
        vpPolicies.push({ policy: policyName, is_success: result.success });
      }
    }
  }
  policyResults.push({ policyResults: vpPolicies });

  const vcPolicies: Array<{ policy: string; is_success: boolean; error?: string }> = [];
  if (session.policyResults?.vc_policies) {
    for (const result of session.policyResults.vc_policies) {
      vcPolicies.push({
        policy: result.policy?.policy || result.policy?.id || 'unknown',
        is_success: result.success,
        error: result.error || result.message || result.details || undefined,
      });
    }
  }
  for (let i = 0; i < Math.max(credentials.length, 1); i++) {
    policyResults.push({ policyResults: vcPolicies });
  }

  return {
    sessionStatus: session.status || '',
    flowParticipants,
    credentials,
    policyResults,
  };
}

type VerificationResultViewProps = {
  sessionData: any;
};

export default function VerificationResultView({ sessionData }: VerificationResultViewProps) {
  const [index, setIndex] = useState(0);

  const { sessionStatus, flowParticipants, credentials, policyResults } = parseApi2Session(sessionData);

  const isFailure = sessionStatus !== '' && sessionStatus !== 'SUCCESSFUL';
  const failedPolicies = policyResults[index + 1]?.policyResults.filter(p => !p.is_success) ?? [];
  const passedPolicies = policyResults[index + 1]?.policyResults.filter(p => p.is_success) ?? [];
  const allPolicies = policyResults[index + 1]?.policyResults ?? [];

  return (
    <div className="text-left">
      {/* Status Header */}
      {sessionStatus && (
        <div className={`flex items-center justify-center gap-2 mb-4 py-2 px-4 rounded-full ${
          sessionStatus === 'SUCCESSFUL' ? 'bg-green-50 text-green-700' : 'bg-red-50 text-red-700'
        }`}>
          {sessionStatus === 'SUCCESSFUL' ? (
            <CheckCircleIcon className="h-5 w-5" />
          ) : (
            <XCircleIcon className="h-5 w-5" />
          )}
          <span className="text-sm font-semibold">
            {sessionStatus === 'SUCCESSFUL' ? 'Verification Successful' : 'Verification Failed'}
          </span>
        </div>
      )}

      {/* Failure Summary Banner */}
      {isFailure && failedPolicies.length > 0 && (
        <div className="mb-4 rounded-lg border border-red-200 bg-red-50 p-3 text-left">
          <div className="flex items-start gap-3">
            <ExclamationTriangleIcon className="h-5 w-5 text-red-500 flex-shrink-0 mt-0.5" />
            <div>
              <h3 className="text-sm font-semibold text-red-800">
                {failedPolicies.length} of {allPolicies.length} policy {allPolicies.length === 1 ? 'check' : 'checks'} failed
              </h3>
              <p className="text-xs text-red-600 mt-1">
                {failedPolicies.map(p => ` ${getPolicyInfo(p.policy).label}`).join(',')} failed.
              </p>
            </div>
          </div>
        </div>
      )}

      {/* Flow Visualization */}
      {flowParticipants.length > 0 && (
        <div className={`mb-4 rounded-lg border p-3 ${isFailure ? 'border-red-200 bg-red-50/50' : 'border-gray-200 bg-gray-50'}`}>
          <div className="text-xs font-medium text-gray-500 uppercase tracking-wide mb-2">Verification Flow</div>
          <div className="flex items-center justify-between gap-2">
            {flowParticipants.map((participant, i) => (
              <div key={participant.role} className="flex items-center gap-2 flex-1">
                <div className="flex flex-col items-center text-center flex-1 min-w-0">
                  <div className={`w-9 h-9 rounded-full flex items-center justify-center text-white text-xs font-bold shadow-md ${
                    isFailure && participant.role === 'Verifier' ? 'ring-2 ring-red-400 ring-offset-2' : ''
                  }`}
                    style={{ backgroundColor: participant.role === 'Issuer' ? '#2563eb' : participant.role === 'Holder' ? '#9333ea' : isFailure ? '#dc2626' : '#16a34a' }}>
                    {participant.role === 'Issuer' ? 'I' : participant.role === 'Holder' ? 'H' : 'V'}
                  </div>
                  <div className="mt-1 text-xs font-semibold text-gray-700">{participant.role}</div>
                  <div className="text-[10px] text-gray-500 truncate max-w-full" title={participant.detail || participant.name}>
                    {participant.name}
                  </div>
                </div>
                {i < flowParticipants.length - 1 && (
                  <svg className={`w-4 h-4 flex-shrink-0 -mt-4 ${isFailure && i === flowParticipants.length - 2 ? 'text-red-400' : 'text-gray-400'}`} fill="none" viewBox="0 0 24 24" stroke="currentColor">
                    <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M9 5l7 7-7 7" />
                  </svg>
                )}
              </div>
            ))}
          </div>
        </div>
      )}

      {/* Credential Claims */}
      {credentials[index] && credentials[index].credentialSubject && (
        <div className="mb-4 rounded-lg border border-gray-200 bg-white p-3">
          <div className="flex items-center justify-between mb-2">
            <div className="text-xs font-medium text-gray-500 uppercase tracking-wide">
              {credentials[index]?.type
                ? credentials[index].type[credentials[index].type.length - 1].replace(/([a-z0-9])([A-Z])/g, '$1 $2')
                : credentials[index]?.vct
                  ? (VCT_DISPLAY_NAMES[String(credentials[index].vct)] || String(credentials[index].vct))
                  : 'Credential'}
            </div>
            {credentials.length > 1 && (
              <div className="flex items-center gap-1">
                <button onClick={() => setIndex(Math.max(0, index - 1))} disabled={index === 0}
                  className="text-gray-400 hover:text-gray-600 disabled:opacity-30 p-0.5">
                  <svg className="w-4 h-4" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                    <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M15 19l-7-7 7-7" />
                  </svg>
                </button>
                <span className="text-xs text-gray-500">{index + 1}/{credentials.length}</span>
                <button onClick={() => setIndex(Math.min(credentials.length - 1, index + 1))} disabled={index === credentials.length - 1}
                  className="text-gray-400 hover:text-gray-600 disabled:opacity-30 p-0.5">
                  <svg className="w-4 h-4" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                    <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M9 5l7 7-7 7" />
                  </svg>
                </button>
              </div>
            )}
          </div>
          <div className="space-y-1">
            {Object.entries(credentials[index].credentialSubject)
              .filter(([, v]) => typeof v === 'string' && (v as string).length > 0 && (v as string).length < 40)
              .map(([key, value]) => (
                <div key={key} className="flex text-sm">
                  <div className="text-gray-500 w-2/5 capitalize">
                    {(key.charAt(0).toUpperCase() + key.slice(1)).replace(/([a-z0-9])([A-Z])/g, '$1 $2')}
                  </div>
                  <div className="text-gray-800 w-3/5">{value as string}</div>
                </div>
              ))}
          </div>
        </div>
      )}

      {/* Verification Timeline */}
      {allPolicies.length > 0 && (
        <div className="px-1">
          <div className="text-xs font-medium text-gray-500 uppercase tracking-wide mb-3">
            {isFailure ? 'Failure Timeline' : 'Verification Timeline'}
          </div>
          <div className="relative">
            <div className="absolute left-[11px] top-2 bottom-2 w-0.5 bg-gray-200" />

            {/* Credential Presented */}
            <div className="relative flex items-start gap-3 pb-3">
              <div className="relative z-10 flex-shrink-0 w-6 h-6 rounded-full bg-blue-100 flex items-center justify-center">
                <div className="w-2 h-2 rounded-full bg-blue-500" />
              </div>
              <div className="flex-1 pt-0.5">
                <div className="text-sm font-medium text-gray-800">Credential Presented</div>
                <div className="text-xs text-gray-500">
                  {credentials[index]?.vct
                    ? (VCT_DISPLAY_NAMES[String(credentials[index].vct)] || String(credentials[index].vct))
                    : credentials[index]?.type
                      ? credentials[index].type[credentials[index].type.length - 1]
                      : 'Credential'
                  } received from holder wallet
                </div>
              </div>
              <CheckCircleIcon className="h-4 w-4 text-blue-500 mt-0.5" />
            </div>

            {/* Policy Check Steps */}
            {allPolicies.map((policy, pIdx) => {
              const info = getPolicyInfo(policy.policy);
              return (
                <div key={pIdx} className="relative flex items-start gap-3 pb-3">
                  <div className={`relative z-10 flex-shrink-0 w-6 h-6 rounded-full flex items-center justify-center ${
                    policy.is_success ? 'bg-green-100' : 'bg-red-100'
                  }`}>
                    <div className={`w-2 h-2 rounded-full ${policy.is_success ? 'bg-green-500' : 'bg-red-500'}`} />
                  </div>
                  <div className="flex-1 pt-0.5">
                    <div className="text-sm font-medium text-gray-800">{info.label}</div>
                    <div className={`text-xs ${policy.is_success ? 'text-gray-500' : 'text-red-600'}`}>
                      {policy.is_success ? info.passText : info.failText}
                    </div>
                    {policy.error && !policy.is_success && (
                      <div className="mt-1 text-xs bg-red-50 border border-red-200 rounded px-2 py-0.5 text-red-700 font-mono">
                        {policy.error}
                      </div>
                    )}
                  </div>
                  <div className="mt-0.5 flex-shrink-0">
                    {policy.is_success ? (
                      <CheckCircleIcon className="h-4 w-4 text-green-500" />
                    ) : (
                      <XCircleIcon className="h-4 w-4 text-red-500" />
                    )}
                  </div>
                </div>
              );
            })}

            {/* Final Result */}
            <div className="relative flex items-start gap-3">
              <div className={`relative z-10 flex-shrink-0 w-6 h-6 rounded-full flex items-center justify-center ${
                isFailure ? 'bg-red-100' : 'bg-green-100'
              }`}>
                {isFailure ? (
                  <XCircleIcon className="h-4 w-4 text-red-600" />
                ) : (
                  <CheckCircleIcon className="h-4 w-4 text-green-600" />
                )}
              </div>
              <div className="flex-1 pt-0.5">
                <div className={`text-sm font-semibold ${isFailure ? 'text-red-800' : 'text-green-800'}`}>
                  {isFailure ? 'Verification Failed' : 'Verification Passed'}
                </div>
                <div className={`text-xs ${isFailure ? 'text-red-600' : 'text-gray-500'}`}>
                  {isFailure
                    ? `${passedPolicies.length} of ${allPolicies.length} checks passed — credential rejected`
                    : `All ${allPolicies.length} policy checks passed — credential accepted`
                  }
                </div>
              </div>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}

export { parseApi2Session, VCT_DISPLAY_NAMES, POLICY_DESCRIPTIONS, getPolicyInfo };
export type { FlowParticipant };
