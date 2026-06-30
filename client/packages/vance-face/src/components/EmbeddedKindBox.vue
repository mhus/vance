<script setup lang="ts">
/**
 * Wrapper around {@link KindBox} for the embedded channel —
 * Markdown links / images with {@code vance:} URI.
 *
 * Default actions (spec §4 + §11.6):
 *   - Copy (primary) — Document source-content to clipboard
 *   - Open (primary) — navigate to the Document editor
 *   - Download (secondary) — original-file blob
 *
 * Renderer comes from {@link kindRegistry}, keyed on the effective
 * kind (kindHint first-paint, then verified against the loaded
 * Document's metadata).
 */
import { computed, inject, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import KindBox from './KindBox.vue';
import { kindIcon, kindLabel, resolveRenderer } from '@/kindRenderers/registry';
import { useDocumentRefStore } from '@/document/documentRefStore';
import type { EmbedRef } from '@/kindRenderers/parseVanceUri';
import { onDocumentChanged } from '@/ws/wsConnectionStore';
import { VANCE_LINK_HANDLER_KEY } from './vanceLinkHandler';

interface Props {
  embedRef: EmbedRef;
}

const props = defineProps<Props>();

const store = useDocumentRefStore();
const doc = ref<Awaited<ReturnType<typeof store.resolve>> | null>(null);
const loadError = ref<string | null>(null);
// Same interception protocol as the inline-anchor path in MarkdownView:
// a host (Cortex) can provide a handler to take ownership of plain
// "Open" clicks and render the document in-place instead of letting us
// jump to documents.html.
const vanceLinkHandler = inject(VANCE_LINK_HANDLER_KEY, null);

/**
 * Media kinds where the link/image source path (or explicit `?kind=`
 * hint) is authoritative over a generic `doc.kind` from the resolver.
 * Rationale: `![alt](foo.png)` is a user-visible commitment to
 * render foo.png as an image; if the document store classified the
 * underlying file as a plain `document`, we still want the inline
 * preview rather than a generic doc card. The spec's "doc.kind wins"
 * rule (§3.3) targets *structured* kinds (mindmap, sheet, …) where
 * the document carries domain semantics the hint can't predict — for
 * raw media the file extension is the ground truth.
 */
const MEDIA_KIND_HINTS = new Set(['image', 'svg', 'audio', 'video', 'pdf']);

const effectiveKind = computed<string>(() => {
  const hint = props.embedRef.kindHint;
  if (hint && MEDIA_KIND_HINTS.has(hint)) return hint;
  // Loaded doc kind wins over hint (§3.3 conflict-resolution).
  return (doc.value?.kind ?? hint ?? 'document').toLowerCase();
});

const renderer = computed(() => resolveRenderer(effectiveKind.value, 'embedded'));
const label = computed(() => kindLabel(effectiveKind.value));
const icon = computed(() => kindIcon(effectiveKind.value));
const title = computed(() => doc.value?.title ?? props.embedRef.text ?? props.embedRef.path);

async function load() {
  loadError.value = null;
  try {
    doc.value = await store.resolve(props.embedRef);
  } catch (e) {
    loadError.value = (e as Error).message;
  }
}

/**
 * Auto-refresh: when a {@code documents.changed} frame arrives for this
 * embed's path (delivered whenever any subscription — e.g. a folder-bound
 * app's prefix subscription — covers it), drop the cached snapshot and
 * re-resolve so the rendered content reflects the new file. Without the
 * cache-invalidate the store would hand back the stale snapshot. This is
 * what makes a workspace form's onSave recompute show up live in an
 * embedded diagram.
 */
function reloadFresh() {
  const project = props.embedRef.project ?? store.currentProject;
  if (project) store.invalidate(project, props.embedRef.path);
  void load();
}

let unsubscribeChanged: (() => void) | null = null;
function subscribeChanged(path: string) {
  unsubscribeChanged?.();
  unsubscribeChanged = onDocumentChanged(path, reloadFresh);
}

onMounted(() => {
  subscribeChanged(props.embedRef.path);
  void load();
});
watch(() => props.embedRef.path, (path) => {
  subscribeChanged(path);
  reloadFresh();
});
onBeforeUnmount(() => unsubscribeChanged?.());

function onCopy(): void {
  if (typeof navigator === 'undefined' || !navigator.clipboard) return;
  if (doc.value?.inlineText != null) {
    void navigator.clipboard.writeText(doc.value.inlineText);
  } else {
    // Fall back to URI when content isn't loaded yet / is binary.
    void navigator.clipboard.writeText(props.embedRef.raw);
  }
}

async function onOpen(event?: MouseEvent): Promise<void> {
  // Mirror the inline-link path in MarkdownView: deep-link into the
  // documents editor via the resolved document id. Without an id
  // (resolve failed) we can't navigate — keep the user where they are.
  const documentId = doc.value?.id;
  if (!documentId) return;
  const projectId = props.embedRef.project ?? doc.value?.projectId;
  if (!projectId) return;
  const newTab = !!event && (event.metaKey || event.ctrlKey || event.shiftKey);
  // Plain click: give an injected host (Cortex) a chance to open the
  // document in-place. Modifier-click bypasses the handler so the user
  // can always escape into a real browser tab.
  if (vanceLinkHandler && !newTab) {
    try {
      const handled = await vanceLinkHandler({
        documentId,
        projectId,
        embedRef: props.embedRef,
        newTab,
      });
      if (handled) return;
    } catch (e) {
      console.warn('EmbeddedKindBox: vance link handler threw', e);
      // Fall through to default navigation rather than swallow.
    }
  }
  const url = `/cortex.html?project=${encodeURIComponent(projectId)}`
    + `&doc=${encodeURIComponent(documentId)}`;
  if (newTab) {
    window.open(url, '_blank', 'noopener');
  } else {
    window.location.href = url;
  }
}

function onDownload(): void {
  // Without a content-bearing REST endpoint surfaced via the store
  // we fall back to opening the document editor where the user can
  // download from the raw tab.
  onOpen();
}
</script>

<template>
  <KindBox
    :kind="effectiveKind"
    :label="label"
    :icon="icon"
    :title="title"
  >
    <template #actions>
      <button class="kbx-act" :title="$t?.('chat.kindBox.copy') ?? 'Copy'" @click="onCopy">⧉</button>
      <button class="kbx-act" :title="$t?.('chat.kindBox.open') ?? 'Open'" @click="(e: MouseEvent) => onOpen(e)">↗</button>
      <button class="kbx-act" :title="$t?.('chat.kindBox.download') ?? 'Download'" @click="onDownload">↓</button>
    </template>

    <div v-if="loadError" class="kbx-error">
      {{ loadError }}
    </div>
    <div v-else-if="!doc" class="kbx-loading">
      <span class="kbx-skeleton" />
    </div>
    <component
      v-else-if="renderer"
      :is="renderer.embedded"
      mode="embedded"
      :document="doc"
      :embed-ref="embedRef"
    />
    <div v-else class="kbx-fallback">
      <span class="opacity-70">{{ embedRef.path }}</span>
    </div>
  </KindBox>
</template>

<style scoped>
.kbx-act {
  border: none;
  background: transparent;
  cursor: pointer;
  font-size: 0.8rem;
  padding: 0.15rem 0.4rem;
  border-radius: 0.25rem;
  opacity: 0.7;
}
.kbx-act:hover {
  background: hsl(var(--bc) / 0.1);
  opacity: 1;
}
.kbx-loading {
  display: flex;
  justify-content: center;
  padding: 1.5rem 0;
}
.kbx-skeleton {
  width: 100%;
  height: 4rem;
  background: linear-gradient(
    90deg,
    hsl(var(--bc) / 0.05) 25%,
    hsl(var(--bc) / 0.12) 50%,
    hsl(var(--bc) / 0.05) 75%
  );
  background-size: 200% 100%;
  animation: kbx-shimmer 1.4s infinite;
  border-radius: 0.25rem;
}
@keyframes kbx-shimmer {
  0% { background-position: 200% 0; }
  100% { background-position: -200% 0; }
}
.kbx-error {
  color: hsl(var(--er));
  padding: 0.5rem;
  font-size: 0.85rem;
}
.kbx-fallback {
  padding: 0.5rem;
  font-size: 0.9rem;
}
</style>
