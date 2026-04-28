import { computed } from 'vue';
import { marked } from 'marked';
import DOMPurify from 'dompurify';
const props = withDefaults(defineProps(), {
    inline: false,
});
// Configure marked once per module load — defaults are safe enough,
// we just want GFM (tables, task-lists, fenced code) and breaks.
marked.setOptions({
    gfm: true,
    breaks: true,
});
const html = computed(() => {
    const src = props.source ?? '';
    if (!src)
        return '';
    // marked's parse can be sync or async depending on extensions. We
    // use no async extensions, so the sync path is guaranteed; cast to
    // string for the strict-typed call site.
    const raw = props.inline
        ? marked.parseInline(src)
        : marked.parse(src);
    // The body is user / LLM content. We must sanitize before
    // injecting via v-html — DOMPurify drops scripts, on*-handlers,
    // javascript: URLs, etc. Keep a tight allow-list of attributes.
    return DOMPurify.sanitize(raw, {
        USE_PROFILES: { html: true },
    });
});
debugger; /* PartiallyEnd: #3632/scriptSetup.vue */
const __VLS_withDefaultsArg = (function (t) { return t; })({
    inline: false,
});
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
// CSS variable injection 
// CSS variable injection end 
__VLS_asFunctionalElement(__VLS_intrinsicElements.div)({
    ...{ class: (['markdown-view', { 'markdown-view--inline': __VLS_ctx.inline }]) },
});
__VLS_asFunctionalDirective(__VLS_directives.vHtml)(null, { ...__VLS_directiveBindingRestFields, value: (__VLS_ctx.html) }, null, null);
/** @type {__VLS_StyleScopedClasses['markdown-view']} */ ;
/** @type {__VLS_StyleScopedClasses['markdown-view--inline']} */ ;
var __VLS_dollars;
const __VLS_self = (await import('vue')).defineComponent({
    setup() {
        return {
            html: html,
        };
    },
    __typeProps: {},
    props: {},
});
export default (await import('vue')).defineComponent({
    setup() {
        return {};
    },
    __typeProps: {},
    props: {},
});
; /* PartiallyEnd: #4569/main.vue */
//# sourceMappingURL=MarkdownView.vue.js.map