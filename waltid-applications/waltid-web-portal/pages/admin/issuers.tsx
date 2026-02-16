import React, { useState, useEffect, useContext } from 'react';
import axios from 'axios';
import { useRouter } from 'next/router';
import { EnvContext } from '@/pages/_app';
import Button from '@/components/walt/button/Button';
import WaltIcon from '@/components/walt/logo/WaltIcon';
import InputField from '@/components/walt/forms/Input';
import {
  ArrowPathIcon,
  ChevronDownIcon,
  ChevronRightIcon,
  BuildingLibraryIcon,
  TrashIcon,
  KeyIcon,
  DocumentTextIcon,
  ClipboardDocumentIcon,
  ArrowTopRightOnSquareIcon,
} from '@heroicons/react/24/outline';
import AdminNav from '@/components/walt/nav/AdminNav';
import { credentialTemplates, getTemplatesByCategory, CredentialTemplate } from '@/types/credentialTemplates';

// -- Interfaces --

interface IssuerSummary {
  id: string;
  legalName: string;
  domain: string;
  country: string;
  status: 'ACTIVE' | 'SUSPENDED' | 'REVOKED';
  hasCertificate: boolean;
  certificateExpiry?: string;
  credentialCount: number;
  createdAt: string;
}

interface IssuerDetail {
  id: string;
  legalName: string;
  country: string;
  domain: string;
  contactEmail: string;
  contactAddress?: string;
  issuerDid?: string;
  iacaCertificate?: CertInfo;
  signerCertificate?: CertInfo;
  x5Chain?: string[];
  credentialConfigurations: Record<string, any>;
  status: 'ACTIVE' | 'SUSPENDED' | 'REVOKED';
  createdAt: string;
  updatedAt: string;
}

interface CertInfo {
  subject: string;
  issuer: string;
  notBefore: string;
  notAfter: string;
  serialNumber: string;
  fingerprint: string;
}

// -- Status badge --

function IssuerStatusBadge({ status }: { status: string }) {
  const colorMap: Record<string, string> = {
    ACTIVE: 'bg-emerald-100 text-emerald-800 border-emerald-200',
    SUSPENDED: 'bg-amber-100 text-amber-800 border-amber-200',
    REVOKED: 'bg-red-100 text-red-800 border-red-200',
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

export default function Issuers() {
  const env = useContext(EnvContext);
  const router = useRouter();
  const [activeTab, setActiveTab] = useState<'list' | 'register'>('list');

  // List state
  const [issuerList, setIssuerList] = useState<IssuerSummary[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  // Detail state
  const [expandedIssuer, setExpandedIssuer] = useState<string | null>(null);
  const [issuerDetail, setIssuerDetail] = useState<Record<string, IssuerDetail>>({});
  const [detailLoading, setDetailLoading] = useState<string | null>(null);

  // Register form state
  const [formLegalName, setFormLegalName] = useState('');
  const [formCountry, setFormCountry] = useState('');
  const [formDomain, setFormDomain] = useState('');
  const [formEmail, setFormEmail] = useState('');
  const [formAddress, setFormAddress] = useState('');
  const [registering, setRegistering] = useState(false);
  const [registerError, setRegisterError] = useState<string | null>(null);
  const [registerSuccess, setRegisterSuccess] = useState<string | null>(null);

  // Action state
  const [generatingCert, setGeneratingCert] = useState<string | null>(null);
  const [deletingIssuer, setDeletingIssuer] = useState<string | null>(null);
  const [togglingStatus, setTogglingStatus] = useState<string | null>(null);
  const [actionError, setActionError] = useState<string | null>(null);

  // Credential config state
  const [editingCredentials, setEditingCredentials] = useState<string | null>(null);
  const [credentialConfigJson, setCredentialConfigJson] = useState('');
  const [credentialError, setCredentialError] = useState<string | null>(null);
  const [showTemplatePicker, setShowTemplatePicker] = useState<Record<string, boolean>>({});

  // Trust list URL state
  const [lotlCopied, setLotlCopied] = useState(false);

  const apiBase = '/api/proxy/issuer';

  // -- Fetch issuer list --

  const fetchIssuerList = async () => {
    try {
      const response = await axios.get<IssuerSummary[]>(`${apiBase}/admin/issuer`);
      setIssuerList(response.data);
      setError(null);
    } catch (e: any) {
      if (e.response?.status === 503) {
        setError(
          'Issuer Registrar feature is not enabled. Set ISSUER_REGISTRAR_ENABLED=true in the issuer configuration to enable.'
        );
      } else {
        setError(
          e.response?.data?.error ||
            e.message ||
            'Failed to fetch issuers'
        );
      }
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    fetchIssuerList();
  }, []);

  // -- Detail expand --

  const handleIssuerClick = async (issuerId: string) => {
    if (expandedIssuer === issuerId) {
      setExpandedIssuer(null);
      return;
    }
    setExpandedIssuer(issuerId);
    if (issuerDetail[issuerId]) return;

    setDetailLoading(issuerId);
    try {
      const response = await axios.get<IssuerDetail>(`${apiBase}/admin/issuer/${issuerId}`);
      setIssuerDetail((prev) => ({ ...prev, [issuerId]: response.data }));
    } catch (e: any) {
      console.error(`Failed to load issuer detail for ${issuerId}:`, e);
    } finally {
      setDetailLoading(null);
    }
  };

  // -- Register issuer --

  const handleRegister = async () => {
    setRegistering(true);
    setRegisterError(null);
    setRegisterSuccess(null);

    try {
      const response = await axios.post(`${apiBase}/admin/issuer`, {
        legalName: formLegalName,
        country: formCountry,
        domain: formDomain,
        contactEmail: formEmail,
        contactAddress: formAddress || undefined,
      });

      setRegisterSuccess(
        `Registered "${response.data.legalName}" (${response.data.domain})`
      );
      setFormLegalName('');
      setFormCountry('');
      setFormDomain('');
      setFormEmail('');
      setFormAddress('');
      await fetchIssuerList();
    } catch (e: any) {
      setRegisterError(
        e.response?.data?.error || e.message || 'Failed to register issuer'
      );
    } finally {
      setRegistering(false);
    }
  };

  // -- Generate certificate --

  const handleGenerateCert = async (issuerId: string) => {
    setGeneratingCert(issuerId);
    setActionError(null);
    try {
      await axios.post(`${apiBase}/admin/issuer/${issuerId}/certificate/generate`);
      const response = await axios.get<IssuerDetail>(`${apiBase}/admin/issuer/${issuerId}`);
      setIssuerDetail((prev) => ({ ...prev, [issuerId]: response.data }));
      await fetchIssuerList();
    } catch (e: any) {
      setActionError(
        e.response?.data?.error || e.message || 'Failed to generate certificate'
      );
    } finally {
      setGeneratingCert(null);
    }
  };

  // -- Toggle status --

  const handleToggleStatus = async (issuerId: string, currentStatus: string) => {
    const newStatus = currentStatus === 'ACTIVE' ? 'SUSPENDED' : 'ACTIVE';
    setTogglingStatus(issuerId);
    setActionError(null);
    try {
      await axios.put(`${apiBase}/admin/issuer/${issuerId}`, { status: newStatus });
      const response = await axios.get<IssuerDetail>(`${apiBase}/admin/issuer/${issuerId}`);
      setIssuerDetail((prev) => ({ ...prev, [issuerId]: response.data }));
      await fetchIssuerList();
    } catch (e: any) {
      setActionError(
        e.response?.data?.error || e.message || 'Failed to update status'
      );
    } finally {
      setTogglingStatus(null);
    }
  };

  // -- Delete issuer --

  const handleDelete = async (issuerId: string) => {
    if (!confirm('Are you sure you want to delete this issuer tenant?')) return;
    setDeletingIssuer(issuerId);
    setActionError(null);
    try {
      await axios.delete(`${apiBase}/admin/issuer/${issuerId}`);
      setExpandedIssuer(null);
      setIssuerDetail((prev) => {
        const next = { ...prev };
        delete next[issuerId];
        return next;
      });
      await fetchIssuerList();
    } catch (e: any) {
      setActionError(
        e.response?.data?.error || e.message || 'Failed to delete issuer'
      );
    } finally {
      setDeletingIssuer(null);
    }
  };

  // -- Update credential configurations --

  const handleSaveCredentials = async (issuerId: string) => {
    setCredentialError(null);
    try {
      const parsed = JSON.parse(credentialConfigJson);
      await axios.put(`${apiBase}/admin/issuer/${issuerId}/credentials`, parsed);
      const response = await axios.get<IssuerDetail>(`${apiBase}/admin/issuer/${issuerId}`);
      setIssuerDetail((prev) => ({ ...prev, [issuerId]: response.data }));
      setEditingCredentials(null);
      await fetchIssuerList();
    } catch (e: any) {
      if (e instanceof SyntaxError) {
        setCredentialError('Invalid JSON format');
      } else {
        setCredentialError(
          e.response?.data?.error || e.message || 'Failed to update credentials'
        );
      }
    }
  };

  // -- Format helpers --

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
              Issuer Registrar
            </h1>
            <p className="text-sm text-gray-500 mt-1">
              Manage multi-tenant credential issuers with independent keys and catalogs
            </p>
          </div>
          <div className="flex items-center gap-2">
            <button
              onClick={() => {
                const url = `${apiBase}/admin/issuer/lotl.xml`;
                navigator.clipboard.writeText(url);
                setLotlCopied(true);
                setTimeout(() => setLotlCopied(false), 2000);
              }}
              className="inline-flex items-center gap-1.5 px-3 py-1.5 text-sm font-medium text-blue-600 border border-blue-200 rounded-lg hover:bg-blue-50 transition-colors"
              title="Copy LOTL URL for verifier trust list configuration"
            >
              <ClipboardDocumentIcon className="w-4 h-4" />
              {lotlCopied ? 'Copied!' : 'Copy LOTL URL'}
            </button>
            <Button
              onClick={fetchIssuerList}
              loading={loading}
              disabled={!!error}
              size="sm"
              color="secondary"
            >
              <div className="flex items-center gap-2">
                <ArrowPathIcon className="w-4 h-4" />
                Refresh
              </div>
            </Button>
          </div>
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

        {/* Action Error */}
        {actionError && (
          <div className="bg-red-50 border border-red-200 rounded-lg p-3 mb-4">
            <p className="text-red-800 text-sm">{actionError}</p>
          </div>
        )}

        {/* Tabs */}
        {!loading && !error && (
          <>
            <div className="border-b border-gray-200 mb-6">
              <nav className="flex gap-6" aria-label="Tabs">
                <button
                  onClick={() => setActiveTab('list')}
                  className={`pb-3 text-sm font-medium border-b-2 transition-colors ${
                    activeTab === 'list'
                      ? 'border-blue-600 text-blue-600'
                      : 'border-transparent text-gray-500 hover:text-gray-700 hover:border-gray-300'
                  }`}
                >
                  Issuers ({issuerList.length})
                </button>
                <button
                  onClick={() => setActiveTab('register')}
                  className={`pb-3 text-sm font-medium border-b-2 transition-colors ${
                    activeTab === 'register'
                      ? 'border-blue-600 text-blue-600'
                      : 'border-transparent text-gray-500 hover:text-gray-700 hover:border-gray-300'
                  }`}
                >
                  Register New Issuer
                </button>
              </nav>
            </div>

            {/* ==================== LIST TAB ==================== */}
            {activeTab === 'list' && (
              <div>
                {issuerList.length === 0 && (
                  <div className="text-center py-8 text-gray-400">
                    <BuildingLibraryIcon className="w-12 h-12 mx-auto mb-3 text-gray-300" />
                    <p>No issuers registered yet.</p>
                    <p className="text-sm mt-1">
                      Switch to the &ldquo;Register New Issuer&rdquo; tab to get
                      started.
                    </p>
                  </div>
                )}

                {issuerList.length > 0 && (
                  <div className="space-y-3">
                    {issuerList.map((issuer) => (
                      <div
                        key={issuer.id}
                        className={`border rounded-lg bg-white transition-all ${
                          expandedIssuer === issuer.id
                            ? 'border-blue-300 ring-1 ring-blue-200'
                            : 'border-gray-200'
                        }`}
                      >
                        {/* Summary row */}
                        <button
                          onClick={() => handleIssuerClick(issuer.id)}
                          className="w-full text-left p-4 flex items-center justify-between hover:bg-gray-50 transition-colors rounded-t-lg"
                        >
                          <div className="flex items-center gap-3 min-w-0">
                            {expandedIssuer === issuer.id ? (
                              <ChevronDownIcon className="w-4 h-4 text-gray-400 flex-shrink-0" />
                            ) : (
                              <ChevronRightIcon className="w-4 h-4 text-gray-400 flex-shrink-0" />
                            )}
                            <div className="min-w-0">
                              <div className="flex items-center gap-2">
                                <span className="font-medium text-sm text-gray-900">
                                  {issuer.legalName}
                                </span>
                                <IssuerStatusBadge status={issuer.status} />
                              </div>
                              <div className="flex items-center gap-3 text-xs text-gray-500 mt-0.5">
                                <span>{issuer.domain}</span>
                                <span>{issuer.country}</span>
                                <span>{issuer.credentialCount} credential(s)</span>
                                <span>
                                  {issuer.hasCertificate
                                    ? `Cert expires ${formatDate(issuer.certificateExpiry)}`
                                    : 'No certificate'}
                                </span>
                              </div>
                            </div>
                          </div>
                          <div className="flex items-center gap-2 flex-shrink-0 ml-3">
                            {issuer.hasCertificate ? (
                              <KeyIcon className="w-5 h-5 text-emerald-500" />
                            ) : (
                              <KeyIcon className="w-5 h-5 text-gray-300" />
                            )}
                          </div>
                        </button>

                        {/* Detail panel */}
                        {expandedIssuer === issuer.id && (
                          <div className="border-t border-blue-200 bg-blue-50/30 p-5">
                            {detailLoading === issuer.id ? (
                              <div className="flex justify-center py-6">
                                <div className="animate-spin rounded-full h-8 w-8 border-b-2 border-blue-500"></div>
                              </div>
                            ) : issuerDetail[issuer.id] ? (
                              <IssuerDetailPanel
                                issuer={issuer}
                                detail={issuerDetail[issuer.id]}
                                apiBase={apiBase!}
                                formatDate={formatDate}
                                onGenerateCert={() => handleGenerateCert(issuer.id)}
                                onToggleStatus={() => handleToggleStatus(issuer.id, issuer.status)}
                                onDelete={() => handleDelete(issuer.id)}
                                generatingCert={generatingCert === issuer.id}
                                deletingIssuer={deletingIssuer === issuer.id}
                                togglingStatus={togglingStatus === issuer.id}
                                editingCredentials={editingCredentials === issuer.id}
                                showTemplatePicker={showTemplatePicker[issuer.id] || false}
                                onToggleTemplatePicker={() => setShowTemplatePicker(prev => ({ ...prev, [issuer.id]: !prev[issuer.id] }))}
                                onEditCredentials={() => {
                                  setEditingCredentials(issuer.id);
                                  setCredentialConfigJson(
                                    JSON.stringify(issuerDetail[issuer.id].credentialConfigurations, null, 2)
                                  );
                                  setCredentialError(null);
                                }}
                                onCancelEditCredentials={() => {
                                  setEditingCredentials(null);
                                  setCredentialError(null);
                                }}
                                onSaveCredentials={() => handleSaveCredentials(issuer.id)}
                                credentialConfigJson={credentialConfigJson}
                                onCredentialConfigChange={setCredentialConfigJson}
                                credentialError={credentialError}
                              />
                            ) : (
                              <p className="text-sm text-gray-500 text-center py-4">
                                Failed to load details
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

            {/* ==================== REGISTER TAB ==================== */}
            {activeTab === 'register' && (
              <div>
                <div className="border border-gray-200 rounded-lg p-5 bg-gray-50">
                  <h2 className="text-lg font-semibold text-gray-800 mb-4">
                    Register New Issuer
                  </h2>
                  <p className="text-sm text-gray-600 mb-4">
                    Register a new credential issuer organization. After registration,
                    generate an IACA + Document Signer certificate chain for credential signing.
                  </p>

                  <div className="space-y-4">
                    <div className="grid grid-cols-1 sm:grid-cols-2 gap-4">
                      <InputField
                        value={formLegalName}
                        onChange={setFormLegalName}
                        type="text"
                        name="legalName"
                        label="Legal Name *"
                        placeholder="Example Bank Ltd"
                        showLabel={true}
                      />
                      <InputField
                        value={formCountry}
                        onChange={setFormCountry}
                        type="text"
                        name="country"
                        label="Country Code *"
                        placeholder="AU"
                        showLabel={true}
                      />
                    </div>

                    <div className="grid grid-cols-1 sm:grid-cols-2 gap-4">
                      <InputField
                        value={formDomain}
                        onChange={setFormDomain}
                        type="text"
                        name="domain"
                        label="Domain *"
                        placeholder="issuer.example.com"
                        showLabel={true}
                      />
                      <InputField
                        value={formEmail}
                        onChange={setFormEmail}
                        type="email"
                        name="contactEmail"
                        label="Contact Email *"
                        placeholder="admin@example.com"
                        showLabel={true}
                      />
                    </div>

                    <InputField
                      value={formAddress}
                      onChange={setFormAddress}
                      type="text"
                      name="contactAddress"
                      label="Address"
                      placeholder="123 Main St, City"
                      showLabel={true}
                    />

                    <Button
                      onClick={handleRegister}
                      loading={registering}
                      disabled={
                        !formLegalName.trim() ||
                        !formCountry.trim() ||
                        !formDomain.trim() ||
                        !formEmail.trim()
                      }
                      color="primary"
                    >
                      Register Issuer
                    </Button>

                    {registerError && (
                      <div className="bg-red-50 border border-red-200 rounded-lg p-3">
                        <p className="text-red-800 text-sm">{registerError}</p>
                      </div>
                    )}

                    {registerSuccess && (
                      <div className="bg-green-50 border border-green-200 rounded-lg p-3">
                        <p className="text-green-800 text-sm">
                          {registerSuccess}
                        </p>
                      </div>
                    )}
                  </div>
                </div>
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

// -- Issuer Detail Panel --

function IssuerDetailPanel({
  issuer,
  detail,
  apiBase,
  formatDate,
  onGenerateCert,
  onToggleStatus,
  onDelete,
  generatingCert,
  deletingIssuer,
  togglingStatus,
  editingCredentials,
  showTemplatePicker,
  onToggleTemplatePicker,
  onEditCredentials,
  onCancelEditCredentials,
  onSaveCredentials,
  credentialConfigJson,
  onCredentialConfigChange,
  credentialError,
}: {
  issuer: IssuerSummary;
  detail: IssuerDetail;
  apiBase: string;
  formatDate: (d?: string) => string;
  onGenerateCert: () => void;
  onToggleStatus: () => void;
  onDelete: () => void;
  generatingCert: boolean;
  deletingIssuer: boolean;
  togglingStatus: boolean;
  editingCredentials: boolean;
  showTemplatePicker: boolean;
  onToggleTemplatePicker: () => void;
  onEditCredentials: () => void;
  onCancelEditCredentials: () => void;
  onSaveCredentials: () => void;
  credentialConfigJson: string;
  onCredentialConfigChange: (v: string) => void;
  credentialError: string | null;
}) {
  const [tslCopied, setTslCopied] = useState(false);

  const isTemplateInCatalog = (template: CredentialTemplate): boolean => {
    return Object.keys(detail.credentialConfigurations).includes(template.id);
  };

  const handleAddTemplate = (template: CredentialTemplate) => {
    const current = editingCredentials
      ? JSON.parse(credentialConfigJson)
      : detail.credentialConfigurations;
    const updated = { ...current, ...template.config };
    onCredentialConfigChange(JSON.stringify(updated, null, 2));
    if (!editingCredentials) {
      onEditCredentials();
      // Re-set the JSON after entering edit mode
      setTimeout(() => onCredentialConfigChange(JSON.stringify(updated, null, 2)), 0);
    }
  };

  const handleRemoveCredential = (configKey: string) => {
    const current = editingCredentials
      ? JSON.parse(credentialConfigJson)
      : detail.credentialConfigurations;
    const updated = { ...current };
    delete updated[configKey];
    onCredentialConfigChange(JSON.stringify(updated, null, 2));
    if (!editingCredentials) {
      onEditCredentials();
      setTimeout(() => onCredentialConfigChange(JSON.stringify(updated, null, 2)), 0);
    }
  };

  return (
    <div>
      {/* Info grid */}
      <div className="grid grid-cols-1 sm:grid-cols-2 gap-x-6 gap-y-3 mb-5">
        <InfoRow label="Issuer ID" value={detail.id} mono />
        <InfoRow label="Domain" value={detail.domain} />
        <InfoRow label="Country" value={detail.country} />
        <InfoRow label="Contact Email" value={detail.contactEmail} />
        {detail.contactAddress && (
          <InfoRow label="Address" value={detail.contactAddress} />
        )}
        {detail.issuerDid && (
          <InfoRow label="DID" value={detail.issuerDid} mono />
        )}
        <InfoRow label="Created" value={formatDate(detail.createdAt)} />
        <InfoRow label="Updated" value={formatDate(detail.updatedAt)} />
      </div>

      {/* Quick Actions */}
      <div className="mb-4 flex flex-wrap gap-2">
        {detail.status === 'ACTIVE' && detail.x5Chain && Object.keys(detail.credentialConfigurations).length > 0 ? (
          <button
            onClick={() => {
              const credIds = Object.keys(detail.credentialConfigurations).join(',');
              window.location.href = `/credentials?ids=${credIds}&issuerId=${issuer.id}&mode=issuance`;
            }}
            className="inline-flex items-center gap-1.5 px-3 py-1.5 bg-emerald-600 text-white text-sm font-medium rounded-lg hover:bg-emerald-700 transition-colors"
          >
            Issue Credential
          </button>
        ) : detail.status === 'ACTIVE' && detail.x5Chain ? (
          <span className="text-sm text-amber-600">Configure credentials first</span>
        ) : null}

        {detail.status === 'ACTIVE' && detail.x5Chain && (
          <a
            href={`${apiBase}/issuers/${issuer.id}/draft13/.well-known/openid-credential-issuer`}
            target="_blank"
            rel="noopener noreferrer"
            className="inline-flex items-center gap-1.5 px-3 py-1.5 text-blue-600 text-sm font-medium rounded-lg border border-blue-200 hover:bg-blue-50 transition-colors"
          >
            <ArrowTopRightOnSquareIcon className="w-4 h-4" />
            View Metadata
          </a>
        )}

        {detail.status === 'ACTIVE' && detail.x5Chain && (
          <button
            onClick={() => {
              const url = `${apiBase}/admin/issuer/tsl/${detail.country}.xml`;
              navigator.clipboard.writeText(url);
              setTslCopied(true);
              setTimeout(() => setTslCopied(false), 2000);
            }}
            className="inline-flex items-center gap-1.5 px-3 py-1.5 text-gray-600 text-sm font-medium rounded-lg border border-gray-200 hover:bg-gray-50 transition-colors"
            title={`Copy TSL URL for ${detail.country} issuers`}
          >
            <ClipboardDocumentIcon className="w-4 h-4" />
            {tslCopied ? 'Copied!' : `Copy ${detail.country} TSL URL`}
          </button>
        )}
      </div>

      {/* Certificate section */}
      <div className="mb-5 border border-gray-200 rounded-lg bg-white p-4">
        <h4 className="text-sm font-semibold text-gray-800 mb-3 flex items-center gap-2">
          <KeyIcon className="w-4 h-4" />
          Certificate Chain (IACA + Document Signer)
        </h4>

        {detail.signerCertificate ? (
          <div className="space-y-4 text-sm">
            <div>
              <h5 className="text-xs font-medium text-gray-500 uppercase tracking-wider mb-2">
                Document Signer (Leaf)
              </h5>
              <div className="grid grid-cols-1 sm:grid-cols-2 gap-x-4 gap-y-1">
                <InfoRow label="Subject" value={detail.signerCertificate.subject} mono />
                <InfoRow label="Expires" value={formatDate(detail.signerCertificate.notAfter)} />
                <InfoRow label="Fingerprint" value={detail.signerCertificate.fingerprint} mono />
                <InfoRow label="Serial" value={detail.signerCertificate.serialNumber} mono />
              </div>
            </div>
            {detail.iacaCertificate && (
              <div>
                <h5 className="text-xs font-medium text-gray-500 uppercase tracking-wider mb-2">
                  IACA (Root CA)
                </h5>
                <div className="grid grid-cols-1 sm:grid-cols-2 gap-x-4 gap-y-1">
                  <InfoRow label="Subject" value={detail.iacaCertificate.subject} mono />
                  <InfoRow label="Expires" value={formatDate(detail.iacaCertificate.notAfter)} />
                  <InfoRow label="Fingerprint" value={detail.iacaCertificate.fingerprint} mono />
                </div>
              </div>
            )}
            <div className="flex gap-2 mt-3">
              <button
                onClick={onGenerateCert}
                disabled={generatingCert}
                className="text-sm text-blue-600 hover:text-blue-800 font-medium transition-colors disabled:opacity-50"
              >
                {generatingCert ? 'Regenerating...' : 'Regenerate Certificates'}
              </button>
            </div>
          </div>
        ) : (
          <div className="text-center py-4">
            <p className="text-sm text-gray-500 mb-3">
              No certificates generated yet. Generate an IACA + Document Signer chain for credential signing.
            </p>
            <button
              onClick={onGenerateCert}
              disabled={generatingCert}
              className="inline-flex items-center gap-2 px-4 py-2 bg-blue-600 text-white text-sm font-medium rounded-lg hover:bg-blue-700 transition-colors disabled:opacity-50"
            >
              {generatingCert ? (
                <div className="animate-spin rounded-full h-4 w-4 border-b-2 border-white"></div>
              ) : (
                <KeyIcon className="w-4 h-4" />
              )}
              Generate IACA + Document Signer (P-256)
            </button>
          </div>
        )}
      </div>

      {/* Credential Configurations */}
      <div className="mb-5 border border-gray-200 rounded-lg bg-white p-4">
        <div className="flex items-center justify-between mb-3">
          <h4 className="text-sm font-semibold text-gray-800 flex items-center gap-2">
            <DocumentTextIcon className="w-4 h-4" />
            Credential Configurations
            <span className="text-xs text-gray-400 font-normal">
              ({Object.keys(detail.credentialConfigurations).length} configured)
            </span>
          </h4>
          <div className="flex gap-2">
            <button
              onClick={onToggleTemplatePicker}
              className="text-xs text-blue-600 hover:text-blue-800 font-medium transition-colors"
            >
              {showTemplatePicker ? 'Hide Templates' : 'Add from Templates'}
            </button>
            <button
              onClick={onEditCredentials}
              className="text-xs text-gray-500 hover:text-gray-700 font-medium transition-colors"
            >
              Edit as JSON
            </button>
          </div>
        </div>

        {/* Current credentials as cards */}
        {Object.keys(detail.credentialConfigurations).length > 0 && !editingCredentials && (
          <div className="mb-3 space-y-1.5">
            {Object.entries(detail.credentialConfigurations).map(([key, config]) => (
              <div key={key} className="flex items-center justify-between px-3 py-2 bg-gray-50 rounded-lg">
                <div className="flex items-center gap-2">
                  <span className="font-mono text-xs text-gray-700">{key}</span>
                  <span className="px-1.5 py-0.5 bg-blue-100 text-blue-700 rounded text-xs">
                    {(config as any)?.format || 'unknown'}
                  </span>
                </div>
                <button
                  onClick={() => handleRemoveCredential(key)}
                  className="text-xs text-red-400 hover:text-red-600 transition-colors"
                >
                  Remove
                </button>
              </div>
            ))}
          </div>
        )}

        {Object.keys(detail.credentialConfigurations).length === 0 && !editingCredentials && !showTemplatePicker && (
          <p className="text-sm text-gray-500 mb-3">
            No credential configurations set. Click &ldquo;Add from Templates&rdquo; to get started.
          </p>
        )}

        {/* Template picker */}
        {showTemplatePicker && !editingCredentials && (
          <div className="mt-3 p-3 border border-blue-200 rounded-lg bg-blue-50/50 space-y-4">
            {(['EUDI', 'Financial', 'Identity'] as const).map(category => (
              <div key={category}>
                <h5 className="text-xs font-semibold text-gray-500 uppercase mb-2">{category}</h5>
                <div className="grid grid-cols-2 gap-2">
                  {getTemplatesByCategory(category).map(template => (
                    <button
                      key={template.id}
                      disabled={isTemplateInCatalog(template)}
                      onClick={() => handleAddTemplate(template)}
                      className={`text-left p-2 rounded-lg border text-xs transition-colors ${
                        isTemplateInCatalog(template)
                          ? 'border-gray-200 bg-gray-50 text-gray-400 cursor-not-allowed'
                          : 'border-gray-300 hover:border-blue-500 hover:bg-blue-50 cursor-pointer'
                      }`}
                    >
                      <span className="font-medium">{template.name}</span>
                      <span className="block text-gray-400 mt-0.5">{template.format}</span>
                    </button>
                  ))}
                </div>
              </div>
            ))}
          </div>
        )}

        {/* JSON editor (advanced mode) */}
        {editingCredentials && (
          <div className="space-y-3">
            <textarea
              value={credentialConfigJson}
              onChange={(e) => onCredentialConfigChange(e.target.value)}
              rows={12}
              className="w-full px-3 py-2 border border-gray-300 rounded-lg text-xs font-mono focus:outline-none focus:ring-2 focus:ring-blue-500 focus:border-transparent resize-y"
              placeholder='{"eu.europa.ec.eudi.pid.1": {"format": "mso_mdoc", ...}}'
            />
            {credentialError && (
              <div className="bg-red-50 border border-red-200 rounded p-2">
                <p className="text-red-800 text-xs">{credentialError}</p>
              </div>
            )}
            <div className="flex gap-2">
              <button
                onClick={onSaveCredentials}
                className="px-3 py-1.5 bg-blue-600 text-white text-sm rounded-md hover:bg-blue-700 transition-colors"
              >
                Save
              </button>
              <button
                onClick={onCancelEditCredentials}
                className="px-3 py-1.5 text-gray-600 text-sm rounded-md hover:bg-gray-100 transition-colors"
              >
                Cancel
              </button>
            </div>
          </div>
        )}
      </div>

      {/* Actions */}
      <div className="flex items-center justify-between pt-3 border-t border-gray-200">
        <div className="flex gap-3">
          {detail.status !== 'REVOKED' && (
            <button
              onClick={onToggleStatus}
              disabled={togglingStatus}
              className={`text-sm font-medium transition-colors disabled:opacity-50 ${
                detail.status === 'ACTIVE'
                  ? 'text-amber-600 hover:text-amber-800'
                  : 'text-emerald-600 hover:text-emerald-800'
              }`}
            >
              {togglingStatus
                ? 'Updating...'
                : detail.status === 'ACTIVE'
                  ? 'Suspend'
                  : 'Activate'}
            </button>
          )}
        </div>
        <button
          onClick={onDelete}
          disabled={deletingIssuer}
          className="inline-flex items-center gap-1.5 text-sm text-red-500 hover:text-red-700 font-medium transition-colors disabled:opacity-50"
        >
          {deletingIssuer ? (
            <div className="animate-spin rounded-full h-4 w-4 border-b-2 border-red-500"></div>
          ) : (
            <TrashIcon className="w-4 h-4" />
          )}
          Delete
        </button>
      </div>
    </div>
  );
}

// -- Info row --

function InfoRow({
  label,
  value,
  mono = false,
}: {
  label: string;
  value: string;
  mono?: boolean;
}) {
  return (
    <div>
      <dt className="text-xs font-medium text-gray-500 uppercase tracking-wider">
        {label}
      </dt>
      <dd
        className={`text-sm text-gray-900 mt-0.5 truncate ${mono ? 'font-mono text-xs' : ''}`}
        title={value}
      >
        {value}
      </dd>
    </div>
  );
}
