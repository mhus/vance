<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import { vanceNavigate } from '@vance/shared';
import { cortexDeepLink, VAlert, VButton, VCard, VEmptyState } from '@vance/components';
import { getDesktopStatus } from './api';
import type { DesktopView } from './generated/common-desktop/DesktopView';
import type { DesktopCard } from './generated/common-desktop/DesktopCard';
import { useT } from './i18n';

const props = defineProps<{
  projectId: string;
  folder: string;
  title?: string;
}>();

const t = useT();

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

function cardUrl(card: DesktopCard): string | null {
  if (!card.id) return null;
  return cortexDeepLink({ project: props.projectId, documentId: card.id });
}

/** Open an app in the current window (default action). */
function openCard(card: DesktopCard): void {
  const url = cardUrl(card);
  if (url) vanceNavigate(url);
}

/** Open an app in a fresh Cortex tab (secondary action). */
function openCardNewWindow(card: DesktopCard): void {
  const url = cardUrl(card);
  if (url) window.open(url, '_blank', 'noopener');
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
        {{ t('desktop.refresh') }}
      </VButton>
    </div>

    <VAlert v-if="error" variant="error">{{ error }}</VAlert>

    <VEmptyState
      v-else-if="!loading && cards.length === 0"
      :headline="t('desktop.emptyHeadline')"
      :body="t('desktop.emptyBody')"
    />

    <div
      v-else
      class="grid gap-4"
      style="grid-template-columns: repeat(auto-fill, minmax(260px, 1fr));"
    >
      <VCard v-for="card in cards" :key="card.folder" class="h-full">
        <template #header>
          <span class="flex items-center gap-2">
            <span class="text-xl">{{ card.icon }}</span>
            <span>{{ card.title }}</span>
          </span>
        </template>

        <div class="flex flex-col gap-2 flex-1">
          <p v-if="card.description" class="text-sm opacity-70">
            {{ card.description }}
          </p>

          <div v-if="card.status" class="flex flex-col gap-2">
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
        </div>

        <template #actions>
          <VButton size="sm" variant="neutral" @click="openCard(card)">
            {{ t('desktop.open') }}
          </VButton>
          <VButton
            size="sm"
            variant="ghost"
            class="px-2"
            :title="t('desktop.openInNewWindow')"
            @click="openCardNewWindow(card)"
          >
            <svg
              xmlns="http://www.w3.org/2000/svg"
              viewBox="0 0 24 24"
              fill="none"
              stroke="currentColor"
              stroke-width="2"
              class="w-4 h-4"
            >
              <path
                stroke-linecap="round"
                stroke-linejoin="round"
                d="M13.5 6H5.25A2.25 2.25 0 0 0 3 8.25v10.5A2.25 2.25 0 0 0 5.25 21h10.5A2.25 2.25 0 0 0 18 18.75V10.5m-10.5 6L21 3m0 0h-5.25M21 3v5.25"
              />
            </svg>
          </VButton>
        </template>
      </VCard>
    </div>
  </div>
</template>
