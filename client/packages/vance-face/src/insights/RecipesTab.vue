<script setup lang="ts">
import { computed, watch } from 'vue';
import type { EffectiveRecipeDto } from '@vance/generated';
import { VAlert, VEmptyState } from '@/components';
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

const sortedRecipes = computed<EffectiveRecipeDto[]>(() => state.recipes.value);
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

    <VEmptyState
      v-else-if="sortedRecipes.length === 0"
      :headline="'No recipes available'"
      :body="'No bundled, tenant or project recipes resolve for this project.'"
    />

    <table v-else class="table table-sm">
      <thead>
        <tr>
          <th class="w-40">Name</th>
          <th class="w-24">Source</th>
          <th class="w-24">Engine</th>
          <th>Description</th>
          <th class="w-16 text-right">Params</th>
          <th class="w-32">Allowed-Tools Δ</th>
          <th class="w-32">Skills</th>
          <th class="w-24">Profiles</th>
        </tr>
      </thead>
      <tbody>
        <tr v-for="r in sortedRecipes" :key="r.name">
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
