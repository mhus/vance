<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue';
import {
  emptyTimeline,
  parseTimeline,
  timelinePosition,
  type TimelineAxis,
  type TimelineDocument,
  type TimelineEntry,
} from './timelineCodec';

// `meta` and `embedRef` come from the host's chat-fence rendering
// pipeline. Declared for shape-compatibility when the host loads this
// component through the inline-fence channel; the renderer does not
// read them.
type FenceMeta = Record<string, unknown>;
type EmbedRef = { path: string };

// Typed subset of the host's DocumentDto — we only read the body and
// its mime type, so a local shape keeps the addon independent of the
// host's DTO catalog.
interface DocumentDto {
  inlineText?: string;
  mimeType?: string;
}

/**
 * Read-only renderer for `kind: timeline` documents.
 *
 * Everything is drawn against one number line, produced by
 * `timelinePosition()`: a numeric axis (optionally counting backwards,
 * for "millions of years ago") and a date-time axis reduce to the same
 * coordinates, and only the ruler's labels differ. That projection is
 * the whole reason this is a kind of its own — a calendar's month grid
 * is bound to the Gregorian calendar and has no vertical axis for
 * parallel strands.
 *
 * Three drawing rules carry the meaning:
 *   - an entry with `to` is a **bar**, one without is a **marker**;
 *   - **lanes** stack vertically, and entries inside a lane are packed
 *     into as few rows as their overlaps allow, grouped by nesting
 *     depth (`parent`), so an era and its epochs read as indentation;
 *   - **uncertainty** is drawn as faded edges, never as a hard one.
 *     A bar whose start is only known to lie between two instants
 *     shows solid where it is certain and faded where it is not.
 *
 * Date-times are formatted in UTC on purpose: positions come from the
 * strings in the document, and a reader in another zone should see the
 * clock the author wrote, not a shifted one.
 */
const props = withDefaults(defineProps<{
  mode?: 'editor' | 'inline' | 'embedded';
  doc?: TimelineDocument;
  content?: string;
  meta?: FenceMeta;
  document?: DocumentDto;
  embedRef?: EmbedRef;
}>(), {
  mode: 'editor',
  meta: () => ({}),
});

// ── Document resolution ─────────────────────────────────────────────

const resolvedDoc = computed<TimelineDocument>(() => {
  if (props.doc) return props.doc;
  if (props.mode === 'inline') {
    try { return parseTimeline(props.content ?? '', 'application/yaml'); }
    catch (e) {
      console.warn('TimelineView: failed to parse inline content', e);
      return emptyTimeline();
    }
  }
  const d = props.document;
  if (!d || !d.inlineText) return emptyTimeline();
  try { return parseTimeline(d.inlineText, d.mimeType ?? 'application/yaml'); }
  catch (e) {
    console.warn('TimelineView: failed to parse embedded document', e);
    return emptyTimeline();
  }
});

const axis = computed<TimelineAxis>(() => resolvedDoc.value.axis);

// ── Positioning ─────────────────────────────────────────────────────

interface Placed {
  entry: TimelineEntry;
  /** Certain start. */
  start: number;
  /** Certain end; equal to `start` for a marker. */
  end: number;
  /** Leftmost extent including uncertainty. */
  spanStart: number;
  /** Rightmost extent including uncertainty. */
  spanEnd: number;
  /** Solid core — `null` when uncertainty swallows the whole bar. */
  coreStart: number;
  coreEnd: number;
  isPeriod: boolean;
  depth: number;
  laneId: string;
  color: string;
}

/** Entries whose position the axis cannot read, kept for the notice. */
const unreadable = computed<TimelineEntry[]>(() => {
  const a = axis.value;
  return resolvedDoc.value.entries.filter(e => timelinePosition(a, e.from) === null);
});

const placed = computed<Placed[]>(() => {
  const a = axis.value;
  const byId = new Map(resolvedDoc.value.entries.map(e => [e.id, e]));
  const out: Placed[] = [];

  for (const entry of resolvedDoc.value.entries) {
    const start = timelinePosition(a, entry.from);
    if (start === null) continue;
    const rawEnd = entry.to !== undefined ? timelinePosition(a, entry.to) : null;
    const isPeriod = rawEnd !== null;
    const end = rawEnd !== null ? Math.max(rawEnd, start) : start;

    const fromEarliest = timelinePosition(a, entry.fromEarliest);
    const fromLatest = timelinePosition(a, entry.fromLatest);
    const toEarliest = timelinePosition(a, entry.toEarliest);
    const toLatest = timelinePosition(a, entry.toLatest);

    const spanStart = Math.min(start, fromEarliest ?? start, fromLatest ?? start);
    const spanEnd = Math.max(end, toEarliest ?? end, toLatest ?? end,
      isPeriod ? end : (fromLatest ?? start));

    // The solid core is what is certain: from the latest possible start
    // to the earliest possible end. A window wider than the bar leaves
    // no core, which is itself the honest picture.
    const coreStart = Math.max(start, fromLatest ?? start);
    const coreEnd = Math.min(end, toEarliest ?? end);

    out.push({
      entry,
      start, end, spanStart, spanEnd,
      coreStart, coreEnd: Math.max(coreStart, coreEnd),
      isPeriod,
      depth: depthOf(entry, byId),
      laneId: entry.lane ?? '',
      color: colorFor(entry),
    });
  }
  return out;
});

/** Nesting depth from the `parent` chain; a broken or circular chain reads as 0. */
function depthOf(entry: TimelineEntry, byId: Map<string, TimelineEntry>): number {
  let depth = 0;
  const seen = new Set<string>([entry.id]);
  let cursor = entry;
  while (cursor.parent !== undefined) {
    const parent = byId.get(cursor.parent);
    if (!parent || seen.has(parent.id)) return depth;
    seen.add(parent.id);
    cursor = parent;
    depth += 1;
    if (depth > 12) return depth;
  }
  return depth;
}

function colorFor(entry: TimelineEntry): string {
  const declared = entry.color ?? laneColor(entry.lane);
  const c = declared?.trim().toLowerCase();
  if (!c) return 'var(--color-primary)';
  switch (c) {
    case 'blue':   return '#3b82f6';
    case 'green':  return '#10b981';
    case 'red':    return '#ef4444';
    case 'orange': return '#f59e0b';
    case 'yellow': return '#eab308';
    case 'purple': return '#a855f7';
    case 'pink':   return '#ec4899';
    case 'teal':   return '#14b8a6';
    case 'gray':
    case 'grey':   return '#6b7280';
    default:       return declared ?? 'var(--color-primary)';
  }
}

function laneColor(laneId: string | undefined): string | undefined {
  if (!laneId) return undefined;
  return resolvedDoc.value.lanes.find(l => l.id === laneId)?.color;
}

// ── Domain + viewport ───────────────────────────────────────────────

interface Domain { from: number; to: number }

/** The window the document asks for, when it declares one. */
const declaredDomain = computed<Domain | null>(() => {
  const a = axis.value;
  const from = timelinePosition(a, a.from);
  const to = timelinePosition(a, a.to);
  if (from === null || to === null || from >= to) return null;
  return { from, to };
});

/** The window that shows every entry, with a little air on both sides. */
const fittedDomain = computed<Domain>(() => {
  const items = placed.value;
  if (items.length === 0) return { from: 0, to: 1 };
  let min = Infinity;
  let max = -Infinity;
  for (const p of items) {
    min = Math.min(min, p.spanStart);
    max = Math.max(max, p.spanEnd);
  }
  if (min === max) {
    // A single moment has no span; give it a readable neighbourhood
    // instead of dividing by zero.
    const pad = axis.value.mode === 'datetime' ? 1800 : 1;
    return { from: min - pad, to: max + pad };
  }
  const pad = (max - min) * 0.04;
  return { from: min - pad, to: max + pad };
});

const homeDomain = computed<Domain>(() => declaredDomain.value ?? fittedDomain.value);

/** Live viewport. `null` means "follow home" — reset drops back to it. */
const viewport = ref<Domain | null>(null);
const view = computed<Domain>(() => viewport.value ?? homeDomain.value);
const viewSpan = computed(() => Math.max(view.value.to - view.value.from, 1e-9));
const isZoomed = computed(() => viewport.value !== null);

// A different document means a different scale; keep no zoom across it.
watch(resolvedDoc, () => { viewport.value = null; selectedId.value = null; });

function pct(value: number): number {
  return ((value - view.value.from) / viewSpan.value) * 100;
}

/**
 * Percentage clamped to just outside the track. Keeps a bar that
 * extends far beyond the viewport from carrying its label off-screen
 * with it — the track clips at its own edge either way, so the only
 * effect is that the visible part of a long period stays labelled.
 */
function clampedPct(value: number): number {
  return Math.min(115, Math.max(-15, pct(value)));
}

/** Outer geometry of a period, uncertain edges included. */
function barStyle(p: Placed): Record<string, string> {
  const left = clampedPct(p.spanStart);
  const right = clampedPct(p.spanEnd);
  return {
    left: `${left}%`,
    width: `${Math.max(0.15, right - left)}%`,
    '--tl-color': p.color,
  };
}

/**
 * The solid core inside a bar, in the bar's own coordinates. Derived
 * from the same clamped edges as {@link barStyle}, so a bar running off
 * the viewport keeps its certain and uncertain parts aligned.
 */
function coreStyle(p: Placed): Record<string, string> {
  const left = clampedPct(p.spanStart);
  const right = clampedPct(p.spanEnd);
  const outer = right - left;
  if (outer <= 0) return { left: '0%', width: '100%' };
  const coreLeft = clampedPct(p.coreStart);
  const coreRight = clampedPct(p.coreEnd);
  return {
    left: `${((coreLeft - left) / outer) * 100}%`,
    width: `${Math.max(0, ((coreRight - coreLeft) / outer) * 100)}%`,
  };
}

/**
 * A marker close to the right edge puts its label on the left instead.
 * Otherwise the label runs into the track's clip and the reader sees a
 * truncated word with no way to widen it (measured: "Kreide-Paläogen-
 * Aussterben" at the end of the Mesozoic reading as "Kreid").
 */
function flipLabel(p: Placed): boolean {
  return pct(p.start) > 70;
}

/**
 * A marker is anchored by whichever edge its label grows away from: by
 * `left` normally, by `right` when flipped. Anchoring a flipped marker
 * by `left` would push the diamond right by the label's width and land
 * it away from the position it marks.
 */
function pointStyle(p: Placed): Record<string, string> {
  const at = clampedPct(p.start);
  return flipLabel(p)
    ? { right: `${100 - at}%`, '--tl-color': p.color }
    : { left: `${at}%`, '--tl-color': p.color };
}

/** Geometry of a point's uncertainty window, in track coordinates. */
function pointWindowStyle(p: Placed): Record<string, string> {
  const left = clampedPct(p.spanStart);
  const right = clampedPct(p.spanEnd);
  return {
    left: `${left}%`,
    width: `${Math.max(0.15, right - left)}%`,
    '--tl-color': p.color,
  };
}

function zoomBy(factor: number, anchorRatio = 0.5): void {
  const v = view.value;
  const span = v.to - v.from;
  const anchor = v.from + span * anchorRatio;
  const nextSpan = span * factor;
  viewport.value = {
    from: anchor - nextSpan * anchorRatio,
    to: anchor + nextSpan * (1 - anchorRatio),
  };
}

function resetView(): void {
  viewport.value = null;
}

function onWheel(ev: WheelEvent): void {
  if (placed.value.length === 0) return;
  ev.preventDefault();
  const track = ev.currentTarget as HTMLElement;
  const rect = track.getBoundingClientRect();
  const ratio = rect.width > 0
    ? Math.min(1, Math.max(0, (ev.clientX - rect.left) / rect.width))
    : 0.5;
  zoomBy(ev.deltaY > 0 ? 1.2 : 1 / 1.2, ratio);
}

// ── Panning ─────────────────────────────────────────────────────────

let panFrom: { x: number; width: number; domain: Domain } | null = null;

function onPanStart(ev: PointerEvent): void {
  if (ev.button !== 0 || placed.value.length === 0) return;
  const rect = (ev.currentTarget as HTMLElement).getBoundingClientRect();
  if (rect.width <= 0) return;
  panFrom = { x: ev.clientX, width: rect.width, domain: { ...view.value } };
  window.addEventListener('pointermove', onPanMove);
  window.addEventListener('pointerup', onPanEnd);
}

function onPanMove(ev: PointerEvent): void {
  if (!panFrom) return;
  const span = panFrom.domain.to - panFrom.domain.from;
  const delta = ((ev.clientX - panFrom.x) / panFrom.width) * span;
  viewport.value = {
    from: panFrom.domain.from - delta,
    to: panFrom.domain.to - delta,
  };
}

function onPanEnd(): void {
  panFrom = null;
  window.removeEventListener('pointermove', onPanMove);
  window.removeEventListener('pointerup', onPanEnd);
}

onBeforeUnmount(onPanEnd);

// ── Lanes and row packing ───────────────────────────────────────────

interface Row { depth: number; items: Placed[] }
interface Lane { id: string; title: string; rows: Row[] }

const lanes = computed<Lane[]>(() => {
  const declared = resolvedDoc.value.lanes;
  const order: string[] = [];
  const titles = new Map<string, string>();

  // An entry without a lane goes into the unnamed default lane, which
  // leads — a document that never mentions lanes then looks like a
  // plain single-track timeline.
  if (placed.value.some(p => p.laneId === '')) {
    order.push('');
    titles.set('', '');
  }
  for (const lane of declared) {
    if (!order.includes(lane.id)) order.push(lane.id);
    titles.set(lane.id, lane.title ?? lane.id);
  }
  // Lanes only an entry names are appended in first-appearance order —
  // visible, but after everything the document declared.
  for (const p of placed.value) {
    if (p.laneId !== '' && !order.includes(p.laneId)) {
      order.push(p.laneId);
      titles.set(p.laneId, p.laneId);
    }
  }

  return order.map(id => ({
    id,
    title: titles.get(id) ?? id,
    rows: packRows(placed.value.filter(p => p.laneId === id)),
  })).filter(lane => lane.rows.length > 0 || declared.some(d => d.id === lane.id));
});

/**
 * Pack a lane's entries into as few rows as their overlaps allow, one
 * group of rows per nesting depth. Greedy first-fit over entries sorted
 * by start: an era and its epochs land on separate rows because they
 * differ in depth, while two unrelated events at the same time stack
 * because they overlap.
 *
 * The gap is measured in fractions of the *current* viewport, so two
 * entries that are distinct but very close still get separate rows when
 * zoomed out far enough for their labels to collide.
 */
function packRows(items: Placed[]): Row[] {
  const byDepth = new Map<number, Placed[]>();
  for (const item of items) {
    const bucket = byDepth.get(item.depth);
    if (bucket) bucket.push(item);
    else byDepth.set(item.depth, [item]);
  }

  const gap = viewSpan.value * 0.02;
  const rows: Row[] = [];
  for (const depth of [...byDepth.keys()].sort((a, b) => a - b)) {
    const sorted = [...(byDepth.get(depth) ?? [])].sort((a, b) => a.spanStart - b.spanStart);
    const ends: number[] = [];
    const depthRows: Placed[][] = [];
    for (const item of sorted) {
      let placedInRow = false;
      for (let i = 0; i < depthRows.length; i++) {
        if (ends[i] <= item.spanStart - gap) {
          depthRows[i].push(item);
          ends[i] = occupiedEnd(item);
          placedInRow = true;
          break;
        }
      }
      if (!placedInRow) {
        depthRows.push([item]);
        ends.push(occupiedEnd(item));
      }
    }
    for (const row of depthRows) rows.push({ depth, items: row });
  }
  return rows;
}

/**
 * How far right an entry actually occupies its row. For a bar that is
 * its own end — the label is clipped inside the box. A marker's label
 * however sits *outside* the position it marks, so packing on the span
 * alone puts a later bar straight on top of it (measured: "Notruf" at
 * 22:31 disappearing under a bar starting 22:39). Estimated from the
 * character count against the current viewport, in the same
 * viewport-relative currency as the gap — the pixel width of the track
 * is not known here, and a rough allowance beats a guaranteed overlap.
 */
function occupiedEnd(item: Placed): number {
  if (item.isPeriod) return item.spanEnd;
  const perChar = viewSpan.value * 0.0075;
  return item.spanEnd + item.entry.title.length * perChar;
}

// ── Ruler ───────────────────────────────────────────────────────────

interface Tick { pos: number; label: string }

const NUMERIC_STEPS = [1, 2, 2.5, 5, 10];
/** Seconds-per-step ladder for a date-time ruler, coarsest chosen last. */
const TIME_STEPS = [
  1, 5, 15, 30,
  60, 300, 900, 1800,
  3600, 3 * 3600, 6 * 3600, 12 * 3600,
  86400, 2 * 86400, 7 * 86400, 14 * 86400,
  30 * 86400, 90 * 86400, 182 * 86400,
];
const YEAR_SECONDS = 365.2425 * 86400;

const ticks = computed<Tick[]>(() => {
  const v = view.value;
  const span = viewSpan.value;
  const target = props.mode === 'inline' ? 4 : 8;
  const step = axis.value.mode === 'datetime'
    ? niceTimeStep(span / target)
    : niceNumberStep(span / target);
  if (!Number.isFinite(step) || step <= 0) return [];

  const out: Tick[] = [];
  const first = Math.ceil(v.from / step) * step;
  for (let value = first; value <= v.to && out.length < 40; value += step) {
    out.push({ pos: value, label: tickLabel(value, step) });
  }
  return out;
});

function niceNumberStep(raw: number): number {
  const magnitude = Math.pow(10, Math.floor(Math.log10(Math.abs(raw) || 1)));
  for (const factor of NUMERIC_STEPS) {
    if (magnitude * factor >= raw) return magnitude * factor;
  }
  return magnitude * 10;
}

function niceTimeStep(raw: number): number {
  for (const step of TIME_STEPS) {
    if (step >= raw) return step;
  }
  // Beyond half a year, step in whole years so labels stay readable.
  return Math.max(1, niceNumberStep(raw / YEAR_SECONDS)) * YEAR_SECONDS;
}

function tickLabel(value: number, step: number): string {
  if (axis.value.mode === 'datetime') return timeTickLabel(value, step);
  const shown = axis.value.direction === 'ago' ? -value : value;
  const decimals = Math.min(6, Math.max(0, -Math.floor(Math.log10(step))));
  const text = shown.toFixed(decimals);
  const unit = axis.value.unit;
  return unit ? `${text} ${unit}` : text;
}

function timeTickLabel(seconds: number, step: number): string {
  const d = new Date(seconds * 1000);
  const pad = (n: number) => String(n).padStart(2, '0');
  const year = d.getUTCFullYear();
  if (step < 60) {
    return `${pad(d.getUTCHours())}:${pad(d.getUTCMinutes())}:${pad(d.getUTCSeconds())}`;
  }
  if (step < 86400) return `${pad(d.getUTCHours())}:${pad(d.getUTCMinutes())}`;
  if (step < 28 * 86400) return `${pad(d.getUTCDate())}.${pad(d.getUTCMonth() + 1)}.`;
  if (step < 300 * 86400) return `${pad(d.getUTCMonth() + 1)}/${year}`;
  return String(year);
}

/** Where "now" sits, when a date-time view contains it. */
const nowPct = computed<number | null>(() => {
  if (axis.value.mode !== 'datetime') return null;
  const now = Date.now() / 1000;
  if (now < view.value.from || now > view.value.to) return null;
  return pct(now);
});

// ── Labels ──────────────────────────────────────────────────────────

/** The position as the author wrote it — never a reformatted guess. */
function rangeLabel(p: Placed): string {
  const from = p.entry.from;
  const unit = axis.value.mode === 'numeric' && axis.value.unit ? ` ${axis.value.unit}` : '';
  if (!p.isPeriod) return `${from}${unit}`;
  return `${from} → ${p.entry.to}${unit}`;
}

/**
 * Uncertainty in words rather than a range with a placeholder for the
 * open side: a one-sided window is the common case ("last seen between
 * 21:40 and 22:05" is `from` plus `fromLatest` only), and "start … –
 * 22:05" reads like a missing value rather than a bound.
 */
function uncertaintyLabel(entry: TimelineEntry): string | null {
  const parts: string[] = [];
  const bound = (what: string, earliest?: string, latest?: string) => {
    if (earliest && latest) parts.push(`${what} between ${earliest} and ${latest}`);
    else if (earliest) parts.push(`${what} no earlier than ${earliest}`);
    else if (latest) parts.push(`${what} no later than ${latest}`);
  };
  bound('start', entry.fromEarliest, entry.fromLatest);
  bound('end', entry.toEarliest, entry.toLatest);
  return parts.length > 0 ? parts.join(', ') : null;
}

// ── Selection ───────────────────────────────────────────────────────

const selectedId = ref<string | null>(null);
const selected = computed<Placed | null>(
  () => placed.value.find(p => p.entry.id === selectedId.value) ?? null,
);

function toggleSelect(p: Placed): void {
  selectedId.value = selectedId.value === p.entry.id ? null : p.entry.id;
}

const showDetail = computed(() => props.mode !== 'inline' && selected.value !== null);
const rootClass = computed(() => ['tl', `tl--${props.mode}`]);
</script>

<template>
  <div :class="rootClass">
    <div class="tl-toolbar">
      <div class="tl-heading">
        <span v-if="resolvedDoc.title" class="tl-title">{{ resolvedDoc.title }}</span>
        <span class="tl-count">
          {{ placed.length }} {{ placed.length === 1 ? 'entry' : 'entries' }}
        </span>
      </div>
      <div class="tl-controls">
        <button type="button" class="tl-btn" title="Zoom out" @click="zoomBy(1.5)">−</button>
        <button type="button" class="tl-btn" title="Zoom in" @click="zoomBy(1 / 1.5)">+</button>
        <button
          type="button"
          class="tl-btn tl-btn--wide"
          :disabled="!isZoomed"
          title="Fit all entries"
          @click="resetView"
        >Fit</button>
      </div>
    </div>

    <div v-if="unreadable.length > 0" class="tl-notice">
      {{ unreadable.length }}
      {{ unreadable.length === 1 ? 'entry has' : 'entries have' }}
      a position this
      {{ axis.mode }} axis cannot read and
      {{ unreadable.length === 1 ? 'is' : 'are' }}
      not drawn:
      {{ unreadable.slice(0, 3).map(e => `${e.title} (${e.from})`).join(', ')
      }}{{ unreadable.length > 3 ? ', …' : '' }}
    </div>

    <div v-if="placed.length === 0" class="tl-empty">
      This timeline has no entries that can be placed on its axis.
    </div>

    <template v-else>
      <!-- Ruler -->
      <div class="tl-body">
        <div class="tl-gutter tl-gutter--ruler"></div>
        <div class="tl-ruler">
          <div
            v-for="(tick, i) in ticks"
            :key="i"
            class="tl-tick"
            :style="{ left: pct(tick.pos) + '%' }"
          >
            <span class="tl-tick-label">{{ tick.label }}</span>
          </div>
        </div>
      </div>

      <!-- Lanes -->
      <div
        class="tl-scroll"
        @wheel="onWheel"
      >
        <div
          v-for="lane in lanes"
          :key="lane.id"
          class="tl-lane"
        >
          <div class="tl-gutter" :title="lane.title">
            <span v-if="lane.title" class="tl-lane-title">{{ lane.title }}</span>
          </div>
          <div class="tl-track" @pointerdown="onPanStart">
            <div
              v-for="(tick, i) in ticks"
              :key="'g' + i"
              class="tl-grid"
              :style="{ left: pct(tick.pos) + '%' }"
            ></div>
            <div
              v-if="nowPct !== null"
              class="tl-now"
              :style="{ left: nowPct + '%' }"
              title="now"
            ></div>

            <div v-if="lane.rows.length === 0" class="tl-row tl-row--empty"></div>
            <div
              v-for="(row, ri) in lane.rows"
              :key="ri"
              class="tl-row"
            >
              <template v-for="p in row.items" :key="p.entry.id">
                <!-- Period: faded edges where the position is uncertain,
                     solid where it is not. -->
                <div
                  v-if="p.isPeriod"
                  class="tl-bar"
                  :class="{
                    'tl-bar--selected': p.entry.id === selectedId,
                    'tl-bar--nested': p.depth > 0,
                  }"
                  :style="barStyle(p)"
                  :title="p.entry.title + ' — ' + rangeLabel(p)"
                  @click="toggleSelect(p)"
                >
                  <div class="tl-bar-core" :style="coreStyle(p)"></div>
                  <span class="tl-bar-label">{{ p.entry.title }}</span>
                </div>

                <!-- Point: a marker at the position, and — when the
                     moment is only known within a window — a faded band
                     covering that window, drawn in track coordinates so
                     it stays aligned with the ruler. -->
                <template v-else>
                  <div
                    v-if="p.spanEnd > p.spanStart"
                    class="tl-point-window"
                    :style="pointWindowStyle(p)"
                  ></div>
                  <div
                    class="tl-point"
                    :class="{
                      'tl-point--selected': p.entry.id === selectedId,
                      'tl-point--flip': flipLabel(p),
                    }"
                    :style="pointStyle(p)"
                    :title="p.entry.title + ' — ' + rangeLabel(p)"
                    @click="toggleSelect(p)"
                  >
                    <span class="tl-point-dot"></span>
                    <span class="tl-point-label">{{ p.entry.title }}</span>
                  </div>
                </template>
              </template>
            </div>
          </div>
        </div>
      </div>

      <div v-if="axis.label" class="tl-axis-label">{{ axis.label }}</div>

      <!-- Detail -->
      <div v-if="showDetail && selected" class="tl-detail">
        <div class="tl-detail-head">
          <span class="tl-detail-swatch" :style="{ background: selected.color }"></span>
          <span class="tl-detail-title">{{ selected.entry.title }}</span>
          <button type="button" class="tl-btn" @click="selectedId = null">✕</button>
        </div>
        <div class="tl-detail-range">{{ rangeLabel(selected) }}</div>
        <div v-if="uncertaintyLabel(selected.entry)" class="tl-detail-fuzzy">
          {{ uncertaintyLabel(selected.entry) }}
        </div>
        <div v-if="selected.entry.lane" class="tl-detail-meta">
          lane: {{ selected.entry.lane }}
        </div>
        <div v-if="selected.entry.parent" class="tl-detail-meta">
          inside: {{ selected.entry.parent }}
        </div>
        <div v-if="selected.entry.notes" class="tl-detail-notes">
          {{ selected.entry.notes }}
        </div>
        <div v-if="selected.entry.tags.length > 0" class="tl-detail-tags">
          <span v-for="tag in selected.entry.tags" :key="tag" class="tl-tag">{{ tag }}</span>
        </div>
      </div>
    </template>
  </div>
</template>

<style scoped>
.tl {
  font-size: 0.9rem;
  --tl-gutter: 8.5rem;
}
.tl--inline { --tl-gutter: 5.5rem; font-size: 0.82rem; }

/* Toolbar */
.tl-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 0.5rem;
  padding: 0.2rem 0.1rem 0.5rem;
  flex-wrap: wrap;
}
.tl-heading { display: flex; align-items: baseline; gap: 0.6rem; min-width: 0; }
.tl-title { font-weight: 600; }
.tl-count { opacity: 0.6; font-size: 0.8rem; }
.tl-controls { display: flex; gap: 0.2rem; }
.tl-btn {
  background: transparent;
  border: 1px solid color-mix(in oklab, var(--color-base-content) 20%, transparent);
  border-radius: 0.25rem;
  padding: 0.15rem 0.5rem;
  font: inherit;
  font-size: 0.85rem;
  color: inherit;
  cursor: pointer;
  line-height: 1.4;
}
.tl-btn--wide { padding-inline: 0.6rem; }
.tl-btn:hover:not(:disabled) {
  background: color-mix(in oklab, var(--color-base-content) 8%, transparent);
}
.tl-btn:disabled { opacity: 0.4; cursor: default; }

.tl-notice {
  border-left: 3px solid #f59e0b;
  background: color-mix(in oklab, #f59e0b 10%, transparent);
  padding: 0.35rem 0.5rem;
  margin-bottom: 0.5rem;
  font-size: 0.8rem;
  border-radius: 0 0.25rem 0.25rem 0;
}
.tl-empty { opacity: 0.6; padding: 1rem 0.25rem; font-style: italic; }

/* Ruler */
.tl-body { display: flex; align-items: stretch; }
.tl-gutter { flex: 0 0 var(--tl-gutter); min-width: 0; padding-right: 0.5rem; }
.tl-gutter--ruler { flex: 0 0 var(--tl-gutter); }
.tl-ruler {
  position: relative;
  flex: 1 1 auto;
  height: 1.5rem;
  border-bottom: 1px solid color-mix(in oklab, var(--color-base-content) 25%, transparent);
}
.tl-tick { position: absolute; top: 0; bottom: 0; }
.tl-tick-label {
  position: absolute;
  bottom: 0.15rem;
  left: 0;
  transform: translateX(-50%);
  font-size: 0.7rem;
  opacity: 0.7;
  white-space: nowrap;
}

/* Lanes */
.tl-scroll { overflow-y: auto; max-height: 34rem; }
.tl--inline .tl-scroll { max-height: 16rem; }
.tl-lane {
  display: flex;
  align-items: stretch;
  border-bottom: 1px solid color-mix(in oklab, var(--color-base-content) 10%, transparent);
}
.tl-lane:last-child { border-bottom: none; }
.tl-lane-title {
  display: block;
  padding-top: 0.55rem;
  font-size: 0.78rem;
  font-weight: 600;
  opacity: 0.75;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.tl-track {
  position: relative;
  flex: 1 1 auto;
  min-width: 0;
  padding: 0.3rem 0;
  cursor: grab;
  touch-action: none;
  overflow: hidden;
}
.tl-track:active { cursor: grabbing; }
.tl-grid {
  position: absolute;
  top: 0;
  bottom: 0;
  width: 1px;
  background: color-mix(in oklab, var(--color-base-content) 8%, transparent);
  pointer-events: none;
}
.tl-now {
  position: absolute;
  top: 0;
  bottom: 0;
  width: 1px;
  background: #ef4444;
  opacity: 0.7;
  pointer-events: none;
}
.tl-row { position: relative; height: 1.6rem; }
.tl-row--empty { height: 1.6rem; }

/* Period */
.tl-bar {
  position: absolute;
  top: 0.15rem;
  height: 1.3rem;
  min-width: 3px;
  border-radius: 0.2rem;
  /* The whole span, including the uncertain edges, is drawn faint; the
     certain core sits on top at full strength. */
  background: color-mix(in oklab, var(--tl-color) 22%, transparent);
  border: 1px solid color-mix(in oklab, var(--tl-color) 45%, transparent);
  box-sizing: border-box;
  cursor: pointer;
  overflow: hidden;
}
.tl-bar--nested { top: 0.3rem; height: 1rem; }
.tl-bar--selected {
  outline: 2px solid var(--tl-color);
  outline-offset: 1px;
}
.tl-bar-core {
  position: absolute;
  top: 0;
  bottom: 0;
  background: color-mix(in oklab, var(--tl-color) 70%, transparent);
  pointer-events: none;
}
.tl-bar-label {
  position: relative;
  display: block;
  padding: 0 0.35rem;
  line-height: 1.25rem;
  font-size: 0.76rem;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  color: var(--color-base-content);
  pointer-events: none;
}
.tl-bar--nested .tl-bar-label { line-height: 0.95rem; font-size: 0.7rem; }

/* Point */
.tl-point {
  position: absolute;
  top: 0.15rem;
  height: 1.3rem;
  display: flex;
  align-items: center;
  cursor: pointer;
}
.tl-point-window {
  position: absolute;
  top: 0.6rem;
  height: 0.4rem;
  background: color-mix(in oklab, var(--tl-color) 28%, transparent);
  border-radius: 0.2rem;
  pointer-events: none;
}
.tl-point-dot {
  position: relative;
  width: 0.55rem;
  height: 0.55rem;
  flex: 0 0 auto;
  margin-left: -0.275rem;
  background: var(--tl-color);
  transform: rotate(45deg);
}
.tl-point--selected .tl-point-dot {
  outline: 2px solid var(--tl-color);
  outline-offset: 2px;
}
.tl-point-label {
  position: relative;
  margin-left: 0.3rem;
  font-size: 0.76rem;
  white-space: nowrap;
  pointer-events: none;
}
/* Near the right edge the label goes to the left of the marker, so the
   track's clip does not cut the word in half. The diamond's centring
   margin mirrors with it. */
.tl-point--flip { flex-direction: row-reverse; }
.tl-point--flip .tl-point-label { margin-left: 0; margin-right: 0.3rem; }
.tl-point--flip .tl-point-dot { margin-left: 0; margin-right: -0.275rem; }

.tl-axis-label {
  padding: 0.4rem 0 0 var(--tl-gutter);
  font-size: 0.75rem;
  opacity: 0.6;
}

/* Detail */
.tl-detail {
  margin-top: 0.6rem;
  border: 1px solid color-mix(in oklab, var(--color-base-content) 15%, transparent);
  border-radius: 0.3rem;
  padding: 0.5rem 0.6rem;
}
.tl-detail-head { display: flex; align-items: center; gap: 0.45rem; }
.tl-detail-swatch {
  width: 0.7rem;
  height: 0.7rem;
  border-radius: 0.15rem;
  flex: 0 0 auto;
}
.tl-detail-title { font-weight: 600; flex: 1 1 auto; min-width: 0; }
.tl-detail-range { font-size: 0.82rem; opacity: 0.85; margin-top: 0.25rem; }
.tl-detail-fuzzy { font-size: 0.78rem; opacity: 0.7; margin-top: 0.1rem; }
.tl-detail-meta { font-size: 0.75rem; opacity: 0.6; margin-top: 0.1rem; }
.tl-detail-notes {
  margin-top: 0.4rem;
  font-size: 0.82rem;
  white-space: pre-wrap;
}
.tl-detail-tags { display: flex; flex-wrap: wrap; gap: 0.25rem; margin-top: 0.4rem; }
.tl-tag {
  font-size: 0.7rem;
  padding: 0.05rem 0.35rem;
  border-radius: 0.6rem;
  background: color-mix(in oklab, var(--color-base-content) 10%, transparent);
}
</style>
