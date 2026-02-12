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
    pathname: '/admin/issuers',
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
  BuildingLibraryIcon: (props: any) => <svg data-testid="building-library-icon" {...props} />,
  BuildingOffice2Icon: (props: any) => <svg data-testid="building-office-icon" {...props} />,
  TrashIcon: (props: any) => <svg data-testid="trash-icon" {...props} />,
  KeyIcon: (props: any) => <svg data-testid="key-icon" {...props} />,
  DocumentTextIcon: (props: any) => <svg data-testid="document-text-icon" {...props} />,
  ShieldCheckIcon: (props: any) => <svg data-testid="shield-check-icon" {...props} />,
  HomeIcon: (props: any) => <svg data-testid="home-icon" {...props} />,
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
import Issuers from '@/pages/admin/issuers';
import AdminNav from '@/components/walt/nav/AdminNav';

// ---- Test Fixtures ----

const mockEnv = {
  NEXT_PUBLIC_ISSUER: 'http://localhost:7002',
  NEXT_PUBLIC_ISSUER_REGISTRAR_ENABLED: 'true',
};

const mockIssuerList = [
  {
    id: 'tenant-1',
    legalName: 'Alpha Bank',
    domain: 'alpha.example.com',
    country: 'AU',
    status: 'ACTIVE',
    hasCertificate: true,
    certificateExpiry: '2027-01-01T00:00:00Z',
    credentialCount: 2,
    createdAt: '2026-01-01T00:00:00Z',
  },
  {
    id: 'tenant-2',
    legalName: 'Beta Corp',
    domain: 'beta.example.com',
    country: 'US',
    status: 'SUSPENDED',
    hasCertificate: false,
    credentialCount: 0,
    createdAt: '2026-02-01T00:00:00Z',
  },
];

const mockIssuerDetail = {
  id: 'tenant-1',
  legalName: 'Alpha Bank',
  country: 'AU',
  domain: 'alpha.example.com',
  contactEmail: 'admin@alpha.example.com',
  contactAddress: '123 Main St',
  signerCertificate: {
    subject: 'CN=Alpha Bank',
    issuer: 'CN=IACA Alpha Bank',
    notBefore: '2026-01-01T00:00:00Z',
    notAfter: '2027-01-01T00:00:00Z',
    serialNumber: '12345',
    fingerprint: 'AB:CD:EF',
  },
  iacaCertificate: {
    subject: 'CN=IACA Alpha Bank',
    issuer: 'CN=IACA Alpha Bank',
    notBefore: '2026-01-01T00:00:00Z',
    notAfter: '2027-01-01T00:00:00Z',
    serialNumber: '99999',
    fingerprint: 'FF:EE:DD',
  },
  x5Chain: ['base64cert1', 'base64cert2'],
  credentialConfigurations: { BankId: ['VerifiableCredential', 'BankId'] },
  status: 'ACTIVE',
  createdAt: '2026-01-01T00:00:00Z',
  updatedAt: '2026-01-15T00:00:00Z',
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
// AdminNav Component Tests
// ====================================================================

describe('AdminNav - Navigation', () => {
  beforeEach(() => {
    mockPush.mockClear();
  });

  it('renders all navigation items', () => {
    render(<AdminNav />);
    expect(screen.getByText('Portal')).toBeInTheDocument();
    expect(screen.getByText('Trust Lists')).toBeInTheDocument();
    expect(screen.getByText('Issuers')).toBeInTheDocument();
    expect(screen.getByText('Relying Parties')).toBeInTheDocument();
  });

  it('highlights the active nav item (Issuers)', () => {
    render(<AdminNav />);
    const issuersButton = screen.getByText('Issuers').closest('button');
    // Active item should have blue styling
    expect(issuersButton?.className).toContain('blue');
  });

  it('navigates to Portal on Portal button click', () => {
    render(<AdminNav />);
    fireEvent.click(screen.getByText('Portal'));
    expect(mockPush).toHaveBeenCalledWith('/');
  });

  it('navigates to trust-config on Trust Lists click', () => {
    render(<AdminNav />);
    fireEvent.click(screen.getByText('Trust Lists'));
    expect(mockPush).toHaveBeenCalledWith('/admin/trust-config');
  });

  it('navigates to relying-parties on Relying Parties click', () => {
    render(<AdminNav />);
    fireEvent.click(screen.getByText('Relying Parties'));
    expect(mockPush).toHaveBeenCalledWith('/admin/relying-parties');
  });
});

// ====================================================================
// Issuers Page - Loading & Error States
// ====================================================================

describe('Issuers Page - Loading & Error States', () => {
  beforeEach(() => {
    jest.clearAllMocks();
  });

  it('shows loading spinner while fetching issuers', async () => {
    // Never resolve the API call — keep loading
    mockAxiosGet.mockReturnValue(new Promise(() => {}));

    renderWithEnv(<Issuers />);
    // Loading spinner should be visible (animate-spin class)
    const spinner = document.querySelector('.animate-spin');
    expect(spinner).toBeInTheDocument();
  });

  it('renders page header and title', async () => {
    mockAxiosGet.mockResolvedValueOnce({ data: [] });

    await act(async () => {
      renderWithEnv(<Issuers />);
    });

    expect(screen.getByText('Issuer Registrar')).toBeInTheDocument();
    expect(screen.getByText(/Manage multi-tenant/)).toBeInTheDocument();
  });

  it('shows error when API returns 503 (feature disabled)', async () => {
    mockAxiosGet.mockRejectedValueOnce({
      response: { status: 503, data: { error: 'not enabled' } },
    });

    await act(async () => {
      renderWithEnv(<Issuers />);
    });

    expect(screen.getByText(/not enabled/i)).toBeInTheDocument();
  });

  it('shows error when NEXT_PUBLIC_ISSUER is not configured', async () => {
    await act(async () => {
      renderWithEnv(<Issuers />, { NEXT_PUBLIC_ISSUER: '' });
    });

    expect(screen.getByText(/not configured/i)).toBeInTheDocument();
  });
});

// ====================================================================
// Issuers Page - List Tab
// ====================================================================

describe('Issuers Page - List Tab', () => {
  beforeEach(() => {
    jest.clearAllMocks();
    mockAxiosGet.mockResolvedValueOnce({ data: mockIssuerList });
  });

  it('renders issuer list with names and domains', async () => {
    await act(async () => {
      renderWithEnv(<Issuers />);
    });

    expect(screen.getByText('Alpha Bank')).toBeInTheDocument();
    expect(screen.getByText('alpha.example.com')).toBeInTheDocument();
    expect(screen.getByText('Beta Corp')).toBeInTheDocument();
    expect(screen.getByText('beta.example.com')).toBeInTheDocument();
  });

  it('displays status badges for each issuer', async () => {
    await act(async () => {
      renderWithEnv(<Issuers />);
    });

    expect(screen.getByText('ACTIVE')).toBeInTheDocument();
    expect(screen.getByText('SUSPENDED')).toBeInTheDocument();
  });

  it('shows credential count for each issuer', async () => {
    await act(async () => {
      renderWithEnv(<Issuers />);
    });

    expect(screen.getByText('2 credential(s)')).toBeInTheDocument();
    expect(screen.getByText('0 credential(s)')).toBeInTheDocument();
  });

  it('shows certificate status for each issuer', async () => {
    await act(async () => {
      renderWithEnv(<Issuers />);
    });

    expect(screen.getByText(/Cert expires/)).toBeInTheDocument();
    expect(screen.getByText('No certificate')).toBeInTheDocument();
  });

  it('shows tab count matching issuer list length', async () => {
    await act(async () => {
      renderWithEnv(<Issuers />);
    });

    expect(screen.getByText(`Issuers (${mockIssuerList.length})`)).toBeInTheDocument();
  });
});

// ====================================================================
// Issuers Page - Empty State
// ====================================================================

describe('Issuers Page - Empty State', () => {
  beforeEach(() => {
    jest.clearAllMocks();
  });

  it('shows empty state when no issuers registered', async () => {
    mockAxiosGet.mockResolvedValueOnce({ data: [] });

    await act(async () => {
      renderWithEnv(<Issuers />);
    });

    expect(screen.getByText('No issuers registered yet.')).toBeInTheDocument();
    // The "Register New Issuer" text appears both in the tab and the empty state hint
    expect(screen.getAllByText(/Register New Issuer/).length).toBeGreaterThanOrEqual(1);
  });
});

// ====================================================================
// Issuers Page - Tab Switching
// ====================================================================

describe('Issuers Page - Tab Switching', () => {
  beforeEach(() => {
    jest.clearAllMocks();
    mockAxiosGet.mockResolvedValueOnce({ data: mockIssuerList });
  });

  it('defaults to list tab', async () => {
    await act(async () => {
      renderWithEnv(<Issuers />);
    });

    // List tab should show issuers
    expect(screen.getByText('Alpha Bank')).toBeInTheDocument();
  });

  it('switches to register tab and shows form', async () => {
    await act(async () => {
      renderWithEnv(<Issuers />);
    });

    // Click "Register New Issuer" tab
    const registerTab = screen.getAllByText(/Register New Issuer/).find(
      (el) => el.tagName === 'BUTTON' && el.closest('nav')
    );
    if (registerTab) {
      fireEvent.click(registerTab);
    } else {
      // Tab button in the nav area
      const tabs = screen.getAllByText(/Register New Issuer/);
      fireEvent.click(tabs[0]);
    }

    // Form fields should be visible
    await waitFor(() => {
      expect(screen.getByPlaceholderText('Example Bank Ltd')).toBeInTheDocument();
      expect(screen.getByPlaceholderText('AU')).toBeInTheDocument();
      expect(screen.getByPlaceholderText('issuer.example.com')).toBeInTheDocument();
      expect(screen.getByPlaceholderText('admin@example.com')).toBeInTheDocument();
    });
  });

  it('register button is disabled when required fields are empty', async () => {
    await act(async () => {
      renderWithEnv(<Issuers />);
    });

    // Switch to register tab
    const tabs = screen.getAllByText(/Register New Issuer/);
    fireEvent.click(tabs[0]);

    await waitFor(() => {
      const registerButton = screen.getByText('Register Issuer');
      expect(registerButton.closest('button')).toBeDisabled();
    });
  });
});

// ====================================================================
// Issuers Page - Detail Expand
// ====================================================================

describe('Issuers Page - Detail Panel', () => {
  beforeEach(() => {
    jest.clearAllMocks();
    // First call: list endpoint
    mockAxiosGet.mockResolvedValueOnce({ data: mockIssuerList });
  });

  it('expands issuer detail on click and shows info', async () => {
    // Second call: detail endpoint
    mockAxiosGet.mockResolvedValueOnce({ data: mockIssuerDetail });

    await act(async () => {
      renderWithEnv(<Issuers />);
    });

    // Click on Alpha Bank row
    await act(async () => {
      fireEvent.click(screen.getByText('Alpha Bank'));
    });

    await waitFor(() => {
      expect(screen.getByText('admin@alpha.example.com')).toBeInTheDocument();
      expect(screen.getByText('123 Main St')).toBeInTheDocument();
    });
  });

  it('shows certificate info when expanded', async () => {
    mockAxiosGet.mockResolvedValueOnce({ data: mockIssuerDetail });

    await act(async () => {
      renderWithEnv(<Issuers />);
    });

    await act(async () => {
      fireEvent.click(screen.getByText('Alpha Bank'));
    });

    await waitFor(() => {
      expect(screen.getByText('CN=Alpha Bank')).toBeInTheDocument();
      expect(screen.getByText('AB:CD:EF')).toBeInTheDocument();
      expect(screen.getByText('Regenerate Certificates')).toBeInTheDocument();
    });
  });

  it('shows IACA certificate info', async () => {
    mockAxiosGet.mockResolvedValueOnce({ data: mockIssuerDetail });

    await act(async () => {
      renderWithEnv(<Issuers />);
    });

    await act(async () => {
      fireEvent.click(screen.getByText('Alpha Bank'));
    });

    await waitFor(() => {
      expect(screen.getByText('CN=IACA Alpha Bank')).toBeInTheDocument();
      expect(screen.getByText('FF:EE:DD')).toBeInTheDocument();
    });
  });

  it('shows credential configurations section', async () => {
    mockAxiosGet.mockResolvedValueOnce({ data: mockIssuerDetail });

    await act(async () => {
      renderWithEnv(<Issuers />);
    });

    await act(async () => {
      fireEvent.click(screen.getByText('Alpha Bank'));
    });

    await waitFor(() => {
      expect(screen.getByText('BankId')).toBeInTheDocument();
      expect(screen.getByText('Edit Credential Configurations')).toBeInTheDocument();
    });
  });

  it('shows action buttons (Suspend, Delete) for active issuer', async () => {
    mockAxiosGet.mockResolvedValueOnce({ data: mockIssuerDetail });

    await act(async () => {
      renderWithEnv(<Issuers />);
    });

    await act(async () => {
      fireEvent.click(screen.getByText('Alpha Bank'));
    });

    await waitFor(() => {
      expect(screen.getByText('Suspend')).toBeInTheDocument();
      expect(screen.getByText('Delete')).toBeInTheDocument();
    });
  });

  it('collapses detail panel on second click', async () => {
    mockAxiosGet.mockResolvedValueOnce({ data: mockIssuerDetail });

    await act(async () => {
      renderWithEnv(<Issuers />);
    });

    // Expand
    await act(async () => {
      fireEvent.click(screen.getByText('Alpha Bank'));
    });

    await waitFor(() => {
      expect(screen.getByText('admin@alpha.example.com')).toBeInTheDocument();
    });

    // Collapse
    await act(async () => {
      fireEvent.click(screen.getByText('Alpha Bank'));
    });

    await waitFor(() => {
      expect(screen.queryByText('admin@alpha.example.com')).not.toBeInTheDocument();
    });
  });
});

// ====================================================================
// Issuers Page - Status Badge Styling
// ====================================================================

describe('Issuers Page - Status Badge', () => {
  beforeEach(() => {
    jest.clearAllMocks();
    mockAxiosGet.mockResolvedValueOnce({ data: mockIssuerList });
  });

  it('ACTIVE badge has emerald styling', async () => {
    await act(async () => {
      renderWithEnv(<Issuers />);
    });

    const activeBadge = screen.getByText('ACTIVE');
    expect(activeBadge.className).toContain('emerald');
  });

  it('SUSPENDED badge has amber styling', async () => {
    await act(async () => {
      renderWithEnv(<Issuers />);
    });

    const suspendedBadge = screen.getByText('SUSPENDED');
    expect(suspendedBadge.className).toContain('amber');
  });
});

// ====================================================================
// Issuers Page - Generate Certificate Action
// ====================================================================

describe('Issuers Page - Actions', () => {
  beforeEach(() => {
    jest.clearAllMocks();
    mockAxiosGet.mockResolvedValueOnce({ data: mockIssuerList });
  });

  it('calls generate certificate API when button clicked', async () => {
    // Detail fetch
    mockAxiosGet.mockResolvedValueOnce({ data: mockIssuerDetail });
    // Generate cert POST
    mockAxiosPost.mockResolvedValueOnce({ data: {} });
    // Re-fetch detail after cert gen
    mockAxiosGet.mockResolvedValueOnce({ data: mockIssuerDetail });
    // Re-fetch list after cert gen
    mockAxiosGet.mockResolvedValueOnce({ data: mockIssuerList });

    await act(async () => {
      renderWithEnv(<Issuers />);
    });

    // Expand detail
    await act(async () => {
      fireEvent.click(screen.getByText('Alpha Bank'));
    });

    await waitFor(() => {
      expect(screen.getByText('Regenerate Certificates')).toBeInTheDocument();
    });

    // Click regenerate
    await act(async () => {
      fireEvent.click(screen.getByText('Regenerate Certificates'));
    });

    expect(mockAxiosPost).toHaveBeenCalledWith(
      'http://localhost:7002/admin/issuer/tenant-1/certificate/generate'
    );
  });
});

// ====================================================================
// Issuers Page - No Cert Generate Button
// ====================================================================

describe('Issuers Page - No Certificate State', () => {
  beforeEach(() => {
    jest.clearAllMocks();
    mockAxiosGet.mockResolvedValueOnce({ data: mockIssuerList });
  });

  it('shows generate button when tenant has no certificate', async () => {
    const detailNoCert = {
      ...mockIssuerDetail,
      id: 'tenant-2',
      legalName: 'Beta Corp',
      signerCertificate: undefined,
      iacaCertificate: undefined,
      x5Chain: undefined,
      status: 'SUSPENDED',
    };
    mockAxiosGet.mockResolvedValueOnce({ data: detailNoCert });

    await act(async () => {
      renderWithEnv(<Issuers />);
    });

    await act(async () => {
      fireEvent.click(screen.getByText('Beta Corp'));
    });

    await waitFor(() => {
      expect(screen.getByText(/No certificates generated yet/)).toBeInTheDocument();
      expect(screen.getByText(/Generate IACA/)).toBeInTheDocument();
    });
  });
});
