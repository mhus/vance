<script setup lang="ts">
/**
 * NodeView for the {@code vanceCallout} block.
 *
 * Two modes, driven by the editor's editable state:
 * - **editable (design mode)**: severity dropdown / title input / body
 *   textarea, written back through {@code updateAttributes}.
 * - **read-only (work mode)**: a rendered callout (icon + title + body),
 *   no input chrome.
 *
 * The block is attribute-only (ProseMirror-atomic); click + key events on
 * the design widgets are stopped so ProseMirror doesn't treat them as
 * node selection / keyboard navigation.
 */
import { computed, onBeforeUnmount, onMounted, ref } from 'vue';
import { NodeViewWrapper } from '@tiptap/vue-3';
import type { Editor } from '@tiptap/core';

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
  editor: Editor;
}>();

const SEVERITIES = [
  { value: 'info', label: 'Info', icon: 'ℹ' },
  { value: 'warn', label: 'Warn', icon: '⚠' },
  { value: 'error', label: 'Error', icon: '✕' },
  { value: 'success', label: 'Success', icon: '✓' },
  { value: 'note', label: 'Note', icon: '•' },
];
const icon = computed(
  () => SEVERITIES.find((s) => s.value === props.node.attrs.severity)?.icon ?? 'ℹ',
);

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
    <!-- DESIGN: editable widgets -->
    <template v-if="editable">
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
    </template>

    <!-- WORK: rendered callout -->
    <div v-else class="vance-callout__rendered" contenteditable="false">
      <span class="vance-callout__icon">{{ icon }}</span>
      <div class="vance-callout__content">
        <div v-if="node.attrs.title" class="vance-callout__title-text">{{ node.attrs.title }}</div>
        <div v-if="node.attrs.body" class="vance-callout__body-text">{{ node.attrs.body }}</div>
      </div>
    </div>
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
  border: 1px solid oklch(var(--bc) / 0.18);
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
  color: oklch(var(--bc) / 0.65);
}
.vance-callout--selected {
  box-shadow: 0 0 0 2px oklch(var(--p));
}

/* Read-only rendered callout (work mode) */
.vance-callout__rendered {
  display: flex;
  gap: 0.6em;
  align-items: flex-start;
}
.vance-callout__icon {
  font-size: 1.1em;
  line-height: 1.4;
  flex-shrink: 0;
}
.vance-callout__content {
  min-width: 0;
  flex: 1;
}
.vance-callout__title-text {
  font-weight: 600;
  margin-bottom: 0.15em;
}
.vance-callout__body-text {
  white-space: pre-wrap;
  color: inherit;
}
</style>
