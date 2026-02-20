import axios from 'axios';

export function getStateFromUrl(url: string) {
    try {
        const normalizedUrl = url.replace(/^openid4vp:/, 'https:').replace(/^mdoc-openid4vp:/, 'https:');
        const parsedUrl = new URL(normalizedUrl);
        return parsedUrl.searchParams.get('state');
    } catch (e) {
        const stateMatch = url.match(/[?&]state=([^&]+)/);
        return stateMatch ? decodeURIComponent(stateMatch[1]) : null;
    }
}

export async function checkVerificationResult(
    verifierURL: string,
    sessionId: string,
    isApi2: boolean = false,
    timeoutMs: number = 300_000
): Promise<boolean> {
    const startTime = Date.now();
    const endpoint = isApi2
        ? `${verifierURL}/verification-session/${encodeURIComponent(sessionId)}/info`
        : `${verifierURL}/openid4vc/session/${encodeURIComponent(sessionId)}`;

    return new Promise((resolve) => {
        const poll = async () => {
            try {
                const response = await axios.get(endpoint, {
                    headers: { 'accept': 'application/json' }
                });

                const data = response.data;

                // API2 uses 'status' field from /info endpoint, legacy uses 'verificationResult'
                if (isApi2) {
                    if (data.status === 'SUCCESSFUL' || data.status === 'SUCCESS') {
                        return resolve(true);
                    } else if (data.status === 'UNSUCCESSFUL' || data.status === 'FAILED') {
                        return resolve(false);
                    }
                } else {
                    if (data.verificationResult === true) {
                        return resolve(true);
                    } else if (data.verificationResult === false) {
                        return resolve(false);
                    }
                }

                setTimeout(poll, 1000);
            } catch (error: any) {
                // On network error or 4xx/5xx, retry instead of failing
                // (the session may not be ready yet)
                if (Date.now() - startTime > timeoutMs) {
                    return resolve(false);
                }
                setTimeout(poll, 2000);
            }
        };

        poll();
    });
}
