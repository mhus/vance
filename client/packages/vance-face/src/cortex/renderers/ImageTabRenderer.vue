<script setup lang="ts">
import { computed, ref } from 'vue';
import { documentContentUrl } from '@vance/shared';
import type { CortexDocument } from '../types';
import { useCortexStore } from '../stores/cortexStore';

interface Props {
  document: CortexDocument;
}

const props = defineProps<Props>();

// Cortex never emits update events for image tabs in v1 — the renderer
// is read-only. Declared for symmetry with CodeTabRenderer so the host
// can wire @update unconditionally and Vue does not warn about an
// unhandled emit.
defineEmits<{
  (e: 'update', text: string): void;
}>();

const store = useCortexStore();
const reloading = ref(false);

async function onReload(): Promise<void> {
  if (reloading.value) return;
  reloading.value = true;
  try {
    await store.reloadTab(props.document.id);
  } catch (e) {
    console.warn('Failed to reload document', e);
  } finally {
    reloading.value = false;
  }
}

// External-edit deep-link — full metadata editing lives in
// documents.html; cortex just jumps there in a new tab so the user can
// edit title/path/MIME/tags/RAG-mode/etc. without us reimplementing
// the entire properties panel here.
const propertiesUrl = computed<string | null>(() => {
  const pid = store.projectId;
  if (!pid) return null;
  const params = new URLSearchParams({
    projectId: pid,
    documentId: props.document.id,
  });
  return `/documents.html?${params.toString()}`;
});

/**
 * Brain-hosted image URL. The server enforces auth on the
 * {@code documents/{id}/content} endpoint, so we can drop the id
 * straight into an {@code <img>} src without leaking bytes through
 * the JS heap.
 *
 * <p>Inline SVG (where {@code inlineText} carries the markup) renders
 * via a Blob URL so DOMPurify or future CSP rules can intercept; today
 * we trust brain output verbatim. Same posture as
 * {@code document/ImageView.vue} which we deliberately don't share
 * because Cortex's prop surface is its own.
 */
const src = computed<string>(() => {
  const doc = props.document;
  if ((doc.mimeType ?? '').startsWith('image/svg') && doc.inlineText) {
    const blob = new Blob([doc.inlineText], { type: doc.mimeType ?? 'image/svg+xml' });
    return URL.createObjectURL(blob);
  }
  return documentContentUrl(doc.id);
});

const alt = computed<string>(() =>
  props.document.title || props.document.path || 'image',
);
</script>

<template>
  <div class="h-full flex flex-col min-h-0">
    <div class="flex items-center gap-2 px-3 py-2 border-b border-base-300 bg-base-100 text-sm">
      <button
        type="button"
        class="opacity-60 enabled:hover:opacity-100 enabled:hover:bg-base-200 disabled:cursor-default
               rounded px-1 leading-none"
        :disabled="reloading"
        title="Reload from server"
        @click="onReload"
      >
        <span :class="reloading ? 'animate-spin inline-block' : ''">⟳</span>
      </button>
      <span class="font-mono opacity-80 truncate">{{ document.path }}</span>
      <a
        v-if="propertiesUrl"
        :href="propertiesUrl"
        target="_blank"
        rel="noopener"
        class="opacity-60 hover:opacity-100 hover:bg-base-200 rounded px-1 leading-none"
        title="Open document properties in a new tab"
      >↗</a>
      <span class="flex-1" />
      <span class="opacity-50 text-xs font-mono">{{ document.mimeType ?? 'image' }}</span>
    </div>
    <div class="flex-1 min-h-0 overflow-auto bg-base-200/40 flex items-start justify-center p-4">
      <img
        :src="src"
        :alt="alt"
        class="max-w-full h-auto rounded shadow-sm"
        style="max-height: 100%;"
      />
    </div>
  </div>
</template>
