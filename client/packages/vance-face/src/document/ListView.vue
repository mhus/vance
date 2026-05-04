<script setup lang="ts">
import { computed } from 'vue';
import type { ListDocument } from './listItemsCodec';

/**
 * Read-only render of a parsed `kind: list` document. The flat
 * structure mirrors the schema in `specification/doc-kind-items.md`
 * — one row per item, multi-line text preserved as `<br>`-separated
 * lines, no nesting (that is `kind: tree`).
 *
 * The CRUD editor variant builds on top of this component; v1 ships
 * read-only so users can verify the parser against their existing
 * documents before we hand them write tools.
 */
const props = defineProps<{
  doc: ListDocument;
}>();

const items = computed(() => props.doc.items);
</script>

<template>
  <div class="list-view">
    <div v-if="items.length === 0" class="empty">
      —
    </div>
    <ul v-else class="rows">
      <li
        v-for="(item, idx) in items"
        :key="idx"
        class="row"
      >
        <span class="bullet" aria-hidden="true">•</span>
        <span class="text">{{ item.text }}</span>
      </li>
    </ul>
  </div>
</template>

<style scoped>
.list-view {
  font-size: 0.95rem;
}
.empty {
  opacity: 0.6;
  font-style: italic;
  padding: 0.5rem 0.25rem;
}
.rows {
  list-style: none;
  padding: 0;
  margin: 0;
  display: flex;
  flex-direction: column;
}
.row {
  display: grid;
  grid-template-columns: 1.25rem 1fr;
  gap: 0.5rem;
  padding: 0.4rem 0.25rem;
  border-bottom: 1px solid hsl(var(--bc) / 0.08);
}
.row:last-child {
  border-bottom: 0;
}
.bullet {
  opacity: 0.5;
  text-align: center;
  user-select: none;
}
.text {
  white-space: pre-wrap;
  word-break: break-word;
}
</style>
