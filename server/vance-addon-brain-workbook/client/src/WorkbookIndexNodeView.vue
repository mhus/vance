<script setup lang="ts">
import { NodeViewWrapper } from '@tiptap/vue-3';

/**
 * Editor NodeView for the `vance-workbook-index` block (workbook addon's
 * first registry-contributed block). Clicking it dispatches a bubbling
 * DOM event that WorkbookAppKind catches to jump to the workbook index
 * page — same host-signalling pattern as the embed "Open" button
 * (`vance:open-embed`). Addon nodes get no host props via configure(), so
 * a decoupled DOM event is the clean bridge.
 */
function goToIndex(e: MouseEvent) {
  e.preventDefault();
  e.stopPropagation();
  (e.currentTarget as HTMLElement).dispatchEvent(
    new CustomEvent('vance:workbook-goto-index', { bubbles: true }),
  );
}
</script>

<template>
  <NodeViewWrapper as="div" class="wb-index-block" contenteditable="false">
    <button type="button" class="wb-index-block__btn" @click="goToIndex">
      🏠 Zum Workbook-Index
    </button>
  </NodeViewWrapper>
</template>

<style scoped>
.wb-index-block {
  margin: 0.5em 0;
}
.wb-index-block__btn {
  display: inline-flex;
  align-items: center;
  gap: 0.4em;
  padding: 0.5em 0.9em;
  border-radius: 0.5rem;
  cursor: pointer;
  border: 1px solid oklch(var(--bc) / 0.2);
  background: oklch(var(--bc) / 0.06);
  color: inherit;
  font: inherit;
}
.wb-index-block__btn:hover {
  background: oklch(var(--p) / 0.12);
  border-color: oklch(var(--p) / 0.5);
}
</style>
