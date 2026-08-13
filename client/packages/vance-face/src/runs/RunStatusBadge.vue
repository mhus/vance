<script setup lang="ts">
/**
 * The six shared statuses as a badge. Colour groups them the way a
 * reader triages: something is happening (blue), someone is needed
 * (amber), it ended well (green) or it did not (red).
 */
import { computed } from 'vue';
import { useI18n } from 'vue-i18n';
import type { RunStatus } from '@vance/generated';

const props = defineProps<{ status: RunStatus }>();
const { t } = useI18n();

const tone = computed(() => {
  switch (props.status) {
    case 'RUNNING': return 'running';
    case 'WAITING': return 'waiting';
    case 'PAUSED':
    case 'STOPPING': return 'held';
    case 'DONE': return 'done';
    case 'FAILED': return 'failed';
    case 'STOPPED': return 'stopped';
    default: return 'held';
  }
});
</script>

<template>
  <span :class="['run-badge', `run-badge--${tone}`]">
    {{ t(`runs.status.${props.status}`) }}
  </span>
</template>

<style scoped>
.run-badge {
  display: inline-block;
  padding: 0.1rem 0.45rem;
  border-radius: 0.25rem;
  font-size: 0.68rem;
  font-weight: 600;
  text-transform: uppercase;
  letter-spacing: 0.04em;
  white-space: nowrap;
}
.run-badge--running {
  background: color-mix(in oklab, var(--color-info) 22%, transparent);
  color: var(--color-info);
}
.run-badge--waiting {
  background: color-mix(in oklab, var(--color-warning) 22%, transparent);
  color: var(--color-warning);
}
.run-badge--held {
  background: color-mix(in oklab, var(--color-base-content) 12%, transparent);
  opacity: 0.85;
}
.run-badge--done {
  background: color-mix(in oklab, var(--color-success) 20%, transparent);
  color: var(--color-success);
}
.run-badge--failed {
  background: color-mix(in oklab, var(--color-error) 20%, transparent);
  color: var(--color-error);
}
.run-badge--stopped {
  background: color-mix(in oklab, var(--color-base-content) 18%, transparent);
  opacity: 0.75;
}
</style>
