<script setup lang="ts">
import { computed, nextTick, onMounted, ref, watch } from 'vue';
import type { Component } from 'vue';
import { Handle, Position } from '@vue-flow/core';
import { NodeResizer } from '@vue-flow/node-resizer';
import '@vue-flow/node-resizer/dist/style.css';
import { NodeToolbar } from '@vue-flow/node-toolbar';
import { brainFetchText, documentContentUrl, safeUrl, vanceNavigate } from '@vance/shared';
import { cortexDeepLink, VIcon, VIconButton } from '@vance/components';
import { marked } from 'marked';
import DOMPurify from 'dompurify';
import type { CanvasNodeDto } from './generated/canvas/CanvasNodeDto';
import type { CanvasDocItem } from './generated/canvas/CanvasDocItem';
import { resolveDocument } from './api';
import { useT } from './i18n';

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
    /** Reopen the reference picker for this link node. */
    onEditLink?: (id: string) => void;
    /** Persist pending edits now — before a same-tab navigation leaves the board. */
    onFlush?: () => void;
  };
  selected?: boolean;
}>();

const t = useT();

// Stored values stay as they always were (documents keep "1"…"6" and these
// hex text colours); only the RENDERING maps them onto the corporate-design
// content palette and meaning tokens, which follow light and dark mode
// (corporate-design.md §3.3/§3.4).
const TEXT_COLORS = ['#111827', '#dc2626', '#2563eb', '#16a34a', '#ca8a04'];
const TEXT_TOKEN: Record<string, string> = {
  '#111827': 'var(--v-label)',
  '#dc2626': 'var(--v-danger)',
  '#2563eb': 'var(--v-accent-text)',
  '#16a34a': 'var(--v-success)',
  '#ca8a04': 'var(--v-warning)',
};

const COLORS = ['1', '2', '3', '4', '5', '6'];
const CONTENT: Record<string, string> = {
  '1': 'pink', '2': 'orange', '3': 'yellow',
  '4': 'green', '5': 'teal', '6': 'purple',
};
const PALETTE: Record<string, string> = Object.fromEntries(
  COLORS.map((c) => [c, `var(--v-content-${CONTENT[c]}-bg)`]),
);
const PALETTE_FG: Record<string, string> = Object.fromEntries(
  COLORS.map((c) => [c, `var(--v-content-${CONTENT[c]}-fg)`]),
);
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
  return kind.value === 'text' ? 'var(--v-content-yellow-bg)' : 'var(--v-surface)';
});

const groupColor = computed(() =>
  node.value.color ? (PALETTE[node.value.color] ?? node.value.color) : null,
);
// color-mix works for tokens and for a custom hex alike.
const groupBg = computed(() =>
  `color-mix(in srgb, ${groupColor.value ?? 'var(--v-label-tertiary)'} ${groupColor.value ? 45 : 8}%, transparent)`,
);
const groupBorder = computed(() =>
  node.value.color && PALETTE_FG[node.value.color] ? PALETTE_FG[node.value.color] : (groupColor.value ?? 'var(--v-label-tertiary)'),
);

/** Default text colour: the content colour's ink on a coloured card, else the label. */
const defaultText = computed(() =>
  node.value.color && PALETTE_FG[node.value.color] ? PALETTE_FG[node.value.color] : 'var(--v-label)',
);

const textStyle = computed(() => ({
  fontSize: FONT_PX[node.value.fontSize ?? 'm'] ?? '13px',
  fontWeight: node.value.bold ? '700' : '400',
  fontStyle: node.value.italic ? 'italic' : 'normal',
  color: node.value.textColor ? (TEXT_TOKEN[node.value.textColor] ?? node.value.textColor) : defaultText.value,
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

/**
 * A `vance:` reference, taken apart.
 *
 * The **project** matters and used to be dropped: `vance://other/x` has the
 * project as a URI authority, and a naive strip left `other/x` as the path,
 * which then resolved against the *current* project — a cross-project node
 * showed "not found" for a document that exists. Picking a foreign app from the
 * reference picker produces exactly such a ref, so it has to be read properly.
 *
 * `entry` is kept for the same reason: {@link openInCortex} rebuilds the target
 * URL from scratch and would otherwise land on the app's default page — a link
 * that looks like it worked.
 */
function parseRef(ref: string | undefined | null): {
  project: string | null;
  path: string;
  entry: string | null;
} {
  const raw = ref ?? '';
  if (!raw.startsWith('vance:')) {
    return { project: null, path: raw.replace(/^\/+/, '').split('?')[0], entry: null };
  }
  try {
    const url = new URL(raw);
    return {
      project: url.hostname ? decodeURIComponent(url.hostname) : null,
      path: decodeURIComponent(url.pathname.replace(/^\/+/, '')),
      entry: url.searchParams.get('entry') || null,
    };
  } catch {
    // Malformed refs stay usable as a plain path — the same leniency the rest
    // of the card applies to hand-edited YAML.
    const body = raw.slice('vance:'.length).replace(/^\/+/, '');
    return { project: null, path: body.split('?')[0], entry: null };
  }
}

/** The project a ref points into — its own, or the board's. */
function refProject(ref: string | undefined | null): string {
  return parseRef(ref).project ?? (props.data.projectId ?? '');
}

async function loadDoc(): Promise<void> {
  docMeta.value = null;
  imgSrc.value = null;
  mdHtml.value = null;
  docError.value = null;
  if (kind.value !== 'doc') return;
  const ref = node.value.ref;
  if (!props.data.projectId || !ref) return;
  const { path } = parseRef(ref);
  // The ref's own project, not the board's — a foreign-app node lives elsewhere.
  const pid = refProject(ref);
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
  const id = docMeta.value?.id;
  const pid = refProject(node.value.ref);
  if (!pid || !id) return;
  window.open(cortexDeepLink({
    project: pid,
    documentId: id,
    entry: parseRef(node.value.ref).entry,
  }), '_blank', 'noopener');
}

function patch(p: Partial<CanvasNodeDto>): void {
  props.data.onPatch?.(node.value.id, p);
}

// ── Link nodes ────────────────────────────────────────────────
/**
 * An external target we are willing to put in an `href`, or null.
 *
 * `safeUrl` allows http/https/mailto only — `vance:` is deliberately not in that
 * set, because it is not navigable by the browser and goes through
 * {@link openLinkTarget} instead. Null means we render the text without a link
 * rather than a link that goes nowhere.
 */
const isVanceLink = computed(() => (node.value.href ?? '').startsWith('vance:'));
const linkHref = computed(() => (isVanceLink.value ? null : safeUrl(node.value.href)));

/**
 * Whether following the link leaves the board.
 *
 * An unset flag means the node predates the choice: it then keeps opening away
 * from the board, which is what those nodes always did. An explicit `false` is a
 * decision and is honoured — that is the whole point of storing it.
 */
const linkNewTab = computed(() => node.value.newTab !== false);

/** What the card shows as the link's second line — the bare target. */
const linkSubtitle = computed(() => {
  const href = node.value.href ?? '';
  if (!isVanceLink.value) return href;
  const { project, path } = parseRef(href);
  return project ? `${project}/${path}` : path;
});

/**
 * Follow an internal link. Same shape as {@link openInCortex}, but a link node
 * carries no resolved metadata (it never embedded anything), so the id is looked
 * up on the click.
 */
async function openLinkTarget(): Promise<void> {
  const href = node.value.href;
  if (!href || !isVanceLink.value) return;
  const { path, entry } = parseRef(href);
  const pid = refProject(href);
  if (!pid || !path) return;
  try {
    const meta = await resolveDocument(pid, path);
    navigate(cortexDeepLink({ project: pid, documentId: meta.id, entry }));
  } catch (e) {
    // A gone target must not be silent — the card is the only place to say it.
    docError.value = e instanceof Error ? e.message : String(e);
  }
}

/**
 * Leaving the board same-tab has to flush the debounced save first, or an edit
 * made in the last second before the click is lost. The host owns the save, so
 * it is asked; without a host the navigation still happens (nothing to lose).
 */
function navigate(url: string): void {
  if (linkNewTab.value) {
    window.open(url, '_blank', 'noopener');
    return;
  }
  props.data.onFlush?.();
  vanceNavigate(url);
}

/** Plain click on an external link: same tab means flushing first. */
function onExternalClick(event: MouseEvent): void {
  if (linkNewTab.value) return; // the anchor's target does it
  event.preventDefault();
  const href = linkHref.value;
  if (href) navigate(href);
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

/**
 * Double-click opens the editor for a text node only.
 *
 * On a link node the title is reached through the toolbar instead: the card is
 * an anchor there, so a double-click is *two clicks* and would have opened the
 * target twice before any editor appeared.
 */
function onCardDoubleClick(): void {
  if (kind.value !== 'text') return;
  void beginEdit();
}

/**
 * Inline edit: the body of a text node, the *title* of a link node. A link's
 * title is the only thing about it that is prose, and it was previously
 * unreachable once the node existed — the target has its own button.
 */
async function beginEdit(): Promise<void> {
  if (!editable.value) return;
  if (kind.value === 'text') draft.value = node.value.text ?? '';
  else if (kind.value === 'link') draft.value = node.value.title ?? '';
  else return;
  editing.value = true;
  await nextTick();
  autogrow();
  area.value?.focus();
  area.value?.select();
}

function commit(): void {
  if (!editing.value) return;
  editing.value = false;
  if (kind.value === 'link') {
    // Empty means "no title" — the card then shows the target, which is a
    // better fallback than an empty headline.
    const title = draft.value.trim();
    if (title !== (node.value.title ?? '')) patch({ title: title || undefined });
    return;
  }
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

// ── Copy link to clipboard ────────────────────────────────────
const copied = ref(false);
function copyLink(): void {
  const href = node.value.href ?? '';
  if (!href || typeof navigator === 'undefined' || !navigator.clipboard) return;
  void navigator.clipboard.writeText(href).then(() => {
    copied.value = true;
    setTimeout(() => { copied.value = false; }, 1500);
  });
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
    @dblclick.stop="onCardDoubleClick"
  >
    <NodeToolbar :is-visible="editable && selected === true" :position="Position.Top" :offset="10">
      <div class="cv-toolbar nodrag">
        <button
          class="cv-swatch"
          :title="t('canvas.node.noColour')"
          style="background: var(--v-surface)"
          :class="{ 'cv-active': !node.color }"
          @click="patch({ color: undefined })"
        ><VIcon name="close" class="mx-auto" /></button>
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
            :style="{ color: TEXT_TOKEN[tc] ?? tc }"
            :class="{ 'cv-active': node.textColor === tc }"
            :title="t('canvas.node.textColour')"
            @click="patch({ textColor: tc })"
          >A</button>
        </template>

        <template v-if="kind === 'link'">
          <span class="cv-sep"></span>
          <VIconButton icon="edit" size="sm" :label="t('canvas.node.rename')" @click="beginEdit" />
          <VIconButton
            icon="link"
            size="sm"
            :label="t('canvas.node.changeTarget')"
            @click="props.data.onEditLink?.(node.id)"
          />
          <VIconButton
            :icon="copied ? 'check' : 'copy'"
            size="sm"
            :label="copied ? t('canvas.node.copied') : t('canvas.node.copyLink')"
            @click="copyLink"
          />
        </template>

        <span class="cv-sep"></span>
        <VIconButton icon="arrow-up" size="sm" :label="t('canvas.node.bringToFront')" @click="props.data.onFront?.(node.id)" />
        <VIconButton icon="arrow-down" size="sm" :label="t('canvas.node.sendToBack')" @click="props.data.onBack?.(node.id)" />
        <VIconButton icon="delete" size="sm" danger :label="t('canvas.node.delete')" @click="props.data.onDelete?.(node.id)" />
      </div>
    </NodeToolbar>

    <NodeResizer
      v-if="editable"
      color="var(--v-accent)"
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
        {{ node.text || t('canvas.node.emptyNote') }}
      </div>
      <div v-if="node.author && !editing" class="canvas-note-author flex items-center gap-1"><VIcon name="edit" />{{ node.author }}</div>
    </template>

    <template v-else-if="kind === 'doc'">
      <div
        v-if="docMeta && !(embedComponent && docMeta.kind)"
        class="absolute top-1 right-1 z-[6] rounded-(--v-radius-control) bg-(--v-surface-raised) nodrag"
        @pointerdown.stop
        @dblclick.stop
      >
        <VIconButton
          icon="open-in-app"
          size="sm"
          :label="t('canvas.node.openInCortex')"
          @click.stop="openInCortex"
        />
      </div>
      <!-- Structured kinds first: the injected host renderer. -->
      <div v-if="embedComponent && docMeta && docMeta.kind" class="canvas-embed-host nowheel">
        <component :is="embedComponent" :uri="node.ref" />
      </div>
      <!-- Otherwise (no kind): render image / markdown / text ourselves. -->
      <img v-else-if="imgSrc" :src="imgSrc" class="canvas-embed-img" alt="" />
      <!-- eslint-disable-next-line vue/no-v-html -- markdown, sanitised via DOMPurify -->
      <div v-else-if="mdHtml" class="canvas-embed-md" v-html="mdHtml"></div>
      <template v-else>
        <div class="canvas-card-title"><VIcon name="document" class="mr-1 inline-block align-[-3px]" />{{ docMeta?.title || basename(node.ref ?? '') }}</div>
        <div class="canvas-card-sub">
          {{ docError ?? ((docMeta?.kind ? docMeta.kind + ' · ' : '') + basename(node.ref ?? '')) }}
        </div>
      </template>
    </template>

    <template v-else-if="kind === 'link'">
      <div class="canvas-card-header">
        <input
          v-if="editing"
          v-model="draft"
          class="canvas-link-title-input nodrag"
          :placeholder="t('canvas.node.titlePlaceholder')"
          @keydown.enter.prevent="commit"
          @keydown.esc.prevent="cancel"
          @blur="commit"
          @dblclick.stop
        />
        <a
          v-else-if="linkHref"
          class="canvas-card-title canvas-card-link"
          :href="linkHref"
          :target="linkNewTab ? '_blank' : undefined"
          rel="noopener noreferrer"
          @mousedown.stop
          @click.stop="onExternalClick"
        ><VIcon name="link" class="mr-1 inline-block align-[-3px]" />{{ node.title || linkSubtitle }}</a>
        <button
          v-else-if="isVanceLink"
          class="canvas-card-title canvas-card-link"
          type="button"
          @mousedown.stop
          @click.stop="openLinkTarget"
        ><VIcon name="link" class="mr-1 inline-block align-[-3px]" />{{ node.title || linkSubtitle }}</button>
        <div v-else class="canvas-card-title"><VIcon name="link" class="mr-1 inline-block align-[-3px]" />{{ node.title || linkSubtitle || '—' }}</div>
        <VIconButton
          :icon="copied ? 'check' : 'copy'"
          size="sm"
          class="nodrag"
          :label="copied ? t('canvas.node.copied') : t('canvas.node.copyLink')"
          @mousedown.stop
          @click.stop="copyLink"
        />
      </div>
      <div v-if="node.title && !editing" class="canvas-card-sub">{{ linkSubtitle }}</div>
      <div v-if="docError" class="canvas-card-sub canvas-card-error">{{ docError }}</div>
    </template>

    <template v-else-if="kind === 'group'">
      <div class="canvas-group-label">{{ node.label || t('canvas.node.group') }}</div>
    </template>
  </div>
</template>

<style scoped>
.canvas-node {
  position: relative;
  width: 100%;
  box-sizing: border-box;
  line-height: 1.35;
  color: var(--v-label);
}
.canvas-node--selected {
  /* Theme-aware neutral (matches the workpage active-block ring): dark-grey in
     light mode, light-grey in dark mode. */
  outline: 2px solid color-mix(in oklab, var(--color-base-content) 50%, transparent);
  outline-offset: 2px;
  /* box-shadow ring renders reliably regardless of node overflow/clipping,
     so the active node is always clearly visible. */
  box-shadow: 0 0 0 4px color-mix(in oklab, var(--color-base-content) 14%, transparent);
}
/* Recolour VueFlow's default (blue) resize border/handles to black. */
:deep(.vue-flow__resize-control.line) {
  border-color: var(--v-label);
}
:deep(.vue-flow__resize-control.handle) {
  background: var(--v-label);
  border-color: var(--v-label);
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
  background: color-mix(in srgb, var(--v-surface) 80%, transparent);
  font-size: 9px;
  color: var(--v-label-secondary);
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
  border: 1px solid var(--v-border);
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
  background: color-mix(in srgb, var(--v-surface-raised) 90%, transparent);
  border: 1px solid var(--v-border);
  cursor: pointer;
  font-size: 12px;
  line-height: 1;
  color: var(--v-label);
}
.canvas-embed-open:hover {
  background: var(--v-surface);
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
.canvas-card-header {
  display: flex;
  align-items: baseline;
  gap: 4px;
  min-width: 0;
}
.canvas-card-header .canvas-card-title,
.canvas-card-header .canvas-link-title-input {
  flex: 1;
  min-width: 0;
}
.canvas-card-copy-btn {
  flex-shrink: 0;
  width: 20px;
  height: 20px;
  border: 1px solid transparent;
  border-radius: 4px;
  background: transparent;
  cursor: pointer;
  font-size: 12px;
  line-height: 1;
  color: var(--v-label-secondary);
  transition: background 0.15s ease;
}
.canvas-card-copy-btn:hover {
  background: var(--v-fill);
  color: var(--v-label);
  border-color: var(--v-border);
}
.canvas-card-sub {
  margin-top: 2px;
  font-size: 10px;
  color: var(--v-label-secondary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.canvas-card-error {
  color: var(--v-danger);
  white-space: normal;
}
/* Anchor and button forms of the link title must look identical — which of the
   two is rendered depends on the target's scheme, not on anything a reader
   should notice. */
.canvas-card-link {
  display: block;
  width: 100%;
  text-align: left;
  background: none;
  border: 0;
  padding: 0;
  font: inherit;
  color: var(--v-accent-text);
  cursor: pointer;
  text-decoration: none;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.canvas-card-link:hover {
  text-decoration: underline;
}
.canvas-link-title-input {
  width: 100%;
  border: 1px solid var(--v-border);
  border-radius: 3px;
  padding: 1px 4px;
  font-size: 13px;
  font-weight: 600;
  background: var(--v-surface);
  color: var(--v-label);
}

.canvas-node--group {
  border: 2px dashed var(--v-label-tertiary);
  border-radius: 10px;
  background: color-mix(in srgb, var(--v-label-tertiary) 8%, transparent);
  font-size: 12px;
  height: 100%;
}
.canvas-group-label {
  padding: 4px 8px;
  font-weight: 600;
  color: var(--v-label-secondary);
}

/* Connectors */
.cv-handle {
  width: 5px;
  height: 5px;
  min-width: 5px;
  min-height: 5px;
  background: var(--v-label-tertiary);
  border: 1px solid var(--v-surface);
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
  background: var(--v-surface);
  border: 1px solid var(--v-border);
  border-radius: 8px;
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.18);
}
.cv-swatch {
  width: 18px;
  height: 18px;
  border-radius: 50%;
  border: 1px solid var(--v-border);
  cursor: pointer;
  font-size: 10px;
  line-height: 1;
  color: var(--v-label-secondary);
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
  color: var(--v-label);
}
.cv-btn:hover {
  background: var(--v-fill);
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
  background: var(--v-fill);
}
.cv-active {
  outline: 2px solid var(--v-accent);
  outline-offset: 0;
}
.cv-btn.cv-active {
  outline: none;
  background: var(--v-selection);
  border-color: var(--v-accent);
}
.cv-danger:hover {
  background: color-mix(in srgb, var(--v-danger) 12%, transparent);
}
.cv-sep {
  width: 1px;
  height: 18px;
  background: var(--v-separator);
  margin: 0 2px;
}
</style>
