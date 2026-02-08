"use strict";
/**
 * walt.id Verify SDK for TypeScript/JavaScript
 *
 * A simple SDK for integrating identity verification using verifiable credentials.
 */
Object.defineProperty(exports, "__esModule", { value: true });
exports.VerifyClient = exports.PollingTimeoutError = exports.VerifyError = void 0;
// =============================================================================
// Error Types
// =============================================================================
/**
 * Error thrown by the Verify SDK
 */
class VerifyError extends Error {
    constructor(message, statusCode, code) {
        super(message);
        this.name = 'VerifyError';
        this.statusCode = statusCode;
        this.code = code;
    }
}
exports.VerifyError = VerifyError;
/**
 * Error thrown when polling times out
 */
class PollingTimeoutError extends VerifyError {
    constructor(sessionId, timeoutMs) {
        super(`Polling timed out after ${timeoutMs}ms for session ${sessionId}`, undefined, 'POLLING_TIMEOUT');
        this.name = 'PollingTimeoutError';
    }
}
exports.PollingTimeoutError = PollingTimeoutError;
// =============================================================================
// Client Implementation
// =============================================================================
/**
 * Client for the walt.id Verify API
 *
 * @example
 * ```typescript
 * const client = new VerifyClient({
 *   apiKey: 'your-api-key',
 *   baseUrl: 'https://verify.yourdomain.com'
 * });
 *
 * // Start verification
 * const verification = await client.verifyIdentity({
 *   template: 'kyc-basic',
 *   responseMode: 'answers'
 * });
 *
 * // Display QR code to user...
 * console.log('Scan QR code:', verification.qrCodeUrl);
 *
 * // Poll for result
 * const result = await client.pollSession(verification.sessionId);
 * if (result.status === 'verified') {
 *   console.log('Verified!', result.result?.answers);
 * }
 * ```
 */
class VerifyClient {
    constructor(config) {
        if (!config.apiKey) {
            throw new VerifyError('API key is required', undefined, 'MISSING_API_KEY');
        }
        this.apiKey = config.apiKey;
        this.baseUrl = (config.baseUrl || 'https://verify.example.com').replace(/\/$/, '');
        this.fetchFn = config.fetch || fetch;
    }
    /**
     * Initiate an identity verification request
     *
     * @param request - The verification request parameters
     * @returns Promise resolving to verification response with session details
     * @throws {VerifyError} When the API returns an error
     *
     * @example
     * ```typescript
     * const verification = await client.verifyIdentity({
     *   template: 'kyc-basic',
     *   responseMode: 'answers',
     *   metadata: { userId: '12345' }
     * });
     * ```
     */
    async verifyIdentity(request) {
        const response = await this.fetchFn(`${this.baseUrl}/v1/verify/identity`, {
            method: 'POST',
            headers: {
                'Authorization': `Bearer ${this.apiKey}`,
                'Content-Type': 'application/json',
            },
            body: JSON.stringify({
                template: request.template,
                response_mode: request.responseMode,
                redirect_uri: request.redirectUri,
                metadata: request.metadata,
            }),
        });
        if (!response.ok) {
            const errorBody = await response.text();
            let errorMessage = `Verification request failed: ${response.status}`;
            let errorCode;
            try {
                const errorJson = JSON.parse(errorBody);
                errorMessage = errorJson.message || errorMessage;
                errorCode = errorJson.code;
            }
            catch {
                // Use default error message
            }
            throw new VerifyError(errorMessage, response.status, errorCode);
        }
        const data = await response.json();
        return {
            sessionId: data.session_id,
            qrCodeUrl: data.qr_code_url,
            qrCodeData: data.qr_code_data,
            deepLink: data.deep_link,
            expiresAt: data.expires_at,
        };
    }
    /**
     * Get the current status of a verification session
     *
     * @param sessionId - The session ID to query
     * @returns Promise resolving to the session status
     * @throws {VerifyError} When the API returns an error
     *
     * @example
     * ```typescript
     * const status = await client.getSession('session-123');
     * if (status.status === 'verified') {
     *   console.log('Name:', status.result?.answers?.full_name);
     * }
     * ```
     */
    async getSession(sessionId) {
        const response = await this.fetchFn(`${this.baseUrl}/v1/sessions/${sessionId}`, {
            headers: {
                'Authorization': `Bearer ${this.apiKey}`,
            },
        });
        if (!response.ok) {
            const errorBody = await response.text();
            let errorMessage = `Failed to get session: ${response.status}`;
            let errorCode;
            try {
                const errorJson = JSON.parse(errorBody);
                errorMessage = errorJson.message || errorMessage;
                errorCode = errorJson.code;
            }
            catch {
                // Use default error message
            }
            throw new VerifyError(errorMessage, response.status, errorCode);
        }
        const data = await response.json();
        return this.mapSessionResponse(data);
    }
    /**
     * Poll a session until it reaches a terminal state (verified, failed, or expired)
     *
     * @param sessionId - The session ID to poll
     * @param intervalMs - Polling interval in milliseconds (default: 2000)
     * @param timeoutMs - Maximum time to poll in milliseconds (default: 300000 = 5 minutes)
     * @returns Promise resolving to the final session status
     * @throws {PollingTimeoutError} When polling exceeds the timeout
     * @throws {VerifyError} When the API returns an error
     *
     * @example
     * ```typescript
     * try {
     *   const status = await client.pollSession('session-123', 2000, 60000);
     *   console.log('Final status:', status.status);
     * } catch (error) {
     *   if (error instanceof PollingTimeoutError) {
     *     console.log('User did not complete verification in time');
     *   }
     * }
     * ```
     */
    async pollSession(sessionId, intervalMs = 2000, timeoutMs = 300000) {
        const start = Date.now();
        while (Date.now() - start < timeoutMs) {
            const status = await this.getSession(sessionId);
            if (status.status !== 'pending') {
                return status;
            }
            await this.sleep(intervalMs);
        }
        throw new PollingTimeoutError(sessionId, timeoutMs);
    }
    /**
     * Create an async iterator for polling session status
     *
     * @param sessionId - The session ID to poll
     * @param intervalMs - Polling interval in milliseconds (default: 2000)
     * @yields SessionStatus on each poll
     *
     * @example
     * ```typescript
     * for await (const status of client.pollSessionIterator('session-123')) {
     *   console.log('Current status:', status.status);
     *   if (status.status !== 'pending') break;
     * }
     * ```
     */
    async *pollSessionIterator(sessionId, intervalMs = 2000) {
        while (true) {
            const status = await this.getSession(sessionId);
            yield status;
            if (status.status !== 'pending') {
                return;
            }
            await this.sleep(intervalMs);
        }
    }
    mapSessionResponse(data) {
        return {
            sessionId: data.session_id,
            status: data.status,
            templateName: data.template_name,
            result: data.result,
            verifiedAt: data.verified_at,
            metadata: data.metadata,
            expiresAt: data.expires_at,
        };
    }
    sleep(ms) {
        return new Promise(resolve => setTimeout(resolve, ms));
    }
}
exports.VerifyClient = VerifyClient;
exports.default = VerifyClient;
//# sourceMappingURL=index.js.map