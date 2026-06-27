<script setup lang="ts">
import { NodeViewWrapper } from '@tiptap/vue-3';

/**
 * Inline-editable NodeView for the {@code vanceCallout} block.
 * Severity dropdown / title input / body textarea write back through
 * {@code updateAttributes}, which triggers the editor's onUpdate (and
 * thus auto-save). ProseMirror keeps the block "atomic" — we don't
 * embed editable inline content, just attribute-bound form fields.
 *
 * Click + key events on the form widgets are stopped from
 * propagating so ProseMirror doesn't interpret them as node
 * selection / keyboard navigation.
 */
const props = defineProps<{
  node: {
    attrs: {
      severity: string;
      title: string | null;
      body: string;
    };
  };
  updateAttributes: (attrs: Record<string, unknown>) => void;
  selected: boolean;
}>();

const SEVERITIES = [
  { value: 'info', label: 'Info', icon: 'ℹ' },
  { value: 'warn', label: 'Warn', icon: '⚠' },
  { value: 'error', label: 'Error', icon: '✕' },
  { value: 'success', label: 'Success', icon: '✓' },
  { value: 'note', label: 'Note', icon: '•' },
];

function onSeverity(e: Event) {
  props.updateAttributes({ severity: (e.target as HTMLSelectElement).value });
}
function onTitle(e: Event) {
  props.updateAttributes({ title: (e.target as HTMLInputElement).value });
}
function onBody(e: Event) {
  props.updateAttributes({ body: (e.target as HTMLTextAreaElement).value });
}
</script>

<template>
  <NodeViewWrapper
    as="aside"
    class="vance-callout"
    :class="[`vance-callout--${node.attrs.severity}`, { 'vance-callout--selected': selected }]"
  >
    <div class="vance-callout__header">
      <select
        class="vance-callout__severity"
        :value="node.attrs.severity"
        contenteditable="false"
        @change="onSeverity"
        @mousedown.stop
        @keydown.stop
      >
        <option v-for="s in SEVERITIES" :key="s.value" :value="s.value">
          {{ s.icon }} {{ s.label }}
        </option>
      </select>
      <input
        type="text"
        class="vance-callout__title"
        placeholder="Title (optional)"
        :value="node.attrs.title ?? ''"
        contenteditable="false"
        @input="onTitle"
        @mousedown.stop
        @keydown.stop
      />
    </div>
    <textarea
      class="vance-callout__body"
      placeholder="Body…"
      :value="node.attrs.body"
      rows="2"
      contenteditable="false"
      @input="onBody"
      @mousedown.stop
      @keydown.stop
    />
  </NodeViewWrapper>
</template>

<style>
.vance-callout__header {
  display: flex;
  gap: 0.5rem;
  margin-bottom: 0.4em;
}
.vance-callout__severity {
  background: transparent;
  border: 1px solid var(--color-border, #d1d5db);
  border-radius: 0.25rem;
  padding: 0.15em 0.4em;
  font-size: 0.8em;
  font-weight: 500;
  flex-shrink: 0;
  cursor: pointer;
}
.vance-callout__title {
  flex: 1;
  border: none;
  background: transparent;
  font-weight: 600;
  font-size: 1em;
  outline: none;
  color: inherit;
  min-width: 0;
}
.vance-callout__body {
  width: 100%;
  border: none;
  background: transparent;
  resize: vertical;
  font: inherit;
  color: inherit;
  outline: none;
  padding: 0;
  min-height: 1.5em;
}
.vance-callout__title::placeholder,
.vance-callout__body::placeholder {
  color: var(--color-text-muted, #9ca3af);
}
.vance-callout--selected {
  box-shadow: 0 0 0 2px var(--color-link, #3b82f6);
}
</style>
