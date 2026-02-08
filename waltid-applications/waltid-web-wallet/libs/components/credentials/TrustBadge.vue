<template>
  <div v-if="trustResult" class="mt-2">
    <!-- Trust Badge -->
    <button
      @click="expanded = !expanded"
      class="flex items-center gap-2 px-3 py-1.5 rounded-lg text-sm font-medium transition-colors"
      :class="badgeClasses"
    >
      <div :class="iconClasses" class="i-heroicons-shield-check w-4 h-4" v-if="trustResult.trusted && trustResult.trustListResult" />
      <div class="i-heroicons-exclamation-triangle w-4 h-4" v-else-if="!trustResult.trusted && !trustResult.legacyTrusted" />
      <div class="i-heroicons-information-circle w-4 h-4" v-else />
      <span>{{ badgeText }}</span>
      <div
        class="i-heroicons-chevron-down w-3 h-3 transition-transform"
        :class="{ 'rotate-180': expanded }"
      />
    </button>

    <!-- Expanded Details -->
    <div v-if="expanded && trustResult.trustListResult" class="mt-2 p-3 rounded-lg bg-gray-50 dark:bg-gray-800 text-sm space-y-1">
      <div v-if="trustResult.trustListResult.providerName" class="flex justify-between">
        <span class="text-gray-500">Provider</span>
        <span class="font-medium">{{ trustResult.trustListResult.providerName }}</span>
      </div>
      <div v-if="trustResult.trustListResult.country" class="flex justify-between">
        <span class="text-gray-500">Country</span>
        <span class="font-medium">{{ trustResult.trustListResult.country }}</span>
      </div>
      <div v-if="trustResult.trustListResult.source" class="flex justify-between">
        <span class="text-gray-500">Source</span>
        <span class="font-medium">{{ formatSource(trustResult.trustListResult.source) }}</span>
      </div>
      <div v-if="trustResult.trustListResult.status" class="flex justify-between">
        <span class="text-gray-500">Status</span>
        <span class="font-medium">{{ trustResult.trustListResult.status }}</span>
      </div>
      <div v-if="trustResult.trustListResult.details" class="mt-2 pt-2 border-t border-gray-200 dark:border-gray-700">
        <div v-for="(value, key) in trustResult.trustListResult.details" :key="key" class="flex justify-between">
          <span class="text-gray-500">{{ formatDetailKey(key) }}</span>
          <span class="font-medium text-xs">{{ value }}</span>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed } from 'vue'
import { useTrustValidation, type DetailedTrustResult } from '../../composables/trust.ts'

const props = defineProps<{
  walletId: string
  issuerDid: string | null
  credentialType?: string | null
}>()

const expanded = ref(false)

// Use the composable for trust validation
const { trustResult, loading } = useTrustValidation(
  computed(() => props.walletId),
  computed(() => props.issuerDid),
  computed(() => props.credentialType || null)
)

const badgeClasses = computed(() => {
  if (!trustResult.value) return 'bg-gray-100 text-gray-600'
  if (trustResult.value.trusted && trustResult.value.trustListResult) {
    return 'bg-green-100 text-green-800 dark:bg-green-900/30 dark:text-green-400'
  }
  if (trustResult.value.legacyTrusted) {
    return 'bg-blue-100 text-blue-800 dark:bg-blue-900/30 dark:text-blue-400'
  }
  return 'bg-orange-100 text-orange-800 dark:bg-orange-900/30 dark:text-orange-400'
})

const iconClasses = computed(() => {
  if (trustResult.value?.trusted && trustResult.value?.trustListResult) {
    return 'text-green-600'
  }
  return ''
})

const badgeText = computed(() => {
  if (loading.value) return 'Checking trust...'
  if (!trustResult.value) return 'Trust Status Unknown'
  if (trustResult.value.trusted && trustResult.value.trustListResult) {
    return 'EUDI Trusted Issuer'
  }
  if (trustResult.value.legacyTrusted) {
    return 'Verified Issuer'
  }
  return 'Issuer Not Verified'
})

function formatSource(source: string): string {
  const sourceMap: Record<string, string> = {
    'etsi_tl': 'EU Trusted List (ETSI)',
    'openid_federation': 'OpenID Federation',
    'vical': 'VICAL',
    'static_list': 'Static Trust List'
  }
  return sourceMap[source] || source
}

function formatDetailKey(key: string): string {
  return key.replace(/([A-Z])/g, ' $1').replace(/^./, s => s.toUpperCase())
}
</script>
