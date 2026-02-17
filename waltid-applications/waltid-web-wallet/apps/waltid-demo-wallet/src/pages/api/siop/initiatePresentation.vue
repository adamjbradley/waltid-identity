<template>
  <CenterMain>
    <WalletListing
      v-if="wallets && wallets.length > 1"
      :wallets="wallets"
      :use-url="walletUrlFunction"
    />
    <div v-else-if="wallets && wallets.length === 0" class="text-center p-8">
      <p class="text-gray-600 mb-4">No wallets found. Please create a wallet first.</p>
      <a href="/" class="text-blue-600 hover:text-blue-700 underline">Go to Dashboard</a>
    </div>
    <LoadingIndicator v-else>Loading wallets...</LoadingIndicator>
  </CenterMain>
</template>

<script lang="ts" setup>
import CenterMain from "@waltid-web-wallet/components/CenterMain.vue";
import WalletListing from "@waltid-web-wallet/components/wallets/WalletListing.vue";
import LoadingIndicator from "@waltid-web-wallet/components/loading/LoadingIndicator.vue";
import {encodeRequest, fixRequest} from "@waltid-web-wallet/composables/siop-requests.ts";
import {listWallets, setWallet, type WalletListing as WalletListingType} from "@waltid-web-wallet/composables/accountWallet.ts";
import {useMtWallet} from "@waltid-web-wallet/composables/mtWallet.ts";

const route = useRoute();

// Auth guard: wait for auth to resolve and redirect to login if unauthenticated
const { status } = useAuth();
if (status.value === 'loading') {
  await new Promise<void>((resolve) => {
    const stop = watch(status, (newStatus) => {
      if (newStatus !== 'loading') {
        stop();
        resolve();
      }
    });
  });
}
if (status.value === 'unauthenticated') {
  await navigateTo('/login?redirect=' + encodeURIComponent(route.fullPath));
}

const { mtWalletEnabled } = useMtWallet();

const queryRequest = new URL("http://example.invalid" + route.fullPath)
  .search; // new URL(window.location.href).search
console.log("queryRequest: ", queryRequest);

let fixedRequest = encodeURI(
  decodeURI(fixRequest("openid://" + window.location.search)),
);
console.log("Fixed request: ", fixedRequest);

const encodedWalletRequestUrl = encodeRequest(fixedRequest);
console.log("Encoded request: ", encodedWalletRequestUrl);

// Forward portal hint params only when MT enabled
const hintParams = mtWalletEnabled.value
  ? Object.fromEntries(
      ['rpName', 'rpDomain']
        .filter(k => route.query[k])
        .map(k => [k, route.query[k] as string])
    )
  : {};
const hintSuffix = Object.keys(hintParams).length
  ? '&' + new URLSearchParams(hintParams).toString()
  : '';

// Preserve redirect_uri from RP for post-presentation return
const redirectUri = route.query.redirect_uri as string || '';
const redirectParam = redirectUri ? `&redirect_uri=${encodeURIComponent(redirectUri)}` : '';

const wallets = (await listWallets())?.value?.wallets;

const walletUrlFunction = (wallet: WalletListingType) =>
  `/wallet/${wallet.id}/exchange/presentation?request=${encodedWalletRequestUrl}${hintSuffix}${redirectParam}`;

if (wallets && wallets.length == 1) {
  const wallet = wallets[0];
  setWallet(wallet.id, undefined);
  navigateTo(walletUrlFunction(wallets[0]));
}

definePageMeta({
  layout: "minimal",
});
</script>
