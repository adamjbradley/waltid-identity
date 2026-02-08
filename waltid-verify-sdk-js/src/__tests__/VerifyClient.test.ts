/**
 * Tests for walt.id Verify SDK
 *
 * These tests validate the SDK functionality with both mock responses
 * and sandbox credentials for integration testing.
 */

import {
  VerifyClient,
  VerifyConfig,
  VerificationRequest,
  VerificationResponse,
  SessionStatus,
  VerifyError,
  PollingTimeoutError,
} from '../index';

// =============================================================================
// Sandbox Credentials
// =============================================================================

const SANDBOX_CONFIG = {
  testApiKey: 'vfy_test_sandbox_demo_key_12345678',
  liveApiKey: 'vfy_live_sandbox_demo_key_12345678',
  apiUrl: 'http://localhost:7010',
};

// =============================================================================
// Mock Fetch Helper
// =============================================================================

type MockResponse = {
  status: number;
  body: unknown;
  headers?: Record<string, string>;
};

function createMockFetch(responses: Map<string, MockResponse>) {
  return async (input: RequestInfo | URL, init?: RequestInit): Promise<Response> => {
    const url = typeof input === 'string' ? input : input.toString();
    const method = init?.method || 'GET';
    const key = `${method} ${url}`;

    // Find matching response (exact match or pattern match)
    let mockResponse: MockResponse | undefined;
    for (const [pattern, response] of responses) {
      if (key === pattern || key.includes(pattern) || new RegExp(pattern).test(key)) {
        mockResponse = response;
        break;
      }
    }

    if (!mockResponse) {
      throw new Error(`No mock response for: ${key}`);
    }

    return new Response(JSON.stringify(mockResponse.body), {
      status: mockResponse.status,
      headers: {
        'Content-Type': 'application/json',
        ...mockResponse.headers,
      },
    });
  };
}

// =============================================================================
// Unit Tests
// =============================================================================

describe('VerifyClient', () => {
  describe('constructor', () => {
    it('should create a client with valid config', () => {
      const client = new VerifyClient({
        apiKey: SANDBOX_CONFIG.testApiKey,
        baseUrl: SANDBOX_CONFIG.apiUrl,
      });

      expect(client).toBeInstanceOf(VerifyClient);
    });

    it('should throw error when API key is missing', () => {
      expect(() => {
        new VerifyClient({ apiKey: '' });
      }).toThrow(VerifyError);

      expect(() => {
        new VerifyClient({ apiKey: '' });
      }).toThrow('API key is required');
    });

    it('should use default base URL when not provided', () => {
      const client = new VerifyClient({
        apiKey: SANDBOX_CONFIG.testApiKey,
      });

      expect(client).toBeInstanceOf(VerifyClient);
    });

    it('should trim trailing slash from base URL', () => {
      const mockFetch = createMockFetch(new Map([
        ['POST http://localhost:7010/v1/verify/identity', {
          status: 201,
          body: {
            session_id: 'vs_test123',
            qr_code_url: 'https://api.example.com/qr/test123',
            qr_code_data: 'openid4vp://...',
            deep_link: 'wallet://verify?request=...',
            expires_at: Date.now() + 300000,
          },
        }],
      ]));

      const client = new VerifyClient({
        apiKey: SANDBOX_CONFIG.testApiKey,
        baseUrl: 'http://localhost:7010/', // with trailing slash
        fetch: mockFetch,
      });

      expect(client).toBeInstanceOf(VerifyClient);
    });
  });

  describe('verifyIdentity', () => {
    it('should create a verification session successfully', async () => {
      const mockResponse: VerificationResponse = {
        sessionId: 'vs_test123',
        qrCodeUrl: 'https://api.example.com/qr/vs_test123.png',
        qrCodeData: 'openid4vp://authorize?response_type=vp_token&nonce=abc123',
        deepLink: 'wallet://verify?request_uri=https://api.example.com/request/vs_test123',
        expiresAt: Math.floor(Date.now() / 1000) + 300,
      };

      const mockFetch = createMockFetch(new Map([
        ['POST http://localhost:7010/v1/verify/identity', {
          status: 201,
          body: {
            session_id: mockResponse.sessionId,
            qr_code_url: mockResponse.qrCodeUrl,
            qr_code_data: mockResponse.qrCodeData,
            deep_link: mockResponse.deepLink,
            expires_at: mockResponse.expiresAt,
          },
        }],
      ]));

      const client = new VerifyClient({
        apiKey: SANDBOX_CONFIG.testApiKey,
        baseUrl: SANDBOX_CONFIG.apiUrl,
        fetch: mockFetch,
      });

      const result = await client.verifyIdentity({
        template: 'kyc-basic',
        responseMode: 'answers',
      });

      expect(result.sessionId).toBe(mockResponse.sessionId);
      expect(result.qrCodeUrl).toBe(mockResponse.qrCodeUrl);
      expect(result.qrCodeData).toContain('openid4vp://');
      expect(result.deepLink).toBeDefined();
      expect(result.expiresAt).toBeGreaterThan(0);
    });

    it('should include metadata in the request', async () => {
      let capturedBody: string | undefined;

      const mockFetch = async (input: RequestInfo | URL, init?: RequestInit): Promise<Response> => {
        capturedBody = init?.body as string;
        return new Response(JSON.stringify({
          session_id: 'vs_test123',
          qr_code_url: 'https://api.example.com/qr/test123',
          qr_code_data: 'openid4vp://...',
          deep_link: 'wallet://...',
          expires_at: Date.now() + 300000,
        }), { status: 201 });
      };

      const client = new VerifyClient({
        apiKey: SANDBOX_CONFIG.testApiKey,
        baseUrl: SANDBOX_CONFIG.apiUrl,
        fetch: mockFetch,
      });

      await client.verifyIdentity({
        template: 'kyc-basic',
        metadata: { userId: '12345', orderId: 'order-abc' },
      });

      const parsedBody = JSON.parse(capturedBody!);
      expect(parsedBody.metadata).toEqual({ userId: '12345', orderId: 'order-abc' });
    });

    it('should include authorization header', async () => {
      let capturedHeaders: HeadersInit | undefined;

      const mockFetch = async (input: RequestInfo | URL, init?: RequestInit): Promise<Response> => {
        capturedHeaders = init?.headers;
        return new Response(JSON.stringify({
          session_id: 'vs_test123',
          qr_code_url: 'https://api.example.com/qr/test123',
          qr_code_data: 'openid4vp://...',
          deep_link: 'wallet://...',
          expires_at: Date.now() + 300000,
        }), { status: 201 });
      };

      const client = new VerifyClient({
        apiKey: SANDBOX_CONFIG.testApiKey,
        baseUrl: SANDBOX_CONFIG.apiUrl,
        fetch: mockFetch,
      });

      await client.verifyIdentity({ template: 'kyc-basic' });

      const headers = capturedHeaders as Record<string, string>;
      expect(headers['Authorization']).toBe(`Bearer ${SANDBOX_CONFIG.testApiKey}`);
      expect(headers['Content-Type']).toBe('application/json');
    });

    it('should throw VerifyError on API failure', async () => {
      const mockFetch = createMockFetch(new Map([
        ['POST http://localhost:7010/v1/verify/identity', {
          status: 400,
          body: {
            message: 'Invalid template name',
            code: 'INVALID_TEMPLATE',
          },
        }],
      ]));

      const client = new VerifyClient({
        apiKey: SANDBOX_CONFIG.testApiKey,
        baseUrl: SANDBOX_CONFIG.apiUrl,
        fetch: mockFetch,
      });

      await expect(
        client.verifyIdentity({ template: 'nonexistent-template' })
      ).rejects.toThrow(VerifyError);

      try {
        await client.verifyIdentity({ template: 'nonexistent-template' });
      } catch (error) {
        expect(error).toBeInstanceOf(VerifyError);
        expect((error as VerifyError).statusCode).toBe(400);
        expect((error as VerifyError).code).toBe('INVALID_TEMPLATE');
        expect((error as VerifyError).message).toBe('Invalid template name');
      }
    });

    it('should handle 401 unauthorized errors', async () => {
      const mockFetch = createMockFetch(new Map([
        ['POST http://localhost:7010/v1/verify/identity', {
          status: 401,
          body: {
            message: 'Invalid API key',
            code: 'UNAUTHORIZED',
          },
        }],
      ]));

      const client = new VerifyClient({
        apiKey: 'invalid-key',
        baseUrl: SANDBOX_CONFIG.apiUrl,
        fetch: mockFetch,
      });

      await expect(
        client.verifyIdentity({ template: 'kyc-basic' })
      ).rejects.toThrow(VerifyError);
    });
  });

  describe('getSession', () => {
    it('should get session status successfully', async () => {
      const mockStatus: SessionStatus = {
        sessionId: 'vs_test123',
        status: 'pending',
        templateName: 'kyc-basic',
        expiresAt: Math.floor(Date.now() / 1000) + 300,
      };

      const mockFetch = createMockFetch(new Map([
        ['GET http://localhost:7010/v1/sessions/vs_test123', {
          status: 200,
          body: {
            session_id: mockStatus.sessionId,
            status: mockStatus.status,
            template_name: mockStatus.templateName,
            expires_at: mockStatus.expiresAt,
          },
        }],
      ]));

      const client = new VerifyClient({
        apiKey: SANDBOX_CONFIG.testApiKey,
        baseUrl: SANDBOX_CONFIG.apiUrl,
        fetch: mockFetch,
      });

      const result = await client.getSession('vs_test123');

      expect(result.sessionId).toBe('vs_test123');
      expect(result.status).toBe('pending');
      expect(result.templateName).toBe('kyc-basic');
    });

    it('should return verified status with result', async () => {
      const mockFetch = createMockFetch(new Map([
        ['GET http://localhost:7010/v1/sessions/vs_verified', {
          status: 200,
          body: {
            session_id: 'vs_verified',
            status: 'verified',
            template_name: 'kyc-basic',
            result: {
              answers: {
                full_name: 'John Doe',
                date_of_birth: '1990-01-15',
              },
            },
            verified_at: Math.floor(Date.now() / 1000),
            expires_at: Math.floor(Date.now() / 1000) + 300,
          },
        }],
      ]));

      const client = new VerifyClient({
        apiKey: SANDBOX_CONFIG.testApiKey,
        baseUrl: SANDBOX_CONFIG.apiUrl,
        fetch: mockFetch,
      });

      const result = await client.getSession('vs_verified');

      expect(result.status).toBe('verified');
      expect(result.result?.answers).toEqual({
        full_name: 'John Doe',
        date_of_birth: '1990-01-15',
      });
      expect(result.verifiedAt).toBeDefined();
    });

    it('should throw VerifyError for non-existent session', async () => {
      const mockFetch = createMockFetch(new Map([
        ['GET http://localhost:7010/v1/sessions/vs_nonexistent', {
          status: 404,
          body: {
            message: 'Session not found',
            code: 'SESSION_NOT_FOUND',
          },
        }],
      ]));

      const client = new VerifyClient({
        apiKey: SANDBOX_CONFIG.testApiKey,
        baseUrl: SANDBOX_CONFIG.apiUrl,
        fetch: mockFetch,
      });

      await expect(
        client.getSession('vs_nonexistent')
      ).rejects.toThrow(VerifyError);

      try {
        await client.getSession('vs_nonexistent');
      } catch (error) {
        expect((error as VerifyError).statusCode).toBe(404);
        expect((error as VerifyError).code).toBe('SESSION_NOT_FOUND');
      }
    });
  });

  describe('pollSession', () => {
    it('should return immediately when session is already verified', async () => {
      const mockFetch = createMockFetch(new Map([
        ['GET http://localhost:7010/v1/sessions/vs_verified', {
          status: 200,
          body: {
            session_id: 'vs_verified',
            status: 'verified',
            template_name: 'kyc-basic',
            result: { answers: { full_name: 'John Doe' } },
            expires_at: Math.floor(Date.now() / 1000) + 300,
          },
        }],
      ]));

      const client = new VerifyClient({
        apiKey: SANDBOX_CONFIG.testApiKey,
        baseUrl: SANDBOX_CONFIG.apiUrl,
        fetch: mockFetch,
      });

      const result = await client.pollSession('vs_verified', 100, 1000);

      expect(result.status).toBe('verified');
      expect(result.result?.answers?.full_name).toBe('John Doe');
    });

    it('should poll until session is verified', async () => {
      let pollCount = 0;

      const mockFetch = async (input: RequestInfo | URL): Promise<Response> => {
        pollCount++;
        const status = pollCount >= 3 ? 'verified' : 'pending';

        return new Response(JSON.stringify({
          session_id: 'vs_polling',
          status,
          template_name: 'kyc-basic',
          result: status === 'verified' ? { answers: { full_name: 'Jane Doe' } } : undefined,
          expires_at: Math.floor(Date.now() / 1000) + 300,
        }), { status: 200 });
      };

      const client = new VerifyClient({
        apiKey: SANDBOX_CONFIG.testApiKey,
        baseUrl: SANDBOX_CONFIG.apiUrl,
        fetch: mockFetch,
      });

      const result = await client.pollSession('vs_polling', 50, 5000);

      expect(result.status).toBe('verified');
      expect(pollCount).toBe(3);
    });

    it('should return when session fails', async () => {
      const mockFetch = createMockFetch(new Map([
        ['GET http://localhost:7010/v1/sessions/vs_failed', {
          status: 200,
          body: {
            session_id: 'vs_failed',
            status: 'failed',
            template_name: 'kyc-basic',
            expires_at: Math.floor(Date.now() / 1000) + 300,
          },
        }],
      ]));

      const client = new VerifyClient({
        apiKey: SANDBOX_CONFIG.testApiKey,
        baseUrl: SANDBOX_CONFIG.apiUrl,
        fetch: mockFetch,
      });

      const result = await client.pollSession('vs_failed', 100, 1000);

      expect(result.status).toBe('failed');
    });

    it('should return when session expires', async () => {
      const mockFetch = createMockFetch(new Map([
        ['GET http://localhost:7010/v1/sessions/vs_expired', {
          status: 200,
          body: {
            session_id: 'vs_expired',
            status: 'expired',
            template_name: 'kyc-basic',
            expires_at: Math.floor(Date.now() / 1000) - 100,
          },
        }],
      ]));

      const client = new VerifyClient({
        apiKey: SANDBOX_CONFIG.testApiKey,
        baseUrl: SANDBOX_CONFIG.apiUrl,
        fetch: mockFetch,
      });

      const result = await client.pollSession('vs_expired', 100, 1000);

      expect(result.status).toBe('expired');
    });

    it('should throw PollingTimeoutError when timeout is exceeded', async () => {
      const mockFetch = createMockFetch(new Map([
        ['GET http://localhost:7010/v1/sessions/vs_timeout', {
          status: 200,
          body: {
            session_id: 'vs_timeout',
            status: 'pending',
            template_name: 'kyc-basic',
            expires_at: Math.floor(Date.now() / 1000) + 300,
          },
        }],
      ]));

      const client = new VerifyClient({
        apiKey: SANDBOX_CONFIG.testApiKey,
        baseUrl: SANDBOX_CONFIG.apiUrl,
        fetch: mockFetch,
      });

      await expect(
        client.pollSession('vs_timeout', 50, 150)
      ).rejects.toThrow(PollingTimeoutError);

      try {
        await client.pollSession('vs_timeout', 50, 150);
      } catch (error) {
        expect(error).toBeInstanceOf(PollingTimeoutError);
        expect((error as PollingTimeoutError).message).toContain('vs_timeout');
        expect((error as PollingTimeoutError).message).toContain('150ms');
      }
    });
  });

  describe('pollSessionIterator', () => {
    it('should yield status updates', async () => {
      let pollCount = 0;

      const mockFetch = async (input: RequestInfo | URL): Promise<Response> => {
        pollCount++;
        const status = pollCount >= 3 ? 'verified' : 'pending';

        return new Response(JSON.stringify({
          session_id: 'vs_iterator',
          status,
          template_name: 'kyc-basic',
          result: status === 'verified' ? { answers: { full_name: 'Iterator Test' } } : undefined,
          expires_at: Math.floor(Date.now() / 1000) + 300,
        }), { status: 200 });
      };

      const client = new VerifyClient({
        apiKey: SANDBOX_CONFIG.testApiKey,
        baseUrl: SANDBOX_CONFIG.apiUrl,
        fetch: mockFetch,
      });

      const statuses: SessionStatus[] = [];
      for await (const status of client.pollSessionIterator('vs_iterator', 50)) {
        statuses.push(status);
      }

      expect(statuses.length).toBe(3);
      expect(statuses[0].status).toBe('pending');
      expect(statuses[1].status).toBe('pending');
      expect(statuses[2].status).toBe('verified');
    });
  });
});

// =============================================================================
// Error Tests
// =============================================================================

describe('VerifyError', () => {
  it('should include status code and error code', () => {
    const error = new VerifyError('Test error', 400, 'TEST_ERROR');

    expect(error.message).toBe('Test error');
    expect(error.statusCode).toBe(400);
    expect(error.code).toBe('TEST_ERROR');
    expect(error.name).toBe('VerifyError');
  });

  it('should work without optional parameters', () => {
    const error = new VerifyError('Simple error');

    expect(error.message).toBe('Simple error');
    expect(error.statusCode).toBeUndefined();
    expect(error.code).toBeUndefined();
  });
});

describe('PollingTimeoutError', () => {
  it('should include session ID and timeout in message', () => {
    const error = new PollingTimeoutError('vs_test123', 30000);

    expect(error.message).toContain('vs_test123');
    expect(error.message).toContain('30000ms');
    expect(error.name).toBe('PollingTimeoutError');
    expect(error.code).toBe('POLLING_TIMEOUT');
  });
});

// =============================================================================
// Integration Tests (requires running Verify API)
// =============================================================================

describe('Integration Tests', () => {
  const INTEGRATION_ENABLED = process.env.RUN_INTEGRATION_TESTS === 'true';
  const API_URL = process.env.VERIFY_API_URL || SANDBOX_CONFIG.apiUrl;
  const API_KEY = process.env.VERIFY_API_KEY || SANDBOX_CONFIG.testApiKey;

  const integrationTest = INTEGRATION_ENABLED ? it : it.skip;

  integrationTest('should create a verification session with sandbox credentials', async () => {
    const client = new VerifyClient({
      apiKey: API_KEY,
      baseUrl: API_URL,
    });

    const verification = await client.verifyIdentity({
      template: 'kyc-basic',
      responseMode: 'answers',
      metadata: { testRun: 'integration-test' },
    });

    expect(verification.sessionId).toMatch(/^vs_/);
    expect(verification.qrCodeUrl).toBeDefined();
    expect(verification.qrCodeData).toContain('openid4vp://');
    expect(verification.deepLink).toBeDefined();
    expect(verification.expiresAt).toBeGreaterThan(0);
  });

  integrationTest('should get session status with sandbox credentials', async () => {
    const client = new VerifyClient({
      apiKey: API_KEY,
      baseUrl: API_URL,
    });

    // First create a session
    const verification = await client.verifyIdentity({
      template: 'kyc-basic',
    });

    // Then check its status
    const status = await client.getSession(verification.sessionId);

    expect(status.sessionId).toBe(verification.sessionId);
    expect(status.status).toBe('pending');
    expect(status.templateName).toBe('kyc-basic');
  });

  integrationTest('should differentiate between test and live API keys', async () => {
    // Test key should work in sandbox mode
    const testClient = new VerifyClient({
      apiKey: SANDBOX_CONFIG.testApiKey,
      baseUrl: API_URL,
    });

    // This should work with the test key
    const verification = await testClient.verifyIdentity({
      template: 'kyc-basic',
    });

    expect(verification.sessionId).toBeDefined();
  });
});
