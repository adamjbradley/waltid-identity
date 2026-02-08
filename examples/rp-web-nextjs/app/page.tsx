'use client';

import { useState, useEffect, useCallback } from 'react';
import { QRCodeSVG } from 'qrcode.react';

type VerificationStatus = 'idle' | 'loading' | 'pending' | 'verified' | 'failed' | 'expired';

interface VerificationSession {
  sessionId: string;
  qrCodeData: string;
  deepLink: string;
  expiresAt: string;
}

interface CredentialInfo {
  format: string | null;
  vct: string | null;
  doctype: string | null;
  issuer: string | null;
  disclosedClaims: Record<string, string>;
}

interface VerificationResult {
  answers?: Record<string, unknown>;
  credentials?: CredentialInfo[];
  error?: string;
}

export default function CheckoutPage() {
  const [session, setSession] = useState<VerificationSession | null>(null);
  const [status, setStatus] = useState<VerificationStatus>('idle');
  const [result, setResult] = useState<VerificationResult | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [isMobile, setIsMobile] = useState(false);

  // Detect mobile device for same-device flow
  useEffect(() => {
    const checkMobile = () => {
      setIsMobile(/iPhone|iPad|iPod|Android/i.test(navigator.userAgent));
    };
    checkMobile();
  }, []);

  const pollStatus = useCallback(async (sessionId: string) => {
    const maxAttempts = 60; // 2 minutes with 2-second intervals
    let attempts = 0;

    const poll = async () => {
      if (attempts >= maxAttempts) {
        setStatus('expired');
        setError('Verification session expired');
        return;
      }

      try {
        const response = await fetch(`/api/sessions/${sessionId}`);
        if (!response.ok) {
          throw new Error('Failed to fetch session status');
        }

        const data = await response.json();

        if (data.status === 'verified') {
          setStatus('verified');
          setResult(data.result);
          return;
        } else if (data.status === 'failed') {
          setStatus('failed');
          setError(data.error || 'Verification failed');
          return;
        } else if (data.status === 'expired') {
          setStatus('expired');
          setError('Verification session expired');
          return;
        }

        // Continue polling if still pending
        attempts++;
        setTimeout(poll, 2000);
      } catch (err) {
        console.error('Polling error:', err);
        attempts++;
        setTimeout(poll, 2000);
      }
    };

    poll();
  }, []);

  const startVerification = async () => {
    setStatus('loading');
    setError(null);
    setResult(null);

    try {
      const response = await fetch('/api/verify', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ template: 'age_check' }),
      });

      if (!response.ok) {
        const errorData = await response.json();
        throw new Error(errorData.error || 'Failed to start verification');
      }

      const data: VerificationSession = await response.json();
      setSession(data);
      setStatus('pending');

      // Start polling for status updates
      pollStatus(data.sessionId);
    } catch (err) {
      setStatus('failed');
      setError(err instanceof Error ? err.message : 'Unknown error');
    }
  };

  const resetVerification = () => {
    setSession(null);
    setStatus('idle');
    setResult(null);
    setError(null);
  };

  return (
    <main className="min-h-screen p-8">
      <div className="max-w-2xl mx-auto">
        {/* Header */}
        <div className="text-center mb-8">
          <h1 className="text-3xl font-bold text-gray-900 mb-2">
            Age Verification Demo
          </h1>
          <p className="text-gray-600">
            E-commerce checkout example using walt.id Verify API
          </p>
        </div>

        {/* Mock Product */}
        <div className="bg-white rounded-lg shadow-md p-6 mb-6">
          <div className="flex items-center space-x-4">
            <div className="w-24 h-24 bg-gray-200 rounded-lg flex items-center justify-center">
              <span className="text-4xl">🍷</span>
            </div>
            <div className="flex-1">
              <h2 className="text-xl font-semibold">Premium Wine Selection</h2>
              <p className="text-gray-500">Age-restricted product</p>
              <p className="text-2xl font-bold text-blue-600 mt-2">$49.99</p>
            </div>
          </div>
        </div>

        {/* Verification Card */}
        <div className="bg-white rounded-lg shadow-md p-6">
          {/* Idle State */}
          {status === 'idle' && (
            <div className="text-center">
              <div className="mb-4">
                <span className="text-6xl">🔐</span>
              </div>
              <h3 className="text-xl font-semibold mb-2">Age Verification Required</h3>
              <p className="text-gray-600 mb-6">
                This product requires age verification before purchase.
                Verify your age using your digital wallet.
              </p>
              <button
                onClick={startVerification}
                className="bg-blue-600 hover:bg-blue-700 text-white font-semibold px-8 py-3 rounded-lg transition-colors"
              >
                Verify Age to Continue
              </button>
            </div>
          )}

          {/* Loading State */}
          {status === 'loading' && (
            <div className="text-center py-8">
              <div className="animate-spin rounded-full h-12 w-12 border-b-2 border-blue-600 mx-auto mb-4"></div>
              <p className="text-gray-600">Starting verification...</p>
            </div>
          )}

          {/* Pending State - QR Code */}
          {status === 'pending' && session && (
            <div className="text-center">
              <h3 className="text-xl font-semibold mb-4">Scan to Verify</h3>

              {!isMobile && (
                <>
                  <div className="bg-white p-4 rounded-lg inline-block border-2 border-gray-100 mb-4">
                    <QRCodeSVG
                      value={session.qrCodeData}
                      size={256}
                      level="M"
                      includeMargin={true}
                    />
                  </div>
                  <p className="text-gray-500 text-sm mb-4">
                    Scan this QR code with your wallet app
                  </p>
                </>
              )}

              {/* Same-device flow for mobile */}
              {isMobile && (
                <div className="mb-6">
                  <a
                    href={session.deepLink}
                    className="inline-block bg-blue-600 hover:bg-blue-700 text-white font-semibold px-8 py-3 rounded-lg transition-colors"
                  >
                    Open in Wallet App
                  </a>
                </div>
              )}

              {/* Deep link for desktop too */}
              {!isMobile && (
                <div className="border-t pt-4">
                  <p className="text-gray-500 text-sm mb-2">Or on this device:</p>
                  <a
                    href={session.deepLink}
                    className="text-blue-600 hover:text-blue-700 text-sm underline"
                  >
                    Open in wallet app
                  </a>
                </div>
              )}

              <div className="mt-6 text-sm text-gray-400">
                <p>Waiting for verification...</p>
                <div className="flex items-center justify-center mt-2">
                  <div className="animate-pulse w-2 h-2 bg-blue-400 rounded-full mr-1"></div>
                  <div className="animate-pulse w-2 h-2 bg-blue-400 rounded-full mr-1" style={{ animationDelay: '0.2s' }}></div>
                  <div className="animate-pulse w-2 h-2 bg-blue-400 rounded-full" style={{ animationDelay: '0.4s' }}></div>
                </div>
              </div>

              <button
                onClick={resetVerification}
                className="mt-6 text-gray-500 hover:text-gray-700 text-sm underline"
              >
                Cancel
              </button>
            </div>
          )}

          {/* Verified State */}
          {status === 'verified' && (
            <div className="text-center">
              <div className="mb-4">
                <span className="text-6xl">✅</span>
              </div>
              <h3 className="text-xl font-semibold text-green-700 mb-2">
                Age Verified Successfully
              </h3>
              <p className="text-gray-600 mb-4">
                Your age has been verified. You can now complete your purchase.
              </p>

              {result?.credentials && result.credentials.length > 0 && (
                <div className="bg-green-50 rounded-lg p-4 mb-6 text-left">
                  <h4 className="font-medium text-green-800 mb-3">Shared Credentials:</h4>
                  {result.credentials.map((cred, idx) => (
                    <div key={idx} className={`${idx > 0 ? 'mt-4 pt-4 border-t border-green-200' : ''}`}>
                      {/* Credential Header */}
                      <div className="mb-2">
                        <span className="inline-block bg-green-200 text-green-800 text-xs font-semibold px-2 py-1 rounded">
                          {cred.format || 'Unknown format'}
                        </span>
                        {cred.vct && (
                          <span className="ml-2 text-xs text-green-600">
                            {cred.vct}
                          </span>
                        )}
                        {cred.doctype && (
                          <span className="ml-2 text-xs text-green-600">
                            {cred.doctype}
                          </span>
                        )}
                      </div>

                      {/* Issuer */}
                      {cred.issuer && (
                        <p className="text-xs text-green-600 mb-2 truncate" title={cred.issuer}>
                          Issuer: {cred.issuer.length > 50 ? `${cred.issuer.substring(0, 50)}...` : cred.issuer}
                        </p>
                      )}

                      {/* Disclosed Claims */}
                      <div className="bg-white rounded p-3 border border-green-200">
                        <h5 className="text-xs font-semibold text-green-700 mb-2">Disclosed Claims:</h5>
                        <ul className="text-sm text-green-700 space-y-1">
                          {Object.entries(cred.disclosedClaims).map(([key, value]) => (
                            <li key={key} className="flex justify-between">
                              <span className="font-medium">{key}:</span>
                              <span className="text-green-600 ml-2 truncate max-w-[200px]" title={String(value)}>
                                {String(value).length > 30 ? `${String(value).substring(0, 30)}...` : String(value)}
                              </span>
                            </li>
                          ))}
                        </ul>
                      </div>
                    </div>
                  ))}
                </div>
              )}

              <button
                className="bg-green-600 hover:bg-green-700 text-white font-semibold px-8 py-3 rounded-lg transition-colors"
              >
                Complete Purchase
              </button>

              <button
                onClick={resetVerification}
                className="block mx-auto mt-4 text-gray-500 hover:text-gray-700 text-sm underline"
              >
                Start Over
              </button>
            </div>
          )}

          {/* Failed State */}
          {(status === 'failed' || status === 'expired') && (
            <div className="text-center">
              <div className="mb-4">
                <span className="text-6xl">{status === 'expired' ? '⏰' : '❌'}</span>
              </div>
              <h3 className="text-xl font-semibold text-red-700 mb-2">
                {status === 'expired' ? 'Verification Expired' : 'Verification Failed'}
              </h3>
              <p className="text-gray-600 mb-6">
                {error || 'Unable to verify your age. Please try again.'}
              </p>
              <button
                onClick={resetVerification}
                className="bg-blue-600 hover:bg-blue-700 text-white font-semibold px-8 py-3 rounded-lg transition-colors"
              >
                Try Again
              </button>
            </div>
          )}
        </div>

        {/* Footer */}
        <div className="text-center mt-8 text-sm text-gray-500">
          <p>Powered by walt.id Verify API</p>
        </div>
      </div>
    </main>
  );
}
