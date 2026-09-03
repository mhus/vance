<script setup lang="ts">
/**
 * KaTeX + mhchem renderer for the `formula` kind.
 *
 * Renders mathematical and chemical formulas inline (chat fence),
 * embedded (document reference), or as a code-preview in the Cortex
 * editor. Supports two modes:
 *
 * - **Display** (default): the entire fence body is rendered as a
 *   single display-mode formula — `katex.renderToString(body,
 *   { displayMode: true })`.
 *
 * - **Mixed** (via `mixed=true` fence-meta): the body is parsed for
 *   `$…$`, `$$…$$`, `\(…\)`, `\[…\` delimiters; math segments are
 *   rendered with KaTeX, text between them as plain text. Same
 *   parsing logic as `TexPreview.vue`.
 *
 * mhchem is loaded via a side-effect import so `\ce{…}` syntax works
 * out of the box for chemical formulas.
 */
import { computed } from 'vue';
import katex from 'katex';
import 'katex/dist/contrib/mhchem.min.js';
import 'katex/dist/katex.min.css';
import type { FenceMeta } from '@/kindRenderers/parseFenceLang';
import type { DocumentDto } from '@vance/generated';
import type { EmbedRef } from '@/kindRenderers/parseVanceUri';

defineOptions({ name: 'FormulaView' });

const props = withDefaults(defineProps<{
  mode?: 'editor' | 'inline' | 'embedded';
  /** Inline mode — raw formula source from the fence body. */
  content?: string;
  meta?: FenceMeta;
  /** Embedded mode — loaded Document. */
  document?: DocumentDto;
  embedRef?: EmbedRef;
}>(), {
  mode: 'inline',
  meta: () => ({}),
});

/** Resolve the source text across all three modes. */
const source = computed<string>(() => {
  if (props.mode === 'embedded') return props.document?.inlineText ?? '';
  return props.content ?? '';
});

const mixedMode = computed<boolean>(() => props.meta.mixed === 'true');

interface MathSegment {
  html: string;
  isDisplay: boolean;
  isError: boolean;
}

function escapeHtml(text: string): string {
  return text
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;');
}

/** Render a single math expression with KaTeX. */
function renderMath(math: string, displayMode: boolean): MathSegment {
  try {
    const html = katex.renderToString(math.trim(), {
      displayMode,
      throwOnError: false,
      strict: 'ignore',
    });
    return { html, isDisplay: displayMode, isError: false };
  } catch (e) {
    const msg = e instanceof Error ? e.message : String(e);
    return {
      html: `<span class="formula-error">${escapeHtml(msg)}</span>`,
      isDisplay: displayMode,
      isError: true,
    };
  }
}

/** Escape HTML and wrap TeX commands in a gray span (like TexPreview). */
function renderText(text: string): string {
  const escaped = escapeHtml(text);
  return escaped.replace(
    /\\([a-zA-Z@]+)\*?/g,
    '<span class="formula-cmd">\\$1*</span>',
  );
}

/**
 * Parse `$…$`, `$$…$$`, `\(…\)`, `\[…\` delimiters — same logic
 * as TexPreview.vue.
 */
function parseSegments(src: string): MathSegment[] {
  if (!src) return [];
  const segments: MathSegment[] = [];
  const re =
    /(\$\$([\s\S]+?)\$\$)|(\\\\\[([\s\S]+?)\\\\\])|(\$([^$\n]+?)\$)|(\\\\\(([\s\S]+?)\\\\\))/g;
  let lastIndex = 0;
  let match: RegExpExecArray | null;
  while ((match = re.exec(src)) !== null) {
    if (match.index > lastIndex) {
      const text = src.slice(lastIndex, match.index);
      segments.push({ html: renderText(text), isDisplay: false, isError: false });
    }
    let math: string;
    let displayMode: boolean;
    if (match[2] !== undefined) {
      math = match[2]; displayMode = true;
    } else if (match[4] !== undefined) {
      math = match[4]; displayMode = true;
    } else if (match[6] !== undefined) {
      math = match[6]; displayMode = false;
    } else {
      math = match[8]; displayMode = false;
    }
    segments.push(renderMath(math, displayMode));
    lastIndex = re.lastIndex;
  }
  if (lastIndex < src.length) {
    const text = src.slice(lastIndex);
    segments.push({ html: renderText(text), isDisplay: false, isError: false });
  }
  return segments;
}

/** Render the whole body as a single display formula. */
function renderDisplay(src: string): MathSegment[] {
  const trimmed = src.trim();
  if (!trimmed) return [];
  return [renderMath(trimmed, true)];
}

const segments = computed<MathSegment[]>(() => {
  const src = source.value;
  if (!src.trim()) return [];
  return mixedMode.value ? parseSegments(src) : renderDisplay(src);
});
</script>

<template>
  <div class="formula-view">
    <template v-for="(seg, i) in segments" :key="i">
      <div
        v-if="seg.isDisplay"
        class="formula-display"
        v-html="seg.html"
      />
      <span v-else v-html="seg.html" />
    </template>
    <div v-if="segments.length === 0" class="formula-empty">
      <span class="opacity-40 italic">{{ $t('kindViews.formula.empty') }}</span>
    </div>
  </div>
</template>

<style scoped>
.formula-view {
  font-size: 0.95rem;
  line-height: 1.6;
  padding: 0.5rem 0;
  white-space: pre-wrap;
  word-wrap: break-word;
}

.formula-display {
  overflow-x: auto;
  overflow-y: hidden;
  padding: 0.25rem 0;
  text-align: center;
}

:deep(.formula-cmd) {
  color: var(--cat, #888);
  opacity: 0.6;
}

:deep(.formula-error) {
  color: var(--color-error);
  background: rgba(239, 68, 68, 0.08);
  border-radius: 2px;
  padding: 0 2px;
  font-family: monospace;
  font-size: 0.85em;
}

.formula-empty {
  padding: 0.5rem 0;
}
</style>
