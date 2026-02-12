import { test, expect } from '@playwright/test';
import {
  ISSUER_API,
  VERIFIER_API2,
  getActiveIssuers,
  getActiveRps,
  getIssuerDetail,
  getRpDetail,
  getTrustStatus,
  type IssuerSummary,
  type IssuerDetail,
  type RpSummary,
  type RpDetail,
  type TrustStatus,
} from './helpers';

test.describe('API Contracts', () => {
  test('GET /admin/issuer returns IssuerSummary array', async ({ request }) => {
    const res = await request.get(`${ISSUER_API}/admin/issuer`);
    expect(res.ok()).toBeTruthy();

    const data: IssuerSummary[] = await res.json();
    expect(Array.isArray(data)).toBeTruthy();
    expect(data.length).toBeGreaterThan(0);

    const issuer = data[0];
    expect(issuer).toHaveProperty('id');
    expect(issuer).toHaveProperty('legalName');
    expect(issuer).toHaveProperty('country');
    expect(issuer).toHaveProperty('domain');
    expect(issuer).toHaveProperty('status');
    expect(issuer).toHaveProperty('hasCertificate');
    expect(issuer).toHaveProperty('credentialCount');
    expect(typeof issuer.id).toBe('string');
    expect(typeof issuer.legalName).toBe('string');
    expect(typeof issuer.country).toBe('string');
    expect(typeof issuer.domain).toBe('string');
    expect(['ACTIVE', 'SUSPENDED', 'REVOKED']).toContain(issuer.status);
    expect(typeof issuer.hasCertificate).toBe('boolean');
    expect(typeof issuer.credentialCount).toBe('number');
  });

  test('GET /admin/issuer/{id} returns IssuerDetail', async ({ request }) => {
    const issuers = await getActiveIssuers(request);
    expect(issuers.length).toBeGreaterThan(0);

    const detail: IssuerDetail = await getIssuerDetail(request, issuers[0].id);
    expect(detail).toHaveProperty('id');
    expect(detail).toHaveProperty('legalName');
    expect(detail).toHaveProperty('signerCertificate');
    expect(detail).toHaveProperty('iacaCertificate');
    expect(detail).toHaveProperty('x5Chain');
    expect(detail).toHaveProperty('credentialConfigurations');
    expect(typeof detail.credentialConfigurations).toBe('object');
  });

  test('GET /admin/rp returns RpSummary array', async ({ request }) => {
    const res = await request.get(`${VERIFIER_API2}/admin/rp`);
    expect(res.ok()).toBeTruthy();

    const data: RpSummary[] = await res.json();
    expect(Array.isArray(data)).toBeTruthy();
    expect(data.length).toBeGreaterThan(0);

    const rp = data[0];
    expect(rp).toHaveProperty('id');
    expect(rp).toHaveProperty('legalName');
    expect(rp).toHaveProperty('domain');
    expect(rp).toHaveProperty('country');
    expect(rp).toHaveProperty('status');
    expect(rp).toHaveProperty('hasCertificate');
    expect(typeof rp.id).toBe('string');
    expect(typeof rp.legalName).toBe('string');
    expect(typeof rp.domain).toBe('string');
    expect(typeof rp.country).toBe('string');
    expect(['ACTIVE', 'SUSPENDED', 'REVOKED']).toContain(rp.status);
    expect(typeof rp.hasCertificate).toBe('boolean');
  });

  test('GET /admin/rp/{id} returns RpDetail with compliance', async ({ request }) => {
    const rps = await getActiveRps(request);
    expect(rps.length).toBeGreaterThan(0);

    const detail: RpDetail = await getRpDetail(request, rps[0].id);
    expect(detail).toHaveProperty('clientId');
    expect(detail).toHaveProperty('privacyPolicyUrl');
    expect(detail).toHaveProperty('dataRetentionPeriod');
    expect(detail).toHaveProperty('lawfulBasis');
    expect(detail).toHaveProperty('dpaAcknowledged');
    expect(typeof detail.clientId).toBe('string');
    expect(typeof detail.privacyPolicyUrl).toBe('string');
    expect(typeof detail.dataRetentionPeriod).toBe('string');
    expect(typeof detail.lawfulBasis).toBe('string');
    expect(typeof detail.dpaAcknowledged).toBe('boolean');
  });

  test('active issuers have certificate chain fields', async ({ request }) => {
    const issuers = await getActiveIssuers(request);
    expect(issuers.length).toBeGreaterThan(0);

    const detail: IssuerDetail = await getIssuerDetail(request, issuers[0].id);

    // Active issuers with certificates must have populated certificate fields
    expect(detail.signerCertificate).toBeTruthy();
    expect(detail.signerCertificate!.subject).toBeTruthy();
    expect(typeof detail.signerCertificate!.subject).toBe('string');
    expect(detail.signerCertificate!.fingerprint).toBeTruthy();
    expect(typeof detail.signerCertificate!.fingerprint).toBe('string');

    expect(detail.iacaCertificate).toBeTruthy();
    expect(detail.iacaCertificate!.subject).toBeTruthy();

    expect(detail.x5Chain).toBeTruthy();
    expect(Array.isArray(detail.x5Chain)).toBeTruthy();
    expect(detail.x5Chain!.length).toBeGreaterThan(0);
  });

  test('active RPs have clientId matching x509_san_dns:{domain}', async ({ request }) => {
    const rps = await getActiveRps(request);
    expect(rps.length).toBeGreaterThan(0);

    for (const rp of rps) {
      const detail: RpDetail = await getRpDetail(request, rp.id);
      expect(detail.clientId).toBeTruthy();
      // clientId should follow the x509_san_dns:{domain} format
      expect(detail.clientId).toMatch(/^x509_san_dns:.+/);
      // The domain portion should match the RP's registered domain
      const clientIdDomain = detail.clientId.replace('x509_san_dns:', '');
      expect(clientIdDomain).toBe(detail.domain);
    }
  });

  test('GET /admin/issuer/lotl.xml returns valid XML', async ({ request }) => {
    const res = await request.get(`${ISSUER_API}/admin/issuer/lotl.xml`);
    expect(res.ok()).toBeTruthy();

    const body = await res.text();
    // Must contain the ETSI TrustServiceStatusList root element
    expect(body).toContain('TrustServiceStatusList');
    // Must reference the ETSI TSL namespace
    expect(body).toContain('http://uri.etsi.org/02231/v2#');
  });

  test('LOTL pointers reference country TSL URLs', async ({ request }) => {
    const res = await request.get(`${ISSUER_API}/admin/issuer/lotl.xml`);
    expect(res.ok()).toBeTruthy();

    const body = await res.text();

    // TSLLocation elements should end with .xml
    const tslLocationMatches = body.match(/<TSLLocation>([^<]+)<\/TSLLocation>/g);
    expect(tslLocationMatches).toBeTruthy();
    expect(tslLocationMatches!.length).toBeGreaterThan(0);

    for (const match of tslLocationMatches!) {
      const url = match.replace(/<\/?TSLLocation>/g, '');
      expect(url).toMatch(/\.xml$/);
    }

    // SchemeTerritory elements should be present and match issuer countries
    const issuers = await getActiveIssuers(request);
    const issuerCountries = new Set(issuers.map(i => i.country));

    const territoryMatches = body.match(/<SchemeTerritory>([^<]+)<\/SchemeTerritory>/g);
    expect(territoryMatches).toBeTruthy();

    const lotlTerritories = territoryMatches!.map(
      m => m.replace(/<\/?SchemeTerritory>/g, '')
    );

    // Each issuer country should appear as a SchemeTerritory in the LOTL
    for (const country of issuerCountries) {
      expect(lotlTerritories).toContain(country);
    }
  });

  test('GET /admin/issuer/tsl/{CC}.xml returns valid TSL', async ({ request }) => {
    const issuers = await getActiveIssuers(request);
    expect(issuers.length).toBeGreaterThan(0);

    // Use the country code of the first active issuer
    const countryCode = issuers[0].country;
    const res = await request.get(`${ISSUER_API}/admin/issuer/tsl/${countryCode}.xml`);
    expect(res.ok()).toBeTruthy();

    const body = await res.text();
    // Must contain TrustServiceProvider elements
    expect(body).toContain('TrustServiceProvider');
    // Must contain X509Certificate data
    expect(body).toContain('X509Certificate');
  });

  test('verifier trust status has loaded trust data', async ({ request }) => {
    const status: TrustStatus = await getTrustStatus(request);

    expect(status).toHaveProperty('sources');

    // Trust status key may be 'etsi_tl' or 'ETSI_TL' depending on service version
    const etsiKey = Object.keys(status.sources).find(
      (k) => k.toLowerCase() === 'etsi_tl'
    );
    expect(etsiKey).toBeTruthy();

    const etsiSource = status.sources[etsiKey!];
    expect(etsiSource).toHaveProperty('enabled');
    expect(etsiSource).toHaveProperty('entryCount');
    expect(etsiSource.enabled).toBe(true);
    expect(etsiSource.entryCount).toBeGreaterThan(0);
  });
});
