import axios from "axios";
import {
  AvailableCredential,
  CredentialFormats,
  mapFormat,
  isEudiFormat,
  buildDcqlQuery,
  buildVerificationSessionRequest,
  VerificationSigningConfig,
} from "@/types/credentials";
import {getStateFromUrl} from "@/utils/checkVerificationResult";

export interface VerificationSessionResult {
  verifyUrl: string;
  sessionId: string;
  isApi2: boolean;
  verifierUrl: string;
  rpHintName?: string;
  error?: string;
}

export interface CreateVerificationSessionParams {
  credentials: AvailableCredential[];
  format: string;
  vps: string[];
  rpId?: string;
  env: Record<string, string | undefined>;
  runtimeConfig: Record<string, string | undefined>;
}

export async function createVerificationSession(
  params: CreateVerificationSessionParams
): Promise<VerificationSessionResult> {
  const {credentials, format, vps, rpId, env, runtimeConfig} = params;
  const credFormat = mapFormat(format);

  if (isEudiFormat(credFormat)) {
    return createApi2Session(credentials, credFormat, vps, rpId, env, runtimeConfig);
  } else {
    return createLegacySession(credentials, credFormat, vps, env, runtimeConfig);
  }
}

async function createApi2Session(
  credentials: AvailableCredential[],
  credFormat: string,
  vps: string[],
  rpId: string | undefined,
  env: Record<string, string | undefined>,
  runtimeConfig: Record<string, string | undefined>
): Promise<VerificationSessionResult> {
  const verifier2Url = env.NEXT_PUBLIC_VERIFIER2 || runtimeConfig.NEXT_PUBLIC_VERIFIER2;

  if (!verifier2Url) {
    return {
      verifyUrl: '',
      sessionId: '',
      isApi2: true,
      verifierUrl: '',
      error: 'EUDI verification requires Verifier API2 configuration (NEXT_PUBLIC_VERIFIER2)',
    };
  }

  const dcqlQuery = buildDcqlQuery(credentials, credFormat);
  let rpHintName: string | undefined;

  // Build signing config - prefer RP-specific config when rpId is present
  let signingConfig: VerificationSigningConfig | undefined;

  if (rpId && verifier2Url) {
    try {
      const rpDetail = await axios.get(`${verifier2Url}/admin/rp/${rpId}`);
      const certResponse = await axios.get(`${verifier2Url}/admin/rp/${rpId}/certificate/download`, {
        transformResponse: [(data: string) => data],
      });
      const rpData = rpDetail.data;
      if (rpData.legalName) rpHintName = rpData.legalName;
      if (rpData.x5c && rpData.x5c.length > 0) {
        const certDownload = JSON.parse(certResponse.data);
        signingConfig = {
          clientId: rpData.clientId,
          key: {type: 'jwk', jwk: certDownload.privateKeyJwk},
          x5c: rpData.x5c,
        };
      }
    } catch (e) {
      console.warn('Failed to fetch RP signing config, falling back to env:', e);
    }
  }

  // Fall back to environment variables if no RP config
  if (!signingConfig) {
    const clientId = env.NEXT_PUBLIC_VERIFIER2_CLIENT_ID || runtimeConfig.NEXT_PUBLIC_VERIFIER2_CLIENT_ID;
    const signingKeyJson = env.NEXT_PUBLIC_VERIFIER2_SIGNING_KEY || runtimeConfig.NEXT_PUBLIC_VERIFIER2_SIGNING_KEY;
    const x5c = env.NEXT_PUBLIC_VERIFIER2_X5C || runtimeConfig.NEXT_PUBLIC_VERIFIER2_X5C;

    if (clientId && signingKeyJson && x5c) {
      try {
        signingConfig = {
          clientId,
          key: JSON.parse(signingKeyJson),
          x5c: [x5c],
        };
      } catch (e) {
        console.warn('Failed to parse verifier signing config:', e);
      }
    }
  }

  const preSessionId = crypto.randomUUID();
  const successUrl = `${window.location.origin}/success/${preSessionId}?api2=true`;

  const requestBody = buildVerificationSessionRequest(
    dcqlQuery,
    signingConfig,
    vps,
    {success_redirect_uri: successUrl, error_redirect_uri: successUrl},
    preSessionId
  );

  // Pass rpId as query param so verifier-api2 resolves the RP and applies the
  // urlPrefix override (response_uri host must match the x509_san_dns client_id
  // domain per OID4VP §5.10). Without this the client_id says rp.<domain> but
  // response_uri stays on verifier2.<domain> and the wallet rejects.
  const createUrl = rpId
    ? `${verifier2Url}/verification-session/create?rpId=${encodeURIComponent(rpId)}`
    : `${verifier2Url}/verification-session/create`;
  const response = await axios.post(
    createUrl,
    requestBody,
    {headers: {'Content-Type': 'application/json'}}
  );

  const verificationUrl = response.data.bootstrapAuthorizationRequestUrl;
  const sessionId = response.data.sessionId;

  return {
    verifyUrl: verificationUrl,
    sessionId: sessionId || getStateFromUrl(verificationUrl) || preSessionId,
    isApi2: true,
    verifierUrl: verifier2Url,
    rpHintName,
  };
}

async function createLegacySession(
  credentials: AvailableCredential[],
  credFormat: string,
  vps: string[],
  env: Record<string, string | undefined>,
  runtimeConfig: Record<string, string | undefined>
): Promise<VerificationSessionResult> {
  const standardVersion = 'draft13';
  const issuerMetadataConfigSelector: Record<string, string> = {
    draft13: 'credential_configurations_supported',
    draft11: 'credentials_supported',
  };

  const issuerUrl = env.NEXT_PUBLIC_ISSUER || runtimeConfig.NEXT_PUBLIC_ISSUER;
  const issuerMetadata = await axios.get(
    `${issuerUrl}/${standardVersion}/.well-known/openid-credential-issuer`
  );

  const request_credentials = credentials.map((credential) => {
    if (credFormat === 'vc+sd-jwt') {
      const vct =
        issuerMetadata.data[issuerMetadataConfigSelector[standardVersion]][
          `${credential.offer.type[credential.offer.type.length - 1]}_vc+sd-jwt`
        ]?.vct;
      return {vct, format: 'vc+sd-jwt'};
    } else {
      return {
        type: credential.offer.type?.[credential.offer.type.length - 1] || credential.id,
        format: credFormat,
      };
    }
  });

  let requestBody: any = {request_credentials};

  if (credFormat !== 'vc+sd-jwt') {
    // Map policy names for legacy verifier (verifier-api uses different names than verifier-api2)
    const legacyPolicyMap: Record<string, string> = {
      'expiration': 'expired',
    };
    // Policies only supported by verifier-api2, skip for legacy
    const api2OnlyPolicies = new Set(['etsi-trusted-issuer', 'revoked-status-list']);

    requestBody.vc_policies = vps
      .filter((vp) => !api2OnlyPolicies.has(vp.split('=')[0]))
      .map((vp) => {
        if (vp.includes('=')) {
          return {policy: vp.split('=')[0], args: vp.split('=')[1]};
        } else {
          return legacyPolicyMap[vp] || vp;
        }
      });
  }

  const verifierUrl = env.NEXT_PUBLIC_VERIFIER || runtimeConfig.NEXT_PUBLIC_VERIFIER;
  const response = await axios.post(`${verifierUrl}/openid4vc/verify`, requestBody, {
    headers: {
      successRedirectUri: `${window.location.origin}/success/$id`,
      errorRedirectUri: `${window.location.origin}/success/$id`,
    },
  });

  const verifyUrl = response.data;
  const sessionId = getStateFromUrl(verifyUrl) || '';

  return {
    verifyUrl,
    sessionId,
    isApi2: false,
    verifierUrl: verifierUrl || '',
  };
}
