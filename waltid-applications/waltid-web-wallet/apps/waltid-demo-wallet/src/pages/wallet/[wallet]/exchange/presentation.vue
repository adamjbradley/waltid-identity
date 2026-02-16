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

      <div v-else class="my-10 mb-40 sm:mb-10 overflow-scroll">
        <div v-if="mobileView" v-for="(credential, credentialIdx) in matchedCredentials" :key="credentialIdx">
          <div :class="{ 'mt-[-85px]': credentialIdx !== 0 }"
            class="col-span-1 divide-y divide-gray-200 rounded-2xl bg-white shadow transform hover:scale-105 cursor-pointer duration-200">
            <VerifiableCredentialCard :credential="credential" />
          </div>
        </div>
        <div class="w-full flex justify-center gap-5" v-else>
          <button v-if="matchedCredentials.length > 1" @click="index--" class="mt-4 text-[#002159] font-bold bg-white"
            :disabled="index === 0" :class="{ 'cursor-not-allowed opacity-50': index === 0 }">
            <svg xmlns="http://www.w3.org/2000/svg" class="h-6 w-6" fill="none" viewBox="0 0 24 24"
              stroke="currentColor">
              <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M15 19l-7-7 7-7" />
            </svg>
          </button>
          <VerifiableCredentialCard :key="index" :credential="matchedCredentials[index]" class="sm:w-[400px]" />
          <button v-if="matchedCredentials.length > 1" @click="index++" class="mt-4 text-[#002159] font-bold bg-white"
            :disabled="index === matchedCredentials.length - 1" :class="{
              'cursor-not-allowed opacity-50':
                index === matchedCredentials.length - 1,
            }">
            <svg xmlns="http://www.w3.org/2000/svg" class="h-6 w-6" fill="none" viewBox="0 0 24 24"
              stroke="currentColor">
              <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M9 5l7 7-7 7" />
            </svg>
          </button>
        </div>
        <div v-if="!mobileView" class="text-center text-gray-500 mt-2">
          {{ index + 1 }} of {{ matchedCredentials.length }}
        </div>
        <div class="sm:w-[80%] md:w-[60%] mx-auto">
          <div class="text-gray-500 mt-8 sm:mt-0">
            {{
              matchedCredentials.length > 1 ? "Credentials" : "Credential"
            }}
            to present
          </div>
          <hr class="mt-1 mb-2 border-gray-200" />
          <div v-for="credentialType in Object.keys(groupedCredentialsByType)">
            <div v-if="groupedCredentialsByType[credentialType].length > 1"
              class="border border-[#E4E7EB] rounded-xl p-4 mb-4">
              <div class="text-[#616E7C]">
                You have {{ groupedCredentialsByType[credentialType].length }} matching credentials of the same type;
                please choose one.
              </div>
              <div v-for="credential in groupedCredentialsByType[credentialType]" class="mt-2 flex gap-2">
                <input type="radio" :id="`${credentialType}-grouped-credential-${credential.id}`"
                  :checked="selection[credential.id]"
                  @click="groupedCredentialsByType[credentialType].forEach(c => selection[c.id] = c.id === credential.id)"
                  class="mt-1 h-4 w-4 text-[#0573F0]" />
                <CredentialDisclosure :credential="credential" :disclosureModalState="disclosureModalState"
                  :disclosures="disclosures" :selection="selection" :toggleDisclosure="toggleDisclosure"
                  :addDisclosure="addDisclosure" :removeDisclosure="removeDisclosure"
                  :requestedClaims="requestedClaimPaths" />
              </div>
            </div>
            <div v-else>
              <CredentialDisclosure :credential="groupedCredentialsByType[credentialType][0]"
                :disclosureModalState="disclosureModalState" :disclosures="disclosures" :selection="selection"
                :toggleDisclosure="toggleDisclosure" :addDisclosure="addDisclosure"
                :removeDisclosure="removeDisclosure" :requestedClaims="requestedClaimPaths" />
            </div>
          </div>
        </div>
      </div>
    </CenterMain>
    <div v-if="!failed && matchedCredentials.length" class="w-full sm:max-w-2xl sm:mx-auto">
      <div
        class="fixed sm:relative bottom-0 w-full p-4 bg-white shadow-md sm:shadow-none sm:flex sm:justify-end sm:gap-4">
        <button data-testid="disclose-credential" @click="acceptPresentation" class="w-full sm:w-44 py-3 mt-4 text-white bg-[#002159] rounded-xl">
          {{ matchedCredentials.length > 1 ? "Disclose All" : "Disclose" }}
        </button>
        <button data-testid="decline-presentation" @click="declinePresentation"
          class="w-full sm:w-44 py-3 mt-4 bg-white sm:border sm:border-gray-400 sm:rounded-xl">
          Decline
        </button>
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
const mobileView = ref(window.innerWidth < 650);

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
  index,
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
