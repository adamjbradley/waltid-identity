import { type APIRequestContext, type Page } from '@playwright/test';

// ── Environment URLs ──────────────────────────────────────────────
export const PORTAL_URL = process.env.PORTAL_BASE_URL || 'http://localhost:7102';
export const WALLET_URL = process.env.WALLET_BASE_URL || 'http://localhost:7101';
export const ISSUER_API = process.env.ISSUER_API_URL || 'http://localhost:7002';
export const VERIFIER_API2 = process.env.VERIFIER_API2_URL || 'http://localhost:7004';
export const WALLET_API = process.env.WALLET_API_URL || 'http://localhost:7001';

// ── Test credentials ──────────────────────────────────────────────
export const TEST_USER_EMAIL = process.env.TEST_USER_EMAIL || 'user@email.com';
export const TEST_USER_PASSWORD = process.env.TEST_USER_PASSWORD || '1234';

// ── API Response Types ────────────────────────────────────────────

export interface IssuerSummary {
  id: string;
  legalName: string;
  country: string;
  domain: string;
  status: 'ACTIVE' | 'SUSPENDED' | 'REVOKED';
  hasCertificate: boolean;
  certificateExpiry?: string;
  credentialCount: number;
  createdAt: string;
}

export interface IssuerDetail extends IssuerSummary {
  contactEmail: string;
  contactAddress?: string;
  signerCertificate?: CertificateInfo;
  iacaCertificate?: CertificateInfo;
  x5Chain?: string[];
  credentialConfigurations: Record<string, unknown>;
}

export interface CertificateInfo {
  subject: string;
  issuer: string;
  notBefore: string;
  notAfter: string;
  serialNumber: string;
  fingerprint: string;
}

export interface RpSummary {
  id: string;
  legalName: string;
  domain: string;
  country: string;
  status: 'ACTIVE' | 'SUSPENDED' | 'REVOKED';
  hasCertificate: boolean;
  certificateExpiry?: string;
  createdAt: string;
}

export interface RpDetail extends RpSummary {
  tradeName?: string;
  registrationNumber?: string;
  contactEmail: string;
  contactPhone?: string;
  contactAddress: string;
  intendedUse: string;
  privacyPolicyUrl: string;
  dataRetentionPeriod: string;
  lawfulBasis: string;
  dpaAcknowledged: boolean;
  clientId: string;
  certificate?: CertificateInfo;
  x5c?: string[];
}

export interface TrustStatus {
  sources: Record<string, {
    enabled: boolean;
    entryCount: number;
  }>;
}

// ── Wallet Auth ───────────────────────────────────────────────────

export async function setupWalletAuth(request: APIRequestContext): Promise<{
  walletId: string;
  token: string;
}> {
  // Try login first
  let loginRes = await request.post(`${WALLET_API}/wallet-api/auth/login`, {
    data: { type: 'email', email: TEST_USER_EMAIL, password: TEST_USER_PASSWORD },
  });

  // If login fails, register then login
  if (!loginRes.ok()) {
    await request.post(`${WALLET_API}/wallet-api/auth/register`, {
      data: { type: 'email', email: TEST_USER_EMAIL, password: TEST_USER_PASSWORD },
    });
    loginRes = await request.post(`${WALLET_API}/wallet-api/auth/login`, {
      data: { type: 'email', email: TEST_USER_EMAIL, password: TEST_USER_PASSWORD },
    });
  }

  const loginData = await loginRes.json();
  const token = loginData.token || '';

  // Get wallet ID
  const walletsRes = await request.get(`${WALLET_API}/wallet-api/wallet/accounts/wallets`, {
    headers: { Authorization: `Bearer ${token}` },
  });
  const walletsData = await walletsRes.json();
  const walletId = walletsData.wallets?.[0]?.id || walletsData[0]?.id || '';

  return { walletId, token };
}

// ── Page Helpers ──────────────────────────────────────────────────

export async function waitForPageReady(page: Page) {
  await page.waitForLoadState('load');
}

export async function loginToWallet(page: Page) {
  await page.goto(`${WALLET_URL}`);
  await page.waitForLoadState('load');

  // Check if already logged in
  if (page.url().includes('/wallet/')) return;

  // Fill login form
  const emailInput = page.getByPlaceholder('Email');
  if (await emailInput.isVisible()) {
    await emailInput.fill(TEST_USER_EMAIL);
    await page.getByPlaceholder('Password').fill(TEST_USER_PASSWORD);
    await page.getByRole('button', { name: /log\s*in|sign\s*in/i }).click();
    await page.waitForURL(/\/wallet\//);
  }
}

// ── API Helpers ───────────────────────────────────────────────────

export async function getActiveIssuers(request: APIRequestContext): Promise<IssuerSummary[]> {
  const res = await request.get(`${ISSUER_API}/admin/issuer`);
  const data: IssuerSummary[] = await res.json();
  return data.filter(t => t.status === 'ACTIVE' && t.hasCertificate);
}

export async function getActiveRps(request: APIRequestContext): Promise<RpSummary[]> {
  const res = await request.get(`${VERIFIER_API2}/admin/rp`);
  const data: RpSummary[] = await res.json();
  return data.filter(rp => rp.status === 'ACTIVE' && rp.hasCertificate);
}

export async function getIssuerDetail(request: APIRequestContext, id: string): Promise<IssuerDetail> {
  const res = await request.get(`${ISSUER_API}/admin/issuer/${id}`);
  return res.json();
}

export async function getRpDetail(request: APIRequestContext, id: string): Promise<RpDetail> {
  const res = await request.get(`${VERIFIER_API2}/admin/rp/${id}`);
  return res.json();
}

export async function getTrustStatus(request: APIRequestContext): Promise<TrustStatus> {
  const res = await request.get(`${VERIFIER_API2}/admin/trust/status`);
  return res.json();
}

// ── Trust List Management ────────────────────────────────────────

export async function importCustomTsl(request: APIRequestContext, country: string, url: string): Promise<boolean> {
  const res = await request.post(`${VERIFIER_API2}/admin/trust/custom-tsls`, {
    data: { country, url },
  });
  return res.ok();
}

export async function removeCustomTsl(request: APIRequestContext, country: string): Promise<boolean> {
  const res = await request.delete(`${VERIFIER_API2}/admin/trust/custom-tsls/${country}`);
  return res.ok();
}

export async function refreshTrustLists(request: APIRequestContext): Promise<boolean> {
  const res = await request.post(`${VERIFIER_API2}/admin/trust/refresh`, { timeout: 120_000 });
  return res.ok();
}

export async function getCustomTsls(request: APIRequestContext): Promise<Record<string, string>> {
  const res = await request.get(`${VERIFIER_API2}/admin/trust/custom-tsls`);
  return res.json();
}
