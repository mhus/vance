<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue';
import { VAlert, VButton } from '@vance/components';
import WidgetNode from './WidgetNode.vue';
import { loadViewByPath } from './api';
import type { RenderedView } from './generated/bistromath/RenderedView';
import { useT } from './i18n';

/**
 * A view document opened on its own — the author's side of Bistromath.
 *
 * <p>What it shows is a **preview with no program**: the real widgets, drawn
 * by the real renderer, against empty state. That is deliberate and it is the
 * whole point. The question an author has while editing a view is "did I write
 * this right", and an outline of the tree would answer a weaker version of it —
 * this shows the actual layout, and a mistake the renderer makes shows up here
 * rather than in the app.
 *
 * <p>What it is **not** is a view builder. Editing stays in the YAML tab.
 *
 * <p>Everything bound to state is empty, because state comes from the program
 * and no program runs here. The banner says so, once, rather than every empty
 * table pretending to be a bug.
 */
const props = defineProps<{
  document: { id?: string; path: string; projectId: string; title?: string | null };
}>();

const t = useT();

const view = ref<RenderedView | null>(null);
const error = ref<string | null>(null);
const busy = ref(false);

/** No program, so nothing ever writes here. Frozen to make that obvious. */
const emptyState: Record<string, unknown> = {};

const appFolder = computed(() => {
  const p = props.document.path;
  const i = p.lastIndexOf('/');
  return i < 0 ? '' : p.slice(0, i);
});

onMounted(load);
watch(() => props.document.path, load);

async function load(): Promise<void> {
  busy.value = true;
  error.value = null;
  try {
    view.value = await loadViewByPath(props.document.projectId, props.document.path);
  } catch (e) {
    // The parser's message names the path inside the document
    // (`.children[3].on.click`), which is the useful part — passed through.
    view.value = null;
    error.value = e instanceof Error ? e.message : String(e);
  } finally {
    busy.value = false;
  }
}

function openApp(): void {
  window.location.search = new URLSearchParams({
    project: props.document.projectId,
    path: `${appFolder.value}/_app.yaml`,
  }).toString();
}
</script>

<template>
  <div class="flex h-full min-h-0 flex-col gap-3 p-3">
    <div class="flex flex-wrap items-center gap-2">
      <span class="font-semibold">{{ view?.title ?? document.title ?? t('bistromath.view.fallbackTitle') }}</span>
      <span class="flex-1" />
      <VButton variant="ghost" size="sm" :disabled="busy" @click="load()">{{ t('bistromath.view.recheck') }}</VButton>
      <VButton variant="ghost" size="sm" :title="t('bistromath.view.openAppTip')" @click="openApp()">
        {{ t('bistromath.view.openApp') }}
      </VButton>
    </div>

    <VAlert v-if="error" variant="error" class="whitespace-pre-line">{{ error }}</VAlert>

    <VAlert v-if="view" variant="info">
      {{ t('bistromath.view.previewPre') }} <code class="font-mono">from:</code>
      {{ t('bistromath.view.previewMid') }} <code class="font-mono">show:</code>
      {{ t('bistromath.view.previewPost') }}
    </VAlert>

    <div v-if="view" class="min-h-0 flex-1 overflow-y-auto rounded border border-base-300 p-3">
      <WidgetNode
        :node="view.root"
        :state="emptyState"
        :record-key="null"
        :resolve="(p) => (p.startsWith('/') ? p.replace(/^\/+/, '') : `${appFolder}/${p}`)"
        @action="() => {}"
        @state="() => {}"
      />
    </div>
  </div>
</template>
