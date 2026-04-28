<script setup lang="ts">
import { computed } from 'vue';
import { marked } from 'marked';
import DOMPurify from 'dompurify';

interface Props {
  /** Raw Markdown source. {@code null}/blank renders empty. */
  source?: string | null;
  /**
   * Compact one-line rendering (no block elements). Useful when
   * the same content appears as a chat-bubble or list-row preview.
   * Default `false` — full block rendering.
   */
  inline?: boolean;
}

const props = withDefaults(defineProps<Props>(), {
  inline: false,
});

// Configure marked once per module load — defaults are safe enough,
// we just want GFM (tables, task-lists, fenced code) and breaks.
marked.setOptions({
  gfm: true,
  breaks: true,
});

const html = computed<string>(() => {
  const src = props.source ?? '';
  if (!src) return '';
  // marked's parse can be sync or async depending on extensions. We
  // use no async extensions, so the sync path is guaranteed; cast to
  // string for the strict-typed call site.
  const raw = props.inline
    ? (marked.parseInline(src) as string)
    : (marked.parse(src) as string);
  // The body is user / LLM content. We must sanitize before
  // injecting via v-html — DOMPurify drops scripts, on*-handlers,
  // javascript: URLs, etc. Keep a tight allow-list of attributes.
  return DOMPurify.sanitize(raw, {
    USE_PROFILES: { html: true },
  });
});
</script>

<template>
  <div :class="['markdown-view', { 'markdown-view--inline': inline }]" v-html="html" />
</template>

<style scoped>
.markdown-view {
  /* Block layout for headings, lists, code blocks, tables. */
  font-size: 0.95rem;
  line-height: 1.55;
  word-break: break-word;
}

.markdown-view :deep(h1),
.markdown-view :deep(h2),
.markdown-view :deep(h3) {
  font-weight: 600;
  margin: 0.75em 0 0.4em;
  line-height: 1.25;
}
.markdown-view :deep(h1) { font-size: 1.4rem; }
.markdown-view :deep(h2) { font-size: 1.2rem; }
.markdown-view :deep(h3) { font-size: 1.05rem; }
.markdown-view :deep(h4),
.markdown-view :deep(h5),
.markdown-view :deep(h6) {
  font-weight: 600;
  margin: 0.6em 0 0.3em;
}

.markdown-view :deep(p)  { margin: 0.5em 0; }
.markdown-view :deep(ul),
.markdown-view :deep(ol) { margin: 0.5em 0 0.5em 1.4em; }
.markdown-view :deep(li) { margin: 0.15em 0; }
.markdown-view :deep(blockquote) {
  border-left: 3px solid hsl(var(--bc) / 0.25);
  padding-left: 0.75em;
  margin: 0.6em 0;
  opacity: 0.8;
}

.markdown-view :deep(code) {
  background: hsl(var(--bc) / 0.08);
  padding: 0.05em 0.3em;
  border-radius: 0.25rem;
  font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace;
  font-size: 0.85em;
}
.markdown-view :deep(pre) {
  background: hsl(var(--bc) / 0.06);
  padding: 0.75em 1em;
  border-radius: 0.5rem;
  overflow-x: auto;
  margin: 0.6em 0;
}
.markdown-view :deep(pre code) {
  background: transparent;
  padding: 0;
  font-size: 0.85em;
}

.markdown-view :deep(a) {
  color: hsl(var(--p));
  text-decoration: underline;
}
.markdown-view :deep(a:hover) { opacity: 0.85; }

.markdown-view :deep(table) {
  border-collapse: collapse;
  margin: 0.6em 0;
}
.markdown-view :deep(th),
.markdown-view :deep(td) {
  border: 1px solid hsl(var(--bc) / 0.2);
  padding: 0.3em 0.6em;
  text-align: left;
}
.markdown-view :deep(th) {
  background: hsl(var(--bc) / 0.05);
  font-weight: 600;
}

.markdown-view :deep(hr) {
  border: 0;
  border-top: 1px solid hsl(var(--bc) / 0.18);
  margin: 1em 0;
}

.markdown-view :deep(img) {
  max-width: 100%;
  height: auto;
  border-radius: 0.375rem;
}

/* Compact inline-mode: strip block margins so the rendered
   fragment fits inside a one-line container (chat bubble,
   list-row preview). */
.markdown-view--inline :deep(p),
.markdown-view--inline :deep(ul),
.markdown-view--inline :deep(ol) {
  display: inline;
  margin: 0;
}
</style>
