<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import { VAlert, VButton, VCard, VEmptyState } from '@vance/components';
import { getDesktopStatus } from './api';
import type { DesktopView } from './generated/common-desktop/DesktopView';
import type { DesktopCard } from './generated/common-desktop/DesktopCard';

const props = defineProps<{
  projectId: string;
  folder: string;
  title?: string;
}>();

const view = ref<DesktopView | null>(null);
const loading = ref(false);
const error = ref<string | null>(null);

const cards = computed<DesktopCard[]>(() => view.value?.cards ?? []);

async function load(): Promise<void> {
  loading.value = true;
  error.value = null;
  try {
    view.value = await getDesktopStatus(props.projectId, props.folder);
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e);
  } finally {
    loading.value = false;
  }
}

/** Open an app in a fresh Cortex tab (same pattern as the canvas jump). */
function openCard(card: DesktopCard): void {
  if (!card.id) return;
  const url = `/cortex.html?project=${encodeURIComponent(props.projectId)}&doc=${encodeURIComponent(card.id)}`;
  window.open(url, '_blank', 'noopener');
}

/** Accent per severity — DaisyUI semantic tokens, theme-aware in both
 *  light and dark (driven by `<html data-theme>` / `.dark`). */
function severityClass(severity?: string | null): string {
  switch (severity) {
    case 'blocked': return 'text-error';
    case 'attention': return 'text-warning';
    default: return 'text-success';
  }
}

onMounted(load);
defineExpose({ reload: load });
</script>

<template>
  <div class="flex flex-col h-full p-4 gap-4 overflow-auto">
    <div class="flex items-center justify-between">
      <h1 class="text-xl font-semibold">{{ title ?? folder }}</h1>
      <VButton variant="ghost" size="sm" :loading="loading" @click="load">
        Refresh
      </VButton>
    </div>

    <VAlert v-if="error" variant="error">{{ error }}</VAlert>

    <VEmptyState
      v-else-if="!loading && cards.length === 0"
      headline="No apps here"
      body="Add an app under this folder, then refresh."
    />

    <div
      v-else
      class="grid gap-4"
      style="grid-template-columns: repeat(auto-fill, minmax(260px, 1fr));"
    >
      <VCard v-for="card in cards" :key="card.folder">
        <template #header>
          <span class="flex items-center gap-2">
            <span class="text-xl">{{ card.icon }}</span>
            <span>{{ card.title }}</span>
          </span>
        </template>

        <p v-if="card.description" class="text-sm opacity-70">
          {{ card.description }}
        </p>

        <div v-if="card.status" class="flex flex-col gap-2 mt-1">
          <p
            v-if="card.status.headline"
            class="text-sm font-medium"
            :class="severityClass(card.status.severity)"
          >
            {{ card.status.headline }}
          </p>

          <div
            v-if="card.status.metrics && card.status.metrics.length"
            class="flex flex-wrap gap-2"
          >
            <span
              v-for="m in card.status.metrics"
              :key="m.label"
              class="text-xs rounded px-2 py-0.5 bg-base-200 text-base-content/80"
            >
              {{ m.label }}: <strong>{{ m.value }}</strong>
            </span>
          </div>

          <ul
            v-if="card.status.items && card.status.items.length"
            class="text-sm flex flex-col gap-1"
          >
            <li
              v-for="(item, i) in card.status.items"
              :key="i"
              class="flex items-center gap-2"
            >
              <span
                class="inline-block w-1.5 h-1.5 rounded-full"
                :class="severityClass(item.severity).replace('text-', 'bg-')"
              />
              <span class="truncate">{{ item.title }}</span>
              <span v-if="item.subtitle" class="opacity-60 truncate">
                — {{ item.subtitle }}
              </span>
            </li>
          </ul>
        </div>

        <template #actions>
          <VButton size="sm" variant="secondary" @click="openCard(card)">
            Open
          </VButton>
        </template>
      </VCard>
    </div>
  </div>
</template>
