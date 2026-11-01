<script setup lang="ts">
import { computed } from 'vue';
import { useI18n } from 'vue-i18n';
import { ProjectLiveState, RunnerPhase } from '@vance/generated';

/**
 * A small status dot for a project, with the details in its tooltip: what the project is
 * doing and, when a runner is bound, the runner's state. Information only.
 */
interface Props {
  state: ProjectLiveState;
  /** The bound runner, absent when the project has none. */
  runner?: { phase: RunnerPhase; restarts: number } | null;
}
const props = withDefaults(defineProps<Props>(), { runner: null });

const { t } = useI18n();

const colour = computed(() => {
  switch (props.state) {
    case ProjectLiveState.RUNNING:
      return 'bg-(--v-success)';
    case ProjectLiveState.STARTING:
    case ProjectLiveState.STOPPING:
      return 'bg-(--v-warning)';
    case ProjectLiveState.STOPPED:
      return 'bg-(--v-label-tertiary)';
    default:
      return 'bg-(--v-separator)';
  }
});

const hollow = computed(() => props.state === ProjectLiveState.UNKNOWN);

const tooltip = computed(() => {
  const project = t(`projectStatus.state.${props.state}`);
  if (!props.runner) return project;
  const runner = t(`projectStatus.runner.${props.runner.phase}`);
  return `${project} · ${t('projectStatus.runnerLabel')}: ${runner}`;
});
</script>

<template>
  <span
    class="inline-block h-2 w-2 shrink-0 rounded-full"
    :class="[colour, hollow ? 'ring-1 ring-(--v-label-tertiary) ring-inset bg-transparent' : '']"
    :title="tooltip"
    :aria-label="tooltip"
    role="img"
  />
</template>
