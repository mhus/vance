<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import type { EffectiveRecipeDto } from '@vance/generated';
import { VAlert, VCheckbox, VEmptyState, VInput } from '@/components';
import { useEffectiveRecipes } from '@/composables/useProjectInsights';

const props = defineProps<{ projectId: string | null }>();

const state = useEffectiveRecipes();

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
    case 'RESOURCE':
      return 'badge-source badge-source--resource';
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
    case 'RESOURCE':
      return 'bundled';
    default:
      return source.toLowerCase();
  }
}

// ─── Filter / sort state ───────────────────────────────────────────────
type SortKey = 'name' | 'source' | 'engine';
const search = ref('');
const sortKey = ref<SortKey>('name');
const sortAsc = ref(true);
const showProject = ref(true);
const showVance = ref(true);
const showResource = ref(true);
const lockedOnly = ref(false);

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

const filteredRecipes = computed<EffectiveRecipeDto[]>(() => {
  const all = state.recipes.value;
  const q = search.value.trim().toLowerCase();
  const wanted = new Set<string>();
  if (showProject.value) wanted.add('PROJECT');
  if (showVance.value) wanted.add('VANCE');
  if (showResource.value) wanted.add('RESOURCE');

  const out = all.filter((r) => {
    if (!wanted.has(r.source)) return false;
    if (lockedOnly.value && !r.locked) return false;
    if (q.length === 0) return true;
    return (
      (r.name ?? '').toLowerCase().includes(q)
      || (r.description ?? '').toLowerCase().includes(q)
      || (r.engine ?? '').toLowerCase().includes(q)
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
      Pick a project in the sidebar to see its effective recipes.
    </div>

    <div v-else-if="state.loading.value" class="text-sm opacity-60">Loading recipes…</div>

    <VAlert v-else-if="state.error.value" variant="error">
      {{ state.error.value }}
    </VAlert>

    <template v-else-if="state.recipes.value.length === 0">
      <VEmptyState
        :headline="'No recipes available'"
        :body="'No bundled, tenant or project recipes resolve for this project.'"
      />
    </template>

    <template v-else>
      <!-- ─── Toolbar ─── -->
      <div class="flex flex-wrap items-end gap-3 text-sm">
        <div class="flex-1 min-w-48">
          <VInput
            v-model="search"
            label="Search"
            placeholder="name, description, engine…"
          />
        </div>

        <div class="flex flex-col gap-1">
          <span class="text-xs opacity-70">Sources</span>
          <div class="flex gap-2">
            <VCheckbox v-model="showProject" label="project" />
            <VCheckbox v-model="showVance" label="_vance" />
            <VCheckbox v-model="showResource" label="bundled" />
          </div>
        </div>

        <div class="flex flex-col gap-1">
          <span class="text-xs opacity-70">Filter</span>
          <VCheckbox v-model="lockedOnly" label="locked only" />
        </div>

        <div class="text-xs opacity-60 ml-auto">
          {{ filteredRecipes.length }} / {{ state.recipes.value.length }}
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
            <th class="w-24 cursor-pointer select-none" @click="toggleSort('engine')">
              Engine{{ arrow('engine') }}
            </th>
            <th>Description</th>
            <th class="w-16 text-right">Params</th>
            <th class="w-32">Allowed-Tools Δ</th>
            <th class="w-32">Skills</th>
            <th class="w-24">Profiles</th>
          </tr>
        </thead>
        <tbody>
          <tr v-if="filteredRecipes.length === 0">
            <td colspan="8" class="opacity-60 text-center py-4">
              No recipes match the current filters.
            </td>
          </tr>
          <tr v-for="r in filteredRecipes" :key="r.name">
            <td class="font-mono">
              {{ r.name }}
              <span v-if="r.locked" class="opacity-60 text-xs">🔒</span>
            </td>
            <td>
              <span :class="sourceClass(r.source)">{{ sourceLabel(r.source) }}</span>
            </td>
            <td class="font-mono opacity-80">{{ r.engine }}</td>
            <td class="text-xs opacity-80">{{ r.description }}</td>
            <td class="text-right opacity-80">{{ r.paramsCount }}</td>
            <td class="text-xs">
              <span v-if="r.allowedToolsAdd && r.allowedToolsAdd.length" class="text-success">
                +{{ r.allowedToolsAdd.length }}
              </span>
              <span v-if="r.allowedToolsRemove && r.allowedToolsRemove.length" class="text-error ml-1">
                −{{ r.allowedToolsRemove.length }}
              </span>
              <span
                v-if="
                  (!r.allowedToolsAdd || !r.allowedToolsAdd.length)
                    && (!r.allowedToolsRemove || !r.allowedToolsRemove.length)
                "
                class="opacity-50"
              >—</span>
            </td>
            <td class="text-xs">
              <span v-if="r.defaultActiveSkills && r.defaultActiveSkills.length">
                {{ r.defaultActiveSkills.length }} default
              </span>
              <span v-if="r.allowedSkills" class="opacity-70 ml-1">
                · whitelist {{ r.allowedSkills.length }}
              </span>
              <span
                v-if="
                  (!r.defaultActiveSkills || !r.defaultActiveSkills.length) && !r.allowedSkills
                "
                class="opacity-50"
              >—</span>
            </td>
            <td class="text-xs opacity-80">
              <span v-if="r.profileKeys && r.profileKeys.length">
                {{ r.profileKeys.join(', ') }}
              </span>
              <span v-else class="opacity-50">—</span>
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
.badge-source--resource {
  background: oklch(var(--b3));
  color: oklch(var(--bc) / 0.7);
}
</style>
