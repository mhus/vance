<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import { useI18n } from 'vue-i18n';
import mermaid from 'mermaid';
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
  // Mermaid keeps no per-instance state we need to tear down; we just
  // drop the SVG from the host so a re-mount starts clean.
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
        class="diagram-download"
        :title="t('documents.diagramView.downloadSvg')"
        @click="downloadSvg"
      >
        ↓ SVG
      </button>
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
  overflow: auto;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 1rem;
}
.diagram-stage :deep(svg) {
  max-width: 100%;
  height: auto;
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
  gap: 0.5rem;
}
.diagram-download {
  border: 1px solid hsl(var(--bc) / 0.2);
  background: hsl(var(--b1) / 0.85);
  backdrop-filter: blur(4px);
  border-radius: 0.375rem;
  padding: 0.25rem 0.75rem;
  font-size: 0.8125rem;
  cursor: pointer;
  color: hsl(var(--bc));
}
.diagram-download:hover {
  background: hsl(var(--b2));
}
</style>
