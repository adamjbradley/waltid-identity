import React, {useEffect, useState} from "react";
import {useRouter} from "next/router";
import {ArrowLeftIcon} from "@heroicons/react/24/outline";
import {EnvContext} from "@/pages/_app";
import {getAllCountries, CountryEntry} from "@/types/credentials";
import nextConfig from "@/next.config";
import axios from "axios";

interface TenantSummary {
  id: string;
  legalName: string;
  country: string;
  status: string;
  hasCertificate: boolean;
}

export default function Explore() {
  const router = useRouter();
  const env = React.useContext(EnvContext);
  const countries = getAllCountries();

  const issuerRegistrarEnabled = (env.NEXT_PUBLIC_ISSUER_REGISTRAR_ENABLED ?? 'false') === 'true';
  const [tenantsByCountry, setTenantsByCountry] = useState<Record<string, TenantSummary[]>>({});

  useEffect(() => {
    if (!issuerRegistrarEnabled) return;
    const apiBase = env.NEXT_PUBLIC_ISSUER ?? nextConfig.publicRuntimeConfig?.NEXT_PUBLIC_ISSUER;
    if (!apiBase) return;
    axios.get(`${apiBase}/admin/issuer`).then((res) => {
      const active = (res.data as TenantSummary[]).filter(
        (t) => t.status === 'ACTIVE' && t.hasCertificate
      );
      const grouped: Record<string, TenantSummary[]> = {};
      for (const t of active) {
        if (!grouped[t.country]) grouped[t.country] = [];
        grouped[t.country].push(t);
      }
      setTenantsByCountry(grouped);
    }).catch(() => {});
  }, [issuerRegistrarEnabled, env.NEXT_PUBLIC_ISSUER]);

  function handleIssue(credentialId: string, country: string) {
    const tenants = tenantsByCountry[country];
    const tenantId = tenants?.[0]?.id;
    let url = `/credentials?ids=${credentialId}`;
    if (tenantId) url += `&issuerId=${tenantId}`;
    router.push(url);
  }

  function getIssuerName(country: CountryEntry): string | null {
    const tenants = tenantsByCountry[country.code];
    if (tenants && tenants.length > 0) return tenants[0].legalName;
    return null;
  }

  const formatBadgeColor: Record<string, string> = {
    'mDoc': 'bg-purple-100 text-purple-800',
    'DC+SD-JWT': 'bg-green-100 text-green-800',
  };

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

        <div className="grid grid-cols-1 md:grid-cols-2 gap-6">
          {countries.map((country) => {
            const issuerName = getIssuerName(country);
            return (
              <div
                key={country.code}
                className="bg-white rounded-xl border border-gray-200 p-6 shadow-sm hover:shadow-md transition-shadow"
              >
                <div className="flex items-center gap-3 mb-1">
                  <span className="text-2xl">{country.flag}</span>
                  <h2 className="text-xl font-semibold text-gray-900">
                    {country.name}
                  </h2>
                </div>
                {issuerName && (
                  <p className="text-sm text-gray-500 mb-4 ml-10">
                    {issuerName}
                  </p>
                )}
                {!issuerName && <div className="mb-4" />}

                <div className="space-y-3">
                  {country.credentials.map((cred) => (
                    <div
                      key={cred.id}
                      className="flex items-center justify-between py-2 px-3 rounded-lg bg-gray-50 hover:bg-gray-100 transition-colors"
                    >
                      <div className="flex items-center gap-2 min-w-0">
                        <span className="text-sm font-medium text-gray-800 truncate">
                          {cred.title}
                        </span>
                        <span className={`px-2 py-0.5 text-xs font-medium rounded-full whitespace-nowrap ${formatBadgeColor[cred.format] || 'bg-gray-100 text-gray-700'}`}>
                          {cred.format}
                        </span>
                      </div>
                      <button
                        onClick={() => handleIssue(cred.id, country.code)}
                        className="ml-3 px-3 py-1 text-sm font-medium text-blue-600 hover:text-blue-800 hover:bg-blue-50 rounded-md transition-colors whitespace-nowrap"
                      >
                        Issue &rarr;
                      </button>
                    </div>
                  ))}
                </div>
              </div>
            );
          })}
        </div>
      </div>
    </div>
  );
}
