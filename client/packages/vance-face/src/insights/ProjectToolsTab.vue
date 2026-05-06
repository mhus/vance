<script setup lang="ts">
import { watch } from 'vue';
import { VAlert, VEmptyState } from '@/components';
import { useEffectiveTools } from '@/composables/useProjectInsights';

const props = defineProps<{ projectId: string | null }>();

const state = useEffectiveTools();

watch(
  () => props.projectId,
  (next) => {
    if (next) state.load(next);
    else state.clear();
  },
  { immediate: true },
);

function sourceClass(source: string): string {
  switch (source) {
    case 'PROJECT':
      return 'badge-source badge-source--project';
    case 'VANCE':
      return 'badge-source badge-source--vance';
    case 'BUILTIN':
      return 'badge-source badge-source--builtin';
    default:
      return 'badge-source';
  }
}

function sourceLabel(source: string): string {
  switch (source) {
    case 'PROJECT':
      return 'project';
    case 'VANCE':
      return '_vance';
    case 'BUILTIN':
      return 'built-in';
    default:
      return source.toLowerCase();
  }
}
</script>

<template>
  <div class="flex flex-col gap-3 p-4">
    <div v-if="!projectId" class="opacity-60 text-sm">
      Pick a project in the sidebar to see its effective tools.
    </div>

    <div v-else-if="state.loading.value" class="text-sm opacity-60">Loading tools…</div>

    <VAlert v-else-if="state.error.value" variant="error">
      {{ state.error.value }}
    </VAlert>

    <VEmptyState
      v-else-if="state.tools.value.length === 0"
      :headline="'No tools'"
      :body="'No built-in, tenant or project tools resolve for this project.'"
    />

    <table v-else class="table table-sm">
      <thead>
        <tr>
          <th class="w-40">Name</th>
          <th class="w-24">Source</th>
          <th class="w-20">Type</th>
          <th>Description</th>
          <th class="w-20">Primary</th>
          <th class="w-32">Labels</th>
          <th class="w-12"></th>
        </tr>
      </thead>
      <tbody>
        <tr
          v-for="t in state.tools.value"
          :key="t.name"
          :class="t.disabledByInnerLayer ? 'opacity-50 line-through' : ''"
        >
          <td class="font-mono">{{ t.name }}</td>
          <td>
            <span :class="sourceClass(t.source)">{{ sourceLabel(t.source) }}</span>
          </td>
          <td class="text-xs opacity-80">{{ t.type ?? '—' }}</td>
          <td class="text-xs opacity-80">{{ t.description }}</td>
          <td class="text-xs">
            <span v-if="t.primary" class="text-success">primary</span>
            <span v-else class="opacity-50">on demand</span>
          </td>
          <td class="text-xs">
            <span v-if="t.labels && t.labels.length" class="font-mono opacity-70">
              {{ t.labels.join(', ') }}
            </span>
            <span v-else class="opacity-50">—</span>
          </td>
          <td>
            <span
              v-if="t.disabledByInnerLayer"
              class="text-xs text-error"
              title="Disabled by an inner-layer document"
            >
              ✕
            </span>
          </td>
        </tr>
      </tbody>
    </table>
  </div>
</template>

<style scoped>
.badge-source {
  display: inline-block;
  padding: 0.1rem 0.45rem;
  border-radius: 0.25rem;
  font-size: 0.7rem;
  font-weight: 500;
  text-transform: lowercase;
}
.badge-source--project {
  background: oklch(var(--p) / 0.18);
  color: oklch(var(--p));
}
.badge-source--vance {
  background: oklch(var(--s) / 0.18);
  color: oklch(var(--s));
}
.badge-source--builtin {
  background: oklch(var(--b3));
  color: oklch(var(--bc) / 0.7);
}
</style>
