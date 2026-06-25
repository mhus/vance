import { computed } from 'vue';
import katex from 'katex';
import 'katex/dist/katex.min.css';
const props = defineProps();
/**
 * Split the source into segments: math (inline + display) and plain
 * text. The regex captures both {@code $$...$$} / {@code \\[...\\]}
 * (display) and {@code $...$} / {@code \\(...\\)} (inline) delimiters.
 *
 * <p>Escaped dollar signs ({@code \$}) are treated as literal text,
 * not as math delimiters.
 */
function parseSegments(source) {
    if (!source)
        return [];
    const segments = [];
    // Regex: matches $$...$$, \[...\], $...$, \(...\) in priority order.
    // Group 1 = display $$, group 2 = display \[\], group 3 = inline $, group 4 = inline \(\)
    const re = /(\$\$([\s\S]+?)\$\$)|(\\\[([\s\S]+?)\\\])|(\$([^\$\n]+?)\$)|(\\\(([\s\S]+?)\\\))/g;
    let lastIndex = 0;
    let match;
    while ((match = re.exec(source)) !== null) {
        // Text before this math segment
        if (match.index > lastIndex) {
            const text = source.slice(lastIndex, match.index);
            segments.push({ html: renderText(text), isDisplay: false, isError: false });
        }
        // Extract the math content and mode
        let math;
        let displayMode;
        if (match[2] !== undefined) {
            // $$...$$
            math = match[2];
            displayMode = true;
        }
        else if (match[4] !== undefined) {
            // \[...\]
            math = match[4];
            displayMode = true;
        }
        else if (match[6] !== undefined) {
            // $...$
            math = match[6];
            displayMode = false;
        }
        else {
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
        }
        catch (e) {
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
function escapeHtml(text) {
    return text
        .replace(/&/g, '&amp;')
        .replace(/</g, '&lt;')
        .replace(/>/g, '&gt;');
}
/**
 * Escape HTML, then wrap TeX commands ({@code \\word}) in a gray span
 * so they are visually distinct from plain prose in the preview.
 */
function renderText(text) {
    const escaped = escapeHtml(text);
    // \ followed by letters (and optional trailing *), e.g. \section, \LaTeX, \textbf
    return escaped.replace(/\\([a-zA-Z@]+)\*?/g, '<span class="tex-cmd">\\$1*</span>');
}
const segments = computed(() => parseSegments(props.source));
/** Count of math segments (inline + display) for the status bar. */
const mathCount = computed(() => segments.value.filter((s) => !s.isError && s.html.includes('katex')).length);
const errorCount = computed(() => segments.value.filter((s) => s.isError).length);
debugger; /* PartiallyEnd: #3632/scriptSetup.vue */
const __VLS_ctx = {};
let __VLS_components;
let __VLS_directives;
// CSS variable injection 
// CSS variable injection end 
__VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
    ...{ class: "tex-preview h-full overflow-auto px-6 py-4" },
});
__VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
    ...{ class: "text-xs opacity-50 mb-3 flex gap-4" },
});
__VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({});
(__VLS_ctx.mathCount);
(__VLS_ctx.mathCount === 1 ? '' : 's');
if (__VLS_ctx.errorCount) {
    __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
        ...{ class: "text-error" },
    });
    (__VLS_ctx.errorCount);
    (__VLS_ctx.errorCount === 1 ? '' : 's');
}
__VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
    ...{ class: "italic" },
});
__VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
    ...{ class: "tex-preview-body" },
});
for (const [seg, i] of __VLS_getVForSourceType((__VLS_ctx.segments))) {
    (i);
    if (seg.isDisplay) {
        __VLS_asFunctionalElement(__VLS_intrinsicElements.div)({
            ...{ class: "tex-display-math my-2 text-center" },
        });
        __VLS_asFunctionalDirective(__VLS_directives.vHtml)(null, { ...__VLS_directiveBindingRestFields, value: (seg.html) }, null, null);
    }
    else {
        __VLS_asFunctionalElement(__VLS_intrinsicElements.span)({});
        __VLS_asFunctionalDirective(__VLS_directives.vHtml)(null, { ...__VLS_directiveBindingRestFields, value: (seg.html) }, null, null);
    }
}
if (__VLS_ctx.segments.length === 0) {
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "opacity-40 italic text-sm" },
    });
}
/** @type {__VLS_StyleScopedClasses['tex-preview']} */ ;
/** @type {__VLS_StyleScopedClasses['h-full']} */ ;
/** @type {__VLS_StyleScopedClasses['overflow-auto']} */ ;
/** @type {__VLS_StyleScopedClasses['px-6']} */ ;
/** @type {__VLS_StyleScopedClasses['py-4']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-50']} */ ;
/** @type {__VLS_StyleScopedClasses['mb-3']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-4']} */ ;
/** @type {__VLS_StyleScopedClasses['text-error']} */ ;
/** @type {__VLS_StyleScopedClasses['italic']} */ ;
/** @type {__VLS_StyleScopedClasses['tex-preview-body']} */ ;
/** @type {__VLS_StyleScopedClasses['tex-display-math']} */ ;
/** @type {__VLS_StyleScopedClasses['my-2']} */ ;
/** @type {__VLS_StyleScopedClasses['text-center']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-40']} */ ;
/** @type {__VLS_StyleScopedClasses['italic']} */ ;
/** @type {__VLS_StyleScopedClasses['text-sm']} */ ;
var __VLS_dollars;
const __VLS_self = (await import('vue')).defineComponent({
    setup() {
        return {
            segments: segments,
            mathCount: mathCount,
            errorCount: errorCount,
        };
    },
    __typeProps: {},
});
export default (await import('vue')).defineComponent({
    setup() {
        return {};
    },
    __typeProps: {},
});
; /* PartiallyEnd: #4569/main.vue */
//# sourceMappingURL=TexPreview.vue.js.map