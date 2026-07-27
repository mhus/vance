<script setup lang="ts">
/**
 * Global session-takeover dialog — shown whenever the tab-singleton
 * {@code wsConnectionStore} flags a {@code bindConflict}: a
 * {@code session-resume} was refused with {@code 409 session_bound_elsewhere}
 * because the same session is held by a live connection of the same user
 * (another window / device).
 *
 * <p>This is the human decision point that breaks the connect/kick
 * ping-pong: the store never auto-escalates to {@code takeover:true}. Only
 * an explicit "take over here" click re-issues the resume with the takeover
 * flag (closing the sibling connection). "Cancel" leaves the session where
 * it is and clears the desired-session so a later reconnect does not re-pop
 * this dialog.
 */
import { computed, ref, watch } from 'vue';
import { useI18n } from 'vue-i18n';
import { VAlert, VButton, VModal } from '@vance/components';
import {
  dismissBindConflict,
  takeoverSession,
  useWsConnection,
} from './wsConnectionStore';

const { t } = useI18n();
const { bindConflict } = useWsConnection();

const loading = ref(false);
const failed = ref(false);

const open = computed(() => bindConflict.value !== null);

// A fresh conflict clears any stale error from a previous attempt.
watch(bindConflict, (v) => {
  if (v !== null) failed.value = false;
});

async function onTakeOver(): Promise<void> {
  loading.value = true;
  failed.value = false;
  try {
    const ok = await takeoverSession();
    failed.value = !ok;
  } finally {
    loading.value = false;
  }
}

function onCancel(): void {
  if (loading.value) return;
  dismissBindConflict();
}
</script>

<template>
  <VModal
    :model-value="open"
    :title="t('sessionTakeover.title')"
    :close-on-backdrop="false"
    @update:model-value="(v) => { if (!v) onCancel(); }"
  >
    <p class="text-sm opacity-80">{{ t('sessionTakeover.body') }}</p>

    <VAlert v-if="failed" variant="error" class="mt-3">
      {{ t('sessionTakeover.failed') }}
    </VAlert>

    <template #actions>
      <VButton variant="ghost" :disabled="loading" @click="onCancel">
        {{ t('sessionTakeover.cancel') }}
      </VButton>
      <VButton
        variant="primary"
        :loading="loading"
        :disabled="loading"
        @click="onTakeOver"
      >
        {{ t('sessionTakeover.takeOver') }}
      </VButton>
    </template>
  </VModal>
</template>
