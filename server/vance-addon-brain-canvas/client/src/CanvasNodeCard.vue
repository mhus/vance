<script setup lang="ts">
import { computed, nextTick, ref } from 'vue';
import { Handle, Position } from '@vue-flow/core';
import { NodeResizer } from '@vue-flow/node-resizer';
import '@vue-flow/node-resizer/dist/style.css';
import { NodeToolbar } from '@vue-flow/node-toolbar';
import type { CanvasNodeDto } from './generated/canvas/CanvasNodeDto';

/**
 * Custom VueFlow node. Text nodes support inline editing (double-click),
 * node-level bold/italic/fontSize, and auto-grow height (text always fits,
 * never scrolls). All nodes are resizable and carry a floating
 * {@link NodeToolbar} (colour / font / style / delete) when selected.
 *
 * Exactly one target handle (left) + one source handle (right) — a single
 * handle of each type is unambiguous, so connections land where dropped.
 */
const props = defineProps<{
  data: {
    node: CanvasNodeDto;
    editable?: boolean;
    onText?: (id: string, text: string) => void;
    onResize?: (id: string, w: number, h: number, x: number, y: number) => void;
    onPatch?: (id: string, patch: Partial<CanvasNodeDto>) => void;
    onDelete?: (id: string) => void;
  };
  selected?: boolean;
}>();

const COLORS = ['1', '2', '3', '4', '5', '6'];
const PALETTE: Record<string, string> = {
  '1': '#fecaca', '2': '#fed7aa', '3': '#fef08a',
  '4': '#bbf7d0', '5': '#bfdbfe', '6': '#ddd6fe',
};
const FONT_PX: Record<string, string> = { s: '11px', m: '13px', l: '16px' };

const node = computed(() => props.data.node);
const kind = computed(() => node.value.type);
const editable = computed(() => props.data.editable === true);

const SIDES = ['top', 'right', 'bottom', 'left'] as const;
const POS: Record<string, Position> = {
  top: Position.Top,
  right: Position.Right,
  bottom: Position.Bottom,
  left: Position.Left,
};

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

function patch(p: Partial<CanvasNodeDto>): void {
  props.data.onPatch?.(node.value.id, p);
}

// ── Inline editing (text nodes) ───────────────────────────────
const editing = ref(false);
const draft = ref('');
const area = ref<HTMLTextAreaElement | null>(null);

function autogrow(): void {
  const el = area.value;
  if (el) {
    el.style.height = 'auto';
    el.style.height = `${el.scrollHeight}px`;
  }
}

async function beginEdit(): Promise<void> {
  if (!editable.value || kind.value !== 'text') return;
  draft.value = node.value.text ?? '';
  editing.value = true;
  await nextTick();
  autogrow();
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

function onResizeEnd(e: { params: { x: number; y: number; width: number; height: number } }): void {
  props.data.onResize?.(
    node.value.id,
    Math.round(e.params.width),
    Math.round(e.params.height),
    Math.round(e.params.x),
    Math.round(e.params.y),
  );
}
</script>

<template>
  <div
    class="canvas-node"
    :class="[`canvas-node--${kind}`, { 'canvas-node--selected': selected }]"
    :style="[
      kind === 'group' ? {} : { background: bg },
      kind === 'text' ? { minHeight: (node.h || 80) + 'px' } : { height: '100%' },
    ]"
    @dblclick.stop="beginEdit"
  >
    <NodeToolbar :is-visible="editable && selected === true" :position="Position.Top" :offset="10">
      <div class="cv-toolbar nodrag">
        <button
          class="cv-swatch"
          title="keine Farbe"
          style="background: #ffffff"
          :class="{ 'cv-active': !node.color }"
          @click="patch({ color: undefined })"
        >×</button>
        <button
          v-for="c in COLORS"
          :key="c"
          class="cv-swatch"
          :style="{ background: PALETTE[c] }"
          :class="{ 'cv-active': node.color === c }"
          @click="patch({ color: c })"
        ></button>

        <template v-if="kind === 'text'">
          <span class="cv-sep"></span>
          <button class="cv-btn" :class="{ 'cv-active': (node.fontSize ?? 'm') === 's' }" @click="patch({ fontSize: 's' })">S</button>
          <button class="cv-btn" :class="{ 'cv-active': (node.fontSize ?? 'm') === 'm' }" @click="patch({ fontSize: 'm' })">M</button>
          <button class="cv-btn" :class="{ 'cv-active': (node.fontSize ?? 'm') === 'l' }" @click="patch({ fontSize: 'l' })">L</button>
          <span class="cv-sep"></span>
          <button class="cv-btn" :class="{ 'cv-active': node.bold }" @click="patch({ bold: !node.bold })"><b>B</b></button>
          <button class="cv-btn" :class="{ 'cv-active': node.italic }" @click="patch({ italic: !node.italic })"><i>K</i></button>
        </template>

        <span class="cv-sep"></span>
        <button class="cv-btn cv-danger" title="Löschen" @click="props.data.onDelete?.(node.id)">🗑</button>
      </div>
    </NodeToolbar>

    <NodeResizer
      v-if="editable"
      color="#111827"
      :min-width="120"
      :min-height="40"
      :is-visible="selected === true"
      @resize-end="onResizeEnd"
    />

    <!-- One source + one target connector per side. Unique ids let the
         edge remember exactly which side it attaches to (fromSide/toSide). -->
    <template v-for="s in SIDES" :key="s">
      <Handle
        :id="`t-${s}`"
        type="target"
        :position="POS[s]"
        :connectable="editable"
        class="cv-handle"
        :class="{ 'cv-handle--hidden': !editable }"
      />
      <Handle
        :id="`s-${s}`"
        type="source"
        :position="POS[s]"
        :connectable="editable"
        class="cv-handle"
        :class="{ 'cv-handle--hidden': !editable }"
      />
    </template>

    <template v-if="kind === 'text'">
      <textarea
        v-if="editing"
        ref="area"
        v-model="draft"
        class="canvas-note-edit nodrag nowheel"
        :style="textStyle"
        @input="autogrow"
        @blur="commit"
        @keydown.esc.prevent="cancel"
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
  </div>
</template>

<style scoped>
.canvas-node {
  width: 100%;
  box-sizing: border-box;
  line-height: 1.35;
  color: #1f2937;
}
.canvas-node--selected {
  outline: 2px solid #111827;
  outline-offset: 1px;
}
/* Recolour VueFlow's default (blue) resize border/handles to black. */
:deep(.vue-flow__resize-control.line) {
  border-color: #111827;
}
:deep(.vue-flow__resize-control.handle) {
  background: #111827;
  border-color: #111827;
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
}
.canvas-note-edit {
  width: 100%;
  resize: none;
  border: none;
  outline: none;
  overflow: hidden;
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
  overflow: hidden;
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
  height: 100%;
}
.canvas-group-label {
  padding: 4px 8px;
  font-weight: 600;
  color: #475569;
}

/* Connectors */
.cv-handle {
  width: 5px;
  height: 5px;
  min-width: 5px;
  min-height: 5px;
  background: #94a3b8;
  border: 1px solid #ffffff;
}
.cv-handle--hidden {
  opacity: 0;
  pointer-events: none;
}

/* Floating node toolbar */
.cv-toolbar {
  display: flex;
  align-items: center;
  gap: 3px;
  padding: 4px 6px;
  background: #ffffff;
  border: 1px solid #d1d5db;
  border-radius: 8px;
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.18);
}
.cv-swatch {
  width: 18px;
  height: 18px;
  border-radius: 50%;
  border: 1px solid #cbd5e1;
  cursor: pointer;
  font-size: 10px;
  line-height: 1;
  color: #64748b;
}
.cv-btn {
  min-width: 22px;
  height: 22px;
  padding: 0 5px;
  border: 1px solid transparent;
  border-radius: 5px;
  background: transparent;
  cursor: pointer;
  font-size: 12px;
  color: #1f2937;
}
.cv-btn:hover {
  background: #f1f5f9;
}
.cv-active {
  outline: 2px solid #2563eb;
  outline-offset: 0;
}
.cv-btn.cv-active {
  outline: none;
  background: #dbeafe;
  border-color: #93c5fd;
}
.cv-danger:hover {
  background: #fee2e2;
}
.cv-sep {
  width: 1px;
  height: 18px;
  background: #e2e8f0;
  margin: 0 2px;
}
</style>
