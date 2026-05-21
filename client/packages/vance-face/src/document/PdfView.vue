<script setup lang="ts">
/**
 * PDF renderer for {@code kind: pdf}. Uses pdfjs-dist to render
 * the first page as a preview in the chat / embedded surface, and
 * the full document in the editor surface.
 *
 * The existing {@link DocumentPreview} component already does PDF
 * rendering for the document editor; here we focus on the embedded
 * channel — small preview, page-count, navigation controls.
 *
 * No {@code inline} channel — PDFs aren't markdown text. Spec §8.
 */
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import { documentContentUrl } from '@vance/shared';
import * as pdfjsLib from 'pdfjs-dist';
// PDF.js v5 worker — Vite bundles it as a URL.
// eslint-disable-next-line @typescript-eslint/ban-ts-comment
// @ts-ignore — Vite asset import resolves at build time.
import pdfWorkerUrl from 'pdfjs-dist/build/pdf.worker.min.mjs?url';
import type { DocumentDto } from '@vance/generated';
import type { EmbedRef } from '@/kindRenderers/parseVanceUri';

interface Props {
  mode?: 'editor' | 'embedded';
  document?: DocumentDto;
  embedRef?: EmbedRef;
}

const props = withDefaults(defineProps<Props>(), { mode: 'embedded' });

(pdfjsLib as unknown as { GlobalWorkerOptions: { workerSrc: string } })
  .GlobalWorkerOptions.workerSrc = pdfWorkerUrl as string;

const canvasRef = ref<HTMLCanvasElement | null>(null);
const pageCount = ref<number>(0);
const currentPage = ref<number>(1);
const loadError = ref<string | null>(null);
const loading = ref<boolean>(false);
let pdfDoc: pdfjsLib.PDFDocumentProxy | null = null;

const url = computed<string>(() => {
  const doc = props.document;
  if (!doc || !doc.id) return '';
  return documentContentUrl(doc.id);
});

async function loadPdf(): Promise<void> {
  if (!url.value) return;
  loading.value = true;
  loadError.value = null;
  try {
    const task = pdfjsLib.getDocument({
      url: url.value,
      // Cookie auth (Web) — let the same-origin request carry it.
      withCredentials: true,
    });
    pdfDoc = await task.promise;
    pageCount.value = pdfDoc.numPages;
    currentPage.value = 1;
    await renderPage(currentPage.value);
  } catch (e) {
    loadError.value = (e as Error).message || 'Failed to load PDF';
  } finally {
    loading.value = false;
  }
}

async function renderPage(num: number): Promise<void> {
  if (!pdfDoc || !canvasRef.value) return;
  const page = await pdfDoc.getPage(num);
  const baseWidth = canvasRef.value.parentElement?.clientWidth ?? 600;
  const viewport = page.getViewport({ scale: 1 });
  const scale = baseWidth / viewport.width;
  const scaled = page.getViewport({ scale: Math.min(scale, 2) });
  const canvas = canvasRef.value;
  const ctx = canvas.getContext('2d');
  if (!ctx) return;
  canvas.width = scaled.width;
  canvas.height = scaled.height;
  await page.render({ canvasContext: ctx, viewport: scaled, canvas }).promise;
}

function prev(): void {
  if (currentPage.value > 1) {
    currentPage.value -= 1;
    void renderPage(currentPage.value);
  }
}

function next(): void {
  if (currentPage.value < pageCount.value) {
    currentPage.value += 1;
    void renderPage(currentPage.value);
  }
}

onMounted(() => { void loadPdf(); });
watch(() => url.value, () => { void loadPdf(); });
onBeforeUnmount(() => {
  void pdfDoc?.destroy();
  pdfDoc = null;
});
</script>

<template>
  <div class="pdf-view" :class="`pdf-view--${mode}`">
    <div v-if="loadError" class="pdf-view__error">{{ loadError }}</div>
    <div v-else-if="loading && pageCount === 0" class="pdf-view__loading">
      <span class="opacity-70">Loading PDF…</span>
    </div>
    <div v-else class="pdf-view__canvas-wrap">
      <canvas ref="canvasRef" class="pdf-view__canvas" />
    </div>
    <div v-if="pageCount > 1" class="pdf-view__nav">
      <button class="pdf-view__nav-btn" :disabled="currentPage <= 1" @click="prev">‹</button>
      <span class="pdf-view__nav-label">{{ currentPage }} / {{ pageCount }}</span>
      <button class="pdf-view__nav-btn" :disabled="currentPage >= pageCount" @click="next">›</button>
    </div>
    <p v-if="embedRef?.caption" class="pdf-view__caption">{{ embedRef.caption }}</p>
  </div>
</template>

<style scoped>
.pdf-view {
  display: flex;
  flex-direction: column;
  gap: 0.4rem;
}
.pdf-view__canvas-wrap {
  background: hsl(var(--bc) / 0.04);
  border-radius: 0.375rem;
  overflow: hidden;
}
.pdf-view__canvas {
  display: block;
  width: 100%;
  height: auto;
  max-height: 32rem;
}
.pdf-view--editor .pdf-view__canvas { max-height: 75vh; }
.pdf-view__error {
  color: hsl(var(--er));
  padding: 0.5rem;
  font-size: 0.85rem;
}
.pdf-view__loading {
  padding: 1rem;
  text-align: center;
}
.pdf-view__nav {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  font-size: 0.85rem;
}
.pdf-view__nav-btn {
  border: 1px solid hsl(var(--bc) / 0.2);
  background: hsl(var(--b1));
  padding: 0.1rem 0.5rem;
  border-radius: 0.25rem;
  cursor: pointer;
}
.pdf-view__nav-btn:disabled { opacity: 0.4; cursor: default; }
.pdf-view__nav-label { opacity: 0.7; }
.pdf-view__caption {
  font-size: 0.85rem;
  opacity: 0.7;
  margin: 0;
}
</style>
