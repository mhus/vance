<script setup lang="ts">
import { computed } from 'vue';
import hljs from 'highlight.js/lib/common';
import 'highlight.js/styles/github.css';
import type { Component } from 'vue';
import type { Block } from './markdown/blocks';
import { parse } from './markdown/parser';
import InlineRender from './InlineRender.vue';
import { safeHref } from './safeHref';
import { findBlockByFence } from './blockRegistry';
import { registerBuiltInBlocks } from './builtins';

// Ensure bundled built-in blocks (callout, …) are registered before we
// render — BlockView is often the first thing to touch a parsed document
// (e.g. WorkPageKind's parseDocument in a render-time computed).
registerBuiltInBlocks();

/** Read-only Vue view for an addon/built-in `custom` block, or null. */
function customView(fence: string): Component | null {
  return findBlockByFence(fence)?.view ?? null;
}

function highlightCode(code: string, lang: string | null): string {
  // Vue interpolation auto-escapes, but the rendered hljs output is
  // intentionally raw HTML — language tokens become <span>s. Use
  // highlight.js's escape pipeline (no language) as a safe fallback
  // when the language is unknown or not bundled.
  if (lang) {
    try {
      return hljs.highlight(code, { language: lang, ignoreIllegals: true }).value;
    } catch {
      /* fall through to auto */
    }
  }
  try {
    return hljs.highlightAuto(code).value;
  } catch {
    // Final fallback: escape manually to avoid raw injection.
    return code
      .replace(/&/g, '&amp;')
      .replace(/</g, '&lt;')
      .replace(/>/g, '&gt;');
  }
}

/**
 * Read-only renderer for a Canvas-block list. Plain Vue templates per
 * block kind — no Tiptap, no marked, no Markdown-in-Markdown. XSS-safe
 * because Vue auto-escapes interpolated text; HTML is only emitted from
 * our own block-kind switch.
 *
 * Used in two places:
 * - `CanvasKind.vue`: read-only View tab for `kind: workpage` documents.
 * - `WorkspaceAppKind.vue`: Master-Detail right pane for the currently
 *   selected workspace page.
 *
 * Inline-marks (bold/italic/code) inside paragraphs render as raw
 * characters in v1 — same trade-off as the editor. A future inline-mark
 * pass would plug in here without changing the API.
 */
const props = defineProps<{
  blocks: Block[];
}>();

// ── Heading anchors ────────────────────────────────────────────────
// Each heading gets a slug-id so a ToC entry can scrollIntoView() and
// users can link directly to a section. Slugs come from the heading
// text — lowercase, alphanumerics + dashes, collapsing runs. Duplicate
// headings get `-2`, `-3` suffixes so anchor links stay unique.

function slugify(text: string): string {
  return text
    .toLowerCase()
    .replace(/[^a-z0-9\s-]/g, '')
    .replace(/\s+/g, '-')
    .replace(/-+/g, '-')
    .replace(/^-|-$/g, '');
}

interface TocEntry {
  level: number;
  text: string;
  slug: string;
}

const headingSlugs = computed<Map<number, string>>(() => {
  const slugs = new Map<number, string>();
  const counts = new Map<string, number>();
  props.blocks.forEach((b, idx) => {
    if (b.kind !== 'heading') return;
    let base = slugify(b.text);
    if (!base) base = `heading-${idx}`;
    const n = (counts.get(base) ?? 0) + 1;
    counts.set(base, n);
    slugs.set(idx, n === 1 ? base : `${base}-${n}`);
  });
  return slugs;
});

const tocEntries = computed<TocEntry[]>(() => {
  const out: TocEntry[] = [];
  props.blocks.forEach((b, idx) => {
    if (b.kind !== 'heading') return;
    out.push({
      level: b.level,
      text: b.text,
      slug: headingSlugs.value.get(idx) ?? '',
    });
  });
  return out;
});

function columnsTemplate(cols: Array<{ width: number | null }>): string {
  // Translate widths to CSS-grid fractional units. null falls back to 1fr;
  // explicit fractions are passed straight through (so 0.4 + 0.6 becomes
  // "0.4fr 0.6fr" with the right proportion). Mixed null/number columns
  // share whatever's left equally — common case after a single resize.
  return cols.map((c) => (c.width != null ? `${c.width}fr` : '1fr')).join(' ');
}

function scrollToHeading(slug: string) {
  const el = document.getElementById(slug);
  if (el) el.scrollIntoView({ behavior: 'smooth', block: 'start' });
}

function copyAnchor(slug: string, event: Event) {
  event.preventDefault();
  const url = `${window.location.origin}${window.location.pathname}${window.location.search}#${slug}`;
  void navigator.clipboard?.writeText(url);
  // Quietly succeed — a toast notification would be nicer but keeps
  // BlockView free of UI deps. Browser visit history shows the # too.
  if (typeof history?.replaceState === 'function') {
    history.replaceState(null, '', `#${slug}`);
  }
  scrollToHeading(slug);
}

// Toggle bodies are themselves Markdown — parse them recursively so a
// toggle can wrap headings / lists / nested callouts. Bounded depth in
// practice (toggle inside toggle is rare) so the recursion is fine.
function parseBody(body: string | undefined): Block[] {
  if (!body) return [];
  return parse(body);
}

const items = computed(() => props.blocks ?? []);
</script>

<template>
  <article class="block-view">
    <template v-for="(block, i) in items" :key="i">
      <p v-if="block.kind === 'paragraph'" class="block-view__paragraph">
        <InlineRender :text="block.text" />
      </p>

      <h1
        v-else-if="block.kind === 'heading' && block.level === 1"
        :id="headingSlugs.get(i)"
        class="block-view__h1 block-view__heading"
      >
        <InlineRender :text="block.text" />
        <a
          v-if="headingSlugs.get(i)"
          :href="`#${headingSlugs.get(i)}`"
          class="block-view__anchor"
          title="Link kopieren"
          @click="copyAnchor(headingSlugs.get(i)!, $event)"
        >🔗</a>
      </h1>
      <h2
        v-else-if="block.kind === 'heading' && block.level === 2"
        :id="headingSlugs.get(i)"
        class="block-view__h2 block-view__heading"
      >
        <InlineRender :text="block.text" />
        <a
          v-if="headingSlugs.get(i)"
          :href="`#${headingSlugs.get(i)}`"
          class="block-view__anchor"
          title="Link kopieren"
          @click="copyAnchor(headingSlugs.get(i)!, $event)"
        >🔗</a>
      </h2>
      <h3
        v-else-if="block.kind === 'heading' && block.level === 3"
        :id="headingSlugs.get(i)"
        class="block-view__h3 block-view__heading"
      >
        <InlineRender :text="block.text" />
        <a
          v-if="headingSlugs.get(i)"
          :href="`#${headingSlugs.get(i)}`"
          class="block-view__anchor"
          title="Link kopieren"
          @click="copyAnchor(headingSlugs.get(i)!, $event)"
        >🔗</a>
      </h3>

      <ul v-else-if="block.kind === 'bullet-list'" class="block-view__bullet-list">
        <li v-for="(item, k) in block.items" :key="k"><InlineRender :text="item" /></li>
      </ul>

      <ol v-else-if="block.kind === 'numbered-list'" class="block-view__numbered-list">
        <li v-for="(item, k) in block.items" :key="k"><InlineRender :text="item" /></li>
      </ol>

      <ul v-else-if="block.kind === 'todo'" class="block-view__todo-list">
        <li v-for="(item, k) in block.items" :key="k" class="block-view__todo-item">
          <input type="checkbox" :checked="item.checked" disabled />
          <span :class="{ 'block-view__todo-text--done': item.checked }">
            <InlineRender :text="item.text" />
          </span>
        </li>
      </ul>

      <blockquote v-else-if="block.kind === 'quote'" class="block-view__quote">
        <template v-for="(line, k) in block.text.split('\n')" :key="k">
          <span><InlineRender :text="line" /></span><br v-if="k < block.text.split('\n').length - 1" />
        </template>
      </blockquote>

      <pre v-else-if="block.kind === 'code'" class="block-view__code hljs"><code
        :class="block.lang ? `language-${block.lang}` : ''"
        v-html="highlightCode(block.code, block.lang)"
      /></pre>

      <hr v-else-if="block.kind === 'divider'" class="block-view__divider" />

      <p v-else-if="block.kind === 'image'" class="block-view__image-wrap">
        <img :src="block.src" :alt="block.alt" class="block-view__image" />
      </p>

      <table v-else-if="block.kind === 'table'" class="block-view__table">
        <thead v-if="block.headers.length > 0">
          <tr>
            <th v-for="(h, k) in block.headers" :key="k">{{ h }}</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="(row, r) in block.rows" :key="r">
            <td v-for="(cell, c) in row" :key="c">{{ cell }}</td>
          </tr>
        </tbody>
      </table>

      <component
        v-else-if="block.kind === 'custom' && customView(block.fence)"
        :is="customView(block.fence)"
        :attrs="block.attrs"
      />
      <pre
        v-else-if="block.kind === 'custom'"
        class="vance-unknown-fence"
      ><div class="vance-unknown-fence__label">Block: {{ block.fence }}</div><code>{{ block.rawBody }}</code></pre>

      <details v-else-if="block.kind === 'toggle'" class="vance-toggle">
        <summary class="vance-toggle__summary">{{ block.summary }}</summary>
        <div class="vance-toggle__body">
          <BlockView :blocks="parseBody(block.body)" />
        </div>
      </details>

      <a
        v-else-if="block.kind === 'link-card'"
        :href="safeHref(block.href)"
        target="_blank"
        rel="noopener noreferrer"
        class="vance-link-card"
      >
        <div class="vance-link-card__title">{{ block.title ?? block.href }}</div>
        <div v-if="block.description" class="vance-link-card__description">
          {{ block.description }}
        </div>
        <div class="vance-link-card__href">{{ block.href }}</div>
      </a>

      <div v-else-if="block.kind === 'dataview'" class="vance-dataview-stub">
        <div class="vance-dataview-stub__label">Dataview</div>
        <code class="vance-dataview-stub__source">{{ block.source }}</code>
        <div class="vance-dataview-stub__hint">
          Dataview rendering not yet implemented.
        </div>
      </div>

      <div
        v-else-if="block.kind === 'columns'"
        class="vance-columns"
        :style="`grid-template-columns: ${columnsTemplate(block.columns)};`"
      >
        <div
          v-for="(col, k) in block.columns"
          :key="k"
          class="vance-column"
        >
          <BlockView :blocks="col.blocks" />
        </div>
      </div>

      <aside v-else-if="block.kind === 'toc'" class="vance-toc">
        <div class="vance-toc__label">Inhaltsverzeichnis</div>
        <div v-if="tocEntries.length === 0" class="vance-toc__empty">
          Noch keine Überschriften auf dieser Seite.
        </div>
        <ul v-else class="vance-toc__list">
          <li
            v-for="(e, k) in tocEntries"
            :key="k"
            class="vance-toc__item"
            :class="`vance-toc__item--h${e.level}`"
          >
            <a
              :href="`#${e.slug}`"
              class="vance-toc__link"
              @click.prevent="scrollToHeading(e.slug)"
            >{{ e.text }}</a>
          </li>
        </ul>
      </aside>

      <pre v-else-if="block.kind === 'unknown-fence'" class="vance-unknown-fence">
        <div class="vance-unknown-fence__label">Unknown block: {{ block.info }}</div>
        <code>{{ block.body }}</code>
      </pre>
    </template>
  </article>
</template>

<style>
.block-view {
  max-width: 760px;
  margin: 0 auto;
  padding: 1.5rem 2rem;
  font-size: 1rem;
  line-height: 1.6;
  color: inherit;
}
.block-view__h1 { font-size: 1.75rem; font-weight: 600; margin: 1.25em 0 0.5em; }
.block-view__h2 { font-size: 1.4rem;  font-weight: 600; margin: 1em 0 0.5em; }
.block-view__h3 { font-size: 1.15rem; font-weight: 600; margin: 0.8em 0 0.4em; }
.block-view__heading {
  scroll-margin-top: 1rem;
  position: relative;
}
.block-view__anchor {
  margin-left: 0.5em;
  font-size: 0.65em;
  opacity: 0;
  text-decoration: none;
  color: oklch(var(--bc) / 0.65);
  vertical-align: middle;
  transition: opacity 0.15s ease;
}
.block-view__heading:hover .block-view__anchor { opacity: 1; }
.block-view__anchor:hover { color: oklch(var(--p)); }
.block-view__paragraph { margin: 0.5em 0; white-space: pre-wrap; }
.block-view__bullet-list {
  list-style-type: disc;
  padding-left: 1.5em;
  margin: 0.5em 0;
}
.block-view__numbered-list {
  list-style-type: decimal;
  padding-left: 1.5em;
  margin: 0.5em 0;
}
.block-view__todo-list { list-style: none; padding: 0; margin: 0.5em 0; }
.block-view__todo-item { display: flex; gap: 0.5em; align-items: flex-start; }
.block-view__todo-text--done { text-decoration: line-through; opacity: 0.6; }
.block-view__quote {
  border-left: 3px solid oklch(var(--bc) / 0.18);
  padding-left: 1em;
  color: oklch(var(--bc) / 0.65);
  margin: 0.75em 0;
  font-style: italic;
}
.block-view__code {
  background: oklch(var(--bc) / 0.08);
  border-radius: 0.375rem;
  padding: 0.75em 1em;
  font-family: monospace;
  font-size: 0.9em;
  overflow-x: auto;
  margin: 0.75em 0;
}
.block-view__divider {
  border: none;
  border-top: 1px solid oklch(var(--bc) / 0.18);
  margin: 1.25em 0;
}
.block-view__image-wrap { text-align: center; margin: 1em 0; }
.block-view__image { max-width: 100%; height: auto; border-radius: 0.25rem; }
.block-view__table {
  border-collapse: collapse;
  margin: 0.75em 0;
  width: 100%;
}
.block-view__table th,
.block-view__table td {
  border: 1px solid oklch(var(--bc) / 0.18);
  padding: 0.4em 0.75em;
  text-align: left;
}
.block-view__table th {
  background: oklch(var(--bc) / 0.06);
  font-weight: 600;
}

.vance-callout {
  border-left: 3px solid var(--vance-callout-color, oklch(var(--in)));
  background: var(--vance-callout-bg, oklch(var(--in) / 0.12));
  border-radius: 0.375rem;
  padding: 0.75em 1em;
  margin: 0.75em 0;
}
.vance-callout--warn { --vance-callout-color: oklch(var(--wa)); --vance-callout-bg: oklch(var(--wa) / 0.12); }
.vance-callout--error { --vance-callout-color: oklch(var(--er)); --vance-callout-bg: oklch(var(--er) / 0.12); }
.vance-callout--success { --vance-callout-color: oklch(var(--su)); --vance-callout-bg: oklch(var(--su) / 0.12); }
.vance-callout--note { --vance-callout-color: oklch(var(--bc) / 0.55); --vance-callout-bg: oklch(var(--bc) / 0.06); }
.vance-callout__title { font-weight: 600; margin-bottom: 0.25em; }
.vance-callout__body { white-space: pre-wrap; }

.vance-toggle {
  border: 1px solid oklch(var(--bc) / 0.18);
  border-radius: 0.375rem;
  padding: 0.5em 0.75em;
  margin: 0.5em 0;
}
.vance-toggle__summary { font-weight: 500; cursor: pointer; }
.vance-toggle__body { margin-top: 0.5em; }

.vance-link-card {
  display: block;
  border: 1px solid oklch(var(--bc) / 0.18);
  border-radius: 0.5rem;
  padding: 0.75em 1em;
  margin: 0.5em 0;
  text-decoration: none;
  color: inherit;
  background: oklch(var(--bc) / 0.06);
}
.vance-link-card__title { font-weight: 600; }
.vance-link-card__description {
  font-size: 0.9em;
  color: oklch(var(--bc) / 0.65);
  margin-top: 0.25em;
}
.vance-link-card__href {
  font-size: 0.8em;
  color: oklch(var(--bc) / 0.65);
  margin-top: 0.25em;
  word-break: break-all;
}

.vance-dataview-stub {
  border: 1px dashed oklch(var(--bc) / 0.18);
  border-radius: 0.375rem;
  padding: 0.75em 1em;
  margin: 0.75em 0;
  background: oklch(var(--bc) / 0.06);
}
.vance-dataview-stub__label {
  font-weight: 600;
  font-size: 0.85em;
  color: oklch(var(--bc) / 0.65);
}
.vance-dataview-stub__source {
  display: block;
  font-family: monospace;
  margin: 0.25em 0;
}
.vance-dataview-stub__hint {
  font-size: 0.85em;
  color: oklch(var(--bc) / 0.65);
}

/* BlockView-only column layout — namespaced under `.block-view` so it
   doesn't override the editor-side NodeView CSS for the same class
   names. The editor uses display:block on the wrapper and an inner
   `.vance-columns__content` grid; this read-only path inlines its grid
   on the outer container instead, since there's no resize-handle to
   accommodate. */
.block-view .vance-columns {
  display: grid;
  gap: 1rem;
  margin: 0.75em 0;
}
.block-view .vance-column {
  min-width: 0;
}
.block-view .vance-column > .block-view {
  padding: 0;
  margin: 0;
}
.block-view .vance-column > .block-view :first-child {
  margin-top: 0;
}

.vance-unknown-fence {
  background: oklch(var(--er) / 0.12);
  border: 1px solid oklch(var(--er) / 0.5);
  border-radius: 0.375rem;
  padding: 0.5em 0.75em;
  margin: 0.5em 0;
  white-space: pre-wrap;
  font-family: monospace;
  font-size: 0.85em;
}
.vance-unknown-fence__label { font-weight: 600; margin-bottom: 0.25em; color: oklch(var(--er)); }
</style>
