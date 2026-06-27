<script setup lang="ts">
import { computed } from 'vue';
import SlideshowApp from './SlideshowApp.vue';

// Wrapper that adapts the kind-registry mount contract (single `document`
// prop carrying the manifest DTO) to SlideshowApp's existing
// (projectId, folder, title) interface.
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
</script>

<template>
  <SlideshowApp :project-id="projectId" :folder="folder" :title="title" />
</template>
