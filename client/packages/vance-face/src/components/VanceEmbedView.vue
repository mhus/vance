<script setup lang="ts">
/**
 * Tiny wrapper around {@link EmbeddedKindBox} that takes a raw
 * {@code vance:} URI string and parses it into an {@link EmbedRef}
 * internally. Designed to be {@code provide}d to deeply-nested
 * Module-Federation remotes (the canvas editor's {@code vanceEmbed}
 * NodeView) so they can render embeds without depending on
 * vance-face's parser at compile time.
 *
 * Spec: specification/inline-and-embedded-content.md §11.
 */
import { computed } from 'vue';
import EmbeddedKindBox from './EmbeddedKindBox.vue';
import { parseVanceUri, VanceUriParseError } from '@/kindRenderers/parseVanceUri';

const props = defineProps<{ uri: string }>();

const parsed = computed(() => {
  try {
    return parseVanceUri(props.uri, { text: '', imageStyle: false });
  } catch (e) {
    if (e instanceof VanceUriParseError) return null;
    throw e;
  }
});
</script>

<template>
  <EmbeddedKindBox v-if="parsed" :embed-ref="parsed" />
  <div v-else class="vance-embed-view__invalid">Invalid embed URI: {{ uri }}</div>
</template>

<style scoped>
.vance-embed-view__invalid {
  padding: 0.5rem;
  font-size: 0.85rem;
  color: hsl(var(--er));
  font-family: monospace;
}
</style>
