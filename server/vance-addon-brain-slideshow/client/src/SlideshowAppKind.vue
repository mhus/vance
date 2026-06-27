<script setup lang="ts">
import { computed, ref } from 'vue';
import { useDocumentPrefixReaction } from '@vance/shared';
import SlideshowApp from './SlideshowApp.vue';

// Wrapper that adapts the kind-registry mount contract (single `document`
// prop carrying the manifest DTO) to SlideshowApp's existing
// (projectId, folder, title) interface, plus owns the folder-level WS
// subscription so the slideshow view stays WS-free.
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

const slideshowRef = ref<InstanceType<typeof SlideshowApp> | null>(null);

useDocumentPrefixReaction({
  prefix: computed(() => `${folder.value}/`),
  debounceMs: 120,
  onRemoteChange: async () => {
    await slideshowRef.value?.reload();
  },
});
</script>

<template>
  <SlideshowApp
    ref="slideshowRef"
    :project-id="projectId"
    :folder="folder"
    :title="title"
  />
</template>
