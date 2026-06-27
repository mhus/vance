<script setup lang="ts">
import { computed } from 'vue';
import CalendarPlanner from './CalendarPlanner.vue';

// Wrapper that adapts the kind-registry mount contract (single `document`
// prop carrying the manifest DTO) to CalendarPlanner's existing
// (projectId, folder, title) interface. Kept thin on purpose — the
// Planner stays free of kind-registry concerns.
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
  <CalendarPlanner :project-id="projectId" :folder="folder" :title="title" />
</template>
