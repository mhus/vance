<script lang="ts">
/**
 * Markdown renderer with Vance-specific rich-content dispatch.
 *
 * - Standard Markdown via {@code marked} + DOMPurify (block + inline).
 * - Code blocks with a kind tag → {@link InlineKindBox} (canvas
 *   rendering by registry).
 * - Markdown links / images with {@code vance:} URI → {@link
 *   EmbeddedKindBox} (Document resolved via store).
 *
 * Uses a render function rather than a template because the
 * walked block stream is a heterogeneous array of VNodes
 * (component instances + sanitized-HTML chunks), which Vue templates
 * can't iterate over via `<component :is="vnode">` directly.
 *
 * Spec: specification/inline-and-embedded-content.md §11.7.
 */
import { computed, defineComponent, h, type PropType, type VNode } from 'vue';
import { marked, type Tokens } from 'marked';
import DOMPurify from 'dompurify';
import InlineKindBox from './InlineKindBox.vue';
import EmbeddedKindBox from './EmbeddedKindBox.vue';
import LinkCard from './LinkCard.vue';
import { hasRenderer } from '@/kindRenderers/registry';
import { parseFenceLang } from '@/kindRenderers/parseFenceLang';
import { isVanceUri, parseVanceUri } from '@/kindRenderers/parseVanceUri';
import { useDocumentRefStore } from '@/document/documentRefStore';

marked.setOptions({
  gfm: true,
  breaks: true,
});

// Force external http(s) links to open in a new tab. `vance:` URIs are
// handled by the click delegation below (preventDefault + manual nav),
// so target/rel on them would be inert anyway — we skip the attribute
// to keep the markup tidy. Internal protocol-relative or relative URLs
// (rare in chat) stay same-tab so deep-link UX inside the app survives.
marked.use({
  renderer: {
    link({ href, title, tokens }) {
      // eslint-disable-next-line @typescript-eslint/no-explicit-any
      const text = (this as any).parser.parseInline(tokens);
      const isExternal = /^https?:\/\//i.test(href);
      const titleAttr = title ? ` title="${title.replace(/"/g, '&quot;')}"` : '';
      const targetAttr = isExternal ? ' target="_blank" rel="noopener noreferrer"' : '';
      return `<a href="${href}"${titleAttr}${targetAttr}>${text}</a>`;
    },
  },
});

// DOMPurify's default URI allowlist (http/https/mailto/tel/cid/xmpp/…)
// strips the href off any other scheme. Inline `vance:` links — Markdown
// like `… see [Doc title](vance:/documents/foo.md?kind=document) …` —
// would render as anchors without an href and be unclickable. We extend
// the regex with `vance:` so the attribute survives sanitisation, then
// the click delegation below intercepts navigation client-side and
// routes through the document store / documents editor.
//
// The leading `(?:f|ht)tps?|mailto|…|vance` block mirrors DOMPurify's
// own default — keep it in sync if upstream changes (no programmatic
// way to "append a scheme to the default allowlist").
const ALLOWED_URI_REGEXP =
  /^(?:(?:(?:f|ht)tps?|mailto|tel|callto|sms|cid|xmpp|vance):|[^a-z]|[a-z+.\-]+(?:[^a-z+.\-:]|$))/i;

const SANITIZE_CONFIG = {
  USE_PROFILES: { html: true },
  ALLOWED_URI_REGEXP,
} as const;

function renderHtmlForTokens(tokens: Tokens.Generic[]): string {
  if (tokens.length === 0) return '';
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  const list = tokens as any;
  if (!list.links) list.links = {};
  const raw = marked.parser(list) as string;
  return DOMPurify.sanitize(raw, SANITIZE_CONFIG);
}

function flushHtmlBuffer(buffer: Tokens.Generic[], out: VNode[]): void {
  if (buffer.length === 0) return;
  const html = renderHtmlForTokens(buffer);
  buffer.length = 0;
  if (!html) return;
  out.push(h('div', { class: 'markdown-view__chunk', innerHTML: html }));
}

function isInlineFenceToken(token: Tokens.Generic): boolean {
  if (token.type !== 'code') return false;
  const codeTok = token as Tokens.Code;
  return !!codeTok.lang && codeTok.lang.trim().length > 0;
}

function isVanceLinkParagraph(token: Tokens.Generic): boolean {
  if (token.type !== 'paragraph') return false;
  const para = token as Tokens.Paragraph;
  const inner = para.tokens ?? [];
  const significant = inner.filter(
    (t) => t.type !== 'text' || ((t as Tokens.Text).text ?? '').trim().length > 0,
  );
  if (significant.length === 0) return false;
  if (significant.length === 1) {
    const t = significant[0];
    return (
      (t.type === 'link' || t.type === 'image') &&
      isVanceUri((t as Tokens.Link | Tokens.Image).href)
    );
  }
  return false;
}

/**
 * Find a single {@code vance:}-href image / link inside a list-item's
 * token tree. Returns the token if the item collapses to exactly one
 * vance-URI media reference (with only whitespace alongside it);
 * otherwise {@code null}. Mixed items (text + image) fall through to
 * normal Markdown rendering — those still produce a broken
 * {@code <img src="vance:…">} but are rare in practice.
 */
function findSoleVanceMediaInListItem(
  item: Tokens.Generic,
): Tokens.Link | Tokens.Image | null {
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  const itemTokens = (item as any).tokens as Tokens.Generic[] | undefined;
  if (!itemTokens) return null;
  // Each list item typically wraps its inline content in a single
  // `text` token whose nested `tokens` carry the real Markdown
  // inlines (link / image / em / strong / …). Drill one level when
  // that's the shape, otherwise scan the item-tokens directly.
  let inlineTokens: Tokens.Generic[] = itemTokens;
  if (itemTokens.length === 1 && itemTokens[0].type === 'text') {
    const inner = (itemTokens[0] as Tokens.Text).tokens;
    if (inner) inlineTokens = inner as Tokens.Generic[];
  }
  let media: Tokens.Link | Tokens.Image | null = null;
  for (const t of inlineTokens) {
    if (t.type === 'text') {
      const txt = ((t as Tokens.Text).text ?? '').trim();
      if (txt.length > 0) return null;
      continue;
    }
    if (
      (t.type === 'link' || t.type === 'image') &&
      isVanceUri((t as Tokens.Link | Tokens.Image).href)
    ) {
      if (media) return null;
      media = t as Tokens.Link | Tokens.Image;
      continue;
    }
    // Any other inline kind (em, strong, codespan, …) breaks the
    // "sole media" rule.
    return null;
  }
  return media;
}

/**
 * Does this list contain at least one item whose only meaningful
 * content is a {@code vance:}-URI link / image? Used to route the
 * whole list through the embedded-channel renderer so the
 * {@code <img src="vance:…">} (which the browser can't load) never
 * gets emitted.
 */
function isVanceMediaList(token: Tokens.Generic): boolean {
  if (token.type !== 'list') return false;
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  const items = (token as any).items as Tokens.Generic[] | undefined;
  if (!items || items.length === 0) return false;
  for (const item of items) {
    if (findSoleVanceMediaInListItem(item)) return true;
  }
  return false;
}

function tokensToText(tokens: Tokens.Generic[]): string {
  return tokens.map((t) => (t as Tokens.Text).text ?? '').join('');
}

/**
 * Collects unique external http(s) URLs referenced as Markdown
 * links anywhere inside {@code token}. Used to render Slack-style
 * preview cards underneath the block that produced them — the
 * inline link itself stays clickable, the card is supplementary
 * context.
 *
 * Walks the full token subtree, so links inside list items,
 * blockquotes, or table cells are picked up the same as links
 * directly in a paragraph. (Common pattern: LLMs render link
 * roundups as bullet lists, which marked parses as a top-level
 * {@code list} token with the links nested under
 * {@code items[].tokens}.)
 *
 * Skips:
 * - image-typed tokens — the image renders inline, no card needed
 * - {@code vance:} URIs — handled by the embedded-kind path
 * - non-http schemes (mailto, tel, …)
 * - duplicate URLs inside the same block
 */
function extractExternalUrls(token: Tokens.Generic): string[] {
  const urls: string[] = [];
  const seen = new Set<string>();
  const CAP = 3;

  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  const walk = (ts: Tokens.Generic[] | undefined): void => {
    if (!ts || urls.length >= CAP) return;
    for (const t of ts) {
      if (urls.length >= CAP) return;
      if (t.type === 'link') {
        const href = (t as Tokens.Link).href;
        if (href && /^https?:\/\//i.test(href) && !seen.has(href)) {
          seen.add(href);
          urls.push(href);
          continue;
        }
      }
      // Recurse into children. `paragraph`, `blockquote`, `heading`,
      // `list_item`, `link` all carry a `tokens` array; `list`
      // carries `items[]`, each item with its own `tokens`. Tables
      // hang their cells off `header[].tokens` and `rows[][].tokens`.
      // eslint-disable-next-line @typescript-eslint/no-explicit-any
      const anyT = t as any;
      if (anyT.tokens) walk(anyT.tokens as Tokens.Generic[]);
      if (Array.isArray(anyT.items)) {
        for (const item of anyT.items) {
          if (item && item.tokens) walk(item.tokens as Tokens.Generic[]);
        }
      }
      if (Array.isArray(anyT.header)) {
        for (const cell of anyT.header) {
          if (cell && cell.tokens) walk(cell.tokens as Tokens.Generic[]);
        }
      }
      if (Array.isArray(anyT.rows)) {
        for (const row of anyT.rows) {
          if (!Array.isArray(row)) continue;
          for (const cell of row) {
            if (cell && cell.tokens) walk(cell.tokens as Tokens.Generic[]);
          }
        }
      }
    }
  };
  walk([token]);
  return urls;
}

function vnodesForTokens(tokens: Tokens.Generic[]): VNode[] {
  const out: VNode[] = [];
  const buffer: Tokens.Generic[] = [];

  for (const token of tokens) {
    if (isInlineFenceToken(token)) {
      const codeTok = token as Tokens.Code;
      const parsed = parseFenceLang(codeTok.lang ?? '');
      if (parsed.kind && hasRenderer(parsed.kind)) {
        flushHtmlBuffer(buffer, out);
        out.push(
          h(InlineKindBox, {
            kind: parsed.kind,
            content: codeTok.text ?? '',
            meta: parsed.meta,
          }),
        );
        continue;
      }
      // Unknown kind / no registered renderer → keep as standard
      // Markdown code block (lang-class on <pre><code>).
      buffer.push(token);
      continue;
    }
    if (isVanceLinkParagraph(token)) {
      const para = token as Tokens.Paragraph;
      const linkTok = para.tokens?.find(
        (t) =>
          (t.type === 'link' || t.type === 'image') &&
          isVanceUri((t as Tokens.Link | Tokens.Image).href),
      ) as Tokens.Link | Tokens.Image | undefined;
      if (linkTok) {
        const isImage = linkTok.type === 'image';
        const text = isImage
          ? ((linkTok as Tokens.Image).text ?? '')
          : tokensToText((linkTok as Tokens.Link).tokens ?? []);
        try {
          const embedRef = parseVanceUri((linkTok as Tokens.Link).href, {
            text,
            imageStyle: isImage,
          });
          flushHtmlBuffer(buffer, out);
          out.push(h(EmbeddedKindBox, { embedRef }));
          continue;
        } catch (e) {
          console.warn('MarkdownView: failed to parse vance: URI', e);
        }
      }
      buffer.push(token);
      continue;
    }
    // Lists of vance:-URI media (typical LLM output for image galleries):
    //   - ![alt1](vance:/a.jpg?kind=image)
    //   - ![alt2](vance:/b.jpg?kind=image)
    // Marked would render these as <ul><li><img src="vance:..."></li>…</ul>,
    // and the browser refuses the unknown scheme — broken images. Route
    // each "sole-media" item through EmbeddedKindBox instead; mixed
    // items (text + image) stay in the normal Markdown stream and
    // accept the broken-image artifact as a known limitation.
    if (isVanceMediaList(token)) {
      // eslint-disable-next-line @typescript-eslint/no-explicit-any
      const items = (token as any).items as Tokens.Generic[];
      flushHtmlBuffer(buffer, out);
      const leftover: Tokens.Generic[] = [];
      for (const item of items) {
        const media = findSoleVanceMediaInListItem(item);
        if (!media) {
          leftover.push(item);
          continue;
        }
        if (leftover.length > 0) {
          // eslint-disable-next-line @typescript-eslint/no-explicit-any
          flushHtmlBuffer([{ ...(token as any), items: leftover } as Tokens.Generic], out);
          leftover.length = 0;
        }
        const isImage = media.type === 'image';
        const text = isImage
          ? ((media as Tokens.Image).text ?? '')
          : tokensToText((media as Tokens.Link).tokens ?? []);
        try {
          const embedRef = parseVanceUri((media as Tokens.Link).href, {
            text,
            imageStyle: isImage,
          });
          out.push(h(EmbeddedKindBox, { embedRef }));
        } catch (e) {
          console.warn('MarkdownView: failed to parse vance: URI', e);
        }
      }
      if (leftover.length > 0) {
        // eslint-disable-next-line @typescript-eslint/no-explicit-any
        flushHtmlBuffer([{ ...(token as any), items: leftover } as Tokens.Generic], out);
      }
      continue;
    }
    // External http(s) links in a paragraph get Slack-style preview
    // cards rendered underneath. The paragraph itself still renders
    // as normal Markdown (so the inline link stays clickable); the
    // cards are appended after the paragraph as separate VNodes.
    const externalUrls = extractExternalUrls(token);
    if (externalUrls.length > 0) {
      flushHtmlBuffer(buffer, out);
      buffer.push(token);
      flushHtmlBuffer(buffer, out);
      for (const url of externalUrls) {
        out.push(h(LinkCard, { url, key: `lc:${url}` }));
      }
      continue;
    }
    buffer.push(token);
  }
  flushHtmlBuffer(buffer, out);
  return out;
}

export default defineComponent({
  name: 'MarkdownView',
  props: {
    /** Raw Markdown source. {@code null}/blank renders empty. */
    source: {
      type: [String, null] as unknown as PropType<string | null>,
      default: null,
    },
    /**
     * Compact one-line rendering (no block elements). Skips the
     * token walker — chat-bubble / list-row previews shouldn't grow
     * fence canvases.
     */
    inline: { type: Boolean, default: false },
  },
  setup(props) {
    const documentRefStore = useDocumentRefStore();

    const inlineHtml = computed<string>(() => {
      const src = props.source ?? '';
      if (!src) return '';
      const raw = marked.parseInline(src) as string;
      return DOMPurify.sanitize(raw, SANITIZE_CONFIG);
    });

    const blockNodes = computed<VNode[]>(() => {
      const src = props.source ?? '';
      if (!src) return [];
      const tokens = marked.lexer(src);
      return vnodesForTokens(tokens as Tokens.Generic[]);
    });

    // Click delegation for inline `vance:` links inside the rendered
    // Markdown. The browser doesn't know the scheme — left to itself
    // it would either no-op (modern browsers) or try a protocol
    // handler that doesn't exist. We resolve through the document
    // store to get a concrete documentId, then jump to the documents
    // editor with deep-link params.
    //
    // Whole-paragraph vance: links go through {@code EmbeddedKindBox}
    // higher up (inline preview, no <a> to click) — this path is for
    // links mixed with other inline text or images.
    async function onMarkdownClick(event: MouseEvent): Promise<void> {
      const target = event.target as HTMLElement | null;
      if (!target) return;
      const anchor = target.closest('a[href]') as HTMLAnchorElement | null;
      if (!anchor) return;
      const href = anchor.getAttribute('href') ?? '';
      if (!isVanceUri(href)) return;

      // We're committing to handling this — anything below shouldn't
      // let the browser fall through to its native (broken) handling.
      event.preventDefault();
      const newTab = event.metaKey || event.ctrlKey || event.shiftKey;

      const imageStyle = !!anchor.querySelector('img');
      const text = (anchor.textContent ?? '').trim();
      let embedRef;
      try {
        embedRef = parseVanceUri(href, { text, imageStyle });
      } catch (e) {
        console.warn('MarkdownView: invalid vance: URI on click', href, e);
        return;
      }

      // Wizard-suggestion path: links emitted by the wizard render
      // pipeline ({@code FollowUpRenderer}) carry {@code kind=wizard}
      // plus an opaque prefill query. We dispatch a window event the
      // chat editor picks up to switch the side tab and seed the form
      // — see {@code chat/WizardPanel.vue} and {@code chat/ChatView.vue}.
      if (embedRef.kindHint === 'wizard') {
        try {
          const url = new URL(href);
          const segments = url.pathname.split('/').filter(Boolean);
          // Path shape: `/wizards/<name>` (after stripping the scheme).
          const wizardName = segments[segments.length - 1];
          if (!wizardName) {
            console.warn('MarkdownView: wizard vance: URI missing name', href);
            return;
          }
          const prefill: Record<string, string> = {};
          url.searchParams.forEach((value, key) => {
            if (key === 'kind') return; // discriminator, not a form value
            prefill[key] = value;
          });
          window.dispatchEvent(
            new CustomEvent('vance-open-wizard', {
              detail: { name: decodeURIComponent(wizardName), prefill },
            }),
          );
        } catch (e) {
          console.warn('MarkdownView: failed to parse wizard vance: URI', href, e);
        }
        return;
      }
      let doc;
      try {
        doc = await documentRefStore.resolve(embedRef);
      } catch (e) {
        console.warn('MarkdownView: failed to resolve vance: URI', href, e);
        return;
      }
      const projectId = embedRef.project ?? documentRefStore.currentProject;
      const documentId = doc.id ?? '';
      if (!projectId || !documentId) {
        console.warn('MarkdownView: resolved vance: URI is missing projectId/id', href);
        return;
      }
      const url = `/documents.html?projectId=${encodeURIComponent(projectId)}`
        + `&documentId=${encodeURIComponent(documentId)}`;
      if (newTab) {
        window.open(url, '_blank', 'noopener');
      } else {
        window.location.href = url;
      }
    }

    return () => {
      if (props.inline) {
        return h('div', {
          class: ['markdown-view', 'markdown-view--inline'],
          innerHTML: inlineHtml.value,
          onClick: onMarkdownClick,
        });
      }
      return h(
        'div',
        { class: 'markdown-view', onClick: onMarkdownClick },
        blockNodes.value,
      );
    };
  },
});
</script>

<style scoped>
.markdown-view {
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
/* Tailwind's preflight strips list-style off ul/ol — explicitly
 * restore so bullets / numbers come back in rendered Markdown. */
.markdown-view :deep(ul),
.markdown-view :deep(ol) { margin: 0.5em 0 0.5em 1.4em; padding-left: 0; }
.markdown-view :deep(ul) { list-style: disc outside; }
.markdown-view :deep(ol) { list-style: decimal outside; }
.markdown-view :deep(ul ul) { list-style: circle outside; }
.markdown-view :deep(ul ul ul) { list-style: square outside; }
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

.markdown-view__chunk:not(:first-child) {
  margin-top: 0.25rem;
}

.markdown-view--inline :deep(p),
.markdown-view--inline :deep(ul),
.markdown-view--inline :deep(ol) {
  display: inline;
  margin: 0;
}
</style>
