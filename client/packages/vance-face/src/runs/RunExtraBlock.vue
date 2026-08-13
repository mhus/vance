<script setup lang="ts">
/**
 * The source-specific part of a run detail.
 *
 * <p>The four common blocks (steps, variables, children, waiting-on) cover
 * most of what a run has to say, but not all of it — a workflow carries
 * its start params, a strategy its engine and goal, a compose run the fact
 * that it will disappear. Flattening those into the shared model would
 * lose exactly the detail someone opened the page for.
 *
 * <p>Dispatch is by source id, and an unknown source renders nothing
 * rather than guessing.
 */
import { computed } from 'vue';
import { useI18n } from 'vue-i18n';
import { VAlert } from '@/components';

const props = defineProps<{ source: string; extra?: Record<string, unknown> | null }>();
const { t } = useI18n();

const entries = computed(() => Object.entries(props.extra ?? {})
  .filter(([, v]) => v !== null && v !== undefined && v !== ''));

const isTransient = computed(() => props.source === 'compose' && props.extra?.transient === true);

function render(value: unknown): string {
  if (typeof value === 'string') return value;
  return JSON.stringify(value, null, 2);
}
</script>

<template>
  <div v-if="entries.length > 0" class="extra">
    <!-- Compose runs live in memory and are swept ten minutes after they
         finish. Saying so is the difference between "gone" and "broken". -->
    <VAlert v-if="isTransient" variant="info" class="transient">
      {{ t('runs.detail.composeTransient') }}
    </VAlert>
    <dl>
      <template v-for="[key, value] in entries" :key="key">
        <dt v-if="key !== 'transient'">{{ key }}</dt>
        <dd v-if="key !== 'transient'"><pre>{{ render(value) }}</pre></dd>
      </template>
    </dl>
  </div>
</template>

<style scoped>
.extra { display: flex; flex-direction: column; gap: 0.5rem; }
.transient { font-size: 0.8rem; }
dl { margin: 0; display: grid; grid-template-columns: max-content 1fr; gap: 0.2rem 0.75rem; }
dt { font-size: 0.72rem; text-transform: uppercase; opacity: 0.6; padding-top: 0.15rem; }
dd { margin: 0; min-width: 0; }
pre {
  margin: 0;
  font-size: 0.75rem;
  white-space: pre-wrap;
  word-break: break-word;
  font-family: ui-monospace, monospace;
}
</style>
