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
    isApi2: boolean = false
): Promise<boolean> {
    const endpoint = isApi2
        ? `${verifierURL}/verification-session/${encodeURIComponent(sessionId)}`
        : `${verifierURL}/openid4vc/session/${encodeURIComponent(sessionId)}`;

    return new Promise((resolve) => {
        const poll = async () => {
            try {
                const response = await axios.get(endpoint, {
                    headers: { 'accept': 'application/json' }
                });

                const data = response.data;

                // API2 uses 'state' field, legacy uses 'verificationResult'
                if (isApi2) {
                    if (data.state === 'SUCCESS') {
                        return resolve(true);
                    } else if (data.state === 'FAILED') {
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
            } catch (error) {
                console.error("Error fetching session:", error);
                return resolve(false);
            }
        };

        poll();
    });
}
