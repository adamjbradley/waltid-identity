export interface ClaimDefinition {
  path: string[];
}

export type AvailableCredential = {
  id: string;
  title: string;
  selectedFormat?: String;
  selectedDID?: String;
  offer: any;
  defaultClaims?: ClaimDefinition[];
  editedClaims?: ClaimDefinition[];
};

export const EudiCredentials: AvailableCredential[] = [
  {
    id: 'eu.europa.ec.eudi.pid.1',
    title: 'EU Personal ID (mDoc)',
    offer: {
      'eu.europa.ec.eudi.pid.1': {
        family_name: 'Doe',
        given_name: 'John',
        birth_date: '1990-01-15',
        age_over_18: true,
        age_over_21: true,
        issuance_date: '2024-01-01',
        expiry_date: '2034-01-01',
        issuing_authority: 'Test Authority',
        issuing_country: 'AU',
      }
    },
    defaultClaims: [
      { path: ['eu.europa.ec.eudi.pid.1', 'family_name'] },
      { path: ['eu.europa.ec.eudi.pid.1', 'given_name'] },
      { path: ['eu.europa.ec.eudi.pid.1', 'birth_date'] },
    ]
  },
  {
    id: 'org.iso.18013.5.1.mDL',
    title: 'Mobile Driving License',
    offer: {
      'org.iso.18013.5.1': {
        family_name: 'Doe',
        given_name: 'John',
        birth_date: '1990-01-15',
        issue_date: '2024-01-01',
        expiry_date: '2034-01-01',
        issuing_country: 'AU',
        issuing_authority: 'Test Authority',
        document_number: 'DL123456789',
        portrait: '',
        driving_privileges: [
          {
            vehicle_category_code: 'C',
            issue_date: '2024-01-01',
            expiry_date: '2034-01-01',
          }
        ],
      }
    },
    defaultClaims: [
      { path: ['org.iso.18013.5.1', 'family_name'] },
      { path: ['org.iso.18013.5.1', 'given_name'] },
      { path: ['org.iso.18013.5.1', 'birth_date'] },
    ]
  },
  {
    id: 'urn:eudi:pid:1',
    title: 'EU Personal ID (SD-JWT)',
    offer: {
      credentialSubject: {
        family_name: 'Doe',
        given_name: 'John',
        birth_date: '1990-01-15',
        age_over_18: true,
        age_over_21: true,
        issuance_date: '2024-01-01',
        expiry_date: '2034-01-01',
        issuing_authority: 'Test Authority',
        issuing_country: 'AU',
      }
    },
    defaultClaims: [
      { path: ['family_name'] },
      { path: ['given_name'] },
      { path: ['birth_date'] },
    ]
  },
  {
    id: 'PaymentWalletAttestation',
    title: 'Payment Wallet Attestation',
    offer: {
      credentialSubject: {
        fundingSource: {
          type: 'card',
          panLastFour: '1234',
          iin: '411111',
          scheme: 'Visa',
          currency: 'EUR',
          icon: 'https://example.com/visa-icon.png',
          aliasId: 'pwa_visa_1234',
        },
      },
      vct: 'PaymentWalletAttestation',
    },
    defaultClaims: [
      { path: ['fundingSource'] },
      { path: ['fundingSource', 'type'] },
      { path: ['fundingSource', 'panLastFour'] },
      { path: ['fundingSource', 'scheme'] },
    ]
  },
  // Australia
  {
    id: 'urn:au:gov:mygovid:pid:1',
    title: 'myGovID Identity',
    offer: { credentialSubject: { family_name: 'Doe', given_name: 'John', birth_date: '1990-01-15', issuing_country: 'AU' } },
    defaultClaims: [{ path: ['family_name'] }, { path: ['given_name'] }, { path: ['birth_date'] }],
  },
  {
    id: 'au.gov.mygovid.pid.1',
    title: 'myGovID (mDoc)',
    offer: { 'eu.europa.ec.eudi.pid.1': { family_name: 'Doe', given_name: 'John', birth_date: '1990-01-15', issuing_country: 'AU' } },
    defaultClaims: [
      { path: ['eu.europa.ec.eudi.pid.1', 'family_name'] },
      { path: ['eu.europa.ec.eudi.pid.1', 'given_name'] },
      { path: ['eu.europa.ec.eudi.pid.1', 'birth_date'] },
    ],
  },
  {
    id: 'urn:au:gov:dl:1',
    title: 'Driving Licence (Australia)',
    offer: { credentialSubject: { family_name: 'Doe', given_name: 'John', birth_date: '1990-01-15', document_number: 'DL000000', issuing_country: 'AU' } },
    defaultClaims: [{ path: ['family_name'] }, { path: ['given_name'] }, { path: ['document_number'] }],
  },
  {
    id: 'urn:au:gov:medicare:1',
    title: 'Medicare Card',
    offer: { credentialSubject: { family_name: 'Doe', given_name: 'John', medicare_number: '0000 00000 0', issuing_country: 'AU' } },
    defaultClaims: [{ path: ['family_name'] }, { path: ['given_name'] }, { path: ['medicare_number'] }],
  },
  {
    id: 'au.gov.medicare.1',
    title: 'Medicare Card (mDoc)',
    offer: { 'au.gov.medicare.1': { family_name: 'Doe', given_name: 'John', medicare_number: '0000 00000 0', issuing_country: 'AU' } },
    defaultClaims: [
      { path: ['au.gov.medicare.1', 'family_name'] },
      { path: ['au.gov.medicare.1', 'given_name'] },
      { path: ['au.gov.medicare.1', 'medicare_number'] },
    ],
  },
  // India
  {
    id: 'urn:in:gov:aadhaar:pid:1',
    title: 'Aadhaar Identity',
    offer: { credentialSubject: { family_name: 'Doe', given_name: 'John', birth_date: '1990-01-15', issuing_country: 'IN' } },
    defaultClaims: [{ path: ['family_name'] }, { path: ['given_name'] }, { path: ['birth_date'] }],
  },
  {
    id: 'in.gov.aadhaar.pid.1',
    title: 'Aadhaar (mDoc)',
    offer: { 'eu.europa.ec.eudi.pid.1': { family_name: 'Doe', given_name: 'John', birth_date: '1990-01-15', issuing_country: 'IN' } },
    defaultClaims: [
      { path: ['eu.europa.ec.eudi.pid.1', 'family_name'] },
      { path: ['eu.europa.ec.eudi.pid.1', 'given_name'] },
      { path: ['eu.europa.ec.eudi.pid.1', 'birth_date'] },
    ],
  },
  {
    id: 'urn:in:gov:dl:1',
    title: 'Driving Licence (India)',
    offer: { credentialSubject: { family_name: 'Doe', given_name: 'John', birth_date: '1990-01-15', document_number: 'DL000000', issuing_country: 'IN' } },
    defaultClaims: [{ path: ['family_name'] }, { path: ['given_name'] }, { path: ['document_number'] }],
  },
  {
    id: 'urn:in:gov:pan:1',
    title: 'PAN Card',
    offer: { credentialSubject: { family_name: 'Doe', given_name: 'John', pan_number: 'AAAAA0000A', issuing_country: 'IN' } },
    defaultClaims: [{ path: ['family_name'] }, { path: ['given_name'] }, { path: ['pan_number'] }],
  },
  // United Kingdom
  {
    id: 'urn:uk:gov:govuk-one-login:pid:1',
    title: 'GOV.UK One Login Identity',
    offer: { credentialSubject: { family_name: 'Doe', given_name: 'John', birth_date: '1990-01-15', issuing_country: 'GB' } },
    defaultClaims: [{ path: ['family_name'] }, { path: ['given_name'] }, { path: ['birth_date'] }],
  },
  {
    id: 'urn:uk:gov:dl:1',
    title: 'Driving Licence (UK)',
    offer: { credentialSubject: { family_name: 'Doe', given_name: 'John', birth_date: '1990-01-15', document_number: 'DL000000', issuing_country: 'GB' } },
    defaultClaims: [{ path: ['family_name'] }, { path: ['given_name'] }, { path: ['document_number'] }],
  },
  {
    id: 'urn:uk:gov:nhs:1',
    title: 'NHS Number',
    offer: { credentialSubject: { family_name: 'Doe', given_name: 'John', nhs_number: '000 000 0000', issuing_country: 'GB' } },
    defaultClaims: [{ path: ['family_name'] }, { path: ['given_name'] }, { path: ['nhs_number'] }],
  },
];

// --- Country-specific credential data ---

export interface CountryCredentialData {
  offer: any;
  defaultClaims: ClaimDefinition[];
}

export interface CountryEntry {
  code: string;
  name: string;
  flag: string;
  credentials: {
    id: string;
    title: string;
    format: string; // display format label
    data: CountryCredentialData;
  }[];
}

const COUNTRY_CLAIM_DATA: Record<string, CountryEntry> = {
  AU: {
    code: 'AU',
    name: 'Australia',
    flag: '\u{1F1E6}\u{1F1FA}',
    credentials: [
      {
        id: 'urn:au:gov:mygovid:pid:1',
        title: 'myGovID Identity',
        format: 'DC+SD-JWT',
        data: {
          offer: {
            credentialSubject: {
              family_name: 'Mitchell',
              given_name: 'Sarah',
              birth_date: '1988-03-22',
              age_over_18: true,
              age_over_21: true,
              issuance_date: '2024-01-01',
              expiry_date: '2034-01-01',
              issuing_authority: 'Australian Digital Identity Office',
              issuing_country: 'AU',
            },
          },
          defaultClaims: [
            { path: ['family_name'] },
            { path: ['given_name'] },
            { path: ['birth_date'] },
          ],
        },
      },
      {
        id: 'urn:au:gov:dl:1',
        title: 'Driving Licence (Australia)',
        format: 'DC+SD-JWT',
        data: {
          offer: {
            credentialSubject: {
              family_name: 'Mitchell',
              given_name: 'Sarah',
              birth_date: '1988-03-22',
              issue_date: '2024-01-01',
              expiry_date: '2034-01-01',
              issuing_country: 'AU',
              issuing_authority: 'Roads and Maritime Services NSW',
              document_number: 'DL987654321',
              driving_privileges: [
                { vehicle_category_code: 'C', issue_date: '2024-01-01', expiry_date: '2034-01-01' },
              ],
            },
          },
          defaultClaims: [
            { path: ['family_name'] },
            { path: ['given_name'] },
            { path: ['document_number'] },
          ],
        },
      },
      {
        id: 'urn:au:gov:medicare:1',
        title: 'Medicare Card',
        format: 'DC+SD-JWT',
        data: {
          offer: {
            credentialSubject: {
              family_name: 'Mitchell',
              given_name: 'Sarah',
              birth_date: '1988-03-22',
              medicare_number: '2123 45670 1',
              card_expiry: '2029-01',
              issuing_authority: 'Services Australia',
              issuing_country: 'AU',
            },
          },
          defaultClaims: [
            { path: ['family_name'] },
            { path: ['given_name'] },
            { path: ['medicare_number'] },
          ],
        },
      },
      {
        id: 'PaymentWalletAttestation',
        title: 'Payment Wallet Attestation',
        format: 'DC+SD-JWT',
        data: {
          offer: {
            credentialSubject: {
              fundingSource: {
                type: 'card',
                panLastFour: '4321',
                iin: '512345',
                scheme: 'mastercard',
                currency: 'AUD',
                icon: 'https://example.com/mc-icon.png',
                aliasId: 'pwa_mc_4321',
              },
            },
            vct: 'PaymentWalletAttestation',
          },
          defaultClaims: [
            { path: ['fundingSource'] },
            { path: ['fundingSource', 'type'] },
            { path: ['fundingSource', 'panLastFour'] },
            { path: ['fundingSource', 'scheme'] },
          ],
        },
      },
    ],
  },
  IN: {
    code: 'IN',
    name: 'India',
    flag: '\u{1F1EE}\u{1F1F3}',
    credentials: [
      {
        id: 'urn:in:gov:aadhaar:pid:1',
        title: 'Aadhaar Identity',
        format: 'DC+SD-JWT',
        data: {
          offer: {
            credentialSubject: {
              family_name: 'Sharma',
              given_name: 'Priya',
              birth_date: '1992-07-10',
              age_over_18: true,
              issuance_date: '2024-01-01',
              expiry_date: '2034-01-01',
              issuing_authority: 'Unique Identification Authority of India',
              issuing_country: 'IN',
            },
          },
          defaultClaims: [
            { path: ['family_name'] },
            { path: ['given_name'] },
            { path: ['birth_date'] },
          ],
        },
      },
      {
        id: 'urn:in:gov:dl:1',
        title: 'Driving Licence (India)',
        format: 'DC+SD-JWT',
        data: {
          offer: {
            credentialSubject: {
              family_name: 'Sharma',
              given_name: 'Priya',
              birth_date: '1992-07-10',
              issue_date: '2024-01-01',
              expiry_date: '2034-01-01',
              issuing_country: 'IN',
              issuing_authority: 'Ministry of Road Transport',
              document_number: 'MH0120240001234',
              driving_privileges: [
                { vehicle_category_code: 'LMV', issue_date: '2024-01-01', expiry_date: '2034-01-01' },
              ],
            },
          },
          defaultClaims: [
            { path: ['family_name'] },
            { path: ['given_name'] },
            { path: ['document_number'] },
          ],
        },
      },
      {
        id: 'urn:in:gov:pan:1',
        title: 'PAN Card',
        format: 'DC+SD-JWT',
        data: {
          offer: {
            credentialSubject: {
              family_name: 'Sharma',
              given_name: 'Priya',
              birth_date: '1992-07-10',
              pan_number: 'ABCPS1234F',
              issuing_authority: 'Income Tax Department',
              issuing_country: 'IN',
            },
          },
          defaultClaims: [
            { path: ['family_name'] },
            { path: ['given_name'] },
            { path: ['pan_number'] },
          ],
        },
      },
      {
        id: 'PaymentWalletAttestation',
        title: 'Payment Wallet Attestation',
        format: 'DC+SD-JWT',
        data: {
          offer: {
            credentialSubject: {
              fundingSource: {
                type: 'card',
                panLastFour: '5678',
                iin: '512345',
                scheme: 'mastercard',
                currency: 'INR',
                icon: 'https://example.com/mc-icon.png',
                aliasId: 'pwa_mc_5678',
              },
            },
            vct: 'PaymentWalletAttestation',
          },
          defaultClaims: [
            { path: ['fundingSource'] },
            { path: ['fundingSource', 'type'] },
            { path: ['fundingSource', 'panLastFour'] },
            { path: ['fundingSource', 'scheme'] },
          ],
        },
      },
    ],
  },
  GB: {
    code: 'GB',
    name: 'United Kingdom',
    flag: '\u{1F1EC}\u{1F1E7}',
    credentials: [
      {
        id: 'urn:uk:gov:govuk-one-login:pid:1',
        title: 'GOV.UK One Login Identity',
        format: 'DC+SD-JWT',
        data: {
          offer: {
            credentialSubject: {
              family_name: 'Williams',
              given_name: 'James',
              birth_date: '1987-04-12',
              age_over_18: true,
              issuance_date: '2024-01-01',
              expiry_date: '2034-01-01',
              issuing_authority: 'Government Digital Service',
              issuing_country: 'GB',
            },
          },
          defaultClaims: [
            { path: ['family_name'] },
            { path: ['given_name'] },
            { path: ['birth_date'] },
          ],
        },
      },
      {
        id: 'urn:uk:gov:dl:1',
        title: 'Driving Licence (UK)',
        format: 'DC+SD-JWT',
        data: {
          offer: {
            credentialSubject: {
              family_name: 'Williams',
              given_name: 'James',
              birth_date: '1987-04-12',
              issue_date: '2024-01-01',
              expiry_date: '2034-01-01',
              issuing_country: 'GB',
              issuing_authority: 'DVLA',
              document_number: 'WILLI874120JA9AB',
              driving_privileges: [
                { vehicle_category_code: 'B', issue_date: '2024-01-01', expiry_date: '2034-01-01' },
              ],
            },
          },
          defaultClaims: [
            { path: ['family_name'] },
            { path: ['given_name'] },
            { path: ['document_number'] },
          ],
        },
      },
      {
        id: 'urn:uk:gov:nhs:1',
        title: 'NHS Number',
        format: 'DC+SD-JWT',
        data: {
          offer: {
            credentialSubject: {
              family_name: 'Williams',
              given_name: 'James',
              birth_date: '1987-04-12',
              nhs_number: '943 476 5919',
              issuing_authority: 'NHS Digital',
              issuing_country: 'GB',
            },
          },
          defaultClaims: [
            { path: ['family_name'] },
            { path: ['given_name'] },
            { path: ['nhs_number'] },
          ],
        },
      },
      {
        id: 'PaymentWalletAttestation',
        title: 'Payment Wallet Attestation',
        format: 'DC+SD-JWT',
        data: {
          offer: {
            credentialSubject: {
              fundingSource: {
                type: 'card',
                panLastFour: '7890',
                iin: '512345',
                scheme: 'mastercard',
                currency: 'GBP',
                icon: 'https://example.com/mc-icon.png',
                aliasId: 'pwa_mc_7890',
              },
            },
            vct: 'PaymentWalletAttestation',
          },
          defaultClaims: [
            { path: ['fundingSource'] },
            { path: ['fundingSource', 'type'] },
            { path: ['fundingSource', 'panLastFour'] },
            { path: ['fundingSource', 'scheme'] },
          ],
        },
      },
    ],
  },
  SG: {
    code: 'SG',
    name: 'Singapore',
    flag: '\u{1F1F8}\u{1F1EC}',
    credentials: [
      {
        id: 'eu.europa.ec.eudi.pid.1',
        title: 'EU Personal ID (mDoc)',
        format: 'mDoc',
        data: {
          offer: {
            'eu.europa.ec.eudi.pid.1': {
              family_name: 'Tan',
              given_name: 'Wei Lin',
              birth_date: '1995-11-05',
              age_over_18: true,
              age_over_21: true,
              issuance_date: '2024-01-01',
              expiry_date: '2034-01-01',
              issuing_authority: 'Government Technology Agency',
              issuing_country: 'SG',
            },
          },
          defaultClaims: [
            { path: ['eu.europa.ec.eudi.pid.1', 'family_name'] },
            { path: ['eu.europa.ec.eudi.pid.1', 'given_name'] },
            { path: ['eu.europa.ec.eudi.pid.1', 'birth_date'] },
          ],
        },
      },
      {
        id: 'PaymentWalletAttestation',
        title: 'Payment Wallet Attestation',
        format: 'DC+SD-JWT',
        data: {
          offer: {
            credentialSubject: {
              fundingSource: {
                type: 'card',
                panLastFour: '8888',
                iin: '512345',
                scheme: 'mastercard',
                currency: 'SGD',
                icon: 'https://example.com/mc-icon.png',
                aliasId: 'pwa_mc_8888',
              },
            },
            vct: 'PaymentWalletAttestation',
          },
          defaultClaims: [
            { path: ['fundingSource'] },
            { path: ['fundingSource', 'type'] },
            { path: ['fundingSource', 'panLastFour'] },
            { path: ['fundingSource', 'scheme'] },
          ],
        },
      },
    ],
  },
  DE: {
    code: 'DE',
    name: 'Germany',
    flag: '\u{1F1E9}\u{1F1EA}',
    credentials: [
      {
        id: 'eu.europa.ec.eudi.pid.1',
        title: 'EU Personal ID (mDoc)',
        format: 'mDoc',
        data: {
          offer: {
            'eu.europa.ec.eudi.pid.1': {
              family_name: 'Schneider',
              given_name: 'Lukas',
              birth_date: '1985-06-14',
              age_over_18: true,
              age_over_21: true,
              issuance_date: '2024-01-01',
              expiry_date: '2034-01-01',
              issuing_authority: 'Bundesdruckerei GmbH',
              issuing_country: 'DE',
            },
          },
          defaultClaims: [
            { path: ['eu.europa.ec.eudi.pid.1', 'family_name'] },
            { path: ['eu.europa.ec.eudi.pid.1', 'given_name'] },
            { path: ['eu.europa.ec.eudi.pid.1', 'birth_date'] },
          ],
        },
      },
      {
        id: 'org.iso.18013.5.1.mDL',
        title: 'Mobile Driving License',
        format: 'mDoc',
        data: {
          offer: {
            'org.iso.18013.5.1': {
              family_name: 'Schneider',
              given_name: 'Lukas',
              birth_date: '1985-06-14',
              issue_date: '2024-01-01',
              expiry_date: '2034-01-01',
              issuing_country: 'DE',
              issuing_authority: 'Kraftfahrt-Bundesamt',
              document_number: 'B072024001234',
              portrait: '',
              driving_privileges: [
                { vehicle_category_code: 'B', issue_date: '2024-01-01', expiry_date: '2034-01-01' },
              ],
            },
          },
          defaultClaims: [
            { path: ['org.iso.18013.5.1', 'family_name'] },
            { path: ['org.iso.18013.5.1', 'given_name'] },
            { path: ['org.iso.18013.5.1', 'birth_date'] },
          ],
        },
      },
      {
        id: 'urn:eudi:pid:1',
        title: 'EU Personal ID (SD-JWT)',
        format: 'DC+SD-JWT',
        data: {
          offer: {
            credentialSubject: {
              family_name: 'Schneider',
              given_name: 'Lukas',
              birth_date: '1985-06-14',
              age_over_18: true,
              age_over_21: true,
              issuance_date: '2024-01-01',
              expiry_date: '2034-01-01',
              issuing_authority: 'Bundesdruckerei GmbH',
              issuing_country: 'DE',
            },
          },
          defaultClaims: [
            { path: ['family_name'] },
            { path: ['given_name'] },
            { path: ['birth_date'] },
          ],
        },
      },
      {
        id: 'PaymentWalletAttestation',
        title: 'Payment Wallet Attestation',
        format: 'DC+SD-JWT',
        data: {
          offer: {
            credentialSubject: {
              fundingSource: {
                type: 'card',
                panLastFour: '2345',
                iin: '512345',
                scheme: 'mastercard',
                currency: 'EUR',
                icon: 'https://example.com/mc-icon.png',
                aliasId: 'pwa_mc_2345',
              },
            },
            vct: 'PaymentWalletAttestation',
          },
          defaultClaims: [
            { path: ['fundingSource'] },
            { path: ['fundingSource', 'type'] },
            { path: ['fundingSource', 'panLastFour'] },
            { path: ['fundingSource', 'scheme'] },
          ],
        },
      },
    ],
  },
  FR: {
    code: 'FR',
    name: 'France',
    flag: '\u{1F1EB}\u{1F1F7}',
    credentials: [
      {
        id: 'org.iso.18013.5.1.mDL',
        title: 'Mobile Driving License',
        format: 'mDoc',
        data: {
          offer: {
            'org.iso.18013.5.1': {
              family_name: 'Dupont',
              given_name: 'Marie',
              birth_date: '1991-09-28',
              issue_date: '2024-01-01',
              expiry_date: '2034-01-01',
              issuing_country: 'FR',
              issuing_authority: 'Prefecture de Police',
              document_number: 'FR2024000567',
              portrait: '',
              driving_privileges: [
                { vehicle_category_code: 'B', issue_date: '2024-01-01', expiry_date: '2034-01-01' },
              ],
            },
          },
          defaultClaims: [
            { path: ['org.iso.18013.5.1', 'family_name'] },
            { path: ['org.iso.18013.5.1', 'given_name'] },
            { path: ['org.iso.18013.5.1', 'birth_date'] },
          ],
        },
      },
      {
        id: 'urn:eudi:pid:1',
        title: 'EU Personal ID (SD-JWT)',
        format: 'DC+SD-JWT',
        data: {
          offer: {
            credentialSubject: {
              family_name: 'Dupont',
              given_name: 'Marie',
              birth_date: '1991-09-28',
              age_over_18: true,
              age_over_21: true,
              issuance_date: '2024-01-01',
              expiry_date: '2034-01-01',
              issuing_authority: 'Agence Nationale des Titres Securises',
              issuing_country: 'FR',
            },
          },
          defaultClaims: [
            { path: ['family_name'] },
            { path: ['given_name'] },
            { path: ['birth_date'] },
          ],
        },
      },
      {
        id: 'PaymentWalletAttestation',
        title: 'Payment Wallet Attestation',
        format: 'DC+SD-JWT',
        data: {
          offer: {
            credentialSubject: {
              fundingSource: {
                type: 'card',
                panLastFour: '6789',
                iin: '512345',
                scheme: 'mastercard',
                currency: 'EUR',
                icon: 'https://example.com/mc-icon.png',
                aliasId: 'pwa_mc_6789',
              },
            },
            vct: 'PaymentWalletAttestation',
          },
          defaultClaims: [
            { path: ['fundingSource'] },
            { path: ['fundingSource', 'type'] },
            { path: ['fundingSource', 'panLastFour'] },
            { path: ['fundingSource', 'scheme'] },
          ],
        },
      },
    ],
  },
};

export function getCountryCredentialData(
  country: string,
  credentialId: string
): CountryCredentialData | null {
  const entry = COUNTRY_CLAIM_DATA[country];
  if (!entry) return null;
  const cred = entry.credentials.find((c) => c.id === credentialId);
  return cred?.data ?? null;
}

export function getAllCountries(): CountryEntry[] {
  return Object.values(COUNTRY_CLAIM_DATA).sort((a, b) =>
    a.name.localeCompare(b.name)
  );
}

export const CredentialFormats = [
  'JWT + W3C VC',
  'SD-JWT + W3C VC',
  'SD-JWT + IETF SD-JWT VC',
  'DC+SD-JWT (EUDI)',
  'mDoc (ISO 18013-5)',
];

// Map credential IDs to their supported formats (based on issuer configuration)
// eu.europa.ec.eudi.pid.1 -> mso_mdoc only
// org.iso.18013.5.1.mDL -> mso_mdoc only
// eu.europa.ec.eudi.pid_vc_sd_jwt / urn:eudi:pid:1 -> dc+sd-jwt only
// PaymentWalletAttestation -> dc+sd-jwt only
const CREDENTIAL_FORMAT_MAP: Record<string, string[]> = {
  // EUDI standard (EU countries)
  'eu.europa.ec.eudi.pid.1': ['mDoc (ISO 18013-5)'],
  'org.iso.18013.5.1.mDL': ['mDoc (ISO 18013-5)'],
  'urn:eudi:pid:1': ['DC+SD-JWT (EUDI)'],
  'PaymentWalletAttestation': ['DC+SD-JWT (EUDI)'],
  // Country-specific (non-EU)
  'urn:au:gov:mygovid:pid:1': ['DC+SD-JWT (EUDI)'],
  'au.gov.mygovid.pid.1': ['mDoc (ISO 18013-5)'],
  'urn:au:gov:dl:1': ['DC+SD-JWT (EUDI)'],
  'urn:au:gov:medicare:1': ['DC+SD-JWT (EUDI)'],
  'au.gov.medicare.1': ['mDoc (ISO 18013-5)'],
  'urn:in:gov:aadhaar:pid:1': ['DC+SD-JWT (EUDI)'],
  'in.gov.aadhaar.pid.1': ['mDoc (ISO 18013-5)'],
  'urn:in:gov:dl:1': ['DC+SD-JWT (EUDI)'],
  'urn:in:gov:pan:1': ['DC+SD-JWT (EUDI)'],
  'urn:uk:gov:govuk-one-login:pid:1': ['DC+SD-JWT (EUDI)'],
  'urn:uk:gov:dl:1': ['DC+SD-JWT (EUDI)'],
  'urn:uk:gov:nhs:1': ['DC+SD-JWT (EUDI)'],
};

// Get available formats for a credential based on issuer support
export function getAvailableFormatsForCredential(credentialId: string): string[] {
  return CREDENTIAL_FORMAT_MAP[credentialId] || CredentialFormats.filter(
    f => f !== 'DC+SD-JWT (EUDI)' && f !== 'mDoc (ISO 18013-5)'
  );
}

// Get the default format for a credential
export function getDefaultFormatForCredential(credentialId: string): string {
  const formats = getAvailableFormatsForCredential(credentialId);
  return formats[0];
}

// Check if credential is EUDI-only (has restricted formats)
export function isEudiCredential(credentialId: string): boolean {
  return credentialId in CREDENTIAL_FORMAT_MAP;
}

// Get Value
export function mapFormat(format: string): string {
  switch (format) {
    case 'JWT + W3C VC':
    case 'SD-JWT + W3C VC':
      return 'jwt_vc_json';
    case 'SD-JWT + IETF SD-JWT VC':
      return 'vc+sd-jwt';
    case 'DC+SD-JWT (EUDI)':
      return 'dc+sd-jwt';
    case 'mDoc (ISO 18013-5)':
      return 'mso_mdoc';
    default:
      throw new Error(`Unsupported format: ${format}`);
  }
}

// Check if format requires Verifier API2 (EUDI formats)
export function isEudiFormat(format: string): boolean {
  return format === 'dc+sd-jwt' || format === 'mso_mdoc';
}

export interface DcqlCredential {
  id: string;
  format: string;
  meta: {
    doctype_value?: string;
    vct_values?: string[];
  };
  claims?: { path: string[] }[];
}

export interface DcqlQuery {
  credentials: DcqlCredential[];
}

export function buildDcqlQuery(credentials: AvailableCredential[], format: string): DcqlQuery {
  return {
    credentials: credentials.map((credential) => {
      // Prefer user-edited claims, then default claims, then fallback
      const claims = credential.editedClaims?.map(c => ({ path: c.path })) ||
        credential.defaultClaims?.map(c => ({ path: c.path })) ||
        getDefaultClaimsForCredential(credential.id, format);

      // DCQL credential id must be alphanumeric with underscores/hyphens only
      // The credential.id may contain dots (e.g., "eu.europa.ec.eudi.pid.1") or
      // colons (e.g., "urn:eudi:pid:1") which are not allowed by EUDI wallets
      const dcqlId = credential.id.replace(/[^a-zA-Z0-9_-]/g, '_');

      if (format === 'mso_mdoc') {
        return {
          id: dcqlId,
          format: 'mso_mdoc',
          meta: {
            doctype_value: credential.offer.doctype || credential.id,
          },
          claims,
        };
      } else {
        // dc+sd-jwt
        return {
          id: dcqlId,
          format: 'dc+sd-jwt',
          meta: {
            vct_values: [credential.offer.vct || credential.id],
          },
          claims,
        };
      }
    }),
  };
}

// Fallback default claims for known EUDI credential types
function getDefaultClaimsForCredential(credentialId: string, format: string): { path: string[] }[] {
  const defaultClaimsMap: Record<string, { path: string[] }[]> = {
    'eu.europa.ec.eudi.pid.1': [
      { path: ['eu.europa.ec.eudi.pid.1', 'family_name'] },
      { path: ['eu.europa.ec.eudi.pid.1', 'given_name'] },
      { path: ['eu.europa.ec.eudi.pid.1', 'birth_date'] },
    ],
    'org.iso.18013.5.1.mDL': [
      { path: ['org.iso.18013.5.1', 'family_name'] },
      { path: ['org.iso.18013.5.1', 'given_name'] },
      { path: ['org.iso.18013.5.1', 'birth_date'] },
    ],
    'urn:eudi:pid:1': [
      { path: ['family_name'] },
      { path: ['given_name'] },
      { path: ['birth_date'] },
    ],
    'PaymentWalletAttestation': [
      { path: ['fundingSource'] },
      { path: ['fundingSource', 'type'] },
      { path: ['fundingSource', 'panLastFour'] },
      { path: ['fundingSource', 'scheme'] },
    ],
  };

  return defaultClaimsMap[credentialId] || [];
}

export interface VerificationSigningConfig {
  clientId: string;
  key: {
    type: string;
    jwk: {
      kty: string;
      crv: string;
      x: string;
      y: string;
      d: string;
    };
  };
  x5c: string[];
}

export interface VerificationSessionRequest {
  flow_type: string;
  core_flow: {
    signed_request: boolean;
    sessionId?: string;
    clientId?: string;
    key?: VerificationSigningConfig['key'];
    x5c?: string[];
    dcql_query: DcqlQuery;
    policies?: { vc_policies: Array<string | Record<string, any>> };
  };
  redirects?: {
    success_redirect_uri?: string;
    error_redirect_uri?: string;
  };
}

export function buildVerificationSessionRequest(
  dcqlQuery: DcqlQuery,
  signingConfig?: VerificationSigningConfig,
  policies: string[] = [],
  redirects?: { success_redirect_uri?: string; error_redirect_uri?: string },
  sessionId?: string
): VerificationSessionRequest {
  const coreFlow: VerificationSessionRequest['core_flow'] = {
    signed_request: true,
    dcql_query: dcqlQuery,
  };

  if (sessionId) {
    coreFlow.sessionId = sessionId;
  }

  // Add signing parameters if provided
  if (signingConfig) {
    coreFlow.clientId = signingConfig.clientId;
    coreFlow.key = signingConfig.key;
    coreFlow.x5c = signingConfig.x5c;
  }

  // Add verification policies if provided
  if (policies.length > 0) {
    // Map portal policy names to verifier-api2 names
    const policyNameMap: Record<string, string> = {
      'expired': 'expiration',
    };

    // Complex policies that require object format (not in VerificationPolicyManager.simpleVerificationPolicies)
    const complexPolicies = new Set(['etsi-trusted-issuer', 'allowed-issuer', 'schema', 'regex']);

    const vcPolicies: Array<string | Record<string, any>> = [];
    for (const p of policies) {
      if (p.includes('=')) {
        // Parameterized policy (e.g., "webhook=https://example.com")
        const [name, ...rest] = p.split('=');
        vcPolicies.push({ policy: name, url: rest.join('=') });
      } else if (complexPolicies.has(p)) {
        // Must be object format with "policy" discriminator
        vcPolicies.push({ policy: p });
      } else {
        // Simple string policy — apply name mapping
        vcPolicies.push(policyNameMap[p] || p);
      }
    }

    if (vcPolicies.length > 0) {
      coreFlow.policies = { vc_policies: vcPolicies };
    }
  }

  const request: VerificationSessionRequest = {
    flow_type: 'cross_device',
    core_flow: coreFlow,
  };

  if (redirects) {
    request.redirects = redirects;
  }

  return request;
}

export const DIDMethods = [
  'did:jwk',
  'did:key',
  'did:ebsi',
  'did:web',
  'did:cheqd',
]

export const DIDMethodsConfig = {
  'did:key': {
    'issuerDid': 'did:key:z6MkmANLkdcnbriWeVaqdfrA3MmtXoVPNu98tww6xDeyVnyF',
    'issuerKey': {
      "type": "jwk",
      "jwk": {
        "kty": "OKP",
        "d": "fbpXmCh4KkcVIGOnkcjHvWAcaUPvvkBvgMFPE4nAgvA",
        "crv": "Ed25519",
        "kid": "DJ3X4BZqk4GJsMGZL44hEZrlEy9scbMcSA_QuUi3tGs",
        "x": "Y64Ns3aRo6KQgJTtCZKFA78uYvslBcIrOk7xaS1PIZI"
      }
    }
  },
  'did:ebsi': {
    'issuerDid': 'did:ebsi:zf39qHTXaLrr6iy3tQhT3UZ',
    'issuerKey': {
      "type": "jwk",
      "jwk": {
        "kty": "EC",
        "x": "SgfOvOk1TL5yiXhK5Nq7OwKfn_RUkDizlIhAf8qd2wE",
        "y": "u_y5JZOsw3SrnNPydzJkoaiqb8raSdCNE_nPovt1fNI",
        "crv": "P-256",
        "d": "UqSi2MbJmPczfRmwRDeOJrdivoEy-qk4OEDjFwJYlUI"
      }
    }
  },
  'did:jwk': {
    'issuerDid': 'did:jwk:eyJrdHkiOiJFQyIsImNydiI6IlAtMjU2Iiwia2lkIjoiM1lOZDlGbng5Smx5UFZZd2dXRkUzN0UzR3dJMGVHbENLOHdGbFd4R2ZwTSIsIngiOiJGb3ZZMjFMQUFPVGxnLW0tTmVLV2haRUw1YUZyblIwdWNKakQ1VEtwR3VnIiwieSI6IkNyRkpmR1RkUDI5SkpjY3BRWHV5TU8zb2h0enJUcVB6QlBCSVRZajBvZ0EifQ',
    'issuerKey': {
      "type": "jwk",
      "jwk": {
        "kty": "EC",
        "d": "8jH4vwtvCw6tcBzdxQ6V7FY2L215lBGm-x3flgENx4Y",
        "crv": "P-256",
        "kid": "3YNd9Fnx9JlyPVYwgWFE37E3GwI0eGlCK8wFlWxGfpM",
        "x": "FovY21LAAOTlg-m-NeKWhZEL5aFrnR0ucJjD5TKpGug",
        "y": "CrFJfGTdP29JJccpQXuyMO3ohtzrTqPzBPBITYj0ogA"
      }
    }
  },
  'did:web': {
    'issuerDid': 'did:web:wallet.demo.walt.id:wallet-api:registry:portal',
    'issuerKey': {
      "type": "jwk",
      "jwk": {
        "kty": "EC",
        "d": "6rVNEWMQzVdPgin7ER_ptWlSnkozGwOWYlSDcQHMRZw",
        "crv": "secp256k1",
        "kid": "hxKurYDplZbY3PgDdXNtz1CwaG6CJ9dyslsyJY11rQs",
        "x": "fTlAxVt3AHGX4LfqStS8MRIWjBrNYbcdHwW95FKZTiU",
        "y": "SqeitQcdT7lZg4z2JgCCD8JabsZvE_6W8dbMlVNxXeo"
      }
    }
  },
  'did:cheqd': {
    'issuerDid': 'did:cheqd:testnet:16047c9a-8f6f-4258-b35e-73098c6981e0',
    'issuerKey': {
      "type": "jwk",
      "jwk": {
        "kty": "OKP",
        "d": "YqOrL8iTCxeoVAFAxXC-CVxX7-RfOtVggl55wwP3wg0",
        "crv": "Ed25519",
        "kid": "AMSWqtZTHp2PosnSCeFJ10rES2Vd6IzNx8UV3oZuKGw",
        "x": "3oKRKU2W66W8DycLCQ26WCv8scVBGI-H3PvTIvZ0Fjw"
      }
    }
  }
}

export const AuthenticationMethods = [
  'PRE_AUTHORIZED',
  'PWD',
  'ID_TOKEN',
  'VP_TOKEN',
  'NONE',
]

export const VpProfiles = [
  'EBSIV3',
  'DEFAULT',
  'ISO_18013_7_MDOC',
]
