import {computed} from "vue";
import {useRuntimeConfig} from "nuxt/app";

/**
 * Multi-Tenant Wallet feature flag composable.
 * When disabled (default), all MT-aware UI is hidden and the wallet
 * behaves identically to single-tenant mode.
 */
export function useMtWallet() {
    const config = useRuntimeConfig();
    const mtWalletEnabled = computed(() => config.public.mtWalletEnabled === true);
    return {mtWalletEnabled};
}
