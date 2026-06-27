<script setup lang="ts">
import { computed, onMounted, onBeforeUnmount, ref } from 'vue';
import { NodeViewWrapper } from '@tiptap/vue-3';
import type { Editor } from '@tiptap/core';

/**
 * Live table-of-contents NodeView. Subscribes to editor transactions
 * and re-renders whenever a heading is added / changed / removed.
 * Click on an entry scrolls the heading into view via its slug-id
 * (added by the heading-anchor pass on render).
 */
const props = defineProps<{
  editor: Editor;
}>();

interface TocEntry {
  level: number;
  text: string;
  slug: string;
}

const entries = ref<TocEntry[]>([]);

function slug(text: string): string {
  return text
    .toLowerCase()
    .replace(/[^a-z0-9\s-]/g, '')
    .replace(/\s+/g, '-')
    .replace(/-+/g, '-')
    .replace(/^-|-$/g, '');
}

function collect() {
  const list: TocEntry[] = [];
  props.editor.state.doc.descendants((node) => {
    if (node.type.name !== 'heading') return;
    const text = node.textContent.trim();
    if (!text) return;
    list.push({
      level: (node.attrs.level as number) ?? 1,
      text,
      slug: slug(text),
    });
  });
  entries.value = list;
}

function scrollTo(entry: TocEntry) {
  // Anchors are emitted by the heading id-decoration plugin on render.
  const el = document.getElementById(entry.slug);
  if (el) {
    el.scrollIntoView({ behavior: 'smooth', block: 'start' });
    return;
  }
  // Fallback: walk the doc and set selection at the heading position.
  let pos = -1;
  props.editor.state.doc.descendants((node, nodePos) => {
    if (pos >= 0) return false;
    if (node.type.name === 'heading' && node.textContent.trim() === entry.text) {
      pos = nodePos;
      return false;
    }
    return true;
  });
  if (pos >= 0) {
    props.editor.commands.focus(pos);
  }
}

function onTransaction() {
  collect();
}

const hasEntries = computed(() => entries.value.length > 0);

onMounted(() => {
  collect();
  props.editor.on('transaction', onTransaction);
});

onBeforeUnmount(() => {
  props.editor.off('transaction', onTransaction);
});
</script>

<template>
  <NodeViewWrapper as="aside" class="vance-toc" contenteditable="false">
    <div class="vance-toc__label">Inhaltsverzeichnis</div>
    <div v-if="!hasEntries" class="vance-toc__empty">
      Noch keine Überschriften auf dieser Seite.
    </div>
    <ul v-else class="vance-toc__list">
      <li
        v-for="(e, i) in entries"
        :key="i"
        class="vance-toc__item"
        :class="`vance-toc__item--h${e.level}`"
      >
        <button
          type="button"
          class="vance-toc__link"
          @click="scrollTo(e)"
          @mousedown.stop
        >{{ e.text }}</button>
      </li>
    </ul>
  </NodeViewWrapper>
</template>

<style>
.vance-toc {
  border: 1px solid var(--color-border, #e5e7eb);
  border-radius: 0.5rem;
  padding: 0.75em 1em;
  margin: 0.75em 0;
  background: var(--color-button-bg, #fafafa);
}
.vance-toc__label {
  font-size: 0.7rem;
  text-transform: uppercase;
  letter-spacing: 0.05em;
  color: var(--color-text-muted, #6b7280);
  margin-bottom: 0.5em;
}
.vance-toc__empty {
  color: var(--color-text-muted, #9ca3af);
  font-style: italic;
  font-size: 0.9rem;
}
.vance-toc__list {
  list-style: none;
  padding: 0;
  margin: 0;
}
.vance-toc__item--h1 { padding-left: 0; }
.vance-toc__item--h2 { padding-left: 1rem; }
.vance-toc__item--h3 { padding-left: 2rem; }
.vance-toc__link {
  background: none;
  border: none;
  padding: 0.2em 0;
  cursor: pointer;
  text-align: left;
  color: var(--color-link, #2563eb);
  font-size: 0.9rem;
  text-decoration: none;
}
.vance-toc__link:hover { text-decoration: underline; }
</style>
