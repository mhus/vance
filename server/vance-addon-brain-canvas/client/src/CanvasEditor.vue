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

const nodes = ref<CanvasNodeDto[]>([]);
const edges = ref<CanvasEdgeDto[]>([]);

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
const vfNodes = computed<Node[]>(() =>
  nodes.value.map((n) => ({
    id: n.id,
    position: { x: n.x, y: n.y },
    type: n.type,
    data: { node: n, editable: isEditable.value, onText: commitText, onResize, onPatch: patchNode, onDelete: removeNode },
    draggable: isEditable.value,
    selectable: isEditable.value,
    connectable: isEditable.value,
    zIndex: n.type === 'group' ? 0 : 1,
    // Text nodes auto-grow their height to fit the text (never scroll);
    // other kinds keep the resized box.
    style: n.type === 'text'
      ? { width: `${n.w || 200}px` }
      : { width: `${n.w || 200}px`, height: `${n.h || 100}px` },
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

function removeNode(id: string): void {
  nodes.value = nodes.value.filter((n) => n.id !== id);
  edges.value = edges.value.filter((e) => e.from !== id && e.to !== id);
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

function handleSide(h: string | null | undefined): string | undefined {
  return h ? h.split('-')[1] : undefined;
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
  emitChange();
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
        Node anklicken → Toolbar für Farbe/Stil · Doppelklick editiert Notiz ·
        Ecken ziehen = Größe · Griff rechts→links = Kante · Entf löscht
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
        :edges-updatable="false"
        @nodes-change="onNodesChange"
        @node-drag-stop="onNodeDragStop"
        @edges-change="onEdgesChange"
        @connect="onConnect"
      >
        <template #node-text="p"><CanvasNodeCard v-bind="p" /></template>
        <template #node-doc="p"><CanvasNodeCard v-bind="p" /></template>
        <template #node-link="p"><CanvasNodeCard v-bind="p" /></template>
        <template #node-group="p"><CanvasNodeCard v-bind="p" /></template>
      </VueFlow>
    </div>
  </div>
</template>
