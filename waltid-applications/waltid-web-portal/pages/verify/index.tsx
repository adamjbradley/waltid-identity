import {useContext, useEffect, useState} from "react";
import WaltIcon from "@/components/walt/logo/WaltIcon";
import Button from "@/components/walt/button/Button";
import {CredentialsContext, EnvContext} from "@/pages/_app";
import Icon from "@/components/walt/logo/Icon";
import {useRouter} from "next/router";
import QRCode from "react-qr-code";
import {sendToWebWallet} from "@/utils/sendToWebWallet";
import {isMobileDevice} from "@/utils/deviceDetection";
import nextConfig from "@/next.config";
import BackButton from "@/components/walt/button/BackButton";
import {CredentialFormats} from "@/types/credentials";
import {checkVerificationResult} from "@/utils/checkVerificationResult";
import {createVerificationSession} from "@/utils/createVerificationSession";

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
  const [rpHintName, setRpHintName] = useState<string>('');

  // Detect mobile device on mount (client-side only)
  useEffect(() => {
    setIsMobile(isMobileDevice());
  }, []);

  function handleCancel() {
    router.push('/');
  }

  useEffect(() => {
    if (!router.isReady) return;

    const getverifyURL = async () => {
      try {
        const vps = router.query.vps?.toString().split(',') ?? [];
        const ids = router.query.ids?.toString().split(',') ?? [];
        const format = router.query.format?.toString() ?? CredentialFormats[0];
        const credentials = AvailableCredentials.filter((cred) => {
          for (const id of ids) {
            if (id.toString() == cred.id.toString()) return true;
          }
          return false;
        });

        const result = await createVerificationSession({
          credentials,
          format,
          vps,
          rpId: router.query.rpId?.toString(),
          env: env as Record<string, string | undefined>,
          runtimeConfig: (nextConfig.publicRuntimeConfig ?? {}) as Record<string, string | undefined>,
        });

        if (result.error) {
          setError(result.error);
          setLoading(false);
          return;
        }

        setverifyURL(result.verifyUrl);
        setUsedApi2(result.isApi2);
        if (result.rpHintName) setRpHintName(result.rpHintName);
        setLoading(false);

        if (result.sessionId) {
          checkVerificationResult(result.verifierUrl, result.sessionId, result.isApi2).then((success) => {
            if (success) {
              router.push(`/success/${result.sessionId}${result.isApi2 ? '?api2=true' : ''}`);
            }
          });
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
    const metadata: Record<string, string> = {};
    if (rpHintName) metadata.rpName = rpHintName;

    sendToWebWallet(
      env.NEXT_PUBLIC_WALLET
        ? env.NEXT_PUBLIC_WALLET
        : nextConfig.publicRuntimeConfig!.NEXT_PUBLIC_WALLET,
      'api/siop/initiatePresentation',
      verifyURL,
      Object.keys(metadata).length > 0 ? metadata : undefined
    );
  }

  function openInLocalWallet() {
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
        {usedApi2 && !loading && !error && (
          <div className="mb-4">
            <Button onClick={openInLocalWallet} style="button" className="w-full bg-blue-600 hover:bg-blue-700">
              Open Local Wallet
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
