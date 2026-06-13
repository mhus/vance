<script setup lang="ts">
import { onMounted, ref } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import {
  isBiometricEnabled,
  markUnlockedByBiometric,
  verifyPin,
} from '@/lock/lockStore';
import { tryBiometricUnlock } from '@/lock/biometric';
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
  <div class="flex h-full flex-col items-center justify-center px-6">
    <div class="mb-2 text-2xl font-semibold">Vance</div>
    <div class="mb-6 text-sm text-gray-400">Enter PIN to unlock</div>

    <PinPad v-model:value="pin" @submit="onSubmit" />

    <p v-if="error" class="mt-4 text-sm text-red-400">{{ error }}</p>

    <button
      v-if="biometricAvailable"
      type="button"
      class="mt-6 text-sm text-blue-400 underline"
      @click="runBiometric"
    >
      Use Face ID / Touch ID
    </button>
  </div>
</template>
