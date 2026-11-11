<script setup lang="ts">
import { onMounted, onUnmounted, ref } from 'vue';
import { VAlert } from '@vance/components';
import FinanceKind from './FinanceKind.vue';
import { getTree, putTree } from './api';
import type { FinanceTreeDoc } from './financeCodec';

// Host for the editor outside the Cortex shell (the summary's Edit dialog).
// There is no shell to own the document here, so this loads and saves through
// the finance REST endpoints, debounced — the one place that still does.
const props = defineProps<{ projectId: string; path: string }>();

const SAVE_DEBOUNCE_MS = 800;

const doc = ref<FinanceTreeDoc | null>(null);
const error = ref<string | null>(null);

let timer: ReturnType<typeof setTimeout> | null = null;
let pending: FinanceTreeDoc | null = null;

onMounted(async () => {
  try {
    doc.value = await getTree(props.projectId, props.path);
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e);
  }
});

async function flush(): Promise<void> {
  if (timer) { clearTimeout(timer); timer = null; }
  const next = pending;
  pending = null;
  if (!next) return;
  try {
    // `$computed` is derived data and not part of the REST DTO.
    const { computed: _derived, ...plain } = next;
    await putTree(props.projectId, props.path, plain);
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e);
  }
}

function onUpdate(next: FinanceTreeDoc): void {
  doc.value = next;
  pending = next;
  if (timer) clearTimeout(timer);
  timer = setTimeout(() => { void flush(); }, SAVE_DEBOUNCE_MS);
}

// Closing the dialog right after an edit must not lose it.
onUnmounted(() => { void flush(); });
</script>

<template>
  <VAlert v-if="error" variant="error">{{ error }}</VAlert>
  <FinanceKind
    v-if="doc"
    :doc="doc"
    :project-id="projectId"
    :doc-path="path"
    @update:doc="onUpdate"
  />
</template>
