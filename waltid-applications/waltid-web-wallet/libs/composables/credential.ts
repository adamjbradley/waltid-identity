import {parseDisclosures} from "../composables/disclosures.ts";
import {computedAsync} from "@vueuse/core";
import {parseJwt} from "../utils/jwt.ts";
import {computed, type Ref, ref, watchEffect} from "vue";
import {useCurrentWallet} from "./accountWallet";

export type CredentialStatusResult = {
    type: string;
    result: boolean;
    message: string;
};

export type WalletCredential = {
    wallet: string;
    id: string;
    document: string;
    disclosures?: string;
    addedOn: string;
    manifest?: string;
    parsedDocument?: {
        [key: string]: any;
        display?: Array<Object>;
    }
    format: string;
};

export const VCT_DISPLAY_NAMES: Record<string, string> = {
    "urn:eudi:pid:1": "EU Personal ID",
    "PaymentWalletAttestation": "Payment Wallet",
};

export const MDOC_DOCTYPE_NAMES: Record<string, string> = {
    "org.iso.18013.5.1.mDL": "Mobile Driving Licence",
    "eu.europa.ec.eudi.pid.1": "EU Personal ID",
    "eu.europa.ec.eudi.pseudonym.1": "EU Pseudonym",
    "eu.europa.ec.eudi.over18.1": "Age Verification",
    "eu.europa.ec.eudi.loyalty.1": "Loyalty Card",
};

function getMdocNameSpaceElement(parsed: any, elementId: string): string | null {
    const nameSpaces = parsed?.issuerSigned?.nameSpaces;
    if (!nameSpaces) return null;
    for (const ns of Object.values(nameSpaces) as any[]) {
        if (!Array.isArray(ns)) continue;
        for (const elem of ns) {
            if (elem.elementIdentifier === elementId) return elem.elementValue;
        }
    }
    return null;
}

export function useCredential(credential: Ref<WalletCredential | null>) {
    const currentWallet = useCurrentWallet()
    const jwtJson = computedAsync(async () => {
        if (credential.value) {
            if (credential.value.parsedDocument) return credential.value.parsedDocument;

            let parsed;
            if (credential.value.format && credential.value.format === "mso_mdoc") {
                let resp = await fetch(`/wallet-api/util/parseMDoc`, {
                    method: "POST",
                    body: credential.value.document,
                });
                parsed = await resp.json();
            }
            else {
                parsed = parseJwt(credential.value.document);
            }

            if (parsed.vc) return parsed.vc; else return parsed;
        } else return null;
    });

    const disclosures = computed(() => {
        if (credential.value && credential.value.disclosures) {
            return parseDisclosures(credential.value.disclosures);
        } else return null;
    });

    const manifest = computed(() => (credential.value?.manifest && credential.value.manifest != "{}" ? (typeof credential.value.manifest === 'string' ? JSON.parse(credential.value.manifest) : credential.value.manifest) : credential.value?.parsedDocument?.display?.[0] ?? null));
    const manifestClaims = computed(() => manifest.value?.display?.claims);

    // Function to resolve VCT URL and fetch the name parameter
    async function fetchVctName(vct: string): Promise<String> {
        try {
            const response = await fetch(`/wallet-api/wallet/${currentWallet.value}/exchange/resolveVctUrl?vct=${vct}`);
            const data = await response.json();
            return data.name || null;
        } catch (error) {
            console.error('Error fetching VCT name:', error);
            return null;
        }
    }

    const titleTitelized = ref('');

    const isMdoc = computed(() => credential.value?.format === "mso_mdoc");

    watchEffect(async () => {
        if (isMdoc.value && jwtJson.value?.docType) {
            titleTitelized.value = MDOC_DOCTYPE_NAMES[jwtJson.value.docType] ?? jwtJson.value.docType;
        } else if (jwtJson.value?.vct) {
            const vct = jwtJson.value.vct;
            if (VCT_DISPLAY_NAMES[vct]) {
                titleTitelized.value = VCT_DISPLAY_NAMES[vct];
            } else {
                const vctName = await fetchVctName(vct);
                titleTitelized.value = vctName ?? vct.replace(/^urn:eudi:/, "").replace(/:/g, " ").replace(/([a-z0-9])([A-Z])/g, "$1 $2");
            }
        } else {
            // Fallback logic if there's no `vct`
            titleTitelized.value = manifest.value?.display?.title
                ?? jwtJson.value?.type?.at(-1)?.replace(/([a-z0-9])([A-Z])/g, "$1 $2")
                ?? jwtJson.value?.vct?.replace("_vc+sd-jwt", "").replace(/([a-z0-9])([A-Z])/g, "$1 $2")
                ?? jwtJson.value?.docType;
        }
    });

    const credentialSubtitle = computed(() => {
        if (isMdoc.value) return getMdocNameSpaceElement(jwtJson.value, "issuing_country");
        return manifest.value?.display?.card?.description ?? jwtJson.value?.name;
    });
    const credentialImageUrl = computed(() => manifest.value?.display?.card?.logo?.uri ?? jwtJson.value?.issuer?.image?.id ?? jwtJson.value?.issuer?.image);
    const issuerName = computed(() => {
        if (isMdoc.value) return getMdocNameSpaceElement(jwtJson.value, "issuing_authority");
        return manifest.value?.display?.card?.issuedBy ?? jwtJson.value?.issuer?.name ?? jwtJson.value?.issuing_authority;
    });
    const issuerLogo = computed(() => jwtJson.value?.issuer?.image?.id ?? jwtJson.value?.issuer?.image);
    const issuerDid = computed(() => manifest.value?.input?.issuer ?? jwtJson.value?.issuer?.id ?? jwtJson.value?.issuer);
    const issuerKid = computed(() =>
        credential.value.format === "vc+sd-jwt" ? jwtJson.value?.iss ?? null : null
    );
    const credentialIssuerService = computed(() => manifest.value?.input?.credentialIssuer);

    const statusResults = ref<CredentialStatusResult[]>([]);
    const statusLoading = ref(false);
    const isRevoked = computed(() => statusResults.value.some(s => s.type === "revocation" && s.result === true));

    async function checkStatus() {
        const wId = credential.value?.wallet;
        const cId = credential.value?.id;
        if (!wId || !cId) return;
        statusLoading.value = true;
        try {
            statusResults.value = await $fetch<CredentialStatusResult[]>(
                `/wallet-api/wallet/${wId}/credentials/${encodeURIComponent(cId)}/status`
            );
        } catch (e) {
            console.error("Failed to check credential status:", e);
        } finally {
            statusLoading.value = false;
        }
    }

    // Auto-check status when credential is available
    watchEffect(() => {
        if (credential.value?.wallet && credential.value?.id) {
            checkStatus();
        }
    });

    const isNotExpired = computed(() => jwtJson.value?.expirationDate ? new Date(jwtJson.value?.expirationDate).getTime() > new Date().getTime() : jwtJson.value?.validUntil ? new Date(jwtJson.value?.validUntil).getTime() > new Date().getTime() : true);
    const issuanceDate = computed(() => {
        if (jwtJson.value?.issuanceDate) {
            return new Date(jwtJson.value?.issuanceDate).toISOString().slice(0, 10);
        } else if (jwtJson.value?.validFrom) {
            return new Date(jwtJson.value?.validFrom).toISOString().slice(0, 10);
        } else {
            return null;
        }
    });
    const expirationDate = computed(() => {
        if (jwtJson.value?.expirationDate) {
            return new Date(jwtJson.value?.expirationDate).toISOString().slice(0, 10);
        } else if (jwtJson.value?.validUntil) {
            return new Date(jwtJson.value?.validUntil).toISOString().slice(0, 10);
        } else {
            return null;
        }
    });

    return {
        jwtJson,
        disclosures,
        manifest,
        manifestClaims,
        titleTitelized,
        credentialSubtitle,
        credentialImageUrl,
        issuerName,
        issuerLogo,
        issuerDid,
        issuerKid,
        credentialIssuerService,
        isNotExpired,
        isRevoked,
        statusLoading,
        checkStatus,
        issuingCountry,
        issuanceDate,
        expirationDate
    };
}
