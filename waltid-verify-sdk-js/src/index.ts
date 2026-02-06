/**
 * walt.id Verify SDK for TypeScript/JavaScript
 *
 * A simple SDK for integrating identity verification using verifiable credentials.
 */

// =============================================================================
// Configuration Types
// =============================================================================

/**
 * Configuration for the VerifyClient
 */
export interface VerifyConfig {
  /** API key for authentication */
  apiKey: string;
  /** Base URL of the Verify API (defaults to https://verify.example.com) */
  baseUrl?: string;
  /** Custom fetch implementation (useful for testing or custom HTTP handling) */
  fetch?: typeof fetch;
}

// =============================================================================
// Request Types
// =============================================================================

/**
 * Request to initiate identity verification
 */
export interface VerificationRequest {
  /** Template name defining which credentials to request */
  template: string;
  /** Response mode: 'answers' for mapped values, 'raw_credentials' for full credential data */
  responseMode?: 'answers' | 'raw_credentials';
  /** URI to redirect the user after verification completes */
  redirectUri?: string;
  /** Custom metadata to associate with the session */
  metadata?: Record<string, string>;
}

// =============================================================================
// Response Types
// =============================================================================

/**
 * Response from initiating a verification request
 */
export interface VerificationResponse {
  /** Unique session identifier */
  sessionId: string;
  /** URL to a QR code image for cross-device flow */
  qrCodeUrl: string;
  /** Raw QR code data (OpenID4VP authorization request) */
  qrCodeData: string;
  /** Deep link for same-device flow */
  deepLink: string;
  /** Unix timestamp when the session expires */
  expiresAt: number;
}

/**
 * Disclosed claims from a credential
 */
export interface DisclosedCredential {
  /** Credential format (e.g., 'dc+sd-jwt', 'mso_mdoc') */
  format: string;
  /** Verifiable Credential Type (for SD-JWT credentials) */
  vct?: string;
  /** Document type (for mDoc credentials) */
  doctype?: string;
  /** Map of disclosed claim names to values */
  disclosedClaims: Record<string, string>;
}

/**
 * Verification result containing the verified data
 */
export interface VerificationResult {
  /** Mapped answers when response_mode is 'answers' */
  answers?: Record<string, string>;
  /** Full credential data when response_mode is 'raw_credentials' */
  credentials?: DisclosedCredential[];
}

/**
 * Session status representing the current state of a verification session
 */
export interface SessionStatus {
  /** Unique session identifier */
  sessionId: string;
  /** Current status of the session */
  status: 'pending' | 'verified' | 'failed' | 'expired';
  /** Name of the template used for this session */
  templateName: string;
  /** Verification result (present when status is 'verified') */
  result?: VerificationResult;
  /** Unix timestamp when verification completed */
  verifiedAt?: number;
  /** Custom metadata associated with the session */
  metadata?: Record<string, string>;
  /** Unix timestamp when the session expires */
  expiresAt: number;
}

// =============================================================================
// Error Types
// =============================================================================

/**
 * Error thrown by the Verify SDK
 */
export class VerifyError extends Error {
  /** HTTP status code if applicable */
  public readonly statusCode?: number;
  /** Error code from the API */
  public readonly code?: string;

  constructor(message: string, statusCode?: number, code?: string) {
    super(message);
    this.name = 'VerifyError';
    this.statusCode = statusCode;
    this.code = code;
  }
}

/**
 * Error thrown when polling times out
 */
export class PollingTimeoutError extends VerifyError {
  constructor(sessionId: string, timeoutMs: number) {
    super(
      `Polling timed out after ${timeoutMs}ms for session ${sessionId}`,
      undefined,
      'POLLING_TIMEOUT'
    );
    this.name = 'PollingTimeoutError';
  }
}

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
export class VerifyClient {
  private readonly apiKey: string;
  private readonly baseUrl: string;
  private readonly fetchFn: typeof fetch;

  constructor(config: VerifyConfig) {
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
  async verifyIdentity(request: VerificationRequest): Promise<VerificationResponse> {
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
      let errorCode: string | undefined;

      try {
        const errorJson = JSON.parse(errorBody);
        errorMessage = errorJson.message || errorMessage;
        errorCode = errorJson.code;
      } catch {
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
  async getSession(sessionId: string): Promise<SessionStatus> {
    const response = await this.fetchFn(`${this.baseUrl}/v1/sessions/${sessionId}`, {
      headers: {
        'Authorization': `Bearer ${this.apiKey}`,
      },
    });

    if (!response.ok) {
      const errorBody = await response.text();
      let errorMessage = `Failed to get session: ${response.status}`;
      let errorCode: string | undefined;

      try {
        const errorJson = JSON.parse(errorBody);
        errorMessage = errorJson.message || errorMessage;
        errorCode = errorJson.code;
      } catch {
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
  async pollSession(
    sessionId: string,
    intervalMs = 2000,
    timeoutMs = 300000
  ): Promise<SessionStatus> {
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
  async *pollSessionIterator(
    sessionId: string,
    intervalMs = 2000
  ): AsyncGenerator<SessionStatus, void, unknown> {
    while (true) {
      const status = await this.getSession(sessionId);
      yield status;

      if (status.status !== 'pending') {
        return;
      }

      await this.sleep(intervalMs);
    }
  }

  private mapSessionResponse(data: Record<string, unknown>): SessionStatus {
    return {
      sessionId: data.session_id as string,
      status: data.status as SessionStatus['status'],
      templateName: data.template_name as string,
      result: data.result as VerificationResult | undefined,
      verifiedAt: data.verified_at as number | undefined,
      metadata: data.metadata as Record<string, string> | undefined,
      expiresAt: data.expires_at as number,
    };
  }

  private sleep(ms: number): Promise<void> {
    return new Promise(resolve => setTimeout(resolve, ms));
  }
}

export default VerifyClient;
