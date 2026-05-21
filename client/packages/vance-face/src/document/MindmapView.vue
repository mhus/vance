<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import { useI18n } from 'vue-i18n';
import { Transformer } from 'markmap-lib';
import { Markmap } from 'markmap-view';
import { parseTree, type TreeDocument } from './treeItemsCodec';
import { treeToMarkmapMarkdown } from './mindmapAdapter';
import type { DocumentDto } from '@vance/generated';
import type { EmbedRef } from '@/kindRenderers/parseVanceUri';
import type { FenceMeta } from '@/kindRenderers/parseFenceLang';

/**
 * Read-only mindmap renderer for `kind: mindmap` documents. Reuses
 * the parsed {@link TreeDocument} from the same codec the tree
 * editor uses; we just project it into markmap-flavoured markdown
 * and hand it to {@code markmap-view}.
 *
 * Three modes (spec §11.2):
 *   - `editor`  — full editor surface, `doc` prop required.
 *   - `inline`  — compact render from a fence body in `content`.
 *   - `embedded`— compact render from a loaded Document in `document`.
 *
 * Edit happens in the sibling {@code <TreeView>} tab — the spec
 * (`specification/doc-kind-mindmap.md` §5) keeps the v1 mindmap as
 * a viewer to ship the renderer without the in-place-editing
 * complexity that {@code mind-elixir} would unlock later.
 */
defineOptions({ name: 'MindmapView' });

interface Props {
  /** Mode-switch: editor (default), inline, or embedded. */
  mode?: 'editor' | 'inline' | 'embedded';
  /** Parsed TreeDocument — required in editor mode. */
  doc?: TreeDocument;
  /** Raw fence-body — required in inline mode. */
  content?: string;
  /** Fence-meta from the parser — inline mode only. */
  meta?: FenceMeta;
  /** Loaded Document — required in embedded mode. */
  document?: DocumentDto;
  /** Reference info (path, project, kindHint) — embedded mode. */
  embedRef?: EmbedRef;
}

const props = withDefaults(defineProps<Props>(), {
  mode: 'editor',
  meta: () => ({}),
});

const { t } = useI18n();

const svgRef = ref<SVGSVGElement | null>(null);
let markmap: Markmap | null = null;
const transformer = new Transformer();

/**
 * Resolve the {@link TreeDocument} to render across all three modes.
 * Throws inline (returns empty doc with a warning) when prerequisites
 * for the selected mode are missing — we never crash the chat
 * because a fence was malformed.
 */
const resolvedDoc = computed<TreeDocument>(() => {
  if (props.mode === 'editor') {
    return props.doc ?? emptyDoc();
  }
  if (props.mode === 'inline') {
    try {
      return parseTree(props.content ?? '', 'text/markdown');
    } catch (e) {
      console.warn('MindmapView: failed to parse inline content', e);
      return emptyDoc();
    }
  }
  // embedded
  const d = props.document;
  if (!d || !d.inlineText) return emptyDoc();
  const mime = d.mimeType ?? 'text/markdown';
  try {
    return parseTree(d.inlineText, mime);
  } catch (e) {
    console.warn('MindmapView: failed to parse embedded document', e);
    return emptyDoc();
  }
});

function emptyDoc(): TreeDocument {
  return { kind: 'mindmap', items: [], extra: {} };
}

function render(): void {
  if (!svgRef.value) return;
  const md = treeToMarkmapMarkdown(resolvedDoc.value);
  const { root } = transformer.transform(md);
  if (!markmap) {
    markmap = Markmap.create(svgRef.value, undefined, root);
    return;
  }
  void markmap.setData(root);
  void markmap.fit();
}

onMounted(() => {
  render();
});

// Re-render when the source changes (deep across all three modes).
watch(
  () => [resolvedDoc.value, props.mode] as const,
  () => render(),
  { deep: true },
);

onBeforeUnmount(() => {
  if (markmap) {
    markmap.destroy();
    markmap = null;
  }
});
</script>

<template>
  <div :class="['mindmap-view', `mindmap-view--${mode}`]">
    <svg ref="svgRef" class="mindmap-svg" />
    <p v-if="mode === 'editor'" class="mindmap-hint">
      {{ t('documents.mindmapView.panZoomHint') }}
    </p>
  </div>
</template>

<style scoped>
.mindmap-view {
  position: relative;
  width: 100%;
  background: hsl(var(--b1));
  border: 1px solid hsl(var(--bc) / 0.15);
  border-radius: 0.5rem;
  overflow: hidden;
}
.mindmap-view--editor {
  height: 65vh;
  min-height: 420px;
}
.mindmap-view--inline,
.mindmap-view--embedded {
  height: 22rem;
  min-height: 16rem;
  border: none;
  background: transparent;
}
.mindmap-svg {
  width: 100%;
  height: 100%;
  display: block;
}
.mindmap-hint {
  position: absolute;
  bottom: 0.5rem;
  right: 0.75rem;
  font-size: 0.7rem;
  opacity: 0.55;
  pointer-events: none;
  user-select: none;
}
</style>
