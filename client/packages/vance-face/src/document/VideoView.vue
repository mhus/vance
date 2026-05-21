<script setup lang="ts">
/**
 * Video renderer for {@code kind: video}. Uses the native HTML5
 * {@code <video>} player. No inline channel — video bytes aren't
 * markdown text. Spec §8.
 */
import { computed } from 'vue';
import { documentContentUrl } from '@vance/shared';
import type { DocumentDto } from '@vance/generated';
import type { EmbedRef } from '@/kindRenderers/parseVanceUri';

interface Props {
  mode?: 'editor' | 'embedded';
  document?: DocumentDto;
  embedRef?: EmbedRef;
}

const props = withDefaults(defineProps<Props>(), { mode: 'embedded' });

const src = computed<string>(() => {
  const doc = props.document;
  if (!doc || !doc.id) return '';
  return documentContentUrl(doc.id);
});

const title = computed<string>(() =>
  props.embedRef?.text || props.document?.title || props.document?.path || 'video',
);

const mimeType = computed<string>(() => props.document?.mimeType ?? 'video/mp4');
</script>

<template>
  <div class="video-view" :class="`video-view--${mode}`">
    <div v-if="!src" class="video-view__empty">
      <span class="opacity-60">{{ title }}</span>
    </div>
    <template v-else>
      <video
        controls
        preload="metadata"
        class="video-view__player"
        :poster="undefined"
      >
        <source :src="src" :type="mimeType" />
        Your browser does not support the video element.
      </video>
      <p v-if="embedRef?.caption" class="video-view__caption">
        {{ embedRef.caption }}
      </p>
    </template>
  </div>
</template>

<style scoped>
.video-view {
  display: flex;
  flex-direction: column;
  gap: 0.4rem;
}
.video-view__player {
  width: 100%;
  max-height: 32rem;
  background: hsl(var(--bc) / 0.04);
  border-radius: 0.375rem;
}
.video-view--editor .video-view__player {
  max-height: 75vh;
}
.video-view__caption {
  font-size: 0.85rem;
  opacity: 0.7;
  margin: 0;
}
.video-view__empty {
  padding: 0.5rem;
  font-size: 0.9rem;
}
</style>
