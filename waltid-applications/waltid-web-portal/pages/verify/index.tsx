import {useContext, useEffect, useState} from "react";
import WaltIcon from "@/components/walt/logo/WaltIcon";
import Button from "@/components/walt/button/Button";
import {CredentialsContext, EnvContext} from "@/pages/_app";
import Icon from "@/components/walt/logo/Icon";
import {useRouter} from "next/router";
import QRCode from "react-qr-code";
import axios from "axios";
import {sendToWebWallet} from "@/utils/sendToWebWallet";
import {isMobileDevice} from "@/utils/deviceDetection";
import nextConfig from "@/next.config";
import BackButton from "@/components/walt/button/BackButton";
import {CredentialFormats, mapFormat, isEudiFormat, buildDcqlQuery, buildVerificationSessionRequest, VerificationSigningConfig} from "@/types/credentials";
import {checkVerificationResult, getStateFromUrl} from "@/utils/checkVerificationResult";

const BUTTON_COPY_TEXT_DEFAULT = 'Copy offer URL';
const BUTTON_COPY_TEXT_COPIED = 'Copied';

export default function Verification() {
  const env = useContext(EnvContext);
  const [AvailableCredentials] = useContext(CredentialsContext);
  const router = useRouter();

  const [verifyURL, setverifyURL] = useState('');
  const [loading, setLoading] = useState(true);
  const [copyText, setCopyText] = useState(BUTTON_COPY_TEXT_DEFAULT);
  const [error, setError] = useState<string | null>(null);
  const [usedApi2, setUsedApi2] = useState(false);
  const [isMobile, setIsMobile] = useState(false);

  // Detect mobile device on mount (client-side only)
  useEffect(() => {
    setIsMobile(isMobileDevice());
  }, []);

  function handleCancel() {
    router.push('/');
  }

  useEffect(() => {
    // Wait for router to be ready with query parameters
    if (!router.isReady) return;

    const getverifyURL = async () => {
      try {
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

        // Route EUDI formats (dc+sd-jwt, mso_mdoc) to Verifier API2
        if (isEudiFormat(credFormat)) {
          const verifier2Url = env.NEXT_PUBLIC_VERIFIER2 || nextConfig.publicRuntimeConfig?.NEXT_PUBLIC_VERIFIER2;

          if (!verifier2Url) {
            setError('EUDI verification requires Verifier API2 configuration (NEXT_PUBLIC_VERIFIER2)');
            setLoading(false);
            return;
          }

          const dcqlQuery = buildDcqlQuery(credentials, credFormat);

          // Build signing config from environment variables if available
          let signingConfig: VerificationSigningConfig | undefined;
          const clientId = env.NEXT_PUBLIC_VERIFIER2_CLIENT_ID || nextConfig.publicRuntimeConfig?.NEXT_PUBLIC_VERIFIER2_CLIENT_ID;
          const signingKeyJson = env.NEXT_PUBLIC_VERIFIER2_SIGNING_KEY || nextConfig.publicRuntimeConfig?.NEXT_PUBLIC_VERIFIER2_SIGNING_KEY;
          const x5c = env.NEXT_PUBLIC_VERIFIER2_X5C || nextConfig.publicRuntimeConfig?.NEXT_PUBLIC_VERIFIER2_X5C;

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

          const requestBody = buildVerificationSessionRequest(dcqlQuery, signingConfig);

          const response = await axios.post(
            `${verifier2Url}/verification-session/create`,
            requestBody,
            {
              headers: {
                'Content-Type': 'application/json',
              },
            }
          );

          // API2 returns an object with bootstrapAuthorizationRequestUrl
          const verificationUrl = response.data.bootstrapAuthorizationRequestUrl;
          const sessionId = response.data.sessionId;

          setverifyURL(verificationUrl);
          setUsedApi2(true);
          setLoading(false);

          const state = sessionId || getStateFromUrl(verificationUrl);
          if (state) {
            checkVerificationResult(verifier2Url, state, true).then((result) => {
              if (result) {
                router.push(`/success/${state}`);
              }
            });
          }
        } else {
          // Legacy formats (jwt_vc_json, vc+sd-jwt) use existing Verifier API
          const standardVersion = 'draft13'; // ['draft13', 'draft11']
          const issuerMetadataConfigSelector = {
            'draft13': 'credential_configurations_supported',
            'draft11': 'credentials_supported',
          }

          const issuerMetadata = await axios.get(`${env.NEXT_PUBLIC_ISSUER ? env.NEXT_PUBLIC_ISSUER : nextConfig.publicRuntimeConfig!.NEXT_PUBLIC_ISSUER}/${standardVersion}/.well-known/openid-credential-issuer`);
          const request_credentials = credentials.map((credential) => {
            if (credFormat === 'vc+sd-jwt') {
              const vct = issuerMetadata.data[issuerMetadataConfigSelector[standardVersion]][`${credential.offer.type[credential.offer.type.length - 1]}_vc+sd-jwt`]?.vct;
              return {
                vct,
                format: 'vc+sd-jwt',
              };
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
          setLoading(false);

          const state = getStateFromUrl(response.data);
          if (state) {
            checkVerificationResult(verifierUrl, state, false).then((result) => {
              if (result) {
                router.push(`/success/${state}`);
              }
            });
          }
        }
      } catch (err) {
        console.error('Error creating verification session:', err);
        setError('Failed to create verification session. Please try again.');
        setLoading(false);
      }
    };
    getverifyURL();
  }, [router.isReady]);

  async function copyCurrentURLToClipboard() {
    navigator.clipboard.writeText(verifyURL).then(
      function () {
        setCopyText(BUTTON_COPY_TEXT_COPIED);
        setTimeout(() => {
          setCopyText(BUTTON_COPY_TEXT_DEFAULT);
        }, 3000);
      },
      function (err) {
        console.error('Could not copy text: ', err);
      }
    );
  }

  function openWebWallet() {
    sendToWebWallet(
      env.NEXT_PUBLIC_WALLET
        ? env.NEXT_PUBLIC_WALLET
        : nextConfig.publicRuntimeConfig!.NEXT_PUBLIC_WALLET,
      'api/siop/initiatePresentation',
      verifyURL
    );
  }

  function openInEudiWallet() {
    // Deep link directly to EUDI wallet app on same device
    window.location.href = verifyURL;
  }

  return (
    <div className="flex flex-col justify-center items-center bg-gray-50">
      <div
        className="my-5 flex flex-row justify-center cursor-pointer"
        onClick={() => router.push('/')}
      >
        <Icon height={35} width={35} />
      </div>
      <div className="relative w-10/12 sm:w-7/12 lg:w-5/12 text-center shadow-2xl rounded-lg pt-8 pb-8 px-10 bg-white">
        <BackButton />
        <h1 className="text-xl sm:text-2xl lg:text-3xl text-gray-900 text-center font-bold mt-5">
          Scan to Verify
        </h1>
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
        {/* Same-device button for EUDI formats - show on all devices for EUDI, mobile detection for UX only */}
        {usedApi2 && !loading && !error && (
          <div className="mb-4">
            <Button onClick={openInEudiWallet} style="button" className="w-full bg-blue-600 hover:bg-blue-700">
              Open in EUDI Wallet
            </Button>
            <p className="text-xs text-gray-500 mt-2 text-center">
              {isMobile ? 'Tap to open wallet app' : 'Or scan the QR code with your mobile device'}
            </p>
          </div>
        )}
        <div className="sm:flex flex-row gap-5 justify-center">
          <Button style="link" onClick={copyCurrentURLToClipboard}>
            {copyText}
          </Button>
          <Button onClick={openWebWallet} style="button">
            Open Web Wallet
          </Button>
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
