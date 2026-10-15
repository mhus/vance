<script setup lang="ts">
/**
 * PDF renderer for {@code kind: pdf}. Two modes:
 *
 * - {@code editor} — full doc inline (used by the document editor in
 *   /documents). The browser's native PDF viewer is embedded via
 *   {@code <iframe>}; pagination, zoom, search, print all come from
 *   the browser plugin.
 * - {@code embedded} — chat-friendly card body: only a single "Open
 *   PDF" button. Click opens a fullscreen lightbox with an
 *   {@code <iframe>} pointing at the same content endpoint.
 *
 * Same-origin request carries the {@code vance_access} cookie, so no
 * worker, no canvas, no pdfjs — the browser handles everything.
 *
 * No {@code inline} channel — PDFs aren't markdown text. Spec §8.
 */
import { computed, ref } from 'vue';
import { useI18n } from 'vue-i18n';
import { documentContentUrl } from '@vance/shared';
import type { DocumentDto } from '@vance/generated';
import type { EmbedRef } from '@/kindRenderers/parseVanceUri';
import { VButton, VIconButton } from '@/components';

interface Props {
  mode?: 'editor' | 'embedded';
  document?: DocumentDto;
  embedRef?: EmbedRef;
}

const props = withDefaults(defineProps<Props>(), { mode: 'embedded' });

const { t } = useI18n();

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
    <VButton
      variant="secondary"
      size="sm"
      icon="file-pdf"
      class="self-start"
      @click="openLightbox"
    >{{ t('documents.pdfView.open') }}</VButton>
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
      :title="$t('kindViews.pdf.label')"
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
      <VIconButton
        icon="close"
        class="pdf-view__lightbox-close"
        :label="$t('kindViews.pdf.close')"
        tooltip="left"
        @click="closeLightbox"
      />
      <div class="pdf-view__lightbox-inner" @click.stop>
        <iframe
          v-if="url"
          :src="url"
          :title="$t('kindViews.pdf.label')"
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
  background: var(--color-base-100);
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
  color: white;
}
</style>
