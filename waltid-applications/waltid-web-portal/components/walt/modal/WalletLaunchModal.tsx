import {useEffect, useRef, useState} from "react";
import Modal from "@/components/walt/modal/BaseModal";
import Button from "@/components/walt/button/Button";
import QRCode from "react-qr-code";
import {openWalletPopup, buildWalletUrl} from "@/utils/openWalletPopup";
import {checkVerificationResult} from "@/utils/checkVerificationResult";
import {checkIssuanceResult} from "@/utils/checkIssuanceResult";
import {ClipboardIcon, ArrowTopRightOnSquareIcon, CheckCircleIcon, XCircleIcon} from "@heroicons/react/24/outline";
import axios from "axios";
import VerificationResultView from "@/components/walt/verification/VerificationResultView";

type WalletLaunchModalProps = {
  show: boolean;
  onClose: () => void;
  mode: 'issue' | 'verify';
  credentialUrl: string;
  sessionId?: string;
  isApi2?: boolean;
  verifierUrl?: string;
  walletUrl: string;
  walletPath: string;
  walletMetadata?: Record<string, string>;
  isEudi?: boolean;
};

export default function WalletLaunchModal({
  show,
  onClose,
  mode,
  credentialUrl,
  sessionId,
  isApi2,
  verifierUrl,
  walletUrl,
  walletPath,
  walletMetadata,
  isEudi,
}: WalletLaunchModalProps) {
  const [popupBlocked, setPopupBlocked] = useState(false);
  const [copied, setCopied] = useState(false);
  const [verifyStatus, setVerifyStatus] = useState<'waiting' | 'success' | 'failed' | null>(null);
  const [verifySessionData, setVerifySessionData] = useState<any | null>(null);
  const [issueStatus, setIssueStatus] = useState<'waiting' | 'success' | 'failed' | 'popup-closed' | null>(null);
  const [popupOpened, setPopupOpened] = useState(false);
  const popupRef = useRef<Window | null>(null);

  const loading = !credentialUrl;

  // Reset state when modal opens/closes
  useEffect(() => {
    if (!show) {
      setPopupBlocked(false);
      setCopied(false);
      setVerifyStatus(null);
      setVerifySessionData(null);
      setIssueStatus(null);
      setPopupOpened(false);
      popupRef.current = null;
    }
  }, [show]);

  // Poll for verification result, then fetch full session info
  useEffect(() => {
    if (mode !== 'verify' || !sessionId || !verifierUrl || !show) return;
    setVerifyStatus('waiting');

    let cancelled = false;
    checkVerificationResult(verifierUrl, sessionId, isApi2 ?? false).then(async (result) => {
      if (cancelled) return;
      if (isApi2) {
        try {
          const response = await axios.get(`${verifierUrl}/verification-session/${sessionId}/info`);
          if (!cancelled) {
            setVerifySessionData(response.data);
            setVerifyStatus(result ? 'success' : 'failed');
          }
        } catch {
          if (!cancelled) setVerifyStatus(result ? 'success' : 'failed');
        }
      } else {
        setVerifyStatus(result ? 'success' : 'failed');
      }
    });

    return () => { cancelled = true; };
  }, [mode, sessionId, verifierUrl, isApi2, show]);

  // Poll for issuance result (via callback status API)
  useEffect(() => {
    if (mode !== 'issue' || !sessionId || !show) return;
    setIssueStatus('waiting');

    let cancelled = false;
    checkIssuanceResult(sessionId).then((result) => {
      if (cancelled) return;
      if (result === 'SUCCESSFUL') {
        setIssueStatus('success');
      } else if (result) {
        setIssueStatus('failed');
      }
    });

    return () => { cancelled = true; };
  }, [mode, sessionId, show]);

  // Detect popup window close
  useEffect(() => {
    if (!popupOpened || !popupRef.current || !show) return;

    const interval = setInterval(() => {
      if (popupRef.current?.closed) {
        popupRef.current = null;
        setPopupOpened(false);
        setIssueStatus((prev) => (prev === 'waiting' || prev === null) ? 'popup-closed' : prev);
        clearInterval(interval);
      }
    }, 500);

    return () => clearInterval(interval);
  }, [popupOpened, show]);

  function handleOpenWebWallet() {
    if (!credentialUrl) return;
    const returnUrl = sessionId && isApi2
      ? `${window.location.origin}/success/${sessionId}?api2=true`
      : undefined;
    const result = openWalletPopup(walletUrl, walletPath, credentialUrl, walletMetadata, returnUrl);
    if (result.status === 'blocked') {
      setPopupBlocked(true);
    } else if (result.popup) {
      popupRef.current = result.popup;
      setPopupOpened(true);
    }
  }

  function handleOpenLocalWallet() {
    if (!credentialUrl) return;
    window.location.href = credentialUrl;
  }

  function handleCopy() {
    if (!credentialUrl) return;
    navigator.clipboard.writeText(credentialUrl).then(() => {
      setCopied(true);
      setTimeout(() => setCopied(false), 3000);
    });
  }

  function handleNewVerification() {
    setVerifyStatus(null);
    setVerifySessionData(null);
    onClose();
  }

  const webWalletUrl = credentialUrl
    ? buildWalletUrl(walletUrl, walletPath, credentialUrl, walletMetadata)
    : '';

  const isIssueComplete = issueStatus === 'success' || issueStatus === 'popup-closed';
  const hasVerifyResults = verifySessionData !== null;

  return (
    <Modal show={show} onClose={onClose}>
      <div className="flex flex-col items-center text-center">
        {/* Verification results view — replaces QR/buttons when results are available */}
        {hasVerifyResults ? (
          <>
            <div className="w-full max-h-[70vh] overflow-y-auto">
              <VerificationResultView sessionData={verifySessionData} />
            </div>
            <div className="flex gap-3 mt-4">
              <Button onClick={onClose} style="link" color="secondary">
                Close
              </Button>
              <Button onClick={handleNewVerification} style="button">
                New Verification
              </Button>
            </div>
          </>
        ) : (
          <>
            <h2 className="text-xl font-bold text-gray-900 mb-2">
              {mode === 'issue' ? 'Claim Your Credential' : 'Scan to Verify'}
            </h2>

            {/* QR Code */}
            <div className="flex justify-center my-6">
              {loading ? (
                <div className="animate-spin rounded-full h-32 w-32 border-b-2 border-gray-900" />
              ) : issueStatus === 'success' ? (
                <div className="flex flex-col items-center gap-3">
                  <CheckCircleIcon className="w-20 h-20 text-green-500" />
                  <p className="text-lg font-semibold text-green-700">Credential Issued</p>
                  <p className="text-sm text-gray-500">The credential has been added to your wallet.</p>
                </div>
              ) : issueStatus === 'failed' ? (
                <div className="flex flex-col items-center gap-3">
                  <XCircleIcon className="w-20 h-20 text-red-500" />
                  <p className="text-lg font-semibold text-red-700">Issuance Failed</p>
                  <p className="text-sm text-gray-500">The credential could not be issued. Please try again.</p>
                </div>
              ) : (
                <QRCode
                  className="h-full max-h-[200px]"
                  value={credentialUrl}
                  viewBox="0 0 256 256"
                />
              )}
            </div>

            {/* Always-on in issue mode: any installed OpenID4VCI wallet can claim the offer. Verify mode keeps the isEudi gate. */}
            {(mode === 'issue' || isEudi) && !loading && !isIssueComplete && issueStatus !== 'failed' && (
              <div className="w-full mb-3">
                <Button
                  onClick={handleOpenLocalWallet}
                  style="button"
                  className="w-full bg-blue-600 hover:bg-blue-700"
                >
                  Open Local Wallet
                </Button>
                <p className="text-xs text-gray-500 mt-1">
                  Deep link to wallet app on this device
                </p>
              </div>
            )}

            {/* Web Wallet button */}
            {!loading && !isIssueComplete && issueStatus !== 'failed' && (
              <div className="w-full mb-3">
                <Button onClick={handleOpenWebWallet} style="button" className="w-full">
                  <span className="flex items-center justify-center gap-2">
                    <ArrowTopRightOnSquareIcon className="w-4 h-4" />
                    Open Web Wallet
                  </span>
                </Button>
              </div>
            )}

            {/* Popup blocked fallback */}
            {popupBlocked && webWalletUrl && (
              <div className="w-full mb-3 p-3 bg-yellow-50 border border-yellow-200 rounded-lg">
                <p className="text-sm text-yellow-800 mb-1">
                  Popup was blocked by your browser.
                </p>
                <a
                  href={webWalletUrl}
                  target="_blank"
                  rel="noopener noreferrer"
                  className="text-sm text-blue-600 underline"
                >
                  Click here to open the wallet
                </a>
              </div>
            )}

            {/* Copy URL */}
            {!loading && !isIssueComplete && issueStatus !== 'failed' && (
              <button
                onClick={handleCopy}
                className="flex items-center gap-1 text-sm text-gray-500 hover:text-gray-700 mb-4"
              >
                <ClipboardIcon className="w-4 h-4" />
                {copied ? 'Copied!' : 'Copy URL'}
              </button>
            )}

            {/* Issue mode: status indicators */}
            {mode === 'issue' && issueStatus === 'waiting' && !loading && (
              <div className="flex items-center gap-2 text-sm text-gray-600 mb-4">
                <div className="animate-pulse w-2 h-2 bg-blue-500 rounded-full" />
                Waiting for credential acceptance...
              </div>
            )}
            {mode === 'issue' && issueStatus === 'popup-closed' && (
              <p className="text-sm text-gray-500 mb-4">
                Wallet closed — credential should be in your wallet.
              </p>
            )}

            {/* Verify mode: polling status (only when no results yet) */}
            {mode === 'verify' && verifyStatus === 'waiting' && (
              <div className="flex items-center gap-2 text-sm text-gray-600 mb-4">
                <div className="animate-pulse w-2 h-2 bg-blue-500 rounded-full" />
                Waiting for wallet response...
              </div>
            )}
            {mode === 'verify' && verifyStatus === 'failed' && !hasVerifyResults && (
              <div className="text-sm text-red-600 mb-4">
                Verification failed or was rejected.
              </div>
            )}

            {/* Done / Close button */}
            {mode === 'issue' && !loading && (
              <Button onClick={onClose} style="link" color="secondary">
                {isIssueComplete ? 'Close' : 'Done'}
              </Button>
            )}
          </>
        )}
      </div>
    </Modal>
  );
}
