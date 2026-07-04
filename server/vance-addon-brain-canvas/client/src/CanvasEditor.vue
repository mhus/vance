<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { Panel, useVueFlow, VueFlow } from '@vue-flow/core';
import type { Connection, Edge, EdgeChange, GraphNode, Node, NodeChange } from '@vue-flow/core';
import '@vue-flow/core/dist/style.css';
import '@vue-flow/core/dist/theme-default.css';
import { getUsername } from '@vance/shared/auth';
import { usePointers } from '@vance/shared';
import { VButton } from '@vance/components';
import CanvasNodeCard from './CanvasNodeCard.vue';
import InputDialog from './InputDialog.vue';
import DocPicker from './DocPicker.vue';
import type { CanvasGraphDto } from './generated/canvas/CanvasGraphDto';
import type { CanvasNodeDto } from './generated/canvas/CanvasNodeDto';
import type { CanvasEdgeDto } from './generated/canvas/CanvasEdgeDto';

const props = withDefaults(
  defineProps<{
    graph: CanvasGraphDto;
    editable?: boolean;
    projectId?: string;
    /** Document path of this board — enables live cursors on the pointers channel. */
    path?: string | null;
  }>(),
  { editable: false, projectId: '', path: null },
);
const emit = defineEmits<{ (e: 'change', graph: CanvasGraphDto): void }>();

const nodes = ref<CanvasNodeDto[]>([]);
const edges = ref<CanvasEdgeDto[]>([]);
const selectMode = ref(false);

// ── Live cursors (pointers channel) ───────────────────────────
// A stable VueFlow instance id lets us read the same viewport the board
// renders with, so remote cursors pan/zoom together with the canvas.
const flowId = `canvas-${props.path ?? 'board'}`;
const { viewport } = useVueFlow(flowId);
const wrapper = ref<HTMLElement | null>(null);
const pointerPath = computed(() => props.path ?? null);
const { pointers, report } = usePointers({ path: pointerPath });

/** Deterministic HSL color per participant so cursors stay stable. */
function colorFor(id: string): string {
  let h = 0;
  for (let i = 0; i < id.length; i++) h = (h * 31 + id.charCodeAt(i)) % 360;
  return `hsl(${h} 70% 45%)`;
}
const myColor = computed(() => colorFor(getUsername() ?? 'me'));
const remotePointers = computed(() => Array.from(pointers.values()));

/** Screen → canvas (flow) coordinates using the current viewport. */
function reportPointer(ev: PointerEvent): void {
  if (!pointerPath.value) return;
  const el = wrapper.value;
  if (!el) return;
  const rect = el.getBoundingClientRect();
  const vp = viewport.value;
  const x = (ev.clientX - rect.left - vp.x) / vp.zoom;
  const y = (ev.clientY - rect.top - vp.y) / vp.zoom;
  report(x, y, { color: myColor.value });
}

/** Canvas (flow) → screen coordinates for positioning a remote cursor overlay. */
function overlayStyle(p: { x: number; y: number; data?: Record<string, unknown> }): Record<string, string> {
  const vp = viewport.value;
  return {
    left: `${vp.x + p.x * vp.zoom}px`,
    top: `${vp.y + p.y * vp.zoom}px`,
    '--cursor-color': typeof p.data?.color === 'string' ? p.data.color : '#3b82f6',
  };
}

type DialogApi = {
  open: (
    t: string,
    f: { key: string; label: string; placeholder?: string; value?: string }[],
  ) => Promise<Record<string, string> | null>;
};
const dialog = ref<DialogApi | null>(null);
const docPicker = ref<{ open: (pid: string) => Promise<{ path: string; kind?: string } | null> } | null>(null);

watch(
  () => props.graph,
  (g) => {
    nodes.value = (g?.nodes ?? []).map((n) => ({ ...n }));
    edges.value = (g?.edges ?? []).map((e) => ({ ...e }));
  },
  { immediate: true },
);

const isEditable = computed(() => props.editable === true);

// ── VueFlow projection ────────────────────────────────────────
const vfNodes = computed<Node[]>(() => {
  const groupIds = new Set(nodes.value.filter((n) => n.type === 'group').map((n) => n.id));
  // VueFlow requires a parent node to precede its children → groups first.
  const ordered = [...nodes.value].sort(
    (a, b) => (a.type === 'group' ? 0 : 1) - (b.type === 'group' ? 0 : 1),
  );
  return ordered.map((n) => ({
    id: n.id,
    position: { x: n.x, y: n.y },
    type: n.type,
    // Real parenting: child position is relative to this group.
    parentNode: n.parent && groupIds.has(n.parent) ? n.parent : undefined,
    data: {
      node: n,
      editable: isEditable.value,
      projectId: props.projectId,
      onText: commitText,
      onResize,
      onPatch: patchNode,
      onDelete: removeNode,
      onFront: bringToFront,
      onBack: sendToBack,
    },
    draggable: isEditable.value,
    selectable: isEditable.value,
    connectable: isEditable.value,
    // Explicit stacking order (groups default behind). No auto-elevate.
    zIndex: n.z ?? (n.type === 'group' ? 0 : 1),
    style: n.type === 'text'
      ? { width: `${n.w || 200}px` }
      : { width: `${n.w || 200}px`, height: `${n.h || 100}px` },
  }));
});

const knownIds = computed(() => new Set(nodes.value.map((n) => n.id)));

const vfEdges = computed<Edge[]>(() => {
  const out: Edge[] = [];
  for (const e of edges.value) {
    if (!knownIds.value.has(e.from) || !knownIds.value.has(e.to)) continue;
    out.push({
      id: e.id,
      source: e.from,
      target: e.to,
      sourceHandle: `s-${e.fromSide ?? 'right'}`,
      targetHandle: `t-${e.toSide ?? 'left'}`,
      label: e.label ?? undefined,
      type: 'default',
      markerEnd: e.toEnd === 'arrow' ? 'arrowclosed' : undefined,
      markerStart: e.fromEnd === 'arrow' ? 'arrowclosed' : undefined,
      style: e.color ? { stroke: e.color } : undefined,
      updatable: false,
      selectable: isEditable.value,
    });
  }
  return out;
});

// ── Mutation core ─────────────────────────────────────────────
function emitChange(): void {
  emit('change', {
    title: props.graph.title,
    description: props.graph.description,
    nodes: nodes.value.map((n) => ({ ...n })),
    edges: edges.value.map((e) => ({ ...e })),
  });
}

function patchNode(id: string, patch: Partial<CanvasNodeDto>): void {
  nodes.value = nodes.value.map((n) => (n.id === id ? { ...n, ...patch } : n));
  emitChange();
}

function commitText(id: string, text: string): void {
  patchNode(id, { text });
}

function onResize(id: string, w: number, h: number, x: number, y: number): void {
  patchNode(id, { w, h, x, y });
}

/** When a group is removed, its children return to the canvas root with
 *  their coordinates converted back to absolute (relative + group origin). */
function detachChildren(list: CanvasNodeDto[], removed: CanvasNodeDto): CanvasNodeDto[] {
  if (removed.type !== 'group') return list;
  return list.map((n) =>
    n.parent === removed.id
      ? { ...n, x: n.x + removed.x, y: n.y + removed.y, parent: undefined }
      : n,
  );
}

function removeNode(id: string): void {
  const removed = nodes.value.find((n) => n.id === id);
  let next = nodes.value.filter((n) => n.id !== id);
  if (removed) next = detachChildren(next, removed);
  nodes.value = next;
  edges.value = edges.value.filter((e) => e.from !== id && e.to !== id);
  emitChange();
}

function bringToFront(id: string): void {
  const zs = nodes.value.map((n) => n.z ?? 1);
  patchNode(id, { z: (zs.length ? Math.max(...zs) : 1) + 1 });
}

function sendToBack(id: string): void {
  const zs = nodes.value.map((n) => n.z ?? 1);
  patchNode(id, { z: (zs.length ? Math.min(...zs) : 1) - 1 });
}

function nextId(prefix: string, existing: string[]): string {
  let max = 0;
  for (const id of existing) {
    if (id.startsWith(prefix)) {
      const n = Number.parseInt(id.slice(prefix.length), 10);
      if (Number.isFinite(n) && n > max) max = n;
    }
  }
  return prefix + (max + 1);
}

// ── VueFlow events ────────────────────────────────────────────
function onNodesChange(changes: NodeChange[]): void {
  if (!isEditable.value) return;
  let next = nodes.value;
  let dirty = false;
  for (const c of changes) {
    if (c.type === 'position' && c.position && c.dragging === false) {
      next = next.map((n) =>
        n.id === c.id ? { ...n, x: Math.round(c.position!.x), y: Math.round(c.position!.y) } : n,
      );
      dirty = true;
    } else if (c.type === 'remove') {
      const removed = next.find((n) => n.id === c.id);
      next = next.filter((n) => n.id !== c.id);
      if (removed) next = detachChildren(next, removed);
      edges.value = edges.value.filter((e) => e.from !== c.id && e.to !== c.id);
      dirty = true;
    }
  }
  if (dirty) {
    nodes.value = next;
    emitChange();
  }
}

function onNodeDragStop(e: { node?: GraphNode; nodes?: GraphNode[] }): void {
  if (!isEditable.value) return;
  const moved = e.nodes && e.nodes.length ? e.nodes : e.node ? [e.node] : [];
  if (!moved.length) return;

  const model = new Map(nodes.value.map((n) => [n.id, n]));
  const groups = nodes.value.filter((n) => n.type === 'group');
  const updates = new Map<string, { x: number; y: number; parent?: string }>();

  for (const m of moved) {
    const cur = model.get(m.id);
    if (!cur) continue;
    // computedPosition is absolute (canvas coords); position is parent-relative.
    const abs = m.computedPosition ?? m.position;
    if (cur.type === 'group') {
      updates.set(m.id, { x: Math.round(abs.x), y: Math.round(abs.y), parent: undefined });
      continue;
    }
    const cx = abs.x + (cur.w || 120) / 2;
    const cy = abs.y + (cur.h || 60) / 2;
    const g = groups.find(
      (gr) => gr.id !== m.id && cx >= gr.x && cx <= gr.x + gr.w && cy >= gr.y && cy <= gr.y + gr.h,
    );
    if (g) {
      updates.set(m.id, { x: Math.round(abs.x - g.x), y: Math.round(abs.y - g.y), parent: g.id });
    } else {
      updates.set(m.id, { x: Math.round(abs.x), y: Math.round(abs.y), parent: undefined });
    }
  }

  nodes.value = nodes.value.map((n) => {
    const u = updates.get(n.id);
    return u ? { ...n, x: u.x, y: u.y, parent: u.parent } : n;
  });
  emitChange();
}

function onEdgesChange(changes: EdgeChange[]): void {
  if (!isEditable.value) return;
  let dirty = false;
  for (const c of changes) {
    if (c.type === 'remove') {
      edges.value = edges.value.filter((e) => e.id !== c.id);
      dirty = true;
    }
  }
  if (dirty) emitChange();
}

function onConnect(conn: Connection): void {
  if (!isEditable.value || !conn.source || !conn.target) return;
  edges.value = [
    ...edges.value,
    {
      id: nextId('e', edges.value.map((e) => e.id)),
      from: conn.source,
      to: conn.target,
      fromSide: handleSide(conn.sourceHandle) ?? 'right',
      toSide: handleSide(conn.targetHandle) ?? 'left',
      fromEnd: 'none',
      toEnd: 'arrow',
    },
  ];
  emitChange();
}

function handleSide(h: string | null | undefined): string | undefined {
  return h ? h.split('-')[1] : undefined;
}

async function onEdgeDoubleClick(e: { edge?: Edge }): Promise<void> {
  if (!isEditable.value || !e.edge) return;
  const id = e.edge.id;
  const cur = edges.value.find((x) => x.id === id);
  if (!cur) return;
  const v = await dialog.value?.open('Kante beschriften', [
    { key: 'label', label: 'Label', value: cur.label ?? '' },
  ]);
  if (!v) return;
  const label = v.label.trim();
  edges.value = edges.value.map((x) => (x.id === id ? { ...x, label: label || undefined } : x));
  emitChange();
}

// ── Toolbox: add nodes ────────────────────────────────────────
function placement(): { x: number; y: number } {
  const c = nodes.value.length;
  return { x: 80 + (c % 4) * 240, y: 80 + Math.floor(c / 4) * 150 };
}

async function addNode(type: 'text' | 'doc' | 'link' | 'group'): Promise<void> {
  if (!isEditable.value) return;
  const id = nextId('n', nodes.value.map((n) => n.id));
  const p = placement();
  const me = getUsername() ?? undefined;
  let node: CanvasNodeDto;
  if (type === 'text') {
    node = { id, type, x: p.x, y: p.y, w: 200, h: 120, text: 'Neue Notiz', author: me };
  } else if (type === 'doc') {
    const picked = await docPicker.value?.open(props.projectId);
    if (!picked || !picked.path) return;
    const ref = `vance:/${picked.path}${picked.kind ? `?kind=${picked.kind}` : ''}`;
    node = { id, type, x: p.x, y: p.y, w: 280, h: 200, ref };
  } else if (type === 'link') {
    const v = await dialog.value?.open('Link-Node', [
      { key: 'href', label: 'URL', placeholder: 'https://', value: 'https://' },
      { key: 'title', label: 'Titel (optional)' },
    ]);
    if (!v || !v.href) return;
    node = { id, type, x: p.x, y: p.y, w: 240, h: 72, href: v.href, title: v.title || undefined };
  } else {
    const v = await dialog.value?.open('Gruppe', [
      { key: 'label', label: 'Titel', value: 'Gruppe' },
    ]);
    if (!v) return;
    node = { id, type, x: p.x, y: p.y, w: 420, h: 300, label: v.label || 'Gruppe' };
  }
  nodes.value = [...nodes.value, node];
  emitChange();
}
</script>

<template>
  <div ref="wrapper" class="relative h-full w-full" @pointermove="reportPointer">
    <VueFlow
      :id="flowId"
      :nodes="vfNodes"
      :edges="vfEdges"
      :fit-view-on-init="true"
      :nodes-draggable="isEditable"
      :nodes-connectable="isEditable"
      :elements-selectable="isEditable"
      :edges-updatable="false"
      :elevate-nodes-on-select="false"
      :delete-key-code="['Delete', 'Backspace']"
      :pan-on-drag="!selectMode"
      :selection-key-code="selectMode ? true : 'Shift'"
      @nodes-change="onNodesChange"
      @node-drag-stop="onNodeDragStop"
      @edges-change="onEdgesChange"
      @edge-double-click="onEdgeDoubleClick"
      @connect="onConnect"
    >
      <Panel v-if="isEditable" position="top-left">
        <div class="cv-panel">
          <VButton size="sm" @click="addNode('text')">📝 Notiz</VButton>
          <VButton size="sm" @click="addNode('doc')">📄 Dok</VButton>
          <VButton size="sm" @click="addNode('link')">🔗 Link</VButton>
          <VButton size="sm" @click="addNode('group')">▢ Gruppe</VButton>
          <span class="cv-panel-sep"></span>
          <VButton
            size="sm"
            :variant="selectMode ? 'primary' : 'ghost'"
            title="Mehrfachauswahl per Aufziehen (sonst Shift+Ziehen)"
            @click="selectMode = !selectMode"
          >⬚ Auswahl</VButton>
        </div>
      </Panel>

      <template #node-text="p"><CanvasNodeCard v-bind="p" /></template>
      <template #node-doc="p"><CanvasNodeCard v-bind="p" /></template>
      <template #node-link="p"><CanvasNodeCard v-bind="p" /></template>
      <template #node-group="p"><CanvasNodeCard v-bind="p" /></template>
    </VueFlow>

    <!-- Remote live cursors — positioned in screen space from the flow
         viewport, so they track pan/zoom. Non-interactive overlay. -->
    <div class="cv-cursors">
      <div
        v-for="p in remotePointers"
        :key="p.editorId"
        class="cv-cursor"
        :style="overlayStyle(p)"
      >
        <svg width="18" height="18" viewBox="0 0 18 18" class="cv-cursor-icon">
          <path d="M2 2 L2 14 L6 10 L9 16 L11 15 L8 9 L14 9 Z" />
        </svg>
        <span class="cv-cursor-label">{{ p.displayName }}</span>
      </div>
    </div>

    <InputDialog ref="dialog" />
    <DocPicker ref="docPicker" />
  </div>
</template>

<style scoped>
.cv-panel {
  display: flex;
  align-items: center;
  gap: 4px;
  padding: 5px 7px;
  background: #ffffff;
  border: 1px solid #d1d5db;
  border-radius: 8px;
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.15);
}
.cv-panel-sep {
  width: 1px;
  height: 20px;
  background: #e2e8f0;
  margin: 0 2px;
}

/* Live-cursor overlay — sits above the flow pane, never eats input. */
.cv-cursors {
  position: absolute;
  inset: 0;
  overflow: hidden;
  pointer-events: none;
  z-index: 5;
}
.cv-cursor {
  position: absolute;
  transform: translate(-2px, -2px);
  will-change: left, top;
  transition: left 0.08s linear, top 0.08s linear;
}
.cv-cursor-icon {
  fill: var(--cursor-color, #3b82f6);
  stroke: #ffffff;
  stroke-width: 1;
  filter: drop-shadow(0 1px 1px rgba(0, 0, 0, 0.3));
}
.cv-cursor-label {
  position: absolute;
  left: 16px;
  top: 12px;
  padding: 1px 6px;
  font-size: 11px;
  line-height: 1.4;
  white-space: nowrap;
  color: #ffffff;
  background: var(--cursor-color, #3b82f6);
  border-radius: 6px;
  box-shadow: 0 1px 2px rgba(0, 0, 0, 0.25);
}
</style>
