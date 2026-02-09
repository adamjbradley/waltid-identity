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
  ShieldCheckIcon,
  TrashIcon,
  KeyIcon,
  DocumentArrowDownIcon,
} from '@heroicons/react/24/outline';
import AdminNav from '@/components/walt/nav/AdminNav';

// -- Interfaces --

interface RpSummary {
  id: string;
  legalName: string;
  domain: string;
  country: string;
  status: 'ACTIVE' | 'SUSPENDED' | 'REVOKED';
  hasCertificate: boolean;
  certificateExpiry?: string;
  createdAt: string;
}

interface RpDetail {
  id: string;
  legalName: string;
  tradeName?: string;
  registrationNumber?: string;
  country: string;
  contactEmail: string;
  contactPhone?: string;
  contactAddress?: string;
  intendedUse?: string;
  dcqlQuery?: object;
  privacyPolicyUrl?: string;
  dataRetentionPeriod?: string;
  lawfulBasis?: string;
  dpaAcknowledged?: boolean;
  clientId: string;
  domain: string;
  certificate?: {
    subject: string;
    issuer: string;
    notBefore: string;
    notAfter: string;
    serialNumber: string;
    fingerprint: string;
  };
  x5c?: string[];
  status: 'ACTIVE' | 'SUSPENDED' | 'REVOKED';
  createdAt: string;
  updatedAt: string;
}

// -- Status badge --

function RpStatusBadge({ status }: { status: string }) {
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

export default function RelyingParties() {
  const env = useContext(EnvContext);
  const router = useRouter();
  const [activeTab, setActiveTab] = useState<'list' | 'register'>('list');

  // List state
  const [rpList, setRpList] = useState<RpSummary[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  // Detail state
  const [expandedRp, setExpandedRp] = useState<string | null>(null);
  const [rpDetail, setRpDetail] = useState<Record<string, RpDetail>>({});
  const [detailLoading, setDetailLoading] = useState<string | null>(null);

  // Register form state
  const [formLegalName, setFormLegalName] = useState('');
  const [formTradeName, setFormTradeName] = useState('');
  const [formRegNumber, setFormRegNumber] = useState('');
  const [formCountry, setFormCountry] = useState('');
  const [formDomain, setFormDomain] = useState('');
  const [formEmail, setFormEmail] = useState('');
  const [formPhone, setFormPhone] = useState('');
  const [formAddress, setFormAddress] = useState('');
  const [formIntendedUse, setFormIntendedUse] = useState('');
  const [formPrivacyPolicyUrl, setFormPrivacyPolicyUrl] = useState('');
  const [formDataRetention, setFormDataRetention] = useState('');
  const [formLawfulBasis, setFormLawfulBasis] = useState('');
  const [formDpaAcknowledged, setFormDpaAcknowledged] = useState(false);
  const [registering, setRegistering] = useState(false);
  const [registerError, setRegisterError] = useState<string | null>(null);
  const [registerSuccess, setRegisterSuccess] = useState<string | null>(null);

  // Action state
  const [generatingCert, setGeneratingCert] = useState<string | null>(null);
  const [deletingRp, setDeletingRp] = useState<string | null>(null);
  const [togglingStatus, setTogglingStatus] = useState<string | null>(null);
  const [actionError, setActionError] = useState<string | null>(null);

  const apiBase = env.NEXT_PUBLIC_VERIFIER2;

  // -- Fetch RP list --

  const fetchRpList = async () => {
    try {
      const response = await axios.get<RpSummary[]>(`${apiBase}/admin/rp`);
      setRpList(response.data);
      setError(null);
    } catch (e: any) {
      if (e.response?.status === 503) {
        setError(
          'RP Registrar feature is not enabled. Set RP_REGISTRAR_ENABLED=true in the verifier configuration to enable.'
        );
      } else {
        setError(
          e.response?.data?.error ||
            e.message ||
            'Failed to fetch relying parties'
        );
      }
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    if (apiBase) {
      fetchRpList();
    } else {
      setError('Verifier API2 is not configured (NEXT_PUBLIC_VERIFIER2)');
      setLoading(false);
    }
  }, [apiBase]);

  // -- Detail expand --

  const handleRpClick = async (rpId: string) => {
    if (expandedRp === rpId) {
      setExpandedRp(null);
      return;
    }
    setExpandedRp(rpId);
    if (rpDetail[rpId]) return;

    setDetailLoading(rpId);
    try {
      const response = await axios.get<RpDetail>(`${apiBase}/admin/rp/${rpId}`);
      setRpDetail((prev) => ({ ...prev, [rpId]: response.data }));
    } catch (e: any) {
      console.error(`Failed to load RP detail for ${rpId}:`, e);
    } finally {
      setDetailLoading(null);
    }
  };

  // -- Register RP --

  const handleRegister = async () => {
    setRegistering(true);
    setRegisterError(null);
    setRegisterSuccess(null);

    try {
      const response = await axios.post(`${apiBase}/admin/rp`, {
        legalName: formLegalName,
        tradeName: formTradeName || undefined,
        registrationNumber: formRegNumber || undefined,
        country: formCountry,
        domain: formDomain,
        contactEmail: formEmail,
        contactPhone: formPhone || undefined,
        contactAddress: formAddress,
        intendedUse: formIntendedUse || undefined,
        privacyPolicyUrl: formPrivacyPolicyUrl,
        dataRetentionPeriod: formDataRetention,
        lawfulBasis: formLawfulBasis,
        dpaAcknowledged: formDpaAcknowledged,
      });

      setRegisterSuccess(
        `Registered "${response.data.legalName}" (${response.data.domain})`
      );
      // Reset form
      setFormLegalName('');
      setFormTradeName('');
      setFormRegNumber('');
      setFormCountry('');
      setFormDomain('');
      setFormEmail('');
      setFormPhone('');
      setFormAddress('');
      setFormIntendedUse('');
      setFormPrivacyPolicyUrl('');
      setFormDataRetention('');
      setFormLawfulBasis('');
      setFormDpaAcknowledged(false);
      // Refresh list
      await fetchRpList();
    } catch (e: any) {
      setRegisterError(
        e.response?.data?.error || e.message || 'Failed to register RP'
      );
    } finally {
      setRegistering(false);
    }
  };

  // -- Generate certificate --

  const handleGenerateCert = async (rpId: string) => {
    setGeneratingCert(rpId);
    setActionError(null);
    try {
      await axios.post(`${apiBase}/admin/rp/${rpId}/certificate/generate`);
      // Refresh detail
      const response = await axios.get<RpDetail>(`${apiBase}/admin/rp/${rpId}`);
      setRpDetail((prev) => ({ ...prev, [rpId]: response.data }));
      await fetchRpList();
    } catch (e: any) {
      setActionError(
        e.response?.data?.error || e.message || 'Failed to generate certificate'
      );
    } finally {
      setGeneratingCert(null);
    }
  };

  // -- Toggle status --

  const handleToggleStatus = async (rpId: string, currentStatus: string) => {
    const newStatus = currentStatus === 'ACTIVE' ? 'SUSPENDED' : 'ACTIVE';
    setTogglingStatus(rpId);
    setActionError(null);
    try {
      await axios.put(`${apiBase}/admin/rp/${rpId}`, { status: newStatus });
      const response = await axios.get<RpDetail>(`${apiBase}/admin/rp/${rpId}`);
      setRpDetail((prev) => ({ ...prev, [rpId]: response.data }));
      await fetchRpList();
    } catch (e: any) {
      setActionError(
        e.response?.data?.error || e.message || 'Failed to update status'
      );
    } finally {
      setTogglingStatus(null);
    }
  };

  // -- Delete RP --

  const handleDelete = async (rpId: string) => {
    if (!confirm('Are you sure you want to delete this relying party?')) return;
    setDeletingRp(rpId);
    setActionError(null);
    try {
      await axios.delete(`${apiBase}/admin/rp/${rpId}`);
      setExpandedRp(null);
      setRpDetail((prev) => {
        const next = { ...prev };
        delete next[rpId];
        return next;
      });
      await fetchRpList();
    } catch (e: any) {
      setActionError(
        e.response?.data?.error || e.message || 'Failed to delete RP'
      );
    } finally {
      setDeletingRp(null);
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
              Relying Party Registrar
            </h1>
            <p className="text-sm text-gray-500 mt-1">
              Manage EUDI relying party onboarding and access certificates
            </p>
          </div>
          <Button
            onClick={fetchRpList}
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
                  Relying Parties ({rpList.length})
                </button>
                <button
                  onClick={() => setActiveTab('register')}
                  className={`pb-3 text-sm font-medium border-b-2 transition-colors ${
                    activeTab === 'register'
                      ? 'border-blue-600 text-blue-600'
                      : 'border-transparent text-gray-500 hover:text-gray-700 hover:border-gray-300'
                  }`}
                >
                  Register New RP
                </button>
              </nav>
            </div>

            {/* ==================== LIST TAB ==================== */}
            {activeTab === 'list' && (
              <div>
                {rpList.length === 0 && (
                  <div className="text-center py-8 text-gray-400">
                    <ShieldCheckIcon className="w-12 h-12 mx-auto mb-3 text-gray-300" />
                    <p>No relying parties registered yet.</p>
                    <p className="text-sm mt-1">
                      Switch to the &ldquo;Register New RP&rdquo; tab to get
                      started.
                    </p>
                  </div>
                )}

                {rpList.length > 0 && (
                  <div className="space-y-3">
                    {rpList.map((rp) => (
                      <div
                        key={rp.id}
                        className={`border rounded-lg bg-white transition-all ${
                          expandedRp === rp.id
                            ? 'border-blue-300 ring-1 ring-blue-200'
                            : 'border-gray-200'
                        }`}
                      >
                        {/* Summary row */}
                        <button
                          onClick={() => handleRpClick(rp.id)}
                          className="w-full text-left p-4 flex items-center justify-between hover:bg-gray-50 transition-colors rounded-t-lg"
                        >
                          <div className="flex items-center gap-3 min-w-0">
                            {expandedRp === rp.id ? (
                              <ChevronDownIcon className="w-4 h-4 text-gray-400 flex-shrink-0" />
                            ) : (
                              <ChevronRightIcon className="w-4 h-4 text-gray-400 flex-shrink-0" />
                            )}
                            <div className="min-w-0">
                              <div className="flex items-center gap-2">
                                <span className="font-medium text-sm text-gray-900">
                                  {rp.legalName}
                                </span>
                                <RpStatusBadge status={rp.status} />
                              </div>
                              <div className="flex items-center gap-3 text-xs text-gray-500 mt-0.5">
                                <span>{rp.domain}</span>
                                <span>{rp.country}</span>
                                <span>
                                  {rp.hasCertificate
                                    ? `Cert expires ${formatDate(rp.certificateExpiry)}`
                                    : 'No certificate'}
                                </span>
                              </div>
                            </div>
                          </div>
                          <div className="flex items-center gap-2 flex-shrink-0 ml-3">
                            {rp.hasCertificate ? (
                              <ShieldCheckIcon className="w-5 h-5 text-emerald-500" />
                            ) : (
                              <ShieldCheckIcon className="w-5 h-5 text-gray-300" />
                            )}
                          </div>
                        </button>

                        {/* Detail panel */}
                        {expandedRp === rp.id && (
                          <div className="border-t border-blue-200 bg-blue-50/30 p-5">
                            {detailLoading === rp.id ? (
                              <div className="flex justify-center py-6">
                                <div className="animate-spin rounded-full h-8 w-8 border-b-2 border-blue-500"></div>
                              </div>
                            ) : rpDetail[rp.id] ? (
                              <RpDetailPanel
                                detail={rpDetail[rp.id]}
                                formatDate={formatDate}
                                onGenerateCert={() =>
                                  handleGenerateCert(rp.id)
                                }
                                onToggleStatus={() =>
                                  handleToggleStatus(rp.id, rp.status)
                                }
                                onDelete={() => handleDelete(rp.id)}
                                generatingCert={generatingCert === rp.id}
                                deletingRp={deletingRp === rp.id}
                                togglingStatus={togglingStatus === rp.id}
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
                    Register New Relying Party
                  </h2>
                  <p className="text-sm text-gray-600 mb-4">
                    Register a new relying party for EUDI wallet verification.
                    An X.509 access certificate will be generated after
                    registration.
                  </p>

                  <div className="space-y-4">
                    <div className="grid grid-cols-1 sm:grid-cols-2 gap-4">
                      <InputField
                        value={formLegalName}
                        onChange={setFormLegalName}
                        type="text"
                        name="legalName"
                        label="Legal Name *"
                        placeholder="Acme Corp"
                        showLabel={true}
                      />
                      <InputField
                        value={formTradeName}
                        onChange={setFormTradeName}
                        type="text"
                        name="tradeName"
                        label="Trade Name"
                        placeholder="Acme"
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
                        placeholder="verifier.acme.com"
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
                        value={formEmail}
                        onChange={setFormEmail}
                        type="email"
                        name="contactEmail"
                        label="Contact Email *"
                        placeholder="admin@acme.com"
                        showLabel={true}
                      />
                      <InputField
                        value={formPhone}
                        onChange={setFormPhone}
                        type="text"
                        name="contactPhone"
                        label="Contact Phone"
                        placeholder="+61 2 1234 5678"
                        showLabel={true}
                      />
                    </div>

                    <InputField
                      value={formRegNumber}
                      onChange={setFormRegNumber}
                      type="text"
                      name="registrationNumber"
                      label="Registration Number"
                      placeholder="ACN 123 456 789"
                      showLabel={true}
                    />

                    <InputField
                      value={formAddress}
                      onChange={setFormAddress}
                      type="text"
                      name="contactAddress"
                      label="Address *"
                      placeholder="123 George St, Sydney NSW 2000"
                      showLabel={true}
                    />

                    <div>
                      <label className="block text-sm font-medium text-gray-700 mb-1">
                        Intended Data Use
                      </label>
                      <textarea
                        value={formIntendedUse}
                        onChange={(e) => setFormIntendedUse(e.target.value)}
                        maxLength={500}
                        rows={3}
                        className="w-full px-3 py-2 border border-gray-300 rounded-lg text-sm focus:outline-none focus:ring-2 focus:ring-blue-500 focus:border-transparent resize-none"
                        placeholder="Describe what personal data this RP will request and for what purpose..."
                      />
                      <p className="text-xs text-gray-400 mt-1 text-right">
                        {formIntendedUse.length}/500
                      </p>
                    </div>

                    {/* Data Protection & Compliance */}
                    <div className="border-t border-gray-200 pt-4 mt-2">
                      <h3 className="text-sm font-semibold text-gray-700 mb-3">
                        Data Protection & Compliance
                      </h3>

                      <div className="space-y-4">
                        <InputField
                          value={formPrivacyPolicyUrl}
                          onChange={setFormPrivacyPolicyUrl}
                          type="url"
                          name="privacyPolicyUrl"
                          label="Privacy Policy URL *"
                          placeholder="https://acme.com/privacy"
                          showLabel={true}
                        />

                        <div className="grid grid-cols-1 sm:grid-cols-2 gap-4">
                          <div>
                            <label className="block text-sm font-medium text-gray-700 mb-1">
                              Data Retention Period *
                            </label>
                            <select
                              value={formDataRetention}
                              onChange={(e) => setFormDataRetention(e.target.value)}
                              className="w-full px-3 py-2 border border-gray-300 rounded-lg text-sm focus:outline-none focus:ring-2 focus:ring-blue-500 focus:border-transparent bg-white"
                            >
                              <option value="">Select retention period...</option>
                              <option value="30_DAYS">30 Days</option>
                              <option value="90_DAYS">90 Days</option>
                              <option value="1_YEAR">1 Year</option>
                              <option value="3_YEARS">3 Years</option>
                              <option value="DURATION_OF_CONTRACT">Duration of Contract</option>
                            </select>
                          </div>

                          <div>
                            <label className="block text-sm font-medium text-gray-700 mb-1">
                              Lawful Basis (GDPR Art. 6) *
                            </label>
                            <select
                              value={formLawfulBasis}
                              onChange={(e) => setFormLawfulBasis(e.target.value)}
                              className="w-full px-3 py-2 border border-gray-300 rounded-lg text-sm focus:outline-none focus:ring-2 focus:ring-blue-500 focus:border-transparent bg-white"
                            >
                              <option value="">Select lawful basis...</option>
                              <option value="CONSENT">Consent (Art. 6(1)(a))</option>
                              <option value="CONTRACT">Contract (Art. 6(1)(b))</option>
                              <option value="LEGAL_OBLIGATION">Legal Obligation (Art. 6(1)(c))</option>
                              <option value="VITAL_INTEREST">Vital Interest (Art. 6(1)(d))</option>
                              <option value="PUBLIC_TASK">Public Task (Art. 6(1)(e))</option>
                              <option value="LEGITIMATE_INTEREST">Legitimate Interest (Art. 6(1)(f))</option>
                            </select>
                          </div>
                        </div>

                        <div className="flex items-start gap-3 bg-blue-50 border border-blue-200 rounded-lg p-4">
                          <input
                            type="checkbox"
                            id="dpaAcknowledged"
                            checked={formDpaAcknowledged}
                            onChange={(e) => setFormDpaAcknowledged(e.target.checked)}
                            className="mt-1 h-4 w-4 text-blue-600 border-gray-300 rounded focus:ring-blue-500"
                          />
                          <label htmlFor="dpaAcknowledged" className="text-sm text-gray-700">
                            <span className="font-medium">Data Protection Acknowledgment *</span>
                            <br />
                            <span className="text-xs text-gray-500">
                              I acknowledge that this relying party will process personal data in
                              accordance with GDPR and the eIDAS 2.0 Implementing Act. A Data
                              Protection Impact Assessment (DPIA) has been or will be completed
                              where required.
                            </span>
                          </label>
                        </div>
                      </div>
                    </div>

                    <Button
                      onClick={handleRegister}
                      loading={registering}
                      disabled={
                        !formLegalName.trim() ||
                        !formDomain.trim() ||
                        !formCountry.trim() ||
                        !formEmail.trim() ||
                        !formAddress.trim() ||
                        !formPrivacyPolicyUrl.trim() ||
                        !formDataRetention ||
                        !formLawfulBasis ||
                        !formDpaAcknowledged
                      }
                      color="primary"
                    >
                      Register Relying Party
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

// -- RP Detail Panel --

function RpDetailPanel({
  detail,
  formatDate,
  onGenerateCert,
  onToggleStatus,
  onDelete,
  generatingCert,
  deletingRp,
  togglingStatus,
}: {
  detail: RpDetail;
  formatDate: (d?: string) => string;
  onGenerateCert: () => void;
  onToggleStatus: () => void;
  onDelete: () => void;
  generatingCert: boolean;
  deletingRp: boolean;
  togglingStatus: boolean;
}) {
  return (
    <div>
      {/* Info grid */}
      <div className="grid grid-cols-1 sm:grid-cols-2 gap-x-6 gap-y-3 mb-5">
        <InfoRow label="Client ID" value={detail.clientId} mono />
        <InfoRow label="Domain" value={detail.domain} />
        <InfoRow label="Country" value={detail.country} />
        <InfoRow label="Contact Email" value={detail.contactEmail} />
        {detail.contactPhone && (
          <InfoRow label="Phone" value={detail.contactPhone} />
        )}
        {detail.registrationNumber && (
          <InfoRow label="Registration #" value={detail.registrationNumber} />
        )}
        {detail.tradeName && (
          <InfoRow label="Trade Name" value={detail.tradeName} />
        )}
        {detail.contactAddress && (
          <InfoRow label="Address" value={detail.contactAddress} />
        )}
        <InfoRow label="Created" value={formatDate(detail.createdAt)} />
        <InfoRow label="Updated" value={formatDate(detail.updatedAt)} />
      </div>

      {/* Intended Use */}
      {detail.intendedUse && (
        <div className="mb-5">
          <h4 className="text-xs font-medium text-gray-500 uppercase tracking-wider mb-1">
            Intended Data Use
          </h4>
          <p className="text-sm text-gray-700 bg-white rounded p-3 border border-gray-200">
            {detail.intendedUse}
          </p>
        </div>
      )}

      {/* Data Protection & Compliance */}
      <div className="mb-5 border border-gray-200 rounded-lg bg-white p-4">
        <h4 className="text-xs font-medium text-gray-500 uppercase tracking-wider mb-3">
          Data Protection & Compliance
        </h4>
        <div className="grid grid-cols-1 sm:grid-cols-2 gap-x-6 gap-y-3">
          <InfoRow
            label="Privacy Policy"
            value={detail.privacyPolicyUrl || '-'}
          />
          <InfoRow
            label="Data Retention"
            value={detail.dataRetentionPeriod?.replace(/_/g, ' ') || '-'}
          />
          <InfoRow
            label="Lawful Basis (GDPR)"
            value={detail.lawfulBasis?.replace(/_/g, ' ') || '-'}
          />
          <InfoRow
            label="DPA Acknowledged"
            value={detail.dpaAcknowledged ? 'Yes' : 'No'}
          />
        </div>
      </div>

      {/* Certificate section */}
      <div className="mb-5 border border-gray-200 rounded-lg bg-white p-4">
        <h4 className="text-sm font-semibold text-gray-800 mb-3 flex items-center gap-2">
          <KeyIcon className="w-4 h-4" />
          Access Certificate
        </h4>

        {detail.certificate ? (
          <div className="space-y-2 text-sm">
            <div className="grid grid-cols-1 sm:grid-cols-2 gap-x-4 gap-y-1">
              <InfoRow label="Subject" value={detail.certificate.subject} mono />
              <InfoRow
                label="Expires"
                value={formatDate(detail.certificate.notAfter)}
              />
              <InfoRow
                label="Fingerprint"
                value={detail.certificate.fingerprint}
                mono
              />
              <InfoRow
                label="Serial"
                value={detail.certificate.serialNumber}
                mono
              />
            </div>
            <div className="flex gap-2 mt-3">
              <button
                onClick={onGenerateCert}
                disabled={generatingCert}
                className="text-sm text-blue-600 hover:text-blue-800 font-medium transition-colors disabled:opacity-50"
              >
                {generatingCert ? 'Regenerating...' : 'Regenerate Certificate'}
              </button>
            </div>
          </div>
        ) : (
          <div className="text-center py-4">
            <p className="text-sm text-gray-500 mb-3">
              No access certificate generated yet.
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
              Generate EC P-256 Certificate
            </button>
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
          disabled={deletingRp}
          className="inline-flex items-center gap-1.5 text-sm text-red-500 hover:text-red-700 font-medium transition-colors disabled:opacity-50"
        >
          {deletingRp ? (
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
