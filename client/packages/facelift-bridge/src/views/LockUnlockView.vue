<script setup lang="ts">
import { onMounted, ref } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import {
  isBiometricEnabled,
  markUnlockedByBiometric,
  verifyPin,
} from '@/lock/lockStore';
import { tryBiometricUnlock } from '@/lock/biometric';
import { VButton } from '@vance/components';
import PinPad from '@/components/PinPad.vue';

const router = useRouter();
const route = useRoute();
const pin = ref('');
const error = ref<string | null>(null);
const submitting = ref(false);
const biometricAvailable = ref(false);

onMounted(async () => {
  biometricAvailable.value = await isBiometricEnabled();
  if (biometricAvailable.value) {
    void runBiometric();
  }
});

async function runBiometric(): Promise<void> {
  const ok = await tryBiometricUnlock();
  if (ok) {
    markUnlockedByBiometric();
    proceed();
  }
}

async function onSubmit(): Promise<void> {
  if (submitting.value) return;
  submitting.value = true;
  const ok = await verifyPin(pin.value);
  if (ok) {
    proceed();
  } else {
    error.value = 'Wrong PIN';
    pin.value = '';
    submitting.value = false;
  }
}

function proceed(): void {
  const next = typeof route.query.next === 'string' ? route.query.next : '/';
  void router.replace(next);
}
</script>

<template>
  <div class="flex h-full flex-col items-center justify-center bg-(--v-bg) px-6 text-(--v-label)">
    <h1 class="mb-1 text-[22px] leading-7 font-semibold">Vancetope</h1>
    <p class="mb-8 text-sm text-(--v-label-secondary)">Enter PIN to unlock</p>

    <PinPad v-model:value="pin" @submit="onSubmit" />

    <p v-if="error" role="alert" class="mt-4 text-sm text-(--v-danger)">{{ error }}</p>

    <VButton
      v-if="biometricAvailable"
      variant="plain"
      class="mt-6"
      @click="runBiometric"
    >
      Use Face ID / Touch ID
    </VButton>
  </div>
</template>
