<template>
  <CenterMain>
    <WalletListing
      v-if="wallets && wallets.length > 1"
      :wallets="wallets"
      :use-url="walletUrlFunction"
    />
    <LoadingIndicator v-else>Loading wallets...</LoadingIndicator>
  </CenterMain>
</template>

<script lang="ts" setup>
import CenterMain from "@waltid-web-wallet/components/CenterMain.vue";
import WalletListing from "@waltid-web-wallet/components/wallets/WalletListing.vue";
import LoadingIndicator from "@waltid-web-wallet/components/loading/LoadingIndicator.vue";
import {listWallets, setWallet, type WalletListing as WalletListingType} from "@waltid-web-wallet/composables/accountWallet.ts";
import {useMtWallet} from "@waltid-web-wallet/composables/mtWallet.ts";

const route = useRoute();
const { mtWalletEnabled } = useMtWallet();

const queryRequest = new URL("http://example.invalid" + route.fullPath)
  .search; // new URL(window.location.href).search
console.log("queryRequest: ", queryRequest);

const walletRequestUrl = "openid-initiate-issuance://" + queryRequest;
console.log("walletRequestUrl: ", walletRequestUrl);
const encodedWalletRequestUrl = btoa(walletRequestUrl);
console.log("encodedWalletRequestUrl: ", encodedWalletRequestUrl);

// Forward portal hint params only when MT enabled
const hintParams = mtWalletEnabled.value
  ? Object.fromEntries(
      ['issuerName', 'issuerDomain']
        .filter(k => route.query[k])
        .map(k => [k, route.query[k] as string])
    )
  : {};
const hintSuffix = Object.keys(hintParams).length
  ? '&' + new URLSearchParams(hintParams).toString()
  : '';

const wallets = (await listWallets())?.value?.wallets;

const walletUrlFunction = (wallet: WalletListingType) =>
  `/wallet/${wallet.id}/exchange/issuance?request=${encodedWalletRequestUrl}${hintSuffix}`;

if (wallets && wallets.length == 1) {
  const wallet = wallets[0];
  setWallet(wallet.id, undefined);
  navigateTo(walletUrlFunction(wallets[0]));
}

definePageMeta({
  layout: "minimal",
});
</script>
