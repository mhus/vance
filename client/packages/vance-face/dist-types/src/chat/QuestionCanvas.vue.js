import { computed } from 'vue';
import { MarkdownView } from '@components/index';
const props = defineProps();
const emit = defineEmits();
/**
 * Strip the trailing Markdown bullet list that the engine appends as
 * a graceful fallback for non-canvas clients. We render the buttons
 * directly so showing the bullets too is duplication. Heuristic:
 * walk back over consecutive lines that start with `- ` or `* ` and
 * carry one of the option labels; cut once we hit a non-bullet line.
 */
const questionText = computed(() => {
    if (props.options.length === 0)
        return props.content;
    const labels = new Set(props.options.map((o) => o.label.toLowerCase()));
    const lines = props.content.split('\n');
    let cut = lines.length;
    for (let i = lines.length - 1; i >= 0; i--) {
        const ln = lines[i].trimEnd();
        if (ln === '')
            continue;
        const m = /^\s*[-*]\s+(?:\*\*)?([^*]+?)(?:\*\*)?\s*(?:—.*)?$/.exec(ln);
        if (m && labels.has(m[1].trim().toLowerCase())) {
            cut = i;
            continue;
        }
        break;
    }
    return lines.slice(0, cut).join('\n').replace(/\s+$/, '');
});
function onPick(label) {
    if (!props.actionable)
        return;
    emit('pick', label);
}
debugger; /* PartiallyEnd: #3632/scriptSetup.vue */
const __VLS_ctx = {};
let __VLS_components;
let __VLS_directives;
/** @type {__VLS_StyleScopedClasses['question-canvas__option']} */ ;
/** @type {__VLS_StyleScopedClasses['question-canvas__option']} */ ;
/** @type {__VLS_StyleScopedClasses['is-actionable']} */ ;
/** @type {__VLS_StyleScopedClasses['question-canvas__option']} */ ;
// CSS variable injection 
// CSS variable injection end 
__VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
    ...{ class: "question-canvas" },
});
const __VLS_0 = {}.MarkdownView;
/** @type {[typeof __VLS_components.MarkdownView, ]} */ ;
// @ts-ignore
const __VLS_1 = __VLS_asFunctionalComponent(__VLS_0, new __VLS_0({
    source: (__VLS_ctx.questionText),
}));
const __VLS_2 = __VLS_1({
    source: (__VLS_ctx.questionText),
}, ...__VLS_functionalComponentArgsRest(__VLS_1));
__VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
    ...{ class: "question-canvas__options" },
});
for (const [opt] of __VLS_getVForSourceType((__VLS_ctx.options))) {
    __VLS_asFunctionalElement(__VLS_intrinsicElements.button, __VLS_intrinsicElements.button)({
        ...{ onClick: (...[$event]) => {
                __VLS_ctx.onPick(opt.label);
            } },
        key: (opt.label),
        type: "button",
        disabled: (!__VLS_ctx.actionable),
        ...{ class: "question-canvas__option" },
        ...{ class: (__VLS_ctx.actionable ? 'is-actionable' : 'is-stale') },
        title: (opt.description ?? opt.label),
    });
    __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
        ...{ class: "question-canvas__option-label" },
    });
    (opt.label);
    if (opt.description) {
        __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
            ...{ class: "question-canvas__option-desc" },
        });
        (opt.description);
    }
}
/** @type {__VLS_StyleScopedClasses['question-canvas']} */ ;
/** @type {__VLS_StyleScopedClasses['question-canvas__options']} */ ;
/** @type {__VLS_StyleScopedClasses['question-canvas__option']} */ ;
/** @type {__VLS_StyleScopedClasses['question-canvas__option-label']} */ ;
/** @type {__VLS_StyleScopedClasses['question-canvas__option-desc']} */ ;
var __VLS_dollars;
const __VLS_self = (await import('vue')).defineComponent({
    setup() {
        return {
            MarkdownView: MarkdownView,
            questionText: questionText,
            onPick: onPick,
        };
    },
    __typeEmits: {},
    __typeProps: {},
});
export default (await import('vue')).defineComponent({
    setup() {
        return {};
    },
    __typeEmits: {},
    __typeProps: {},
});
; /* PartiallyEnd: #4569/main.vue */
//# sourceMappingURL=QuestionCanvas.vue.js.map