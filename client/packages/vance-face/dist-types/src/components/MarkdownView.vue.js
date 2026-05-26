import { computed, defineComponent, h } from 'vue';
import { marked } from 'marked';
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
const ALLOWED_URI_REGEXP = /^(?:(?:(?:f|ht)tps?|mailto|tel|callto|sms|cid|xmpp|vance):|[^a-z]|[a-z+.\-]+(?:[^a-z+.\-:]|$))/i;
const SANITIZE_CONFIG = {
    USE_PROFILES: { html: true },
    ALLOWED_URI_REGEXP,
};
function renderHtmlForTokens(tokens) {
    if (tokens.length === 0)
        return '';
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    const list = tokens;
    if (!list.links)
        list.links = {};
    const raw = marked.parser(list);
    return DOMPurify.sanitize(raw, SANITIZE_CONFIG);
}
function flushHtmlBuffer(buffer, out) {
    if (buffer.length === 0)
        return;
    const html = renderHtmlForTokens(buffer);
    buffer.length = 0;
    if (!html)
        return;
    out.push(h('div', { class: 'markdown-view__chunk', innerHTML: html }));
}
function isInlineFenceToken(token) {
    if (token.type !== 'code')
        return false;
    const codeTok = token;
    return !!codeTok.lang && codeTok.lang.trim().length > 0;
}
function isVanceLinkParagraph(token) {
    if (token.type !== 'paragraph')
        return false;
    const para = token;
    const inner = para.tokens ?? [];
    const significant = inner.filter((t) => t.type !== 'text' || (t.text ?? '').trim().length > 0);
    if (significant.length === 0)
        return false;
    if (significant.length === 1) {
        const t = significant[0];
        return ((t.type === 'link' || t.type === 'image') &&
            isVanceUri(t.href));
    }
    return false;
}
function tokensToText(tokens) {
    return tokens.map((t) => t.text ?? '').join('');
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
function extractExternalUrls(token) {
    const urls = [];
    const seen = new Set();
    const CAP = 3;
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    const walk = (ts) => {
        if (!ts || urls.length >= CAP)
            return;
        for (const t of ts) {
            if (urls.length >= CAP)
                return;
            if (t.type === 'link') {
                const href = t.href;
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
            const anyT = t;
            if (anyT.tokens)
                walk(anyT.tokens);
            if (Array.isArray(anyT.items)) {
                for (const item of anyT.items) {
                    if (item && item.tokens)
                        walk(item.tokens);
                }
            }
            if (Array.isArray(anyT.header)) {
                for (const cell of anyT.header) {
                    if (cell && cell.tokens)
                        walk(cell.tokens);
                }
            }
            if (Array.isArray(anyT.rows)) {
                for (const row of anyT.rows) {
                    if (!Array.isArray(row))
                        continue;
                    for (const cell of row) {
                        if (cell && cell.tokens)
                            walk(cell.tokens);
                    }
                }
            }
        }
    };
    walk([token]);
    return urls;
}
function vnodesForTokens(tokens) {
    const out = [];
    const buffer = [];
    for (const token of tokens) {
        if (isInlineFenceToken(token)) {
            const codeTok = token;
            const parsed = parseFenceLang(codeTok.lang ?? '');
            if (parsed.kind && hasRenderer(parsed.kind)) {
                flushHtmlBuffer(buffer, out);
                out.push(h(InlineKindBox, {
                    kind: parsed.kind,
                    content: codeTok.text ?? '',
                    meta: parsed.meta,
                }));
                continue;
            }
            // Unknown kind / no registered renderer → keep as standard
            // Markdown code block (lang-class on <pre><code>).
            buffer.push(token);
            continue;
        }
        if (isVanceLinkParagraph(token)) {
            const para = token;
            const linkTok = para.tokens?.find((t) => (t.type === 'link' || t.type === 'image') &&
                isVanceUri(t.href));
            if (linkTok) {
                const isImage = linkTok.type === 'image';
                const text = isImage
                    ? (linkTok.text ?? '')
                    : tokensToText(linkTok.tokens ?? []);
                try {
                    const embedRef = parseVanceUri(linkTok.href, {
                        text,
                        imageStyle: isImage,
                    });
                    flushHtmlBuffer(buffer, out);
                    out.push(h(EmbeddedKindBox, { embedRef }));
                    continue;
                }
                catch (e) {
                    console.warn('MarkdownView: failed to parse vance: URI', e);
                }
            }
            buffer.push(token);
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
            type: [String, null],
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
        const inlineHtml = computed(() => {
            const src = props.source ?? '';
            if (!src)
                return '';
            const raw = marked.parseInline(src);
            return DOMPurify.sanitize(raw, SANITIZE_CONFIG);
        });
        const blockNodes = computed(() => {
            const src = props.source ?? '';
            if (!src)
                return [];
            const tokens = marked.lexer(src);
            return vnodesForTokens(tokens);
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
        async function onMarkdownClick(event) {
            const target = event.target;
            if (!target)
                return;
            const anchor = target.closest('a[href]');
            if (!anchor)
                return;
            const href = anchor.getAttribute('href') ?? '';
            if (!isVanceUri(href))
                return;
            // We're committing to handling this — anything below shouldn't
            // let the browser fall through to its native (broken) handling.
            event.preventDefault();
            const newTab = event.metaKey || event.ctrlKey || event.shiftKey;
            const imageStyle = !!anchor.querySelector('img');
            const text = (anchor.textContent ?? '').trim();
            let embedRef;
            try {
                embedRef = parseVanceUri(href, { text, imageStyle });
            }
            catch (e) {
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
                    const prefill = {};
                    url.searchParams.forEach((value, key) => {
                        if (key === 'kind')
                            return; // discriminator, not a form value
                        prefill[key] = value;
                    });
                    window.dispatchEvent(new CustomEvent('vance-open-wizard', {
                        detail: { name: decodeURIComponent(wizardName), prefill },
                    }));
                }
                catch (e) {
                    console.warn('MarkdownView: failed to parse wizard vance: URI', href, e);
                }
                return;
            }
            let doc;
            try {
                doc = await documentRefStore.resolve(embedRef);
            }
            catch (e) {
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
            }
            else {
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
            return h('div', { class: 'markdown-view', onClick: onMarkdownClick }, blockNodes.value);
        };
    },
});
debugger; /* PartiallyEnd: #3632/script.vue */
const __VLS_ctx = {};
let __VLS_components;
let __VLS_directives;
/** @type {__VLS_StyleScopedClasses['markdown-view']} */ ;
/** @type {__VLS_StyleScopedClasses['markdown-view']} */ ;
/** @type {__VLS_StyleScopedClasses['markdown-view']} */ ;
/** @type {__VLS_StyleScopedClasses['markdown-view']} */ ;
/** @type {__VLS_StyleScopedClasses['markdown-view']} */ ;
/** @type {__VLS_StyleScopedClasses['markdown-view']} */ ;
/** @type {__VLS_StyleScopedClasses['markdown-view']} */ ;
/** @type {__VLS_StyleScopedClasses['markdown-view']} */ ;
/** @type {__VLS_StyleScopedClasses['markdown-view']} */ ;
/** @type {__VLS_StyleScopedClasses['markdown-view']} */ ;
/** @type {__VLS_StyleScopedClasses['markdown-view']} */ ;
/** @type {__VLS_StyleScopedClasses['markdown-view']} */ ;
/** @type {__VLS_StyleScopedClasses['markdown-view']} */ ;
/** @type {__VLS_StyleScopedClasses['markdown-view']} */ ;
/** @type {__VLS_StyleScopedClasses['markdown-view']} */ ;
/** @type {__VLS_StyleScopedClasses['markdown-view']} */ ;
/** @type {__VLS_StyleScopedClasses['markdown-view']} */ ;
/** @type {__VLS_StyleScopedClasses['markdown-view']} */ ;
/** @type {__VLS_StyleScopedClasses['markdown-view']} */ ;
/** @type {__VLS_StyleScopedClasses['markdown-view']} */ ;
/** @type {__VLS_StyleScopedClasses['markdown-view']} */ ;
/** @type {__VLS_StyleScopedClasses['markdown-view']} */ ;
/** @type {__VLS_StyleScopedClasses['markdown-view']} */ ;
/** @type {__VLS_StyleScopedClasses['markdown-view']} */ ;
/** @type {__VLS_StyleScopedClasses['markdown-view']} */ ;
/** @type {__VLS_StyleScopedClasses['markdown-view--inline']} */ ;
/** @type {__VLS_StyleScopedClasses['markdown-view--inline']} */ ;
let __VLS_self;
//# sourceMappingURL=MarkdownView.vue.js.map