import RowCredential from "@/components/walt/credential/RowCredential";
import PolicyListItem from "@/components/walt/policy/PolicyListItem";
import {AvailableCredential} from "@/types/credentials";
import WaltIcon from "@/components/walt/logo/WaltIcon";
import InputField from "@/components/walt/forms/Input";
import Button from "@/components/walt/button/Button";
import React, {useContext, useEffect, useState} from "react";
import {CredentialsContext, EnvContext} from "@/pages/_app";
import {useRouter} from "next/router";
import nextConfig from "@/next.config";
import axios from "axios";
import {BuildingOfficeIcon} from "@heroicons/react/24/outline";

interface RpTenantSummary {
  id: string;
  legalName: string;
  domain: string;
  country: string;
  status: string;
  hasCertificate: boolean;
}

export default function VerificationSection() {
  const router = useRouter();
  const env = useContext(EnvContext);
  const [AvailableCredentials] = useContext(CredentialsContext);

  // RP registrar state
  const rpRegistrarEnabled = (env.NEXT_PUBLIC_RP_REGISTRAR_ENABLED ?? 'false') === 'true';
  const [rpTenants, setRpTenants] = useState<RpTenantSummary[]>([]);
  const [selectedRpId, setSelectedRpId] = useState<string>('');

  const [signaturePolicy, setSignaturePolicy] = useState<boolean>(true);
  const [expiredPolicy, setExpiredPolicy] = useState<boolean>(true);
  const [notBeforePolicy, setNotBeforePolicy] = useState<boolean>(true);
  const [trustPolicy, setTrustPolicy] = useState<boolean>(true);
  const [webhookPolicy, setWebhookPolicy] = useState<boolean>(false);
  const [webhook, setWebhook] = useState<string>('');

  // Fetch RP tenants when enabled
  useEffect(() => {
    if (!rpRegistrarEnabled) return;
    const apiBase = env.NEXT_PUBLIC_VERIFIER2 ?? nextConfig.publicRuntimeConfig?.NEXT_PUBLIC_VERIFIER2;
    if (!apiBase) return;
    axios.get(`${apiBase}/admin/rp`).then((res) => {
      const active = (res.data as RpTenantSummary[]).filter(
        (t) => t.status === 'ACTIVE' && t.hasCertificate
      );
      setRpTenants(active);
    }).catch(() => {});
  }, [rpRegistrarEnabled, env.NEXT_PUBLIC_VERIFIER2]);

  function handleCancel() {
    router.push('/');
  }

  const params = router.query;

  const idsParam = (params as unknown as { ids: string }).ids;
  const idsToIssue = idsParam ? idsParam.split(',') : [];
  const [credentialsToIssue, setCredentialsToIssue] = useState<
    AvailableCredential[]
  >([]);

  React.useEffect(() => {
    setCredentialsToIssue(
      AvailableCredentials.filter((cred) => {
        for (const id of idsToIssue) {
          if (id.toString() == cred.id.toString()) {
            return true;
          }
        }
        return false;
      })
    );
  }, [AvailableCredentials]);

  function handleVerify() {
    const vps = [];
    if (signaturePolicy) {
      vps.push('signature');
    }
    if (expiredPolicy) {
      vps.push('expired');
    }
    if (notBeforePolicy) {
      vps.push('not-before');
    }
    if (trustPolicy) {
      vps.push('etsi-trusted-issuer');
    }
    if (webhookPolicy) {
      if (webhook.length == 0) {
        alert('Please enter a webhook url');
        return;
      }
      vps.push('webhook=' + webhook);
    }

    const params = new URLSearchParams();
    params.append('ids', idsToIssue.join(','));
    if (vps.length) {
      params.append('vps', vps.join(','));
    }

    params.append(
      'format',
      (credentialsToIssue[0]?.selectedFormat ?? 'JWT + W3C VC') as string
    );
    if (selectedRpId) {
      params.append('rpId', selectedRpId);
    }
    router.push(`/verify?${params.toString()}`);
  }

  if (params.ids === undefined) {
    return <Button onClick={() => router.push('/')}>Select Credentials</Button>;
  }

  return (
    <>
      <h1 className="text-3xl text-gray-900 text-center font-bold">
        Customise Verification
      </h1>
      <p className="mt-3 text-gray-600">
        Select credential format and policies which should be checked
      </p>

      {rpRegistrarEnabled && rpTenants.length > 0 && (
        <div className="mt-6 p-4 bg-blue-50 border border-blue-200 rounded-lg">
          <div className="flex items-center gap-2 mb-2">
            <BuildingOfficeIcon className="w-5 h-5 text-blue-600" />
            <label className="text-sm font-medium text-blue-800">Verifying as</label>
          </div>
          <select
            data-testid="rp-tenant-select"
            value={selectedRpId}
            onChange={(e) => setSelectedRpId(e.target.value)}
            className="w-full px-3 py-2 border border-blue-300 rounded-md text-sm bg-white focus:ring-2 focus:ring-blue-500 focus:border-blue-500"
          >
            <option value="">Default verifier (no RP)</option>
            {rpTenants.map((rp) => (
              <option key={rp.id} value={rp.id}>
                {rp.legalName} ({rp.domain})
              </option>
            ))}
          </select>
        </div>
      )}

      <hr className="mt-8" />
      <h3 className="text-gray-500 text-left mt-2 font-semibold">
        Credential Formats
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
      <div className="mt-12"></div>
      <hr className="text-green-900 border border-[0.5px] border-gray-100" />
      <h3 className="text-gray-500 text-left mt-2 font-semibold">
        Credential Policies
      </h3>
      <div className="flex flex-row justify-start mt-8">
        <div className="flex flex-col gap-3 w-full">
          <PolicyListItem
            name="Signature Policy"
            value={signaturePolicy}
            onChange={setSignaturePolicy}
          />
          <PolicyListItem
            name="Expired Policy"
            value={expiredPolicy}
            onChange={setExpiredPolicy}
          />
          <PolicyListItem
            name="Not Before Policy"
            value={notBeforePolicy}
            onChange={setNotBeforePolicy}
          />
          <PolicyListItem
            name="EUDI Trust List"
            value={trustPolicy}
            onChange={setTrustPolicy}
          />
          <div className="sm:flex justify-between">
            <PolicyListItem
              name="Webhook Policy"
              value={webhookPolicy}
              onChange={setWebhookPolicy}
            />
            <InputField
              error={false}
              label=""
              value={webhook}
              name=""
              type=""
              placeholder="https://webhook.site/..."
              onChange={setWebhook}
            />
          </div>
        </div>
      </div>
      <div className="mt-12" />
      <hr />
      <div className="flex flex-row justify-center gap-3 mt-14">
        <Button onClick={handleCancel} style="link" color="secondary">
          Cancel
        </Button>
        <Button onClick={handleVerify}>Verify</Button>
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
