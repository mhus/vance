<script setup lang="ts">
/**
 * PDF renderer for {@code kind: pdf}. Two modes:
 *
 * - {@code editor} — full doc inline (used by the document editor in
 *   documents.html). The browser's native PDF viewer is embedded via
 *   {@code <iframe>}; pagination, zoom, search, print all come from
 *   the browser plugin.
 * - {@code embedded} — chat-friendly card body: only a single "PDF
 *   anzeigen" button. Click opens a fullscreen lightbox with an
 *   {@code <iframe>} pointing at the same content endpoint.
 *
 * Same-origin request carries the {@code vance_access} cookie, so no
 * worker, no canvas, no pdfjs — the browser handles everything.
 *
 * No {@code inline} channel — PDFs aren't markdown text. Spec §8.
 */
import { computed, ref } from 'vue';
import { documentContentUrl } from '@vance/shared';
import type { DocumentDto } from '@vance/generated';
import type { EmbedRef } from '@/kindRenderers/parseVanceUri';

interface Props {
  mode?: 'editor' | 'embedded';
  document?: DocumentDto;
  embedRef?: EmbedRef;
}

const props = withDefaults(defineProps<Props>(), { mode: 'embedded' });

const lightbox = ref<boolean>(false);

const url = computed<string>(() => {
  const doc = props.document;
  if (!doc || !doc.id) return '';
  return documentContentUrl(doc.id);
});

function openLightbox(): void {
  lightbox.value = true;
}

function closeLightbox(): void {
  lightbox.value = false;
}
</script>

<template>
  <!-- Embedded card body: a single button, no iframe yet. Keeps the
       chat stream compact; the actual doc lives behind the lightbox. -->
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

  <!-- Editor mode: native browser viewer, full height. -->
  <div
    v-else
    class="pdf-view pdf-view--editor"
  >
    <iframe
      v-if="url"
      :src="url"
      title="PDF"
      class="pdf-view__frame"
    />
    <p v-if="embedRef?.caption" class="pdf-view__caption">{{ embedRef.caption }}</p>
  </div>

  <!-- Lightbox overlay: full doc render on demand. Teleported to body
       so neighbouring overflow/z-index from the chat bubble can't
       clip it. Backdrop or ESC closes; inner clicks are stopped so
       the user can interact with the iframe without dismissing. -->
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
        <iframe
          v-if="url"
          :src="url"
          title="PDF"
          class="pdf-view__frame pdf-view__frame--lightbox"
        />
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
.pdf-view__frame {
  display: block;
  width: 100%;
  min-height: 75vh;
  border: 0;
  border-radius: 0.375rem;
  background: #fff;
}
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
 * overlays feel like one pattern. */
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
.pdf-view__frame--lightbox {
  flex: 1 1 auto;
  min-height: 0;
  height: 100%;
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
