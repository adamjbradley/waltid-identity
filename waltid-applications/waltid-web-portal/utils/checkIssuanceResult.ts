import axios from 'axios';

/**
 * Extract the issuance session ID from a credential offer URL.
 * The offer URL format is: openid-credential-offer://?credential_offer_uri=...credentialOffer?id=SESSION_ID
 */
export function getIssuanceSessionId(offerUrl: string): string | null {
  try {
    // The offer URL contains a credential_offer_uri parameter which itself has an id param
    const normalized = offerUrl.replace(/^openid-credential-offer:\/\//, 'https://dummy');
    const parsed = new URL(normalized);
    const credentialOfferUri = parsed.searchParams.get('credential_offer_uri');
    if (credentialOfferUri) {
      const offerParsed = new URL(credentialOfferUri);
      return offerParsed.searchParams.get('id');
    }
    // Fallback: look for id directly
    return parsed.searchParams.get('id');
  } catch {
    // Regex fallback
    const match = offerUrl.match(/[?&]id=([^&]+)/);
    return match ? decodeURIComponent(match[1]) : null;
  }
}

/**
 * Poll the portal's issuance status API route for session completion.
 * Returns the final status string ('SUCCESSFUL', 'UNSUCCESSFUL', etc.) or null on timeout.
 */
export async function checkIssuanceResult(
  sessionId: string,
  timeoutMs: number = 300_000 // 5 minutes
): Promise<string | null> {
  const startTime = Date.now();

  return new Promise((resolve) => {
    const poll = async () => {
      if (Date.now() - startTime > timeoutMs) {
        return resolve(null);
      }

      try {
        const response = await axios.get(`/api/issuance-status/${encodeURIComponent(sessionId)}`);
        const { status } = response.data;

        if (status === 'SUCCESSFUL') return resolve('SUCCESSFUL');
        if (status === 'UNSUCCESSFUL' || status === 'REJECTED_BY_USER' || status === 'EXPIRED') {
          return resolve(status);
        }

        // Still pending — poll again
        setTimeout(poll, 1500);
      } catch {
        setTimeout(poll, 2000);
      }
    };

    poll();
  });
}
