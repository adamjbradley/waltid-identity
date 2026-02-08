/**
 * API client for communicating with the Verify API backend.
 */

const API_BASE_URL = process.env.NEXT_PUBLIC_VERIFY_API_URL || 'http://localhost:7005';

export interface AuthResponse {
  access_token: string;
  refresh_token: string;
  token_type: string;
  expires_in: number;
  user: UserInfo;
}

export interface UserInfo {
  id: string;
  email: string;
  role: string;
  organization: OrganizationInfo;
}

export interface OrganizationInfo {
  id: string;
  name: string;
}

export interface ApiKey {
  id: string;
  keyPrefix: string;
  environment: 'live' | 'test';
  name: string | null;
  lastUsedAt: string | null;
  createdAt: string;
  revoked: boolean;
}

export interface CreateApiKeyResponse extends ApiKey {
  key: string; // Full key value - only returned on creation
}

export interface WidgetConfig {
  allowedOrigins: string[];
  availableTemplates: TemplateInfo[];
  updatedAt: string | null;
}

export interface TemplateInfo {
  id: string;
  name: string;
  displayName: string | null;
  description: string | null;
  type: string;
  isSystem: boolean;
}

export interface WidgetSnippets {
  allowedOrigins: string[];
  snippets: CodeSnippet[];
}

export interface CodeSnippet {
  language: string;
  code: string;
}

export interface UsageAnalytics {
  period: {
    today: number;
    thisWeek: number;
    thisMonth: number;
    allTime: number;
  };
  byStatus: Record<string, number>;
  byTemplate: Record<string, number>;
  byEnvironment: Record<string, number>;
}

export interface DailyUsage {
  date: string;
  count: number;
}

export interface ApiError {
  error: string;
  message: string;
}

class ApiClient {
  private accessToken: string | null = null;
  private refreshToken: string | null = null;

  constructor() {
    // Load tokens from localStorage on init (client-side only)
    if (typeof window !== 'undefined') {
      this.accessToken = localStorage.getItem('access_token');
      this.refreshToken = localStorage.getItem('refresh_token');
    }
  }

  setTokens(accessToken: string, refreshToken: string) {
    this.accessToken = accessToken;
    this.refreshToken = refreshToken;
    if (typeof window !== 'undefined') {
      localStorage.setItem('access_token', accessToken);
      localStorage.setItem('refresh_token', refreshToken);
    }
  }

  clearTokens() {
    this.accessToken = null;
    this.refreshToken = null;
    if (typeof window !== 'undefined') {
      localStorage.removeItem('access_token');
      localStorage.removeItem('refresh_token');
    }
  }

  getAccessToken(): string | null {
    return this.accessToken;
  }

  private async request<T>(
    endpoint: string,
    options: RequestInit = {},
    retry = true
  ): Promise<T> {
    const url = `${API_BASE_URL}${endpoint}`;
    const headers: HeadersInit = {
      'Content-Type': 'application/json',
      ...options.headers,
    };

    if (this.accessToken) {
      (headers as Record<string, string>)['Authorization'] = `Bearer ${this.accessToken}`;
    }

    const response = await fetch(url, {
      ...options,
      headers,
    });

    // Handle token refresh on 401
    if (response.status === 401 && this.refreshToken && retry) {
      const refreshed = await this.refreshAccessToken();
      if (refreshed) {
        return this.request<T>(endpoint, options, false);
      }
    }

    if (!response.ok) {
      const error = await response.json().catch(() => ({ error: 'unknown', message: 'An error occurred' }));
      throw new ApiError(error.error || 'error', error.message || 'Request failed');
    }

    // Handle empty responses (204 No Content)
    if (response.status === 204) {
      return undefined as T;
    }

    return response.json();
  }

  private async refreshAccessToken(): Promise<boolean> {
    try {
      const response = await fetch(`${API_BASE_URL}/portal/auth/refresh`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ refresh_token: this.refreshToken }),
      });

      if (!response.ok) {
        this.clearTokens();
        return false;
      }

      const data: AuthResponse = await response.json();
      this.setTokens(data.access_token, data.refresh_token);
      return true;
    } catch {
      this.clearTokens();
      return false;
    }
  }

  // Auth endpoints
  async signup(email: string, password: string, organizationName: string): Promise<AuthResponse> {
    const response = await fetch(`${API_BASE_URL}/portal/auth/signup`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ email, password, organization_name: organizationName }),
    });

    if (!response.ok) {
      const error = await response.json().catch(() => ({ error: 'unknown', message: 'Signup failed' }));
      throw new ApiClientError(error.error || 'signup_failed', error.message || 'Signup failed');
    }

    const data: AuthResponse = await response.json();
    this.setTokens(data.access_token, data.refresh_token);
    return data;
  }

  async login(email: string, password: string): Promise<AuthResponse> {
    const response = await fetch(`${API_BASE_URL}/portal/auth/login`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ email, password }),
    });

    if (!response.ok) {
      const error = await response.json().catch(() => ({ error: 'unknown', message: 'Login failed' }));
      throw new ApiClientError(error.error || 'login_failed', error.message || 'Login failed');
    }

    const data: AuthResponse = await response.json();
    this.setTokens(data.access_token, data.refresh_token);
    return data;
  }

  logout() {
    this.clearTokens();
  }

  // API Keys endpoints
  async getApiKeys(): Promise<ApiKey[]> {
    return this.request<ApiKey[]>('/portal/api-keys');
  }

  async createApiKey(name?: string, environment: 'live' | 'test' = 'test'): Promise<CreateApiKeyResponse> {
    return this.request<CreateApiKeyResponse>('/portal/api-keys', {
      method: 'POST',
      body: JSON.stringify({ name, environment }),
    });
  }

  async revokeApiKey(id: string): Promise<void> {
    return this.request<void>(`/portal/api-keys/${id}`, {
      method: 'DELETE',
    });
  }

  // Widget config endpoints
  async getWidgetConfig(): Promise<WidgetConfig> {
    return this.request<WidgetConfig>('/portal/widget/config');
  }

  async updateWidgetConfig(allowedOrigins: string[]): Promise<WidgetConfig> {
    return this.request<WidgetConfig>('/portal/widget/config', {
      method: 'PUT',
      body: JSON.stringify({ allowedOrigins }),
    });
  }

  async getWidgetSnippets(): Promise<WidgetSnippets> {
    return this.request<WidgetSnippets>('/portal/widget/config/snippets');
  }

  // Usage analytics endpoints
  async getUsageAnalytics(params?: {
    startDate?: string;
    endDate?: string;
    environment?: 'live' | 'test';
  }): Promise<UsageAnalytics> {
    const searchParams = new URLSearchParams();
    if (params?.startDate) searchParams.set('start_date', params.startDate);
    if (params?.endDate) searchParams.set('end_date', params.endDate);
    if (params?.environment) searchParams.set('environment', params.environment);

    const query = searchParams.toString();
    return this.request<UsageAnalytics>(`/portal/usage${query ? `?${query}` : ''}`);
  }

  async getDailyUsage(days = 30, environment?: 'live' | 'test'): Promise<DailyUsage[]> {
    const searchParams = new URLSearchParams({ days: days.toString() });
    if (environment) searchParams.set('environment', environment);

    return this.request<DailyUsage[]>(`/portal/usage/daily?${searchParams.toString()}`);
  }
}

export class ApiClientError extends Error {
  constructor(
    public code: string,
    message: string
  ) {
    super(message);
    this.name = 'ApiClientError';
  }
}

// Singleton instance
export const apiClient = new ApiClient();
