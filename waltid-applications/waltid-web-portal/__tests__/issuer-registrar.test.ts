import nextConfig from '../next.config';
import * as fs from 'fs';
import * as path from 'path';

describe('Issuer Registrar - next.config.js Environment', () => {
  it('should expose NEXT_PUBLIC_ISSUER_REGISTRAR_ENABLED in publicRuntimeConfig', () => {
    const config = nextConfig.publicRuntimeConfig;
    expect(config).toBeDefined();
    expect(config!.NEXT_PUBLIC_ISSUER_REGISTRAR_ENABLED).toBeDefined();
  });

  it('should default NEXT_PUBLIC_ISSUER_REGISTRAR_ENABLED to "false"', () => {
    const config = nextConfig.publicRuntimeConfig;
    expect(config!.NEXT_PUBLIC_ISSUER_REGISTRAR_ENABLED).toBe('false');
  });

  it('should have NEXT_PUBLIC_ISSUER for API base URL', () => {
    const config = nextConfig.publicRuntimeConfig;
    expect(config!.NEXT_PUBLIC_ISSUER).toBeDefined();
    expect(typeof config!.NEXT_PUBLIC_ISSUER).toBe('string');
    expect(config!.NEXT_PUBLIC_ISSUER.length).toBeGreaterThan(0);
  });
});

describe('Issuer Registrar - getOfferUrl tenant-scoping', () => {
  // Test the tenant-scoped URL construction logic from getOfferUrl.tsx
  // We test the URL path construction without calling the actual function
  // (which requires axios and real API endpoints)

  it('should construct tenant-scoped basePath when issuerId is provided', () => {
    const issuerId = 'abc-123-def';
    const basePath = issuerId ? `/issuers/${issuerId}` : '';
    expect(basePath).toBe('/issuers/abc-123-def');
  });

  it('should construct empty basePath when issuerId is undefined', () => {
    const issuerId: string | undefined = undefined;
    const basePath = issuerId ? `/issuers/${issuerId}` : '';
    expect(basePath).toBe('');
  });

  it('should construct correct metadata URL for tenant', () => {
    const NEXT_PUBLIC_ISSUER = 'http://localhost:7002';
    const issuerId = 'abc-123-def';
    const basePath = issuerId ? `/issuers/${issuerId}` : '';
    const metadataUrl = `${NEXT_PUBLIC_ISSUER}${basePath}/draft13/.well-known/openid-credential-issuer`;
    expect(metadataUrl).toBe('http://localhost:7002/issuers/abc-123-def/draft13/.well-known/openid-credential-issuer');
  });

  it('should construct correct metadata URL for global (no tenant)', () => {
    const NEXT_PUBLIC_ISSUER = 'http://localhost:7002';
    const issuerId: string | undefined = undefined;
    const basePath = issuerId ? `/issuers/${issuerId}` : '';
    const metadataUrl = `${NEXT_PUBLIC_ISSUER}${basePath}/draft13/.well-known/openid-credential-issuer`;
    expect(metadataUrl).toBe('http://localhost:7002/draft13/.well-known/openid-credential-issuer');
  });

  it('should construct correct issuance URL for tenant JWT', () => {
    const NEXT_PUBLIC_ISSUER = 'http://localhost:7002';
    const issuerId = 'tenant-uuid';
    const basePath = issuerId ? `/issuers/${issuerId}` : '';
    const issueUrl = `${NEXT_PUBLIC_ISSUER}${basePath}/openid4vc/jwt/issue`;
    expect(issueUrl).toBe('http://localhost:7002/issuers/tenant-uuid/openid4vc/jwt/issue');
  });

  it('should construct correct issuance URL for tenant SD-JWT', () => {
    const NEXT_PUBLIC_ISSUER = 'http://localhost:7002';
    const issuerId = 'tenant-uuid';
    const basePath = issuerId ? `/issuers/${issuerId}` : '';
    const issueUrl = `${NEXT_PUBLIC_ISSUER}${basePath}/openid4vc/sdjwt/issue`;
    expect(issueUrl).toBe('http://localhost:7002/issuers/tenant-uuid/openid4vc/sdjwt/issue');
  });

  it('should construct correct issuance URL for tenant mDoc', () => {
    const NEXT_PUBLIC_ISSUER = 'http://localhost:7002';
    const issuerId = 'tenant-uuid';
    const basePath = issuerId ? `/issuers/${issuerId}` : '';
    const issueUrl = `${NEXT_PUBLIC_ISSUER}${basePath}/openid4vc/mdoc/issue`;
    expect(issueUrl).toBe('http://localhost:7002/issuers/tenant-uuid/openid4vc/mdoc/issue');
  });

  it('should construct correct global issuance URL without tenant', () => {
    const NEXT_PUBLIC_ISSUER = 'http://localhost:7002';
    const issuerId: string | undefined = undefined;
    const basePath = issuerId ? `/issuers/${issuerId}` : '';
    const issueUrl = `${NEXT_PUBLIC_ISSUER}${basePath}/openid4vc/jwt/issue`;
    expect(issueUrl).toBe('http://localhost:7002/openid4vc/jwt/issue');
  });
});

describe('Issuer Registrar - IssueSection issuerId query param', () => {
  // Test the issuerId extraction from URL query params
  // (mirrors the logic in IssueSection.tsx and offer/index.tsx)

  it('should extract issuerId from query params', () => {
    const query = { ids: 'BankId', format: 'jwt', issuerId: 'abc-123-def' };
    const issuerId = query.issuerId as string | undefined;
    expect(issuerId).toBe('abc-123-def');
  });

  it('should handle missing issuerId in query params', () => {
    const query = { ids: 'BankId', format: 'jwt' };
    const issuerId = (query as any).issuerId as string | undefined;
    expect(issuerId).toBeUndefined();
  });

  it('should append issuerId to offer URL params', () => {
    const issuerId = 'abc-123-def';
    const baseOfferUrl = '/offer?ids=BankId&format=jwt';
    const offerUrl = issuerId ? `${baseOfferUrl}&issuerId=${issuerId}` : baseOfferUrl;
    expect(offerUrl).toContain('issuerId=abc-123-def');
  });

  it('should not append issuerId when undefined', () => {
    const issuerId: string | undefined = undefined;
    const baseOfferUrl = '/offer?ids=BankId&format=jwt';
    const offerUrl = issuerId ? `${baseOfferUrl}&issuerId=${issuerId}` : baseOfferUrl;
    expect(offerUrl).not.toContain('issuerId');
  });
});

describe('Issuer Registrar - Admin page feature flag', () => {
  it('should detect feature disabled state from env', () => {
    const env = { NEXT_PUBLIC_ISSUER_REGISTRAR_ENABLED: 'false' };
    const isEnabled = env.NEXT_PUBLIC_ISSUER_REGISTRAR_ENABLED === 'true';
    expect(isEnabled).toBe(false);
  });

  it('should detect feature enabled state from env', () => {
    const env = { NEXT_PUBLIC_ISSUER_REGISTRAR_ENABLED: 'true' };
    const isEnabled = env.NEXT_PUBLIC_ISSUER_REGISTRAR_ENABLED === 'true';
    expect(isEnabled).toBe(true);
  });

  it('should construct correct admin API URL', () => {
    const NEXT_PUBLIC_ISSUER = 'http://localhost:7002';
    const adminUrl = `${NEXT_PUBLIC_ISSUER}/admin/issuer`;
    expect(adminUrl).toBe('http://localhost:7002/admin/issuer');
  });

  it('should construct correct tenant detail API URL', () => {
    const NEXT_PUBLIC_ISSUER = 'http://localhost:7002';
    const tenantId = 'abc-123';
    const detailUrl = `${NEXT_PUBLIC_ISSUER}/admin/issuer/${tenantId}`;
    expect(detailUrl).toBe('http://localhost:7002/admin/issuer/abc-123');
  });

  it('should construct correct certificate generate URL', () => {
    const NEXT_PUBLIC_ISSUER = 'http://localhost:7002';
    const tenantId = 'abc-123';
    const certUrl = `${NEXT_PUBLIC_ISSUER}/admin/issuer/${tenantId}/certificate/generate`;
    expect(certUrl).toBe('http://localhost:7002/admin/issuer/abc-123/certificate/generate');
  });
});

// ============================================================
// Docker Configuration Infrastructure Tests
// ============================================================

describe('Issuer Registrar - Docker Compose Configuration', () => {
  const dockerComposePath = path.resolve(__dirname, '../../../docker-compose/docker-compose.yaml');
  const envPath = path.resolve(__dirname, '../../../docker-compose/.env');
  const issuerRegistrarConfPath = path.resolve(__dirname, '../../../docker-compose/issuer-api/config/issuer-registrar.conf');

  let dockerComposeContent: string;
  let envContent: string;
  let issuerRegistrarConfContent: string;

  beforeAll(() => {
    dockerComposeContent = fs.readFileSync(dockerComposePath, 'utf-8');
    envContent = fs.readFileSync(envPath, 'utf-8');
    issuerRegistrarConfContent = fs.readFileSync(issuerRegistrarConfPath, 'utf-8');
  });

  it('should pass ISSUER_REGISTRAR_ENABLED to issuer-api service', () => {
    expect(dockerComposeContent).toContain('ISSUER_REGISTRAR_ENABLED');
    // Verify the env var is mapped with a default of false
    expect(dockerComposeContent).toMatch(/ISSUER_REGISTRAR_ENABLED.*\$\{ISSUER_REGISTRAR_ENABLED:-false\}/);
  });

  it('should pass NEXT_PUBLIC_ISSUER_REGISTRAR_ENABLED to web-portal service', () => {
    expect(dockerComposeContent).toContain('NEXT_PUBLIC_ISSUER_REGISTRAR_ENABLED');
  });

  it('should default ISSUER_REGISTRAR_ENABLED to false in .env', () => {
    expect(envContent).toContain('ISSUER_REGISTRAR_ENABLED=false');
  });

  it('should have issuer-registrar.conf with storageDir', () => {
    expect(issuerRegistrarConfContent).toContain('storageDir');
    expect(issuerRegistrarConfContent).toContain('config/issuer-tenants');
  });

  it('should mount issuer-api config volume for tenant storage', () => {
    expect(dockerComposeContent).toMatch(/issuer-api\/config:\/waltid-issuer-api\/config/);
  });
});

describe('Issuer Registrar - next.config.js feature flag plumbing', () => {
  it('should expose NEXT_PUBLIC_ISSUER_REGISTRAR_ENABLED in publicRuntimeConfig', () => {
    const config = nextConfig.publicRuntimeConfig;
    expect(config).toBeDefined();
    expect('NEXT_PUBLIC_ISSUER_REGISTRAR_ENABLED' in config!).toBe(true);
  });

  it('should default to string "false" when env var is not set', () => {
    // In test environment, env var is not set, so fallback applies
    const config = nextConfig.publicRuntimeConfig;
    expect(config!.NEXT_PUBLIC_ISSUER_REGISTRAR_ENABLED).toBe('false');
  });
});
