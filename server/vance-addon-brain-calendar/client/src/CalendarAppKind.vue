<script setup lang="ts">
import { computed, ref } from 'vue';
import { useDocumentPrefixReaction } from '@vance/components';
import CalendarPlanner from './CalendarPlanner.vue';

// Wrapper that adapts the kind-registry mount contract (single `document`
// prop carrying the manifest DTO) to CalendarPlanner's existing
// (projectId, folder, title) interface. The wrapper also owns the
// folder-level WS subscription so the Planner stays WS-free.
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

const plannerRef = ref<InstanceType<typeof CalendarPlanner> | null>(null);

// Live-watch the app folder. Any change under it (lane YAMLs, the
// _app.yaml manifest, the generated _gantt.md / _conflicts.yaml) flows
// through one debounced batch — a rebuild that writes two artefacts in
// quick succession produces a single reload, not two.
//
// We don't differentiate by path today: any change triggers a full
// reload. Granular re-fetching (only re-load one lane on lane-write) is
// a future optimisation; correctness comes first.
useDocumentPrefixReaction({
  prefix: computed(() => `${folder.value}/`),
  debounceMs: 120,
  onRemoteChange: async () => {
    await plannerRef.value?.reload();
  },
});
</script>

<template>
  <CalendarPlanner
    ref="plannerRef"
    :project-id="projectId"
    :folder="folder"
    :title="title"
  />
</template>
