<template>
    <div ref="vcCardDiv"
        :class="{
            'p-6 rounded-2xl shadow-2xl sm:shadow-lg h-full text-white': true,
            'lg:w-[400px]': isDetailView,
            'bg-gradient-to-br from-[#DC2626] to-[#991B1B] border-t-white border-t-[0.5px]': isRevoked,
            'bg-gradient-to-br from-[#0573F0] to-[#03449E] border-t-white border-t-[0.5px]': !isRevoked && isNotExpired,
            'bg-[#7B8794]': !isRevoked && !isNotExpired
        }">
        <div class="flex justify-end" v-if="isRevoked">
            <div class="bg-red-800 text-white px-2 py-1 rounded-full text-xs font-semibold">Revoked</div>
        </div>
        <div class="flex justify-end" v-else-if="!isNotExpired">
            <div class="text-black bg-[#CBD2D9] px-2 py-1 rounded-full text-xs">Expired</div>
        </div>

        <div class="mb-8">
            <div class="text-2xl font-bold bold">
                {{ !isDetailView ? titleTitelized?.length > 20 ? titleTitelized?.slice(0, 20) + "..." :
                    titleTitelized : titleTitelized }}
            </div>
        </div>

        <div :class="{ 'sm:mt-18': !isRevoked && isNotExpired, 'sm:mt-8': isRevoked || !isNotExpired }">
            <div class="flex justify-between items-end gap-2">
                <div>
                    <div :class="{ 'text-[#0573f000]': !issuerName }">Issuer</div>
                    <div :class="{ 'text-[#0573f000]': !issuerName }" class="font-bold">
                        {{ issuerName ?? 'Unknown' }}
                    </div>
                    <div v-if="issuingCountry || countryFlag" class="text-xs opacity-75">
                        <span v-if="countryFlag" class="mr-1">{{ countryFlag }}</span>
                        <span v-if="issuingCountry">{{ issuingCountry }}</span>
                    </div>
                </div>
                <img v-if="issuerLogo" :src="issuerLogo" alt="Issuer Logo" class="h-6 rounded-full" />
            </div>
        </div>

        <div v-if="showId" class="font-mono mt-3">ID: {{ credential?.id }}</div>
    </div>
</template>

<script lang="ts" setup>
import {useCredential} from "../../composables/credential.ts";
import {computed, defineProps, ref, watchEffect} from "vue";

const props = defineProps({
    credential: {
        type: Object,
        required: true,
    },
    showId: {
        type: Boolean,
        required: false,
        default: false,
    },
    isDetailView: {
        type: Boolean,
        required: false,
        default: false,
    },
});

const { jwtJson: credential, manifest, titleTitelized, isNotExpired, isRevoked, issuerName, issuerLogo, issuingCountry, countryFlag } = useCredential(ref(props.credential as any));
const manifestCard = computed(() => manifest.value?.display?.card ?? manifest.value);
const isDetailView = ref(props.isDetailView ?? false);
const vcCardDiv: any = ref(null);

watchEffect(async () => {
    try {
        if (vcCardDiv.value) {
            // Don't override card background when revoked
            if (isRevoked.value) return;

            if (manifestCard.value) {
                if (manifest.value.backgroundImage) {
                    vcCardDiv.value.style.backgroundImage = `url(${manifest.value.backgroundImage.url})`;
                    vcCardDiv.value.style.backgroundSize = 'cover';
                    vcCardDiv.value.style.backgroundPosition = 'center';
                }
                else if (manifestCard.value.backgroundColor) {
                    vcCardDiv.value.style.background = manifestCard.value.backgroundColor;
                }

                if (manifestCard.value.textColor) {
                    vcCardDiv.value.style.color = manifestCard.value.textColor;
                }
            }
        }
    } catch (_) { }
});
</script>
