import React, { useState, useEffect, useContext } from 'react';
import axios from 'axios';
import { useRouter } from 'next/router';
import { EnvContext } from '@/pages/_app';
import Button from '@/components/walt/button/Button';
import WaltIcon from '@/components/walt/logo/WaltIcon';
import {
  ArrowPathIcon,
  ChevronDownIcon,
  ChevronRightIcon,
  ListBulletIcon,
  TrashIcon,
  ArrowLeftIcon,
  NoSymbolIcon,
  CheckCircleIcon,
  GlobeAltIcon,
  FunnelIcon,
} from '@heroicons/react/24/outline';
import AdminNav from '@/components/walt/nav/AdminNav';

// -- Interfaces --

interface StatusListSummary {
  id: string;
  tenantId: string | null;
  purpose: string;
  listSize: number;
  nextAvailableIndex: number;
  revokedCount: number;
  totalIssued: number;
  credentialTypes: string[];
  createdAt: string;
  updatedAt: string;
}

interface StatusListEntry {
  index: number;
  listId: string | null;
  credentialId: string | null;
  credentialType: string | null;
  subjectDid: string | null;
  issuerDid: string | null;
  issuerName: string | null;
  country: string | null;
  issuedAt: string | null;
  revoked: boolean;
  revokedAt: string | null;
  revokedReason: string | null;
}

interface GlobalEntry {
  listId: string;
  entry: StatusListEntry;
}

interface DimensionStats {
  issued: number;
  revoked: number;
}

interface IssuerDimensionStats {
  name: string | null;
  issued: number;
  revoked: number;
}

interface StatsResponse {
  totalLists: number;
  totalIssued: number;
  totalRevoked: number;
  byCountry: Record<string, DimensionStats>;
  byIssuer: Record<string, IssuerDimensionStats>;
  byCredentialType: Record<string, DimensionStats>;
}

// -- Status Badge --

function StatusBadge({ revoked }: { revoked: boolean }) {
  if (revoked) {
    return (
      <span className="inline-flex items-center gap-1 px-2 py-0.5 rounded-full text-xs font-medium bg-red-100 text-red-700">
        <NoSymbolIcon className="w-3 h-3" />
        Revoked
      </span>
    );
  }
  return (
    <span className="inline-flex items-center gap-1 px-2 py-0.5 rounded-full text-xs font-medium bg-green-100 text-green-700">
      <CheckCircleIcon className="w-3 h-3" />
      Active
    </span>
  );
}

function PurposeBadge({ purpose }: { purpose: string }) {
  return (
    <span className="px-2 py-0.5 bg-purple-100 text-purple-700 rounded-full text-xs font-medium">
      {purpose}
    </span>
  );
}

// -- Main Component --

export default function StatusLists() {
  const env = useContext(EnvContext);
  const router = useRouter();

  // View state
  const [view, setView] = useState<'lists' | 'entries' | 'all-credentials'>('lists');
  const [selectedListId, setSelectedListId] = useState<string | null>(null);
  const [expandedList, setExpandedList] = useState<string | null>(null);

  // List state
  const [statusLists, setStatusLists] = useState<StatusListSummary[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  // Create form state
  const [showCreateForm, setShowCreateForm] = useState(false);
  const [createPurpose, setCreatePurpose] = useState('revocation');
  const [createCredentialTypes, setCreateCredentialTypes] = useState('');
  const [creating, setCreating] = useState(false);

  // Entries state (per-list)
  const [entries, setEntries] = useState<StatusListEntry[]>([]);
  const [entriesLoading, setEntriesLoading] = useState(false);
  const [entriesPage, setEntriesPage] = useState(1);
  const [entriesTotalCount, setEntriesTotalCount] = useState(0);
  const [entriesFilter, setEntriesFilter] = useState<'all' | 'revoked' | 'active'>('all');

  // All Credentials state
  const [globalEntries, setGlobalEntries] = useState<GlobalEntry[]>([]);
  const [globalLoading, setGlobalLoading] = useState(false);
  const [globalPage, setGlobalPage] = useState(1);
  const [globalTotalCount, setGlobalTotalCount] = useState(0);
  const [globalCountryFilter, setGlobalCountryFilter] = useState('');
  const [globalIssuerFilter, setGlobalIssuerFilter] = useState('');
  const [globalTypeFilter, setGlobalTypeFilter] = useState('');
  const [globalStatusFilter, setGlobalStatusFilter] = useState('');
  const [stats, setStats] = useState<StatsResponse | null>(null);
  const [bulkActionLoading, setBulkActionLoading] = useState(false);

  // Action state
  const [deletingList, setDeletingList] = useState<string | null>(null);
  const [actionError, setActionError] = useState<string | null>(null);

  // Bulk revoke (per-list view)
  const [selectedIndices, setSelectedIndices] = useState<Set<number>>(new Set());

  const apiBase = '/api/proxy/issuer';
  const featureEnabled = env.NEXT_PUBLIC_STATUS_LISTS_ENABLED === 'true';
  const pageSize = 50;

  // -- Fetch lists --

  const fetchStatusLists = async () => {
    try {
      setLoading(true);
      const response = await axios.get<StatusListSummary[]>(`${apiBase}/admin/status-lists`);
      setStatusLists(response.data);
      setError(null);
    } catch (e: any) {
      if (e.response?.status === 503) {
        setError('Status Lists feature is not enabled. Set STATUS_LISTS_ENABLED=true to enable.');
      } else {
        setError(e.response?.data?.error || e.message || 'Failed to fetch status lists');
      }
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    if (apiBase && featureEnabled) {
      fetchStatusLists();
    } else {
      setLoading(false);
    }
  }, [apiBase, featureEnabled]);

  // -- Fetch entries (per-list) --

  const fetchEntries = async (listId: string, page: number = 1) => {
    try {
      setEntriesLoading(true);
      const params: Record<string, string> = { page: String(page), size: String(pageSize) };
      if (entriesFilter === 'revoked') params.revoked = 'true';
      if (entriesFilter === 'active') params.revoked = 'false';

      const response = await axios.get<StatusListEntry[]>(
        `${apiBase}/admin/status-lists/${listId}/entries`,
        { params }
      );
      setEntries(response.data);
      setEntriesTotalCount(parseInt(response.headers['x-total-count'] || '0', 10));
      setEntriesPage(page);
    } catch (e: any) {
      setActionError(e.response?.data?.error || e.message || 'Failed to fetch entries');
    } finally {
      setEntriesLoading(false);
    }
  };

  useEffect(() => {
    if (view === 'entries' && selectedListId) {
      fetchEntries(selectedListId, 1);
    }
  }, [view, selectedListId, entriesFilter]);

  // -- Fetch global entries (All Credentials) --

  const fetchGlobalEntries = async (page: number = 1) => {
    try {
      setGlobalLoading(true);
      const params: Record<string, string> = { page: String(page), size: String(pageSize) };
      if (globalCountryFilter) params.country = globalCountryFilter;
      if (globalIssuerFilter) params.issuerDid = globalIssuerFilter;
      if (globalTypeFilter) params.credentialType = globalTypeFilter;
      if (globalStatusFilter === 'revoked') params.revoked = 'true';
      if (globalStatusFilter === 'active') params.revoked = 'false';

      const response = await axios.get<GlobalEntry[]>(
        `${apiBase}/admin/status-lists/entries/search`,
        { params }
      );
      setGlobalEntries(response.data);
      setGlobalTotalCount(parseInt(response.headers['x-total-count'] || '0', 10));
      setGlobalPage(page);
    } catch (e: any) {
      setActionError(e.response?.data?.error || e.message || 'Failed to fetch credentials');
    } finally {
      setGlobalLoading(false);
    }
  };

  const fetchStats = async () => {
    try {
      const response = await axios.get<StatsResponse>(`${apiBase}/admin/status-lists/stats`);
      setStats(response.data);
    } catch {
      // stats are optional — don't block the view
    }
  };

  useEffect(() => {
    if (view === 'all-credentials') {
      fetchGlobalEntries(1);
      fetchStats();
    }
  }, [view, globalCountryFilter, globalIssuerFilter, globalTypeFilter, globalStatusFilter]);

  // -- Handlers --

  const handleCreate = async () => {
    try {
      setCreating(true);
      setActionError(null);
      const body: any = { purpose: createPurpose };
      if (createCredentialTypes.trim()) {
        body.credentialTypes = createCredentialTypes.split(',').map((s: string) => s.trim()).filter(Boolean);
      }
      await axios.post(`${apiBase}/admin/status-lists`, body);
      setShowCreateForm(false);
      setCreatePurpose('revocation');
      setCreateCredentialTypes('');
      fetchStatusLists();
    } catch (e: any) {
      setActionError(e.response?.data?.error || e.message || 'Failed to create status list');
    } finally {
      setCreating(false);
    }
  };

  const handleDelete = async (listId: string) => {
    if (!confirm('Are you sure you want to delete this status list? This cannot be undone.')) return;
    try {
      setDeletingList(listId);
      await axios.delete(`${apiBase}/admin/status-lists/${listId}`);
      fetchStatusLists();
    } catch (e: any) {
      setActionError(e.response?.data?.error || e.message || 'Failed to delete status list');
    } finally {
      setDeletingList(null);
    }
  };

  const handleRevoke = async (listId: string, index: number) => {
    const reason = prompt('Revocation reason (optional):');
    try {
      setActionError(null);
      await axios.put(`${apiBase}/admin/status-lists/${listId}/entries/${index}/revoke`, { reason });
      if (view === 'entries' && selectedListId) fetchEntries(selectedListId, entriesPage);
      if (view === 'all-credentials') fetchGlobalEntries(globalPage);
      fetchStatusLists();
    } catch (e: any) {
      setActionError(e.response?.data?.error || e.message || 'Failed to revoke entry');
    }
  };

  const handleUnrevoke = async (listId: string, index: number) => {
    try {
      setActionError(null);
      await axios.put(`${apiBase}/admin/status-lists/${listId}/entries/${index}/unrevoke`);
      if (view === 'entries' && selectedListId) fetchEntries(selectedListId, entriesPage);
      if (view === 'all-credentials') fetchGlobalEntries(globalPage);
      fetchStatusLists();
    } catch (e: any) {
      setActionError(e.response?.data?.error || e.message || 'Failed to unrevoke entry');
    }
  };

  const handleBulkRevoke = async () => {
    if (!selectedListId || selectedIndices.size === 0) return;
    const reason = prompt('Bulk revocation reason (optional):');
    try {
      setActionError(null);
      await axios.post(`${apiBase}/admin/status-lists/${selectedListId}/bulk-revoke`, {
        indices: Array.from(selectedIndices),
        reason,
      });
      setSelectedIndices(new Set());
      fetchEntries(selectedListId, entriesPage);
      fetchStatusLists();
    } catch (e: any) {
      setActionError(e.response?.data?.error || e.message || 'Failed to bulk revoke');
    }
  };

  const handleGlobalBulkAction = async (action: 'revoke' | 'unrevoke') => {
    const reason = action === 'revoke' ? prompt('Bulk revocation reason (optional):') : null;
    try {
      setBulkActionLoading(true);
      setActionError(null);
      const filter: Record<string, any> = {};
      if (globalCountryFilter) filter.country = globalCountryFilter;
      if (globalIssuerFilter) filter.issuerDid = globalIssuerFilter;
      if (globalTypeFilter) filter.credentialType = globalTypeFilter;
      if (globalStatusFilter === 'revoked') filter.revoked = true;
      if (globalStatusFilter === 'active') filter.revoked = false;

      const response = await axios.post(`${apiBase}/admin/status-lists/bulk-action`, {
        action,
        reason,
        filter,
      });
      const affected = response.data?.affected || 0;
      alert(`${action === 'revoke' ? 'Revoked' : 'Unrevoked'} ${affected} credential(s).`);
      fetchGlobalEntries(globalPage);
      fetchStats();
      fetchStatusLists();
    } catch (e: any) {
      setActionError(e.response?.data?.error || e.message || 'Bulk action failed');
    } finally {
      setBulkActionLoading(false);
    }
  };

  const toggleSelect = (index: number) => {
    setSelectedIndices((prev) => {
      const next = new Set(prev);
      if (next.has(index)) next.delete(index);
      else next.add(index);
      return next;
    });
  };

  const viewEntries = (listId: string) => {
    setSelectedListId(listId);
    setView('entries');
    setEntriesFilter('all');
    setSelectedIndices(new Set());
  };

  const formatDate = (dateStr: string) => {
    try {
      return new Date(dateStr).toLocaleString();
    } catch {
      return dateStr;
    }
  };

  const truncate = (s: string | null, len: number = 24) => {
    if (!s) return '\u2014';
    return s.length > len ? s.slice(0, len) + '...' : s;
  };

  const totalPages = Math.ceil(entriesTotalCount / pageSize);
  const globalTotalPages = Math.ceil(globalTotalCount / pageSize);

  // -- Render --

  if (!featureEnabled) {
    return (
      <div className="flex flex-col justify-center items-center bg-gray-50 min-h-screen">
        <div className="my-5 flex flex-row items-center gap-4">
          <div className="cursor-pointer" onClick={() => router.push('/')}>
            <WaltIcon height={35} width={35} type="primary" />
          </div>
          <AdminNav />
        </div>
        <div className="w-11/12 md:w-9/12 lg:w-8/12 shadow-2xl rounded-lg mt-5 pt-8 pb-8 px-10 bg-white max-w-[1100px]">
          <div className="text-center py-12">
            <ListBulletIcon className="w-12 h-12 mx-auto text-gray-300 mb-4" />
            <h2 className="text-xl font-semibold text-gray-700 mb-2">Status Lists Disabled</h2>
            <p className="text-sm text-gray-500">
              Set <code className="bg-gray-100 px-1.5 py-0.5 rounded text-xs">STATUS_LISTS_ENABLED=true</code> in your
              issuer configuration to enable StatusList2021 revocation management.
            </p>
          </div>
        </div>
        <div className="flex flex-col items-center mt-8 pt-6">
          <div className="flex flex-row gap-2 items-center content-center text-sm text-center text-gray-500">
            <p>Secured by walt.id</p>
            <WaltIcon height={15} width={15} type="gray" />
          </div>
        </div>
      </div>
    );
  }

  // -- Tab bar --
  const TabBar = () => (
    <div className="flex items-center gap-1 mb-6 border-b border-gray-200">
      {([
        { key: 'lists', label: 'Status Lists', icon: ListBulletIcon },
        { key: 'all-credentials', label: 'All Credentials', icon: GlobeAltIcon },
      ] as const).map(({ key, label, icon: Icon }) => (
        <button
          key={key}
          onClick={() => {
            setView(key);
            setSelectedListId(null);
            setActionError(null);
          }}
          className={`flex items-center gap-1.5 px-4 py-2.5 text-sm font-medium border-b-2 transition-colors -mb-px ${
            view === key || (key === 'lists' && view === 'entries')
              ? 'border-blue-600 text-blue-600'
              : 'border-transparent text-gray-500 hover:text-gray-700 hover:border-gray-300'
          }`}
        >
          <Icon className="w-4 h-4" />
          {label}
        </button>
      ))}
    </div>
  );

  return (
    <div className="flex flex-col justify-center items-center bg-gray-50 min-h-screen">
      <div className="my-5 flex flex-row items-center gap-4">
        <div className="cursor-pointer" onClick={() => router.push('/')}>
          <WaltIcon height={35} width={35} type="primary" />
        </div>
        <AdminNav />
      </div>

      <div className="w-11/12 md:w-9/12 lg:w-8/12 shadow-2xl rounded-lg mt-5 pt-8 pb-8 px-10 bg-white max-w-[1100px]">
        <TabBar />

        {/* Error banner */}
        {(error || actionError) && (
          <div className="bg-red-50 border border-red-200 rounded-lg p-4 mb-4">
            <p className="text-red-800 text-sm">{error || actionError}</p>
            {actionError && (
              <button onClick={() => setActionError(null)} className="text-xs text-red-500 mt-1 underline">
                Dismiss
              </button>
            )}
          </div>
        )}

        {/* ── Status Lists Tab ── */}
        {(view === 'lists' || view === 'entries') && view === 'lists' && (
          <>
            <div className="flex flex-row justify-between items-center mb-6">
              <div>
                <h1 className="text-2xl font-bold text-gray-900">Status Lists</h1>
                <p className="text-sm text-gray-500 mt-1">
                  StatusList2021 credential revocation management
                </p>
              </div>
              <div className="flex items-center gap-2">
                <Button onClick={fetchStatusLists} loading={loading} size="sm" color="secondary">
                  <div className="flex items-center gap-2">
                    <ArrowPathIcon className="w-4 h-4" />
                    Refresh
                  </div>
                </Button>
              </div>
            </div>

            {/* Create Form */}
            {showCreateForm ? (
              <div className="mb-6 p-4 border border-blue-200 rounded-lg bg-blue-50/50">
                <h3 className="text-sm font-semibold text-gray-800 mb-3">Create Status List</h3>
                <div className="space-y-3">
                  <div>
                    <label className="block text-xs font-medium text-gray-600 mb-1">Purpose</label>
                    <select
                      value={createPurpose}
                      onChange={(e) => setCreatePurpose(e.target.value)}
                      className="w-full px-3 py-2 border border-gray-300 rounded-lg text-sm focus:outline-none focus:ring-2 focus:ring-blue-500"
                    >
                      <option value="revocation">Revocation</option>
                      <option value="suspension">Suspension</option>
                    </select>
                  </div>
                  <div>
                    <label className="block text-xs font-medium text-gray-600 mb-1">
                      Credential Types (comma-separated, optional)
                    </label>
                    <input
                      type="text"
                      value={createCredentialTypes}
                      onChange={(e) => setCreateCredentialTypes(e.target.value)}
                      placeholder="e.g. VerifiableId, DriverLicense"
                      className="w-full px-3 py-2 border border-gray-300 rounded-lg text-sm focus:outline-none focus:ring-2 focus:ring-blue-500"
                    />
                  </div>
                  <div className="flex gap-2">
                    <Button onClick={handleCreate} loading={creating} size="sm" color="primary">
                      Create
                    </Button>
                    <button
                      onClick={() => setShowCreateForm(false)}
                      className="px-3 py-1.5 text-sm text-gray-600 hover:bg-gray-100 rounded-md transition-colors"
                    >
                      Cancel
                    </button>
                  </div>
                </div>
              </div>
            ) : (
              <div className="mb-4">
                <button
                  onClick={() => setShowCreateForm(true)}
                  className="inline-flex items-center gap-1.5 px-3 py-1.5 text-sm font-medium text-blue-600 border border-blue-200 rounded-lg hover:bg-blue-50 transition-colors"
                >
                  <ListBulletIcon className="w-4 h-4" />
                  Create Status List
                </button>
              </div>
            )}

            {/* Status List Cards */}
            {loading ? (
              <div className="flex justify-center py-12">
                <div className="animate-spin rounded-full h-8 w-8 border-b-2 border-blue-600"></div>
              </div>
            ) : statusLists.length === 0 && !error ? (
              <div className="text-center py-12">
                <ListBulletIcon className="w-12 h-12 mx-auto text-gray-300 mb-4" />
                <p className="text-sm text-gray-500">No status lists created yet.</p>
              </div>
            ) : (
              <div className="space-y-2">
                {statusLists.map((list) => {
                  const isExpanded = expandedList === list.id;
                  return (
                    <div key={list.id} className="border border-gray-200 rounded-lg overflow-hidden">
                      <button
                        onClick={() => setExpandedList(isExpanded ? null : list.id)}
                        className="w-full flex items-center justify-between px-4 py-3 hover:bg-gray-50 transition-colors text-left"
                      >
                        <div className="flex items-center gap-3">
                          {isExpanded ? (
                            <ChevronDownIcon className="w-4 h-4 text-gray-400" />
                          ) : (
                            <ChevronRightIcon className="w-4 h-4 text-gray-400" />
                          )}
                          <PurposeBadge purpose={list.purpose} />
                          <span className="text-sm text-gray-700">
                            {list.totalIssued} issued / {list.revokedCount} revoked
                          </span>
                          {list.credentialTypes.length > 0 && (
                            <div className="flex gap-1">
                              {list.credentialTypes.map((t) => (
                                <span
                                  key={t}
                                  className="px-1.5 py-0.5 bg-gray-100 text-gray-600 rounded text-xs"
                                >
                                  {t}
                                </span>
                              ))}
                            </div>
                          )}
                        </div>
                        <span className="text-xs text-gray-400 font-mono">{truncate(list.id, 8)}</span>
                      </button>

                      {isExpanded && (
                        <div className="px-4 pb-4 pt-2 border-t border-gray-100 bg-gray-50/50">
                          <div className="grid grid-cols-2 sm:grid-cols-3 gap-x-4 gap-y-2 mb-4">
                            <InfoRow label="ID" value={list.id} mono />
                            <InfoRow label="Purpose" value={list.purpose} />
                            <InfoRow label="List Size" value={list.listSize.toLocaleString()} />
                            <InfoRow label="Next Index" value={list.nextAvailableIndex.toString()} />
                            <InfoRow label="Total Issued" value={list.totalIssued.toString()} />
                            <InfoRow label="Revoked Count" value={list.revokedCount.toString()} />
                            <InfoRow label="Created" value={formatDate(list.createdAt)} />
                            <InfoRow label="Updated" value={formatDate(list.updatedAt)} />
                          </div>
                          <div className="flex items-center gap-3 pt-3 border-t border-gray-200">
                            <button
                              onClick={() => viewEntries(list.id)}
                              className="text-sm text-blue-600 hover:text-blue-800 font-medium transition-colors"
                            >
                              View Entries
                            </button>
                            <button
                              onClick={() => handleDelete(list.id)}
                              disabled={deletingList === list.id}
                              className="inline-flex items-center gap-1 text-sm text-red-500 hover:text-red-700 font-medium transition-colors disabled:opacity-50"
                            >
                              {deletingList === list.id ? (
                                <div className="animate-spin rounded-full h-3 w-3 border-b-2 border-red-500"></div>
                              ) : (
                                <TrashIcon className="w-3.5 h-3.5" />
                              )}
                              Delete
                            </button>
                          </div>
                        </div>
                      )}
                    </div>
                  );
                })}
              </div>
            )}
          </>
        )}

        {/* ── Per-List Entries View ── */}
        {view === 'entries' && (
          <>
            <div className="flex items-center gap-3 mb-6">
              <button
                onClick={() => {
                  setView('lists');
                  setSelectedListId(null);
                }}
                className="flex items-center gap-1 text-sm text-gray-500 hover:text-gray-700 transition-colors"
              >
                <ArrowLeftIcon className="w-4 h-4" />
                Back
              </button>
              <h2 className="text-lg font-bold text-gray-900">Entries</h2>
              <span className="font-mono text-xs text-gray-400">{truncate(selectedListId, 12)}</span>
              {statusLists.find((l) => l.id === selectedListId) && (
                <PurposeBadge purpose={statusLists.find((l) => l.id === selectedListId)!.purpose} />
              )}
            </div>

            {/* Filters */}
            <div className="flex items-center justify-between mb-4">
              <div className="flex items-center gap-2">
                {(['all', 'revoked', 'active'] as const).map((f) => (
                  <button
                    key={f}
                    onClick={() => setEntriesFilter(f)}
                    className={`px-3 py-1 text-xs font-medium rounded-full transition-colors ${
                      entriesFilter === f
                        ? 'bg-blue-100 text-blue-700'
                        : 'bg-gray-100 text-gray-600 hover:bg-gray-200'
                    }`}
                  >
                    {f.charAt(0).toUpperCase() + f.slice(1)}
                  </button>
                ))}
              </div>
              {selectedIndices.size > 0 && (
                <button
                  onClick={handleBulkRevoke}
                  className="inline-flex items-center gap-1.5 px-3 py-1.5 text-xs font-medium text-red-600 border border-red-200 rounded-lg hover:bg-red-50 transition-colors"
                >
                  <NoSymbolIcon className="w-3.5 h-3.5" />
                  Revoke Selected ({selectedIndices.size})
                </button>
              )}
            </div>

            {/* Entries Table */}
            {entriesLoading ? (
              <div className="flex justify-center py-12">
                <div className="animate-spin rounded-full h-8 w-8 border-b-2 border-blue-600"></div>
              </div>
            ) : entries.length === 0 ? (
              <div className="text-center py-12">
                <p className="text-sm text-gray-500">No entries found.</p>
              </div>
            ) : (
              <div className="overflow-x-auto">
                <table className="w-full text-sm">
                  <thead>
                    <tr className="border-b border-gray-200 text-left">
                      <th className="pb-2 pr-2 w-8">
                        <input
                          type="checkbox"
                          className="rounded border-gray-300"
                          checked={selectedIndices.size === entries.length && entries.length > 0}
                          onChange={(e) => {
                            if (e.target.checked) {
                              setSelectedIndices(new Set(entries.map((en) => en.index)));
                            } else {
                              setSelectedIndices(new Set());
                            }
                          }}
                        />
                      </th>
                      <th className="pb-2 pr-3 text-xs font-medium text-gray-500 uppercase">Index</th>
                      <th className="pb-2 pr-3 text-xs font-medium text-gray-500 uppercase">Type</th>
                      <th className="pb-2 pr-3 text-xs font-medium text-gray-500 uppercase">Issuer</th>
                      <th className="pb-2 pr-3 text-xs font-medium text-gray-500 uppercase">Country</th>
                      <th className="pb-2 pr-3 text-xs font-medium text-gray-500 uppercase">Issued</th>
                      <th className="pb-2 pr-3 text-xs font-medium text-gray-500 uppercase">Status</th>
                      <th className="pb-2 text-xs font-medium text-gray-500 uppercase">Actions</th>
                    </tr>
                  </thead>
                  <tbody>
                    {entries.map((entry) => (
                      <tr key={entry.index} className="border-b border-gray-100 hover:bg-gray-50">
                        <td className="py-2 pr-2">
                          <input
                            type="checkbox"
                            className="rounded border-gray-300"
                            checked={selectedIndices.has(entry.index)}
                            onChange={() => toggleSelect(entry.index)}
                          />
                        </td>
                        <td className="py-2 pr-3 font-mono text-xs">{entry.index}</td>
                        <td className="py-2 pr-3">
                          {entry.credentialType && (
                            <span className="px-1.5 py-0.5 bg-gray-100 text-gray-600 rounded text-xs">
                              {entry.credentialType}
                            </span>
                          )}
                        </td>
                        <td className="py-2 pr-3 text-xs" title={entry.issuerDid || undefined}>
                          {entry.issuerName || truncate(entry.issuerDid)}
                        </td>
                        <td className="py-2 pr-3 text-xs">{entry.country || '\u2014'}</td>
                        <td className="py-2 pr-3 text-xs text-gray-500">
                          {entry.issuedAt ? formatDate(entry.issuedAt) : '\u2014'}
                        </td>
                        <td className="py-2 pr-3">
                          <StatusBadge revoked={entry.revoked} />
                        </td>
                        <td className="py-2">
                          {entry.revoked ? (
                            <span className="text-xs text-gray-400 font-medium">Permanent</span>
                          ) : (
                            <button
                              onClick={() => handleRevoke(selectedListId!, entry.index)}
                              className="text-xs text-red-600 hover:text-red-800 font-medium transition-colors"
                            >
                              Revoke
                            </button>
                          )}
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}

            {/* Pagination */}
            {totalPages > 1 && (
              <div className="flex items-center justify-between mt-4 pt-3 border-t border-gray-200">
                <button
                  onClick={() => selectedListId && fetchEntries(selectedListId, entriesPage - 1)}
                  disabled={entriesPage <= 1}
                  className="text-sm text-gray-600 hover:text-gray-800 disabled:text-gray-300 disabled:cursor-not-allowed transition-colors"
                >
                  Previous
                </button>
                <span className="text-xs text-gray-500">
                  Page {entriesPage} of {totalPages} ({entriesTotalCount} total)
                </span>
                <button
                  onClick={() => selectedListId && fetchEntries(selectedListId, entriesPage + 1)}
                  disabled={entriesPage >= totalPages}
                  className="text-sm text-gray-600 hover:text-gray-800 disabled:text-gray-300 disabled:cursor-not-allowed transition-colors"
                >
                  Next
                </button>
              </div>
            )}
          </>
        )}

        {/* ── All Credentials Tab ── */}
        {view === 'all-credentials' && (
          <>
            <div className="flex flex-row justify-between items-center mb-4">
              <div>
                <h1 className="text-2xl font-bold text-gray-900">All Credentials</h1>
                <p className="text-sm text-gray-500 mt-1">
                  Cross-list credential search and bulk operations
                </p>
              </div>
              <div className="flex items-center gap-2">
                <Button onClick={() => fetchGlobalEntries(globalPage)} loading={globalLoading} size="sm" color="secondary">
                  <div className="flex items-center gap-2">
                    <ArrowPathIcon className="w-4 h-4" />
                    Refresh
                  </div>
                </Button>
              </div>
            </div>

            {/* Stats row */}
            {stats && (
              <div className="grid grid-cols-3 gap-4 mb-4">
                <div className="bg-blue-50 rounded-lg p-3 text-center">
                  <div className="text-2xl font-bold text-blue-700">{stats.totalIssued}</div>
                  <div className="text-xs text-blue-600">Total Issued</div>
                </div>
                <div className="bg-red-50 rounded-lg p-3 text-center">
                  <div className="text-2xl font-bold text-red-700">{stats.totalRevoked}</div>
                  <div className="text-xs text-red-600">Total Revoked</div>
                </div>
                <div className="bg-gray-50 rounded-lg p-3 text-center">
                  <div className="text-2xl font-bold text-gray-700">{stats.totalLists}</div>
                  <div className="text-xs text-gray-600">Status Lists</div>
                </div>
              </div>
            )}

            {/* Filter bar */}
            <div className="flex items-center gap-3 mb-4 p-3 bg-gray-50 rounded-lg border border-gray-200">
              <FunnelIcon className="w-4 h-4 text-gray-400 flex-shrink-0" />
              <select
                value={globalCountryFilter}
                onChange={(e) => setGlobalCountryFilter(e.target.value)}
                className="px-2 py-1.5 border border-gray-300 rounded-md text-xs bg-white focus:outline-none focus:ring-2 focus:ring-blue-500"
              >
                <option value="">All Countries</option>
                {stats && Object.keys(stats.byCountry).sort().map((c) => (
                  <option key={c} value={c}>{c} ({stats.byCountry[c].issued})</option>
                ))}
              </select>
              <select
                value={globalIssuerFilter}
                onChange={(e) => setGlobalIssuerFilter(e.target.value)}
                className="px-2 py-1.5 border border-gray-300 rounded-md text-xs bg-white focus:outline-none focus:ring-2 focus:ring-blue-500 max-w-[200px]"
              >
                <option value="">All Issuers</option>
                {stats && Object.entries(stats.byIssuer).map(([did, s]) => (
                  <option key={did} value={did}>{s.name || truncate(did, 20)} ({s.issued})</option>
                ))}
              </select>
              <select
                value={globalTypeFilter}
                onChange={(e) => setGlobalTypeFilter(e.target.value)}
                className="px-2 py-1.5 border border-gray-300 rounded-md text-xs bg-white focus:outline-none focus:ring-2 focus:ring-blue-500"
              >
                <option value="">All Types</option>
                {stats && Object.keys(stats.byCredentialType).sort().map((t) => (
                  <option key={t} value={t}>{t} ({stats.byCredentialType[t].issued})</option>
                ))}
              </select>
              <select
                value={globalStatusFilter}
                onChange={(e) => setGlobalStatusFilter(e.target.value)}
                className="px-2 py-1.5 border border-gray-300 rounded-md text-xs bg-white focus:outline-none focus:ring-2 focus:ring-blue-500"
              >
                <option value="">All Statuses</option>
                <option value="active">Active</option>
                <option value="revoked">Revoked</option>
              </select>
              {(globalCountryFilter || globalIssuerFilter || globalTypeFilter || globalStatusFilter) && (
                <button
                  onClick={() => {
                    setGlobalCountryFilter('');
                    setGlobalIssuerFilter('');
                    setGlobalTypeFilter('');
                    setGlobalStatusFilter('');
                  }}
                  className="text-xs text-gray-500 hover:text-gray-700 underline"
                >
                  Clear
                </button>
              )}
            </div>

            {/* Bulk action bar */}
            {(globalCountryFilter || globalIssuerFilter || globalTypeFilter || globalStatusFilter) && (
              <div className="flex items-center gap-2 mb-4">
                <span className="text-xs text-gray-500">
                  {globalTotalCount} matching credential(s)
                </span>
                <button
                  onClick={() => handleGlobalBulkAction('revoke')}
                  disabled={bulkActionLoading}
                  className="inline-flex items-center gap-1 px-2.5 py-1 text-xs font-medium text-red-600 border border-red-200 rounded-md hover:bg-red-50 transition-colors disabled:opacity-50"
                >
                  <NoSymbolIcon className="w-3 h-3" />
                  Revoke All Matching
                </button>
              </div>
            )}

            {/* Global entries table */}
            {globalLoading ? (
              <div className="flex justify-center py-12">
                <div className="animate-spin rounded-full h-8 w-8 border-b-2 border-blue-600"></div>
              </div>
            ) : globalEntries.length === 0 ? (
              <div className="text-center py-12">
                <GlobeAltIcon className="w-12 h-12 mx-auto text-gray-300 mb-4" />
                <p className="text-sm text-gray-500">No credentials found.</p>
              </div>
            ) : (
              <div className="overflow-x-auto">
                <table className="w-full text-sm">
                  <thead>
                    <tr className="border-b border-gray-200 text-left">
                      <th className="pb-2 pr-3 text-xs font-medium text-gray-500 uppercase">Index</th>
                      <th className="pb-2 pr-3 text-xs font-medium text-gray-500 uppercase">Type</th>
                      <th className="pb-2 pr-3 text-xs font-medium text-gray-500 uppercase">Issuer</th>
                      <th className="pb-2 pr-3 text-xs font-medium text-gray-500 uppercase">Country</th>
                      <th className="pb-2 pr-3 text-xs font-medium text-gray-500 uppercase">Issued</th>
                      <th className="pb-2 pr-3 text-xs font-medium text-gray-500 uppercase">Status</th>
                      <th className="pb-2 text-xs font-medium text-gray-500 uppercase">Actions</th>
                    </tr>
                  </thead>
                  <tbody>
                    {globalEntries.map((ge) => (
                      <tr key={`${ge.listId}-${ge.entry.index}`} className="border-b border-gray-100 hover:bg-gray-50">
                        <td className="py-2 pr-3 font-mono text-xs">{ge.entry.index}</td>
                        <td className="py-2 pr-3">
                          {ge.entry.credentialType && (
                            <span className="px-1.5 py-0.5 bg-gray-100 text-gray-600 rounded text-xs">
                              {ge.entry.credentialType}
                            </span>
                          )}
                        </td>
                        <td className="py-2 pr-3 text-xs" title={ge.entry.issuerDid || undefined}>
                          {ge.entry.issuerName || truncate(ge.entry.issuerDid)}
                        </td>
                        <td className="py-2 pr-3 text-xs">{ge.entry.country || '\u2014'}</td>
                        <td className="py-2 pr-3 text-xs text-gray-500">
                          {ge.entry.issuedAt ? formatDate(ge.entry.issuedAt) : '\u2014'}
                        </td>
                        <td className="py-2 pr-3">
                          <StatusBadge revoked={ge.entry.revoked} />
                        </td>
                        <td className="py-2">
                          {ge.entry.revoked ? (
                            <span className="text-xs text-gray-400 font-medium">Permanent</span>
                          ) : (
                            <button
                              onClick={() => handleRevoke(ge.listId, ge.entry.index)}
                              className="text-xs text-red-600 hover:text-red-800 font-medium transition-colors"
                            >
                              Revoke
                            </button>
                          )}
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}

            {/* Pagination */}
            {globalTotalPages > 1 && (
              <div className="flex items-center justify-between mt-4 pt-3 border-t border-gray-200">
                <button
                  onClick={() => fetchGlobalEntries(globalPage - 1)}
                  disabled={globalPage <= 1}
                  className="text-sm text-gray-600 hover:text-gray-800 disabled:text-gray-300 disabled:cursor-not-allowed transition-colors"
                >
                  Previous
                </button>
                <span className="text-xs text-gray-500">
                  Page {globalPage} of {globalTotalPages} ({globalTotalCount} total)
                </span>
                <button
                  onClick={() => fetchGlobalEntries(globalPage + 1)}
                  disabled={globalPage >= globalTotalPages}
                  className="text-sm text-gray-600 hover:text-gray-800 disabled:text-gray-300 disabled:cursor-not-allowed transition-colors"
                >
                  Next
                </button>
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

      <div className="mb-10" />
    </div>
  );
}

// -- Info row --

function InfoRow({ label, value, mono = false }: { label: string; value: string; mono?: boolean }) {
  return (
    <div>
      <dt className="text-xs font-medium text-gray-500 uppercase tracking-wider">{label}</dt>
      <dd className={`text-sm text-gray-900 mt-0.5 truncate ${mono ? 'font-mono text-xs' : ''}`} title={value}>
        {value}
      </dd>
    </div>
  );
}
