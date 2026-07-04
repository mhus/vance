<script setup lang="ts">
import { onMounted, ref, watch } from 'vue';
import { VAlert } from '@vance/components';
import CanvasEditor from './CanvasEditor.vue';
import { getGraph } from './api';
import type { CanvasGraphDto } from './generated/canvas/CanvasGraphDto';

/**
 * Read-only mount for a standalone `kind: canvas` document. Editing
 * happens inside a canvasbook app; here we only render the board.
 * The graph is fetched from the addon (server parses the YAML).
 */
const props = defineProps<{
  document: { id?: string; path: string; projectId: string };
}>();

const graph = ref<CanvasGraphDto | null>(null);
const error = ref<string | null>(null);
const loading = ref(false);

async function load(): Promise<void> {
  loading.value = true;
  error.value = null;
  try {
    graph.value = await getGraph(props.document.projectId, props.document.path);
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e);
  } finally {
    loading.value = false;
  }
}

onMounted(load);
watch(() => [props.document.projectId, props.document.path], load);
</script>

<template>
  <div class="h-full w-full">
    <VAlert v-if="error" variant="error">{{ error }}</VAlert>
    <div v-else-if="loading" class="p-4 text-sm opacity-60">Lädt…</div>
    <CanvasEditor v-else-if="graph" :graph="graph" :editable="false" />
  </div>
</template>
