export interface CredentialTemplate {
  id: string;
  name: string;
  description: string;
  category: 'EUDI' | 'Financial' | 'Identity' | 'Custom';
  format: string;
  config: Record<string, any>;
}

export const credentialTemplates: CredentialTemplate[] = [
  // -- EUDI --
  {
    id: 'eu.europa.ec.eudi.pid.1',
    name: 'EU Personal ID (mDoc)',
    description: 'EUDI PID credential in mso_mdoc format',
    category: 'EUDI',
    format: 'mso_mdoc',
    config: {
      'eu.europa.ec.eudi.pid.1': {
        format: 'mso_mdoc',
        cryptographic_binding_methods_supported: ['cose_key'],
        credential_signing_alg_values_supported: ['ES256'],
        proof_types_supported: { jwt: { proof_signing_alg_values_supported: ['ES256'] } },
        doctype: 'eu.europa.ec.eudi.pid.1',
      },
    },
  },
  {
    id: 'org.iso.18013.5.1.mDL',
    name: 'Mobile Driving License',
    description: 'ISO 18013-5 mDL in mso_mdoc format',
    category: 'EUDI',
    format: 'mso_mdoc',
    config: {
      'org.iso.18013.5.1.mDL': {
        format: 'mso_mdoc',
        cryptographic_binding_methods_supported: ['cose_key'],
        credential_signing_alg_values_supported: ['ES256'],
        proof_types_supported: { jwt: { proof_signing_alg_values_supported: ['ES256'] } },
        doctype: 'org.iso.18013.5.1.mDL',
      },
    },
  },
  {
    id: 'urn:eudi:pid:1',
    name: 'EU Personal ID (SD-JWT)',
    description: 'EUDI PID credential in dc+sd-jwt format',
    category: 'EUDI',
    format: 'dc+sd-jwt',
    config: {
      'urn:eudi:pid:1': {
        format: 'dc+sd-jwt',
        cryptographic_binding_methods_supported: ['jwk'],
        credential_signing_alg_values_supported: ['ES256'],
        proof_types_supported: { jwt: { proof_signing_alg_values_supported: ['ES256'] } },
        vct: 'urn:eudi:pid:1',
      },
    },
  },
  // -- Financial --
  {
    id: 'BankId_jwt_vc_json',
    name: 'Bank ID',
    description: 'Bank identity credential in JWT format',
    category: 'Financial',
    format: 'jwt_vc_json',
    config: {
      'BankId_jwt_vc_json': {
        format: 'jwt_vc_json',
        cryptographic_binding_methods_supported: ['did'],
        credential_signing_alg_values_supported: ['ES256', 'EdDSA'],
        credential_definition: { type: ['VerifiableCredential', 'BankId'] },
      },
    },
  },
  {
    id: 'PaymentWalletAttestation',
    name: 'Payment Wallet Attestation',
    description: 'EWC RFC007 payment funding source binding',
    category: 'Financial',
    format: 'dc+sd-jwt',
    config: {
      'PaymentWalletAttestation': {
        format: 'dc+sd-jwt',
        cryptographic_binding_methods_supported: ['jwk'],
        credential_signing_alg_values_supported: ['ES256'],
        proof_types_supported: { jwt: { proof_signing_alg_values_supported: ['ES256'] } },
        vct: 'PaymentWalletAttestation',
      },
    },
  },
  // -- Identity --
  {
    id: 'VerifiableId_jwt_vc_json',
    name: 'National ID',
    description: 'National identity document in JWT format',
    category: 'Identity',
    format: 'jwt_vc_json',
    config: {
      'VerifiableId_jwt_vc_json': {
        format: 'jwt_vc_json',
        cryptographic_binding_methods_supported: ['did'],
        credential_signing_alg_values_supported: ['ES256', 'EdDSA'],
        credential_definition: { type: ['VerifiableCredential', 'VerifiableId'] },
      },
    },
  },
  {
    id: 'Passport_jwt_vc_json',
    name: 'Passport',
    description: 'Passport credential in JWT format',
    category: 'Identity',
    format: 'jwt_vc_json',
    config: {
      'Passport_jwt_vc_json': {
        format: 'jwt_vc_json',
        cryptographic_binding_methods_supported: ['did'],
        credential_signing_alg_values_supported: ['ES256', 'EdDSA'],
        credential_definition: { type: ['VerifiableCredential', 'VerifiableAttestation', 'Passport'] },
      },
    },
  },
  {
    id: 'ResidencePermit_jwt_vc_json',
    name: 'Residence Permit',
    description: 'Residence permit credential in JWT format',
    category: 'Identity',
    format: 'jwt_vc_json',
    config: {
      'ResidencePermit_jwt_vc_json': {
        format: 'jwt_vc_json',
        cryptographic_binding_methods_supported: ['did'],
        credential_signing_alg_values_supported: ['ES256', 'EdDSA'],
        credential_definition: { type: ['VerifiableCredential', 'VerifiableAttestation', 'ResidencePermit'] },
      },
    },
  },
];

export function getTemplatesByCategory(category: CredentialTemplate['category']): CredentialTemplate[] {
  return credentialTemplates.filter(t => t.category === category);
}

export function getTemplateById(id: string): CredentialTemplate | undefined {
  return credentialTemplates.find(t => t.id === id);
}
