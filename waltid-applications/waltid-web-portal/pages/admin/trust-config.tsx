import React, {
  useState,
  useEffect,
  useContext,
  useCallback,
  useRef,
} from 'react';
import axios from 'axios';
import { useRouter } from 'next/router';
import { EnvContext } from '@/pages/_app';
import Button from '@/components/walt/button/Button';
import WaltIcon from '@/components/walt/logo/WaltIcon';
import InputField from '@/components/walt/forms/Input';
import {
  ArrowPathIcon,
  MagnifyingGlassIcon,
  ChevronDownIcon,
  ChevronRightIcon,
  PencilIcon,
} from '@heroicons/react/24/outline';
import AdminNav from '@/components/walt/nav/AdminNav';

// -- Interfaces --

interface TrustSourceStatus {
  enabled: boolean;
  healthy: boolean;
  lastUpdate?: string;
  entryCount: number;
  error?: string;
}

interface TrustServiceStatus {
  healthy: boolean;
  sources: Record<string, TrustSourceStatus>;
  lastUpdate?: string;
}

interface TestValidationResult {
  trusted: boolean;
  provider?: string;
  country?: string;
  source?: string;
  message?: string;
}

interface MemberStateSummary {
  country: string;
  location: string;
  providerCount: number;
  serviceCount: number;
  healthy: boolean;
}

interface LotlOverview {
  schemeTerritory: string;
  schemeOperatorName: string;
  listIssueDate?: string;
  nextUpdate?: string;
  sequenceNumber?: number;
  memberStates: MemberStateSummary[];
}

interface ServiceDetail {
  serviceName: string;
  serviceType: string;
  serviceTypeLabel: string;
  status: string;
  statusRaw: string;
  statusStartingTime?: string;
  isQualified: boolean;
  x509SubjectName?: string;
  x509Certificate?: string;
}

interface ProviderDetail {
  name: string;
  tradeName?: string;
  country?: string;
  services: ServiceDetail[];
}

interface CountryTslDetail {
  schemeTerritory: string;
  schemeOperatorName: string;
  listIssueDate?: string;
  nextUpdate?: string;
  sequenceNumber?: number;
  providers: ProviderDetail[];
}

interface SearchResponse {
  query?: string;
  country?: string;
  status?: string;
  serviceType?: string;
  total: number;
  providers: ProviderDetail[];
}

interface CustomTslEntry {
  country: string;
  url: string;
  providerCount: number;
  serviceCount: number;
  loaded: boolean;
}

interface CustomTslListResponse {
  customTsls: CustomTslEntry[];
}

// -- Constants --

const SOURCE_LABELS: Record<string, { name: string; description: string }> = {
  etsi_tl: {
    name: 'EU Trusted List (ETSI)',
    description: 'ETSI TS 119 612 Trust Service Lists from EU Member States',
  },
  openid_federation: {
    name: 'OpenID Federation',
    description: 'OpenID Federation 1.0 trust chain resolution',
  },
  vical: {
    name: 'VICAL',
    description: 'Verifiable Issuer Certificate Authority List',
  },
  static_list: {
    name: 'Static Trust List',
    description: 'Manually configured trusted issuers',
  },
};

const COUNTRY_FLAGS: Record<string, string> = {
  AT: '\u{1F1E6}\u{1F1F9}',
  BE: '\u{1F1E7}\u{1F1EA}',
  BG: '\u{1F1E7}\u{1F1EC}',
  HR: '\u{1F1ED}\u{1F1F7}',
  CY: '\u{1F1E8}\u{1F1FE}',
  CZ: '\u{1F1E8}\u{1F1FF}',
  DK: '\u{1F1E9}\u{1F1F0}',
  EE: '\u{1F1EA}\u{1F1EA}',
  FI: '\u{1F1EB}\u{1F1EE}',
  FR: '\u{1F1EB}\u{1F1F7}',
  DE: '\u{1F1E9}\u{1F1EA}',
  EL: '\u{1F1EC}\u{1F1F7}',
  GR: '\u{1F1EC}\u{1F1F7}',
  HU: '\u{1F1ED}\u{1F1FA}',
  IE: '\u{1F1EE}\u{1F1EA}',
  IT: '\u{1F1EE}\u{1F1F9}',
  LV: '\u{1F1F1}\u{1F1FB}',
  LT: '\u{1F1F1}\u{1F1F9}',
  LU: '\u{1F1F1}\u{1F1FA}',
  MT: '\u{1F1F2}\u{1F1F9}',
  NL: '\u{1F1F3}\u{1F1F1}',
  PL: '\u{1F1F5}\u{1F1F1}',
  PT: '\u{1F1F5}\u{1F1F9}',
  RO: '\u{1F1F7}\u{1F1F4}',
  SK: '\u{1F1F8}\u{1F1F0}',
  SI: '\u{1F1F8}\u{1F1EE}',
  ES: '\u{1F1EA}\u{1F1F8}',
  SE: '\u{1F1F8}\u{1F1EA}',
  EU: '\u{1F1EA}\u{1F1FA}',
  NO: '\u{1F1F3}\u{1F1F4}',
  IS: '\u{1F1EE}\u{1F1F8}',
  LI: '\u{1F1F1}\u{1F1EE}',
  UK: '\u{1F1EC}\u{1F1E7}',
  TR: '\u{1F1F9}\u{1F1F7}',
  RS: '\u{1F1F7}\u{1F1F8}',
  ME: '\u{1F1F2}\u{1F1EA}',
  MK: '\u{1F1F2}\u{1F1F0}',
  AL: '\u{1F1E6}\u{1F1F1}',
  CH: '\u{1F1E8}\u{1F1ED}',
  AU: '\u{1F1E6}\u{1F1FA}',
  NZ: '\u{1F1F3}\u{1F1FF}',
  US: '\u{1F1FA}\u{1F1F8}',
  CA: '\u{1F1E8}\u{1F1E6}',
  JP: '\u{1F1EF}\u{1F1F5}',
  KR: '\u{1F1F0}\u{1F1F7}',
  IN: '\u{1F1EE}\u{1F1F3}',
  BR: '\u{1F1E7}\u{1F1F7}',
  SG: '\u{1F1F8}\u{1F1EC}',
};

function getFlag(country: string): string {
  return COUNTRY_FLAGS[country.toUpperCase()] || '\u{1F3F3}\u{FE0F}';
}

// -- Status badge component --

function StatusBadge({ status }: { status: string }) {
  const colorMap: Record<string, string> = {
    granted: 'bg-emerald-100 text-emerald-800 border-emerald-200',
    withdrawn: 'bg-red-100 text-red-800 border-red-200',
    deprecated: 'bg-amber-100 text-amber-800 border-amber-200',
    recognised: 'bg-amber-100 text-amber-800 border-amber-200',
  };
  const colors =
    colorMap[status] || 'bg-gray-100 text-gray-700 border-gray-200';

  return (
    <span
      className={`inline-flex items-center px-2 py-0.5 rounded text-xs font-medium border ${colors}`}
    >
      {status}
    </span>
  );
}

// -- Main component --

export default function TrustConfig() {
  const env = useContext(EnvContext);
  const router = useRouter();
  const [activeTab, setActiveTab] = useState<
    'status' | 'trust-lists' | 'custom-tsls'
  >('status');

  // Status tab state
  const [status, setStatus] = useState<TrustServiceStatus | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [refreshing, setRefreshing] = useState(false);
  const [testDid, setTestDid] = useState('');
  const [testType, setTestType] = useState('');
  const [testResult, setTestResult] = useState<TestValidationResult | null>(
    null
  );
  const [testing, setTesting] = useState(false);
  const [testError, setTestError] = useState<string | null>(null);

  // Trust Lists tab state
  const [lotl, setLotl] = useState<LotlOverview | null>(null);
  const [lotlLoading, setLotlLoading] = useState(false);
  const [lotlError, setLotlError] = useState<string | null>(null);
  const [expandedCountry, setExpandedCountry] = useState<string | null>(null);
  const [countryDetail, setCountryDetail] = useState<
    Record<string, CountryTslDetail>
  >({});
  const [countryLoading, setCountryLoading] = useState<string | null>(null);

  // Search state
  const [searchQuery, setSearchQuery] = useState('');
  const [searchResults, setSearchResults] = useState<SearchResponse | null>(
    null
  );
  const [searching, setSearching] = useState(false);
  const searchTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);

  // Custom TSLs tab state
  const [customTsls, setCustomTsls] = useState<CustomTslEntry[]>([]);
  const [customTslsLoading, setCustomTslsLoading] = useState(false);
  const [customTslsError, setCustomTslsError] = useState<string | null>(null);
  const [importCountry, setImportCountry] = useState('');
  const [importUrl, setImportUrl] = useState('');
  const [importing, setImporting] = useState(false);
  const [importError, setImportError] = useState<string | null>(null);
  const [importSuccess, setImportSuccess] = useState<string | null>(null);
  const [deleting, setDeleting] = useState<string | null>(null);
  const [expandedCustomTsl, setExpandedCustomTsl] = useState<string | null>(
    null
  );
  const [customTslDetail, setCustomTslDetail] = useState<
    Record<string, CountryTslDetail>
  >({});
  const [customTslDetailLoading, setCustomTslDetailLoading] = useState<
    string | null
  >(null);
  const [editingTsl, setEditingTsl] = useState<string | null>(null);
  const [editUrl, setEditUrl] = useState('');
  const [editSaving, setEditSaving] = useState(false);
  const [editError, setEditError] = useState<string | null>(null);

  const apiBase = '/api/proxy/verifier2';

  // -- Status tab handlers --

  const fetchStatus = async () => {
    try {
      const response = await axios.get(`${apiBase}/admin/trust/status`);
      setStatus(response.data);
      setError(null);
    } catch (e: any) {
      if (e.response?.status === 503) {
        setError(
          'Trust lists feature is not enabled. Set TRUST_LISTS_ENABLED=true in the verifier configuration to enable.'
        );
      } else {
        setError(
          e.response?.data?.message ||
            e.message ||
            'Failed to fetch trust service status'
        );
      }
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    fetchStatus();
  }, []);

  const handleRefresh = async () => {
    setRefreshing(true);
    try {
      await axios.post(`${apiBase}/admin/trust/refresh`);
      await fetchStatus();
    } catch (e: any) {
      setError(
        e.response?.data?.message ||
          e.message ||
          'Failed to refresh trust sources'
      );
    } finally {
      setRefreshing(false);
    }
  };

  const handleToggleSource = async (
    sourceName: string,
    currentEnabled: boolean
  ) => {
    try {
      const endpoint =
        sourceName === 'etsi_tl'
          ? 'etsi'
          : sourceName === 'openid_federation'
            ? 'federation'
            : null;
      if (!endpoint) return;

      await axios.put(`${apiBase}/admin/trust/${endpoint}`, {
        enabled: !currentEnabled,
      });
      await fetchStatus();
    } catch (e: any) {
      setError(
        e.response?.data?.message ||
          e.message ||
          'Failed to toggle trust source'
      );
    }
  };

  const handleTestValidation = async () => {
    if (!testDid.trim()) {
      setTestError('Please enter an issuer DID');
      return;
    }
    setTesting(true);
    setTestError(null);
    setTestResult(null);
    try {
      const params: any = { issuerDid: testDid };
      if (testType.trim()) params.credentialType = testType;
      const response = await axios.get(`${apiBase}/admin/trust/test`, {
        params,
      });
      setTestResult(response.data);
    } catch (e: any) {
      setTestError(
        e.response?.data?.message || e.message || 'Failed to test validation'
      );
    } finally {
      setTesting(false);
    }
  };

  // -- Trust Lists tab handlers --

  const fetchLotl = useCallback(async () => {
    if (!apiBase) return;
    setLotlLoading(true);
    setLotlError(null);
    try {
      const response = await axios.get(`${apiBase}/admin/trust/lotl`);
      setLotl(response.data);
    } catch (e: any) {
      if (e.response?.status === 404) {
        setLotlError(
          'LOTL data not available. Try refreshing trust lists from the Status tab.'
        );
      } else if (e.response?.status === 503) {
        setLotlError('Trust lists feature is not enabled.');
      } else {
        setLotlError(
          e.response?.data?.message || e.message || 'Failed to fetch LOTL data'
        );
      }
    } finally {
      setLotlLoading(false);
    }
  }, [apiBase]);

  useEffect(() => {
    if (activeTab === 'trust-lists' && !lotl && !lotlLoading) {
      fetchLotl();
    }
  }, [activeTab, lotl, lotlLoading, fetchLotl]);

  const handleCountryClick = async (country: string) => {
    if (expandedCountry === country) {
      setExpandedCountry(null);
      return;
    }

    setExpandedCountry(country);

    if (countryDetail[country]) return;

    setCountryLoading(country);
    try {
      const response = await axios.get(
        `${apiBase}/admin/trust/lotl/${country}`
      );
      setCountryDetail((prev) => ({ ...prev, [country]: response.data }));
    } catch (e: any) {
      console.error(`Failed to load TSL for ${country}:`, e);
    } finally {
      setCountryLoading(null);
    }
  };

  const handleSearch = useCallback(
    async (q: string) => {
      if (!q.trim()) {
        setSearchResults(null);
        return;
      }
      setSearching(true);
      try {
        const response = await axios.get(`${apiBase}/admin/trust/search`, {
          params: { q, limit: 50 },
        });
        setSearchResults(response.data);
      } catch (e: any) {
        console.error('Search failed:', e);
        setSearchResults(null);
      } finally {
        setSearching(false);
      }
    },
    [apiBase]
  );

  const onSearchInput = (value: string) => {
    setSearchQuery(value);
    if (searchTimerRef.current) clearTimeout(searchTimerRef.current);
    searchTimerRef.current = setTimeout(() => handleSearch(value), 300);
  };

  // -- Custom TSLs tab handlers --

  const fetchCustomTsls = useCallback(async () => {
    if (!apiBase) return;
    setCustomTslsLoading(true);
    setCustomTslsError(null);
    try {
      const response = await axios.get<CustomTslListResponse>(
        `${apiBase}/admin/trust/custom-tsls`
      );
      setCustomTsls(response.data.customTsls);
    } catch (e: any) {
      if (e.response?.status === 503) {
        setCustomTslsError('Trust lists feature is not enabled.');
      } else {
        setCustomTslsError(
          e.response?.data?.message ||
            e.message ||
            'Failed to fetch custom TSLs'
        );
      }
    } finally {
      setCustomTslsLoading(false);
    }
  }, [apiBase]);

  const handleImportTsl = async () => {
    const country = importCountry.trim().toUpperCase();
    const url = importUrl.trim();

    if (country.length !== 2) {
      setImportError('Country code must be exactly 2 letters (e.g., AU)');
      return;
    }
    if (!url) {
      setImportError('TSL URL is required');
      return;
    }

    setImporting(true);
    setImportError(null);
    setImportSuccess(null);
    try {
      const response = await axios.post(`${apiBase}/admin/trust/custom-tsls`, {
        country,
        url,
      });
      const data = response.data;
      setImportSuccess(
        `Imported ${data.country} TSL: ${data.providerCount} provider${data.providerCount !== 1 ? 's' : ''}, ` +
          `${data.serviceCount} service${data.serviceCount !== 1 ? 's' : ''}`
      );
      setImportCountry('');
      setImportUrl('');
      await fetchCustomTsls();
    } catch (e: any) {
      setImportError(
        e.response?.data?.message || e.message || 'Failed to import TSL'
      );
    } finally {
      setImporting(false);
    }
  };

  const handleDeleteTsl = async (country: string) => {
    setDeleting(country);
    try {
      await axios.delete(`${apiBase}/admin/trust/custom-tsls/${country}`);
      await fetchCustomTsls();
    } catch (e: any) {
      setCustomTslsError(
        e.response?.data?.message ||
          e.message ||
          `Failed to remove ${country} TSL`
      );
    } finally {
      setDeleting(null);
    }
  };

  const handleCustomTslClick = async (country: string) => {
    if (expandedCustomTsl === country) {
      setExpandedCustomTsl(null);
      return;
    }
    setExpandedCustomTsl(country);
    if (customTslDetail[country]) return;
    setCustomTslDetailLoading(country);
    try {
      const response = await axios.get(
        `${apiBase}/admin/trust/lotl/${country}`
      );
      setCustomTslDetail((prev) => ({ ...prev, [country]: response.data }));
    } catch (e: any) {
      console.error(`Failed to load TSL detail for ${country}:`, e);
    } finally {
      setCustomTslDetailLoading(null);
    }
  };

  const handleEditTsl = (tsl: CustomTslEntry) => {
    setEditingTsl(tsl.country);
    setEditUrl(tsl.url);
    setEditError(null);
  };

  const handleEditCancel = () => {
    setEditingTsl(null);
    setEditUrl('');
    setEditError(null);
  };

  const handleEditSave = async (country: string) => {
    const url = editUrl.trim();
    if (!url) {
      setEditError('TSL URL is required');
      return;
    }
    setEditSaving(true);
    setEditError(null);
    try {
      await axios.delete(`${apiBase}/admin/trust/custom-tsls/${country}`);
      await axios.post(`${apiBase}/admin/trust/custom-tsls`, { country, url });
      setCustomTslDetail((prev) => {
        const next = { ...prev };
        delete next[country];
        return next;
      });
      setEditingTsl(null);
      setEditUrl('');
      await fetchCustomTsls();
    } catch (e: any) {
      setEditError(
        e.response?.data?.message || e.message || 'Failed to update TSL'
      );
    } finally {
      setEditSaving(false);
    }
  };

  useEffect(() => {
    if (
      activeTab === 'custom-tsls' &&
      customTsls.length === 0 &&
      !customTslsLoading
    ) {
      fetchCustomTsls();
    }
  }, [activeTab, customTsls.length, customTslsLoading, fetchCustomTsls]);

  const formatTimestamp = (timestamp?: string) => {
    if (!timestamp) return 'Never';
    try {
      const date = new Date(timestamp);
      const now = new Date();
      const diffMs = now.getTime() - date.getTime();
      const diffMins = Math.floor(diffMs / 60000);
      const diffHours = Math.floor(diffMins / 60);
      const diffDays = Math.floor(diffHours / 24);
      if (diffMins < 1) return 'Just now';
      if (diffMins < 60) return `${diffMins}m ago`;
      if (diffHours < 24) return `${diffHours}h ago`;
      if (diffDays < 7) return `${diffDays}d ago`;
      return date.toLocaleDateString();
    } catch {
      return timestamp;
    }
  };

  const formatDate = (dateStr?: string) => {
    if (!dateStr) return '-';
    try {
      return new Date(dateStr).toLocaleDateString('en-GB', {
        day: 'numeric',
        month: 'short',
        year: 'numeric',
      });
    } catch {
      return dateStr;
    }
  };

  // -- Render --

  return (
    <div className="flex flex-col justify-center items-center bg-gray-50 min-h-screen">
      <div className="my-5 flex flex-row items-center gap-4">
        <div
          className="cursor-pointer"
          onClick={() => router.push('/')}
        >
          <WaltIcon height={35} width={35} type="primary" />
        </div>
        <AdminNav />
      </div>

      <div className="w-11/12 md:w-9/12 lg:w-8/12 shadow-2xl rounded-lg mt-5 pt-8 pb-8 px-10 bg-white max-w-[1100px]">
        {/* Header */}
        <div className="flex flex-row justify-between items-center mb-6">
          <div>
            <h1 className="text-2xl font-bold text-gray-900">
              Trust List Configuration
            </h1>
            {status && (
              <div className="flex items-center gap-2 mt-2">
                <div
                  className={`w-3 h-3 rounded-full ${status.healthy ? 'bg-green-500' : 'bg-red-500'}`}
                />
                <span className="text-sm text-gray-600">
                  {status.healthy ? 'System Healthy' : 'System Unhealthy'}
                </span>
              </div>
            )}
          </div>
          <Button
            onClick={handleRefresh}
            loading={refreshing}
            disabled={loading || !!error}
            size="sm"
            color="secondary"
          >
            <div className="flex items-center gap-2">
              <ArrowPathIcon className="w-4 h-4" />
              Refresh
            </div>
          </Button>
        </div>

        {/* Error Display */}
        {error && (
          <div className="bg-red-50 border border-red-200 rounded-lg p-4 mb-6">
            <p className="text-red-800 font-semibold">Error</p>
            <p className="text-red-600 text-sm mt-1">{error}</p>
          </div>
        )}

        {/* Loading State */}
        {loading && !error && (
          <div className="flex justify-center py-10">
            <div className="animate-spin rounded-full h-12 w-12 border-b-2 border-primary-400"></div>
          </div>
        )}

        {/* Tabs */}
        {!loading && status && (
          <>
            <div className="border-b border-gray-200 mb-6">
              <nav className="flex gap-6" aria-label="Tabs">
                <button
                  onClick={() => setActiveTab('status')}
                  className={`pb-3 text-sm font-medium border-b-2 transition-colors ${
                    activeTab === 'status'
                      ? 'border-blue-600 text-blue-600'
                      : 'border-transparent text-gray-500 hover:text-gray-700 hover:border-gray-300'
                  }`}
                >
                  Status
                </button>
                <button
                  onClick={() => setActiveTab('trust-lists')}
                  className={`pb-3 text-sm font-medium border-b-2 transition-colors ${
                    activeTab === 'trust-lists'
                      ? 'border-blue-600 text-blue-600'
                      : 'border-transparent text-gray-500 hover:text-gray-700 hover:border-gray-300'
                  }`}
                >
                  Trust Lists
                </button>
                <button
                  onClick={() => setActiveTab('custom-tsls')}
                  className={`pb-3 text-sm font-medium border-b-2 transition-colors ${
                    activeTab === 'custom-tsls'
                      ? 'border-blue-600 text-blue-600'
                      : 'border-transparent text-gray-500 hover:text-gray-700 hover:border-gray-300'
                  }`}
                >
                  Custom TSLs
                </button>
              </nav>
            </div>

            {/* ==================== STATUS TAB ==================== */}
            {activeTab === 'status' && (
              <>
                <div className="space-y-4 mb-8">
                  <h2 className="text-lg font-semibold text-gray-800 mb-3">
                    Trust Sources
                  </h2>
                  {Object.entries(status.sources).map(([sourceKey, source]) => {
                    const label = SOURCE_LABELS[sourceKey] || {
                      name: sourceKey,
                      description: '',
                    };
                    const canToggle =
                      sourceKey === 'etsi_tl' ||
                      sourceKey === 'openid_federation';

                    return (
                      <div
                        key={sourceKey}
                        className="border border-gray-200 rounded-lg p-4 bg-gray-50"
                      >
                        <div className="flex justify-between items-start">
                          <div className="flex-1">
                            <div className="flex items-center gap-3 mb-2">
                              <h3 className="font-semibold text-gray-900">
                                {label.name}
                              </h3>
                              {canToggle && (
                                <button
                                  onClick={() =>
                                    handleToggleSource(
                                      sourceKey,
                                      source.enabled
                                    )
                                  }
                                  className={`px-3 py-1 rounded-full text-xs font-medium transition-colors ${
                                    source.enabled
                                      ? 'bg-green-100 text-green-800 hover:bg-green-200'
                                      : 'bg-gray-200 text-gray-600 hover:bg-gray-300'
                                  }`}
                                >
                                  {source.enabled ? 'Enabled' : 'Disabled'}
                                </button>
                              )}
                            </div>
                            {label.description && (
                              <p className="text-sm text-gray-600 mb-3">
                                {label.description}
                              </p>
                            )}
                            <div className="flex items-center gap-4 text-sm">
                              <div className="flex items-center gap-2">
                                <div
                                  className={`w-2 h-2 rounded-full ${source.healthy ? 'bg-green-500' : 'bg-red-500'}`}
                                />
                                <span
                                  className={
                                    source.healthy
                                      ? 'text-green-700'
                                      : 'text-red-700'
                                  }
                                >
                                  {source.healthy ? 'Healthy' : 'Unhealthy'}
                                </span>
                              </div>
                              <span className="text-gray-600">
                                {source.entryCount}{' '}
                                {source.entryCount === 1 ? 'entry' : 'entries'}
                              </span>
                              <span className="text-gray-500">
                                Updated {formatTimestamp(source.lastUpdate)}
                              </span>
                            </div>
                            {source.error && (
                              <p className="text-sm text-red-600 mt-2">
                                Error: {source.error}
                              </p>
                            )}
                          </div>
                        </div>
                      </div>
                    );
                  })}
                </div>

                {/* Test Validation Section */}
                <div className="border-t border-gray-200 pt-6">
                  <h2 className="text-lg font-semibold text-gray-800 mb-4">
                    Test Validation
                  </h2>
                  <div className="space-y-4">
                    <InputField
                      value={testDid}
                      onChange={setTestDid}
                      type="text"
                      name="issuerDid"
                      label="Issuer DID"
                      placeholder="did:key:z6Mk..."
                      showLabel={true}
                    />
                    <InputField
                      value={testType}
                      onChange={setTestType}
                      type="text"
                      name="credentialType"
                      label="Credential Type (optional)"
                      placeholder="VerifiableCredential"
                      showLabel={true}
                    />
                    <Button
                      onClick={handleTestValidation}
                      loading={testing}
                      disabled={!testDid.trim()}
                      color="primary"
                    >
                      Test Validation
                    </Button>

                    {testError && (
                      <div className="bg-red-50 border border-red-200 rounded-lg p-3">
                        <p className="text-red-800 text-sm">{testError}</p>
                      </div>
                    )}

                    {testResult && (
                      <div
                        className={`border rounded-lg p-4 ${
                          testResult.trusted
                            ? 'bg-green-50 border-green-200'
                            : 'bg-yellow-50 border-yellow-200'
                        }`}
                      >
                        <div className="flex items-start gap-3">
                          <div
                            className={`text-2xl ${testResult.trusted ? 'text-green-600' : 'text-yellow-600'}`}
                          >
                            {testResult.trusted ? '\u2713' : '\u26A0'}
                          </div>
                          <div className="flex-1">
                            <p
                              className={`font-semibold ${testResult.trusted ? 'text-green-800' : 'text-yellow-800'}`}
                            >
                              {testResult.trusted ? 'Trusted' : 'Not Trusted'}
                            </p>
                            {testResult.message && (
                              <p className="text-sm text-gray-600 mt-1">
                                {testResult.message}
                              </p>
                            )}
                            {testResult.trusted && (
                              <div className="mt-2 text-sm text-gray-700 space-y-1">
                                {testResult.source && (
                                  <p>
                                    Source:{' '}
                                    <span className="font-medium">
                                      {testResult.source}
                                    </span>
                                  </p>
                                )}
                                {testResult.provider && (
                                  <p>
                                    Provider:{' '}
                                    <span className="font-medium">
                                      {testResult.provider}
                                    </span>
                                  </p>
                                )}
                                {testResult.country && (
                                  <p>
                                    Country:{' '}
                                    <span className="font-medium">
                                      {testResult.country}
                                    </span>
                                  </p>
                                )}
                              </div>
                            )}
                          </div>
                        </div>
                      </div>
                    )}
                  </div>
                </div>
              </>
            )}

            {/* ==================== TRUST LISTS TAB ==================== */}
            {activeTab === 'trust-lists' && (
              <div>
                {/* Search bar */}
                <div className="relative mb-6">
                  <MagnifyingGlassIcon className="absolute left-3 top-1/2 -translate-y-1/2 w-4 h-4 text-gray-400" />
                  <input
                    type="text"
                    value={searchQuery}
                    onChange={(e) => onSearchInput(e.target.value)}
                    placeholder="Search providers and services..."
                    className="w-full pl-10 pr-4 py-2.5 border border-gray-300 rounded-lg text-sm focus:outline-none focus:ring-2 focus:ring-blue-500 focus:border-transparent"
                  />
                  {searching && (
                    <div className="absolute right-3 top-1/2 -translate-y-1/2">
                      <div className="animate-spin rounded-full h-4 w-4 border-b-2 border-blue-500"></div>
                    </div>
                  )}
                </div>

                {/* Search results mode */}
                {searchQuery.trim() && searchResults ? (
                  <div>
                    <p className="text-sm text-gray-500 mb-4">
                      {searchResults.total} result
                      {searchResults.total !== 1 ? 's' : ''} for &ldquo;
                      {searchResults.query}&rdquo;
                    </p>
                    {searchResults.providers.length === 0 ? (
                      <div className="text-center py-8 text-gray-400">
                        <p>No providers or services match your search.</p>
                      </div>
                    ) : (
                      <div className="space-y-3">
                        {searchResults.providers.map((provider, idx) => (
                          <SearchResultCard
                            key={`${provider.name}-${idx}`}
                            provider={provider}
                          />
                        ))}
                      </div>
                    )}
                  </div>
                ) : (
                  <>
                    {/* LOTL loading / error */}
                    {lotlLoading && (
                      <div className="flex justify-center py-10">
                        <div className="animate-spin rounded-full h-10 w-10 border-b-2 border-blue-500"></div>
                      </div>
                    )}

                    {lotlError && (
                      <div className="bg-amber-50 border border-amber-200 rounded-lg p-4 mb-6">
                        <p className="text-amber-800 text-sm">{lotlError}</p>
                      </div>
                    )}

                    {/* LOTL header */}
                    {lotl && !lotlLoading && (
                      <>
                        <div className="bg-gradient-to-r from-blue-50 to-indigo-50 border border-blue-100 rounded-lg p-5 mb-6">
                          <div className="flex items-start justify-between">
                            <div>
                              <div className="flex items-center gap-2 mb-1">
                                <span className="text-xl">{getFlag('EU')}</span>
                                <h3 className="font-semibold text-gray-900">
                                  EU List of Trusted Lists
                                </h3>
                              </div>
                              <p className="text-sm text-gray-600">
                                {lotl.schemeOperatorName}
                              </p>
                            </div>
                            <div className="text-right text-xs text-gray-500 space-y-1">
                              {lotl.sequenceNumber && (
                                <p>Seq #{lotl.sequenceNumber}</p>
                              )}
                              <p>Issued {formatDate(lotl.listIssueDate)}</p>
                              <p>Next update {formatDate(lotl.nextUpdate)}</p>
                            </div>
                          </div>
                          <div className="flex gap-4 mt-3 text-sm">
                            <span className="text-gray-600">
                              <span className="font-medium text-gray-900">
                                {lotl.memberStates.length}
                              </span>{' '}
                              member states
                            </span>
                            <span className="text-gray-600">
                              <span className="font-medium text-gray-900">
                                {lotl.memberStates.reduce(
                                  (sum, ms) => sum + ms.providerCount,
                                  0
                                )}
                              </span>{' '}
                              providers
                            </span>
                            <span className="text-gray-600">
                              <span className="font-medium text-gray-900">
                                {lotl.memberStates.reduce(
                                  (sum, ms) => sum + ms.serviceCount,
                                  0
                                )}
                              </span>{' '}
                              services
                            </span>
                          </div>
                        </div>

                        {/* Member state grid */}
                        <div className="grid grid-cols-2 sm:grid-cols-3 md:grid-cols-4 gap-3 mb-4">
                          {lotl.memberStates
                            .sort((a, b) => a.country.localeCompare(b.country))
                            .map((ms) => (
                              <button
                                key={ms.country}
                                onClick={() => handleCountryClick(ms.country)}
                                className={`text-left p-3 rounded-lg border transition-all ${
                                  expandedCountry === ms.country
                                    ? 'border-blue-300 bg-blue-50 ring-1 ring-blue-200'
                                    : 'border-gray-200 bg-white hover:border-gray-300 hover:shadow-sm'
                                }`}
                              >
                                <div className="flex items-center justify-between mb-1.5">
                                  <div className="flex items-center gap-2">
                                    <span className="text-lg">
                                      {getFlag(ms.country)}
                                    </span>
                                    <span className="font-medium text-sm text-gray-900">
                                      {ms.country}
                                    </span>
                                  </div>
                                  <div
                                    className={`w-2 h-2 rounded-full ${ms.healthy ? 'bg-emerald-500' : 'bg-red-400'}`}
                                  />
                                </div>
                                <div className="text-xs text-gray-500">
                                  {ms.providerCount} provider
                                  {ms.providerCount !== 1
                                    ? 's'
                                    : ''} &middot; {ms.serviceCount} service
                                  {ms.serviceCount !== 1 ? 's' : ''}
                                </div>
                              </button>
                            ))}
                        </div>

                        {/* Expanded country detail */}
                        {expandedCountry && (
                          <div className="border border-blue-200 rounded-lg bg-blue-50/30 p-5 mt-2 mb-4">
                            {countryLoading === expandedCountry ? (
                              <div className="flex justify-center py-6">
                                <div className="animate-spin rounded-full h-8 w-8 border-b-2 border-blue-500"></div>
                              </div>
                            ) : countryDetail[expandedCountry] ? (
                              <CountryDetailPanel
                                detail={countryDetail[expandedCountry]}
                                formatDate={formatDate}
                              />
                            ) : (
                              <p className="text-sm text-gray-500 text-center py-4">
                                Failed to load details for {expandedCountry}
                              </p>
                            )}
                          </div>
                        )}
                      </>
                    )}
                  </>
                )}
              </div>
            )}

            {/* ==================== CUSTOM TSLs TAB ==================== */}
            {activeTab === 'custom-tsls' && (
              <div>
                {/* Import Form */}
                <div className="border border-gray-200 rounded-lg p-5 bg-gray-50 mb-6">
                  <h2 className="text-lg font-semibold text-gray-800 mb-4">
                    Import Custom TSL
                  </h2>
                  <p className="text-sm text-gray-600 mb-4">
                    Add trust service lists from countries outside the EU LOTL.
                    Custom TSLs do not need to be signed.
                  </p>
                  <div className="space-y-4">
                    <div className="grid grid-cols-1 sm:grid-cols-4 gap-4">
                      <div>
                        <InputField
                          value={importCountry}
                          onChange={setImportCountry}
                          type="text"
                          name="country"
                          label="Country Code"
                          placeholder="AU"
                          showLabel={true}
                        />
                      </div>
                      <div className="sm:col-span-3">
                        <InputField
                          value={importUrl}
                          onChange={setImportUrl}
                          type="text"
                          name="tslUrl"
                          label="TSL URL"
                          placeholder="https://example.com/tsl.xml"
                          showLabel={true}
                        />
                      </div>
                    </div>
                    <Button
                      onClick={handleImportTsl}
                      loading={importing}
                      disabled={!importCountry.trim() || !importUrl.trim()}
                      color="primary"
                    >
                      Import TSL
                    </Button>

                    {importError && (
                      <div className="bg-red-50 border border-red-200 rounded-lg p-3">
                        <p className="text-red-800 text-sm">{importError}</p>
                      </div>
                    )}

                    {importSuccess && (
                      <div className="bg-green-50 border border-green-200 rounded-lg p-3">
                        <p className="text-green-800 text-sm">
                          {importSuccess}
                        </p>
                      </div>
                    )}
                  </div>
                </div>

                {/* Custom TSLs List */}
                <h2 className="text-lg font-semibold text-gray-800 mb-3">
                  Imported TSLs
                </h2>

                {customTslsError && (
                  <div className="bg-red-50 border border-red-200 rounded-lg p-4 mb-4">
                    <p className="text-red-800 text-sm">{customTslsError}</p>
                  </div>
                )}

                {customTslsLoading && (
                  <div className="flex justify-center py-10">
                    <div className="animate-spin rounded-full h-10 w-10 border-b-2 border-blue-500"></div>
                  </div>
                )}

                {!customTslsLoading &&
                  customTsls.length === 0 &&
                  !customTslsError && (
                    <div className="text-center py-8 text-gray-400">
                      <p>No custom TSLs imported yet.</p>
                      <p className="text-sm mt-1">
                        Use the form above to import a trust service list.
                      </p>
                    </div>
                  )}

                {!customTslsLoading && customTsls.length > 0 && (
                  <div className="space-y-3">
                    {customTsls.map((tsl) => (
                      <div
                        key={tsl.country}
                        className={`border rounded-lg bg-white transition-all ${
                          expandedCustomTsl === tsl.country
                            ? 'border-blue-300 ring-1 ring-blue-200'
                            : 'border-gray-200'
                        }`}
                      >
                        {/* Header row - clickable to expand */}
                        <button
                          onClick={() => handleCustomTslClick(tsl.country)}
                          className="w-full text-left p-4 flex items-center justify-between hover:bg-gray-50 transition-colors rounded-t-lg"
                        >
                          <div className="flex items-center gap-2 min-w-0">
                            {expandedCustomTsl === tsl.country ? (
                              <ChevronDownIcon className="w-4 h-4 text-gray-400 flex-shrink-0" />
                            ) : (
                              <ChevronRightIcon className="w-4 h-4 text-gray-400 flex-shrink-0" />
                            )}
                            <span className="text-lg">
                              {getFlag(tsl.country)}
                            </span>
                            <span className="font-medium text-gray-900">
                              {tsl.country}
                            </span>
                            <div
                              className={`w-2 h-2 rounded-full ${tsl.loaded ? 'bg-emerald-500' : 'bg-red-400'}`}
                            />
                            <span className="text-xs text-gray-500 ml-2">
                              {tsl.providerCount} provider
                              {tsl.providerCount !== 1 ? 's' : ''} &middot;{' '}
                              {tsl.serviceCount} service
                              {tsl.serviceCount !== 1 ? 's' : ''}
                            </span>
                          </div>
                          <div
                            className="flex items-center gap-2 flex-shrink-0 ml-3"
                            onClick={(e) => e.stopPropagation()}
                          >
                            {editingTsl === tsl.country ? (
                              <>
                                <button
                                  onClick={() => handleEditSave(tsl.country)}
                                  disabled={editSaving}
                                  className="text-blue-600 hover:text-blue-800 text-sm font-medium transition-colors disabled:opacity-50"
                                >
                                  {editSaving ? (
                                    <div className="animate-spin rounded-full h-4 w-4 border-b-2 border-blue-500"></div>
                                  ) : (
                                    'Save'
                                  )}
                                </button>
                                <button
                                  onClick={handleEditCancel}
                                  disabled={editSaving}
                                  className="text-gray-500 hover:text-gray-700 text-sm font-medium transition-colors disabled:opacity-50"
                                >
                                  Cancel
                                </button>
                              </>
                            ) : (
                              <>
                                <button
                                  onClick={() => handleEditTsl(tsl)}
                                  className="text-gray-400 hover:text-blue-600 transition-colors"
                                  title="Edit URL"
                                >
                                  <PencilIcon className="w-4 h-4" />
                                </button>
                                <button
                                  onClick={() => handleDeleteTsl(tsl.country)}
                                  disabled={deleting === tsl.country}
                                  className="text-red-500 hover:text-red-700 text-sm font-medium transition-colors disabled:opacity-50"
                                >
                                  {deleting === tsl.country ? (
                                    <div className="animate-spin rounded-full h-4 w-4 border-b-2 border-red-500"></div>
                                  ) : (
                                    'Remove'
                                  )}
                                </button>
                              </>
                            )}
                          </div>
                        </button>

                        {/* URL row - or edit input when editing */}
                        <div className="px-4 pb-3 -mt-1">
                          {editingTsl === tsl.country ? (
                            <div className="ml-6">
                              <input
                                type="text"
                                value={editUrl}
                                onChange={(e) => setEditUrl(e.target.value)}
                                className="w-full px-3 py-1.5 border border-gray-300 rounded text-sm focus:outline-none focus:ring-2 focus:ring-blue-500 focus:border-transparent"
                                placeholder="https://example.com/tsl.xml"
                                onKeyDown={(e) => {
                                  if (e.key === 'Enter')
                                    handleEditSave(tsl.country);
                                  if (e.key === 'Escape') handleEditCancel();
                                }}
                              />
                              {editError && (
                                <p className="text-xs text-red-600 mt-1">
                                  {editError}
                                </p>
                              )}
                            </div>
                          ) : (
                            <p
                              className="text-xs text-gray-500 truncate ml-6"
                              title={tsl.url}
                            >
                              {tsl.url}
                            </p>
                          )}
                        </div>

                        {/* Expanded detail panel */}
                        {expandedCustomTsl === tsl.country && (
                          <div className="border-t border-blue-200 bg-blue-50/30 p-5">
                            {customTslDetailLoading === tsl.country ? (
                              <div className="flex justify-center py-6">
                                <div className="animate-spin rounded-full h-8 w-8 border-b-2 border-blue-500"></div>
                              </div>
                            ) : customTslDetail[tsl.country] ? (
                              <CountryDetailPanel
                                detail={customTslDetail[tsl.country]}
                                formatDate={formatDate}
                              />
                            ) : (
                              <p className="text-sm text-gray-500 text-center py-4">
                                Failed to load details for {tsl.country}
                              </p>
                            )}
                          </div>
                        )}
                      </div>
                    ))}
                  </div>
                )}
              </div>
            )}
          </>
        )}

        {/* Footer */}
        <div className="flex flex-col items-center mt-8 pt-6 border-t border-gray-200">
          <div className="flex flex-row gap-2 items-center content-center text-sm text-center text-gray-500">
            <p>Secured by walt.id</p>
            <WaltIcon height={15} width={15} type="gray" />
          </div>
        </div>
      </div>
    </div>
  );
}

// -- Country Detail Panel --

function CountryDetailPanel({
  detail,
  formatDate,
}: {
  detail: CountryTslDetail;
  formatDate: (d?: string) => string;
}) {
  const [expandedProvider, setExpandedProvider] = useState<number | null>(null);

  return (
    <div>
      {/* TSL metadata */}
      <div className="flex items-start justify-between mb-4">
        <div>
          <div className="flex items-center gap-2">
            <span className="text-xl">{getFlag(detail.schemeTerritory)}</span>
            <h3 className="font-semibold text-gray-900">
              {detail.schemeTerritory} Trusted List
            </h3>
          </div>
          <p className="text-sm text-gray-600 mt-0.5">
            {detail.schemeOperatorName}
          </p>
        </div>
        <div className="text-right text-xs text-gray-500 space-y-0.5">
          {detail.sequenceNumber && <p>Seq #{detail.sequenceNumber}</p>}
          <p>Issued {formatDate(detail.listIssueDate)}</p>
          <p>Next update {formatDate(detail.nextUpdate)}</p>
        </div>
      </div>

      {/* Providers */}
      <div className="space-y-2">
        {detail.providers.map((provider, idx) => (
          <div key={idx} className="bg-white rounded-lg border border-gray-200">
            <button
              onClick={() =>
                setExpandedProvider(expandedProvider === idx ? null : idx)
              }
              className="w-full text-left p-3 flex items-center justify-between hover:bg-gray-50 transition-colors rounded-lg"
            >
              <div className="flex-1 min-w-0">
                <div className="flex items-center gap-2">
                  {expandedProvider === idx ? (
                    <ChevronDownIcon className="w-4 h-4 text-gray-400 flex-shrink-0" />
                  ) : (
                    <ChevronRightIcon className="w-4 h-4 text-gray-400 flex-shrink-0" />
                  )}
                  <span className="font-medium text-sm text-gray-900 truncate">
                    {provider.name}
                  </span>
                </div>
                {provider.tradeName && provider.tradeName !== provider.name && (
                  <p className="text-xs text-gray-500 ml-6 truncate">
                    {provider.tradeName}
                  </p>
                )}
              </div>
              <span className="text-xs text-gray-400 flex-shrink-0 ml-3">
                {provider.services.length} service
                {provider.services.length !== 1 ? 's' : ''}
              </span>
            </button>

            {expandedProvider === idx && (
              <div className="border-t border-gray-100 px-3 pb-3">
                <table className="w-full text-sm mt-2">
                  <thead>
                    <tr className="text-xs text-gray-500 uppercase tracking-wider">
                      <th className="text-left py-1.5 pr-3 font-medium">
                        Service
                      </th>
                      <th className="text-left py-1.5 pr-3 font-medium">
                        Type
                      </th>
                      <th className="text-left py-1.5 pr-3 font-medium">
                        Status
                      </th>
                      <th className="text-left py-1.5 font-medium">Since</th>
                    </tr>
                  </thead>
                  <tbody>
                    {provider.services.map((svc, svcIdx) => (
                      <React.Fragment key={svcIdx}>
                        <tr className="border-t border-gray-50">
                          <td
                            className={`pr-3 text-gray-800 max-w-[200px] truncate ${svc.x509SubjectName ? 'pt-2 pb-0' : 'py-2'}`}
                            title={svc.serviceName}
                          >
                            {svc.serviceName}
                          </td>
                          <td
                            className={`pr-3 ${svc.x509SubjectName ? 'pt-2 pb-0' : 'py-2'}`}
                          >
                            <span className="inline-flex items-center gap-1">
                              <span className="text-gray-600">
                                {svc.serviceTypeLabel}
                              </span>
                              {svc.isQualified && (
                                <span className="text-xs bg-blue-100 text-blue-700 px-1.5 py-0.5 rounded font-medium">
                                  Q
                                </span>
                              )}
                            </span>
                          </td>
                          <td
                            className={`pr-3 ${svc.x509SubjectName ? 'pt-2 pb-0' : 'py-2'}`}
                          >
                            <StatusBadge status={svc.status} />
                          </td>
                          <td
                            className={`text-xs text-gray-500 ${svc.x509SubjectName ? 'pt-2 pb-0' : 'py-2'}`}
                          >
                            {formatDate(svc.statusStartingTime)}
                          </td>
                        </tr>
                        {svc.x509SubjectName && (
                          <tr>
                            <td colSpan={4} className="pb-2 pt-0.5 pl-1">
                              <span className="text-xs text-gray-400 font-mono truncate block">
                                {svc.x509SubjectName}
                              </span>
                            </td>
                          </tr>
                        )}
                      </React.Fragment>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
          </div>
        ))}
      </div>
    </div>
  );
}

// -- Search result card --

function SearchResultCard({ provider }: { provider: ProviderDetail }) {
  const [expanded, setExpanded] = useState(false);

  return (
    <div className="border border-gray-200 rounded-lg bg-white">
      <button
        onClick={() => setExpanded(!expanded)}
        className="w-full text-left p-4 flex items-center justify-between hover:bg-gray-50 transition-colors rounded-lg"
      >
        <div className="flex-1 min-w-0">
          <div className="flex items-center gap-2">
            {expanded ? (
              <ChevronDownIcon className="w-4 h-4 text-gray-400 flex-shrink-0" />
            ) : (
              <ChevronRightIcon className="w-4 h-4 text-gray-400 flex-shrink-0" />
            )}
            {provider.country && (
              <span className="text-base">{getFlag(provider.country)}</span>
            )}
            <span className="font-medium text-sm text-gray-900 truncate">
              {provider.name}
            </span>
          </div>
          {provider.tradeName && provider.tradeName !== provider.name && (
            <p className="text-xs text-gray-500 ml-6">{provider.tradeName}</p>
          )}
        </div>
        <div className="flex items-center gap-2 flex-shrink-0 ml-3">
          {provider.country && (
            <span className="text-xs text-gray-400">{provider.country}</span>
          )}
          <span className="text-xs text-gray-400">
            {provider.services.length} service
            {provider.services.length !== 1 ? 's' : ''}
          </span>
        </div>
      </button>

      {expanded && provider.services.length > 0 && (
        <div className="border-t border-gray-100 px-4 pb-3">
          <div className="space-y-2 mt-2">
            {provider.services.map((svc, idx) => (
              <div key={idx}>
                <div className="flex items-center justify-between text-sm py-1">
                  <div className="flex items-center gap-2 min-w-0 flex-1">
                    <span className="text-gray-800 truncate">
                      {svc.serviceName}
                    </span>
                    {svc.isQualified && (
                      <span className="text-xs bg-blue-100 text-blue-700 px-1.5 py-0.5 rounded font-medium flex-shrink-0">
                        Q
                      </span>
                    )}
                  </div>
                  <div className="flex items-center gap-3 flex-shrink-0 ml-3">
                    <span className="text-xs text-gray-500">
                      {svc.serviceTypeLabel}
                    </span>
                    <StatusBadge status={svc.status} />
                  </div>
                </div>
                {svc.x509SubjectName && (
                  <p className="text-xs text-gray-400 font-mono truncate pl-1 -mt-0.5">
                    {svc.x509SubjectName}
                  </p>
                )}
              </div>
            ))}
          </div>
        </div>
      )}
    </div>
  );
}
