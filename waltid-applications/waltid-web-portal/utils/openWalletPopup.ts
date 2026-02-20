import {isMobileDevice} from "@/utils/deviceDetection";

/**
 * Build a wallet URL from the request URL (same logic as sendToWebWallet but returns the URL).
 */
export function buildWalletUrl(
  walletUrl: string,
  path: string,
  requestUrl: string,
  metadata?: Record<string, string>
): string {
  const request = requestUrl.replaceAll("\n", "").trim();
  let url = `${walletUrl}/${path}` + request.substring(request.indexOf('?'));
  if (metadata && Object.keys(metadata).length > 0) {
    const params = new URLSearchParams(metadata);
    url += '&' + params.toString();
  }
  return url;
}

export type PopupResult = {
  status: 'opened' | 'blocked' | 'redirected';
  popup: Window | null;
};

/**
 * Open the wallet in a popup (desktop) or redirect (mobile).
 * Returns the popup window reference so callers can track when it closes.
 */
export function openWalletPopup(
  walletUrl: string,
  path: string,
  requestUrl: string,
  metadata?: Record<string, string>,
  returnUrl?: string
): PopupResult {
  const url = buildWalletUrl(walletUrl, path, requestUrl, metadata);

  if (isMobileDevice()) {
    const redirectParam = returnUrl
      ? `&redirect_uri=${encodeURIComponent(returnUrl)}`
      : '';
    window.location.href = url + redirectParam;
    return { status: 'redirected', popup: null };
  }

  const popup = window.open(url, 'wallet', 'width=480,height=720,scrollbars=yes,resizable=yes');
  if (!popup || popup.closed) {
    return { status: 'blocked', popup: null };
  }
  return { status: 'opened', popup };
}
