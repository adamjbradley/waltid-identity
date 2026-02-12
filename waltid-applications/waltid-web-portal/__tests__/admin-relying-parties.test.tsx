/**
 * @jest-environment jsdom
 */
import React from 'react';
import { render, screen, fireEvent, waitFor, act } from '@testing-library/react';
import '@testing-library/jest-dom';

// ---- Mocks (must be before component imports) ----

// Mock next/router
const mockPush = jest.fn();
jest.mock('next/router', () => ({
  useRouter: () => ({
    pathname: '/admin/relying-parties',
    push: mockPush,
    query: {},
  }),
}));

// Mock axios
const mockAxiosGet = jest.fn();
const mockAxiosPost = jest.fn();
const mockAxiosPut = jest.fn();
const mockAxiosDelete = jest.fn();
jest.mock('axios', () => ({
  __esModule: true,
  default: {
    get: (...args: any[]) => mockAxiosGet(...args),
    post: (...args: any[]) => mockAxiosPost(...args),
    put: (...args: any[]) => mockAxiosPut(...args),
    delete: (...args: any[]) => mockAxiosDelete(...args),
  },
}));

// Mock heroicons (return simple SVG placeholders)
jest.mock('@heroicons/react/24/outline', () => ({
  ArrowPathIcon: (props: any) => <svg data-testid="arrow-path-icon" {...props} />,
  ChevronDownIcon: (props: any) => <svg data-testid="chevron-down-icon" {...props} />,
  ChevronRightIcon: (props: any) => <svg data-testid="chevron-right-icon" {...props} />,
  ShieldCheckIcon: (props: any) => <svg data-testid="shield-check-icon" {...props} />,
  TrashIcon: (props: any) => <svg data-testid="trash-icon" {...props} />,
  KeyIcon: (props: any) => <svg data-testid="key-icon" {...props} />,
  DocumentArrowDownIcon: (props: any) => <svg data-testid="document-arrow-down-icon" {...props} />,
  ClipboardDocumentIcon: (props: any) => <svg data-testid="clipboard-icon" {...props} />,
  ArrowTopRightOnSquareIcon: (props: any) => <svg data-testid="external-link-icon" {...props} />,
  HomeIcon: (props: any) => <svg data-testid="home-icon" {...props} />,
  BuildingLibraryIcon: (props: any) => <svg data-testid="building-library-icon" {...props} />,
  BuildingOffice2Icon: (props: any) => <svg data-testid="building-office-icon" {...props} />,
  Cog6ToothIcon: (props: any) => <svg data-testid="cog-icon" {...props} />,
}));

// Mock react-icons (used by Button component)
jest.mock('react-icons/ai', () => ({
  AiOutlineLoading3Quarters: (props: any) => <svg data-testid="loading-icon" {...props} />,
}));

// Mock WaltIcon
jest.mock('@/components/walt/logo/WaltIcon', () => ({
  __esModule: true,
  default: (props: any) => <svg data-testid="walt-icon" {...props} />,
}));

// ---- Import components after mocks ----

import { EnvContext } from '@/pages/_app';
import RelyingParties from '@/pages/admin/relying-parties';
import AdminNav from '@/components/walt/nav/AdminNav';

// ---- Test Fixtures ----

const mockEnv = {
  NEXT_PUBLIC_VERIFIER2: 'http://localhost:7004',
  NEXT_PUBLIC_RP_REGISTRAR_ENABLED: 'true',
};

const mockRpList = [
  {
    id: 'rp-1',
    legalName: 'Acme Verifier',
    domain: 'verify.acme.com',
    country: 'AU',
    status: 'ACTIVE',
    hasCertificate: true,
    certificateExpiry: '2027-06-01T00:00:00Z',
    createdAt: '2026-01-15T00:00:00Z',
  },
  {
    id: 'rp-2',
    legalName: 'Beta Services',
    domain: 'beta.example.com',
    country: 'DE',
    status: 'SUSPENDED',
    hasCertificate: false,
    createdAt: '2026-02-01T00:00:00Z',
  },
];

const mockRpDetail = {
  id: 'rp-1',
  legalName: 'Acme Verifier',
  tradeName: 'Acme',
  registrationNumber: 'ACN 123 456 789',
  country: 'AU',
  contactEmail: 'admin@acme.com',
  contactPhone: '+61 2 1234 5678',
  contactAddress: '123 George St, Sydney',
  intendedUse: 'Age verification for online purchases',
  privacyPolicyUrl: 'https://acme.com/privacy',
  dataRetentionPeriod: '90_DAYS',
  lawfulBasis: 'CONSENT',
  dpaAcknowledged: true,
  clientId: 'x509_san_dns:verify.acme.com',
  domain: 'verify.acme.com',
  certificate: {
    subject: 'CN=verify.acme.com',
    issuer: 'CN=RP CA',
    notBefore: '2026-01-15T00:00:00Z',
    notAfter: '2027-06-01T00:00:00Z',
    serialNumber: '54321',
    fingerprint: '12:34:56',
  },
  x5c: ['base64cert1'],
  status: 'ACTIVE' as const,
  createdAt: '2026-01-15T00:00:00Z',
  updatedAt: '2026-02-01T00:00:00Z',
};

// ---- Helper ----

function renderWithEnv(ui: React.ReactElement, envOverrides = {}) {
  return render(
    <EnvContext.Provider value={{ ...mockEnv, ...envOverrides }}>
      {ui}
    </EnvContext.Provider>
  );
}

// ====================================================================
// 1. AdminNav Component Tests (2 tests)
// ====================================================================

describe('AdminNav - Navigation', () => {
  beforeEach(() => {
    mockPush.mockClear();
  });

  it('renders all navigation items (Portal, Trust Lists, Issuers, Relying Parties)', () => {
    render(<AdminNav />);
    expect(screen.getByText('Portal')).toBeInTheDocument();
    expect(screen.getByText('Trust Lists')).toBeInTheDocument();
    expect(screen.getByText('Issuers')).toBeInTheDocument();
    expect(screen.getByText('Relying Parties')).toBeInTheDocument();
  });

  it('highlights Relying Parties as active (blue class)', () => {
    render(<AdminNav />);
    const rpButton = screen.getByText('Relying Parties').closest('button');
    expect(rpButton?.className).toContain('blue');
  });
});

// ====================================================================
// 2. Loading & Error States (3 tests)
// ====================================================================

describe('Relying Parties Page - Loading & Error States', () => {
  beforeEach(() => {
    jest.clearAllMocks();
  });

  it('shows loading spinner while fetching', async () => {
    // Never resolve the API call - keep loading
    mockAxiosGet.mockReturnValue(new Promise(() => {}));

    renderWithEnv(<RelyingParties />);
    const spinner = document.querySelector('.animate-spin');
    expect(spinner).toBeInTheDocument();
  });

  it('renders page header "Relying Party Registrar"', async () => {
    mockAxiosGet.mockResolvedValueOnce({ data: [] });

    await act(async () => {
      renderWithEnv(<RelyingParties />);
    });

    expect(screen.getByText('Relying Party Registrar')).toBeInTheDocument();
  });

  it('shows error when API returns 503', async () => {
    mockAxiosGet.mockRejectedValueOnce({
      response: { status: 503, data: { error: 'not enabled' } },
    });

    await act(async () => {
      renderWithEnv(<RelyingParties />);
    });

    expect(screen.getByText(/not enabled/i)).toBeInTheDocument();
  });
});

// ====================================================================
// 3. Error - not configured (1 test)
// ====================================================================

describe('Relying Parties Page - Not Configured', () => {
  beforeEach(() => {
    jest.clearAllMocks();
  });

  it('shows error when NEXT_PUBLIC_VERIFIER2 is empty', async () => {
    await act(async () => {
      renderWithEnv(<RelyingParties />, { NEXT_PUBLIC_VERIFIER2: '' });
    });

    expect(screen.getByText(/not configured/i)).toBeInTheDocument();
  });
});

// ====================================================================
// 4. RP List Tab (5 tests)
// ====================================================================

describe('Relying Parties Page - List Tab', () => {
  beforeEach(() => {
    jest.clearAllMocks();
    mockAxiosGet.mockResolvedValueOnce({ data: mockRpList });
  });

  it('renders RP list with names and domains', async () => {
    await act(async () => {
      renderWithEnv(<RelyingParties />);
    });

    expect(screen.getByText('Acme Verifier')).toBeInTheDocument();
    expect(screen.getByText('verify.acme.com')).toBeInTheDocument();
    expect(screen.getByText('Beta Services')).toBeInTheDocument();
    expect(screen.getByText('beta.example.com')).toBeInTheDocument();
  });

  it('displays ACTIVE and SUSPENDED status badges', async () => {
    await act(async () => {
      renderWithEnv(<RelyingParties />);
    });

    expect(screen.getByText('ACTIVE')).toBeInTheDocument();
    expect(screen.getByText('SUSPENDED')).toBeInTheDocument();
  });

  it('shows certificate status (Cert expires / No certificate)', async () => {
    await act(async () => {
      renderWithEnv(<RelyingParties />);
    });

    expect(screen.getByText(/Cert expires/)).toBeInTheDocument();
    expect(screen.getByText('No certificate')).toBeInTheDocument();
  });

  it('shows country for each RP', async () => {
    await act(async () => {
      renderWithEnv(<RelyingParties />);
    });

    expect(screen.getByText('AU')).toBeInTheDocument();
    expect(screen.getByText('DE')).toBeInTheDocument();
  });

  it('shows tab count matching list length "Relying Parties (2)"', async () => {
    await act(async () => {
      renderWithEnv(<RelyingParties />);
    });

    expect(screen.getByText(`Relying Parties (${mockRpList.length})`)).toBeInTheDocument();
  });
});

// ====================================================================
// 5. Empty State (1 test)
// ====================================================================

describe('Relying Parties Page - Empty State', () => {
  beforeEach(() => {
    jest.clearAllMocks();
  });

  it('shows "No relying parties registered yet." when list is empty', async () => {
    mockAxiosGet.mockResolvedValueOnce({ data: [] });

    await act(async () => {
      renderWithEnv(<RelyingParties />);
    });

    expect(screen.getByText('No relying parties registered yet.')).toBeInTheDocument();
  });
});

// ====================================================================
// 6. Tab Switching (2 tests)
// ====================================================================

describe('Relying Parties Page - Tab Switching', () => {
  beforeEach(() => {
    jest.clearAllMocks();
    mockAxiosGet.mockResolvedValueOnce({ data: mockRpList });
  });

  it('defaults to list tab showing RPs', async () => {
    await act(async () => {
      renderWithEnv(<RelyingParties />);
    });

    expect(screen.getByText('Acme Verifier')).toBeInTheDocument();
  });

  it('switches to register tab showing form with "Acme Corp" placeholder', async () => {
    await act(async () => {
      renderWithEnv(<RelyingParties />);
    });

    // Click "Register New RP" tab
    const registerTab = screen.getByText('Register New RP');
    fireEvent.click(registerTab);

    await waitFor(() => {
      expect(screen.getByPlaceholderText('Acme Corp')).toBeInTheDocument();
    });
  });
});

// ====================================================================
// 7. Detail Panel (6 tests)
// ====================================================================

describe('Relying Parties Page - Detail Panel', () => {
  beforeEach(() => {
    jest.clearAllMocks();
    // First call: list endpoint
    mockAxiosGet.mockResolvedValueOnce({ data: mockRpList });
  });

  it('expands RP detail on click showing contactEmail and address', async () => {
    mockAxiosGet.mockResolvedValueOnce({ data: mockRpDetail });

    await act(async () => {
      renderWithEnv(<RelyingParties />);
    });

    await act(async () => {
      fireEvent.click(screen.getByText('Acme Verifier'));
    });

    await waitFor(() => {
      expect(screen.getByText('admin@acme.com')).toBeInTheDocument();
      expect(screen.getByText('123 George St, Sydney')).toBeInTheDocument();
    });
  });

  it('shows clientId in detail', async () => {
    mockAxiosGet.mockResolvedValueOnce({ data: mockRpDetail });

    await act(async () => {
      renderWithEnv(<RelyingParties />);
    });

    await act(async () => {
      fireEvent.click(screen.getByText('Acme Verifier'));
    });

    await waitFor(() => {
      expect(screen.getByText('x509_san_dns:verify.acme.com')).toBeInTheDocument();
    });
  });

  it('shows certificate info (CN=verify.acme.com, 12:34:56 fingerprint)', async () => {
    mockAxiosGet.mockResolvedValueOnce({ data: mockRpDetail });

    await act(async () => {
      renderWithEnv(<RelyingParties />);
    });

    await act(async () => {
      fireEvent.click(screen.getByText('Acme Verifier'));
    });

    await waitFor(() => {
      expect(screen.getByText('CN=verify.acme.com')).toBeInTheDocument();
      expect(screen.getByText('12:34:56')).toBeInTheDocument();
    });
  });

  it('shows data protection section (privacy policy, data retention, lawful basis, DPA acknowledged)', async () => {
    mockAxiosGet.mockResolvedValueOnce({ data: mockRpDetail });

    await act(async () => {
      renderWithEnv(<RelyingParties />);
    });

    await act(async () => {
      fireEvent.click(screen.getByText('Acme Verifier'));
    });

    await waitFor(() => {
      expect(screen.getByText('https://acme.com/privacy')).toBeInTheDocument();
      expect(screen.getByText('90 DAYS')).toBeInTheDocument();
      expect(screen.getByText('CONSENT')).toBeInTheDocument();
      expect(screen.getByText('Yes')).toBeInTheDocument();
    });
  });

  it('collapses detail on second click', async () => {
    mockAxiosGet.mockResolvedValueOnce({ data: mockRpDetail });

    await act(async () => {
      renderWithEnv(<RelyingParties />);
    });

    // Expand
    await act(async () => {
      fireEvent.click(screen.getByText('Acme Verifier'));
    });

    await waitFor(() => {
      expect(screen.getByText('admin@acme.com')).toBeInTheDocument();
    });

    // Collapse
    await act(async () => {
      fireEvent.click(screen.getByText('Acme Verifier'));
    });

    await waitFor(() => {
      expect(screen.queryByText('admin@acme.com')).not.toBeInTheDocument();
    });
  });

  it('shows "Regenerate Certificate" link when cert exists', async () => {
    mockAxiosGet.mockResolvedValueOnce({ data: mockRpDetail });

    await act(async () => {
      renderWithEnv(<RelyingParties />);
    });

    await act(async () => {
      fireEvent.click(screen.getByText('Acme Verifier'));
    });

    await waitFor(() => {
      expect(screen.getByText('Regenerate Certificate')).toBeInTheDocument();
    });
  });
});

// ====================================================================
// 8. Status Management (3 tests)
// ====================================================================

describe('Relying Parties Page - Status Management', () => {
  beforeEach(() => {
    jest.clearAllMocks();
    mockAxiosGet.mockResolvedValueOnce({ data: mockRpList });
  });

  it('shows "Suspend" button for ACTIVE RP', async () => {
    mockAxiosGet.mockResolvedValueOnce({ data: mockRpDetail });

    await act(async () => {
      renderWithEnv(<RelyingParties />);
    });

    await act(async () => {
      fireEvent.click(screen.getByText('Acme Verifier'));
    });

    await waitFor(() => {
      expect(screen.getByText('Suspend')).toBeInTheDocument();
    });
  });

  it('shows "Activate" button for SUSPENDED RP', async () => {
    const suspendedDetail = {
      ...mockRpDetail,
      id: 'rp-2',
      legalName: 'Beta Services',
      status: 'SUSPENDED' as const,
    };
    mockAxiosGet.mockResolvedValueOnce({ data: suspendedDetail });

    await act(async () => {
      renderWithEnv(<RelyingParties />);
    });

    await act(async () => {
      fireEvent.click(screen.getByText('Beta Services'));
    });

    await waitFor(() => {
      expect(screen.getByText('Activate')).toBeInTheDocument();
    });
  });

  it('toggle calls PUT API with new status', async () => {
    mockAxiosGet.mockResolvedValueOnce({ data: mockRpDetail });
    // PUT toggle status
    mockAxiosPut.mockResolvedValueOnce({ data: {} });
    // Re-fetch detail after toggle
    mockAxiosGet.mockResolvedValueOnce({ data: { ...mockRpDetail, status: 'SUSPENDED' } });
    // Re-fetch list after toggle
    mockAxiosGet.mockResolvedValueOnce({ data: mockRpList });

    await act(async () => {
      renderWithEnv(<RelyingParties />);
    });

    await act(async () => {
      fireEvent.click(screen.getByText('Acme Verifier'));
    });

    await waitFor(() => {
      expect(screen.getByText('Suspend')).toBeInTheDocument();
    });

    await act(async () => {
      fireEvent.click(screen.getByText('Suspend'));
    });

    expect(mockAxiosPut).toHaveBeenCalledWith(
      'http://localhost:7004/admin/rp/rp-1',
      { status: 'SUSPENDED' }
    );
  });
});

// ====================================================================
// 9. Quick Action Buttons (5 tests)
// ====================================================================

describe('Relying Parties Page - Quick Action Buttons', () => {
  beforeEach(() => {
    jest.clearAllMocks();
    mockAxiosGet.mockResolvedValueOnce({ data: mockRpList });
  });

  it('"Verify as this RP" link has correct href with rpId', async () => {
    mockAxiosGet.mockResolvedValueOnce({ data: mockRpDetail });

    await act(async () => {
      renderWithEnv(<RelyingParties />);
    });

    await act(async () => {
      fireEvent.click(screen.getByText('Acme Verifier'));
    });

    await waitFor(() => {
      const verifyLink = screen.getByTestId('verify-as-rp');
      expect(verifyLink).toBeInTheDocument();
      expect(verifyLink).toHaveAttribute('href', '/verify?rpId=rp-1');
    });
  });

  it('"Copy Verify Link" button present with data-testid', async () => {
    mockAxiosGet.mockResolvedValueOnce({ data: mockRpDetail });

    await act(async () => {
      renderWithEnv(<RelyingParties />);
    });

    await act(async () => {
      fireEvent.click(screen.getByText('Acme Verifier'));
    });

    await waitFor(() => {
      const copyButton = screen.getByTestId('copy-verify-link');
      expect(copyButton).toBeInTheDocument();
    });
  });

  it('"Download Certificate" button present when cert exists', async () => {
    mockAxiosGet.mockResolvedValueOnce({ data: mockRpDetail });

    await act(async () => {
      renderWithEnv(<RelyingParties />);
    });

    await act(async () => {
      fireEvent.click(screen.getByText('Acme Verifier'));
    });

    await waitFor(() => {
      const downloadButton = screen.getByTestId('download-cert');
      expect(downloadButton).toBeInTheDocument();
    });
  });

  it('quick actions only shown for ACTIVE status', async () => {
    const suspendedDetail = {
      ...mockRpDetail,
      id: 'rp-2',
      legalName: 'Beta Services',
      status: 'SUSPENDED' as const,
    };
    mockAxiosGet.mockResolvedValueOnce({ data: suspendedDetail });

    await act(async () => {
      renderWithEnv(<RelyingParties />);
    });

    await act(async () => {
      fireEvent.click(screen.getByText('Beta Services'));
    });

    await waitFor(() => {
      // Detail should be loaded but quick actions should not be present
      expect(screen.queryByTestId('verify-as-rp')).not.toBeInTheDocument();
      expect(screen.queryByTestId('copy-verify-link')).not.toBeInTheDocument();
    });
  });

  it('no download button when no certificate', async () => {
    const detailNoCert = {
      ...mockRpDetail,
      certificate: undefined,
      x5c: undefined,
    };
    mockAxiosGet.mockResolvedValueOnce({ data: detailNoCert });

    await act(async () => {
      renderWithEnv(<RelyingParties />);
    });

    await act(async () => {
      fireEvent.click(screen.getByText('Acme Verifier'));
    });

    await waitFor(() => {
      expect(screen.getByTestId('verify-as-rp')).toBeInTheDocument();
      expect(screen.queryByTestId('download-cert')).not.toBeInTheDocument();
    });
  });
});

// ====================================================================
// 10. Certificate Section (4 tests)
// ====================================================================

describe('Relying Parties Page - Certificate Section', () => {
  beforeEach(() => {
    jest.clearAllMocks();
    mockAxiosGet.mockResolvedValueOnce({ data: mockRpList });
  });

  it('shows "No access certificate generated yet." when none', async () => {
    const detailNoCert = {
      ...mockRpDetail,
      certificate: undefined,
      x5c: undefined,
    };
    mockAxiosGet.mockResolvedValueOnce({ data: detailNoCert });

    await act(async () => {
      renderWithEnv(<RelyingParties />);
    });

    await act(async () => {
      fireEvent.click(screen.getByText('Acme Verifier'));
    });

    await waitFor(() => {
      expect(screen.getByText('No access certificate generated yet.')).toBeInTheDocument();
    });
  });

  it('shows "Generate EC P-256 Certificate" button when no cert', async () => {
    const detailNoCert = {
      ...mockRpDetail,
      certificate: undefined,
      x5c: undefined,
    };
    mockAxiosGet.mockResolvedValueOnce({ data: detailNoCert });

    await act(async () => {
      renderWithEnv(<RelyingParties />);
    });

    await act(async () => {
      fireEvent.click(screen.getByText('Acme Verifier'));
    });

    await waitFor(() => {
      expect(screen.getByText('Generate EC P-256 Certificate')).toBeInTheDocument();
    });
  });

  it('generate cert calls POST API', async () => {
    const detailNoCert = {
      ...mockRpDetail,
      certificate: undefined,
      x5c: undefined,
    };
    mockAxiosGet.mockResolvedValueOnce({ data: detailNoCert });
    // Generate cert POST
    mockAxiosPost.mockResolvedValueOnce({ data: {} });
    // Re-fetch detail after cert gen
    mockAxiosGet.mockResolvedValueOnce({ data: mockRpDetail });
    // Re-fetch list after cert gen
    mockAxiosGet.mockResolvedValueOnce({ data: mockRpList });

    await act(async () => {
      renderWithEnv(<RelyingParties />);
    });

    await act(async () => {
      fireEvent.click(screen.getByText('Acme Verifier'));
    });

    await waitFor(() => {
      expect(screen.getByText('Generate EC P-256 Certificate')).toBeInTheDocument();
    });

    await act(async () => {
      fireEvent.click(screen.getByText('Generate EC P-256 Certificate'));
    });

    expect(mockAxiosPost).toHaveBeenCalledWith(
      'http://localhost:7004/admin/rp/rp-1/certificate/generate'
    );
  });

  it('shows cert details when present (subject, fingerprint, serial)', async () => {
    mockAxiosGet.mockResolvedValueOnce({ data: mockRpDetail });

    await act(async () => {
      renderWithEnv(<RelyingParties />);
    });

    await act(async () => {
      fireEvent.click(screen.getByText('Acme Verifier'));
    });

    await waitFor(() => {
      expect(screen.getByText('CN=verify.acme.com')).toBeInTheDocument();
      expect(screen.getByText('12:34:56')).toBeInTheDocument();
      expect(screen.getByText('54321')).toBeInTheDocument();
    });
  });
});

// ====================================================================
// 11. Create RP Form (3 tests)
// ====================================================================

describe('Relying Parties Page - Create RP Form', () => {
  beforeEach(() => {
    jest.clearAllMocks();
    mockAxiosGet.mockResolvedValueOnce({ data: mockRpList });
  });

  it('form fields render with correct placeholders', async () => {
    await act(async () => {
      renderWithEnv(<RelyingParties />);
    });

    // Switch to register tab
    fireEvent.click(screen.getByText('Register New RP'));

    await waitFor(() => {
      expect(screen.getByPlaceholderText('Acme Corp')).toBeInTheDocument();
      expect(screen.getByPlaceholderText('Acme')).toBeInTheDocument();
      expect(screen.getByPlaceholderText('verifier.acme.com')).toBeInTheDocument();
      expect(screen.getByPlaceholderText('AU')).toBeInTheDocument();
      expect(screen.getByPlaceholderText('admin@acme.com')).toBeInTheDocument();
      expect(screen.getByPlaceholderText('+61 2 1234 5678')).toBeInTheDocument();
    });
  });

  it('register button disabled when required fields empty', async () => {
    await act(async () => {
      renderWithEnv(<RelyingParties />);
    });

    // Switch to register tab
    fireEvent.click(screen.getByText('Register New RP'));

    await waitFor(() => {
      const registerButton = screen.getByText('Register Relying Party');
      expect(registerButton.closest('button')).toBeDisabled();
    });
  });

  it('successful registration calls POST API and shows success message', async () => {
    const registeredRp = {
      id: 'rp-3',
      legalName: 'Test Corp',
      domain: 'test.example.com',
    };
    mockAxiosPost.mockResolvedValueOnce({ data: registeredRp });
    // Re-fetch list after register
    mockAxiosGet.mockResolvedValueOnce({ data: [...mockRpList, { ...registeredRp, status: 'ACTIVE', hasCertificate: false, country: 'AU', createdAt: '2026-02-12T00:00:00Z' }] });

    await act(async () => {
      renderWithEnv(<RelyingParties />);
    });

    // Switch to register tab
    fireEvent.click(screen.getByText('Register New RP'));

    await waitFor(() => {
      expect(screen.getByPlaceholderText('Acme Corp')).toBeInTheDocument();
    });

    // Fill in required fields
    fireEvent.change(screen.getByPlaceholderText('Acme Corp'), { target: { value: 'Test Corp' } });
    fireEvent.change(screen.getByPlaceholderText('verifier.acme.com'), { target: { value: 'test.example.com' } });
    fireEvent.change(screen.getByPlaceholderText('AU'), { target: { value: 'AU' } });
    fireEvent.change(screen.getByPlaceholderText('admin@acme.com'), { target: { value: 'admin@test.com' } });
    fireEvent.change(screen.getByPlaceholderText('123 George St, Sydney NSW 2000'), { target: { value: '456 Test St' } });
    fireEvent.change(screen.getByPlaceholderText('https://acme.com/privacy'), { target: { value: 'https://test.com/privacy' } });

    // Select data retention period
    const retentionSelect = screen.getByDisplayValue('Select retention period...');
    fireEvent.change(retentionSelect, { target: { value: '90_DAYS' } });

    // Select lawful basis
    const lawfulBasisSelect = screen.getByDisplayValue('Select lawful basis...');
    fireEvent.change(lawfulBasisSelect, { target: { value: 'CONSENT' } });

    // Check DPA acknowledgment
    const dpaCheckbox = screen.getByRole('checkbox');
    fireEvent.click(dpaCheckbox);

    // Click register
    const registerButton = screen.getByText('Register Relying Party');
    expect(registerButton.closest('button')).not.toBeDisabled();

    await act(async () => {
      fireEvent.click(registerButton);
    });

    expect(mockAxiosPost).toHaveBeenCalledWith(
      'http://localhost:7004/admin/rp',
      expect.objectContaining({
        legalName: 'Test Corp',
        domain: 'test.example.com',
        country: 'AU',
        contactEmail: 'admin@test.com',
      })
    );

    await waitFor(() => {
      expect(screen.getByText(/Registered "Test Corp"/)).toBeInTheDocument();
    });
  });
});

// ====================================================================
// 12. Delete RP (2 tests)
// ====================================================================

describe('Relying Parties Page - Delete RP', () => {
  beforeEach(() => {
    jest.clearAllMocks();
    mockAxiosGet.mockResolvedValueOnce({ data: mockRpList });
  });

  it('delete button visible in detail panel', async () => {
    mockAxiosGet.mockResolvedValueOnce({ data: mockRpDetail });

    await act(async () => {
      renderWithEnv(<RelyingParties />);
    });

    await act(async () => {
      fireEvent.click(screen.getByText('Acme Verifier'));
    });

    await waitFor(() => {
      expect(screen.getByText('Delete')).toBeInTheDocument();
    });
  });

  it('delete calls API after confirm', async () => {
    jest.spyOn(window, 'confirm').mockReturnValue(true);

    mockAxiosGet.mockResolvedValueOnce({ data: mockRpDetail });
    // Delete API
    mockAxiosDelete.mockResolvedValueOnce({ data: {} });
    // Re-fetch list after delete
    mockAxiosGet.mockResolvedValueOnce({ data: [mockRpList[1]] });

    await act(async () => {
      renderWithEnv(<RelyingParties />);
    });

    await act(async () => {
      fireEvent.click(screen.getByText('Acme Verifier'));
    });

    await waitFor(() => {
      expect(screen.getByText('Delete')).toBeInTheDocument();
    });

    await act(async () => {
      fireEvent.click(screen.getByText('Delete'));
    });

    expect(mockAxiosDelete).toHaveBeenCalledWith(
      'http://localhost:7004/admin/rp/rp-1'
    );

    (window.confirm as jest.Mock).mockRestore();
  });
});

// ====================================================================
// 13. Status Badge Styling (1 test)
// ====================================================================

describe('Relying Parties Page - Status Badge Styling', () => {
  beforeEach(() => {
    jest.clearAllMocks();
    mockAxiosGet.mockResolvedValueOnce({ data: mockRpList });
  });

  it('ACTIVE has emerald, SUSPENDED has amber', async () => {
    await act(async () => {
      renderWithEnv(<RelyingParties />);
    });

    const activeBadge = screen.getByText('ACTIVE');
    expect(activeBadge.className).toContain('emerald');

    const suspendedBadge = screen.getByText('SUSPENDED');
    expect(suspendedBadge.className).toContain('amber');
  });
});
