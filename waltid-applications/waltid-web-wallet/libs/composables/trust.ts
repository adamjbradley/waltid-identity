import { ref, watch, type Ref } from 'vue'

export interface TrustValidationResult {
  trusted: boolean
  source?: string
  providerName?: string
  country?: string
  status?: string
  validFrom?: string
  validUntil?: string
  details?: Record<string, string>
}

export interface DetailedTrustResult {
  trusted: boolean
  legacyTrusted: boolean
  trustListResult?: TrustValidationResult
}

export function useTrustValidation(
  walletId: Ref<string | null>,
  issuerDid: Ref<string | null>,
  credentialType?: Ref<string | null>
) {
  const trustResult = ref<DetailedTrustResult | null>(null)
  const loading = ref(false)
  const error = ref<string | null>(null)

  async function validate() {
    if (!issuerDid.value || !walletId.value) {
      trustResult.value = null
      return
    }

    loading.value = true
    error.value = null

    try {
      const response = await $fetch<DetailedTrustResult>(
        `/wallet-api/wallet/${walletId.value}/trust/validate`,
        {
          params: {
            did: issuerDid.value,
            type: credentialType?.value || '',
            detailed: true
          }
        }
      )
      trustResult.value = response
    } catch (e: any) {
      error.value = e.message || 'Trust validation unavailable'
      trustResult.value = null
    } finally {
      loading.value = false
    }
  }

  watch([issuerDid, walletId], () => validate(), { immediate: true })

  return { trustResult, loading, error, validate }
}
