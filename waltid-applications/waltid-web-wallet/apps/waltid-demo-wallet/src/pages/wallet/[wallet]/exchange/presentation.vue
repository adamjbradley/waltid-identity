<template>
  <div>
    <CenterMain>
      <h1 class="mb-2 text-2xl text-center font-bold">Presentation Request</h1>

      <!-- MT MODE ONLY: Verifier identity section (hidden when MT_WALLET_ENABLED=false) -->
      <div v-if="mtWalletEnabled" class="mb-4 rounded-lg bg-blue-50 p-3 border border-blue-100" data-testid="verifier-identity">
        <div class="text-xs font-medium text-blue-600 uppercase tracking-wide">Requested by</div>
        <template v-if="rpDomain">
          <div class="text-sm font-semibold text-gray-800">{{ rpHintName || rpDomain }}</div>
          <div v-if="rpHintName" class="text-xs text-gray-500">{{ rpDomain }}</div>
        </template>
        <template v-else-if="verifierHost">
          <div class="text-sm font-semibold text-gray-800">{{ rpHintName || verifierHost }}</div>
        </template>
        <template v-else>
          <div class="text-sm text-gray-600">Unknown verifier</div>
        </template>
        <div v-if="clientId" class="mt-1 text-xs text-gray-400 font-mono truncate" data-testid="verifier-client-id">
          {{ clientId }}
        </div>
      </div>

      <LoadingIndicator v-if="immediateAccept" class="my-6 mb-12 w-full">
        Presenting credential(s)...
      </LoadingIndicator>

      <div v-if="failed && failMessage" class="my-4 rounded-lg bg-red-50 p-4 border border-red-200">
        <div class="flex items-start gap-3">
          <Icon name="heroicons:exclamation-triangle" class="h-6 w-6 text-red-500 flex-shrink-0 mt-0.5" />
          <div>
            <div class="text-sm font-semibold text-red-800">Presentation Failed</div>
            <div class="text-sm text-red-700 mt-1">{{ failMessage }}</div>
            <button @click="failed = false" class="mt-3 text-sm font-medium text-red-600 hover:text-red-800 underline">
              Try again
            </button>
          </div>
        </div>
      </div>

      <div v-if="matchedCredentials.length == 0 && !failed">
        <span class="text-red-600 animate-pulse flex items-center gap-1 py-1">
          <Icon name="heroicons:exclamation-circle" class="h-6 w-6" />
          You don't have any credentials matching this presentation definition
          in your wallet.
        </span>
      </div>

      <!-- SELECTION PHASE: Choose which credentials to present -->
      <div v-else-if="selectionPhase" class="my-10 mb-40 sm:mb-10">
        <div class="text-gray-500 mb-4 text-center">Select credentials to present</div>
        <div v-for="credentialType in Object.keys(groupedCredentialsByType)" :key="credentialType" class="mb-6">
          <div v-if="groupedCredentialsByType[credentialType].length > 1"
            class="text-sm text-[#616E7C] mb-2">Choose one:</div>
          <div class="grid grid-cols-1 sm:grid-cols-2 gap-4">
            <div v-for="credential in groupedCredentialsByType[credentialType]" :key="credential.id"
              @click="toggleSelectionCard(credentialType, credential.id)"
              class="relative cursor-pointer rounded-2xl transition-all duration-200"
              :class="selection[credential.id]
                ? 'ring-2 ring-blue-500 shadow-lg'
                : 'opacity-60 hover:opacity-80'">
              <div v-if="selection[credential.id]"
                class="absolute top-2 right-2 z-10 bg-blue-500 text-white rounded-full w-6 h-6 flex items-center justify-center">
                <Icon name="heroicons:check" class="h-4 w-4" />
              </div>
              <VerifiableCredentialCard :credential="credential" />
            </div>
          </div>
        </div>
      </div>

      <!-- CONFIRM PHASE: Review disclosures and confirm -->
      <div v-else class="my-10 mb-40 sm:mb-10 overflow-scroll">
        <div v-if="matchedCredentials.length > 1" class="mb-4">
          <button @click="selectionPhase = true" class="text-sm text-blue-600 hover:text-blue-800 flex items-center gap-1">
            <Icon name="heroicons:arrow-left" class="h-4 w-4" />
            Back to selection
          </button>
        </div>
        <div class="sm:w-[80%] md:w-[60%] mx-auto">
          <div class="text-gray-500">
            {{ selectedCredentials.length > 1 ? "Credentials" : "Credential" }} to present
          </div>
          <hr class="mt-1 mb-2 border-gray-200" />
          <div v-for="credential in selectedCredentials" :key="credential.id">
            <CredentialDisclosure :credential="credential" :disclosureModalState="disclosureModalState"
              :disclosures="disclosures" :selection="selection" :toggleDisclosure="toggleDisclosure"
              :addDisclosure="addDisclosure" :removeDisclosure="removeDisclosure" />
          </div>
        </div>
      </div>
    </CenterMain>
    <div v-if="!failed && matchedCredentials.length" class="w-full sm:max-w-2xl sm:mx-auto">
      <div
        class="fixed sm:relative bottom-0 w-full p-4 bg-white shadow-md sm:shadow-none sm:flex sm:justify-end sm:gap-4">
        <!-- Selection phase buttons -->
        <template v-if="selectionPhase">
          <button data-testid="continue-selection" @click="selectionPhase = false"
            :disabled="!hasSelection"
            class="w-full sm:w-44 py-3 mt-4 text-white bg-[#002159] rounded-xl disabled:opacity-50 disabled:cursor-not-allowed">
            Continue
          </button>
          <button data-testid="decline-presentation" @click="navigateTo(`/wallet/${walletId}`)"
            class="w-full sm:w-44 py-3 mt-4 bg-white sm:border sm:border-gray-400 sm:rounded-xl">
            Decline
          </button>
        </template>
        <!-- Confirm phase buttons -->
        <template v-else>
          <button data-testid="disclose-credential" @click="acceptPresentation" class="w-full sm:w-44 py-3 mt-4 text-white bg-[#002159] rounded-xl">
            {{ selectedCredentials.length > 1 ? "Disclose All" : "Disclose" }}
          </button>
          <button data-testid="decline-presentation" @click="navigateTo(`/wallet/${walletId}`)"
            class="w-full sm:w-44 py-3 mt-4 bg-white sm:border sm:border-gray-400 sm:rounded-xl">
            Decline
          </button>
        </template>
      </div>
    </div>
  </div>
</template>

<script lang="ts" setup>
import {computed} from "vue";
import {useTitle} from "@vueuse/core";
import {parseJwt} from "@waltid-web-wallet/utils/jwt.ts";
import CenterMain from "@waltid-web-wallet/components/CenterMain.vue";
import {usePresentation} from "@waltid-web-wallet/composables/presentation.ts";
import LoadingIndicator from "@waltid-web-wallet/components/loading/LoadingIndicator.vue";
import VerifiableCredentialCard from "@waltid-web-wallet/components/credentials/VerifiableCredentialCard.vue";

const immediateAccept = ref(false);
const selectionPhase = ref(false);

const route = useRoute();
const query = route.query;
const walletId = route.params.wallet;

const {
  disclosures,
  selection,
  disclosureModalState,
  toggleDisclosure,
  addDisclosure,
  removeDisclosure,
  matchedCredentials,
  acceptPresentation,
  declinePresentation,
  failed,
  failMessage,
  verifierHost,
  clientId,
  rpDomain,
  mtWalletEnabled,
  requestedClaimPaths,
} = await usePresentation(query);

// Show selection phase when multiple credentials match
selectionPhase.value = matchedCredentials.length > 1;

// Credentials filtered to only selected ones (for confirm phase)
const selectedCredentials = computed(() =>
  matchedCredentials.filter(c => selection.value[c.id])
);

// At least one credential selected
const hasSelection = computed(() => selectedCredentials.value.length > 0);

// Toggle credential selection — radio behavior for same-type groups
function toggleSelectionCard(credentialType: string, credentialId: string) {
  const group = groupedCredentialsByType.value[credentialType];
  if (group.length > 1) {
    // Same-type group: radio — select this one, deselect others
    for (const c of group) {
      selection.value[c.id] = c.id === credentialId;
    }
  } else {
    // Single in group: toggle
    selection.value[credentialId] = !selection.value[credentialId];
  }
}

// Portal hint params — ONLY read when MT enabled
const rpHintName = computed(() =>
  mtWalletEnabled.value ? (route.query.rpName as string || '') : ''
);
const groupedCredentialsByType = computed(() => {
  const groups: Record<string, {
    id: string;
    document: string;
    parsedDocument?: string;
    disclosures?: string;
  }[]> = {};
  for (const credential of matchedCredentials) {
    const parsedDocument = parseJwt(credential.document);
    const parsed = credential.parsedDocument ?? parsedDocument?.vc ?? parsedDocument;
    const types = parsed?.type ?? (parsed?.vct ? [parsed.vct] : undefined) ?? (parsed?.docType ? [parsed.docType] : undefined);
    const typeKey = Array.isArray(types) && types.length > 0 ? types.at(-1) : "unknown";
    if (!groups[typeKey]) {
      groups[typeKey] = [];
    }
    groups[typeKey].push(credential);
  }
  return groups;
});
watch(groupedCredentialsByType, (newValue) => {
  for (const type in newValue) {
    if (newValue[type].length > 1) {
      for (const credential of newValue[type]) {
        selection.value[credential.id] = false;
      }
      selection.value[newValue[type][0].id] = true;
    }
  }
}, { immediate: true });

if (query.accept) {
  immediateAccept.value = true;
  acceptPresentation();
}

useTitle(`Present credentials - walt.id`);
definePageMeta({
  layout: window.innerWidth > 650 ? "desktop-without-sidebar" : false,
});
</script>

<style scoped></style>
