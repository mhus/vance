<script setup lang="ts">
import { ref, watch } from 'vue';
import { useI18n } from 'vue-i18n';
import { brainFetch } from '@vance/shared';
import type {
  FookSubmissionRequestDto,
  FookSubmissionResponseDto,
} from '@vance/generated';
import { VAlert, VButton, VModal, VTextarea } from '@vance/components';

/**
 * Free-form bug / feature / feedback report dialog. Lives in the
 * user-menu of {@code EditorTopbar} so reporters can reach it from
 * every editor. The form is intentionally minimal — one textarea.
 * Fook on the server side derives type, title and severity from
 * the text, so the UI doesn't need to ask.
 *
 * <p>If the current URL carries {@code ?project=…} or
 * {@code ?sessionId=…} query parameters, those get forwarded as
 * origin context. The user-menu can be reached from any route, so
 * either may be absent — Fook accepts both as optional.
 */
interface Props {
  modelValue: boolean;
}
const props = defineProps<Props>();
const emit = defineEmits<{
  (e: 'update:modelValue', open: boolean): void;
}>();

const { t } = useI18n();

const text = ref<string>('');
const loading = ref<boolean>(false);
const error = ref<string | null>(null);
const submissionId = ref<string | null>(null);

// Reset state every time the dialog opens. Avoids "previous error
// still visible when I open it again" and clears stale text from a
// successful prior submission.
watch(() => props.modelValue, (open) => {
  if (open) {
    text.value = '';
    error.value = null;
    submissionId.value = null;
    loading.value = false;
  }
});

function close(): void {
  if (loading.value) return;
  emit('update:modelValue', false);
}

function urlParam(name: string): string | undefined {
  const v = new URLSearchParams(window.location.search).get(name);
  return v && v.length > 0 ? v : undefined;
}

async function onSubmit(): Promise<void> {
  const trimmed = text.value.trim();
  if (trimmed.length === 0) {
    error.value = t('fook.errorEmpty');
    return;
  }
  loading.value = true;
  error.value = null;
  try {
    const body: FookSubmissionRequestDto = {
      text: trimmed,
      projectId: urlParam('project'),
      sessionId: urlParam('sessionId'),
    };
    const res = await brainFetch<FookSubmissionResponseDto>(
      'POST', 'fook/submit', { body });
    submissionId.value = res.submissionId;
  } catch (e) {
    error.value = (e as Error).message || t('fook.errorGeneric');
  } finally {
    loading.value = false;
  }
}
</script>

<template>
  <VModal
    :model-value="modelValue"
    :title="t('fook.title')"
    :close-on-backdrop="!loading"
    @update:model-value="(v) => emit('update:modelValue', v)"
  >
    <p class="mb-3 text-sm opacity-80">{{ t('fook.intro') }}</p>

    <VTextarea
      v-if="!submissionId"
      v-model="text"
      :placeholder="t('fook.placeholder')"
      :rows="8"
      :disabled="loading"
    />

    <VAlert
      v-if="submissionId"
      variant="success"
      class="mt-2"
    >
      {{ t('fook.submitted') }}
      <code class="ml-1 font-mono text-xs">{{ submissionId }}</code>
    </VAlert>

    <VAlert v-if="error" variant="error" class="mt-2">{{ error }}</VAlert>

    <template #actions>
      <VButton variant="ghost" :disabled="loading" @click="close">
        {{ submissionId ? t('common.close') : t('common.cancel') }}
      </VButton>
      <VButton
        v-if="!submissionId"
        variant="primary"
        :loading="loading"
        :disabled="loading || text.trim().length === 0"
        @click="onSubmit"
      >{{ t('fook.submit') }}</VButton>
    </template>
  </VModal>
</template>
