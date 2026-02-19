export interface CredentialTemplate {
  id: string;
  name: string;
  description: string;
  category: 'EUDI' | 'Financial' | 'Identity' | 'India' | 'Australia' | 'UK' | 'Custom';
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
  // -- India --
  {
    id: 'urn:in:gov:aadhaar:pid:1',
    name: 'Aadhaar Identity',
    description: 'Indian national identity (Aadhaar) in dc+sd-jwt format',
    category: 'India',
    format: 'dc+sd-jwt',
    config: {
      'urn:in:gov:aadhaar:pid:1': {
        format: 'dc+sd-jwt',
        cryptographic_binding_methods_supported: ['jwk'],
        credential_signing_alg_values_supported: ['ES256'],
        proof_types_supported: { jwt: { proof_signing_alg_values_supported: ['ES256'] } },
        vct: 'urn:in:gov:aadhaar:pid:1',
      },
    },
  },
  {
    id: 'urn:in:gov:pan:1',
    name: 'PAN Card',
    description: 'Indian Permanent Account Number card in dc+sd-jwt format',
    category: 'India',
    format: 'dc+sd-jwt',
    config: {
      'urn:in:gov:pan:1': {
        format: 'dc+sd-jwt',
        cryptographic_binding_methods_supported: ['jwk'],
        credential_signing_alg_values_supported: ['ES256'],
        proof_types_supported: { jwt: { proof_signing_alg_values_supported: ['ES256'] } },
        vct: 'urn:in:gov:pan:1',
      },
    },
  },
  {
    id: 'urn:in:gov:dl:1',
    name: 'Driving Licence (India)',
    description: 'Indian driving licence in dc+sd-jwt format',
    category: 'India',
    format: 'dc+sd-jwt',
    config: {
      'urn:in:gov:dl:1': {
        format: 'dc+sd-jwt',
        cryptographic_binding_methods_supported: ['jwk'],
        credential_signing_alg_values_supported: ['ES256'],
        proof_types_supported: { jwt: { proof_signing_alg_values_supported: ['ES256'] } },
        vct: 'urn:in:gov:dl:1',
      },
    },
  },
  // -- Australia --
  {
    id: 'urn:au:gov:mygovid:pid:1',
    name: 'myGovID Identity',
    description: 'Australian myGovID identity in dc+sd-jwt format',
    category: 'Australia',
    format: 'dc+sd-jwt',
    config: {
      'urn:au:gov:mygovid:pid:1': {
        format: 'dc+sd-jwt',
        cryptographic_binding_methods_supported: ['jwk'],
        credential_signing_alg_values_supported: ['ES256'],
        proof_types_supported: { jwt: { proof_signing_alg_values_supported: ['ES256'] } },
        vct: 'urn:au:gov:mygovid:pid:1',
      },
    },
  },
  {
    id: 'urn:au:gov:dl:1',
    name: 'Driving Licence (Australia)',
    description: 'Australian driving licence in dc+sd-jwt format',
    category: 'Australia',
    format: 'dc+sd-jwt',
    config: {
      'urn:au:gov:dl:1': {
        format: 'dc+sd-jwt',
        cryptographic_binding_methods_supported: ['jwk'],
        credential_signing_alg_values_supported: ['ES256'],
        proof_types_supported: { jwt: { proof_signing_alg_values_supported: ['ES256'] } },
        vct: 'urn:au:gov:dl:1',
      },
    },
  },
  {
    id: 'urn:au:gov:medicare:1',
    name: 'Medicare Card',
    description: 'Australian Medicare card in dc+sd-jwt format',
    category: 'Australia',
    format: 'dc+sd-jwt',
    config: {
      'urn:au:gov:medicare:1': {
        format: 'dc+sd-jwt',
        cryptographic_binding_methods_supported: ['jwk'],
        credential_signing_alg_values_supported: ['ES256'],
        proof_types_supported: { jwt: { proof_signing_alg_values_supported: ['ES256'] } },
        vct: 'urn:au:gov:medicare:1',
      },
    },
  },
  // -- UK --
  {
    id: 'urn:uk:gov:onelogin:pid:1',
    name: 'GOV.UK Identity',
    description: 'UK GOV.UK One Login identity in dc+sd-jwt format',
    category: 'UK',
    format: 'dc+sd-jwt',
    config: {
      'urn:uk:gov:onelogin:pid:1': {
        format: 'dc+sd-jwt',
        cryptographic_binding_methods_supported: ['jwk'],
        credential_signing_alg_values_supported: ['ES256'],
        proof_types_supported: { jwt: { proof_signing_alg_values_supported: ['ES256'] } },
        vct: 'urn:uk:gov:onelogin:pid:1',
      },
    },
  },
  {
    id: 'urn:uk:gov:dvla:dl:1',
    name: 'Driving Licence (UK)',
    description: 'UK DVLA driving licence in dc+sd-jwt format',
    category: 'UK',
    format: 'dc+sd-jwt',
    config: {
      'urn:uk:gov:dvla:dl:1': {
        format: 'dc+sd-jwt',
        cryptographic_binding_methods_supported: ['jwk'],
        credential_signing_alg_values_supported: ['ES256'],
        proof_types_supported: { jwt: { proof_signing_alg_values_supported: ['ES256'] } },
        vct: 'urn:uk:gov:dvla:dl:1',
      },
    },
  },
  {
    id: 'urn:uk:gov:rtw:1',
    name: 'Right to Work',
    description: 'UK Right to Work credential in dc+sd-jwt format',
    category: 'UK',
    format: 'dc+sd-jwt',
    config: {
      'urn:uk:gov:rtw:1': {
        format: 'dc+sd-jwt',
        cryptographic_binding_methods_supported: ['jwk'],
        credential_signing_alg_values_supported: ['ES256'],
        proof_types_supported: { jwt: { proof_signing_alg_values_supported: ['ES256'] } },
        vct: 'urn:uk:gov:rtw:1',
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
