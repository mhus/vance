<script setup lang="ts">
/**
 * Read-only code renderer for inline / embedded code-kind blocks
 * (java, python, sql, json, yaml, …). Thin wrapper around
 * {@code <CodeEditor>}: maps the kind to a mime-type and flips the
 * editor into {@code readOnly} mode.
 *
 * Modes (spec §11.2):
 *   - `inline`   — content from a fenced-block body via `content`.
 *   - `embedded` — content from a loaded {@link DocumentDto}.
 *   - `editor`   — full-edit CodeEditor (the doc-app already wires
 *                  CodeEditor directly today; this view re-exports
 *                  it for completeness).
 */
import { computed } from 'vue';
import CodeEditor from '@components/CodeEditor.vue';
import type { DocumentDto } from '@vance/generated';
import type { EmbedRef } from '@/kindRenderers/parseVanceUri';
import type { FenceMeta } from '@/kindRenderers/parseFenceLang';

interface Props {
  /** Identifies the code language (kind). */
  kind?: string;
  /** Mode-switch — editor (default) / inline / embedded. */
  mode?: 'editor' | 'inline' | 'embedded';
  /** Fenced-body content — inline mode. */
  content?: string;
  meta?: FenceMeta;
  /** Loaded Document — embedded mode. */
  document?: DocumentDto;
  embedRef?: EmbedRef;
}

const props = withDefaults(defineProps<Props>(), {
  mode: 'inline',
  meta: () => ({}),
});

const sourceText = computed<string>(() => {
  if (props.mode === 'inline') return props.content ?? '';
  if (props.mode === 'embedded') return props.document?.inlineText ?? '';
  return props.content ?? props.document?.inlineText ?? '';
});

const effectiveMime = computed<string>(() => {
  // Embedded mode: trust the Document's own mime where present.
  const docMime = props.document?.mimeType;
  if (docMime && props.mode === 'embedded') return docMime;
  // Otherwise infer from the kind tag.
  return mimeForKind(props.kind);
});

const rows = computed<number>(() => {
  const lines = sourceText.value.split('\n').length;
  // Compact: 4 rows minimum, max 24 — fence blocks shouldn't dwarf
  // the chat. Embedded mode honours the doc length up to 32.
  if (props.mode === 'embedded') return Math.min(Math.max(lines, 6), 32);
  return Math.min(Math.max(lines, 4), 24);
});

function mimeForKind(kind: string | undefined): string {
  switch ((kind ?? '').toLowerCase()) {
    case 'java': return 'text/x-java';
    case 'python':
    case 'py':
      return 'text/x-python';
    case 'js':
    case 'javascript':
      return 'application/javascript';
    case 'ts':
    case 'typescript':
      return 'application/typescript';
    case 'sql': return 'application/sql';
    case 'json': return 'application/json';
    case 'yaml':
    case 'yml':
      return 'application/yaml';
    case 'xml': return 'application/xml';
    case 'html': return 'text/html';
    case 'css': return 'text/css';
    case 'bash':
    case 'sh':
    case 'shell':
      return 'application/x-sh';
    case 'r': return 'text/x-r';
    case 'markdown':
    case 'md':
      return 'text/markdown';
    default: return 'text/plain';
  }
}
</script>

<template>
  <div class="code-view" :class="`code-view--${mode}`">
    <CodeEditor
      :model-value="sourceText"
      :mime-type="effectiveMime"
      :rows="rows"
      :read-only="true"
    />
  </div>
</template>

<style scoped>
.code-view :deep(.code-editor) {
  border: none;
  border-radius: 0;
  background: hsl(var(--b1));
}
.code-view--inline :deep(.cm-editor),
.code-view--embedded :deep(.cm-editor) {
  font-size: 0.82rem;
}
</style>
