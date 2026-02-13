/**
 * @jest-environment jsdom
 */
import React from 'react';
import { render, screen, fireEvent, waitFor, act } from '@testing-library/react';
import '@testing-library/jest-dom';

// ---------------------------------------------------------------------------
// Mocks
// ---------------------------------------------------------------------------

// Mock next/router - shared mutable query object so tests can adjust it
const mockPush = jest.fn();
const mockRouterQuery: Record<string, string> = {};
jest.mock('next/router', () => ({
  useRouter: () => ({
    pathname: '/credentials',
    push: mockPush,
    query: mockRouterQuery,
  }),
}));

// Mock next.config (still needed — components import it for API URL fallback)
jest.mock('@/next.config', () => ({
  __esModule: true,
  default: {
    publicRuntimeConfig: {},
  },
}));

// Mock next/font/google (used by pages/index.tsx)
jest.mock('next/font/google', () => ({
  Inter: () => ({ className: 'inter-mock' }),
}));

// Mock axios
const mockAxiosGet = jest.fn();
const mockAxiosPost = jest.fn();
jest.mock('axios', () => ({
  __esModule: true,
  default: {
    get: (...args: any[]) => mockAxiosGet(...args),
    post: (...args: any[]) => mockAxiosPost(...args),
  },
}));

// Mock heroicons
jest.mock('@heroicons/react/24/outline', () => ({
  ViewfinderCircleIcon: (props: any) => <svg data-testid="viewfinder-icon" {...props} />,
  LockClosedIcon: (props: any) => <svg data-testid="lock-icon" {...props} />,
  BuildingLibraryIcon: (props: any) => <svg data-testid="building-library-icon" {...props} />,
  BuildingOfficeIcon: (props: any) => <svg data-testid="building-office-icon" {...props} />,
  MagnifyingGlassIcon: (props: any) => <svg data-testid="search-icon" {...props} />,
  Cog6ToothIcon: (props: any) => <svg data-testid="cog-icon" {...props} />,
  GlobeAltIcon: (props: any) => <svg data-testid="globe-icon" {...props} />,
  ArrowLeftIcon: (props: any) => <svg data-testid="arrow-left-icon" {...props} />,
}));

jest.mock('react-icons/tb', () => ({
  TbRubberStamp: (props: any) => <svg data-testid="stamp-icon" {...props} />,
}));

jest.mock('react-icons/ai', () => ({
  AiOutlineLoading3Quarters: (props: any) => <svg data-testid="loading-icon" {...props} />,
}));

jest.mock('@/components/walt/logo/WaltIcon', () => ({
  __esModule: true,
  default: (props: any) => <svg data-testid="walt-icon" {...props} />,
}));

// Mock sub-components used by IssueSection and VerificationSection
jest.mock('@/components/walt/credential/RowCredential', () => ({
  __esModule: true,
  default: (props: any) => <div data-testid="row-credential">{props.credentialToEdit?.title}</div>,
}));

jest.mock('@/components/walt/forms/SelectButton', () => ({
  __esModule: true,
  default: (props: any) => (
    <button data-testid={`select-btn-${props.children}`} onClick={props.onClick}>
      {props.children}
    </button>
  ),
}));

jest.mock('@/components/walt/credential/Credential', () => ({
  __esModule: true,
  default: (props: any) => (
    <div data-testid={`credential-${props.id}`} onClick={() => props.onClick(props.id)}>
      {props.title}
    </div>
  ),
}));

jest.mock('@/components/walt/modal/CustomCredentialModal', () => ({
  __esModule: true,
  default: () => <div data-testid="custom-modal" />,
}));

jest.mock('@/components/walt/policy/PolicyListItem', () => ({
  __esModule: true,
  default: (props: any) => <div data-testid={`policy-${props.name}`}>{props.name}</div>,
}));

jest.mock('@/components/walt/forms/Dropdown', () => ({
  __esModule: true,
  default: (props: any) => <select data-testid="dropdown" />,
}));

jest.mock('@/components/walt/forms/Checkbox', () => ({
  __esModule: true,
  default: (props: any) => (
    <label>
      <input
        type="checkbox"
        checked={props.value}
        onChange={(e) => props.onChange(e.target.checked)}
      />
      {props.children}
    </label>
  ),
}));

jest.mock('@/components/walt/forms/Input', () => ({
  __esModule: true,
  default: (props: any) => (
    <input
      data-testid={`input-${props.name}`}
      value={props.value}
      onChange={(e: any) => props.onChange(e.target.value)}
      placeholder={props.placeholder}
    />
  ),
}));

jest.mock('@/utils/getOfferUrl', () => ({
  getOfferUrl: jest.fn().mockResolvedValue({ data: 'mock-offer-url' }),
}));

jest.mock('@/utils/sendToWebWallet', () => ({
  sendToWebWallet: jest.fn(),
}));

jest.mock('@/lib/helper/addQueryParamToCurrentURL', () => ({
  addQueryParamToCurrentURL: jest.fn(),
}));

// ---------------------------------------------------------------------------
// Imports (after mocks)
// ---------------------------------------------------------------------------
import { EnvContext, CredentialsContext } from '@/pages/_app';
import IssueSection from '@/components/sections/IssueSection';
import VerificationSection from '@/components/sections/VerificationSection';
import Home from '@/pages/index';

// ---------------------------------------------------------------------------
// Test data
// ---------------------------------------------------------------------------
const mockEnv = {
  NEXT_PUBLIC_ISSUER: 'http://localhost:7002',
  NEXT_PUBLIC_VERIFIER2: 'http://localhost:7004',
};

const mockCredentials = [
  { id: '1', title: 'Test Credential', selectedFormat: 'JWT + W3C VC' },
];

const mockTenantList = [
  {
    id: 't-1',
    legalName: 'Active Bank',
    country: 'AU',
    status: 'ACTIVE',
    hasCertificate: true,
    credentialCount: 2,
  },
  {
    id: 't-2',
    legalName: 'Suspended Corp',
    country: 'US',
    status: 'SUSPENDED',
    hasCertificate: true,
    credentialCount: 1,
  },
  {
    id: 't-3',
    legalName: 'No Cert Inc',
    country: 'DE',
    status: 'ACTIVE',
    hasCertificate: false,
    credentialCount: 0,
  },
];

const mockRpList = [
  {
    id: 'rp-1',
    legalName: 'Acme Verifier',
    domain: 'verify.acme.com',
    country: 'AU',
    status: 'ACTIVE',
    hasCertificate: true,
  },
  {
    id: 'rp-2',
    legalName: 'Suspended RP',
    domain: 'suspended.com',
    country: 'US',
    status: 'SUSPENDED',
    hasCertificate: true,
  },
];

// ---------------------------------------------------------------------------
// Helper
// ---------------------------------------------------------------------------
function renderWithProviders(
  ui: React.ReactElement,
  { env = {}, credentials = mockCredentials, routerQuery = {} } = {}
) {
  // Populate the shared router query object
  Object.keys(mockRouterQuery).forEach((k) => delete mockRouterQuery[k]);
  Object.assign(mockRouterQuery, { ids: '1', ...routerQuery });

  return render(
    <EnvContext.Provider value={{ ...mockEnv, ...env } as Record<string, string>}>
      <CredentialsContext.Provider value={[credentials as any, jest.fn()]}>
        {ui}
      </CredentialsContext.Provider>
    </EnvContext.Provider>
  );
}

// ============================================================================
// IssueSection Tests (12 tests)
// ============================================================================
describe('IssueSection', () => {
  beforeEach(() => {
    jest.clearAllMocks();
    Object.keys(mockRouterQuery).forEach((k) => delete mockRouterQuery[k]);
  });

  // ---- Feature flag OFF ----
  describe('Feature flag off', () => {
    it('shows no tenant dropdown when issuer registrar is disabled', async () => {
      await act(async () => {
        renderWithProviders(<IssueSection />);
      });
      expect(screen.queryByTestId('tenant-select')).not.toBeInTheDocument();
    });

    it('renders normally without dropdown showing Customise Issuance heading', async () => {
      await act(async () => {
        renderWithProviders(<IssueSection />);
      });
      expect(screen.getByText('Customise Issuance')).toBeInTheDocument();
    });
  });

  // ---- Feature flag ON ----
  describe('Feature flag on', () => {
    const issuerEnv = { NEXT_PUBLIC_ISSUER_REGISTRAR_ENABLED: 'true' };

    it('renders tenant dropdown when tenants are returned with matching credentials', async () => {
      // List tenants, then detail fetch for Active Bank (only ACTIVE+hasCert tenant)
      mockAxiosGet
        .mockResolvedValueOnce({ data: mockTenantList })
        .mockResolvedValueOnce({ data: { credentialConfigurations: { '1': { format: 'jwt_vc_json' } } } });

      await act(async () => {
        renderWithProviders(<IssueSection />, { env: issuerEnv });
      });

      await waitFor(() => {
        expect(screen.getByTestId('tenant-select')).toBeInTheDocument();
      });
    });

    it('fetches /admin/issuer on mount', async () => {
      mockAxiosGet.mockResolvedValueOnce({ data: [] });

      await act(async () => {
        renderWithProviders(<IssueSection />, { env: issuerEnv });
      });

      await waitFor(() => {
        expect(mockAxiosGet).toHaveBeenCalledWith('http://localhost:7002/admin/issuer');
      });
    });

    it('shows only ACTIVE tenants with certificates and matching credentials', async () => {
      // List tenants, then detail fetch for Active Bank
      mockAxiosGet
        .mockResolvedValueOnce({ data: mockTenantList })
        .mockResolvedValueOnce({ data: { credentialConfigurations: { '1': { format: 'jwt_vc_json' } } } });

      await act(async () => {
        renderWithProviders(<IssueSection />, { env: issuerEnv });
      });

      await waitFor(() => {
        expect(screen.getByTestId('tenant-select')).toBeInTheDocument();
      });

      const select = screen.getByTestId('tenant-select');
      const options = select.querySelectorAll('option');

      // "Select an issuer..." + Active Bank only (Suspended Corp filtered, No Cert Inc filtered)
      expect(options).toHaveLength(2);
      expect(options[0].textContent).toBe('Select an issuer...');
      expect(options[1].textContent).toBe('Active Bank (AU)');
    });
  });

  // ---- Tenant selection ----
  describe('Tenant selection', () => {
    const issuerEnv = { NEXT_PUBLIC_ISSUER_REGISTRAR_ENABLED: 'true' };

    it('selecting a tenant changes the select value', async () => {
      // List tenants, then detail fetch for Active Bank with matching credential
      mockAxiosGet
        .mockResolvedValueOnce({ data: mockTenantList })
        .mockResolvedValueOnce({ data: { credentialConfigurations: { '1': { format: 'jwt_vc_json' } } } });

      await act(async () => {
        renderWithProviders(<IssueSection />, { env: issuerEnv });
      });

      await waitFor(() => {
        expect(screen.getByTestId('tenant-select')).toBeInTheDocument();
      });

      const select = screen.getByTestId('tenant-select') as HTMLSelectElement;
      await act(async () => {
        fireEvent.change(select, { target: { value: 't-1' } });
      });

      expect(select.value).toBe('t-1');
    });

    it('has a placeholder option with empty value', async () => {
      mockAxiosGet
        .mockResolvedValueOnce({ data: mockTenantList })
        .mockResolvedValueOnce({ data: { credentialConfigurations: { '1': { format: 'jwt_vc_json' } } } });

      await act(async () => {
        renderWithProviders(<IssueSection />, { env: issuerEnv });
      });

      await waitFor(() => {
        expect(screen.getByTestId('tenant-select')).toBeInTheDocument();
      });

      const defaultOption = screen.getByText('Select an issuer...') as HTMLOptionElement;
      expect(defaultOption.value).toBe('');
    });

    it('detail API is fetched during initial tenant load', async () => {
      // The detail API is now batch-fetched during the initial tenant load
      mockAxiosGet
        .mockResolvedValueOnce({ data: mockTenantList })
        .mockResolvedValueOnce({ data: { credentialConfigurations: { '1': {} } } });

      await act(async () => {
        renderWithProviders(<IssueSection />, { env: issuerEnv });
      });

      await waitFor(() => {
        expect(mockAxiosGet).toHaveBeenCalledWith('http://localhost:7002/admin/issuer/t-1');
      });
    });

    it('selecting a tenant shows the credential count', async () => {
      // Active Bank has 2 credential configs that include '1' (matching the test credential)
      mockAxiosGet
        .mockResolvedValueOnce({ data: mockTenantList })
        .mockResolvedValueOnce({
          data: { credentialConfigurations: { '1': {}, 'mdl': {} } },
        });

      await act(async () => {
        renderWithProviders(<IssueSection />, { env: issuerEnv });
      });

      await waitFor(() => {
        expect(screen.getByTestId('tenant-select')).toBeInTheDocument();
      });

      const select = screen.getByTestId('tenant-select') as HTMLSelectElement;
      await act(async () => {
        fireEvent.change(select, { target: { value: 't-1' } });
      });

      await waitFor(() => {
        expect(screen.getByText(/Tenant has 2 credential configurations/)).toBeInTheDocument();
      });
    });
  });

  // ---- Error handling ----
  describe('Error handling', () => {
    const issuerEnv = { NEXT_PUBLIC_ISSUER_REGISTRAR_ENABLED: 'true' };

    it('hides dropdown gracefully on API failure', async () => {
      mockAxiosGet.mockRejectedValueOnce(new Error('Network error'));

      await act(async () => {
        renderWithProviders(<IssueSection />, { env: issuerEnv });
      });

      // Should not crash — heading still renders, no dropdown
      expect(screen.getByText('Customise Issuance')).toBeInTheDocument();
      expect(screen.queryByTestId('tenant-select')).not.toBeInTheDocument();
    });

    it('shows empty state when API returns empty array', async () => {
      mockAxiosGet.mockResolvedValueOnce({ data: [] });

      await act(async () => {
        renderWithProviders(<IssueSection />, { env: issuerEnv });
      });

      await waitFor(() => {
        expect(mockAxiosGet).toHaveBeenCalled();
      });

      // Dropdown not shown, but the issuer section is visible with loading message
      expect(screen.queryByTestId('tenant-select')).not.toBeInTheDocument();
      expect(screen.getByText('Loading issuers...')).toBeInTheDocument();
    });

    it('handles detail fetch error without crashing', async () => {
      // Detail fetch fails — tenant has no matching credentials, so no dropdown
      mockAxiosGet
        .mockResolvedValueOnce({ data: mockTenantList })
        .mockRejectedValueOnce(new Error('Detail error'));

      await act(async () => {
        renderWithProviders(<IssueSection />, { env: issuerEnv });
      });

      // Should not crash — the detail API was called
      await waitFor(() => {
        expect(mockAxiosGet).toHaveBeenCalledWith('http://localhost:7002/admin/issuer/t-1');
      });

      // No matching credentials so "No issuers available" message shown
      expect(screen.getByText('No issuers available for this credential')).toBeInTheDocument();
    });
  });
});

// ============================================================================
// VerificationSection Tests (10 tests)
// ============================================================================
describe('VerificationSection', () => {
  beforeEach(() => {
    jest.clearAllMocks();
    Object.keys(mockRouterQuery).forEach((k) => delete mockRouterQuery[k]);
  });

  // ---- Feature flag OFF ----
  describe('Feature flag off', () => {
    it('shows no RP dropdown when RP registrar is disabled', async () => {
      await act(async () => {
        renderWithProviders(<VerificationSection />);
      });
      expect(screen.queryByTestId('rp-tenant-select')).not.toBeInTheDocument();
    });

    it('renders normally with Customise Verification heading', async () => {
      await act(async () => {
        renderWithProviders(<VerificationSection />);
      });
      expect(screen.getByText('Customise Verification')).toBeInTheDocument();
    });
  });

  // ---- Feature flag ON ----
  describe('Feature flag on', () => {
    const rpEnv = { NEXT_PUBLIC_RP_REGISTRAR_ENABLED: 'true' };

    it('renders RP dropdown when RPs are returned', async () => {
      mockAxiosGet.mockResolvedValueOnce({ data: mockRpList });

      await act(async () => {
        renderWithProviders(<VerificationSection />, { env: rpEnv });
      });

      await waitFor(() => {
        expect(screen.getByTestId('rp-tenant-select')).toBeInTheDocument();
      });
    });

    it('fetches /admin/rp on mount', async () => {
      mockAxiosGet.mockResolvedValueOnce({ data: [] });

      await act(async () => {
        renderWithProviders(<VerificationSection />, { env: rpEnv });
      });

      await waitFor(() => {
        expect(mockAxiosGet).toHaveBeenCalledWith('http://localhost:7004/admin/rp');
      });
    });

    it('shows only ACTIVE RPs with certificates', async () => {
      mockAxiosGet.mockResolvedValueOnce({ data: mockRpList });

      await act(async () => {
        renderWithProviders(<VerificationSection />, { env: rpEnv });
      });

      await waitFor(() => {
        expect(screen.getByTestId('rp-tenant-select')).toBeInTheDocument();
      });

      const select = screen.getByTestId('rp-tenant-select');
      const options = select.querySelectorAll('option');

      // Default + Acme Verifier only (Suspended RP filtered)
      expect(options).toHaveLength(2);
      expect(options[0].textContent).toBe('Default verifier (no RP)');
      expect(options[1].textContent).toBe('Acme Verifier (verify.acme.com)');
    });
  });

  // ---- RP selection ----
  describe('RP selection', () => {
    const rpEnv = { NEXT_PUBLIC_RP_REGISTRAR_ENABLED: 'true' };

    it('selecting an RP changes the select value', async () => {
      mockAxiosGet.mockResolvedValueOnce({ data: mockRpList });

      await act(async () => {
        renderWithProviders(<VerificationSection />, { env: rpEnv });
      });

      await waitFor(() => {
        expect(screen.getByTestId('rp-tenant-select')).toBeInTheDocument();
      });

      const select = screen.getByTestId('rp-tenant-select') as HTMLSelectElement;
      await act(async () => {
        fireEvent.change(select, { target: { value: 'rp-1' } });
      });

      expect(select.value).toBe('rp-1');
    });

    it('has a Default verifier option with empty value', async () => {
      mockAxiosGet.mockResolvedValueOnce({ data: mockRpList });

      await act(async () => {
        renderWithProviders(<VerificationSection />, { env: rpEnv });
      });

      await waitFor(() => {
        expect(screen.getByTestId('rp-tenant-select')).toBeInTheDocument();
      });

      const defaultOption = screen.getByText('Default verifier (no RP)') as HTMLOptionElement;
      expect(defaultOption.value).toBe('');
    });

    it('verify button includes rpId in the pushed URL', async () => {
      mockAxiosGet.mockResolvedValueOnce({ data: mockRpList });

      await act(async () => {
        renderWithProviders(<VerificationSection />, { env: rpEnv });
      });

      await waitFor(() => {
        expect(screen.getByTestId('rp-tenant-select')).toBeInTheDocument();
      });

      // Select the RP
      const select = screen.getByTestId('rp-tenant-select') as HTMLSelectElement;
      await act(async () => {
        fireEvent.change(select, { target: { value: 'rp-1' } });
      });

      // Click Verify button
      const verifyButton = screen.getByText('Verify');
      await act(async () => {
        fireEvent.click(verifyButton);
      });

      expect(mockPush).toHaveBeenCalled();
      const pushedUrl = mockPush.mock.calls[0][0] as string;
      expect(pushedUrl).toContain('rpId=rp-1');
    });
  });

  // ---- Error handling ----
  describe('Error handling', () => {
    const rpEnv = { NEXT_PUBLIC_RP_REGISTRAR_ENABLED: 'true' };

    it('hides dropdown gracefully on API failure', async () => {
      mockAxiosGet.mockRejectedValueOnce(new Error('Network error'));

      await act(async () => {
        renderWithProviders(<VerificationSection />, { env: rpEnv });
      });

      expect(screen.getByText('Customise Verification')).toBeInTheDocument();
      expect(screen.queryByTestId('rp-tenant-select')).not.toBeInTheDocument();
    });

    it('shows no dropdown when API returns empty list', async () => {
      mockAxiosGet.mockResolvedValueOnce({ data: [] });

      await act(async () => {
        renderWithProviders(<VerificationSection />, { env: rpEnv });
      });

      await waitFor(() => {
        expect(mockAxiosGet).toHaveBeenCalled();
      });

      expect(screen.queryByTestId('rp-tenant-select')).not.toBeInTheDocument();
    });
  });
});

// ============================================================================
// Homepage MT Banner Tests (10 tests)
//
// The Home component reads feature flags from EnvContext, so we pass them
// via the renderHome helper's env parameter.
// ============================================================================
describe('Homepage - MT Banner', () => {
  beforeEach(() => {
    jest.clearAllMocks();
    Object.keys(mockRouterQuery).forEach((k) => delete mockRouterQuery[k]);
  });

  function renderHome(env: Record<string, string> = {}) {
    return render(
      <EnvContext.Provider value={{ ...mockEnv, ...env } as Record<string, string>}>
        <CredentialsContext.Provider value={[[], jest.fn()]}>
          <Home />
        </CredentialsContext.Provider>
      </EnvContext.Provider>
    );
  }

  // ---- Both flags OFF ----
  describe('Both flags off', () => {
    it('does not show mt-banner when both flags are disabled', () => {
      renderHome();
      expect(screen.queryByTestId('mt-banner')).not.toBeInTheDocument();
    });

    it('renders page normally with Walt.id Portal heading', () => {
      renderHome();
      expect(screen.getByText('Walt.id Portal')).toBeInTheDocument();
    });
  });

  // ---- Issuer registrar only ----
  describe('Issuer registrar only', () => {
    const issuerOnlyEnv = { NEXT_PUBLIC_ISSUER_REGISTRAR_ENABLED: 'true' };

    it('shows the mt-banner', () => {
      renderHome(issuerOnlyEnv);
      expect(screen.getByTestId('mt-banner')).toBeInTheDocument();
    });

    it('shows Issuer Registrar badge', () => {
      renderHome(issuerOnlyEnv);
      expect(screen.getByText('Issuer Registrar')).toBeInTheDocument();
    });

    it('does NOT show RP Registrar badge', () => {
      renderHome(issuerOnlyEnv);
      expect(screen.queryByText('RP Registrar')).not.toBeInTheDocument();
    });
  });

  // ---- RP registrar only ----
  describe('RP registrar only', () => {
    const rpOnlyEnv = { NEXT_PUBLIC_RP_REGISTRAR_ENABLED: 'true' };

    it('shows the mt-banner', () => {
      renderHome(rpOnlyEnv);
      expect(screen.getByTestId('mt-banner')).toBeInTheDocument();
    });

    it('shows RP Registrar badge', () => {
      renderHome(rpOnlyEnv);
      expect(screen.getByText('RP Registrar')).toBeInTheDocument();
    });
  });

  // ---- Both enabled ----
  describe('Both enabled', () => {
    const bothEnv = {
      NEXT_PUBLIC_ISSUER_REGISTRAR_ENABLED: 'true',
      NEXT_PUBLIC_RP_REGISTRAR_ENABLED: 'true',
    };

    it('shows both Issuer Registrar and RP Registrar badges', () => {
      renderHome(bothEnv);
      expect(screen.getByText('Issuer Registrar')).toBeInTheDocument();
      expect(screen.getByText('RP Registrar')).toBeInTheDocument();
    });

    it('shows Multi-Tenant Mode text', () => {
      renderHome(bothEnv);
      expect(screen.getByText('Multi-Tenant Mode')).toBeInTheDocument();
    });

    it('has the mt-banner data-testid', () => {
      renderHome(bothEnv);
      expect(screen.getByTestId('mt-banner')).toBeInTheDocument();
    });
  });
});
