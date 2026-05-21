<script setup lang="ts">
/**
 * Image renderer for {@code kind: image} (and {@code svg} via the
 * same view). Two modes:
 *
 * - {@code embedded} — load the bytes via {@code documentContentUrl},
 *   show inline with click-to-zoom.
 * - {@code editor}  — full-bleed image in the document editor.
 *
 * No {@code inline} channel: images aren't markdown text. Spec §8.
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

const lightbox = ref(false);

const src = computed<string>(() => {
  const doc = props.document;
  if (!doc) return '';
  // Inline svg: render the text directly via blob URL so DOMPurify
  // catches XSS payloads. Future hardening — for now, only load
  // images we got from the trusted brain endpoint.
  if (doc.inline && (doc.mimeType ?? '').startsWith('image/svg')) {
    if (!doc.inlineText) return '';
    const blob = new Blob([doc.inlineText], { type: doc.mimeType ?? 'image/svg+xml' });
    return URL.createObjectURL(blob);
  }
  if (!doc.id) return '';
  return documentContentUrl(doc.id);
});

const alt = computed<string>(() =>
  props.embedRef?.text || props.document?.title || props.document?.path || 'image',
);

function openLightbox(): void {
  lightbox.value = true;
}

function closeLightbox(): void {
  lightbox.value = false;
}
</script>

<template>
  <div class="image-view" :class="`image-view--${mode}`">
    <div v-if="!src" class="image-view__empty">
      <span class="opacity-60">{{ alt }}</span>
    </div>
    <img
      v-else
      :src="src"
      :alt="alt"
      class="image-view__img"
      @click="openLightbox"
    />
    <p v-if="embedRef?.caption" class="image-view__caption">
      {{ embedRef.caption }}
    </p>

    <!-- Click-to-zoom lightbox. Closes on backdrop or ESC. -->
    <div
      v-if="lightbox"
      class="image-view__lightbox"
      role="dialog"
      tabindex="-1"
      @click="closeLightbox"
      @keydown.escape="closeLightbox"
    >
      <img :src="src" :alt="alt" class="image-view__lightbox-img" />
    </div>
  </div>
</template>

<style scoped>
.image-view {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 0.4rem;
}
.image-view__img {
  max-width: 100%;
  max-height: 32rem;
  height: auto;
  border-radius: 0.375rem;
  cursor: zoom-in;
  background: hsl(var(--bc) / 0.04);
}
.image-view__empty {
  padding: 1rem;
  font-size: 0.85rem;
}
.image-view__caption {
  font-size: 0.85rem;
  opacity: 0.7;
  margin: 0;
}
.image-view--editor .image-view__img {
  max-height: 75vh;
}

.image-view__lightbox {
  position: fixed;
  inset: 0;
  background: rgba(0, 0, 0, 0.78);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 100;
  cursor: zoom-out;
  padding: 2rem;
}
.image-view__lightbox-img {
  max-width: 100%;
  max-height: 100%;
  border-radius: 0.5rem;
}
</style>
