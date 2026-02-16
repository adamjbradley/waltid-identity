import RowCredential from "@/components/walt/credential/RowCredential";
import Dropdown from "@/components/walt/forms/Dropdown";
import {AuthenticationMethods, AvailableCredential, VpProfiles, getCountryCredentialData} from "@/types/credentials";
import Checkbox from "@/components/walt/forms/Checkbox";
import InputField from "@/components/walt/forms/Input";
import Button from "@/components/walt/button/Button";
import WaltIcon from "@/components/walt/logo/WaltIcon";
import {CredentialsContext, EnvContext} from "@/pages/_app";
import React, {useState, useEffect} from "react";
import {useRouter} from "next/router";
import {getOfferUrl} from "@/utils/getOfferUrl";
import {sendToWebWallet} from "@/utils/sendToWebWallet";
import nextConfig from "@/next.config";
import {LockClosedIcon, BuildingLibraryIcon} from "@heroicons/react/24/outline";
import axios from "axios";

interface IssuerTenantSummary {
  id: string;
  legalName: string;
  country: string;
  status: string;
  hasCertificate: boolean;
  credentialCount: number;
}

export default function IssueSection() {
  const env = React.useContext(EnvContext);
  const [AvailableCredentials] = React.useContext(CredentialsContext);

  const [selectedAuthenticationMethod, setSelectedAuthenticationMethod] =
    React.useState(AuthenticationMethods[0]);
  const [requirePin, setRequirePin] = useState<boolean>(false);
  const [pin, setPin] = useState<string>('0235');
  const [requireVpRequestValue, setRequireVpRequestValue] =
    useState<boolean>(false);
  const [vpRequestValue, setVpRequestValue] = useState<string>(
    'NaturalPersonVerifiableID'
  );
  const [requireVpProfile, setRequireVpProfile] = useState<boolean>(false);
  const [selectedVpProfile, setSelectedVpProfile] = React.useState(
    VpProfiles[0]
  );
  const [useServerKeys, setUseServerKeys] = useState<boolean>(true);

  // Multi-tenant issuer selection
  const issuerRegistrarEnabled = (env.NEXT_PUBLIC_ISSUER_REGISTRAR_ENABLED ?? 'false') === 'true';
  const [tenants, setTenants] = useState<IssuerTenantSummary[]>([]);
  const [selectedTenantId, setSelectedTenantId] = useState<string>('');
  const [tenantCredentialKeys, setTenantCredentialKeys] = useState<string[]>([]);
  // Map of tenantId -> list of credential config identifiers (configId, vct, doctype)
  const [tenantCredentialConfigs, setTenantCredentialConfigs] = useState<
    Record<string, { configId: string; format: string; vct?: string; doctype?: string }[]>
  >({});

  const router = useRouter();
  const params = router.query;

  const idsParam = (params as unknown as { ids: string }).ids;
  const idsToIssue = idsParam ? idsParam.split(',') : [];
  const [credentialsToIssue, setCredentialsToIssue] = useState<
    AvailableCredential[]
  >([]);

  // Check if any credential has mDoc format selected (server keys only supported for mDoc)
  const hasEudiFormat = credentialsToIssue.some(
    (cred) => cred.selectedFormat === 'mDoc (ISO 18013-5)'
  );

  // Fetch tenants and their credential configs when issuer registrar is enabled
  useEffect(() => {
    if (!issuerRegistrarEnabled) return;
    const proxyBase = '/api/proxy/issuer';
    axios.get(`${proxyBase}/admin/issuer`).then(async (res) => {
      const active = (res.data as IssuerTenantSummary[]).filter(
        (t) => t.status === 'ACTIVE' && t.hasCertificate
      );
      setTenants(active);

      // Batch-fetch credential configs for each tenant
      const configMap: Record<string, { configId: string; format: string; vct?: string; doctype?: string }[]> = {};
      await Promise.all(
        active.map(async (tenant) => {
          try {
            const detail = await axios.get(`${proxyBase}/admin/issuer/${tenant.id}`);
            const configs = detail.data?.credentialConfigurations;
            const entries: { configId: string; format: string; vct?: string; doctype?: string }[] = [];
            if (Array.isArray(configs)) {
              // Direct array format: [{configId, format, vct?, doctype?}]
              for (const c of configs) {
                entries.push({ configId: c.configId, format: c.format, vct: c.vct, doctype: c.doctype });
              }
            } else if (configs && typeof configs === 'object') {
              // Legacy nested format: {credentials: [{configId, format, ...}]}
              if (Array.isArray((configs as any).credentials)) {
                for (const c of (configs as any).credentials) {
                  entries.push({ configId: c.configId, format: c.format, vct: c.vct, doctype: c.doctype });
                }
              } else {
                // Standard object format: {configId: {format, vct?, doctype?}}
                for (const [key, val] of Object.entries(configs as Record<string, any>)) {
                  entries.push({ configId: key, format: val.format, vct: val.vct, doctype: val.doctype });
                }
              }
            }
            configMap[tenant.id] = entries;
          } catch {
            configMap[tenant.id] = [];
          }
        })
      );
      setTenantCredentialConfigs(configMap);

      // If issuerId was passed via query param, pre-select it
      const qIssuerId = params.issuerId as string | undefined;
      if (qIssuerId && active.some((t) => t.id === qIssuerId)) {
        setSelectedTenantId(qIssuerId);
      }
    }).catch(() => {});
  }, [issuerRegistrarEnabled]);

  // Filter tenants to only those whose credential configs match ALL idsToIssue
  const filteredTenants = React.useMemo(() => {
    if (!issuerRegistrarEnabled || idsToIssue.length === 0) return tenants;
    return tenants.filter((tenant) => {
      const configs = tenantCredentialConfigs[tenant.id];
      if (!configs || configs.length === 0) return false;
      return idsToIssue.every((id) =>
        configs.some(
          (c) => c.configId === id || c.vct === id || c.doctype === id
        )
      );
    });
  }, [tenants, tenantCredentialConfigs, idsToIssue.join(','), issuerRegistrarEnabled]);

  // Fetch tenant credential keys when a tenant is selected
  useEffect(() => {
    if (!selectedTenantId || !issuerRegistrarEnabled) {
      setTenantCredentialKeys([]);
      return;
    }
    const configs = tenantCredentialConfigs[selectedTenantId];
    if (configs) {
      setTenantCredentialKeys(configs.map((c) => c.configId));
    } else {
      setTenantCredentialKeys([]);
    }
  }, [selectedTenantId, issuerRegistrarEnabled, tenantCredentialConfigs]);

  React.useEffect(() => {
    if (!idsToIssue.length) return;
    setCredentialsToIssue(
      AvailableCredentials.filter((cred) => {
        if (!cred?.id) return false;
        for (const id of idsToIssue) {
          if (id === cred.id) {
            return true;
          }
        }
        return false;
      })
    );
  }, [AvailableCredentials]);

  // Apply country-specific claims when tenant selection changes
  useEffect(() => {
    if (!selectedTenantId || !issuerRegistrarEnabled) return;
    const tenant = tenants.find((t) => t.id === selectedTenantId);
    if (!tenant?.country) return;
    setCredentialsToIssue((prev) =>
      prev.map((cred) => {
        const countryData = getCountryCredentialData(tenant.country, cred.id);
        if (!countryData) return cred;
        return { ...cred, offer: countryData.offer, defaultClaims: countryData.defaultClaims };
      })
    );
  }, [selectedTenantId, tenants]);

  function handleCancel() {
    router.push('/');
  }

  async function handleIssue() {
    const issuerId = selectedTenantId || (params.issuerId as string | undefined);

    if (checkCallbackUrlParameter()) {
      const offer = await getOfferUrl(
        credentialsToIssue,
        env.NEXT_PUBLIC_VC_REPO ??
          nextConfig.publicRuntimeConfig!.NEXT_PUBLIC_VC_REPO,
        env.NEXT_PUBLIC_ISSUER ??
          nextConfig.publicRuntimeConfig!.NEXT_PUBLIC_ISSUER,
        undefined, // authenticationMethod
        undefined, // vpRequestValue
        undefined, // vpProfile
        hasEudiFormat && useServerKeys,
        issuerId
      );
      sendToWebWallet(
        decodeURI(params.callback!.toString()),
        'api/siop/initiateIssuance',
        offer.data
      );
    } else {
      console.log('show qr-offer');
      localStorage.setItem('offer', JSON.stringify(credentialsToIssue));
      let url = `/offer?ids=${idsToIssue.join(',')}`;
      url = url + `&authenticationMethod=${selectedAuthenticationMethod}`;
      if (requireVpRequestValue && vpRequestValue?.trim().length) {
        url = url + `&vpRequestValue=${vpRequestValue}`;
      }
      if (requireVpProfile && selectedVpProfile?.trim().length) {
        url = url + `&vpProfile=${selectedVpProfile}`;
      }
      if (hasEudiFormat && useServerKeys) {
        url = url + `&useServerKeys=true`;
      }
      if (issuerId) {
        url = url + `&issuerId=${issuerId}`;
        const tenant = tenants.find((t) => t.id === issuerId);
        if (tenant) {
          url = url + `&issuerName=${encodeURIComponent(tenant.legalName)}`;
        }
      }

      await router.push(url);
    }
  }

  function checkCallbackUrlParameter(): Boolean {
    const callback = params.callback;
    return !(callback === undefined || callback === null || callback === '');
  }

  if (params.ids === undefined) {
    return <Button onClick={() => router.push('/')}>Select Credentials</Button>;
  }

  return (
    <>
      <h1 className="text-3xl text-gray-900 text-center font-bold">
        Customise Issuance
      </h1>
      <p className="mt-3 text-gray-600">
        Adjust credential data, format and issuance security
      </p>

      {issuerRegistrarEnabled && (
        <div className="mt-6 p-4 bg-blue-50 border border-blue-200 rounded-lg">
          <div className="flex items-center gap-2 mb-2">
            <BuildingLibraryIcon className="w-5 h-5 text-blue-600" />
            <label className="text-sm font-medium text-blue-800">Issuing as</label>
          </div>
          {filteredTenants.length > 0 ? (
            <>
              <select
                data-testid="tenant-select"
                value={selectedTenantId}
                onChange={(e) => setSelectedTenantId(e.target.value)}
                className="w-full px-3 py-2 border border-blue-300 rounded-md text-sm bg-white focus:ring-2 focus:ring-blue-500 focus:border-blue-500"
              >
                <option value="">Select an issuer...</option>
                {filteredTenants.map((t) => (
                  <option key={t.id} value={t.id}>
                    {t.legalName} ({t.country})
                  </option>
                ))}
              </select>
              {selectedTenantId && tenantCredentialKeys.length > 0 && (
                <p className="mt-2 text-xs text-blue-600">
                  Tenant has {tenantCredentialKeys.length} credential configuration{tenantCredentialKeys.length !== 1 ? 's' : ''}
                </p>
              )}
            </>
          ) : (
            <p className="text-sm text-gray-500">
              {tenants.length === 0 ? 'Loading issuers...' : 'No issuers available for this credential'}
            </p>
          )}
        </div>
      )}

      <hr className="mt-8" />
      <h3 className="text-gray-500 text-left mt-2 font-semibold">
        Credential Configuration
      </h3>
      <div className="mt-12"></div>
      {/*START*/}
      <div className="flex flex-col gap-6">
        {credentialsToIssue.map((credential) => (
          <RowCredential
            credentialToEdit={credential}
            credentialsToIssue={credentialsToIssue}
            setCredentialsToIssue={setCredentialsToIssue}
            key={credential.title}
          />
        ))}
      </div>
      {/*END*/}
      <div className="mt-10"></div>
      <hr className="text-green-900 border border-[0.5px] border-gray-100" />
      <h3 className="text-gray-500 text-left mt-2 font-semibold">
        Security Settings
      </h3>
      <div className="mt-5 flex flex-col sm:flex-row justify-between">
        <div className="mt-2">
          <div className="flex flex-row gap-2 items-center">
            <LockClosedIcon className="h-5" />
            {/* <div className="hidden sm:block bg-primary-400 w-[45px] h-[28px] rounded-lg"></div> */}
            <span> Authentication Method</span>
          </div>
        </div>
        <Dropdown
          values={AuthenticationMethods}
          selected={selectedAuthenticationMethod}
          setSelected={setSelectedAuthenticationMethod}
        />
      </div>
      <div className="mt-3 flex flex-col sm:flex-row justify-between">
        <div className="">
          <Checkbox value={requirePin} onChange={setRequirePin}>
            Require User Pin
          </Checkbox>
        </div>
        <InputField
          error={false}
          label="Test"
          value={pin}
          name="test"
          type="id"
          placeholder=""
          onChange={setPin}
        />
      </div>
      <div className="mt-1 flex flex-col sm:flex-row justify-between">
        <div className="">
          <Checkbox
            value={requireVpRequestValue}
            onChange={setRequireVpRequestValue}
          >
            VP Token Requested Value
          </Checkbox>
        </div>
        <InputField
          error={false}
          label="Test"
          value={vpRequestValue}
          name="test"
          type="id"
          placeholder=""
          onChange={setVpRequestValue}
        />
      </div>

      <div className="mt-1 flex flex-col sm:flex-row justify-between">
        <div className="">
          <Checkbox value={requireVpProfile} onChange={setRequireVpProfile}>
            VP Token Requested Profile
          </Checkbox>
        </div>
        <Dropdown
          values={VpProfiles}
          selected={selectedVpProfile}
          setSelected={setSelectedVpProfile}
        />
      </div>

      {hasEudiFormat && (
        <div className="mt-3 flex flex-col sm:flex-row justify-between items-start sm:items-center">
          <div className="">
            <Checkbox value={useServerKeys} onChange={setUseServerKeys}>
              Use server signing keys
            </Checkbox>
          </div>
          <span className="text-sm text-gray-500 mt-1 sm:mt-0">
            Recommended for EUDI wallets
          </span>
        </div>
      )}

      <hr className="my-5" />
      <div className="flex flex-row justify-center gap-3 mt-14">
        <Button onClick={handleCancel} style="link" color="secondary">
          Cancel
        </Button>
        <Button
          disabled={
            !(
              credentialsToIssue.length > 0 &&
              (credentialsToIssue.length < 2 ||
                credentialsToIssue.filter(
                  (cred) =>
                    cred.selectedFormat === 'SD-JWT + W3C VC' ||
                    cred.selectedFormat === 'SD-JWT + IETF SD-JWT VC'
                ).length === credentialsToIssue.length ||
                credentialsToIssue.filter(
                  (cred) =>
                    !cred.selectedFormat ||
                    cred.selectedFormat === 'JWT + W3C VC'
                ).length === credentialsToIssue.length)
            )
          }
          onClick={handleIssue}
        >
          Issue
        </Button>
      </div>
      <div className="flex flex-col items-center mt-12">
        <div className="flex flex-row gap-2 items-center content-center text-sm text-center text-gray-500">
          <p className="">Secured by walt.id</p>
          <WaltIcon height={15} width={15} type="gray" />
        </div>
      </div>
    </>
  );
}
