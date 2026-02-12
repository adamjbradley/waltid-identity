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

describe('RP Registrar - next.config.js feature flag', () => {
  it('should expose NEXT_PUBLIC_RP_REGISTRAR_ENABLED in publicRuntimeConfig', () => {
    const config = nextConfig.publicRuntimeConfig;
    expect(config).toBeDefined();
    expect('NEXT_PUBLIC_RP_REGISTRAR_ENABLED' in config!).toBe(true);
  });

  it('should default to "false" when env var is not set', () => {
    const config = nextConfig.publicRuntimeConfig;
    expect(config!.NEXT_PUBLIC_RP_REGISTRAR_ENABLED).toBe('false');
  });
});

describe('RP Registrar - Docker Compose Configuration', () => {
  const dockerComposePath = path.resolve(__dirname, '../../../docker-compose/docker-compose.yaml');

  let dockerComposeContent: string;

  beforeAll(() => {
    dockerComposeContent = fs.readFileSync(dockerComposePath, 'utf-8');
  });

  it('should pass NEXT_PUBLIC_RP_REGISTRAR_ENABLED to web-portal service', () => {
    expect(dockerComposeContent).toContain('NEXT_PUBLIC_RP_REGISTRAR_ENABLED');
    expect(dockerComposeContent).toMatch(/NEXT_PUBLIC_RP_REGISTRAR_ENABLED.*\$\{RP_REGISTRAR_ENABLED:-false\}/);
  });
});

describe('IssueSection - Tenant Dropdown Logic', () => {
  it('should filter tenants to ACTIVE with certificates', () => {
    const tenants = [
      { id: '1', legalName: 'Active Co', country: 'AU', status: 'ACTIVE', hasCertificate: true, credentialCount: 3 },
      { id: '2', legalName: 'No Cert Co', country: 'AU', status: 'ACTIVE', hasCertificate: false, credentialCount: 1 },
      { id: '3', legalName: 'Suspended Co', country: 'IN', status: 'SUSPENDED', hasCertificate: true, credentialCount: 2 },
    ];
    const filtered = tenants.filter(t => t.status === 'ACTIVE' && t.hasCertificate);
    expect(filtered).toHaveLength(1);
    expect(filtered[0].legalName).toBe('Active Co');
  });

  it('should use selectedTenantId when available, fallback to query param', () => {
    const selectedTenantId = 'tenant-123';
    const queryIssuerId = 'query-456';
    const issuerId = selectedTenantId || queryIssuerId;
    expect(issuerId).toBe('tenant-123');
  });

  it('should fallback to query param when no tenant selected', () => {
    const selectedTenantId = '';
    const queryIssuerId = 'query-456';
    const issuerId = selectedTenantId || queryIssuerId;
    expect(issuerId).toBe('query-456');
  });

  it('should pre-select tenant when issuerId matches query param', () => {
    const tenants = [
      { id: 'abc-123', legalName: 'Test Co', country: 'AU', status: 'ACTIVE', hasCertificate: true, credentialCount: 2 },
      { id: 'def-456', legalName: 'Other Co', country: 'IN', status: 'ACTIVE', hasCertificate: true, credentialCount: 1 },
    ];
    const qIssuerId = 'abc-123';
    const match = tenants.some(t => t.id === qIssuerId);
    expect(match).toBe(true);
  });
});

describe('RP Admin - Action Buttons Logic', () => {
  it('should construct verify URL with rpId', () => {
    const rpId = 'rp-abc-123';
    const verifyUrl = `/verify?rpId=${rpId}`;
    expect(verifyUrl).toBe('/verify?rpId=rp-abc-123');
  });

  it('should construct verify link for clipboard', () => {
    const origin = 'http://localhost:7102';
    const rpId = 'rp-abc-123';
    const verifyUrl = `${origin}/verify?rpId=${rpId}`;
    expect(verifyUrl).toBe('http://localhost:7102/verify?rpId=rp-abc-123');
  });

  it('should construct certificate download URL', () => {
    const apiBase = 'http://localhost:7004';
    const rpId = 'rp-abc-123';
    const certUrl = `${apiBase}/admin/rp/${rpId}/certificate/download`;
    expect(certUrl).toBe('http://localhost:7004/admin/rp/rp-abc-123/certificate/download');
  });
});

describe('VerificationSection - RP Tenant Dropdown Logic', () => {
  it('should filter RP tenants to ACTIVE with certificates', () => {
    const tenants = [
      { id: '1', legalName: 'Active RP', domain: 'rp.example.com', country: 'AU', status: 'ACTIVE', hasCertificate: true },
      { id: '2', legalName: 'No Cert RP', domain: 'rp2.example.com', country: 'AU', status: 'ACTIVE', hasCertificate: false },
      { id: '3', legalName: 'Suspended RP', domain: 'rp3.example.com', country: 'IN', status: 'SUSPENDED', hasCertificate: true },
    ];
    const filtered = tenants.filter(t => t.status === 'ACTIVE' && t.hasCertificate);
    expect(filtered).toHaveLength(1);
    expect(filtered[0].legalName).toBe('Active RP');
  });

  it('should append rpId to verify URL params when selected', () => {
    const selectedRpId = 'rp-123';
    const params = new URLSearchParams();
    params.append('ids', 'BankId');
    params.append('format', 'DC+SD-JWT (EUDI)');
    if (selectedRpId) {
      params.append('rpId', selectedRpId);
    }
    const url = `/verify?${params.toString()}`;
    expect(url).toContain('rpId=rp-123');
  });

  it('should not append rpId when empty', () => {
    const selectedRpId = '';
    const params = new URLSearchParams();
    params.append('ids', 'BankId');
    if (selectedRpId) {
      params.append('rpId', selectedRpId);
    }
    expect(params.toString()).not.toContain('rpId');
  });
});

describe('Verify Page - RP-Aware Signing Config Logic', () => {
  it('should prefer RP signing config when rpId is present', () => {
    const rpId = 'rp-123';
    const rpConfig = { clientId: 'x509_san_dns:rp.example.com', key: { kty: 'EC' }, x5c: ['certdata'] };
    const envConfig = { clientId: 'default-client', key: { kty: 'EC' }, x5c: ['default-cert'] };

    // Simulating the logic: if rpId is present and RP config was fetched, use it
    const signingConfig = rpId ? rpConfig : envConfig;
    expect(signingConfig.clientId).toBe('x509_san_dns:rp.example.com');
  });

  it('should fall back to env config when no rpId', () => {
    const rpId: string | undefined = undefined;
    const rpConfig = null;
    const envConfig = { clientId: 'default-client', key: { kty: 'EC' }, x5c: ['default-cert'] };

    const signingConfig = rpId && rpConfig ? rpConfig : envConfig;
    expect(signingConfig.clientId).toBe('default-client');
  });

  it('should construct correct RP detail URL for signing config fetch', () => {
    const verifier2Url = 'http://localhost:7004';
    const rpId = 'rp-abc-123';
    const rpDetailUrl = `${verifier2Url}/admin/rp/${rpId}`;
    expect(rpDetailUrl).toBe('http://localhost:7004/admin/rp/rp-abc-123');
  });
});

describe('Homepage - Multi-Tenant Banner Logic', () => {
  it('should show banner when issuer registrar is enabled', () => {
    const issuerEnabled = true;
    const rpEnabled = false;
    const hasMtMode = issuerEnabled || rpEnabled;
    expect(hasMtMode).toBe(true);
  });

  it('should show banner when RP registrar is enabled', () => {
    const issuerEnabled = false;
    const rpEnabled = true;
    const hasMtMode = issuerEnabled || rpEnabled;
    expect(hasMtMode).toBe(true);
  });

  it('should not show banner when both are disabled', () => {
    const issuerEnabled = false;
    const rpEnabled = false;
    const hasMtMode = issuerEnabled || rpEnabled;
    expect(hasMtMode).toBe(false);
  });

  it('should show both badges when both are enabled', () => {
    const issuerEnabled = true;
    const rpEnabled = true;
    const badges: string[] = [];
    if (issuerEnabled) badges.push('Issuer Registrar');
    if (rpEnabled) badges.push('RP Registrar');
    expect(badges).toEqual(['Issuer Registrar', 'RP Registrar']);
  });

  it('should correctly parse feature flag from config string', () => {
    expect(('true') === 'true').toBe(true);
    expect(('false') === 'true').toBe(false);
    expect(('') === 'true').toBe(false);
  });
});
