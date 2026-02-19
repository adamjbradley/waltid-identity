import React, {useEffect, useState} from "react";
import {useRouter} from "next/router";
import {ArrowLeftIcon} from "@heroicons/react/24/outline";
import {EnvContext} from "@/pages/_app";
import nextConfig from "@/next.config";
import axios from "axios";

interface TenantSummary {
  id: string;
  legalName: string;
  country: string;
  status: string;
  hasCertificate: boolean;
}

interface IssuerDetail {
  id: string;
  legalName: string;
  country: string;
  credentialConfigurations: {
    credentials: { configId: string; format: string; vct?: string }[];
  };
}

const COUNTRY_META: Record<string, { name: string; flag: string }> = {
  AU: { name: 'Australia', flag: '🇦🇺' },
  DE: { name: 'Germany', flag: '🇩🇪' },
  FR: { name: 'France', flag: '🇫🇷' },
  GB: { name: 'United Kingdom', flag: '🇬🇧' },
  IN: { name: 'India', flag: '🇮🇳' },
  SG: { name: 'Singapore', flag: '🇸🇬' },
  US: { name: 'United States', flag: '🇺🇸' },
};

const FORMAT_LABELS: Record<string, string> = {
  'mso_mdoc': 'mDoc',
  'dc+sd-jwt': 'DC+SD-JWT',
};

const CREDENTIAL_TITLES: Record<string, string> = {
  'eu.europa.ec.eudi.pid.1': 'EU Personal ID (mDoc)',
  'org.iso.18013.5.1.mDL': 'Mobile Driving License',
  'eu.europa.ec.eudi.pid_vc_sd_jwt': 'EU Personal ID (SD-JWT)',
  'PaymentWalletAttestation': 'Payment Wallet Attestation',
};

const formatBadgeColor: Record<string, string> = {
  'mDoc': 'bg-purple-100 text-purple-800',
  'DC+SD-JWT': 'bg-green-100 text-green-800',
};

interface CountryGroup {
  code: string;
  name: string;
  flag: string;
  issuers: IssuerDetail[];
}

export default function Explore() {
  const router = useRouter();
  const env = React.useContext(EnvContext);
  const issuerRegistrarEnabled = (env.NEXT_PUBLIC_ISSUER_REGISTRAR_ENABLED ?? 'false') === 'true';

  const [countryGroups, setCountryGroups] = useState<CountryGroup[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(false);

  useEffect(() => {
    if (!issuerRegistrarEnabled) {
      setLoading(false);
      return;
    }
    const apiBase = env.NEXT_PUBLIC_ISSUER ?? nextConfig.publicRuntimeConfig?.NEXT_PUBLIC_ISSUER;
    if (!apiBase) {
      setLoading(false);
      setError(true);
      return;
    }

    axios.get(`${apiBase}/admin/issuer`)
      .then(async (res) => {
        const active = (res.data as TenantSummary[]).filter(
          (t) => t.status === 'ACTIVE' && t.hasCertificate
        );
        if (active.length === 0) {
          setCountryGroups([]);
          setLoading(false);
          return;
        }

        const details = await Promise.all(
          active.map((t) =>
            axios.get(`${apiBase}/admin/issuer/${t.id}`).then((r) => r.data as IssuerDetail)
          )
        );

        const grouped: Record<string, IssuerDetail[]> = {};
        for (const d of details) {
          if (!grouped[d.country]) grouped[d.country] = [];
          grouped[d.country].push(d);
        }

        const groups: CountryGroup[] = Object.entries(grouped)
          .map(([code, issuers]) => {
            const meta = COUNTRY_META[code];
            return {
              code,
              name: meta?.name ?? code,
              flag: meta?.flag ?? '',
              issuers,
            };
          })
          .sort((a, b) => a.name.localeCompare(b.name));

        setCountryGroups(groups);
        setLoading(false);
      })
      .catch(() => {
        setError(true);
        setLoading(false);
      });
  }, [issuerRegistrarEnabled, env.NEXT_PUBLIC_ISSUER]);

  function handleIssue(configId: string, issuerId: string) {
    router.push(`/credentials?ids=${configId}&issuerId=${issuerId}`);
  }

  if (!issuerRegistrarEnabled) {
    return (
      <div className="min-h-screen bg-gray-50">
        <div className="max-w-4xl mx-auto px-4 py-10">
          <button
            onClick={() => router.push('/')}
            className="flex items-center gap-1.5 text-sm text-gray-500 hover:text-gray-700 mb-8 transition-colors"
          >
            <ArrowLeftIcon className="w-4 h-4" />
            Back to Portal
          </button>
          <p className="text-gray-600">Issuer registrar is not enabled.</p>
        </div>
      </div>
    );
  }

  return (
    <div className="min-h-screen bg-gray-50">
      <div className="max-w-4xl mx-auto px-4 py-10">
        <button
          onClick={() => router.push('/')}
          className="flex items-center gap-1.5 text-sm text-gray-500 hover:text-gray-700 mb-8 transition-colors"
        >
          <ArrowLeftIcon className="w-4 h-4" />
          Back to Portal
        </button>

        <h1 className="text-3xl font-bold text-gray-900 mb-2">
          Explore Credentials by Country
        </h1>
        <p className="text-gray-600 mb-10">
          Browse available credentials from issuers across different countries
        </p>

        {loading && (
          <div className="grid grid-cols-1 md:grid-cols-2 gap-6">
            {[1, 2, 3, 4].map((i) => (
              <div key={i} className="bg-white rounded-xl border border-gray-200 p-6 shadow-sm animate-pulse">
                <div className="flex items-center gap-3 mb-4">
                  <div className="w-8 h-8 bg-gray-200 rounded" />
                  <div className="h-6 w-32 bg-gray-200 rounded" />
                </div>
                <div className="space-y-3">
                  <div className="h-10 bg-gray-100 rounded-lg" />
                  <div className="h-10 bg-gray-100 rounded-lg" />
                </div>
              </div>
            ))}
          </div>
        )}

        {!loading && (error || countryGroups.length === 0) && (
          <p className="text-gray-600">No issuers available.</p>
        )}

        {!loading && !error && countryGroups.length > 0 && (
          <div className="grid grid-cols-1 md:grid-cols-2 gap-6">
            {countryGroups.map((group) =>
              group.issuers.map((issuer) => {
                const credentials = issuer.credentialConfigurations?.credentials ?? [];
                return (
                  <div
                    key={issuer.id}
                    className="bg-white rounded-xl border border-gray-200 p-6 shadow-sm hover:shadow-md transition-shadow"
                  >
                    <div className="flex items-center gap-3 mb-1">
                      {group.flag && <span className="text-2xl">{group.flag}</span>}
                      <h2 className="text-xl font-semibold text-gray-900">
                        {group.name}
                      </h2>
                    </div>
                    <p className="text-sm text-gray-500 mb-4 ml-10">
                      {issuer.legalName}
                    </p>

                    <div className="space-y-3">
                      {credentials.map((cred) => {
                        const formatLabel = FORMAT_LABELS[cred.format] ?? cred.format;
                        const title = CREDENTIAL_TITLES[cred.configId] ?? cred.configId;
                        return (
                          <div
                            key={cred.configId}
                            className="flex items-center justify-between py-2 px-3 rounded-lg bg-gray-50 hover:bg-gray-100 transition-colors"
                          >
                            <div className="flex items-center gap-2 min-w-0">
                              <span className="text-sm font-medium text-gray-800 truncate">
                                {title}
                              </span>
                              <span className={`px-2 py-0.5 text-xs font-medium rounded-full whitespace-nowrap ${formatBadgeColor[formatLabel] || 'bg-gray-100 text-gray-700'}`}>
                                {formatLabel}
                              </span>
                            </div>
                            <button
                              onClick={() => handleIssue(cred.configId, issuer.id)}
                              className="ml-3 px-3 py-1 text-sm font-medium text-blue-600 hover:text-blue-800 hover:bg-blue-50 rounded-md transition-colors whitespace-nowrap"
                            >
                              Issue &rarr;
                            </button>
                          </div>
                        );
                      })}
                      {credentials.length === 0 && (
                        <p className="text-sm text-gray-400 italic">No credentials configured</p>
                      )}
                    </div>
                  </div>
                );
              })
            )}
          </div>
        )}
      </div>
    </div>
  );
}
