<script setup lang="ts">
import { computed } from 'vue';
import { BlockView, parseDocument } from '@vance/block-editor';

/**
 * Mount wrapper for `kind: canvas` documents in the Cortex/Notepad
 * tab system. v1 is **read-only** — the rendered Markdown view tab.
 * Edits go through the shell's standard View/Edit toggle into the raw
 * CodeEditor (same pattern Markdown / TeX use). The Tiptap-based
 * editor lives one level up in `WorkspaceAppKind.vue` where it has
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
</script>

<template>
  <div class="canvas-kind">
    <header v-if="title || description" class="canvas-kind__header">
      <h1 v-if="title" class="canvas-kind__title">{{ title }}</h1>
      <p v-if="description" class="canvas-kind__description">{{ description }}</p>
    </header>
    <BlockView :blocks="blocks" />
  </div>
</template>

<style scoped>
.canvas-kind {
  height: 100%;
  overflow-y: auto;
  background: var(--color-bg, #fff);
}
.canvas-kind__header {
  max-width: 760px;
  margin: 0 auto;
  padding: 1.5rem 2rem 0;
  border-bottom: 1px solid var(--color-border, #e5e7eb);
  margin-bottom: 1rem;
}
.canvas-kind__title {
  font-size: 1.875rem;
  font-weight: 700;
  margin: 0 0 0.25em;
}
.canvas-kind__description {
  color: var(--color-text-muted, #6b7280);
  margin: 0 0 1rem;
}
</style>
