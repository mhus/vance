<script setup lang="ts">
import { computed, ref } from 'vue';
import { useDocumentPrefixReaction } from '@vance/components';
import KanbanBoard from './KanbanBoard.vue';

// Wrapper that adapts the kind-registry mount contract (single `document`
// prop carrying the manifest DTO) to KanbanBoard's existing
// (projectId, folder, title) interface, plus owns the folder-level WS
// subscription so the Board stays WS-free.
const props = defineProps<{
  document: {
    id: string;
    path: string;
    projectId: string;
    title?: string | null;
  };
}>();

const projectId = computed(() => props.document.projectId);
const folder = computed(() => props.document.path.replace(/\/_app\.yaml$/, ''));
const title = computed(() => props.document.title ?? folder.value);

const boardRef = ref<InstanceType<typeof KanbanBoard> | null>(null);

useDocumentPrefixReaction({
  prefix: computed(() => `${folder.value}/`),
  debounceMs: 120,
  onRemoteChange: async (paths) => {
    // Pass the changed paths so the board only re-seeds the open card when
    // that card actually changed (and it isn't our own write-echo).
    await boardRef.value?.reload(paths);
  },
});
</script>

<template>
  <KanbanBoard
    ref="boardRef"
    :project-id="projectId"
    :folder="folder"
    :title="title"
  />
</template>
