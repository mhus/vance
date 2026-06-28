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
 * v1 = generic card. Inline rendering of mindmap / tree / chart /
 * calendar / … is v2 and will plug into the kind-renderer registry.
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
  canvas: '📄', markdown: '📝', text: '📝',
  yaml: '⚙️', json: '⚙️', data: '⚙️',
  mindmap: '🧠', tree: '🌳', list: '•', items: '•',
  checklist: '☑', records: '▤', graph: '🕸', chart: '📊',
  map: '🗺', calendar: '📅', slides: '📽️',
  // Code / script kinds
  code: '⌨', script: '⌨',
  // Media — shown as warning placeholder, but icon stays informative
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
  // Also remount the host renderer so it re-fetches the underlying
  // document. Without this the meta-card refreshes but the embedded
  // mindmap / tree / chart would still show the cached content.
  renderEpoch.value += 1;
}

function openEmbed(e: MouseEvent) {
  if (!meta.value) return;
  e.preventDefault();
  // Dispatch a custom event the host can listen to (same pattern as
  // the asset-picker bridge); the host knows how to map an embed
  // open to its router. Falls through to a window.open(vance:URI)
  // which won't actually navigate but at least surfaces in devtools.
  const detail = { uri: uri.value, openInNewTab: e.ctrlKey || e.metaKey || true };
  const evt = new CustomEvent('vance:open-embed', { detail, bubbles: true });
  (e.currentTarget as HTMLElement).dispatchEvent(evt);
}

onMounted(resolve);
watch(uri, resolve);
</script>

<template>
  <NodeViewWrapper as="aside" class="vance-embed" :data-uri="uri">
    <div v-if="isBlocked" class="vance-embed__media-warning">
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
    <!-- Full kind-aware renderer (vance-face's EmbeddedKindBox wrapped
         via VanceEmbedView). Mounted with the URI; key=renderEpoch
         forces a remount on ↻. The card's chrome (icon + actions)
         floats on top so refresh + open work the same as in the
         card-only fallback. -->
    <div v-else-if="hostComponent" class="vance-embed__hosted">
      <component
        :is="hostComponent"
        :key="renderEpoch"
        :uri="uri"
        contenteditable="false"
      />
      <div class="vance-embed__hosted-actions">
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
    <div v-else class="vance-embed__card">
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
  user-select: none;
}
/* Hosted variant — the kind-renderer component renders inside this
   block. The floating action buttons (refresh + open) sit at the
   top-right corner and only show on hover so they don't fight the
   content for visual real-estate. */
.vance-embed__hosted {
  position: relative;
}
.vance-embed__hosted-actions {
  position: absolute;
  top: 0.4rem;
  right: 0.4rem;
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
  border: 1px solid var(--color-border, #e5e7eb);
  border-radius: 0.5rem;
  background: var(--color-button-bg, #fafafa);
  position: relative;
  transition: border-color 0.15s ease, background 0.15s ease;
  cursor: default;
}
.vance-embed__card:hover {
  border-color: var(--color-link, #3b82f6);
}
.vance-embed__media-warning {
  background: #fef9c3;
  border-color: #fcd34d;
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
  background: var(--color-border, #e5e7eb);
  border-radius: 999px;
  color: var(--color-text, #111827);
}
.vance-embed__path {
  font-family: monospace;
  font-size: 0.75rem;
  color: var(--color-text-muted, #6b7280);
  margin-top: 0.2em;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.vance-embed__error {
  color: #b91c1c;
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
  background: var(--color-bg, #fff);
  border: 1px solid var(--color-border, #e5e7eb);
  border-radius: 0.25rem;
  cursor: pointer;
  width: 1.8rem;
  height: 1.8rem;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 0.95rem;
  color: var(--color-text-muted, #6b7280);
  padding: 0;
}
.vance-embed__action:hover {
  background: var(--color-button-bg, #f3f4f6);
  color: var(--color-text, #111827);
}
</style>
