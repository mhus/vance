import { computed } from 'vue';
import CodeEditor from '@components/CodeEditor.vue';
const props = withDefaults(defineProps(), {
    mode: 'inline',
    meta: () => ({}),
});
const sourceText = computed(() => {
    if (props.mode === 'inline')
        return props.content ?? '';
    if (props.mode === 'embedded')
        return props.document?.inlineText ?? '';
    return props.content ?? props.document?.inlineText ?? '';
});
const effectiveMime = computed(() => {
    // Embedded mode: trust the Document's own mime where present.
    const docMime = props.document?.mimeType;
    if (docMime && props.mode === 'embedded')
        return docMime;
    // Otherwise infer from the kind tag.
    return mimeForKind(props.kind);
});
const rows = computed(() => {
    const lines = sourceText.value.split('\n').length;
    // Compact: 4 rows minimum, max 24 — fence blocks shouldn't dwarf
    // the chat. Embedded mode honours the doc length up to 32.
    if (props.mode === 'embedded')
        return Math.min(Math.max(lines, 6), 32);
    return Math.min(Math.max(lines, 4), 24);
});
function mimeForKind(kind) {
    switch ((kind ?? '').toLowerCase()) {
        case 'java': return 'text/x-java';
        case 'python':
        case 'py':
            return 'text/x-python';
        case 'js':
        case 'javascript':
            return 'application/javascript';
        case 'ts':
        case 'typescript':
            return 'application/typescript';
        case 'sql': return 'application/sql';
        case 'json': return 'application/json';
        case 'yaml':
        case 'yml':
            return 'application/yaml';
        case 'xml': return 'application/xml';
        case 'html': return 'text/html';
        case 'css': return 'text/css';
        case 'bash':
        case 'sh':
        case 'shell':
            return 'application/x-sh';
        case 'r': return 'text/x-r';
        case 'markdown':
        case 'md':
            return 'text/markdown';
        default: return 'text/plain';
    }
}
debugger; /* PartiallyEnd: #3632/scriptSetup.vue */
const __VLS_withDefaultsArg = (function (t) { return t; })({
    mode: 'inline',
    meta: () => ({}),
});
const __VLS_ctx = {};
let __VLS_components;
let __VLS_directives;
/** @type {__VLS_StyleScopedClasses['cm-editor']} */ ;
// CSS variable injection 
// CSS variable injection end 
__VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
    ...{ class: "code-view" },
    ...{ class: (`code-view--${__VLS_ctx.mode}`) },
});
/** @type {[typeof CodeEditor, ]} */ ;
// @ts-ignore
const __VLS_0 = __VLS_asFunctionalComponent(CodeEditor, new CodeEditor({
    modelValue: (__VLS_ctx.sourceText),
    mimeType: (__VLS_ctx.effectiveMime),
    rows: (__VLS_ctx.rows),
    readOnly: (true),
}));
const __VLS_1 = __VLS_0({
    modelValue: (__VLS_ctx.sourceText),
    mimeType: (__VLS_ctx.effectiveMime),
    rows: (__VLS_ctx.rows),
    readOnly: (true),
}, ...__VLS_functionalComponentArgsRest(__VLS_0));
/** @type {__VLS_StyleScopedClasses['code-view']} */ ;
var __VLS_dollars;
const __VLS_self = (await import('vue')).defineComponent({
    setup() {
        return {
            CodeEditor: CodeEditor,
            sourceText: sourceText,
            effectiveMime: effectiveMime,
            rows: rows,
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
//# sourceMappingURL=CodeView.vue.js.map