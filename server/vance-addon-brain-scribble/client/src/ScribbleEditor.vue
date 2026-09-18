<script setup lang="ts">
/**
 * The handwriting surface: a fixed-size sheet rendered from vector strokes.
 *
 * <p>Rendering architecture (planning/scribble.md §8.1): one base canvas
 * holds the committed ink and is vector-re-rendered only on commit, resize
 * and gesture end; one transparent live canvas above it carries only the
 * stroke in motion. During pan/pinch gestures the base is moved as a
 * bitmap (cheap {@code drawImage} per frame) and re-rendered crisp when
 * the gesture ends — zoom is a view, never stored.
 *
 * <p>Input: pointer events with coalesced samples (the full stylus rate,
 * not the display rate). Pen and mouse draw; a finger pans/zooms unless
 * the "draw with finger" toggle is on — which doubles as palm rejection
 * and as the fallback for an empty pencil battery.
 */
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue';
import { useT } from './i18n';
import {
  commitPoints,
  paletteColor,
  strokeHit,
  strokePath,
  type InkPoint,
  type PenWidth,
} from './ink';
import { StrokeHistory, applyOp, revertOp } from './history';
import type { ScribbleSheetDto } from './generated/scribble/ScribbleSheetDto';
import type { ScribbleStrokeDto } from './generated/scribble/ScribbleStrokeDto';

const props = withDefaults(
  defineProps<{
    sheet: ScribbleSheetDto;
    editable?: boolean;
  }>(),
  { editable: false },
);
const emit = defineEmits<{ (e: 'change', sheet: ScribbleSheetDto): void }>();

const t = useT();

// ── Model ──────────────────────────────────────────────────────

const strokes = ref<ScribbleStrokeDto[]>([]);
const history = new StrokeHistory();
const canUndo = ref(false);
const canRedo = ref(false);

function syncHistoryState(): void {
  canUndo.value = history.canUndo;
  canRedo.value = history.canRedo;
}


const sheetW = computed(() => props.sheet.sizeW || 1240);
const sheetH = computed(() => props.sheet.sizeH || 1754);

// ── Tool state ─────────────────────────────────────────────────

const mode = ref<'pen' | 'eraser'>('pen');
const width = ref<PenWidth>('m');
const color = ref<string>('1');
const fingerDraw = ref(false);

const swatches: { index: string; labelKey: string; hex: string }[] = [
  { index: '1', labelKey: 'scribble.editor.colorBlack', hex: '#111827' },
  { index: '2', labelKey: 'scribble.editor.colorRed', hex: '#dc2626' },
  { index: '3', labelKey: 'scribble.editor.colorBlue', hex: '#2563eb' },
  { index: '4', labelKey: 'scribble.editor.colorGreen', hex: '#16a34a' },
];

const sizeOptions: { key: PenWidth; labelKey: string; dot: string }[] = [
  { key: 's', labelKey: 'scribble.editor.sizeSmall', dot: 'h-1.5 w-1.5' },
  { key: 'm', labelKey: 'scribble.editor.sizeMedium', dot: 'h-2.5 w-2.5' },
  { key: 'l', labelKey: 'scribble.editor.sizeLarge', dot: 'h-3.5 w-3.5' },
];

function emitChange(): void {
  emit('change', {
    title: props.sheet.title,
    sizeW: sheetW.value,
    sizeH: sheetH.value,
    strokes: strokes.value,
  });
}

// ── Viewport ───────────────────────────────────────────────────
// screen = sheetPoint * scale + offset — pure view state, never stored.

const viewport = reactive({ scale: 1, x: 0, y: 0 });
const SCALE_MIN = 0.25;
const SCALE_MAX = 8;
const zoomPct = computed(() => Math.round(viewport.scale * 100));

// ── Canvases ────────────────────────────────────────────────────

const container = ref<HTMLElement | null>(null);
const baseCanvas = ref<HTMLCanvasElement | null>(null);
const liveCanvas = ref<HTMLCanvasElement | null>(null);
const printCanvas = ref<HTMLCanvasElement | null>(null);
let dpr = 1;
let resizeObserver: ResizeObserver | null = null;
let didFit = false;

// Sheet (re)load. Declared AFTER the canvas refs on purpose: the immediate
// run happens during setup() and reaches renderBase() — reading baseCanvas
// before its const declaration is a temporal-dead-zone ReferenceError, the
// exact crash this watch produced when it lived above the refs. Here the
// refs exist (still null pre-mount), so the first render no-ops and the
// mounted resize() does the real first paint.
watch(
  () => props.sheet,
  (s) => {
    strokes.value = [...(s?.strokes ?? [])];
    history.reset();
    syncHistoryState();
    renderBase();
  },
  { immediate: true },
);

function ctxOf(cv: HTMLCanvasElement): CanvasRenderingContext2D {
  const ctx = cv.getContext('2d');
  if (!ctx) throw new Error('2D canvas context unavailable');
  ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
  return ctx;
}

function cssSize(cv: HTMLCanvasElement): { w: number; h: number } {
  return { w: cv.width / dpr, h: cv.height / dpr };
}

function resize(): void {
  const host = container.value;
  const base = baseCanvas.value;
  const live = liveCanvas.value;
  if (!host || !base || !live) return;
  dpr = window.devicePixelRatio || 1;
  const w = host.clientWidth;
  const h = host.clientHeight;
  if (w === 0 || h === 0) return;
  base.width = live.width = Math.round(w * dpr);
  base.height = live.height = Math.round(h * dpr);
  base.style.width = live.style.width = `${w}px`;
  base.style.height = live.style.height = `${h}px`;
  renderBase();
  if (!didFit) {
    fitToView();
    didFit = true;
  }
}

/** Crisp vector re-render of all committed ink (gesture end, commit, resize). */
function renderBase(): void {
  const cv = baseCanvas.value;
  if (!cv) return;
  const ctx = ctxOf(cv);
  const { w, h } = cssSize(cv);
  ctx.clearRect(0, 0, w, h);
  ctx.save();
  ctx.translate(viewport.x, viewport.y);
  ctx.scale(viewport.scale, viewport.scale);
  // The page: white raster with a hairline border, in sheet units.
  ctx.fillStyle = '#ffffff';
  ctx.fillRect(0, 0, sheetW.value, sheetH.value);
  ctx.strokeStyle = '#e2e8f0';
  ctx.lineWidth = 1 / viewport.scale;
  ctx.strokeRect(0, 0, sheetW.value, sheetH.value);
  for (const s of strokes.value) {
    ctx.fillStyle = paletteColor(s.color);
    ctx.fill(strokePath(s));
  }
  ctx.restore();
}

function clearLive(): void {
  const cv = liveCanvas.value;
  if (!cv) return;
  const { w, h } = cssSize(cv);
  ctxOf(cv).clearRect(0, 0, w, h);
}

/** beforeprint: the screen canvases hold the zoomed, clipped viewport —
 *  worthless on paper. Re-render the full sheet 1:1 into the print-only
 *  canvas; CSS scales it to page width, the intrinsic buffer ratio keeps
 *  the A4 shape, so one sheet lands on one page. */
function renderPrintSheet(): void {
  const cv = printCanvas.value;
  if (!cv) return;
  cv.width = sheetW.value;
  cv.height = sheetH.value;
  const ctx = cv.getContext('2d');
  if (!ctx) return;
  ctx.setTransform(1, 0, 0, 1, 0, 0);
  ctx.fillStyle = '#ffffff';
  ctx.fillRect(0, 0, cv.width, cv.height);
  for (const s of strokes.value) {
    ctx.fillStyle = paletteColor(s.color);
    ctx.fill(strokePath(s));
  }
}

// ── Input: drawing, erasing, pan/pinch ─────────────────────────

const activePointers = new Map<number, { x: number; y: number; type: string }>();
let drawing: { id: number; type: string; points: InkPoint[] } | null = null;
let erasing: { entries: { index: number; stroke: ScribbleStrokeDto }[] } | null = null;
let pan: { snap: HTMLCanvasElement; startOffset: { x: number; y: number }; last: { x: number; y: number } } | null =
  null;
let pinch: {
  snap: HTMLCanvasElement;
  snapScale: number;
  snapOffset: { x: number; y: number };
  startDist: number;
  startScale: number;
  sheetAtCentroid: { x: number; y: number };
} | null = null;

function pointerOf(ev: PointerEvent): { x: number; y: number; type: string } {
  const rect = container.value?.getBoundingClientRect();
  return {
    x: ev.clientX - (rect?.left ?? 0),
    y: ev.clientY - (rect?.top ?? 0),
    type: ev.pointerType,
  };
}

function toSheet(p: { x: number; y: number }): { x: number; y: number } {
  return { x: (p.x - viewport.x) / viewport.scale, y: (p.y - viewport.y) / viewport.scale };
}

function touchPointerCount(): number {
  let n = 0;
  for (const p of activePointers.values()) if (p.type === 'touch') n++;
  return n;
}

/** Pen and mouse always draw; a finger only with the explicit toggle. */
function wantsToDraw(type: string): boolean {
  return (
    props.editable &&
    mode.value === 'pen' &&
    (type === 'pen' || type === 'mouse' || (type === 'touch' && fingerDraw.value))
  );
}

function snapshotOfBase(): HTMLCanvasElement {
  const src = baseCanvas.value;
  if (!src) throw new Error('base canvas unavailable');
  const snap = document.createElement('canvas');
  snap.width = src.width;
  snap.height = src.height;
  snap.getContext('2d')!.drawImage(src, 0, 0);
  return snap;
}

/** Blit a snapshot scaled/translated from its capture-time viewport. */
function blit(snap: HTMLCanvasElement, snapScale: number, snapOffset: { x: number; y: number }): void {
  const cv = baseCanvas.value;
  if (!cv) return;
  const ctx = ctxOf(cv);
  const { w, h } = cssSize(cv);
  ctx.clearRect(0, 0, w, h);
  const ratio = viewport.scale / snapScale;
  const snapCssW = snap.width / dpr;
  const snapCssH = snap.height / dpr;
  ctx.drawImage(
    snap,
    0,
    0,
    snap.width,
    snap.height,
    viewport.x - snapOffset.x * ratio,
    viewport.y - snapOffset.y * ratio,
    snapCssW * ratio,
    snapCssH * ratio,
  );
}

function beginPan(p: { x: number; y: number }): void {
  pan = {
    snap: snapshotOfBase(),
    startOffset: { x: viewport.x, y: viewport.y },
    last: { ...p },
  };
}

function endPan(): void {
  if (pan) renderBase();
  pan = null;
}

function beginPinch(): void {
  const touches = [...activePointers.values()].filter((p) => p.type === 'touch');
  if (touches.length < 2) return;
  const a = touches[0];
  const b = touches[1];
  const centroid = { x: (a.x + b.x) / 2, y: (a.y + b.y) / 2 };
  pinch = {
    snap: snapshotOfBase(),
    snapScale: viewport.scale,
    snapOffset: { x: viewport.x, y: viewport.y },
    startDist: Math.max(1, Math.hypot(a.x - b.x, a.y - b.y)),
    startScale: viewport.scale,
    sheetAtCentroid: {
      x: (centroid.x - viewport.x) / viewport.scale,
      y: (centroid.y - viewport.y) / viewport.scale,
    },
  };
}

function onPointerDown(ev: PointerEvent): void {
  if (!props.editable) return;
  // The toolbar is a CHILD of the surface: its pointerdown bubbles here.
  // Handling it would start ink on the toolbar and — worse —
  // setPointerCapture would steal the click from the button, killing
  // every toolbar control. Only pointers that start on a canvas (or the
  // bare surface) become gestures.
  if (ev.target !== ev.currentTarget && !(ev.target instanceof HTMLCanvasElement)) return;
  if (ev.button !== 0 && ev.button !== 1) return;
  ev.preventDefault();
  container.value?.setPointerCapture(ev.pointerId);
  const p = pointerOf(ev);
  activePointers.set(ev.pointerId, p);

  // A touch arriving while the pen writes is a palm — never a gesture.
  if (drawing && p.type === 'touch') return;

  if (touchPointerCount() >= 2) {
    endPan();
    beginPinch();
    return;
  }
  if (pinch) return;

  // Middle button: pan on desktop.
  if (ev.button === 1) {
    beginPan(p);
    return;
  }
  if (wantsToDraw(p.type)) {
    drawing = { id: ev.pointerId, type: p.type, points: [{ ...toSheet(p), pressure: ev.pressure > 0 ? ev.pressure : 0.5 }] };
    renderLive();
    return;
  }
  if (mode.value === 'eraser' && (p.type !== 'touch' || fingerDraw.value)) {
    erasing = { entries: [] };
    eraseAt(toSheet(p));
    return;
  }
  // A finger that does not draw navigates — that is the iPad convention.
  if (p.type === 'touch') {
    beginPan(p);
  }
}

function onPointerMove(ev: PointerEvent): void {
  const tracked = activePointers.get(ev.pointerId);
  if (tracked) {
    const rect = container.value?.getBoundingClientRect();
    tracked.x = ev.clientX - (rect?.left ?? 0);
    tracked.y = ev.clientY - (rect?.top ?? 0);
  }

  if (drawing && ev.pointerId === drawing.id) {
    // Coalesced samples: the stylus' full rate, not the display's.
    const batch = typeof ev.getCoalescedEvents === 'function' ? ev.getCoalescedEvents() : [];
    for (const e of batch.length > 0 ? batch : [ev]) {
      const p = pointerOf(e);
      drawing.points.push({
        ...toSheet(p),
        pressure: e.pressure > 0 ? e.pressure : 0.5,
      });
    }
    renderLive();
    return;
  }
  if (erasing) {
    eraseAt(toSheet(pointerOf(ev)));
    return;
  }
  if (pinch) {
    const touches = [...activePointers.values()].filter((p) => p.type === 'touch');
    if (touches.length < 2) return;
    const a = touches[0];
    const b = touches[1];
    const centroid = { x: (a.x + b.x) / 2, y: (a.y + b.y) / 2 };
    const dist = Math.max(1, Math.hypot(a.x - b.x, a.y - b.y));
    viewport.scale = clampScale(pinch.startScale * (dist / pinch.startDist));
    viewport.x = centroid.x - pinch.sheetAtCentroid.x * viewport.scale;
    viewport.y = centroid.y - pinch.sheetAtCentroid.y * viewport.scale;
    blit(pinch.snap, pinch.snapScale, pinch.snapOffset);
    return;
  }
  if (pan && tracked) {
    viewport.x += tracked.x - pan.last.x;
    viewport.y += tracked.y - pan.last.y;
    pan.last = { x: tracked.x, y: tracked.y };
    blit(pan.snap, viewport.scale, pan.startOffset);
  }
}

function onPointerUp(ev: PointerEvent): void {
  activePointers.delete(ev.pointerId);

  if (drawing && ev.pointerId === drawing.id) {
    finishStroke();
    drawing = null;
    clearLive();
    return;
  }
  if (erasing) {
    if (erasing.entries.length > 0) {
      history.push({ type: 'remove', entries: erasing.entries });
      syncHistoryState();
      emitChange();
    }
    erasing = null;
    return;
  }
  if (pinch && touchPointerCount() < 2) {
    pinch = null;
    renderBase();
    return;
  }
  if (pan && activePointers.size === 0) {
    endPan();
  }
}

function finishStroke(): void {
  if (!drawing || drawing.points.length === 0) return;
  const points = commitPoints(drawing.points);
  if (points.length === 0) return;
  const stroke: ScribbleStrokeDto = {
    tool: 'pen',
    color: color.value,
    width: width.value,
    points,
  };
  strokes.value = [...strokes.value, stroke];
  history.push({ type: 'add', strokes: [stroke] });
  syncHistoryState();
  renderBase();
  emitChange();
}

/** Erase every stroke under the point; entries keep their original indices. */
function eraseAt(p: { x: number; y: number }): void {
  if (!erasing) return;
  let removed = 0;
  for (let i = strokes.value.length - 1; i >= 0; i--) {
    if (strokeHit(strokes.value[i], p.x, p.y)) {
      erasing.entries.push({ index: i, stroke: strokes.value[i] });
      const next = [...strokes.value];
      next.splice(i, 1);
      strokes.value = next;
      removed++;
    }
  }
  if (removed > 0) renderBase();
}

// ── Live layer ─────────────────────────────────────────────────

function renderLive(): void {
  const cv = liveCanvas.value;
  if (!cv || !drawing) return;
  const ctx = ctxOf(cv);
  const { w, h } = cssSize(cv);
  ctx.clearRect(0, 0, w, h);
  if (drawing.points.length === 0) return;
  ctx.save();
  ctx.translate(viewport.x, viewport.y);
  ctx.scale(viewport.scale, viewport.scale);
  const live: ScribbleStrokeDto = {
    tool: 'pen',
    color: color.value,
    width: width.value,
    points: drawing.points.map((p) => [p.x, p.y, p.pressure]),
  };
  ctx.fillStyle = paletteColor(color.value);
  // simulatePressure only for devices that report none (mouse/finger).
  ctx.fill(strokePath(live, drawing.type === 'mouse' || drawing.type === 'touch'));
  ctx.restore();
}

// ── Wheel: pan, ctrl/cmd+wheel: zoom (trackpad pinch arrives as that) ──

function onWheel(ev: WheelEvent): void {
  if (!props.editable) return;
  ev.preventDefault();
  if (ev.ctrlKey || ev.metaKey) {
    const rect = container.value?.getBoundingClientRect();
    if (!rect) return;
    zoomAt(ev.clientX - rect.left, ev.clientY - rect.top, Math.exp(-ev.deltaY * 0.01));
  } else {
    viewport.x -= ev.deltaX;
    viewport.y -= ev.deltaY;
    renderBase();
  }
}

function clampScale(s: number): number {
  return Math.min(SCALE_MAX, Math.max(SCALE_MIN, s));
}

function zoomAt(cx: number, cy: number, factor: number): void {
  const next = clampScale(viewport.scale * factor);
  const sheet = { x: (cx - viewport.x) / viewport.scale, y: (cy - viewport.y) / viewport.scale };
  viewport.scale = next;
  viewport.x = cx - sheet.x * next;
  viewport.y = cy - sheet.y * next;
  renderBase();
}

function fitToView(): void {
  const host = container.value;
  if (!host) return;
  const w = host.clientWidth;
  const h = host.clientHeight;
  if (w === 0 || h === 0) return;
  viewport.scale = clampScale(Math.min((w - 48) / sheetW.value, (h - 48) / sheetH.value));
  viewport.x = (w - sheetW.value * viewport.scale) / 2;
  viewport.y = (h - sheetH.value * viewport.scale) / 2;
  renderBase();
}

// ── Undo / redo ────────────────────────────────────────────────

function undo(): void {
  const op = history.popUndo();
  if (!op) return;
  strokes.value = revertOp(strokes.value, op);
  syncHistoryState();
  renderBase();
  emitChange();
}

function redo(): void {
  const op = history.popRedo();
  if (!op) return;
  strokes.value = applyOp(strokes.value, op);
  syncHistoryState();
  renderBase();
  emitChange();
}

function onKeydown(ev: KeyboardEvent): void {
  if (!(ev.ctrlKey || ev.metaKey)) return;
  const key = ev.key.toLowerCase();
  if (key === 'z' && !ev.shiftKey) {
    ev.preventDefault();
    undo();
  } else if ((key === 'z' && ev.shiftKey) || key === 'y') {
    ev.preventDefault();
    redo();
  }
}

// ── Lifecycle ──────────────────────────────────────────────────

onMounted(() => {
  resizeObserver = new ResizeObserver(resize);
  if (container.value) resizeObserver.observe(container.value);
  resize();
  window.addEventListener('beforeprint', renderPrintSheet);
});

onBeforeUnmount(() => {
  window.removeEventListener('beforeprint', renderPrintSheet);
  resizeObserver?.disconnect();
  resizeObserver = null;
});
</script>

<template>
  <div
    ref="container"
    class="relative h-full w-full touch-none select-none overflow-hidden bg-slate-100 focus:outline-none"
    tabindex="0"
    @pointerdown="onPointerDown"
    @pointermove="onPointerMove"
    @pointerup="onPointerUp"
    @pointercancel="onPointerUp"
    @wheel="onWheel"
    @keydown="onKeydown"
    @contextmenu.prevent
  >
    <canvas ref="baseCanvas" class="absolute inset-0"></canvas>
    <canvas ref="liveCanvas" class="absolute inset-0"></canvas>
    <!-- Paper body: the screen canvases hold the zoomed viewport raster,
         useless on paper. This one is invisible on screen and gets the full
         sheet rendered 1:1 on beforeprint; print.css switches it on and
         the width below scales it onto the page. -->
    <canvas ref="printCanvas" class="print-only scribble-print-sheet"></canvas>

    <div
      v-if="editable && strokes.length === 0"
      class="no-print pointer-events-none absolute inset-0 flex items-center justify-center"
    >
      <p class="max-w-xs text-center text-sm text-slate-400">
        {{ t('scribble.editor.emptyHint') }}
      </p>
    </div>

    <div
      v-if="editable"
      class="no-print absolute left-1/2 top-3 z-10 flex -translate-x-1/2 items-center gap-1 rounded-lg border border-slate-200 bg-white/95 p-1 shadow-sm"
    >
      <button
        v-for="size in sizeOptions"
        :key="size.key"
        type="button"
        class="flex h-8 w-8 items-center justify-center rounded-md"
        :class="width === size.key ? 'bg-slate-900 text-white' : 'text-slate-600 hover:bg-slate-100'"
        :title="t(size.labelKey)"
        :aria-label="t(size.labelKey)"
        @click="width = size.key"
      >
        <span class="rounded-full bg-current" :class="size.dot"></span>
      </button>

      <span class="mx-1 h-5 w-px bg-slate-200"></span>

      <button
        v-for="sw in swatches"
        :key="sw.index"
        type="button"
        class="h-8 w-8 rounded-md"
        :class="color === sw.index ? 'ring-2 ring-slate-900 ring-offset-1' : 'hover:bg-slate-100'"
        :title="t(sw.labelKey)"
        :aria-label="t(sw.labelKey)"
        @click="((color = sw.index), (mode = 'pen'))"
      >
        <span
          class="mx-auto block h-4 w-4 rounded-full border border-black/10"
          :style="{ backgroundColor: sw.hex }"
        ></span>
      </button>

      <span class="mx-1 h-5 w-px bg-slate-200"></span>

      <button
        type="button"
        class="flex h-8 w-8 items-center justify-center rounded-md text-sm"
        :class="mode === 'eraser' ? 'bg-slate-900 text-white' : 'text-slate-600 hover:bg-slate-100'"
        :title="t('scribble.editor.eraser')"
        :aria-label="t('scribble.editor.eraser')"
        @click="mode = mode === 'eraser' ? 'pen' : 'eraser'"
      >
        ⌫
      </button>

      <span class="mx-1 h-5 w-px bg-slate-200"></span>

      <button
        type="button"
        class="flex h-8 w-8 items-center justify-center rounded-md text-sm disabled:opacity-30"
        :class="canUndo ? 'text-slate-600 hover:bg-slate-100' : ''"
        :disabled="!canUndo"
        :title="t('scribble.editor.undo')"
        :aria-label="t('scribble.editor.undo')"
        @click="undo"
      >
        ↶
      </button>
      <button
        type="button"
        class="flex h-8 w-8 items-center justify-center rounded-md text-sm disabled:opacity-30"
        :class="canRedo ? 'text-slate-600 hover:bg-slate-100' : ''"
        :disabled="!canRedo"
        :title="t('scribble.editor.redo')"
        :aria-label="t('scribble.editor.redo')"
        @click="redo"
      >
        ↷
      </button>

      <span class="mx-1 h-5 w-px bg-slate-200"></span>

      <button
        type="button"
        class="flex h-8 items-center justify-center rounded-md px-2 text-sm"
        :class="fingerDraw ? 'bg-slate-900 text-white' : 'text-slate-600 hover:bg-slate-100'"
        :title="t('scribble.editor.fingerDraw')"
        :aria-label="t('scribble.editor.fingerDraw')"
        @click="fingerDraw = !fingerDraw"
      >
        ☝
      </button>
      <button
        type="button"
        class="flex h-8 w-8 items-center justify-center rounded-md text-sm text-slate-600 hover:bg-slate-100"
        :title="t('scribble.editor.fit')"
        :aria-label="t('scribble.editor.fit')"
        @click="fitToView"
      >
        ⤢
      </button>
      <span class="w-10 text-center text-xs tabular-nums text-slate-500">{{ zoomPct }}%</span>
    </div>
  </div>
</template>

<style scoped>
/* On paper the print-only canvas takes the full page width; the intrinsic
   1240×1754 buffer keeps the A4 aspect ratio, so a sheet fits one page. */
.scribble-print-sheet {
  width: 100%;
  height: auto;
  background: #ffffff;
}
</style>
