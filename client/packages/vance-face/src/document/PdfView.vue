<script setup lang="ts">
/**
 * PDF renderer for {@code kind: pdf}. Two modes:
 *
 * - {@code editor} — full doc inline (used by the document editor in
 *   documents.html). First page rendered on mount, page-nav strip
 *   underneath.
 * - {@code embedded} — chat-friendly card body: only a single "PDF
 *   anzeigen" button. Click opens a fullscreen lightbox that renders
 *   the full doc with navigation — same lazy-load pattern as the
 *   image lightbox in {@link ImageView}. PDF is too tall to render
 *   inline in a chat stream by default, but a one-click overlay
 *   keeps the document one tap away.
 *
 * No {@code inline} channel — PDFs aren't markdown text. Spec §8.
 */
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import { documentContentUrl } from '@vance/shared';
// Polyfill Map.prototype.getOrInsertComputed before pdfjs initialises —
// pdfjs-dist v5 calls it from its message handler and crashes on
// browsers that haven't shipped the TC39 upsert proposal yet.
import '../polyfills/mapGetOrInsert';
import * as pdfjsLib from 'pdfjs-dist';
import type { DocumentDto } from '@vance/generated';
import type { EmbedRef } from '@/kindRenderers/parseVanceUri';

interface Props {
  mode?: 'editor' | 'embedded';
  document?: DocumentDto;
  embedRef?: EmbedRef;
}

const props = withDefaults(defineProps<Props>(), { mode: 'embedded' });

import { configurePdfWorker } from './pdfWorkerPort';

configurePdfWorker(pdfjsLib.GlobalWorkerOptions);

const canvasRef = ref<HTMLCanvasElement | null>(null);
const pageCount = ref<number>(0);
const currentPage = ref<number>(1);
const loadError = ref<string | null>(null);
const loading = ref<boolean>(false);
const lightbox = ref<boolean>(false);
let pdfDoc: pdfjsLib.PDFDocumentProxy | null = null;

const url = computed<string>(() => {
  const doc = props.document;
  if (!doc || !doc.id) return '';
  return documentContentUrl(doc.id);
});

/** True when a canvas is currently mounted — drives load-on-demand. */
const canvasMounted = computed<boolean>(
  () => props.mode === 'editor' || lightbox.value,
);

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

function openLightbox(): void {
  lightbox.value = true;
}

function closeLightbox(): void {
  lightbox.value = false;
}

function disposeDoc(): void {
  void pdfDoc?.destroy();
  pdfDoc = null;
  pageCount.value = 0;
  currentPage.value = 1;
  loadError.value = null;
}

// Load on mount only for editor mode; embedded waits for the lightbox.
// {@link canvasRef} is bound during the same flush so by the time
// {@link loadPdf} reaches {@link renderPage} the canvas is alive.
onMounted(() => {
  if (canvasMounted.value) void loadPdf();
});

// Re-load when the doc URL changes (editor switches to a different
// PDF tab) or when the lightbox toggles. Closing the lightbox tears
// down the loaded doc — frees the worker-side state plus any large
// page buffers; the next open re-fetches.
watch(() => url.value, () => {
  if (canvasMounted.value) void loadPdf();
});
watch(canvasMounted, (open) => {
  if (open) void loadPdf();
  else disposeDoc();
});

onBeforeUnmount(() => disposeDoc());
</script>

<template>
  <!-- Embedded card body: a single button, no canvas. Keeps the chat
       stream compact; the actual doc lives behind the lightbox. -->
  <div
    v-if="mode === 'embedded'"
    class="pdf-view pdf-view--card"
  >
    <button
      type="button"
      class="pdf-view__open-btn"
      @click="openLightbox"
    >
      <span class="pdf-view__open-icon" aria-hidden="true">📄</span>
      <span>PDF anzeigen</span>
    </button>
    <p v-if="embedRef?.caption" class="pdf-view__caption">
      {{ embedRef.caption }}
    </p>
  </div>

  <!-- Editor mode: full inline render with page navigation. -->
  <div
    v-else
    class="pdf-view pdf-view--editor"
  >
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

  <!-- Lightbox overlay: full doc render on demand. Teleported to body
       so neighbouring overflow/z-index from the chat bubble can't
       clip it. Backdrop or ESC closes; inner clicks are stopped so
       the user can interact with the canvas / nav without dismissing. -->
  <Teleport to="body">
    <div
      v-if="lightbox"
      class="pdf-view__lightbox"
      role="dialog"
      tabindex="-1"
      @click.self="closeLightbox"
      @keydown.escape="closeLightbox"
    >
      <button
        type="button"
        class="pdf-view__lightbox-close"
        aria-label="Close"
        @click="closeLightbox"
      >×</button>
      <div class="pdf-view__lightbox-inner" @click.stop>
        <div v-if="loadError" class="pdf-view__error">{{ loadError }}</div>
        <div v-else-if="loading && pageCount === 0" class="pdf-view__loading">
          <span class="opacity-70">Loading PDF…</span>
        </div>
        <div v-else class="pdf-view__canvas-wrap pdf-view__canvas-wrap--lightbox">
          <canvas ref="canvasRef" class="pdf-view__canvas pdf-view__canvas--lightbox" />
        </div>
        <div v-if="pageCount > 1" class="pdf-view__nav pdf-view__nav--lightbox">
          <button class="pdf-view__nav-btn" :disabled="currentPage <= 1" @click="prev">‹</button>
          <span class="pdf-view__nav-label">{{ currentPage }} / {{ pageCount }}</span>
          <button class="pdf-view__nav-btn" :disabled="currentPage >= pageCount" @click="next">›</button>
        </div>
        <p v-if="embedRef?.caption" class="pdf-view__caption">{{ embedRef.caption }}</p>
      </div>
    </div>
  </Teleport>
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

/* Embedded card: single button, lives inside an EmbeddedKindBox so
 * the surrounding card already provides icon, title and the action
 * row — we only need the call-to-action. */
.pdf-view--card {
  padding: 0.4rem 0;
}
.pdf-view__open-btn {
  display: inline-flex;
  align-items: center;
  gap: 0.45rem;
  align-self: flex-start;
  padding: 0.4rem 0.85rem;
  border: 1px solid hsl(var(--bc) / 0.2);
  border-radius: 0.375rem;
  background: hsl(var(--b1));
  font-size: 0.9rem;
  font-weight: 500;
  cursor: pointer;
  transition: background-color 120ms ease, border-color 120ms ease;
}
.pdf-view__open-btn:hover {
  background: hsl(var(--bc) / 0.08);
  border-color: hsl(var(--bc) / 0.35);
}
.pdf-view__open-btn:focus-visible {
  outline: 2px solid hsl(var(--p));
  outline-offset: 2px;
}
.pdf-view__open-icon {
  font-size: 1.05rem;
  line-height: 1;
}

/* Lightbox: same backdrop treatment as ImageView so the two
 * overlays feel like one pattern. Canvas wrap is sized to fit
 * within the viewport — pdfjs scales to parent width via the
 * existing renderPage() logic. */
.pdf-view__lightbox {
  position: fixed;
  inset: 0;
  background: rgba(0, 0, 0, 0.78);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 100;
  padding: 2rem;
}
.pdf-view__lightbox-inner {
  background: hsl(var(--b1));
  border-radius: 0.5rem;
  padding: 1rem;
  max-width: min(1100px, calc(100vw - 4rem));
  max-height: calc(100vh - 4rem);
  width: 100%;
  display: flex;
  flex-direction: column;
  gap: 0.5rem;
  overflow: hidden;
}
.pdf-view__canvas-wrap--lightbox {
  overflow: auto;
  flex: 1 1 auto;
  min-height: 0;
}
.pdf-view__canvas--lightbox {
  max-height: none;
}
.pdf-view__nav--lightbox {
  justify-content: center;
}
.pdf-view__lightbox-close {
  position: absolute;
  top: 0.75rem;
  right: 1rem;
  background: transparent;
  border: none;
  color: white;
  font-size: 2rem;
  line-height: 1;
  cursor: pointer;
  padding: 0.25rem 0.5rem;
  border-radius: 0.25rem;
}
.pdf-view__lightbox-close:hover {
  background: rgba(255, 255, 255, 0.15);
}
</style>
