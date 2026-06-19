<script setup lang="ts">
/**
 * Simple "stage a virtual folder" modal. The server has no folder
 * entity — folders exist implicitly via document path prefixes — so
 * this just hands the entered path back to the parent, which calls
 * the store's {@code addVirtualFolder} to display it in the tree.
 */
import { ref, watch } from 'vue';
import { VAlert, VButton, VInput, VModal } from '@/components';

interface Props {
  open: boolean;
  initialPath?: string;
}

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
    error.value = 'Path required';
    return;
  }
  emit('confirm', normalised);
}
</script>

<template>
  <VModal
    :model-value="open"
    title="New folder"
    @update:model-value="(v: boolean) => emit('update:open', v)"
  >
    <form class="space-y-3 p-2" @submit.prevent="submit">
      <VInput
        v-model="path"
        label="Folder path"
        placeholder="documents/notes"
      />
      <p class="text-xs opacity-60">
        Folders are virtual until a file lives in them — this entry is
        client-side and disappears on refresh unless something gets
        moved into it.
      </p>
      <VAlert v-if="error" variant="error">{{ error }}</VAlert>
      <div class="flex justify-end gap-2 pt-2">
        <VButton type="button" variant="ghost" @click="close">Cancel</VButton>
        <VButton type="submit" variant="primary">Create</VButton>
      </div>
    </form>
  </VModal>
</template>
