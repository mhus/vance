<script setup lang="ts">
import { computed, ref } from 'vue';
// `ref` still used by the dragging state below — keep the import.
import { NodeViewContent, NodeViewWrapper } from '@tiptap/vue-3';
import type { Editor } from '@tiptap/core';
import type { Node as ProseMirrorNode } from '@tiptap/pm/model';

/**
 * NodeView for the columns container. Renders the inner `vanceColumn`
 * children via NodeViewContent as direct grid items; alongside that,
 * N-1 absolutely-positioned resize handles sit at each column boundary.
 * Dragging a handle adjusts the two flanking columns' `width` attrs.
 *
 * Width semantics: each column has a fractional `width` (e.g. 0.4). The
 * grid uses `fr` units so the proportions render correctly regardless
 * of absolute values. {@code null} means "equal share of remaining
 * space" — we normalise to a 1.0 default during resize so once you
 * touch the handle the entire row commits to explicit fractions.
 */
const props = defineProps<{
  node: ProseMirrorNode;
  editor: Editor;
  getPos: () => number | undefined;
}>();

interface ColumnInfo {
  width: number | null;
}

const columns = computed<ColumnInfo[]>(() => {
  const out: ColumnInfo[] = [];
  props.node.content.forEach((child) => {
    const w = child.attrs?.width;
    out.push({ width: typeof w === 'number' ? w : null });
  });
  return out;
});

const effectiveWidths = computed<number[]>(() => {
  // For grid-template-columns: null entries fall back to 1.0 so they
  // share equally when mixed with explicit widths.
  return columns.value.map((c) => (c.width != null ? c.width : 1));
});

const gridTemplate = computed(
  () => effectiveWidths.value.map((w) => `${w}fr`).join(' '),
);

const cumulativeFractions = computed<number[]>(() => {
  const total = effectiveWidths.value.reduce((a, b) => a + b, 0) || 1;
  let acc = 0;
  const out: number[] = [];
  for (const w of effectiveWidths.value) {
    acc += w;
    out.push(acc / total);
  }
  // Drop the final 1.0 — that's the right edge, no handle there.
  return out.slice(0, -1);
});

// Active drag state. While dragging we suspend reactivity on the
// `columns` computed by mutating attrs through Tiptap commands; the
// updateAttributes call triggers a doc transaction, the node prop
// re-renders, and the next computed cycle picks up the new widths.
const dragging = ref<{
  handleIndex: number;
  startX: number;
  containerLeft: number;
  containerWidth: number;
  startLeftWidth: number;
  startRightWidth: number;
} | null>(null);

function onHandleDown(handleIndex: number, ev: PointerEvent) {
  ev.preventDefault();
  ev.stopPropagation();
  const handle = ev.currentTarget as HTMLElement;
  // Handles are direct children of the NodeViewWrapper-rendered
  // `.vance-columns` div — climb one step to get the positioning
  // context for percentage maths.
  const container = handle.parentElement;
  if (!container) return;
  const rect = container.getBoundingClientRect();
  // Snapshot the absolute fractions; ensure both flanking columns
  // become explicit (commit on first drag).
  const left = effectiveWidths.value[handleIndex];
  const right = effectiveWidths.value[handleIndex + 1];
  dragging.value = {
    handleIndex,
    startX: ev.clientX,
    containerLeft: rect.left,
    containerWidth: rect.width,
    startLeftWidth: left,
    startRightWidth: right,
  };
  document.addEventListener('pointermove', onHandleMove);
  document.addEventListener('pointerup', onHandleUp);
  // Visually highlight the dragging handle.
  (ev.currentTarget as HTMLElement).classList.add('vance-column-resize-handle--active');
}

function onHandleMove(ev: PointerEvent) {
  const d = dragging.value;
  if (!d) return;
  ev.preventDefault();
  const totalWidths = effectiveWidths.value.reduce((a, b) => a + b, 0) || 1;
  const dxPx = ev.clientX - d.startX;
  const dxFr = (dxPx / d.containerWidth) * totalWidths;
  // Clamp so neither column shrinks below a small minimum.
  const minFr = totalWidths * 0.05;
  let newLeft = Math.max(minFr, d.startLeftWidth + dxFr);
  let newRight = Math.max(minFr, d.startRightWidth - dxFr);
  // If a clamp kicked in on one side, preserve the pair's combined
  // width by pinning the other side accordingly.
  const sum = d.startLeftWidth + d.startRightWidth;
  if (newLeft + newRight !== sum) {
    if (newLeft === minFr) newRight = sum - minFr;
    else newLeft = sum - minFr;
  }
  writeWidths([
    { idx: d.handleIndex, width: newLeft },
    { idx: d.handleIndex + 1, width: newRight },
  ]);
}

function onHandleUp() {
  document.removeEventListener('pointermove', onHandleMove);
  document.removeEventListener('pointerup', onHandleUp);
  document
    .querySelectorAll('.vance-column-resize-handle--active')
    .forEach((el) => el.classList.remove('vance-column-resize-handle--active'));
  dragging.value = null;
}

/**
 * Patch the `width` attribute of one or more columns inside this
 * container in a SINGLE ProseMirror transaction. Bundling both
 * width-updates (left + right column after a drag) prevents stale-
 * position reads when the second `dispatch` runs against a fresh
 * state from the first.
 */
function writeWidths(updates: Array<{ idx: number; width: number }>) {
  const basePos = props.getPos();
  if (typeof basePos !== 'number') return;
  const updateMap = new Map<number, number>();
  for (const u of updates) {
    updateMap.set(u.idx, Math.round(u.width * 10000) / 10000);
  }
  const tr = props.editor.state.tr;
  let offset = basePos + 1;
  let i = 0;
  props.node.content.forEach((child) => {
    if (updateMap.has(i)) {
      tr.setNodeMarkup(offset, undefined, {
        ...child.attrs,
        width: updateMap.get(i)!,
      });
    }
    offset += child.nodeSize;
    i++;
  });
  if (tr.docChanged) {
    props.editor.view.dispatch(tr);
  }
}
</script>

<template>
  <NodeViewWrapper
    as="div"
    class="vance-columns"
    :style="`grid-template-columns: ${gridTemplate};`"
  >
    <NodeViewContent as="div" class="vance-columns__content" />
    <div
      v-for="(frac, k) in cumulativeFractions"
      :key="k"
      class="vance-column-resize-handle"
      :style="{ left: `calc(${frac * 100}% - 4px)` }"
      contenteditable="false"
      @pointerdown="onHandleDown(k, $event)"
    />
  </NodeViewWrapper>
</template>

<style>
.vance-columns {
  position: relative;
  margin: 0.75em 0;
  /* Explicit width so the absolute-positioned resize handles align
     with the actual content. NodeViewWrapper sometimes renders as a
     span-ish element if `as` is ignored — guard against that. */
  display: block;
  width: 100%;
}
.vance-columns__content {
  display: grid;
  grid-template-columns: inherit;
  width: 100%;
  /* No gap so the resize-handle's 50% line equals the visual column
     boundary. The padding on .vance-column below provides the spacing
     instead. */
}
.vance-columns > .vance-columns__content > .vance-column {
  min-width: 0;
  border: 1px dashed transparent;
  border-radius: 0.25rem;
  padding: 0.25rem 0.75rem;
  transition: border-color 0.15s ease;
}
.vance-columns > .vance-columns__content > .vance-column:hover,
.vance-columns > .vance-columns__content > .vance-column:focus-within {
  border-color: var(--color-border, #e5e7eb);
}
.vance-columns > .vance-columns__content > .vance-column > :first-child {
  margin-top: 0;
}
.vance-column-resize-handle {
  position: absolute;
  top: 0;
  bottom: 0;
  width: 8px;
  cursor: col-resize;
  z-index: 10;
  background: transparent;
  transition: background 0.15s ease;
}
.vance-column-resize-handle::before {
  content: '';
  position: absolute;
  left: 3px;
  top: 0;
  bottom: 0;
  width: 2px;
  background: transparent;
  transition: background 0.15s ease;
}
.vance-columns:hover .vance-column-resize-handle::before {
  background: var(--color-border, #d1d5db);
}
.vance-column-resize-handle:hover::before,
.vance-column-resize-handle--active::before {
  background: var(--color-link, #3b82f6);
}
</style>
