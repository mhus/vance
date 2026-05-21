import { computed, defineComponent, h } from 'vue';
import { marked } from 'marked';
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
function renderHtmlForTokens(tokens) {
    if (tokens.length === 0)
        return '';
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    const list = tokens;
    if (!list.links)
        list.links = {};
    const raw = marked.parser(list);
    return DOMPurify.sanitize(raw, { USE_PROFILES: { html: true } });
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
        const inlineHtml = computed(() => {
            const src = props.source ?? '';
            if (!src)
                return '';
            const raw = marked.parseInline(src);
            return DOMPurify.sanitize(raw, { USE_PROFILES: { html: true } });
        });
        const blockNodes = computed(() => {
            const src = props.source ?? '';
            if (!src)
                return [];
            const tokens = marked.lexer(src);
            return vnodesForTokens(tokens);
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