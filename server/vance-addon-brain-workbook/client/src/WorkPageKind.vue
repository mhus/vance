<script setup lang="ts">
import { computed } from 'vue';
import { BlockView, parseDocument } from '@vance/block-editor';

/**
 * Mount wrapper for `kind: workpage` documents in the Cortex/Notepad
 * tab system. v1 is **read-only** — the rendered Markdown view tab.
 * Edits go through the shell's standard View/Edit toggle into the raw
 * CodeEditor (same pattern Markdown / TeX use). The Tiptap-based
 * editor lives one level up in `WorkbookAppKind.vue` where it has
 * the page-tree context that makes Notion-style editing meaningful.
 *
 * Receives the standard kind-registry `document` prop (raw DTO, no
 * codec). The parser pulls headers + blocks out of the inlineText and
 * BlockView renders them.
 */
const props = defineProps<{
  document: {
    id: string;
    path: string;
    projectId: string;
    title?: string | null;
    inlineText?: string | null;
    mimeType?: string | null;
  };
}>();

const parsed = computed(() => parseDocument(props.document.inlineText ?? ''));
const blocks = computed(() => parsed.value.blocks);
const title = computed(
  () => parsed.value.title ?? props.document.title ?? null,
);
const description = computed(() => parsed.value.description);
const icon = computed(() => parsed.value.icon);
const cover = computed(() => parsed.value.cover);
</script>

<template>
  <div class="canvas-kind">
    <img v-if="cover" :src="cover" alt="" class="canvas-kind__cover" />
    <header v-if="title || description || icon" class="canvas-kind__header">
      <h1 v-if="title || icon" class="canvas-kind__title">
        <span v-if="icon" class="canvas-kind__icon">{{ icon }}</span>
        {{ title }}
      </h1>
      <p v-if="description" class="canvas-kind__description">{{ description }}</p>
    </header>
    <BlockView :blocks="blocks" />
  </div>
</template>

<style scoped>
.canvas-kind {
  height: 100%;
  overflow-y: auto;
  background: oklch(var(--b1));
}
.canvas-kind__header {
  max-width: 760px;
  margin: 0 auto;
  padding: 1.5rem 2rem 0;
  border-bottom: 1px solid oklch(var(--bc) / 0.18);
  margin-bottom: 1rem;
}
.canvas-kind__cover {
  display: block;
  width: 100%;
  max-height: 12rem;
  object-fit: cover;
  background: oklch(var(--bc) / 0.06);
}
.canvas-kind__title {
  font-size: 1.875rem;
  font-weight: 700;
  margin: 0 0 0.25em;
  display: flex;
  align-items: center;
  gap: 0.4em;
}
.canvas-kind__icon {
  font-size: 1.6em;
  line-height: 1;
}
.canvas-kind__description {
  color: oklch(var(--bc) / 0.65);
  margin: 0 0 1rem;
}
</style>
