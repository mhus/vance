<script setup lang="ts">
import { NodeViewWrapper } from '@tiptap/vue-3';

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
}>();

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
  color: var(--color-text-muted, #6b7280);
  outline: none;
}
.vance-link-card__href-input {
  border: none;
  background: transparent;
  font-size: 0.8em;
  color: var(--color-text-muted, #9ca3af);
  outline: none;
  font-family: monospace;
}
.vance-link-card__title-input::placeholder,
.vance-link-card__description-input::placeholder,
.vance-link-card__href-input::placeholder {
  color: var(--color-text-muted, #9ca3af);
}
.vance-link-card--selected {
  box-shadow: 0 0 0 2px var(--color-link, #3b82f6);
}
</style>
