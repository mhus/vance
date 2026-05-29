<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import { useI18n } from 'vue-i18n';
import mermaid from 'mermaid';
import panzoom, { type PanZoom } from 'panzoom';
import { parseDiagram, type DiagramDocument } from './diagramCodec';
import { VAlert } from '@components/index';
import type { DocumentDto } from '@vance/generated';
import type { EmbedRef } from '@/kindRenderers/parseVanceUri';

/**
 * Read-only renderer for `kind: diagram` documents. The diagram
 * source is passed verbatim to Mermaid which returns SVG; we mount
 * it into a scoped container. Render errors surface as an alert
 * banner with the original source shown below for debugging.
 *
 * Two modes:
 *   - `editor`   — full editor surface, `doc` prop required.
 *   - `embedded` — compact render from a loaded {@link DocumentDto}.
 *
 * `securityLevel: 'strict'` is non-negotiable: it blocks `<script>`
 * and inline event handlers in the source. LLM-generated diagrams
 * must not be able to execute code via this renderer.
 *
 * Spec: `specification/doc-kind-diagram.md` §5.
 */
defineOptions({ name: 'DiagramView' });

interface Props {
  mode?: 'editor' | 'embedded';
  doc?: DiagramDocument;
  document?: DocumentDto;
  embedRef?: EmbedRef;
}

const props = withDefaults(defineProps<Props>(), { mode: 'editor' });

const { t } = useI18n();

const stageHost = ref<HTMLElement | null>(null);
const rendering = ref(false);
const error = ref<string | null>(null);
const svg = ref<string>('');

/** Live PanZoom instance attached to the rendered SVG. Recreated
 *  whenever the source SVG changes; disposed on unmount. */
let pzInstance: PanZoom | null = null;

/** Per-instance render id — Mermaid uses it to namespace generated
 *  SVG ids. Stable across renders so id-suffix-sensitive consumers
 *  (download, deep-links) don't churn. */
const renderId = `vance-diagram-${Math.random().toString(36).slice(2, 10)}`;

const resolvedDoc = computed<DiagramDocument>(() => {
  if (props.mode === 'editor') {
    return props.doc ?? emptyDoc();
  }
  const d = props.document;
  if (!d || !d.inlineText) return emptyDoc();
  const mime = d.mimeType ?? 'text/markdown';
  try {
    return parseDiagram(d.inlineText, mime);
  } catch (e) {
    console.warn('DiagramView: failed to parse embedded document', e);
    return emptyDoc();
  }
});

function emptyDoc(): DiagramDocument {
  return {
    kind: 'diagram',
    dialect: 'mermaid',
    diagram: { theme: 'default', look: 'classic', extra: {} },
    source: '',
    extra: {},
  };
}

/** Initialise Mermaid once. Theme/look come from the current document
 *  — we re-initialise on every render so a theme change in the doc
 *  takes effect. Re-init is cheap; it just updates the config object. */
function initMermaid(): void {
  const h = resolvedDoc.value.diagram;
  mermaid.initialize({
    startOnLoad: false,
    securityLevel: 'strict',
    theme: h.theme,
    look: h.look,
    fontFamily: h.fontFamily,
  });
}

async function render(): Promise<void> {
  const doc = resolvedDoc.value;
  const dialect = doc.dialect || 'mermaid';

  // v1 only renders Mermaid. Other dialects fall back to the empty
  // state — the parent UI shows a banner explaining the Raw fallback.
  if (dialect !== 'mermaid') {
    svg.value = '';
    error.value = null;
    return;
  }
  if (!doc.source.trim()) {
    svg.value = '';
    error.value = null;
    return;
  }

  rendering.value = true;
  try {
    initMermaid();
    const out = await mermaid.render(renderId, doc.source);
    svg.value = out.svg;
    error.value = null;
  } catch (e) {
    svg.value = '';
    error.value = e instanceof Error ? e.message : String(e);
  } finally {
    rendering.value = false;
  }
}

function applyStage(): void {
  if (stageHost.value) stageHost.value.innerHTML = svg.value;
  attachPanZoom();
}

/**
 * Attach a fresh {@link PanZoom} instance to the rendered SVG. Mermaid
 * gives the SVG a {@code max-width: 100%} attribute which the library
 * needs cleared — otherwise the scaled element overshoots its layout
 * box and clips inside the stage. Initial state is identity transform;
 * the {@link fitToContainer} helper re-runs after a tick so the SVG
 * has settled dimensions before we compute the fit ratio.
 */
function attachPanZoom(): void {
  pzInstance?.dispose();
  pzInstance = null;
  if (!stageHost.value || !svg.value) return;
  const svgEl = stageHost.value.querySelector('svg');
  if (!svgEl) return;
  // Mermaid hard-codes `style="max-width: ..."` on the root <svg>;
  // panzoom's transform fights it. Strip it so zoom > 1 stays visible.
  svgEl.style.maxWidth = 'none';
  svgEl.style.height = 'auto';
  svgEl.style.transformOrigin = '0 0';
  // Cast: panzoom's TS bindings type the argument as HTMLElement, but
  // it works on SVG / SVGGraphicsElement too — the underlying impl
  // uses generic DOM APIs.
  pzInstance = panzoom(svgEl as unknown as HTMLElement, {
    maxZoom: 10,
    minZoom: 0.1,
    bounds: false,
    zoomDoubleClickSpeed: 1, // disable double-click zoom (1 = no animation = effectively off)
    smoothScroll: false,
  });
  // Give Vue a tick so the SVG's natural size is measurable before
  // the initial fit-to-container calculation.
  requestAnimationFrame(() => fitToContainer());
}

/** Reset zoom and centre the SVG inside the stage. Used for the
 *  toolbar reset button and the initial render. */
function fitToContainer(): void {
  if (!pzInstance || !stageHost.value) return;
  const svgEl = stageHost.value.querySelector('svg');
  if (!svgEl) return;
  const stageRect = stageHost.value.getBoundingClientRect();
  // The SVG's intrinsic width/height comes from Mermaid's render —
  // either explicit attributes or computed from viewBox. Use the
  // bounding rect of the SVG element itself as the source of truth.
  const svgRect = svgEl.getBoundingClientRect();
  if (svgRect.width === 0 || svgRect.height === 0) return;
  // Fit to 95% of the stage so there's a small margin around the edges.
  const zoomX = (stageRect.width * 0.95) / svgRect.width;
  const zoomY = (stageRect.height * 0.95) / svgRect.height;
  const zoom = Math.min(zoomX, zoomY, 1); // never auto-upscale beyond 1
  pzInstance.zoomAbs(0, 0, zoom);
  const offsetX = (stageRect.width - svgRect.width * zoom) / 2;
  const offsetY = (stageRect.height - svgRect.height * zoom) / 2;
  pzInstance.moveTo(offsetX, offsetY);
}

function zoomBy(factor: number): void {
  if (!pzInstance || !stageHost.value) return;
  const rect = stageHost.value.getBoundingClientRect();
  // Anchor the zoom on the stage centre — most natural for keyboard /
  // toolbar zoom. Wheel-zoom is handled by panzoom itself with the
  // cursor as the anchor.
  pzInstance.smoothZoom(rect.width / 2, rect.height / 2, factor);
}

/** Download the rendered SVG as a file. Filename derives from the
 *  embedded document's path / title when available, falls back to a
 *  generic name. */
function downloadSvg(): void {
  if (!svg.value) return;
  const blob = new Blob([svg.value], { type: 'image/svg+xml' });
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = filenameForDownload();
  document.body.appendChild(a);
  a.click();
  document.body.removeChild(a);
  URL.revokeObjectURL(url);
}

function filenameForDownload(): string {
  const d = props.document;
  const base = (d?.title || d?.name || 'diagram')
    .replace(/\.[^.]+$/, '')
    .replace(/[^A-Za-z0-9_\-]+/g, '-')
    .replace(/^-+|-+$/g, '');
  return (base || 'diagram') + '.svg';
}

onMounted(() => {
  void render().then(applyStage);
});

watch(() => resolvedDoc.value, () => {
  void render().then(applyStage);
}, { deep: true });

watch(svg, () => {
  applyStage();
});

onBeforeUnmount(() => {
  // Mermaid keeps no per-instance state we need to tear down; the
  // panzoom instance, however, attaches window-level wheel/mouse
  // listeners and must be disposed explicitly.
  pzInstance?.dispose();
  pzInstance = null;
  if (stageHost.value) stageHost.value.innerHTML = '';
});
</script>

<template>
  <div :class="['diagram-view', `diagram-view--${mode}`]">
    <VAlert v-if="error" variant="warning" class="diagram-error">
      <div class="diagram-error-title">
        {{ t('documents.diagramView.renderError') }}
      </div>
      <pre class="diagram-error-message">{{ error }}</pre>
      <details class="diagram-error-source">
        <summary>{{ t('documents.diagramView.sourceLabel') }}</summary>
        <pre>{{ resolvedDoc.source }}</pre>
      </details>
    </VAlert>

    <div
      v-if="!error"
      ref="stageHost"
      class="diagram-stage"
      :aria-busy="rendering"
    />

    <div v-if="!error && !resolvedDoc.source.trim()" class="diagram-empty">
      {{ t('documents.diagramView.empty') }}
    </div>

    <div v-if="!error && resolvedDoc.dialect !== 'mermaid'" class="diagram-empty">
      {{ t('documents.diagramView.unsupportedDialect', { dialect: resolvedDoc.dialect }) }}
    </div>

    <div v-if="mode === 'editor' && svg" class="diagram-toolbar">
      <button
        type="button"
        class="diagram-tool-btn"
        :title="t('documents.diagramView.zoomOut')"
        @click="zoomBy(0.8)"
      >−</button>
      <button
        type="button"
        class="diagram-tool-btn"
        :title="t('documents.diagramView.zoomIn')"
        @click="zoomBy(1.25)"
      >+</button>
      <button
        type="button"
        class="diagram-tool-btn"
        :title="t('documents.diagramView.fitToView')"
        @click="fitToContainer"
      >⤢</button>
      <button
        type="button"
        class="diagram-tool-btn diagram-tool-btn--text"
        :title="t('documents.diagramView.downloadSvg')"
        @click="downloadSvg"
      >↓ SVG</button>
    </div>
  </div>
</template>

<style scoped>
.diagram-view {
  position: relative;
  width: 100%;
  background: hsl(var(--b1));
  border: 1px solid hsl(var(--bc) / 0.15);
  border-radius: 0.5rem;
  outline: none;
  overflow: hidden;
  display: flex;
  flex-direction: column;
}
.diagram-view--editor {
  min-height: 420px;
  height: 65vh;
}
.diagram-view--embedded {
  min-height: 12rem;
  height: 22rem;
  border: none;
  background: transparent;
}

.diagram-stage {
  flex: 1;
  min-height: 0;
  overflow: hidden;
  position: relative;
  cursor: grab;
  /* panzoom moves the SVG via transform; the stage is the clipped
     viewport. No flex centering — fitToContainer() handles the
     initial position so we don't fight the library's transform. */
}
.diagram-stage:active { cursor: grabbing; }
.diagram-stage :deep(svg) {
  /* panzoom needs the natural SVG dimensions to compute zoom. The
     attachPanZoom() helper clears max-width inline; the rule below
     is the layer-zero fallback in case the inline style is dropped
     by a future Mermaid version that uses CSS instead. */
  max-width: none;
  height: auto;
  transform-origin: 0 0;
}

.diagram-empty {
  position: absolute;
  inset: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 0.875rem;
  opacity: 0.6;
  pointer-events: none;
  padding: 1rem;
  text-align: center;
}

.diagram-error {
  margin: 0.75rem;
}
.diagram-error-title {
  font-weight: 600;
  margin-bottom: 0.25rem;
}
.diagram-error-message {
  font-family: ui-monospace, SFMono-Regular, "Cascadia Mono", monospace;
  font-size: 0.8125rem;
  white-space: pre-wrap;
  margin: 0;
  padding: 0.5rem;
  background: hsl(var(--b2));
  border-radius: 0.25rem;
}
.diagram-error-source {
  margin-top: 0.5rem;
  font-size: 0.8125rem;
}
.diagram-error-source pre {
  margin: 0.5rem 0 0;
  padding: 0.5rem;
  background: hsl(var(--b2));
  border-radius: 0.25rem;
  font-family: ui-monospace, SFMono-Regular, "Cascadia Mono", monospace;
  font-size: 0.8125rem;
  overflow: auto;
  white-space: pre-wrap;
}

.diagram-toolbar {
  position: absolute;
  bottom: 0.5rem;
  right: 0.75rem;
  display: flex;
  gap: 0.35rem;
  z-index: 2;
}
.diagram-tool-btn {
  min-width: 2rem;
  height: 2rem;
  border: 1px solid oklch(var(--bc) / 0.2);
  background: oklch(var(--b1) / 0.9);
  backdrop-filter: blur(4px);
  border-radius: 0.375rem;
  padding: 0 0.5rem;
  font-size: 0.95rem;
  line-height: 1;
  cursor: pointer;
  color: oklch(var(--bc));
  display: inline-flex;
  align-items: center;
  justify-content: center;
}
.diagram-tool-btn:hover {
  background: oklch(var(--b2));
  border-color: oklch(var(--bc) / 0.35);
}
.diagram-tool-btn--text {
  font-size: 0.8125rem;
  padding: 0 0.75rem;
}
</style>
