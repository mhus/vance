<script setup lang="ts">
/**
 * Rename the active document — filename only, the folder stays. Same rule
 * as the Documents explorer's row rename: blank names and names with a
 * slash are refused. The parent passes the actual rename call so a server
 * error (e.g. 409 when the target exists) stays inline and the form is kept.
 */
import { nextTick, ref, watch } from 'vue';
import { useI18n } from 'vue-i18n';
import { VAlert, VButton, VInput, VModal } from '@/components';

interface Props {
  open: boolean;
  currentName: string;
  rename: (newName: string) => Promise<void>;
}

const { t } = useI18n();

const props = defineProps<Props>();

const emit = defineEmits<{
  (e: 'update:open', open: boolean): void;
}>();

const name = ref('');
const error = ref<string | null>(null);
const renaming = ref(false);

// showModal() focuses the header's close button; the name field is the one
// the user edits, so it is focused (and the stem selected) explicitly.
const nameInput = ref<InstanceType<typeof VInput> | null>(null);

watch(
  () => props.open,
  (open) => {
    if (!open) return;
    name.value = props.currentName;
    error.value = null;
    nextTick(() => {
      const input = nameInput.value?.input;
      if (!input) return;
      input.focus();
      const dot = props.currentName.lastIndexOf('.');
      input.setSelectionRange(0, dot > 0 ? dot : props.currentName.length);
    });
  },
  { immediate: true },
);

function close(): void {
  emit('update:open', false);
}

async function submit(): Promise<void> {
  const newName = name.value.trim();
  if (!newName || newName.includes('/')) {
    error.value = t('documents.rename.invalid');
    return;
  }
  if (newName === props.currentName) {
    close();
    return;
  }
  renaming.value = true;
  error.value = null;
  try {
    await props.rename(newName);
    close();
  } catch (e) {
    error.value = e instanceof Error ? e.message : t('cortex.renameDocument.failed');
  } finally {
    renaming.value = false;
  }
}
</script>

<template>
  <VModal
    :model-value="open"
    :title="$t('documents.rename.title')"
    size="sm"
    @update:model-value="(v: boolean) => emit('update:open', v)"
  >
    <form id="rename-document-form" class="flex flex-col gap-4" @submit.prevent="submit">
      <VInput
        ref="nameInput"
        v-model="name"
        :label="$t('documents.rename.label')"
        :disabled="renaming"
      />
      <VAlert v-if="error" variant="error">{{ error }}</VAlert>
    </form>

    <template #actions>
      <VButton type="button" variant="secondary" :disabled="renaming" @click="close">
        {{ $t('common.cancel') }}
      </VButton>
      <VButton type="submit" form="rename-document-form" variant="primary" :disabled="renaming">
        {{ $t('documents.rename.confirm') }}
      </VButton>
    </template>
  </VModal>
</template>
