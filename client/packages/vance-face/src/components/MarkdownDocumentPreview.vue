<script lang="ts">
/**
 * Theme-aware markdown document preview for the Cortex "View" mode.
 *
 * <p>This is the Phase 3 surface for report themes in the web UI — the
 * one place a markdown document is shown rendered (not edited), and the
 * one place the document's {@code theme:}/{@code css:} front matter
 * should colour the result. It loads the theme CSS from the
 * {@code GET /documents/{id}/theme-css} endpoint and injects it as a
 * {@code <style>} element scoped to {@code .markdown-document-preview},
 * so the theme reaches the rendered markdown but cannot leak onto the
 * Cortex shell.
 *
 * <p><b>Why a separate component.</b> {@link MarkdownView} renders
 * markdown in chat, inbox, search hits and link cards — all untrusted
 * content surfaces where a {@code <style>} would be an injection vector.
 * DOMPurify strips {@code <style>} there, and that is correct. This
 * component is for trusted document content (operator/tenant/project
 * docs, not chat), and it adds the theme CSS from a server-filtered,
 * server-scoped endpoint — a different trust level, a different CSS
 * injection point, a different component.
 *
 * <p><b>Why not Shadow DOM.</b> Shadow DOM would give a real CSS
 * boundary, but {@link MarkdownView}'s rendered output includes Vue
 * VNodes for embedded kinds ({@link InlineKindBox}, {@link
 * EmbeddedKindBox}, {@link LinkCard}) which break in a shadow root.
 * Scoping the CSS server-side is the compromise that keeps the kinds
 * working. See {@code specification/public/report-themes.md} §9.3.
 *
 * <p><b>Fail-open.</b> A failed theme-css fetch (404, 403, network)
 * logs a warning and renders the document with no theme — the default
 * styles still apply. This mirrors the PDF path, which never aborts a
 * render on a missing theme.
 */
import { ref, watch, onMounted, h, type PropType } from 'vue';
import MarkdownView from './MarkdownView.vue';
import { brainFetchText } from '@vance/shared';

const THEME_CSS_CACHE = new Map<string, string>();

/** Cap on the in-memory cache. Insertion-ordered, so the oldest key goes. */
const THEME_CSS_CACHE_MAX = 50;

/**
 * The part of the source that decides the answer: the leading front-matter
 * fence, where `theme:` and `css:` live.
 *
 * The cache key has to carry this, not just the document id. Keyed on the id
 * alone, editing `theme:` and re-rendering shows the previous theme for as
 * long as the tab lives — the request that would have told us otherwise is
 * exactly the one the cache skips. Everything below the fence is body text
 * and cannot change the stylesheet, so it stays out of the key.
 */
function frontMatterOf(source: string | null): string {
  if (!source) return '';
  if (!source.startsWith('---')) return '';
  const end = source.indexOf('\n---', 3);
  return end < 0 ? source : source.slice(0, end + 4);
}

function rememberThemeCss(key: string, css: string): void {
  THEME_CSS_CACHE.set(key, css);
  while (THEME_CSS_CACHE.size > THEME_CSS_CACHE_MAX) {
    const oldest = THEME_CSS_CACHE.keys().next().value;
    if (oldest === undefined) break;
    THEME_CSS_CACHE.delete(oldest);
  }
}

export default {
  name: 'MarkdownDocumentPreview',
  components: { MarkdownView },
  props: {
    /** Raw markdown source. {@code null}/blank renders empty. */
    source: {
      type: [String, null] as unknown as PropType<string | null>,
      default: null,
    },
    /** Document id for the theme-css fetch. {@code null} skips the
     *  fetch (e.g. a not-yet-saved draft has no id, so no theme). */
    documentId: {
      type: [String, null] as unknown as PropType<string | null>,
      default: null,
    },
  },
  setup(props) {
    const themeCss = ref<string>('');

    async function loadThemeCss(documentId: string): Promise<void> {
      // Per-tab cache (small, in-memory, bounded). A theme changes rarely,
      // and a re-fetch on every tab switch would fire a request per view.
      // The server sets Cache-Control: private, max-age=60, so the browser
      // cache covers the cross-tab case; this cache covers same-tab
      // re-mounts. Keyed on the front matter as well as the id, so an edit
      // to `theme:` is a different question and gets asked.
      const key = `${documentId}\n${frontMatterOf(props.source)}`;
      const cached = THEME_CSS_CACHE.get(key);
      if (cached !== undefined) {
        themeCss.value = cached;
        return;
      }
      try {
        const css = await brainFetchText(`documents/${documentId}/theme-css`);
        const body = css ?? '';
        rememberThemeCss(key, body);
        themeCss.value = body;
      } catch (e) {
        // Fail-open: log and render with no theme. The default styles
        // still apply through MarkdownView's scoped CSS, so the document
        // stays readable. We do NOT surface an error banner — a theme
        // problem is a styling problem, not a content problem. Not cached
        // either: a failed fetch is a moment, not an answer.
        themeCss.value = '';
        // eslint-disable-next-line no-console
        console.warn(
          'MarkdownDocumentPreview: theme-css fetch failed, rendering without theme',
          e,
        );
      }
    }

    onMounted(() => {
      if (props.documentId) void loadThemeCss(props.documentId);
    });

    // Both inputs matter: a different document obviously, and a changed
    // front matter on the same document — that is where `theme:` is edited.
    watch(
      () => [props.documentId, frontMatterOf(props.source)] as const,
      ([newId]) => {
        if (newId) void loadThemeCss(newId);
        else themeCss.value = '';
      },
    );

    return () => {
      const children: import('vue').VNode[] = [];
      // The theme CSS sits in the light DOM, scoped to this root by the
      // server-side CssScopePrefixer (every selector already carries
      // .markdown-document-preview). DOMPurify is NOT involved here —
      // the CSS comes from our own filtered endpoint, not from the
      // markdown body, so the sanitiser's <style>-strip does not apply.
      if (themeCss.value) {
        children.push(h('style', themeCss.value));
      }
      children.push(h(MarkdownView, { source: props.source }));
      return h('div', { class: 'markdown-document-preview' }, children);
    };
  },
};
</script>

<style scoped>
/* Base layout for the preview root. The actual typographic styling of
 * the rendered markdown comes from MarkdownView's own scoped CSS
 * (MarkdownView is the child). The theme CSS, scoped to
 * .markdown-document-preview, layers on top and wins the cascade
 * because the theme <style> is rendered after MarkdownView's scoped
 * styles in document order. */
.markdown-document-preview {
  /* Cap the rendered document at a readable line length and centre it
   * inside the Cortex view pane, which can be far wider than prose
   * wants to be. With max-width + auto margins the content still fills
   * the pane on narrow viewports and only narrows + centres once the
   * pane grows past the cap — no media query needed. The cap sits in
   * the common 60–80 characters-per-line readability band while
   * leaving room for code blocks and tables (the latter scroll
   * sideways inside MarkdownView's scoped pre/table styles).
   *
   * The theme CSS is server-side prefix-scoped to this class, but it
   * targets child elements (h1, p, code, …) — it never sets properties
   * on the root itself, so the cap and centring are safe from theme
   * overrides. Print is a separate layer (style/print.css, @media
   * print) and unaffected. */
  max-width: 50rem;
  margin-inline: auto;
  font-size: 0.95rem;
  line-height: 1.55;
  word-break: break-word;
}

/* Hide the YAML front-matter strip that MarkdownView renders above the
 * body. In the preview surface the front matter is configuration
 * metadata (theme:/css: keys), not report content — showing it above
 * the rendered document reads as clutter. MarkdownView keeps it for
 * chat / inbox / search hits where the metadata is contextual; here it
 * is suppressed via :deep() (the strip lives inside the MarkdownView
 * child's scoped boundary). The body itself is unaffected — the
 * front matter was already stripped from the rendered markdown by
 * MarkdownView's extractFrontmatter, this only hides the chip strip. */
.markdown-document-preview :deep(.markdown-view__frontmatter) {
  display: none;
}
</style>
