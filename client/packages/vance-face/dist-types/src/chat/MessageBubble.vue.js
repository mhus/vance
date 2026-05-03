import { computed } from 'vue';
import { MarkdownView } from '@components/index';
const props = withDefaults(defineProps(), {
    worker: false,
    lineMaxChars: 140,
});
const isUser = computed(() => props.role === 'USER');
const isAssistant = computed(() => props.role === 'ASSISTANT');
const isSystem = computed(() => props.role === 'SYSTEM');
const workerText = computed(() => {
    if (!props.worker)
        return '';
    const max = props.lineMaxChars;
    // Collapse newlines so a long multi-line worker reply stays one
    // visual row — the user only needs the gist; full content is in
    // the engine's own log if they want detail.
    const flat = props.content.replace(/\s+/g, ' ').trim();
    if (max <= 0 || flat.length <= max)
        return flat;
    return flat.slice(0, Math.max(0, max - 3)) + '...';
});
const formatted = computed(() => {
    if (!props.createdAt)
        return '';
    const d = props.createdAt instanceof Date ? props.createdAt : new Date(props.createdAt);
    if (isNaN(d.getTime()))
        return '';
    return d.toLocaleTimeString();
});
debugger; /* PartiallyEnd: #3632/scriptSetup.vue */
const __VLS_withDefaultsArg = (function (t) { return t; })({
    worker: false,
    lineMaxChars: 140,
});
const __VLS_ctx = {};
let __VLS_components;
let __VLS_directives;
if (__VLS_ctx.worker) {
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "flex justify-start" },
    });
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "max-w-[85%] text-xs text-success/80 truncate flex items-center gap-2" },
    });
    __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
        ...{ class: "font-mono opacity-70" },
    });
    (__VLS_ctx.processName ?? '?');
    (String(__VLS_ctx.role).toLowerCase());
    __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
        ...{ class: "truncate" },
    });
    (__VLS_ctx.workerText);
    if (__VLS_ctx.streaming) {
        __VLS_asFunctionalElement(__VLS_intrinsicElements.span)({
            ...{ class: "inline-block w-1.5 h-1.5 rounded-full bg-success animate-pulse shrink-0" },
        });
    }
}
else {
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "flex" },
        ...{ class: (__VLS_ctx.isUser ? 'justify-end' : 'justify-start') },
    });
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "max-w-[85%] rounded-2xl px-4 py-2.5 shadow-sm" },
        ...{ class: ([
                __VLS_ctx.isUser ? 'bg-primary text-primary-content' : '',
                __VLS_ctx.isAssistant ? 'bg-base-100 border border-base-300' : '',
                __VLS_ctx.isSystem ? 'bg-base-200 text-sm italic opacity-80' : '',
            ]) },
    });
    if (!__VLS_ctx.isUser) {
        __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
            ...{ class: "text-xs opacity-60 mb-1 flex items-center gap-2" },
        });
        __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({});
        (String(__VLS_ctx.role).toLowerCase());
        if (__VLS_ctx.streaming) {
            __VLS_asFunctionalElement(__VLS_intrinsicElements.span)({
                ...{ class: "inline-block w-1.5 h-1.5 rounded-full bg-success animate-pulse" },
            });
        }
        if (__VLS_ctx.formatted) {
            __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
                ...{ class: "opacity-60" },
            });
            (__VLS_ctx.formatted);
        }
    }
    const __VLS_0 = {}.MarkdownView;
    /** @type {[typeof __VLS_components.MarkdownView, ]} */ ;
    // @ts-ignore
    const __VLS_1 = __VLS_asFunctionalComponent(__VLS_0, new __VLS_0({
        source: (__VLS_ctx.content),
    }));
    const __VLS_2 = __VLS_1({
        source: (__VLS_ctx.content),
    }, ...__VLS_functionalComponentArgsRest(__VLS_1));
}
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['justify-start']} */ ;
/** @type {__VLS_StyleScopedClasses['max-w-[85%]']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['text-success/80']} */ ;
/** @type {__VLS_StyleScopedClasses['truncate']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['items-center']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-2']} */ ;
/** @type {__VLS_StyleScopedClasses['font-mono']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-70']} */ ;
/** @type {__VLS_StyleScopedClasses['truncate']} */ ;
/** @type {__VLS_StyleScopedClasses['inline-block']} */ ;
/** @type {__VLS_StyleScopedClasses['w-1.5']} */ ;
/** @type {__VLS_StyleScopedClasses['h-1.5']} */ ;
/** @type {__VLS_StyleScopedClasses['rounded-full']} */ ;
/** @type {__VLS_StyleScopedClasses['bg-success']} */ ;
/** @type {__VLS_StyleScopedClasses['animate-pulse']} */ ;
/** @type {__VLS_StyleScopedClasses['shrink-0']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['max-w-[85%]']} */ ;
/** @type {__VLS_StyleScopedClasses['rounded-2xl']} */ ;
/** @type {__VLS_StyleScopedClasses['px-4']} */ ;
/** @type {__VLS_StyleScopedClasses['py-2.5']} */ ;
/** @type {__VLS_StyleScopedClasses['shadow-sm']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-60']} */ ;
/** @type {__VLS_StyleScopedClasses['mb-1']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['items-center']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-2']} */ ;
/** @type {__VLS_StyleScopedClasses['inline-block']} */ ;
/** @type {__VLS_StyleScopedClasses['w-1.5']} */ ;
/** @type {__VLS_StyleScopedClasses['h-1.5']} */ ;
/** @type {__VLS_StyleScopedClasses['rounded-full']} */ ;
/** @type {__VLS_StyleScopedClasses['bg-success']} */ ;
/** @type {__VLS_StyleScopedClasses['animate-pulse']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-60']} */ ;
var __VLS_dollars;
const __VLS_self = (await import('vue')).defineComponent({
    setup() {
        return {
            MarkdownView: MarkdownView,
            isUser: isUser,
            isAssistant: isAssistant,
            isSystem: isSystem,
            workerText: workerText,
            formatted: formatted,
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
//# sourceMappingURL=MessageBubble.vue.js.map