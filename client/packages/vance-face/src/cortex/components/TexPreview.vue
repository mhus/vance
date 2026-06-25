<script setup lang="ts">
/**
 * Live KaTeX preview for TeX source files.
 *
 * Parses the raw {@code .tex} source for math delimiters and renders
 * the formulas with KaTeX — no backend, no latexmk, no full LaTeX
 * layout. Think of it as a quick formula preview while editing, not
 * a replacement for the "Generate PDF" button.
 *
 * <p>Supported delimiters (LaTeX / TeX convention):
 * <ul>
 *   <li>{@code $$...$$} — display math (block-level, centered)</li>
 *   <li>{@code \\[...\\]} — display math (alternative)</li>
 *   <li>{@code $...$} — inline math</li>
 *   <li>{@code \\(...\\)} — inline math (alternative)</li>
 * </ul>
 *
 * <p>Non-math text between formulas is rendered as plain text,
 * preserving line breaks. LaTeX commands outside math mode
 * ({@code \\section}, {@code \\includegraphics}, etc.) are shown
 * verbatim — KaTeX only renders math, not the full LaTeX document
 * structure. For the full rendered document, use "Generate PDF".
 */
import { computed } from 'vue';
import katex from 'katex';
import 'katex/dist/katex.min.css';

interface Props {
  source: string;
}

const props = defineProps<Props>();

interface MathSegment {
  /** Rendered HTML (KaTeX output or plain text). */
  html: string;
  /** Whether this segment is a math block (display mode). */
  isDisplay: boolean;
  /** Whether KaTeX rendering threw an error. */
  isError: boolean;
}

/**
 * Split the source into segments: math (inline + display) and plain
 * text. The regex captures both {@code $$...$$} / {@code \\[...\\]}
 * (display) and {@code $...$} / {@code \\(...\\)} (inline) delimiters.
 *
 * <p>Escaped dollar signs ({@code \$}) are treated as literal text,
 * not as math delimiters.
 */
function parseSegments(source: string): MathSegment[] {
  if (!source) return [];

  const segments: MathSegment[] = [];

  // Regex: matches $$...$$, \[...\], $...$, \(...\) in priority order.
  // Group 1 = display $$, group 2 = display \[\], group 3 = inline $, group 4 = inline \(\)
  const re =
    /(\$\$([\s\S]+?)\$\$)|(\\\[([\s\S]+?)\\\])|(\$([^\$\n]+?)\$)|(\\\(([\s\S]+?)\\\))/g;

  let lastIndex = 0;
  let match: RegExpExecArray | null;

  while ((match = re.exec(source)) !== null) {
    // Text before this math segment
    if (match.index > lastIndex) {
      const text = source.slice(lastIndex, match.index);
      segments.push({ html: renderText(text), isDisplay: false, isError: false });
    }

    // Extract the math content and mode
    let math: string;
    let displayMode: boolean;

    if (match[2] !== undefined) {
      // $$...$$
      math = match[2];
      displayMode = true;
    } else if (match[4] !== undefined) {
      // \[...\]
      math = match[4];
      displayMode = true;
    } else if (match[6] !== undefined) {
      // $...$
      math = match[6];
      displayMode = false;
    } else {
      // \(...\)
      math = match[8];
      displayMode = false;
    }

    // Render with KaTeX
    try {
      const html = katex.renderToString(math.trim(), {
        displayMode,
        throwOnError: true,
        strict: 'ignore',
      });
      segments.push({ html, isDisplay: displayMode, isError: false });
    } catch (e) {
      const msg = e instanceof Error ? e.message : String(e);
      segments.push({
        html: `<span class="tex-preview-error">${escapeHtml(msg)}</span>`,
        isDisplay: displayMode,
        isError: true,
      });
    }

    lastIndex = re.lastIndex;
  }

  // Trailing text after the last math segment
  if (lastIndex < source.length) {
    const text = source.slice(lastIndex);
    segments.push({ html: renderText(text), isDisplay: false, isError: false });
  }

  return segments;
}

function escapeHtml(text: string): string {
  return text
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;');
}

/**
 * Escape HTML, then wrap TeX commands ({@code \\word}) in a gray span
 * so they are visually distinct from plain prose in the preview.
 */
function renderText(text: string): string {
  const escaped = escapeHtml(text);
  // \ followed by letters (and optional trailing *), e.g. \section, \LaTeX, \textbf
  return escaped.replace(
    /\\([a-zA-Z@]+)\*?/g,
    '<span class="tex-cmd">\\$1*</span>',
  );
}

const segments = computed(() => parseSegments(props.source));

/** Count of math segments (inline + display) for the status bar. */
const mathCount = computed(
  () => segments.value.filter((s) => !s.isError && s.html.includes('katex')).length,
);
const errorCount = computed(
  () => segments.value.filter((s) => s.isError).length,
);
</script>

<template>
  <div class="tex-preview h-full overflow-auto px-6 py-4">
    <!-- Status bar -->
    <div class="text-xs opacity-50 mb-3 flex gap-4">
      <span>{{ mathCount }} formula{{ mathCount === 1 ? '' : 's' }}</span>
      <span v-if="errorCount" class="text-error">
        {{ errorCount }} error{{ errorCount === 1 ? '' : 's' }}
      </span>
      <span class="italic">KaTeX preview — for full layout use "Generate PDF"</span>
    </div>

    <!-- Rendered segments -->
    <div class="tex-preview-body">
      <template v-for="(seg, i) in segments" :key="i">
        <div
          v-if="seg.isDisplay"
          class="tex-display-math my-2 text-center"
          v-html="seg.html"
        />
        <span v-else v-html="seg.html" />
      </template>
    </div>

    <!-- Empty state -->
    <div v-if="segments.length === 0" class="opacity-40 italic text-sm">
      No content to preview.
    </div>
  </div>
</template>

<style scoped>
.tex-preview {
  font-size: 0.9rem;
  line-height: 1.6;
}

.tex-preview-body {
  white-space: pre-wrap;
  word-wrap: break-word;
}

.tex-display-math {
  overflow-x: auto;
  overflow-y: hidden;
  padding: 0.25rem 0;
}

:deep(.tex-cmd) {
  color: var(--cat, #888);
  opacity: 0.6;
}

:deep(.tex-preview-error) {
  color: var(--er, #ef4444);
  background: rgba(239, 68, 68, 0.08);
  border-radius: 2px;
  padding: 0 2px;
  font-family: monospace;
  font-size: 0.85em;
}
</style>
