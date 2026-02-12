import * as mod from '../types/credentialTemplates';
const { credentialTemplates, getTemplatesByCategory } = mod;
type CredentialTemplate = mod.CredentialTemplate;

describe('Credential Template Library', () => {
  it('should have templates organized by category', () => {
    expect(credentialTemplates).toBeDefined();
    expect(Array.isArray(credentialTemplates)).toBe(true);
    expect(credentialTemplates.length).toBeGreaterThan(0);
    const mapped = credentialTemplates.map(t => t.category);
    expect(mapped.length).toBeGreaterThan(0);
    const categories = Array.from(new Set(mapped));
    expect(categories).toContain('EUDI');
    expect(categories).toContain('Financial');
    expect(categories).toContain('Identity');
  });

  it('each template should have required fields', () => {
    credentialTemplates.forEach((t: CredentialTemplate) => {
      expect(t.id).toBeTruthy();
      expect(t.name).toBeTruthy();
      expect(t.category).toBeTruthy();
      expect(t.config).toBeDefined();
      expect(typeof t.config).toBe('object');
    });
  });

  it('EUDI templates should have correct format', () => {
    const eudiTemplates = credentialTemplates.filter(t => t.category === 'EUDI');
    expect(eudiTemplates.length).toBeGreaterThanOrEqual(3);
    eudiTemplates.forEach(t => {
      const configValue = Object.values(t.config)[0] as any;
      expect(['mso_mdoc', 'dc+sd-jwt']).toContain(configValue.format);
    });
  });

  it('getTemplatesByCategory should filter correctly', () => {
    const eudi = getTemplatesByCategory('EUDI');
    eudi.forEach(t => expect(t.category).toBe('EUDI'));
  });

  it('template config keys should match template id', () => {
    credentialTemplates.forEach(t => {
      expect(Object.keys(t.config)).toContain(t.id);
    });
  });
});
