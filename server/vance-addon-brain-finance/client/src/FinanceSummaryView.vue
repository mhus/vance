<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import { VAlert, VButton, VModal } from '@vance/components';
import FinanceKind from './FinanceKind.vue';
import { getSnapshot, getTree } from './api';
import type { FinanceNodeDto } from './generated/finance/FinanceNodeDto';
import type { FinanceTreeDto } from './generated/finance/FinanceTreeDto';
import type { NodeSnapshot } from './types';

// Embedded channel: the host passes `document` (resolved doc) and/or
// `embedRef` ({ project, path }); `mode` is 'embedded'. Data-only view —
// full editing happens in the "Bearbeiten" dialog.
const props = defineProps<{
  mode?: string;
  document?: { projectId?: string; path?: string } | null;
  embedRef?: { project?: string; path?: string } | null;
}>();

const projectId = computed(() => props.document?.projectId ?? props.embedRef?.project ?? '');
const path = computed(() => props.document?.path ?? props.embedRef?.path ?? '');

const tree = ref<FinanceTreeDto | null>(null);
const snap = ref<Record<string, NodeSnapshot> | null>(null);
const error = ref<string | null>(null);
const loading = ref(false);
const editOpen = ref(false);

async function load(): Promise<void> {
  if (!projectId.value || !path.value) {
    error.value = 'Cannot resolve the finance document.';
    return;
  }
  loading.value = true;
  error.value = null;
  try {
    tree.value = await getTree(projectId.value, path.value);
    const c = await getSnapshot(projectId.value, path.value);
    const map: Record<string, NodeSnapshot> = {};
    for (const n of c.nodes) map[n.name] = n;
    snap.value = map;
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e);
  } finally {
    loading.value = false;
  }
}

onMounted(load);

function money(v: number | undefined): string {
  if (v === undefined) return '—';
  return v.toLocaleString(undefined, { maximumFractionDigits: 2 });
}

const rootName = computed(() => tree.value?.root?.name ?? null);
const rootPerYear = computed(() => (rootName.value ? snap.value?.[rootName.value]?.perYear : undefined));
const rootOneTime = computed(() => (rootName.value ? snap.value?.[rootName.value]?.oneTimeSum : undefined));
const topChildren = computed<FinanceNodeDto[]>(() => tree.value?.root?.children ?? []);

async function onEditClosed(): Promise<void> {
  await load(); // values may have changed in the dialog
}
</script>

<template>
  <div class="text-sm">
    <VAlert v-if="error" variant="error">{{ error }}</VAlert>
    <div v-else-if="loading" class="opacity-60 p-2">Loading…</div>
    <div v-else-if="tree" class="flex flex-col gap-2 p-2">
      <div class="flex items-center gap-2">
        <span class="font-semibold flex-1 truncate">{{ tree.title || 'Finance tree' }}</span>
        <VButton variant="ghost" @click="editOpen = true">Bearbeiten</VButton>
      </div>

      <div class="flex items-baseline gap-2">
        <span class="text-2xl font-semibold tabular-nums"
              :class="(rootPerYear ?? 0) < 0 ? 'text-red-500' : 'text-green-600'">
          {{ money(rootPerYear) }}
        </span>
        <span class="opacity-60">/ year</span>
      </div>
      <div v-if="rootOneTime" class="opacity-70 text-xs">
        one-time: {{ money(rootOneTime) }}
      </div>

      <ul v-if="topChildren.length" class="flex flex-col gap-0.5 mt-1">
        <li v-for="c in topChildren" :key="c.name" class="flex items-center gap-2">
          <span v-if="c.icon">{{ c.icon }}</span>
          <span class="flex-1 truncate">{{ c.title || c.name }}</span>
          <span class="tabular-nums opacity-70">{{ money(snap?.[c.name]?.perYear) }}</span>
        </li>
      </ul>
      <div v-else class="opacity-50 text-xs">Empty — open to add nodes.</div>
    </div>

    <VModal v-model="editOpen" title="Finance tree" @update:model-value="(o: boolean) => !o && onEditClosed()">
      <div class="h-[70vh]">
        <FinanceKind
          v-if="editOpen && projectId && path"
          :document="{ projectId, path }"
        />
      </div>
    </VModal>
  </div>
</template>
