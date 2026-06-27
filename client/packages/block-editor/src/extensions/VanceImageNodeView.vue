<script setup lang="ts">
/**
 * NodeView for the canvas Image — resolves {@code vance:} URIs to a
 * browser-renderable HTTP URL while keeping the canonical {@code src}
 * attribute (and the on-disk Markdown) in the `vance:` form.
 *
 * The actual resolver (URI → HTTP URL) lives in the host (workspace
 * addon / cortex), since only the host knows about REST clients and
 * the current project context. The host passes it in via the Image
 * extension's `resolveImageSrc` option — see `CanvasEditor.vue`.
 *
 * Plain HTTPS or relative URLs pass through unchanged.
 */
import { computed, onMounted, ref, watch } from 'vue';
import { NodeViewWrapper } from '@tiptap/vue-3';
import type { Node as ProseMirrorNode } from '@tiptap/pm/model';

interface ImageExtensionOptions {
  resolveImageSrc?: (vanceUri: string) => Promise<string | null>;
}

const props = defineProps<{
  node: ProseMirrorNode;
  extension: { options: ImageExtensionOptions };
}>();

const src = computed(() => (props.node.attrs?.src as string | null) ?? null);
const alt = computed(() => (props.node.attrs?.alt as string | null) ?? '');
const width = computed(() => (props.node.attrs?.width as string | null) ?? null);

// Cache resolved URLs in a module-level Map so the same image showing
// up multiple times in a doc doesn't hit the resolver each time. The
// cache lives for the page session — document ids don't change across
// saves.
const resolveCache = new Map<string, string | null>();

const resolved = ref<string | null>(null);
const error = ref<string | null>(null);
const loading = ref(false);

async function update() {
  const s = src.value;
  if (!s) { resolved.value = null; return; }
  if (!s.startsWith('vance:')) { resolved.value = s; return; }
  const resolver = props.extension.options.resolveImageSrc;
  if (!resolver) {
    error.value = 'No vance: resolver configured';
    resolved.value = null;
    return;
  }
  if (resolveCache.has(s)) {
    resolved.value = resolveCache.get(s)!;
    error.value = resolved.value == null ? 'Could not resolve image source' : null;
    return;
  }
  loading.value = true;
  error.value = null;
  try {
    const url = await resolver(s);
    resolveCache.set(s, url);
    resolved.value = url;
    if (!url) error.value = 'Could not resolve image source';
  } catch (e) {
    error.value = e instanceof Error ? e.message : 'Image lookup failed';
    resolved.value = null;
  } finally {
    loading.value = false;
  }
}

onMounted(update);
watch(src, update);

const imgClass = computed(() =>
  width.value ? `canvas-image canvas-image--${width.value}` : 'canvas-image',
);
</script>

<template>
  <NodeViewWrapper as="span" class="canvas-image-wrap">
    <img
      v-if="resolved"
      :src="resolved"
      :alt="alt"
      :class="imgClass"
      :data-vance-src="src"
      :data-width="width"
    />
    <span v-else-if="loading" class="canvas-image-placeholder">Loading image…</span>
    <span v-else-if="error" class="canvas-image-placeholder canvas-image-placeholder--error">
      {{ error }} — {{ src }}
    </span>
    <span v-else class="canvas-image-placeholder">No image source</span>
  </NodeViewWrapper>
</template>

<style scoped>
.canvas-image-wrap {
  display: inline-block;
}
.canvas-image-placeholder {
  display: inline-block;
  padding: 0.4em 0.8em;
  background: #f3f4f6;
  border: 1px dashed #d1d5db;
  border-radius: 0.25rem;
  color: #6b7280;
  font-size: 0.85em;
  font-family: monospace;
}
.canvas-image-placeholder--error {
  background: #fef2f2;
  border-color: #fca5a5;
  color: #991b1b;
}
</style>
