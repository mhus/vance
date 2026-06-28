<script setup lang="ts">
import { NodeViewWrapper } from '@tiptap/vue-3';
import { ref } from 'vue';

const props = defineProps<{
  node: {
    attrs: {
      summary: string;
      body: string;
    };
  };
  updateAttributes: (attrs: Record<string, unknown>) => void;
  selected: boolean;
}>();

const open = ref(true);

function onSummary(e: Event) {
  props.updateAttributes({ summary: (e.target as HTMLInputElement).value });
}
function onBody(e: Event) {
  props.updateAttributes({ body: (e.target as HTMLTextAreaElement).value });
}
</script>

<template>
  <NodeViewWrapper
    as="div"
    class="vance-toggle"
    :class="{ 'vance-toggle--selected': selected }"
  >
    <div class="vance-toggle__header">
      <button
        type="button"
        class="vance-toggle__chevron"
        contenteditable="false"
        :aria-expanded="open"
        @click="open = !open"
        @mousedown.stop
      >{{ open ? '▾' : '▸' }}</button>
      <input
        type="text"
        class="vance-toggle__summary"
        placeholder="Summary…"
        :value="node.attrs.summary"
        contenteditable="false"
        @input="onSummary"
        @mousedown.stop
        @keydown.stop
      />
    </div>
    <textarea
      v-show="open"
      class="vance-toggle__body"
      placeholder="Body (Markdown)…"
      :value="node.attrs.body"
      rows="3"
      contenteditable="false"
      @input="onBody"
      @mousedown.stop
      @keydown.stop
    />
  </NodeViewWrapper>
</template>

<style>
.vance-toggle__header {
  display: flex;
  gap: 0.5rem;
  align-items: center;
}
.vance-toggle__chevron {
  background: transparent;
  border: none;
  cursor: pointer;
  font-size: 0.9em;
  color: oklch(var(--bc) / 0.65);
  width: 1.25em;
  text-align: center;
  flex-shrink: 0;
}
.vance-toggle__summary {
  flex: 1;
  border: none;
  background: transparent;
  font-weight: 500;
  outline: none;
  color: inherit;
  min-width: 0;
}
.vance-toggle__body {
  width: 100%;
  margin-top: 0.4em;
  border: none;
  background: transparent;
  resize: vertical;
  font: inherit;
  color: inherit;
  outline: none;
  padding: 0;
  min-height: 1.5em;
}
.vance-toggle__summary::placeholder,
.vance-toggle__body::placeholder {
  color: oklch(var(--bc) / 0.65);
}
.vance-toggle--selected {
  box-shadow: 0 0 0 2px oklch(var(--p));
}
</style>
