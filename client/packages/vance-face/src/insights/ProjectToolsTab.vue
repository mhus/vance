<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import type { EffectiveToolDto } from '@vance/generated';
import { VAlert, VCheckbox, VEmptyState, VInput } from '@/components';
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

// ─── Filter / sort state ───────────────────────────────────────────────
type SortKey = 'name' | 'source' | 'type';
const search = ref('');
const sortKey = ref<SortKey>('name');
const sortAsc = ref(true);
const showProject = ref(true);
const showVance = ref(true);
const showBuiltin = ref(true);
const primaryOnly = ref(false);
const showDisabled = ref(true);

function toggleSort(key: SortKey): void {
  if (sortKey.value === key) {
    sortAsc.value = !sortAsc.value;
  } else {
    sortKey.value = key;
    sortAsc.value = true;
  }
}

function arrow(key: SortKey): string {
  if (sortKey.value !== key) return '';
  return sortAsc.value ? ' ▲' : ' ▼';
}

const filteredTools = computed<EffectiveToolDto[]>(() => {
  const all = state.tools.value;
  const q = search.value.trim().toLowerCase();
  const wanted = new Set<string>();
  if (showProject.value) wanted.add('PROJECT');
  if (showVance.value) wanted.add('VANCE');
  if (showBuiltin.value) wanted.add('BUILTIN');

  const out = all.filter((t) => {
    if (!wanted.has(t.source)) return false;
    if (primaryOnly.value && !t.primary) return false;
    if (!showDisabled.value && t.disabledByInnerLayer) return false;
    if (q.length === 0) return true;
    return (
      (t.name ?? '').toLowerCase().includes(q)
      || (t.description ?? '').toLowerCase().includes(q)
      || (t.type ?? '').toLowerCase().includes(q)
      || (t.labels ?? []).some((l) => l.toLowerCase().includes(q))
    );
  });

  const dir = sortAsc.value ? 1 : -1;
  return [...out].sort((a, b) => {
    const av = (a[sortKey.value] ?? '') as string;
    const bv = (b[sortKey.value] ?? '') as string;
    return av.localeCompare(bv) * dir;
  });
});
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

    <template v-else-if="state.tools.value.length === 0">
      <VEmptyState
        :headline="'No tools'"
        :body="'No built-in, tenant or project tools resolve for this project.'"
      />
    </template>

    <template v-else>
      <!-- ─── Toolbar ─── -->
      <div class="flex flex-wrap items-end gap-3 text-sm">
        <div class="flex-1 min-w-48">
          <VInput
            v-model="search"
            label="Search"
            placeholder="name, description, type, label…"
          />
        </div>

        <div class="flex flex-col gap-1">
          <span class="text-xs opacity-70">Sources</span>
          <div class="flex gap-2">
            <VCheckbox v-model="showProject" label="project" />
            <VCheckbox v-model="showVance" label="_vance" />
            <VCheckbox v-model="showBuiltin" label="built-in" />
          </div>
        </div>

        <div class="flex flex-col gap-1">
          <span class="text-xs opacity-70">Filter</span>
          <div class="flex gap-2">
            <VCheckbox v-model="primaryOnly" label="primary only" />
            <VCheckbox v-model="showDisabled" label="show disabled" />
          </div>
        </div>

        <div class="text-xs opacity-60 ml-auto">
          {{ filteredTools.length }} / {{ state.tools.value.length }}
        </div>
      </div>

      <table class="table table-sm">
        <thead>
          <tr>
            <th class="w-40 cursor-pointer select-none" @click="toggleSort('name')">
              Name{{ arrow('name') }}
            </th>
            <th class="w-24 cursor-pointer select-none" @click="toggleSort('source')">
              Source{{ arrow('source') }}
            </th>
            <th class="w-20 cursor-pointer select-none" @click="toggleSort('type')">
              Type{{ arrow('type') }}
            </th>
            <th>Description</th>
            <th class="w-20">Primary</th>
            <th class="w-32">Labels</th>
            <th class="w-12"></th>
          </tr>
        </thead>
        <tbody>
          <tr v-if="filteredTools.length === 0">
            <td colspan="7" class="opacity-60 text-center py-4">
              No tools match the current filters.
            </td>
          </tr>
          <tr
            v-for="t in filteredTools"
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
    </template>
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
