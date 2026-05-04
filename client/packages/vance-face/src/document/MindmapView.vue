<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref, watch } from 'vue';
import { useI18n } from 'vue-i18n';
import { Transformer } from 'markmap-lib';
import { Markmap } from 'markmap-view';
import type { TreeDocument } from './treeItemsCodec';
import { treeToMarkmapMarkdown } from './mindmapAdapter';

/**
 * Read-only mindmap renderer for `kind: mindmap` documents. Reuses
 * the parsed {@link TreeDocument} from the same codec the tree
 * editor uses; we just project it into markmap-flavoured markdown
 * and hand it to {@code markmap-view}.
 *
 * Edit happens in the sibling {@code <TreeView>} tab — the spec
 * (`specification/doc-kind-mindmap.md` §5) keeps the v1 mindmap as
 * a viewer to ship the renderer without the in-place-editing
 * complexity that {@code mind-elixir} would unlock later.
 */
defineOptions({ name: 'MindmapView' });

const props = defineProps<{ doc: TreeDocument }>();

const { t } = useI18n();

const svgRef = ref<SVGSVGElement | null>(null);
let markmap: Markmap | null = null;
const transformer = new Transformer();

function render(): void {
  if (!svgRef.value) return;
  const md = treeToMarkmapMarkdown(props.doc);
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

// Re-render when the parent re-emits the doc (e.g. raw-tab edits).
// `deep: true` so per-item text/extra changes propagate even when
// the outer object identity stays the same.
watch(() => props.doc, () => render(), { deep: true });

onBeforeUnmount(() => {
  if (markmap) {
    markmap.destroy();
    markmap = null;
  }
});
</script>

<template>
  <div class="mindmap-view">
    <svg ref="svgRef" class="mindmap-svg" />
    <p class="mindmap-hint">
      {{ t('documents.mindmapView.panZoomHint') }}
    </p>
  </div>
</template>

<style scoped>
.mindmap-view {
  position: relative;
  width: 100%;
  height: 65vh;
  min-height: 420px;
  background: hsl(var(--b1));
  border: 1px solid hsl(var(--bc) / 0.15);
  border-radius: 0.5rem;
  overflow: hidden;
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
