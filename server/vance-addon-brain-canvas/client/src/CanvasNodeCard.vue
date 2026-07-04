<script setup lang="ts">
import { computed, nextTick, ref } from 'vue';
import { Handle, Position } from '@vue-flow/core';
import type { CanvasNodeDto } from './generated/canvas/CanvasNodeDto';

/**
 * Custom VueFlow node renderer. Reads the canvas node model from
 * {@code data.node} and styles per type — a sticky note for text, cards
 * for doc/link, a dashed frame for group. Text nodes support inline
 * editing (double-click) and node-level bold/italic/fontSize.
 */
const props = defineProps<{
  data: {
    node: CanvasNodeDto;
    editable?: boolean;
    onText?: (id: string, text: string) => void;
  };
  selected?: boolean;
}>();

const PALETTE: Record<string, string> = {
  '1': '#fecaca', '2': '#fed7aa', '3': '#fef08a',
  '4': '#bbf7d0', '5': '#bfdbfe', '6': '#ddd6fe',
};
const FONT_PX: Record<string, string> = { s: '11px', m: '13px', l: '16px' };

const node = computed(() => props.data.node);
const kind = computed(() => node.value.type);
const editable = computed(() => props.data.editable === true);

const bg = computed(() => {
  const c = node.value.color;
  if (c) return PALETTE[c] ?? c;
  return kind.value === 'text' ? '#fef9c3' : '#ffffff';
});

const textStyle = computed(() => ({
  fontSize: FONT_PX[node.value.fontSize ?? 'm'] ?? '13px',
  fontWeight: node.value.bold ? '700' : '400',
  fontStyle: node.value.italic ? 'italic' : 'normal',
}));

function basename(uri: string): string {
  const clean = (uri ?? '').split('?')[0];
  const seg = clean.split('/');
  return seg[seg.length - 1] || uri;
}

// ── Inline editing (text nodes) ───────────────────────────────
const editing = ref(false);
const draft = ref('');
const area = ref<HTMLTextAreaElement | null>(null);

async function beginEdit(): Promise<void> {
  if (!editable.value || kind.value !== 'text') return;
  draft.value = node.value.text ?? '';
  editing.value = true;
  await nextTick();
  area.value?.focus();
  area.value?.select();
}

function commit(): void {
  if (!editing.value) return;
  editing.value = false;
  if (draft.value !== (node.value.text ?? '')) {
    props.data.onText?.(node.value.id, draft.value);
  }
}

function cancel(): void {
  editing.value = false;
}
</script>

<template>
  <div
    class="canvas-node"
    :class="[`canvas-node--${kind}`, { 'canvas-node--selected': selected }]"
    :style="kind === 'group' ? {} : { background: bg }"
    @dblclick.stop="beginEdit"
  >
    <Handle type="target" :position="Position.Left" />
    <Handle type="target" :position="Position.Top" />

    <template v-if="kind === 'text'">
      <textarea
        v-if="editing"
        ref="area"
        v-model="draft"
        class="canvas-note-edit nodrag nowheel"
        :style="textStyle"
        @blur="commit"
        @keydown.esc.prevent="cancel"
        @keydown.enter.exact.stop
      ></textarea>
      <div v-else class="canvas-note-body" :style="textStyle">
        {{ node.text || '(leere Notiz)' }}
      </div>
    </template>

    <template v-else-if="kind === 'doc'">
      <div class="canvas-card-title">📄 {{ basename(node.ref ?? '') }}</div>
      <div class="canvas-card-sub">{{ node.ref }}</div>
    </template>

    <template v-else-if="kind === 'link'">
      <div class="canvas-card-title">🔗 {{ node.title || node.href }}</div>
      <div v-if="node.title" class="canvas-card-sub">{{ node.href }}</div>
    </template>

    <template v-else-if="kind === 'group'">
      <div class="canvas-group-label">{{ node.label || 'Gruppe' }}</div>
    </template>

    <Handle type="source" :position="Position.Right" />
    <Handle type="source" :position="Position.Bottom" />
  </div>
</template>

<style scoped>
.canvas-node {
  width: 100%;
  height: 100%;
  box-sizing: border-box;
  line-height: 1.35;
  color: #1f2937;
}
.canvas-node--selected {
  outline: 2px solid #2563eb;
  outline-offset: 1px;
}

.canvas-node--text {
  border-radius: 4px;
  padding: 10px 12px;
  box-shadow: 0 2px 6px rgba(0, 0, 0, 0.18);
  border: 1px solid rgba(0, 0, 0, 0.06);
}
.canvas-note-body {
  white-space: pre-wrap;
  word-break: break-word;
  overflow: hidden;
  height: 100%;
}
.canvas-note-edit {
  width: 100%;
  height: 100%;
  resize: none;
  border: none;
  outline: none;
  background: transparent;
  font-family: inherit;
  color: inherit;
}

.canvas-node--doc,
.canvas-node--link {
  border: 1px solid #d1d5db;
  border-radius: 8px;
  padding: 8px 10px;
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.12);
  font-size: 12px;
}
.canvas-card-title {
  font-weight: 600;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.canvas-card-sub {
  margin-top: 2px;
  font-size: 10px;
  color: #6b7280;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.canvas-node--group {
  border: 2px dashed #94a3b8;
  border-radius: 10px;
  background: rgba(148, 163, 184, 0.08);
  font-size: 12px;
}
.canvas-group-label {
  padding: 4px 8px;
  font-weight: 600;
  color: #475569;
}
</style>
