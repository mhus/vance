<script setup lang="ts">
import { computed, ref } from 'vue';
import { useDocumentPrefixReaction } from '@vance/components';
import DesignerApp from './DesignerApp.vue';

// Wrapper that adapts the kind-registry mount contract (single `document`
// prop carrying the manifest DTO) to DesignerApp's (projectId, folder,
// documentId, title) interface, plus owns the folder-level WS
// subscription so the design view stays WS-free: a remote change under
// the app folder (a new design, an edited stylesheet) reloads the
// catalogue.
const props = defineProps<{
  document: {
    id: string;
    path: string;
    projectId: string;
    title?: string | null;
  };
}>();

const projectId = computed(() => props.document.projectId);
// The manifest lives at <folder>/_app.yaml; the folder is the app root.
const folder = computed(() => props.document.path.replace(/\/_app\.yaml$/, ''));
const title = computed(() => props.document.title ?? folder.value);

const designerRef = ref<InstanceType<typeof DesignerApp> | null>(null);

useDocumentPrefixReaction({
  prefix: computed(() => `${folder.value}/`),
  debounceMs: 120,
  onRemoteChange: async () => {
    await designerRef.value?.reload();
  },
});
</script>

<template>
  <DesignerApp
    ref="designerRef"
    :project-id="projectId"
    :folder="folder"
    :document-id="props.document.id"
    :title="title"
  />
</template>
