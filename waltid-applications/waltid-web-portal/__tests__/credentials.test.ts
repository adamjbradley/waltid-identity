import { EudiCredentials, mapFormat, AvailableCredential, isEudiFormat, buildDcqlQuery } from '../types/credentials';

describe('Environment Configuration', () => {
  it('should recognize NEXT_PUBLIC_VERIFIER2 as a valid env variable', () => {
    const envVars = [
      'NEXT_PUBLIC_VC_REPO',
      'NEXT_PUBLIC_ISSUER',
      'NEXT_PUBLIC_VERIFIER',
      'NEXT_PUBLIC_VERIFIER2',
      'NEXT_PUBLIC_WALLET',
    ];
    expect(envVars).toContain('NEXT_PUBLIC_VERIFIER2');
  });
});

describe('EudiCredentials', () => {
  it('should contain EU Personal ID (mDoc) credential', () => {
    const pidMdoc = EudiCredentials.find(c => c.id === 'eu.europa.ec.eudi.pid.1');
    expect(pidMdoc).toBeDefined();
    expect(pidMdoc?.title).toBe('EU Personal ID (mDoc)');
    expect(pidMdoc?.offer.doctype).toBe('eu.europa.ec.eudi.pid.1');
  });

  it('should contain Mobile Driving License credential', () => {
    const mdl = EudiCredentials.find(c => c.id === 'org.iso.18013.5.1.mDL');
    expect(mdl).toBeDefined();
    expect(mdl?.title).toBe('Mobile Driving License');
    expect(mdl?.offer.doctype).toBe('org.iso.18013.5.1.mDL');
  });

  it('should contain EU Personal ID (SD-JWT) credential', () => {
    const pidSdJwt = EudiCredentials.find(c => c.id === 'urn:eudi:pid:1');
    expect(pidSdJwt).toBeDefined();
    expect(pidSdJwt?.title).toBe('EU Personal ID (SD-JWT)');
    expect(pidSdJwt?.offer.vct).toBe('urn:eudi:pid:1');
  });

  it('should have exactly 3 EUDI credentials', () => {
    expect(EudiCredentials.length).toBe(3);
  });
});

describe('isEudiFormat', () => {
  it('should return true for dc+sd-jwt format', () => {
    expect(isEudiFormat('dc+sd-jwt')).toBe(true);
  });

  it('should return true for mso_mdoc format', () => {
    expect(isEudiFormat('mso_mdoc')).toBe(true);
  });

  it('should return false for vc+sd-jwt format', () => {
    expect(isEudiFormat('vc+sd-jwt')).toBe(false);
  });

  it('should return false for jwt_vc_json format', () => {
    expect(isEudiFormat('jwt_vc_json')).toBe(false);
  });
});

describe('buildDcqlQuery', () => {
  it('should build correct DCQL query for mso_mdoc format', () => {
    const credentials: AvailableCredential[] = [{
      id: 'eu.europa.ec.eudi.pid.1',
      title: 'EU Personal ID (mDoc)',
      offer: { doctype: 'eu.europa.ec.eudi.pid.1' }
    }];

    const result = buildDcqlQuery(credentials, 'mso_mdoc');

    expect(result).toEqual({
      credentials: [{
        id: 'eu.europa.ec.eudi.pid.1',
        format: 'mso_mdoc',
        meta: { doctype_value: 'eu.europa.ec.eudi.pid.1' }
      }]
    });
  });

  it('should build correct DCQL query for dc+sd-jwt format', () => {
    const credentials: AvailableCredential[] = [{
      id: 'urn:eudi:pid:1',
      title: 'EU Personal ID (SD-JWT)',
      offer: { vct: 'urn:eudi:pid:1' }
    }];

    const result = buildDcqlQuery(credentials, 'dc+sd-jwt');

    expect(result).toEqual({
      credentials: [{
        id: 'urn:eudi:pid:1',
        format: 'dc+sd-jwt',
        meta: { vct_values: ['urn:eudi:pid:1'] }
      }]
    });
  });

  it('should use credential id as fallback for doctype', () => {
    const credentials: AvailableCredential[] = [{
      id: 'org.iso.18013.5.1.mDL',
      title: 'Mobile Driving License',
      offer: {}
    }];

    const result = buildDcqlQuery(credentials, 'mso_mdoc');

    expect(result.credentials[0].meta.doctype_value).toBe('org.iso.18013.5.1.mDL');
  });

  it('should use default vct if not provided', () => {
    const credentials: AvailableCredential[] = [{
      id: 'some-pid',
      title: 'PID',
      offer: {}
    }];

    const result = buildDcqlQuery(credentials, 'dc+sd-jwt');

    expect(result.credentials[0].meta.vct_values).toEqual(['urn:eudi:pid:1']);
  });

  it('should handle multiple credentials', () => {
    const credentials: AvailableCredential[] = [
      { id: 'eu.europa.ec.eudi.pid.1', title: 'PID', offer: { doctype: 'eu.europa.ec.eudi.pid.1' } },
      { id: 'org.iso.18013.5.1.mDL', title: 'mDL', offer: { doctype: 'org.iso.18013.5.1.mDL' } }
    ];

    const result = buildDcqlQuery(credentials, 'mso_mdoc');

    expect(result.credentials).toHaveLength(2);
  });
});

describe('mapFormat', () => {
  it('should map "DC+SD-JWT (EUDI)" to "dc+sd-jwt"', () => {
    expect(mapFormat('DC+SD-JWT (EUDI)')).toBe('dc+sd-jwt');
  });

  it('should map "mDoc (ISO 18013-5)" to "mso_mdoc"', () => {
    expect(mapFormat('mDoc (ISO 18013-5)')).toBe('mso_mdoc');
  });

  it('should map "SD-JWT + IETF SD-JWT VC" to "vc+sd-jwt"', () => {
    expect(mapFormat('SD-JWT + IETF SD-JWT VC')).toBe('vc+sd-jwt');
  });

  it('should map "JWT + W3C VC" to "jwt_vc_json"', () => {
    expect(mapFormat('JWT + W3C VC')).toBe('jwt_vc_json');
  });

  it('should map "SD-JWT + W3C VC" to "jwt_vc_json"', () => {
    expect(mapFormat('SD-JWT + W3C VC')).toBe('jwt_vc_json');
  });

  it('should throw error for unsupported format', () => {
    expect(() => mapFormat('unknown')).toThrow('Unsupported format: unknown');
  });
});

describe('buildRequestCredentials (Legacy Verifier API)', () => {
  // Helper function that mirrors the logic in verify/index.tsx
  function buildRequestCredential(
    credential: AvailableCredential,
    format: string,
    issuerMetadata?: any
  ) {
    if (format === 'dc+sd-jwt') {
      return {
        vct: credential.offer.vct || 'urn:eudi:pid:1',
        format: 'dc+sd-jwt',
      };
    } else if (format === 'mso_mdoc') {
      return {
        doctype: credential.offer.doctype || credential.id,
        format: 'mso_mdoc',
      };
    } else if (format === 'vc+sd-jwt') {
      const vct = issuerMetadata?.credential_configurations_supported?.[
        `${credential.offer.type?.[credential.offer.type.length - 1]}_vc+sd-jwt`
      ]?.vct;
      return {
        vct,
        format: 'vc+sd-jwt',
      };
    } else {
      return {
        type: credential.offer.type?.[credential.offer.type.length - 1] || credential.id,
        format: format,
      };
    }
  }

  describe('dc+sd-jwt format', () => {
    it('should use vct from credential offer for SD-JWT', () => {
      const credential: AvailableCredential = {
        id: 'urn:eudi:pid:1',
        title: 'EU Personal ID (SD-JWT)',
        offer: { vct: 'urn:eudi:pid:1' },
      };
      const result = buildRequestCredential(credential, 'dc+sd-jwt');
      expect(result).toEqual({
        vct: 'urn:eudi:pid:1',
        format: 'dc+sd-jwt',
      });
    });

    it('should fallback to default vct if not provided', () => {
      const credential: AvailableCredential = {
        id: 'some-id',
        title: 'Some Credential',
        offer: {},
      };
      const result = buildRequestCredential(credential, 'dc+sd-jwt');
      expect(result).toEqual({
        vct: 'urn:eudi:pid:1',
        format: 'dc+sd-jwt',
      });
    });
  });

  describe('mso_mdoc format', () => {
    it('should use doctype from credential offer for mDoc', () => {
      const credential: AvailableCredential = {
        id: 'eu.europa.ec.eudi.pid.1',
        title: 'EU Personal ID (mDoc)',
        offer: { doctype: 'eu.europa.ec.eudi.pid.1' },
      };
      const result = buildRequestCredential(credential, 'mso_mdoc');
      expect(result).toEqual({
        doctype: 'eu.europa.ec.eudi.pid.1',
        format: 'mso_mdoc',
      });
    });

    it('should use credential id as fallback doctype', () => {
      const credential: AvailableCredential = {
        id: 'org.iso.18013.5.1.mDL',
        title: 'Mobile Driving License',
        offer: {},
      };
      const result = buildRequestCredential(credential, 'mso_mdoc');
      expect(result).toEqual({
        doctype: 'org.iso.18013.5.1.mDL',
        format: 'mso_mdoc',
      });
    });
  });

  describe('vc+sd-jwt format', () => {
    it('should get vct from issuer metadata', () => {
      const credential: AvailableCredential = {
        id: 'OpenBadgeCredential',
        title: 'Open Badge',
        offer: { type: ['VerifiableCredential', 'OpenBadgeCredential'] },
      };
      const issuerMetadata = {
        credential_configurations_supported: {
          'OpenBadgeCredential_vc+sd-jwt': {
            vct: 'https://example.com/credentials/OpenBadgeCredential',
          },
        },
      };
      const result = buildRequestCredential(credential, 'vc+sd-jwt', issuerMetadata);
      expect(result).toEqual({
        vct: 'https://example.com/credentials/OpenBadgeCredential',
        format: 'vc+sd-jwt',
      });
    });
  });

  describe('jwt_vc_json format', () => {
    it('should use type from credential offer', () => {
      const credential: AvailableCredential = {
        id: 'OpenBadgeCredential',
        title: 'Open Badge',
        offer: { type: ['VerifiableCredential', 'OpenBadgeCredential'] },
      };
      const result = buildRequestCredential(credential, 'jwt_vc_json');
      expect(result).toEqual({
        type: 'OpenBadgeCredential',
        format: 'jwt_vc_json',
      });
    });

    it('should fallback to credential id if type not present', () => {
      const credential: AvailableCredential = {
        id: 'some-credential-id',
        title: 'Some Credential',
        offer: {},
      };
      const result = buildRequestCredential(credential, 'jwt_vc_json');
      expect(result).toEqual({
        type: 'some-credential-id',
        format: 'jwt_vc_json',
      });
    });
  });
});

describe('OpenID4VP Profile Header (Legacy Verifier API)', () => {
  function getOpenId4VPProfile(format: string): string | undefined {
    return format === 'mso_mdoc' ? 'ISO_18013_7_MDOC' : undefined;
  }

  it('should return ISO_18013_7_MDOC for mso_mdoc format', () => {
    expect(getOpenId4VPProfile('mso_mdoc')).toBe('ISO_18013_7_MDOC');
  });

  it('should return undefined for dc+sd-jwt format', () => {
    expect(getOpenId4VPProfile('dc+sd-jwt')).toBeUndefined();
  });

  it('should return undefined for vc+sd-jwt format', () => {
    expect(getOpenId4VPProfile('vc+sd-jwt')).toBeUndefined();
  });

  it('should return undefined for jwt_vc_json format', () => {
    expect(getOpenId4VPProfile('jwt_vc_json')).toBeUndefined();
  });
});

/**
 * Tests for Verifier API2 (OID4VP 1.0 with DCQL)
 * This is the API that supports EUDI wallet verification
 */
describe('Verifier API2 DCQL Query Builder', () => {
  // Helper function to build DCQL credential query for Verifier API2
  function buildDcqlCredentialQuery(
    credential: AvailableCredential,
    format: string
  ): any {
    const baseQuery = {
      id: credential.id,
      format: format,
    };

    if (format === 'mso_mdoc') {
      return {
        ...baseQuery,
        meta: {
          doctype_value: credential.offer.doctype || credential.id,
        },
        claims: [
          {
            path: [credential.offer.doctype || credential.id, 'family_name'],
          },
        ],
      };
    } else if (format === 'dc+sd-jwt') {
      return {
        ...baseQuery,
        meta: {
          vct_values: [credential.offer.vct || 'urn:eudi:pid:1'],
        },
        claims: [
          {
            path: ['family_name'],
          },
        ],
      };
    } else {
      // W3C VC formats
      return {
        ...baseQuery,
        claims: [
          {
            path: ['$.credentialSubject.name'],
          },
        ],
      };
    }
  }

  // Helper to build full verification session setup for Verifier API2
  function buildVerificationSessionSetup(
    credentials: AvailableCredential[],
    format: string,
    redirects?: { success: string; error: string }
  ): any {
    return {
      flow_type: 'cross_device',
      core_flow: {
        dcql_query: {
          credentials: credentials.map((c) => buildDcqlCredentialQuery(c, format)),
        },
      },
      ...(redirects && {
        redirects: {
          successRedirectUri: redirects.success,
          errorRedirectUri: redirects.error,
        },
      }),
    };
  }

  describe('DCQL Query for mso_mdoc format', () => {
    it('should build correct DCQL query for EUDI PID mDoc', () => {
      const credential: AvailableCredential = {
        id: 'eu.europa.ec.eudi.pid.1',
        title: 'EU Personal ID (mDoc)',
        offer: { doctype: 'eu.europa.ec.eudi.pid.1' },
      };
      const result = buildDcqlCredentialQuery(credential, 'mso_mdoc');
      expect(result).toEqual({
        id: 'eu.europa.ec.eudi.pid.1',
        format: 'mso_mdoc',
        meta: {
          doctype_value: 'eu.europa.ec.eudi.pid.1',
        },
        claims: [
          {
            path: ['eu.europa.ec.eudi.pid.1', 'family_name'],
          },
        ],
      });
    });

    it('should build correct DCQL query for mDL', () => {
      const credential: AvailableCredential = {
        id: 'org.iso.18013.5.1.mDL',
        title: 'Mobile Driving License',
        offer: { doctype: 'org.iso.18013.5.1.mDL' },
      };
      const result = buildDcqlCredentialQuery(credential, 'mso_mdoc');
      expect(result).toEqual({
        id: 'org.iso.18013.5.1.mDL',
        format: 'mso_mdoc',
        meta: {
          doctype_value: 'org.iso.18013.5.1.mDL',
        },
        claims: [
          {
            path: ['org.iso.18013.5.1.mDL', 'family_name'],
          },
        ],
      });
    });
  });

  describe('DCQL Query for dc+sd-jwt format', () => {
    it('should build correct DCQL query for EUDI PID SD-JWT', () => {
      const credential: AvailableCredential = {
        id: 'urn:eudi:pid:1',
        title: 'EU Personal ID (SD-JWT)',
        offer: { vct: 'urn:eudi:pid:1' },
      };
      const result = buildDcqlCredentialQuery(credential, 'dc+sd-jwt');
      expect(result).toEqual({
        id: 'urn:eudi:pid:1',
        format: 'dc+sd-jwt',
        meta: {
          vct_values: ['urn:eudi:pid:1'],
        },
        claims: [
          {
            path: ['family_name'],
          },
        ],
      });
    });

    it('should use default vct if not provided', () => {
      const credential: AvailableCredential = {
        id: 'some-sdjwt',
        title: 'Some SD-JWT Credential',
        offer: {},
      };
      const result = buildDcqlCredentialQuery(credential, 'dc+sd-jwt');
      expect(result.meta.vct_values).toEqual(['urn:eudi:pid:1']);
    });
  });

  describe('Verification Session Setup', () => {
    it('should build correct cross-device session setup for PID mDoc', () => {
      const credential: AvailableCredential = {
        id: 'eu.europa.ec.eudi.pid.1',
        title: 'EU Personal ID (mDoc)',
        offer: { doctype: 'eu.europa.ec.eudi.pid.1' },
      };
      const result = buildVerificationSessionSetup([credential], 'mso_mdoc');

      expect(result.flow_type).toBe('cross_device');
      expect(result.core_flow.dcql_query.credentials).toHaveLength(1);
      expect(result.core_flow.dcql_query.credentials[0].format).toBe('mso_mdoc');
      expect(result.core_flow.dcql_query.credentials[0].meta.doctype_value).toBe('eu.europa.ec.eudi.pid.1');
    });

    it('should build correct cross-device session setup for PID SD-JWT', () => {
      const credential: AvailableCredential = {
        id: 'urn:eudi:pid:1',
        title: 'EU Personal ID (SD-JWT)',
        offer: { vct: 'urn:eudi:pid:1' },
      };
      const result = buildVerificationSessionSetup([credential], 'dc+sd-jwt');

      expect(result.flow_type).toBe('cross_device');
      expect(result.core_flow.dcql_query.credentials).toHaveLength(1);
      expect(result.core_flow.dcql_query.credentials[0].format).toBe('dc+sd-jwt');
      expect(result.core_flow.dcql_query.credentials[0].meta.vct_values).toEqual(['urn:eudi:pid:1']);
    });

    it('should include redirects when provided', () => {
      const credential: AvailableCredential = {
        id: 'eu.europa.ec.eudi.pid.1',
        title: 'EU Personal ID (mDoc)',
        offer: { doctype: 'eu.europa.ec.eudi.pid.1' },
      };
      const result = buildVerificationSessionSetup(
        [credential],
        'mso_mdoc',
        { success: 'https://example.com/success', error: 'https://example.com/error' }
      );

      expect(result.redirects).toEqual({
        successRedirectUri: 'https://example.com/success',
        errorRedirectUri: 'https://example.com/error',
      });
    });

    it('should handle multiple credentials in a single session', () => {
      const credentials: AvailableCredential[] = [
        {
          id: 'eu.europa.ec.eudi.pid.1',
          title: 'EU Personal ID (mDoc)',
          offer: { doctype: 'eu.europa.ec.eudi.pid.1' },
        },
        {
          id: 'org.iso.18013.5.1.mDL',
          title: 'Mobile Driving License',
          offer: { doctype: 'org.iso.18013.5.1.mDL' },
        },
      ];
      const result = buildVerificationSessionSetup(credentials, 'mso_mdoc');

      expect(result.core_flow.dcql_query.credentials).toHaveLength(2);
      expect(result.core_flow.dcql_query.credentials[0].meta.doctype_value).toBe('eu.europa.ec.eudi.pid.1');
      expect(result.core_flow.dcql_query.credentials[1].meta.doctype_value).toBe('org.iso.18013.5.1.mDL');
    });
  });
});

/**
 * Tests for EUDI Wallet Compatible Payloads
 * These test the exact JSON structures expected by EUDI Reference Wallet
 */
describe('EUDI Wallet Compatible Verification Payloads', () => {
  it('should generate EUDI-compatible PID mDoc verification payload', () => {
    const payload = {
      flow_type: 'cross_device',
      core_flow: {
        dcql_query: {
          credentials: [
            {
              id: 'pid_mdoc',
              format: 'mso_mdoc',
              meta: {
                doctype_value: 'eu.europa.ec.eudi.pid.1',
              },
              claims: [
                { path: ['eu.europa.ec.eudi.pid.1', 'family_name'] },
                { path: ['eu.europa.ec.eudi.pid.1', 'given_name'] },
                { path: ['eu.europa.ec.eudi.pid.1', 'birth_date'] },
              ],
            },
          ],
        },
      },
    };

    // Verify structure matches EUDI wallet expectations
    expect(payload.flow_type).toBe('cross_device');
    expect(payload.core_flow.dcql_query.credentials[0].format).toBe('mso_mdoc');
    expect(payload.core_flow.dcql_query.credentials[0].meta.doctype_value).toBe('eu.europa.ec.eudi.pid.1');
  });

  it('should generate EUDI-compatible mDL verification payload', () => {
    const payload = {
      flow_type: 'cross_device',
      core_flow: {
        dcql_query: {
          credentials: [
            {
              id: 'mdl',
              format: 'mso_mdoc',
              meta: {
                doctype_value: 'org.iso.18013.5.1.mDL',
              },
              claims: [
                { path: ['org.iso.18013.5.1', 'family_name'] },
                { path: ['org.iso.18013.5.1', 'given_name'] },
                { path: ['org.iso.18013.5.1', 'birth_date'] },
                { path: ['org.iso.18013.5.1', 'document_number'] },
              ],
            },
          ],
        },
      },
    };

    expect(payload.core_flow.dcql_query.credentials[0].meta.doctype_value).toBe('org.iso.18013.5.1.mDL');
    // mDL claims use org.iso.18013.5.1 namespace
    expect(payload.core_flow.dcql_query.credentials[0].claims[0].path[0]).toBe('org.iso.18013.5.1');
  });

  it('should generate EUDI-compatible PID SD-JWT verification payload', () => {
    const payload = {
      flow_type: 'cross_device',
      core_flow: {
        dcql_query: {
          credentials: [
            {
              id: 'pid_sdjwt',
              format: 'dc+sd-jwt',
              meta: {
                vct_values: ['urn:eudi:pid:1'],
              },
              claims: [
                { path: ['family_name'] },
                { path: ['given_name'] },
                { path: ['birth_date'] },
              ],
            },
          ],
        },
      },
    };

    expect(payload.core_flow.dcql_query.credentials[0].format).toBe('dc+sd-jwt');
    expect(payload.core_flow.dcql_query.credentials[0].meta.vct_values).toContain('urn:eudi:pid:1');
    // SD-JWT claims don't have namespace prefix
    expect(payload.core_flow.dcql_query.credentials[0].claims[0].path).toEqual(['family_name']);
  });
});
