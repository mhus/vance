<script setup lang="ts">
/**
 * Simple "stage a virtual folder" modal. The server has no folder
 * entity — folders exist implicitly via document path prefixes — so
 * this just hands the entered path back to the parent, which calls
 * the store's {@code addVirtualFolder} to display it in the tree.
 */
import { ref, watch } from 'vue';
import { useI18n } from 'vue-i18n';
import { VAlert, VButton, VInput, VModal } from '@/components';

interface Props {
  open: boolean;
  initialPath?: string;
}

const { t } = useI18n();

const props = withDefaults(defineProps<Props>(), {
  initialPath: '',
});

const emit = defineEmits<{
  (e: 'update:open', open: boolean): void;
  (e: 'confirm', path: string): void;
}>();

const path = ref('');
const error = ref<string | null>(null);

watch(
  () => props.open,
  (open) => {
    if (!open) return;
    path.value = props.initialPath ?? '';
    error.value = null;
  },
  { immediate: true },
);

function close(): void {
  emit('update:open', false);
}

function submit(): void {
  const normalised = path.value.trim().replace(/^\/+|\/+$/g, '');
  if (!normalised) {
    error.value = t('cortex.pathRequired');
    return;
  }
  emit('confirm', normalised);
}
</script>

<template>
  <VModal
    :model-value="open"
    :title="$t('cortex.newFolder.title')"
    :description="$t('cortex.newFolder.description')"
    size="sm"
    @update:model-value="(v: boolean) => emit('update:open', v)"
  >
    <form id="new-folder-form" class="flex flex-col gap-4" @submit.prevent="submit">
      <VInput
        v-model="path"
        :label="$t('cortex.newFolder.pathLabel')"
        :placeholder="$t('cortex.newFolder.pathPlaceholder')"
      />
      <VAlert v-if="error" variant="error">{{ error }}</VAlert>
    </form>

    <template #actions>
      <VButton type="button" variant="secondary" @click="close">{{ $t('cortex.newFolder.cancel') }}</VButton>
      <VButton type="submit" form="new-folder-form" variant="primary">{{ $t('cortex.newFolder.create') }}</VButton>
    </template>
  </VModal>
</template>
