import { computed, defineComponent, h, inject } from 'vue';
import { marked } from 'marked';
import DOMPurify from 'dompurify';
import InlineKindBox from './InlineKindBox.vue';
import EmbeddedKindBox from './EmbeddedKindBox.vue';
import LinkCard from './LinkCard.vue';
import { hasRenderer } from '@/kindRenderers/registry';
import { parseFenceLang } from '@/kindRenderers/parseFenceLang';
import { isVanceUri, parseVanceUri } from '@/kindRenderers/parseVanceUri';
import { useDocumentRefStore } from '@/document/documentRefStore';
import { getOpenDocumentsInNewTab } from '@/platform/webUiSession';
import { VANCE_LINK_HANDLER_KEY } from './vanceLinkHandler';
// Re-export the host-interception contract from its dedicated module so
// existing import paths (`from '@/components/MarkdownView.vue'` and the
// `@/components` barrel) keep working — the symbol itself lives in
// {@link ./vanceLinkHandler} to dodge the circular import with
// {@link EmbeddedKindBox}.
export { VANCE_LINK_HANDLER_KEY, } from './vanceLinkHandler';
marked.setOptions({
    gfm: true,
    breaks: true,
});
/**
 * Markdown links inside chat content never address the Face UI itself
 * — a relative href like `documents/coding-modelle-vergleich.md` is
 * always meant as a Vance Document reference. Rewrite such hrefs to
 * the `vance:` scheme so they flow through the same EmbeddedKindBox /
 * click-delegation path as an explicit `vance:/...` URI would.
 *
 * Pass through:
 * - anything with an explicit scheme (http, https, mailto, tel, vance, …)
 * - fragment-only links (#section)
 * - protocol-relative URLs (//example.com/…)
 */
function rewriteHrefIfRelative(href) {
    if (!href)
        return href ?? '';
    const trimmed = href.trim();
    if (!trimmed)
        return href;
    if (/^[a-z][a-z0-9+.\-]*:/i.test(trimmed))
        return href;
    if (trimmed.startsWith('#') || trimmed.startsWith('//'))
        return href;
    const path = trimmed.replace(/^(\.\/)+/, '').replace(/^\/+/, '');
    return `vance:/${path}`;
}
// Force external http(s) links to open in a new tab. `vance:` URIs are
// handled by the click delegation below (preventDefault + manual nav),
// so target/rel on them would be inert anyway — we skip the attribute
// to keep the markup tidy. Internal protocol-relative or relative URLs
// (rare in chat) stay same-tab so deep-link UX inside the app survives.
marked.use({
    renderer: {
        link({ href, title, tokens }) {
            // eslint-disable-next-line @typescript-eslint/no-explicit-any
            const text = this.parser.parseInline(tokens);
            const resolvedHref = rewriteHrefIfRelative(href);
            const isExternal = /^https?:\/\//i.test(resolvedHref);
            const titleAttr = title ? ` title="${title.replace(/"/g, '&quot;')}"` : '';
            const targetAttr = isExternal ? ' target="_blank" rel="noopener noreferrer"' : '';
            return `<a href="${resolvedHref}"${titleAttr}${targetAttr}>${text}</a>`;
        },
    },
});
/**
 * Walks the token tree and rewrites relative-style hrefs on
 * `link`/`image` tokens via {@link rewriteHrefIfRelative}. Normalising
 * at the token layer means the {@link isVanceLinkParagraph} /
 * {@link isVanceMediaList} checks see the same `vance:` URI as the
 * link renderer below, so a paragraph that's just
 * `[Doc](documents/foo.md)` still routes to {@link EmbeddedKindBox}.
 */
function normalizeRelativeHrefs(tokens) {
    for (const t of tokens) {
        if (t.type === 'link' || t.type === 'image') {
            const lt = t;
            lt.href = rewriteHrefIfRelative(lt.href);
        }
        // eslint-disable-next-line @typescript-eslint/no-explicit-any
        const anyT = t;
        if (Array.isArray(anyT.tokens))
            normalizeRelativeHrefs(anyT.tokens);
        if (Array.isArray(anyT.items)) {
            for (const item of anyT.items) {
                if (item && Array.isArray(item.tokens)) {
                    normalizeRelativeHrefs(item.tokens);
                }
            }
        }
        if (Array.isArray(anyT.header)) {
            for (const cell of anyT.header) {
                if (cell && Array.isArray(cell.tokens)) {
                    normalizeRelativeHrefs(cell.tokens);
                }
            }
        }
        if (Array.isArray(anyT.rows)) {
            for (const row of anyT.rows) {
                if (!Array.isArray(row))
                    continue;
                for (const cell of row) {
                    if (cell && Array.isArray(cell.tokens)) {
                        normalizeRelativeHrefs(cell.tokens);
                    }
                }
            }
        }
    }
}
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
/**
 * Find a single {@code vance:}-href image / link inside a list-item's
 * token tree. Returns the token if the item collapses to exactly one
 * vance-URI media reference (with only whitespace alongside it);
 * otherwise {@code null}. Mixed items (text + image) fall through to
 * normal Markdown rendering — those still produce a broken
 * {@code <img src="vance:…">} but are rare in practice.
 */
function findSoleVanceMediaInListItem(item) {
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    const itemTokens = item.tokens;
    if (!itemTokens)
        return null;
    // Each list item typically wraps its inline content in a single
    // `text` token whose nested `tokens` carry the real Markdown
    // inlines (link / image / em / strong / …). Drill one level when
    // that's the shape, otherwise scan the item-tokens directly.
    let inlineTokens = itemTokens;
    if (itemTokens.length === 1 && itemTokens[0].type === 'text') {
        const inner = itemTokens[0].tokens;
        if (inner)
            inlineTokens = inner;
    }
    let media = null;
    for (const t of inlineTokens) {
        if (t.type === 'text') {
            const txt = (t.text ?? '').trim();
            if (txt.length > 0)
                return null;
            continue;
        }
        if ((t.type === 'link' || t.type === 'image') &&
            isVanceUri(t.href)) {
            if (media)
                return null;
            media = t;
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
function isVanceMediaList(token) {
    if (token.type !== 'list')
        return false;
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    const items = token.items;
    if (!items || items.length === 0)
        return false;
    for (const item of items) {
        if (findSoleVanceMediaInListItem(item))
            return true;
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
            const items = token.items;
            flushHtmlBuffer(buffer, out);
            const leftover = [];
            for (const item of items) {
                const media = findSoleVanceMediaInListItem(item);
                if (!media) {
                    leftover.push(item);
                    continue;
                }
                if (leftover.length > 0) {
                    // eslint-disable-next-line @typescript-eslint/no-explicit-any
                    flushHtmlBuffer([{ ...token, items: leftover }], out);
                    leftover.length = 0;
                }
                const isImage = media.type === 'image';
                const text = isImage
                    ? (media.text ?? '')
                    : tokensToText(media.tokens ?? []);
                try {
                    const embedRef = parseVanceUri(media.href, {
                        text,
                        imageStyle: isImage,
                    });
                    out.push(h(EmbeddedKindBox, { embedRef }));
                }
                catch (e) {
                    console.warn('MarkdownView: failed to parse vance: URI', e);
                }
            }
            if (leftover.length > 0) {
                // eslint-disable-next-line @typescript-eslint/no-explicit-any
                flushHtmlBuffer([{ ...token, items: leftover }], out);
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
        const vanceLinkHandler = inject(VANCE_LINK_HANDLER_KEY, null);
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
            normalizeRelativeHrefs(tokens);
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
            // Plain-click interception — Cortex (or another host) can take
            // ownership and open the doc in-place. Cmd/Ctrl/Shift-click is
            // always treated as "I really want a new browser tab" and goes
            // through the default path below so the host can't trap the
            // user. The handler returns truthy to claim the click.
            if (vanceLinkHandler && !newTab) {
                try {
                    const handled = await vanceLinkHandler({
                        documentId,
                        projectId,
                        embedRef,
                        newTab,
                    });
                    if (handled)
                        return;
                }
                catch (e) {
                    console.warn('MarkdownView: vance link handler threw', e);
                    // Fall through to default navigation rather than swallow.
                }
            }
            const url = `/documents.html?projectId=${encodeURIComponent(projectId)}`
                + `&documentId=${encodeURIComponent(documentId)}`;
            // Hosts that provide a {@link VanceLinkHandler} (Cortex) keep
            // their existing behaviour even when the handler decided not to
            // claim this click — the page already gives the user a tab-aware
            // surface, so opening a doc in a new browser tab from there would
            // be surprising. Everywhere else (chat, inbox, …) honour the
            // user-level `webui.document.openInNewTab` preference (default
            // true) so a `vance:`-link click doesn't blow away the current
            // page.
            const preferNewTab = !vanceLinkHandler && getOpenDocumentsInNewTab();
            if (newTab || preferNewTab) {
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
/** @type {__VLS_StyleScopedClasses['markdown-view']} */ ;
/** @type {__VLS_StyleScopedClasses['markdown-view']} */ ;
/** @type {__VLS_StyleScopedClasses['markdown-view']} */ ;
/** @type {__VLS_StyleScopedClasses['markdown-view']} */ ;
/** @type {__VLS_StyleScopedClasses['markdown-view--inline']} */ ;
/** @type {__VLS_StyleScopedClasses['markdown-view--inline']} */ ;
let __VLS_self;
//# sourceMappingURL=MarkdownView.vue.js.map