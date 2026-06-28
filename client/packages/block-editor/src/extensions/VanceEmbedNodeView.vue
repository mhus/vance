<script setup lang="ts">
/**
 * NodeView for {@code ```vance-embed} blocks — kind-aware preview
 * card. Resolves the {@code vance:} URI via the host-provided
 * {@code resolveDocumentMeta} option, picks an icon based on the
 * document kind, and offers Refresh + Open actions on hover.
 *
 * Refresh exists because embeds reference documents that live
 * outside the editor's live-WS subscription — when the embedded
 * doc changes, the editor doesn't know. Hover-Refresh lets the
 * user pull a fresh snapshot manually.
 *
 * The inner content wrappers carry an explicit
 * {@code contenteditable="true"} attribute. ProseMirror sets
 * {@code contenteditable="false"} on the NodeViewWrapper automatically
 * (because the node has no editable PM children), which would block
 * native text-selection inside the hosted renderer. Re-asserting
 * {@code true} on the inner DOM hands selection control back to the
 * browser without breaking PM's view-level invariants.
 */
import { computed, onMounted, ref, watch } from 'vue';
import { NodeViewWrapper } from '@tiptap/vue-3';
import type { Node as ProseMirrorNode } from '@tiptap/pm/model';

interface EmbedDocMeta {
  id: string;
  path: string;
  title: string | null;
  kind: string | null;
  mimeType: string | null;
}

interface ExtensionOptions {
  resolveDocumentMeta?: ((uri: string) => Promise<EmbedDocMeta | null>) | null;
  embedComponent?: (() => import('vue').Component | null) | null;
}

const props = defineProps<{
  node: ProseMirrorNode;
  extension: { options: ExtensionOptions };
}>();

const uri = computed(() => (props.node.attrs?.uri as string | null) ?? '');
const meta = ref<EmbedDocMeta | null>(null);
const loading = ref(false);
const error = ref<string | null>(null);

// Host-supplied full kind-aware renderer (from vance-face). When
// present we mount that component and wrap it with the same hover
// actions (refresh + open) so the card-only fallback and the full
// renderer share the same chrome.
const hostComponent = computed(() => props.extension.options.embedComponent?.() ?? null);

// Increment to force a refresh: bound as :key on the host component
// so it remounts (= re-fetches its document) when the user clicks ↻.
const renderEpoch = ref(0);

// Kinds that are intentionally NOT embeddable. Media (image / svg /
// pdf / audio / video) belong in a link card, not an inline embed.
// `application` is a *folder container*, not content — embedding it
// would mean rendering a whole app inside another, which doesn't have
// a meaningful semantics.
const EMBED_BLOCKED_KINDS = new Set([
  'image', 'svg', 'pdf', 'audio', 'video',
  'application',
]);
const isBlocked = computed(
  () => !!meta.value?.kind && EMBED_BLOCKED_KINDS.has(meta.value.kind),
);

const KIND_ICONS: Record<string, string> = {
  canvas: '📄', workpage: '📄', markdown: '📝', text: '📝',
  yaml: '⚙️', json: '⚙️', data: '⚙️',
  mindmap: '🧠', tree: '🌳', list: '•', items: '•',
  checklist: '☑', records: '▤', graph: '🕸', chart: '📊',
  map: '🗺', calendar: '📅', slides: '📽️',
  code: '⌨', script: '⌨',
  image: '🖼', svg: '🖼', pdf: '📄',
  audio: '🔊', video: '🎬',
};
const icon = computed(() => {
  if (loading.value) return '⏳';
  if (error.value || !meta.value) return '❓';
  return KIND_ICONS[(meta.value.kind ?? '').toLowerCase()] ?? '📄';
});
const title = computed(() => meta.value?.title ?? meta.value?.path ?? uri.value);
const subtitle = computed(() => meta.value?.path ?? '');

async function resolve() {
  const u = uri.value;
  if (!u) { meta.value = null; return; }
  const resolver = props.extension.options.resolveDocumentMeta;
  if (!resolver) {
    error.value = 'No embed resolver configured';
    return;
  }
  loading.value = true;
  error.value = null;
  try {
    meta.value = await resolver(u);
    if (!meta.value) error.value = 'Document not found';
  } catch (e) {
    error.value = e instanceof Error ? e.message : 'Lookup failed';
    meta.value = null;
  } finally {
    loading.value = false;
  }
}

function refresh() {
  void resolve();
  renderEpoch.value += 1;
}

function openEmbed(e: MouseEvent) {
  if (!meta.value) return;
  e.preventDefault();
  const detail = { uri: uri.value, openInNewTab: e.ctrlKey || e.metaKey || true };
  const evt = new CustomEvent('vance:open-embed', { detail, bubbles: true });
  (e.currentTarget as HTMLElement).dispatchEvent(evt);
}

onMounted(resolve);
watch(uri, resolve);
</script>

<template>
  <NodeViewWrapper as="aside" class="vance-embed" :data-uri="uri">
    <div
      v-if="isBlocked"
      class="vance-embed__media-warning"
      contenteditable="true"
    >
      <span class="vance-embed__icon">{{ icon }}</span>
      <div class="vance-embed__body">
        <div class="vance-embed__title">
          {{ meta?.kind === 'application'
            ? "Applications can't be embedded — link to a page inside instead"
            : "Media kinds aren't embedded inline — use a link instead" }}
        </div>
        <div class="vance-embed__path">{{ uri }}</div>
      </div>
    </div>
    <div
      v-else-if="hostComponent"
      class="vance-embed__hosted"
      contenteditable="true"
    >
      <component
        :is="hostComponent"
        :key="renderEpoch"
        :uri="uri"
      />
      <div class="vance-embed__hosted-actions">
        <!-- Only Refresh here. The Open/Copy/Download buttons come
             from the kind-renderer's own chrome (EmbeddedKindBox in
             vance-face), embedding our own would just duplicate them. -->
        <button
          class="vance-embed__action"
          type="button"
          title="Refresh — embedded documents don't auto-update"
          @click.stop="refresh"
        >↻</button>
      </div>
    </div>
    <div v-else class="vance-embed__card" contenteditable="true">
      <span class="vance-embed__icon">{{ icon }}</span>
      <div class="vance-embed__body">
        <div class="vance-embed__title">{{ title }}</div>
        <div v-if="meta?.kind" class="vance-embed__kind">{{ meta.kind }}</div>
        <div v-if="subtitle && subtitle !== title" class="vance-embed__path">{{ subtitle }}</div>
        <div v-if="error" class="vance-embed__error">{{ error }}</div>
      </div>
      <div class="vance-embed__actions">
        <button
          class="vance-embed__action"
          type="button"
          title="Refresh — embedded documents don't auto-update"
          @click.stop="refresh"
        >↻</button>
        <button
          class="vance-embed__action"
          type="button"
          title="Open document (or ⌘/Ctrl+click)"
          @click="openEmbed"
        >↗</button>
      </div>
    </div>
  </NodeViewWrapper>
</template>

<style scoped>
.vance-embed {
  margin: 0.75em 0;
}
.vance-embed__hosted {
  position: relative;
}
.vance-embed__hosted-actions {
  position: absolute;
  /* Top-right corner, pushed slightly above the box. Far enough from
     the kind-renderer's own action row (copy/open/download) so the
     two strips don't overlap visually. */
  /* Half a button-height (0.9rem) further down — aligns the refresh
     button roughly with the kind-renderer's own action row. */
  top: 0.3rem;
  /* One button-width (1.8rem) further right so the refresh button
     sits past the kind-renderer's action row instead of above it. */
  right: -1.2rem;
  display: flex;
  gap: 0.25rem;
  opacity: 0;
  transition: opacity 0.15s ease;
  z-index: 2;
}
.vance-embed__hosted:hover .vance-embed__hosted-actions {
  opacity: 1;
}

.vance-embed__card,
.vance-embed__media-warning {
  display: flex;
  align-items: flex-start;
  gap: 0.75rem;
  padding: 0.75rem 1rem;
  border: 1px solid oklch(var(--bc) / 0.18);
  border-radius: 0.5rem;
  background: oklch(var(--bc) / 0.06);
  position: relative;
  transition: border-color 0.15s ease, background 0.15s ease;
  cursor: default;
}
.vance-embed__card:hover {
  border-color: oklch(var(--p));
}
.vance-embed__media-warning {
  background: oklch(var(--wa) / 0.15);
  border-color: oklch(var(--wa) / 0.5);
}
.vance-embed__icon {
  font-size: 1.5em;
  line-height: 1.2;
  flex-shrink: 0;
}
.vance-embed__body {
  flex: 1;
  min-width: 0;
}
.vance-embed__title {
  font-weight: 600;
  font-size: 0.95rem;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.vance-embed__kind {
  display: inline-block;
  font-family: monospace;
  font-size: 0.7rem;
  padding: 0 0.35em;
  margin-top: 0.2em;
  background: oklch(var(--bc) / 0.18);
  border-radius: 999px;
  color: oklch(var(--bc));
}
.vance-embed__path {
  font-family: monospace;
  font-size: 0.75rem;
  color: oklch(var(--bc) / 0.65);
  margin-top: 0.2em;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.vance-embed__error {
  color: oklch(var(--er));
  font-size: 0.8rem;
  margin-top: 0.25rem;
}
.vance-embed__actions {
  display: flex;
  gap: 0.25rem;
  opacity: 0;
  transition: opacity 0.15s ease;
}
.vance-embed__card:hover .vance-embed__actions {
  opacity: 1;
}
.vance-embed__action {
  background: oklch(var(--b1));
  border: 1px solid oklch(var(--bc) / 0.18);
  border-radius: 0.25rem;
  cursor: pointer;
  width: 1.8rem;
  height: 1.8rem;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 0.95rem;
  color: oklch(var(--bc) / 0.65);
  padding: 0;
}
.vance-embed__action:hover {
  background: oklch(var(--bc) / 0.06);
  color: oklch(var(--bc));
}
</style>
