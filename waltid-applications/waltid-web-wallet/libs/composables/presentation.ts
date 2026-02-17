import {encodeDisclosure, parseDisclosures} from "./disclosures.ts";
import {useCurrentWallet} from "./accountWallet.ts";
import {computed, type Ref, ref, watch} from "vue";
import {decodeRequest} from "./siop-requests.ts";
import {navigateTo} from "nuxt/app";
import {useMtWallet} from "./mtWallet.ts";

export async function usePresentation(query: any) {
  const index = ref(0);
  const failed = ref(false);
  const failMessage = ref("Unknown error occurred.");

  const currentWallet = useCurrentWallet();

  async function resolvePresentationRequest(request: string) {
    try {
      const response = await $fetch(
        `/wallet-api/wallet/${currentWallet.value}/exchange/resolvePresentationRequest`,
        {
          method: "POST",
          body: request,
        },
      );
      return response;
    } catch (e) {
      failed.value = true;
      throw e;
    }
  }

  const request = await resolvePresentationRequest(
    decodeRequest(query.request as string),
  );
  const presentationUrl = new URL(request as string);
  const presentationParams = presentationUrl.searchParams;

  const verifierHost = new URL(
    presentationParams.get("response_uri") ??
      presentationParams.get("redirect_uri") ??
      "",
  ).host;

  const { mtWalletEnabled } = useMtWallet();

  // Extract client_id — ONLY when MT enabled
  const clientId = computed(() => {
    if (!mtWalletEnabled.value) return '';
    return presentationParams.get('client_id') || '';
  });

  // Parse RP domain from x509_san_dns:{domain} — ONLY when MT enabled
  const rpDomain = computed(() => {
    if (!mtWalletEnabled.value) return '';
    const cid = presentationParams.get('client_id') || '';
    if (cid.startsWith('x509_san_dns:')) return cid.substring('x509_san_dns:'.length);
    if (cid.startsWith('x509_san_uri:')) {
      try { return new URL(cid.substring('x509_san_uri:'.length)).host; } catch { return ''; }
    }
    return '';
  });

  const dcqlQueryParam = presentationParams.get("dcql_query");
  const presentationDefinition = presentationParams.get(
    "presentation_definition",
  ) as string;
  const isDcql = !!dcqlQueryParam && !presentationDefinition;

  // Extract requested claim paths from DCQL or presentation_definition
  const requestedClaimPaths: string[] = [];
  if (isDcql && dcqlQueryParam) {
    try {
      const dcql = JSON.parse(dcqlQueryParam);
      for (const cred of dcql.credentials || []) {
        for (const claim of cred.claims || []) {
          if (claim.path?.length) requestedClaimPaths.push(claim.path[claim.path.length - 1]);
        }
      }
    } catch { /* ignore parse errors */ }
  }
  if (!isDcql && presentationDefinition) {
    try {
      const pd = JSON.parse(presentationDefinition);
      for (const desc of pd.input_descriptors || []) {
        for (const field of desc.constraints?.fields || []) {
          for (const p of field.path || []) {
            const leaf = p.split('.').pop()?.replace(/[[\]$]/g, '');
            if (leaf) requestedClaimPaths.push(leaf);
          }
        }
      }
    } catch { /* ignore */ }
  }

  let matchedCredentials: Array<{
    id: string;
    document: string;
    parsedDocument?: string;
    disclosures?: string;
  }>;

  if (isDcql) {
    matchedCredentials = await $fetch(
      `/wallet-api/wallet/${currentWallet.value}/exchange/matchCredentialsForDcqlQuery`,
      {
        method: "POST",
        body: dcqlQueryParam,
      },
    );
  } else if (presentationDefinition) {
    matchedCredentials = await $fetch(
      `/wallet-api/wallet/${currentWallet.value}/exchange/matchCredentialsForPresentationDefinition`,
      {
        method: "POST",
        body: presentationDefinition,
      },
    );
  } else {
    failed.value = true;
    failMessage.value = "No presentation_definition or dcql_query in request";
    matchedCredentials = [];
  }

  const selection = ref<{ [key: string]: boolean }>({});
  const selectedCredentialIds = computed(() =>
    Object.entries(selection.value)
      .filter((it) => it[1])
      .map((it) => it[0]),
  );
  for (let credential of matchedCredentials) {
    selection.value[credential.id] = true;
  }

  // Pre-select only requested disclosures when claim paths are known
  const preSelectedDisclosures: { [key: string]: any[] } = {};
  if (requestedClaimPaths.length > 0) {
    for (const credential of matchedCredentials) {
      if (credential.disclosures) {
        const allDisclosures = parseDisclosures(credential.disclosures);
        const matching = allDisclosures.filter((d: any[]) => requestedClaimPaths.includes(d[1]));
        if (matching.length > 0) {
          preSelectedDisclosures[credential.id] = matching;
        }
      }
    }
  }

  const disclosures: Ref<{ [key: string]: any[] }> = ref(preSelectedDisclosures);
  const encodedDisclosures = computed(() => {
    if (JSON.stringify(disclosures.value) === "{}") return null;

    const m: { [key: string]: any[] } = {};
    for (let credId in disclosures.value) {
      if (m[credId] === undefined) {
        m[credId] = [];
      }

      for (let disclosure of disclosures.value[credId]) {
        m[credId].push(encodeDisclosure(disclosure));
      }
    }

    return m;
  });

  function addDisclosure(credentialId: string, disclosure: string) {
    if (disclosures.value[credentialId] === undefined) {
      disclosures.value[credentialId] = [];
    }
    disclosures.value[credentialId].push(disclosure);
  }

  function removeDisclosure(credentialId: string, disclosure: string) {
    disclosures.value[credentialId] = disclosures.value[credentialId].filter(
      (elem) => elem[0] != disclosure[0],
    );
  }

  const disclosureModalState: Ref<{ [key: string]: boolean }> = ref({});

  for (let credential of matchedCredentials) {
    disclosureModalState.value[credential.id] = false;
  }
  if (matchedCredentials[index.value]) {
    disclosureModalState.value[matchedCredentials[index.value].id] = true;
  }

  function toggleDisclosure(credentialId: string) {
    disclosureModalState.value[credentialId] =
      !disclosureModalState.value[credentialId];
  }

  // Disable all disclosure modals when switching between credentials and set the current one to active
  watch(index, () => {
    for (let credential of matchedCredentials) {
      disclosureModalState.value[credential.id] = false;
    }
    disclosureModalState.value[matchedCredentials[index.value].id] = true;
  });

  async function acceptPresentation() {
    const req = {
      //did: String, // todo: choose DID of shared credential // for now wallet-api chooses the default wallet did
      presentationRequest: request,
      selectedCredentials: selectedCredentialIds.value,
      disclosures: encodedDisclosures.value ?? {},
    };

    const response = await fetch(
      `/wallet-api/wallet/${currentWallet.value}/exchange/usePresentationRequest`,
      {
        method: "POST",
        body: JSON.stringify(req),
        redirect: "manual",
        headers: {
          "Content-Type": "application/json",
        },
      },
    );

    if (response.ok) {
      const parsedResponse: { redirectUri: string } = await response.json();

      // Priority 1: popup mode — notify opener and close
      if (window.opener) {
        const state = presentationParams.get('state') || '';
        window.opener.postMessage({
          type: 'waltid:presentation-complete',
          success: true,
          sessionId: state,
        }, '*');
        window.close();
        return;
      }

      // Priority 2: RP-provided redirect_uri (mobile flow)
      const rpRedirectUri = new URL(window.location.href).searchParams.get('redirect_uri');
      if (rpRedirectUri && rpRedirectUri.startsWith('https://')) {
        navigateTo(rpRedirectUri, { external: true });
        return;
      }

      // Priority 3: verifier-provided redirect (existing behavior)
      if (parsedResponse.redirectUri) {
        navigateTo(parsedResponse.redirectUri, {
          external: true,
        });
      } else {
        window.alert("Presentation successful, no redirect URL supplied.");
        navigateTo(`/wallet/${currentWallet.value}`, {
          external: true,
        });
      }
    } else {
      const error: {
        message: string;
        redirectUri: string | null | undefined;
        errorMessage: string;
      } = await response.json();

      console.log("Error response: " + JSON.stringify(error));

      // Extract a user-friendly message from the error
      const rawMsg = error.errorMessage || error.message || "Presentation failed";
      if (rawMsg.includes("do not disclose all required DCQL claims")) {
        failMessage.value = "The verifier rejected the presentation because required claims were not disclosed. Please try again and ensure all requested attributes are selected.";
      } else {
        failMessage.value = rawMsg;
      }

      // In popup mode, notify the opener of the failure and close
      if (window.opener) {
        const state = presentationParams.get('state') || '';
        window.opener.postMessage({
          type: 'waltid:presentation-complete',
          success: false,
          sessionId: state,
          error: failMessage.value,
        }, '*');
        window.close();
        return;
      }

      failed.value = true;

      if (error.redirectUri != null) {
        navigateTo(error.redirectUri as string, {
          external: true,
        });
      }
    }
  }

  function declinePresentation() {
    if (window.opener) {
      const state = presentationParams.get('state') || '';
      window.opener.postMessage({
        type: 'waltid:presentation-complete',
        success: false,
        sessionId: state,
        error: 'User declined the presentation request.',
      }, '*');
      window.close();
      return;
    }
    navigateTo(`/wallet/${currentWallet.value}`);
  }

  return {
    currentWallet,
    verifierHost,
    clientId,
    rpDomain,
    mtWalletEnabled,
    isDcql,
    presentationDefinition,
    matchedCredentials,
    selectedCredentialIds,
    disclosures,
    selection,
    index,
    disclosureModalState,
    toggleDisclosure,
    addDisclosure,
    removeDisclosure,
    acceptPresentation,
    declinePresentation,
    failed,
    failMessage,
    requestedClaimPaths,
  };
}
