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
import { hasRenderer } from '@/kindRenderers/registry';
import { parseFenceLang } from '@/kindRenderers/parseFenceLang';
import { isVanceUri, parseVanceUri } from '@/kindRenderers/parseVanceUri';

marked.setOptions({
  gfm: true,
  breaks: true,
});

function renderHtmlForTokens(tokens: Tokens.Generic[]): string {
  if (tokens.length === 0) return '';
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  const list = tokens as any;
  if (!list.links) list.links = {};
  const raw = marked.parser(list) as string;
  return DOMPurify.sanitize(raw, { USE_PROFILES: { html: true } });
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

function tokensToText(tokens: Tokens.Generic[]): string {
  return tokens.map((t) => (t as Tokens.Text).text ?? '').join('');
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
    const inlineHtml = computed<string>(() => {
      const src = props.source ?? '';
      if (!src) return '';
      const raw = marked.parseInline(src) as string;
      return DOMPurify.sanitize(raw, { USE_PROFILES: { html: true } });
    });

    const blockNodes = computed<VNode[]>(() => {
      const src = props.source ?? '';
      if (!src) return [];
      const tokens = marked.lexer(src);
      return vnodesForTokens(tokens as Tokens.Generic[]);
    });

    return () => {
      if (props.inline) {
        return h('div', {
          class: ['markdown-view', 'markdown-view--inline'],
          innerHTML: inlineHtml.value,
        });
      }
      return h('div', { class: 'markdown-view' }, blockNodes.value);
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
