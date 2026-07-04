<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { VueFlow } from '@vue-flow/core';
import type { Connection, Edge, EdgeChange, Node, NodeChange } from '@vue-flow/core';
import '@vue-flow/core/dist/style.css';
import '@vue-flow/core/dist/theme-default.css';
import { VButton } from '@vance/components';
import CanvasNodeCard from './CanvasNodeCard.vue';
import type { CanvasGraphDto } from './generated/canvas/CanvasGraphDto';
import type { CanvasNodeDto } from './generated/canvas/CanvasNodeDto';
import type { CanvasEdgeDto } from './generated/canvas/CanvasEdgeDto';

const props = withDefaults(
  defineProps<{ graph: CanvasGraphDto; editable?: boolean }>(),
  { editable: false },
);
const emit = defineEmits<{ (e: 'change', graph: CanvasGraphDto): void }>();

const COLORS = ['1', '2', '3', '4', '5', '6'];
const SWATCH: Record<string, string> = {
  '1': '#fecaca', '2': '#fed7aa', '3': '#fef08a',
  '4': '#bbf7d0', '5': '#bfdbfe', '6': '#ddd6fe',
};

const nodes = ref<CanvasNodeDto[]>([]);
const edges = ref<CanvasEdgeDto[]>([]);
const selectedId = ref<string | null>(null);

watch(
  () => props.graph,
  (g) => {
    nodes.value = (g?.nodes ?? []).map((n) => ({ ...n }));
    edges.value = (g?.edges ?? []).map((e) => ({ ...e }));
    selectedId.value = null;
  },
  { immediate: true },
);

const isEditable = computed(() => props.editable === true);
const selected = computed(() => nodes.value.find((n) => n.id === selectedId.value) ?? null);

// ── VueFlow projection ────────────────────────────────────────
const vfNodes = computed<Node[]>(() =>
  nodes.value.map((n) => ({
    id: n.id,
    position: { x: n.x, y: n.y },
    type: n.type,
    data: { node: n, editable: isEditable.value, onText: commitText },
    draggable: isEditable.value,
    selectable: isEditable.value,
    connectable: isEditable.value,
    zIndex: n.type === 'group' ? 0 : 1,
    style: { width: `${n.w || 200}px`, height: `${n.h || 100}px` },
  })),
);

const knownIds = computed(() => new Set(nodes.value.map((n) => n.id)));

const vfEdges = computed<Edge[]>(() => {
  const out: Edge[] = [];
  for (const e of edges.value) {
    if (!knownIds.value.has(e.from) || !knownIds.value.has(e.to)) continue;
    out.push({
      id: e.id,
      source: e.from,
      target: e.to,
      label: e.label ?? undefined,
      type: 'default',
      markerEnd: e.toEnd === 'arrow' ? 'arrowclosed' : undefined,
      markerStart: e.fromEnd === 'arrow' ? 'arrowclosed' : undefined,
      style: e.color ? { stroke: e.color } : undefined,
      updatable: isEditable.value,
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
      next = next.filter((n) => n.id !== c.id);
      edges.value = edges.value.filter((e) => e.from !== c.id && e.to !== c.id);
      if (selectedId.value === c.id) selectedId.value = null;
      dirty = true;
    }
  }
  if (dirty) {
    nodes.value = next;
    emitChange();
  }
}

function onNodeDragStop(e: { node?: Node; nodes?: Node[] }): void {
  if (!isEditable.value) return;
  const moved = e.nodes && e.nodes.length ? e.nodes : e.node ? [e.node] : [];
  if (!moved.length) return;
  const byId = new Map(moved.map((n) => [n.id, n.position]));
  nodes.value = nodes.value.map((n) => {
    const p = byId.get(n.id);
    return p ? { ...n, x: Math.round(p.x), y: Math.round(p.y) } : n;
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
      fromEnd: 'none',
      toEnd: 'arrow',
    },
  ];
  emitChange();
}

// ── Toolbox: add nodes ────────────────────────────────────────
function placement(): { x: number; y: number } {
  const c = nodes.value.length;
  return { x: 80 + (c % 4) * 240, y: 80 + Math.floor(c / 4) * 150 };
}

function addNode(type: 'text' | 'doc' | 'link' | 'group'): void {
  if (!isEditable.value) return;
  const id = nextId('n', nodes.value.map((n) => n.id));
  const p = placement();
  let node: CanvasNodeDto;
  if (type === 'text') {
    node = { id, type, x: p.x, y: p.y, w: 200, h: 120, text: 'Neue Notiz' };
  } else if (type === 'doc') {
    const ref = window.prompt('vance:-URI oder Dokumentpfad:', 'vance:/');
    if (!ref) return;
    node = { id, type, x: p.x, y: p.y, w: 260, h: 80, ref };
  } else if (type === 'link') {
    const href = window.prompt('Link-URL:', 'https://');
    if (!href) return;
    const title = window.prompt('Titel (optional):', '') ?? undefined;
    node = { id, type, x: p.x, y: p.y, w: 240, h: 72, href, title: title || undefined };
  } else {
    const label = window.prompt('Gruppen-Titel:', 'Gruppe') ?? 'Gruppe';
    node = { id, type, x: p.x, y: p.y, w: 420, h: 300, label };
  }
  nodes.value = [...nodes.value, node];
  selectedId.value = id;
  emitChange();
}

// ── Properties panel actions ──────────────────────────────────
function commitText(id: string, text: string): void {
  patchNode(id, { text });
}
function setColor(color: string | undefined): void {
  if (selected.value) patchNode(selected.value.id, { color });
}
function setFont(fontSize: string): void {
  if (selected.value) patchNode(selected.value.id, { fontSize });
}
function toggleBold(): void {
  if (selected.value) patchNode(selected.value.id, { bold: !selected.value.bold });
}
function toggleItalic(): void {
  if (selected.value) patchNode(selected.value.id, { italic: !selected.value.italic });
}
function removeSelected(): void {
  if (!selected.value) return;
  const id = selected.value.id;
  nodes.value = nodes.value.filter((n) => n.id !== id);
  edges.value = edges.value.filter((e) => e.from !== id && e.to !== id);
  selectedId.value = null;
  emitChange();
}

function onNodeClick(e: { node: Node }): void {
  selectedId.value = e.node.id;
}
</script>

<template>
  <div class="flex h-full w-full">
    <!-- Toolbox -->
    <div v-if="isEditable" class="flex w-44 flex-col gap-1 border-r border-base-300 p-2">
      <div class="mb-1 text-xs font-semibold uppercase opacity-50">Hinzufügen</div>
      <VButton size="sm" @click="addNode('text')">📝 Notiz</VButton>
      <VButton size="sm" @click="addNode('doc')">📄 Dokument</VButton>
      <VButton size="sm" @click="addNode('link')">🔗 Link</VButton>
      <VButton size="sm" @click="addNode('group')">▢ Gruppe</VButton>
      <div class="mt-2 text-[11px] leading-snug opacity-50">
        Doppelklick editiert eine Notiz · Griff ziehen für Kante · Entf löscht
      </div>
    </div>

    <!-- Canvas -->
    <div class="min-h-0 min-w-0 flex-1">
      <VueFlow
        :nodes="vfNodes"
        :edges="vfEdges"
        :fit-view-on-init="true"
        :nodes-draggable="isEditable"
        :nodes-connectable="isEditable"
        :elements-selectable="isEditable"
        :edges-updatable="isEditable"
        @nodes-change="onNodesChange"
        @node-drag-stop="onNodeDragStop"
        @edges-change="onEdgesChange"
        @connect="onConnect"
        @node-click="onNodeClick"
        @pane-click="selectedId = null"
      >
        <template #node-text="p"><CanvasNodeCard v-bind="p" /></template>
        <template #node-doc="p"><CanvasNodeCard v-bind="p" /></template>
        <template #node-link="p"><CanvasNodeCard v-bind="p" /></template>
        <template #node-group="p"><CanvasNodeCard v-bind="p" /></template>
      </VueFlow>
    </div>

    <!-- Properties -->
    <div v-if="isEditable && selected" class="flex w-56 flex-col gap-3 border-l border-base-300 p-3">
      <div class="text-xs font-semibold uppercase opacity-50">
        {{ selected.type }} · {{ selected.id }}
      </div>

      <div>
        <div class="mb-1 text-xs opacity-60">Farbe</div>
        <div class="flex flex-wrap gap-1">
          <button
            class="h-6 w-6 rounded border border-base-300"
            title="keine"
            style="background: #ffffff"
            @click="setColor(undefined)"
          >×</button>
          <button
            v-for="c in COLORS"
            :key="c"
            class="h-6 w-6 rounded border border-base-300"
            :style="{ background: SWATCH[c], outline: selected.color === c ? '2px solid #2563eb' : 'none' }"
            @click="setColor(c)"
          ></button>
        </div>
      </div>

      <template v-if="selected.type === 'text'">
        <div>
          <div class="mb-1 text-xs opacity-60">Schriftgröße</div>
          <div class="flex gap-1">
            <VButton size="sm" :variant="(selected.fontSize ?? 'm') === 's' ? 'primary' : 'ghost'" @click="setFont('s')">S</VButton>
            <VButton size="sm" :variant="(selected.fontSize ?? 'm') === 'm' ? 'primary' : 'ghost'" @click="setFont('m')">M</VButton>
            <VButton size="sm" :variant="(selected.fontSize ?? 'm') === 'l' ? 'primary' : 'ghost'" @click="setFont('l')">L</VButton>
          </div>
        </div>
        <div>
          <div class="mb-1 text-xs opacity-60">Stil</div>
          <div class="flex gap-1">
            <VButton size="sm" :variant="selected.bold ? 'primary' : 'ghost'" @click="toggleBold"><b>B</b></VButton>
            <VButton size="sm" :variant="selected.italic ? 'primary' : 'ghost'" @click="toggleItalic"><i>K</i></VButton>
          </div>
        </div>
      </template>

      <VButton size="sm" variant="ghost" class="mt-auto" @click="removeSelected">🗑 Löschen</VButton>
    </div>
  </div>
</template>
