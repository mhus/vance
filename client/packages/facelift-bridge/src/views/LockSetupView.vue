<script setup lang="ts">
import { onMounted, ref } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import {
  MIN_PIN_LENGTH,
  declinePinSetup,
  isPinConfigured,
  setPin,
} from '@/lock/lockStore';
import { VButton } from '@vance/components';
import PinPad from '@/components/PinPad.vue';

type Step = 'enter' | 'confirm';

const router = useRouter();
const route = useRoute();
const step = ref<Step>('enter');
const enteredPin = ref('');
const confirmPin = ref('');
const error = ref<string | null>(null);
const submitting = ref(false);
// "Not now" is offered only when there is nothing to keep — coming
// here from Accounts → Change PIN means a PIN already exists, and
// skipping would then read as "remove it", which this screen does
// not do.
const canDecline = ref(false);

onMounted(async () => {
  canDecline.value = !(await isPinConfigured());
});

async function onDecline(): Promise<void> {
  if (submitting.value) return;
  submitting.value = true;
  try {
    await declinePinSetup();
    void router.replace(nextRoute());
  } catch (e) {
    error.value = e instanceof Error ? e.message : 'Could not continue';
    submitting.value = false;
  }
}

function nextRoute(): string {
  return typeof route.query.next === 'string' ? route.query.next : '/';
}

async function onEnterSubmit(): Promise<void> {
  if (enteredPin.value.length < MIN_PIN_LENGTH) {
    error.value = `PIN must be at least ${MIN_PIN_LENGTH} digits`;
    return;
  }
  error.value = null;
  step.value = 'confirm';
}

async function onConfirmSubmit(): Promise<void> {
  if (submitting.value) return;
  if (confirmPin.value !== enteredPin.value) {
    error.value = "PINs don't match — try again";
    confirmPin.value = '';
    step.value = 'enter';
    enteredPin.value = '';
    return;
  }
  submitting.value = true;
  try {
    await setPin(enteredPin.value);
    void router.replace(nextRoute());
  } catch (e) {
    error.value = e instanceof Error ? e.message : 'Could not save PIN';
    submitting.value = false;
  }
}

function onBack(): void {
  step.value = 'enter';
  confirmPin.value = '';
  error.value = null;
}
</script>

<template>
  <div class="flex h-full flex-col items-center justify-center bg-(--v-bg) px-6 text-(--v-label)">
    <h1 class="mb-1 text-[22px] leading-7 font-semibold">Vancetope</h1>
    <p v-if="step === 'enter'" class="mb-8 text-sm text-(--v-label-secondary)">
      Set a PIN to protect the app
    </p>
    <p v-else class="mb-8 text-sm text-(--v-label-secondary)">Confirm PIN</p>

    <PinPad
      v-if="step === 'enter'"
      v-model:value="enteredPin"
      @submit="onEnterSubmit"
    />
    <PinPad
      v-else
      v-model:value="confirmPin"
      @submit="onConfirmSubmit"
    />

    <p v-if="error" role="alert" class="mt-4 text-sm text-(--v-danger)">{{ error }}</p>

    <VButton
      v-if="step === 'confirm'"
      variant="plain"
      size="sm"
      icon="back"
      class="mt-3"
      @click="onBack"
    >
      Back
    </VButton>

    <VButton
      v-if="canDecline && step === 'enter'"
      variant="plain"
      class="mt-6"
      :disabled="submitting"
      @click="onDecline"
    >
      Not now
    </VButton>

    <p class="mt-10 max-w-xs text-center text-xs text-(--v-label-secondary)">
      The PIN is stored only on this device. Removing the app deletes
      it; you will choose a new PIN on the next install.
    </p>
    <p
      v-if="canDecline"
      class="mt-2 max-w-xs text-center text-xs text-(--v-label-secondary)"
    >
      You can set one later in Accounts, under Security.
    </p>
  </div>
</template>
