<script setup lang="ts">
/**
 * NodeView for the `vance-link` rich-link card (slash `/link`).
 *
 * Two modes, driven by the editor's editable state:
 * - **editable (design mode)**: the title / description / href input
 *   fields, so the user can author the card.
 * - **read-only (work mode)**: a rendered, clickable link card. The
 *   anchor's click is routed by the editor's DOM-level link handler
 *   (vance: → host navigation, external → new tab).
 */
import { onBeforeUnmount, onMounted, ref } from 'vue';
import { NodeViewWrapper } from '@tiptap/vue-3';
import { safeHref } from '../safeHref';
import type { Editor } from '@tiptap/core';

const props = defineProps<{
  node: {
    attrs: {
      href: string;
      title: string | null;
      description: string | null;
    };
  };
  updateAttributes: (attrs: Record<string, unknown>) => void;
  selected: boolean;
  editor: Editor;
}>();

const editable = ref(props.editor.isEditable);
function syncEditable() { editable.value = props.editor.isEditable; }
onMounted(() => {
  props.editor.on('update', syncEditable);
  props.editor.on('transaction', syncEditable);
});
onBeforeUnmount(() => {
  props.editor.off('update', syncEditable);
  props.editor.off('transaction', syncEditable);
});

function onTitle(e: Event) {
  props.updateAttributes({ title: (e.target as HTMLInputElement).value || null });
}
function onDescription(e: Event) {
  props.updateAttributes({ description: (e.target as HTMLInputElement).value || null });
}
function onHref(e: Event) {
  props.updateAttributes({ href: (e.target as HTMLInputElement).value });
}
</script>

<template>
  <NodeViewWrapper
    as="div"
    class="vance-link-card"
    :class="{ 'vance-link-card--selected': selected }"
  >
    <!-- DESIGN: editable inputs -->
    <template v-if="editable">
      <input
        type="text"
        class="vance-link-card__title-input"
        placeholder="Link title…"
        :value="node.attrs.title ?? ''"
        contenteditable="false"
        @input="onTitle"
        @mousedown.stop
        @keydown.stop
      />
      <input
        type="text"
        class="vance-link-card__description-input"
        placeholder="Description (optional)…"
        :value="node.attrs.description ?? ''"
        contenteditable="false"
        @input="onDescription"
        @mousedown.stop
        @keydown.stop
      />
      <input
        type="url"
        class="vance-link-card__href-input"
        placeholder="https://…"
        :value="node.attrs.href"
        contenteditable="false"
        @input="onHref"
        @mousedown.stop
        @keydown.stop
      />
    </template>

    <!-- WORK: rendered clickable link card -->
    <a
      v-else
      class="vance-link-card__link"
      :href="safeHref(node.attrs.href)"
      :title="node.attrs.href"
      contenteditable="false"
    >
      <span class="vance-link-card__title-text">{{ node.attrs.title || node.attrs.href }}</span>
      <span v-if="node.attrs.description" class="vance-link-card__desc-text">
        {{ node.attrs.description }}
      </span>
      <span class="vance-link-card__url-text">{{ node.attrs.href }}</span>
    </a>
  </NodeViewWrapper>
</template>

<style>
.vance-link-card {
  display: flex;
  flex-direction: column;
  gap: 0.2em;
}
.vance-link-card__title-input {
  border: none;
  background: transparent;
  font-weight: 600;
  outline: none;
  color: inherit;
}
.vance-link-card__description-input {
  border: none;
  background: transparent;
  font-size: 0.9em;
  color: oklch(var(--bc) / 0.65);
  outline: none;
}
.vance-link-card__href-input {
  border: none;
  background: transparent;
  font-size: 0.8em;
  color: oklch(var(--bc) / 0.65);
  outline: none;
  font-family: monospace;
}
.vance-link-card__title-input::placeholder,
.vance-link-card__description-input::placeholder,
.vance-link-card__href-input::placeholder {
  color: oklch(var(--bc) / 0.65);
}
.vance-link-card--selected {
  box-shadow: 0 0 0 2px oklch(var(--p));
}

/* Read-only rendered card (work mode) */
.vance-link-card__link {
  display: flex;
  flex-direction: column;
  gap: 0.15em;
  padding: 0.6em 0.8em;
  border: 1px solid oklch(var(--bc) / 0.18);
  border-radius: 0.5rem;
  text-decoration: none;
  color: inherit;
  background: oklch(var(--bc) / 0.04);
  transition: border-color 0.15s ease, background 0.15s ease;
  cursor: pointer;
}
.vance-link-card__link:hover {
  border-color: oklch(var(--p));
  background: oklch(var(--bc) / 0.07);
}
.vance-link-card__title-text {
  font-weight: 600;
}
.vance-link-card__desc-text {
  font-size: 0.9em;
  color: oklch(var(--bc) / 0.7);
}
.vance-link-card__url-text {
  font-size: 0.78em;
  color: oklch(var(--bc) / 0.55);
  font-family: monospace;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
</style>
