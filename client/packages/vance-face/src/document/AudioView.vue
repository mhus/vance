<script setup lang="ts">
/**
 * Audio renderer for {@code kind: audio}. Uses the native HTML5
 * {@code <audio>} player; no edit / waveform UI in v1.
 *
 * No {@code inline} channel — audio bytes aren't markdown text.
 * Spec §8.
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
  props.embedRef?.text || props.document?.title || props.document?.path || 'audio',
);

const mimeType = computed<string>(() => props.document?.mimeType ?? 'audio/mpeg');
</script>

<template>
  <div class="audio-view" :class="`audio-view--${mode}`">
    <div v-if="!src" class="audio-view__empty">
      <span class="opacity-60">{{ title }}</span>
    </div>
    <template v-else>
      <p class="audio-view__title">🔊 {{ title }}</p>
      <audio
        controls
        preload="metadata"
        class="audio-view__player"
      >
        <source :src="src" :type="mimeType" />
        Your browser does not support the audio element.
      </audio>
      <p v-if="embedRef?.caption" class="audio-view__caption">
        {{ embedRef.caption }}
      </p>
    </template>
  </div>
</template>

<style scoped>
.audio-view {
  display: flex;
  flex-direction: column;
  gap: 0.4rem;
}
.audio-view__title {
  margin: 0;
  font-size: 0.9rem;
  opacity: 0.8;
}
.audio-view__player {
  width: 100%;
  max-width: 32rem;
}
.audio-view__caption {
  font-size: 0.85rem;
  opacity: 0.7;
  margin: 0;
}
.audio-view__empty {
  padding: 0.5rem;
  font-size: 0.9rem;
}
</style>
