<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue';
import { documentContentUrl } from '@vance/shared';
import * as pdfjsLib from 'pdfjs-dist';
// PDF.js v5 uses an ESM worker that Vite can bundle as a URL via the
// `?url` import. We point pdfjs at it once on first mount — same worker
// instance is reused across renders.
// eslint-disable-next-line @typescript-eslint/ban-ts-comment
// @ts-ignore — Vite asset import resolves at build time.
import pdfWorkerUrl from 'pdfjs-dist/build/pdf.worker.min.mjs?url';

interface Props {
  documentId: string;
  mimeType?: string | null;
  /** True when the source is editable inline-text — preview steps
   *  out of the way for those (the editor handles them). */
  inline?: boolean;
}

const props = defineProps<Props>();

(pdfjsLib as unknown as { GlobalWorkerOptions: { workerSrc: string } })
  .GlobalWorkerOptions.workerSrc = pdfWorkerUrl as string;

const streamUrl = computed(() =>
  props.documentId ? documentContentUrl(props.documentId, false) : '',
);

const kind = computed<'image' | 'pdf' | 'inline' | 'binary'>(() => {
  if (props.inline) return 'inline';
  const mt = (props.mimeType ?? '').toLowerCase();
  if (mt === 'application/pdf') return 'pdf';
  if (mt.startsWith('image/')) return 'image';
  return 'binary';
});

// PDF state — pages render to an array of canvases, drawn lazily
// when the kind switches to `pdf` or the docId changes.
const pdfPages = ref<HTMLCanvasElement[]>([]);
const pdfError = ref<string | null>(null);
const pdfLoading = ref(false);
let activePdfTask: { destroy: () => Promise<void> } | null = null;

async function renderPdf(url: string): Promise<void> {
  pdfError.value = null;
  pdfPages.value = [];
  pdfLoading.value = true;
  try {
    const loadingTask = pdfjsLib.getDocument(url);
    activePdfTask = loadingTask as unknown as { destroy: () => Promise<void> };
    const doc = await loadingTask.promise;
    const canvases: HTMLCanvasElement[] = [];
    for (let i = 1; i <= doc.numPages; i++) {
      const page = await doc.getPage(i);
      // Render at devicePixelRatio for sharp output. Cap scale so a
      // huge PDF doesn't blow the canvas size out.
      const baseViewport = page.getViewport({ scale: 1 });
      const targetWidth = Math.min(baseViewport.width, 900);
      const scale =
        (targetWidth / baseViewport.width) * (window.devicePixelRatio || 1);
      const viewport = page.getViewport({ scale });
      const canvas = document.createElement('canvas');
      canvas.width = Math.floor(viewport.width);
      canvas.height = Math.floor(viewport.height);
      canvas.style.width = `${Math.floor(viewport.width / (window.devicePixelRatio || 1))}px`;
      canvas.style.height = `${Math.floor(viewport.height / (window.devicePixelRatio || 1))}px`;
      const ctx = canvas.getContext('2d');
      if (!ctx) continue;
      // pdfjs v5 expects { canvasContext, viewport, canvas }.
      // eslint-disable-next-line @typescript-eslint/no-explicit-any
      await page.render({ canvasContext: ctx, viewport, canvas } as any).promise;
      canvases.push(canvas);
    }
    pdfPages.value = canvases;
  } catch (e) {
    pdfError.value = e instanceof Error ? e.message : String(e);
  } finally {
    pdfLoading.value = false;
  }
}

async function destroyActivePdf(): Promise<void> {
  if (activePdfTask) {
    try {
      await activePdfTask.destroy();
    } catch {
      // best-effort cleanup
    }
    activePdfTask = null;
  }
}

watch(
  () => [kind.value, streamUrl.value] as const,
  async ([k, url]) => {
    await destroyActivePdf();
    if (k === 'pdf' && url) {
      void renderPdf(url);
    } else {
      pdfPages.value = [];
      pdfError.value = null;
    }
  },
  { immediate: true },
);

onBeforeUnmount(() => {
  void destroyActivePdf();
});

const downloadUrl = computed(() =>
  props.documentId ? documentContentUrl(props.documentId, true) : '',
);

defineExpose({ downloadUrl });
</script>

<template>
  <div class="document-preview">
    <!-- Inline-text — handled by the parent's editor; we render nothing. -->
    <div v-if="kind === 'inline'" />

    <!-- Image: direct <img>, capped via CSS. -->
    <div v-else-if="kind === 'image'" class="flex justify-center">
      <img
        :src="streamUrl"
        :alt="mimeType ?? 'image'"
        class="document-preview__image"
        loading="lazy"
      />
    </div>

    <!-- PDF rendered with PDF.js — pages stacked vertically. -->
    <div v-else-if="kind === 'pdf'" class="flex flex-col items-center gap-3">
      <div v-if="pdfLoading" class="text-sm opacity-70">
        {{ $t('documents.preview.pdfRendering') }}
      </div>
      <div v-if="pdfError" class="text-sm text-error">
        {{ $t('documents.preview.pdfError', { error: pdfError }) }}
      </div>
      <div
        v-for="(canvas, idx) in pdfPages"
        :key="idx"
        class="document-preview__pdf-page"
        :ref="(el) => attachCanvas(el as HTMLElement | null, canvas)"
      />
    </div>

    <!-- Binary: nothing useful to render in-page. The parent shows
         a Download button. -->
    <div v-else class="text-sm opacity-70 italic">
      {{ $t('documents.preview.binary') }}
    </div>
  </div>
</template>

<script lang="ts">
// Helper used by the v-for ref to attach the canvas the PDF.js
// rendered into. Defining it in a module-level <script> block
// keeps it out of the reactive setup state.
function attachCanvas(host: HTMLElement | null, canvas: HTMLCanvasElement): void {
  if (!host) return;
  // Replace any prior canvas (re-renders) so the DOM stays clean.
  while (host.firstChild) host.removeChild(host.firstChild);
  host.appendChild(canvas);
}
export { attachCanvas };
</script>

<style scoped>
.document-preview__image {
  max-width: 100%;
  max-height: 70vh;
  object-fit: contain;
  border-radius: 0.5rem;
}

.document-preview__pdf-page {
  display: block;
  box-shadow: 0 0 0 1px rgba(0, 0, 0, 0.08);
  background: #fff;
}
</style>
