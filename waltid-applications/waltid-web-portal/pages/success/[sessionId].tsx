import WaltIcon from "@/components/walt/logo/WaltIcon";
import {CheckCircleIcon, XCircleIcon} from "@heroicons/react/24/outline";
import {useContext, useEffect, useState} from "react";
import {useRouter} from "next/router";
import axios from "axios";
import nextConfig from "@/next.config";
import Modal from "@/components/walt/modal/BaseModal";
import {EnvContext} from "@/pages/_app";

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

export default function Success() {
  const env = useContext(EnvContext);
  const router = useRouter();
  const [vctName, setVctName] = useState<string | null>(null);

  const [policyResults, setPolicyResults] = useState<
    Array<{
      policyResults: Array<{
        policy: string;
        is_success: boolean;
      }>;
    }>
  >([]);
  const [credentials, setCredentials] = useState<
    Array<{
      type: Array<string>;
      vct: Array<string>;
      credentialSubject: {
        [key: string]: string;
      };
    }>
  >([]);
  const [index, setIndex] = useState<number>(0);
  const [modal, setModal] = useState<boolean>(false);
  const [sessionStatus, setSessionStatus] = useState<string>('');
  const [flowParticipants, setFlowParticipants] = useState<FlowParticipant[]>([]);

  function parseJwt(token: string) {
    return JSON.parse(Buffer.from(token.split('.')[1], 'base64').toString());
  }

  const fetchVctName = async (vctUrl: string) => {
    try {
      const response = await axios.get(vctUrl);
      const bodyJson = response.data;
      return bodyJson['name'];
    } catch (error) {
      console.error('Error fetching vct:', error);
      return 'Unknown VCT'; // Fallback value if the request fails
    }
  };

  useEffect(() => {
    if (!router.isReady) return;
    const isApi2 = router.query.api2 === 'true';

    if (isApi2) {
      // Verifier API2 session response format
      const verifier2Url = env.NEXT_PUBLIC_VERIFIER2 || nextConfig.publicRuntimeConfig?.NEXT_PUBLIC_VERIFIER2;
      if (!verifier2Url) return;

      axios.get(`${verifier2Url}/verification-session/${router.query.sessionId}/info`)
        .then((response) => {
          const session = response.data;

          // Extract session status
          setSessionStatus(session.status || '');

          // Extract flow participants
          const participants: FlowParticipant[] = [];

          // 1. Issuer — check top-level claims, mDoc namespaces, then X.509 cert CN
          let issuerName = 'Unknown Issuer';
          let issuerDetail = '';
          if (session.presentedCredentials) {
            for (const [, credList] of Object.entries(session.presentedCredentials) as [string, any[]][]) {
              for (const cred of credList) {
                const data = cred.credentialData || {};

                // Collect all claims (top-level + mDoc namespaces)
                const allClaims: Record<string, any> = {};
                for (const [k, v] of Object.entries(data)) {
                  if (typeof v === 'object' && v !== null && !Array.isArray(v) && k !== 'cnf') {
                    // mDoc namespace — flatten claims from it
                    Object.assign(allClaims, v);
                  } else {
                    allClaims[k] = v;
                  }
                }

                // Prefer human-readable issuing_authority claim
                if (allClaims.issuing_authority) {
                  issuerName = allClaims.issuing_authority;
                  issuerDetail = data.iss || cred.docType || '';
                } else if (data.iss) {
                  try {
                    const issUrl = new URL(data.iss);
                    issuerName = issUrl.host;
                    issuerDetail = data.iss;
                  } catch {
                    issuerName = data.iss.length > 30
                      ? data.iss.substring(0, 30) + '...'
                      : data.iss;
                    issuerDetail = data.iss;
                  }
                }

                // For mDoc: extract issuer from X.509 IACA certificate CN
                if (issuerName === 'Unknown Issuer' && cred.signature?.x5cList?.length > 0) {
                  try {
                    // Use the IACA cert (last in chain) or signer cert (first)
                    const certB64 = cred.signature.x5cList[cred.signature.x5cList.length - 1];
                    const certBinary = Buffer.from(certB64, 'base64').toString('binary');
                    // Extract readable text sequences from DER cert
                    let readable = '';
                    for (let ci = 0; ci < certBinary.length; ci++) {
                      const code = certBinary.charCodeAt(ci);
                      readable += (code >= 32 && code < 127) ? certBinary[ci] : '\x00';
                    }
                    // Find org name — look for text before " IACA" or " Document Signer"
                    const iacaMatch = readable.match(/([A-Z][A-Za-z0-9 .'()-]+?)\s+IACA/);
                    if (iacaMatch) {
                      issuerName = iacaMatch[1].trim();
                    } else {
                      const dsMatch = readable.match(/([A-Z][A-Za-z0-9 .'()-]+?)\s+Document Signer/);
                      if (dsMatch) {
                        issuerName = dsMatch[1].trim();
                      }
                    }
                  } catch {
                    // Cert parsing failed — keep Unknown Issuer
                  }
                }

                // Append country context
                const country = allClaims.issuing_country;
                if (country && issuerName !== 'Unknown Issuer') {
                  issuerDetail = `${country} — ${issuerDetail || data.iss || ''}`;
                }
                if (cred.docType) {
                  issuerDetail = issuerDetail || cred.docType;
                }
              }
            }
          }
          participants.push({ role: 'Issuer', name: issuerName, detail: issuerDetail });

          // 2. Holder — the wallet that presented
          participants.push({ role: 'Holder', name: 'Digital Wallet', detail: 'Credential holder' });

          // 3. Verifier — from client_id in authorization request
          let verifierName = 'Unknown Verifier';
          let verifierDetail = '';
          const clientId = session.bootstrapAuthorizationRequest?.client_id
            || session.authorizationRequest?.client_id;
          if (clientId) {
            verifierDetail = clientId;
            // Parse x509_san_dns: prefix
            if (clientId.startsWith('x509_san_dns:')) {
              verifierName = clientId.replace('x509_san_dns:', '');
            } else {
              try {
                verifierName = new URL(clientId).host;
              } catch {
                verifierName = clientId;
              }
            }
          }
          participants.push({ role: 'Verifier', name: verifierName, detail: verifierDetail });

          setFlowParticipants(participants);

          // Extract credentials from presentedCredentials map
          const creds: any[] = [];
          if (session.presentedCredentials) {
            for (const [, credList] of Object.entries(session.presentedCredentials) as [string, any[]][]) {
              for (const cred of credList) {
                const data = cred.credentialData || {};
                const displayCred: any = {
                  vct: data.vct || cred.docType,
                  type: data.type,
                  credentialSubject: {} as Record<string, string>,
                };
                // Pick user-facing claims — check top-level AND mDoc namespaces
                const skipKeys = new Set(['iss', 'iat', 'nbf', 'exp', 'cnf', 'vct', 'type', '_sd', '_sd_alg', 'docType']);
                for (const [k, v] of Object.entries(data)) {
                  if (typeof v === 'object' && v !== null && !Array.isArray(v) && k !== 'cnf') {
                    // mDoc namespace — extract claims from nested object
                    for (const [nk, nv] of Object.entries(v as Record<string, any>)) {
                      if (typeof nv === 'string' || typeof nv === 'boolean') {
                        displayCred.credentialSubject[nk] = String(nv);
                      }
                    }
                  } else if (!skipKeys.has(k) && (typeof v === 'string' || typeof v === 'boolean')) {
                    displayCred.credentialSubject[k] = String(v);
                  }
                }
                creds.push(displayCred);
              }
            }
          }
          setCredentials(creds);

          if (creds.length > 0 && creds[0].vct) {
            setVctName(creds[0].vct);
          }

          // Transform api2 policy results into the display format
          // policyResults state uses: Array<{ policyResults: Array<{ policy: string; is_success: boolean }> }>
          // Index 0 = VP-level dummy, index 1+ = per-credential
          const displayPolicies: Array<{ policyResults: Array<{ policy: string; is_success: boolean }> }> = [];

          // VP-level policies (index 0 placeholder)
          const vpPolicies: Array<{ policy: string; is_success: boolean }> = [];
          if (session.policyResults?.vp_policies) {
            for (const [, policiesMap] of Object.entries(session.policyResults.vp_policies) as [string, any][]) {
              for (const [policyName, result] of Object.entries(policiesMap) as [string, any][]) {
                vpPolicies.push({
                  policy: policyName,
                  is_success: result.success,
                });
              }
            }
          }
          displayPolicies.push({ policyResults: vpPolicies });

          // VC-level policies (index 1+, one entry per credential)
          const vcPolicies: Array<{ policy: string; is_success: boolean }> = [];
          if (session.policyResults?.vc_policies) {
            for (const result of session.policyResults.vc_policies) {
              vcPolicies.push({
                policy: result.policy?.policy || result.policy?.id || 'unknown',
                is_success: result.success,
              });
            }
          }
          // One VC policy group per credential
          for (let i = 0; i < Math.max(creds.length, 1); i++) {
            displayPolicies.push({ policyResults: vcPolicies });
          }

          setPolicyResults(displayPolicies);
        });
    } else {
      // Legacy verifier session response format
      axios
        .get(
          `${env.NEXT_PUBLIC_VERIFIER ? env.NEXT_PUBLIC_VERIFIER : nextConfig.publicRuntimeConfig!.NEXT_PUBLIC_VERIFIER}/openid4vc/session/${router.query.sessionId}`
        )
        .then((response) => {
          let parsedToken = parseJwt(response.data.tokenResponse.vp_token);
          let containsVP = !!parsedToken.vp?.verifiableCredential;
          let vcs = containsVP
            ? parsedToken.vp?.verifiableCredential
            : [response.data.tokenResponse.vp_token];

          setCredentials(
            Array.isArray(vcs)
              ? vcs.map((vc: string) => {
                if (typeof vc !== 'string') {
                  console.error(
                    'Invalid VC format: expected a string but got',
                    vc
                  );
                  return vc;
                }
                let split = vc.split('~');
                let parsed = parseJwt(split[0]);

                if (split.length === 1) return parsed.vc ? parsed.vc : parsed;
                else {
                  let credentialWithSdJWTAttributes = { ...parsed };
                  split.slice(1).forEach((item) => {
                    // If it is key binding jwt, skip
                    if (item.split('.').length === 3) return;

                    let parsedItem = JSON.parse(
                      Buffer.from(item, 'base64').toString()
                    );
                    credentialWithSdJWTAttributes.credentialSubject = {
                      [parsedItem[1]]: parsedItem[2],
                      ...credentialWithSdJWTAttributes.credentialSubject,
                    };
                  });
                  credentialWithSdJWTAttributes.type = parsed.vc?.type
                  return credentialWithSdJWTAttributes;
                }
              })
              : []
          );

          setPolicyResults(() => {
            if (containsVP) {
              return response.data.policyResults.results;
            } else {
              return [
                {
                  policyResults: [
                    {
                      policy: 'New Policy',
                      is_success: true,
                    },
                  ],
                },
                ...response.data.policyResults.results,
              ];
            }
          });

          if (!containsVP) {
            const vct = parsedToken['vct'];
            const vctUrl = new URL(vct);
            const vctResolutionUrl = `${vctUrl.origin}/.well-known/vct${vctUrl.pathname}`;
            fetchVctName(vctResolutionUrl).then((name) => setVctName(name));
          }
        });
    }
  }, [router.isReady, env]);

  return (
    <div className="h-screen flex justify-center items-center bg-gray-50">
      <Modal show={modal} securedByWalt={false} onClose={() => setModal(false)}>
        <div className="flex flex-col items-center">
          <div className="w-full">
            <textarea
              value={JSON.stringify(
                credentials[index]?.credentialSubject ?? credentials[index],
                null,
                4
              )}
              disabled={true}
              className="w-full h-48 border-2 border-gray-300 rounded-md px-2"
            />
          </div>
        </div>
      </Modal>
      <div className="relative w-full h-full sm:h-auto sm:w-10/12 md:w-8/12 lg:w-6/12 text-center shadow-2xl rounded-lg pt-8 pb-8 px-10 bg-white">
        {/* Status Header */}
        {sessionStatus && (
          <div className={`flex items-center justify-center gap-2 mb-4 py-2 px-4 rounded-full ${
            sessionStatus === 'SUCCESSFUL'
              ? 'bg-green-50 text-green-700'
              : 'bg-red-50 text-red-700'
          }`}>
            {sessionStatus === 'SUCCESSFUL' ? (
              <CheckCircleIcon className="h-5 w-5" />
            ) : (
              <XCircleIcon className="h-5 w-5" />
            )}
            <span className="text-sm font-semibold">
              {sessionStatus === 'SUCCESSFUL' ? 'Verification Successful' : `Verification ${sessionStatus}`}
            </span>
          </div>
        )}

        <h1 className="text-3xl text-gray-900 text-center font-bold mb-6">
          Presented Credentials
        </h1>

        {/* Flow Visualization */}
        {flowParticipants.length > 0 && (
          <div className="mb-8 rounded-lg border border-gray-200 bg-gray-50 p-4">
            <div className="text-xs font-medium text-gray-500 uppercase tracking-wide mb-3">Verification Flow</div>
            <div className="flex items-center justify-between gap-2">
              {flowParticipants.map((participant, i) => (
                <div key={participant.role} className="flex items-center gap-2 flex-1">
                  <div className="flex flex-col items-center text-center flex-1 min-w-0">
                    <div className="w-11 h-11 rounded-full flex items-center justify-center text-white text-sm font-bold shadow-md"
                      style={{ backgroundColor: participant.role === 'Issuer' ? '#2563eb' : participant.role === 'Holder' ? '#9333ea' : '#16a34a' }}>
                      {participant.role === 'Issuer' ? 'I' : participant.role === 'Holder' ? 'H' : 'V'}
                    </div>
                    <div className="mt-1.5 text-xs font-semibold text-gray-700">{participant.role}</div>
                    <div className="text-xs text-gray-500 truncate max-w-full" title={participant.detail || participant.name}>
                      {participant.name}
                    </div>
                  </div>
                  {i < flowParticipants.length - 1 && (
                    <svg className="w-5 h-5 text-gray-400 flex-shrink-0 -mt-5" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                      <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M9 5l7 7-7 7" />
                    </svg>
                  )}
                </div>
              ))}
            </div>
          </div>
        )}

        <div className="flex items-center justify-center">
          {index !== 0 && (
            <button
              onClick={() => setIndex(index - 1)}
              className="text-gray-500 hover:text-gray-900 focus:outline-none absolute left-10"
            >
              <svg
                xmlns="http://www.w3.org/2000/svg"
                className="h-6 w-6 mr-2"
                fill="none"
                viewBox="0 0 24 24"
                stroke="currentColor"
              >
                <path
                  strokeLinecap="round"
                  strokeLinejoin="round"
                  strokeWidth={2}
                  d="M15 19l-7-7 7-7"
                />
              </svg>
            </button>
          )}
          <div className="group h-[225px] w-[400px] [perspective:1000px]">
            <div className="relative h-full w-full rounded-xl shadow-xl transition-all duration-500 [transform-style:preserve-3d] group-hover:[transform:rotateY(180deg)]">
              <div className="absolute inset-0">
                <div className="flex h-full w-full flex-col drop-shadow-sm rounded-xl py-7 px-8 text-gray-100 cursor-pointer overflow-hidden bg-gradient-to-r from-green-700 to-green-900 z-[-2]">
                  <div className="flex flex-row">
                    <WaltIcon height={35} width={35} outline type="white" />
                  </div>
                  <div className="mb-8 mt-12">
                    <h6 className={'text-2xl font-bold overflow-hidden text-ellipsis whitespace-nowrap'}>
                      {credentials[index]?.type
                        ? credentials[index]?.type[
                          credentials[index].type.length - 1
                        ].replace(/([a-z0-9])([A-Z])/g, '$1 $2')
                        : credentials[index]?.vct
                          ? (VCT_DISPLAY_NAMES[String(credentials[index].vct)] || vctName || String(credentials[index].vct))
                          : 'Credential'}
                    </h6>
                  </div>
                </div>
              </div>
              <div className="absolute inset-0 h-full w-full rounded-xl bg-white p-5 text-slate-200 [transform:rotateY(180deg)] [backface-visibility:hidden] overflow-y-scroll">
                {credentials[index] && credentials[index].credentialSubject &&
                  Object.keys(credentials[index].credentialSubject)
                    .map((key) => {
                      if (
                        typeof credentials[index].credentialSubject[key] ===
                        'string' &&
                        credentials[index].credentialSubject[key].length > 0 &&
                        credentials[index].credentialSubject[key].length < 20
                      ) {
                        return {
                          key: (
                            key.charAt(0).toUpperCase() + key.slice(1)
                          ).replace(/([a-z0-9])([A-Z])/g, '$1 $2'),
                          value: credentials[index].credentialSubject[key],
                        };
                      }
                    })
                    .filter((item) => item !== undefined).length > 0 && (
                    <>
                      {Object.keys(credentials[index].credentialSubject)
                        .map((key) => {
                          if (
                            typeof credentials[index].credentialSubject[key] ===
                            'string' &&
                            credentials[index].credentialSubject[key].length >
                            0 &&
                            credentials[index].credentialSubject[key].length <
                            20
                          ) {
                            return {
                              key: (
                                key.charAt(0).toUpperCase() + key.slice(1)
                              ).replace(/([a-z0-9])([A-Z])/g, '$1 $2'),
                              value: credentials[index].credentialSubject[key],
                            };
                          }
                        })
                        .map((item, index) => {
                          return (
                            <div key={index} className="flex flex-row py-1">
                              <div className="text-gray-600 text-left w-1/2 capitalize leading-[1.1]">
                                {item?.key}
                              </div>
                              <div className="text-slate-800 text-left w-1/2 text-[#313233]">
                                {item?.value}
                              </div>
                            </div>
                          );
                        })}
                    </>
                  )}
                <div className="flex flex-row py-1">
                  <button
                    onClick={() => setModal(true)}
                    className="text-gray-500 text-center w-full capitalize leading-[1.1] underline"
                  >
                    View Credential In JSON
                  </button>
                </div>
              </div>
            </div>
          </div>
          {index !== credentials.length - 1 && (
            <button
              onClick={() => setIndex(index + 1)}
              className="text-gray-500 hover:text-gray-900 focus:outline-none absolute right-10"
            >
              <svg
                xmlns="http://www.w3.org/2000/svg"
                className="h-6 w-6 ml-2"
                fill="none"
                viewBox="0 0 24 24"
                stroke="currentColor"
              >
                <path
                  strokeLinecap="round"
                  strokeLinejoin="round"
                  strokeWidth={2}
                  d="M9 5l7 7-7 7"
                />
              </svg>
            </button>
          )}
        </div>
        <div className="mt-10 px-12">
          <div className="flex flex-row items-center justify-center mb-5 text-gray-500">
            {policyResults[index + 1]?.policyResults.length
              ? 'The VP was verified along with:'
              : 'The VP was not verified against any policies'}
          </div>
          <div className="xs:grid xs:grid-cols-2 items-center justify-center">
            {policyResults[index + 1]?.policyResults
              .map((policy) => {
                return {
                  name:
                    policy.policy.charAt(0).toUpperCase() +
                    policy.policy.slice(1) +
                    ' Policy',
                  is_success: policy.is_success,
                };
              })
              .map((policy, index) => {
                return (
                  <div
                    key={policy.name}
                    className={`flex items-center gap-3 overflow-hidden text-ellipsis whitespace-nowrap ${index % 2 == 1 ? 'sm:justify-self-end' : ''}`}
                  >
                    {policy.is_success ? (
                      <CheckCircleIcon className="h-4 text-green-600" />
                    ) : (
                      <CheckCircleIcon className="h-4 text-red-600" />
                    )}
                    <div>{policy.name}</div>
                  </div>
                );
              })}
          </div>
        </div>
        <div className="flex flex-col items-center mt-12">
          <div className="flex flex-row gap-2 items-center content-center text-sm text-center text-gray-500">
            <p className="">Secured by walt.id</p>
            <WaltIcon height={15} width={15} type="gray" />
          </div>
        </div>
      </div>
    </div>
  );
}
