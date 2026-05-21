<script setup lang="ts">
/**
 * Wrapper around {@link KindBox} for the inline channel — fenced
 * code blocks emitted directly in the message text.
 *
 * Default actions (spec §4 + §11.6):
 *   - Download — save the fence body as a file
 *   - Raw (toggle) — switch between renderer output and source view
 *   - Copy — fence body to clipboard
 *   - Save as Document — promote-pfad §9: persist via POST /documents,
 *     return the {@code vance:} link (copied to clipboard) so the user
 *     can reference the now-persistent artifact.
 *
 * Renderer comes from {@link kindRegistry}; on unknown kind we fall
 * back to a plain {@code <pre>} body with the same action set.
 */
import { computed, ref } from 'vue';
import { brainFetch } from '@vance/shared';
import type { DocumentCreateRequest, DocumentDto } from '@vance/generated';
import KindBox from './KindBox.vue';
import { kindIcon, kindLabel, resolveRenderer } from '@/kindRenderers/registry';
import type { FenceMeta } from '@/kindRenderers/parseFenceLang';
import { useDocumentRefStore } from '@/document/documentRefStore';

interface Props {
  kind: string;
  /** Fence-body content. */
  content: string;
  /** Parsed fence meta (key=value pairs). */
  meta?: FenceMeta;
}

const props = withDefaults(defineProps<Props>(), { meta: () => ({}) });

const renderer = computed(() => resolveRenderer(props.kind, 'inline'));
const showRaw = ref(false);
const status = ref<{ kind: 'ok' | 'err'; text: string } | null>(null);
const saving = ref(false);

const label = computed(() => kindLabel(props.kind));
const icon = computed(() => kindIcon(props.kind));

const documentRefStore = useDocumentRefStore();

function onCopy(): void {
  if (typeof navigator === 'undefined' || !navigator.clipboard) return;
  void navigator.clipboard.writeText(props.content);
  flashStatus('ok', 'Inhalt kopiert');
}

function onDownload(): void {
  const ext = extForKind(props.kind);
  const ts = timestampSlug();
  const filename = `${props.kind}-${ts}.${ext}`;
  const blob = new Blob([props.content], { type: mimeForKind(props.kind) });
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = filename;
  document.body.appendChild(a);
  a.click();
  document.body.removeChild(a);
  URL.revokeObjectURL(url);
}

function toggleRaw(): void {
  showRaw.value = !showRaw.value;
}

/**
 * Save-as-Document — promote-pfad. Prompts the user for a path
 * (with a sensible default), POSTs to the documents endpoint, builds
 * the canonical {@code vance:} link client-side, copies the link to
 * the clipboard and surfaces a one-line success badge inside the
 * KindBox. On error: an inline error message; the inline block keeps
 * working unchanged.
 */
async function onSaveAsDocument(): Promise<void> {
  if (saving.value) return;
  const projectId = documentRefStore.currentProject;
  if (!projectId) {
    flashStatus('err', 'Kein aktuelles Project — bind erst eine Chat-Session.');
    return;
  }
  const defaultPath = `documents/${props.kind}-${timestampSlug()}.${extForKind(props.kind)}`;
  // window.prompt is intentionally minimal v1 — a proper Modal can
  // come later. The user can rename the document afterwards in the
  // document editor anyway.
  const userPath = window.prompt('Pfad für das neue Document:', defaultPath);
  if (userPath === null) return; // cancelled
  const path = userPath.trim() || defaultPath;

  saving.value = true;
  status.value = null;
  try {
    const body: DocumentCreateRequest = {
      path,
      inlineText: props.content,
      mimeType: mimeForKind(props.kind),
    };
    const params = new URLSearchParams({ projectId });
    const created = await brainFetch<DocumentDto>(
      'POST',
      `documents?${params}`,
      { body },
    );
    const link = buildVanceLink(created.path ?? path, props.kind);
    if (typeof navigator !== 'undefined' && navigator.clipboard) {
      void navigator.clipboard.writeText(link);
    }
    flashStatus('ok', `Gespeichert: ${created.path ?? path} — Link kopiert`);
  } catch (e) {
    const msg = e instanceof Error ? e.message : String(e);
    flashStatus('err', `Speichern fehlgeschlagen: ${msg}`);
  } finally {
    saving.value = false;
  }
}

function buildVanceLink(path: string, kind: string): string {
  const encodedPath = path
    .split('/')
    .map((seg) => encodeURIComponent(seg))
    .join('/');
  const k = encodeURIComponent(kind.toLowerCase());
  const text = path.split('/').pop() || path;
  // Image-style for image/svg kinds, link-style otherwise — same
  // heuristic as DocumentLinkBuilder server-side.
  const imageStyle = kind.toLowerCase() === 'image' || kind.toLowerCase() === 'svg';
  return `${imageStyle ? '!' : ''}[${text}](vance:/${encodedPath}?kind=${k})`;
}

function flashStatus(kind: 'ok' | 'err', text: string): void {
  status.value = { kind, text };
  // Auto-clear OK messages so the box doesn't permanently carry a
  // stale toast; errors stick until the user toggles another action.
  if (kind === 'ok') {
    setTimeout(() => {
      if (status.value?.text === text) status.value = null;
    }, 4000);
  }
}

function timestampSlug(): string {
  return new Date().toISOString().replace(/[:.]/g, '-').slice(0, 16);
}

function extForKind(kind: string): string {
  switch (kind.toLowerCase()) {
    case 'json': return 'json';
    case 'yaml': return 'yaml';
    case 'xml':  return 'xml';
    case 'bash': return 'sh';
    case 'java': return 'java';
    case 'python': return 'py';
    case 'sql':  return 'sql';
    case 'markdown': return 'md';
    case 'mindmap':
    case 'tree':
    case 'list':
    case 'items':
    case 'table':
      return 'md';
    case 'graph':
    case 'records':
    case 'sheet':
      return 'json';
    case 'svg': return 'svg';
    default: return 'txt';
  }
}

function mimeForKind(kind: string): string {
  switch (kind.toLowerCase()) {
    case 'json': return 'application/json';
    case 'yaml': return 'application/yaml';
    case 'xml':  return 'application/xml';
    case 'svg':  return 'image/svg+xml';
    case 'markdown':
    case 'mindmap':
    case 'tree':
    case 'list':
    case 'items':
    case 'table':
      return 'text/markdown';
    case 'graph':
    case 'records':
    case 'sheet':
      return 'application/json';
    default:
      return 'text/plain';
  }
}
</script>

<template>
  <KindBox :kind="kind" :label="label" :icon="icon">
    <template #actions>
      <button class="kbx-act" :title="$t?.('chat.kindBox.download') ?? 'Download'" @click="onDownload">↓</button>
      <button class="kbx-act" :title="$t?.('chat.kindBox.raw') ?? 'Raw'" @click="toggleRaw">{{ showRaw ? '⟲' : '&lt;/&gt;' }}</button>
      <button class="kbx-act" :title="$t?.('chat.kindBox.copy') ?? 'Copy'" @click="onCopy">⧉</button>
      <button
        class="kbx-act"
        :disabled="saving"
        :title="$t?.('chat.kindBox.saveAsDocument') ?? 'Save as Document'"
        @click="onSaveAsDocument"
      >{{ saving ? '⌛' : '💾' }}</button>
    </template>
    <div
      v-if="status"
      :class="['kbx-status', status.kind === 'ok' ? 'kbx-status--ok' : 'kbx-status--err']"
    >{{ status.text }}</div>
    <pre v-if="showRaw || !renderer" class="kbx-raw"><code>{{ content }}</code></pre>
    <component
      v-else
      :is="renderer.inline"
      mode="inline"
      :content="content"
      :meta="meta"
    />
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
.kbx-act:disabled {
  cursor: default;
  opacity: 0.4;
}
.kbx-status {
  font-size: 0.78rem;
  padding: 0.3rem 0.5rem;
  border-radius: 0.25rem;
  margin-bottom: 0.4rem;
}
.kbx-status--ok {
  background: hsl(var(--su) / 0.12);
  color: hsl(var(--su));
}
.kbx-status--err {
  background: hsl(var(--er) / 0.12);
  color: hsl(var(--er));
}
.kbx-raw {
  margin: 0;
  background: hsl(var(--bc) / 0.05);
  padding: 0.5em 0.75em;
  border-radius: 0.25rem;
  overflow-x: auto;
  font-size: 0.85em;
  font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace;
  white-space: pre;
}
.kbx-raw code {
  background: transparent;
  padding: 0;
}
</style>
