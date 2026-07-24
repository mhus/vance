<script setup lang="ts">
import { computed, ref } from 'vue';
import { useDocumentPrefixReaction } from '@vance/components';
import DesktopBoard from './DesktopBoard.vue';

// Adapts the kind-registry mount contract (single `document` prop
// carrying the manifest DTO) to DesktopBoard's (projectId, folder,
// title) interface, and owns the folder-level WS subscription so the
// board reloads when any app under it changes.
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

const boardRef = ref<InstanceType<typeof DesktopBoard> | null>(null);

useDocumentPrefixReaction({
  prefix: computed(() => `${folder.value}/`),
  debounceMs: 200,
  onRemoteChange: async () => {
    await boardRef.value?.reload();
  },
});
</script>

<template>
  <DesktopBoard
    ref="boardRef"
    :project-id="projectId"
    :folder="folder"
    :title="title"
  />
</template>
