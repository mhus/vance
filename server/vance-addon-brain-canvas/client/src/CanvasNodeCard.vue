<script setup lang="ts">
import { computed, nextTick, onMounted, ref, watch } from 'vue';
import type { Component } from 'vue';
import { Handle, Position } from '@vue-flow/core';
import { NodeResizer } from '@vue-flow/node-resizer';
import '@vue-flow/node-resizer/dist/style.css';
import { NodeToolbar } from '@vue-flow/node-toolbar';
import { brainFetchText, documentContentUrl } from '@vance/shared';
import { marked } from 'marked';
import DOMPurify from 'dompurify';
import type { CanvasNodeDto } from './generated/canvas/CanvasNodeDto';
import type { CanvasDocItem } from './generated/canvas/CanvasDocItem';
import { resolveDocument } from './api';

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
    projectId?: string;
    embedComponent?: Component | null;
    onText?: (id: string, text: string) => void;
    onResize?: (id: string, w: number, h: number, x: number, y: number) => void;
    onPatch?: (id: string, patch: Partial<CanvasNodeDto>) => void;
    onDelete?: (id: string) => void;
    onFront?: (id: string) => void;
    onBack?: (id: string) => void;
  };
  selected?: boolean;
}>();

const TEXT_COLORS = ['#111827', '#dc2626', '#2563eb', '#16a34a', '#ca8a04'];

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

function hexToRgba(hex: string, a: number): string {
  const m = /^#?([0-9a-fA-F]{6})$/.exec(hex.trim());
  if (!m) return hex;
  const n = Number.parseInt(m[1], 16);
  return `rgba(${(n >> 16) & 255}, ${(n >> 8) & 255}, ${n & 255}, ${a})`;
}

const groupColor = computed(() =>
  node.value.color ? (PALETTE[node.value.color] ?? node.value.color) : null,
);
const groupBg = computed(() =>
  groupColor.value ? hexToRgba(groupColor.value, 0.18) : 'rgba(148, 163, 184, 0.08)',
);
const groupBorder = computed(() => groupColor.value ?? '#94a3b8');

const textStyle = computed(() => ({
  fontSize: FONT_PX[node.value.fontSize ?? 'm'] ?? '13px',
  fontWeight: node.value.bold ? '700' : '400',
  fontStyle: node.value.italic ? 'italic' : 'normal',
  color: node.value.textColor ?? '#1f2937',
}));

function basename(uri: string): string {
  const clean = (uri ?? '').split('?')[0];
  const seg = clean.split('/');
  return seg[seg.length - 1] || uri;
}

// ── Embedded document (doc nodes) ─────────────────────────────
// Host-provided kind renderer, threaded in via node data by CanvasEditor
// (cortex `provide('vance:embed-component', …)`). When present it renders ANY
// kind (records/mindmap/chart/…) via the original vance-face renderer;
// otherwise we fall back to image/markdown/card below.
const embedComponent = computed(() => props.data.embedComponent ?? null);

const docMeta = ref<CanvasDocItem | null>(null);
const imgSrc = ref<string | null>(null);
const mdHtml = ref<string | null>(null);
const docError = ref<string | null>(null);

function refToPath(ref: string): string {
  let s = ref ?? '';
  if (s.startsWith('vance:')) s = s.slice('vance:'.length);
  return s.replace(/^\/+/, '').split('?')[0];
}

async function loadDoc(): Promise<void> {
  docMeta.value = null;
  imgSrc.value = null;
  mdHtml.value = null;
  docError.value = null;
  if (kind.value !== 'doc') return;
  const pid = props.data.projectId;
  const ref = node.value.ref;
  if (!pid || !ref) return;
  const path = refToPath(ref);
  try {
    const meta = await resolveDocument(pid, path);
    docMeta.value = meta;
    // Priority: if the doc has a real `kind` and the host renderer is
    // available, let it render (records/mindmap/chart/markdown-as-kind/…).
    // Only when there is no usable kind do we fall back to mime/extension
    // detection and render text/image ourselves.
    if (meta.kind && meta.kind.trim() && embedComponent.value) return;
    const mime = meta.mimeType ?? '';
    if (mime.startsWith('image/') || /\.(png|jpe?g|gif|webp|svg|bmp)$/i.test(path)) {
      imgSrc.value = documentContentUrl(meta.id);
    } else if (mime === 'text/markdown' || mime === 'text/x-markdown' || /\.(md|markdown)$/i.test(path)) {
      const txt = await brainFetchText('documents/' + meta.id + '/content');
      mdHtml.value = DOMPurify.sanitize(await marked.parse(txt ?? ''));
    } else if (mime.startsWith('text/') || /\.(txt|log|csv|json|ya?ml|xml|ts|js|py|java|sql|sh)$/i.test(path)) {
      const txt = await brainFetchText('documents/' + meta.id + '/content');
      mdHtml.value = DOMPurify.sanitize(await marked.parse('```\n' + (txt ?? '') + '\n```'));
    }
    // else: structured kind → rendered by the injected host renderer (below).
  } catch (e) {
    docError.value = e instanceof Error ? e.message : String(e);
  }
}

onMounted(loadDoc);
watch(() => [node.value.ref, props.data.projectId], loadDoc);

/** Jump: open the referenced document in a new Cortex tab. */
function openInCortex(): void {
  const pid = props.data.projectId;
  const id = docMeta.value?.id;
  if (!pid || !id) return;
  const url = `/cortex.html?project=${encodeURIComponent(pid)}&doc=${encodeURIComponent(id)}`;
  window.open(url, '_blank', 'noopener');
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
      kind === 'group'
        ? { background: groupBg, borderColor: groupBorder }
        : { background: bg },
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
          <span class="cv-sep"></span>
          <button
            v-for="tc in TEXT_COLORS"
            :key="tc"
            class="cv-tcolor"
            :style="{ color: tc }"
            :class="{ 'cv-active': node.textColor === tc }"
            title="Textfarbe"
            @click="patch({ textColor: tc })"
          >A</button>
        </template>

        <span class="cv-sep"></span>
        <button class="cv-btn" title="in den Vordergrund" @click="props.data.onFront?.(node.id)">⬆</button>
        <button class="cv-btn" title="in den Hintergrund" @click="props.data.onBack?.(node.id)">⬇</button>
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
      <div v-if="node.author && !editing" class="canvas-note-author">✎ {{ node.author }}</div>
    </template>

    <template v-else-if="kind === 'doc'">
      <button
        v-if="docMeta && !(embedComponent && docMeta.kind)"
        class="canvas-embed-open nodrag"
        title="Im Cortex öffnen"
        @click.stop="openInCortex"
        @pointerdown.stop
        @dblclick.stop
      >↗</button>
      <!-- Strukturierte Kinds zuerst: der injizierte Original-Renderer. -->
      <div v-if="embedComponent && docMeta && docMeta.kind" class="canvas-embed-host nowheel">
        <component :is="embedComponent" :uri="node.ref" />
      </div>
      <!-- Sonst (kein Kind): Bild/Markdown/Text selbst rendern. -->
      <img v-else-if="imgSrc" :src="imgSrc" class="canvas-embed-img" alt="" />
      <div v-else-if="mdHtml" class="canvas-embed-md" v-html="mdHtml"></div>
      <template v-else>
        <div class="canvas-card-title">📄 {{ docMeta?.title || basename(node.ref ?? '') }}</div>
        <div class="canvas-card-sub">
          {{ docError ?? ((docMeta?.kind ? docMeta.kind + ' · ' : '') + basename(node.ref ?? '')) }}
        </div>
      </template>
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
  position: relative;
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
.canvas-note-author {
  position: absolute;
  right: 4px;
  bottom: 3px;
  z-index: 5;
  padding: 0 4px;
  border-radius: 6px;
  background: rgba(255, 255, 255, 0.75);
  font-size: 9px;
  color: #475569;
  pointer-events: none;
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
.canvas-embed-open {
  position: absolute;
  top: 4px;
  right: 4px;
  z-index: 6;
  width: 20px;
  height: 20px;
  border-radius: 5px;
  background: rgba(255, 255, 255, 0.85);
  border: 1px solid #d1d5db;
  cursor: pointer;
  font-size: 12px;
  line-height: 1;
  color: #1f2937;
}
.canvas-embed-open:hover {
  background: #ffffff;
}
.canvas-embed-host {
  height: 100%;
  overflow: auto;
}
.canvas-embed-img {
  display: block;
  width: 100%;
  height: 100%;
  object-fit: contain;
}
.canvas-embed-md {
  height: 100%;
  overflow: auto;
  font-size: 12px;
  word-break: break-word;
}
.canvas-embed-md :deep(img) {
  max-width: 100%;
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
.cv-tcolor {
  min-width: 20px;
  height: 22px;
  border: 1px solid transparent;
  border-radius: 5px;
  background: transparent;
  cursor: pointer;
  font-weight: 700;
  font-size: 13px;
}
.cv-tcolor:hover {
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
