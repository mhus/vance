import { computed } from 'vue';
import { MarkdownView } from '@components/index';
import { uiTheme, paletteStyle } from '@composables/useUiTheme';
const props = withDefaults(defineProps(), {
    worker: false,
    lineMaxChars: () => uiTheme.lineMaxChars,
    optionsActionable: true,
});
const emit = defineEmits();
const askUserOptions = computed(() => {
    const raw = props.meta?.['askUserOptions'];
    if (!Array.isArray(raw))
        return [];
    const out = [];
    for (const item of raw) {
        if (!item || typeof item !== 'object')
            continue;
        const obj = item;
        const label = obj['label'];
        if (typeof label !== 'string' || !label.trim())
            continue;
        const desc = obj['description'];
        out.push({
            label: label.trim(),
            description: typeof desc === 'string' && desc.trim() ? desc.trim() : undefined,
        });
    }
    return out;
});
const showOptions = computed(() => askUserOptions.value.length > 0);
function onPick(label) {
    if (!props.optionsActionable)
        return;
    emit('pickOption', label);
}
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
// Inline-style overrides from env. Resolved at module load (Vite
// inlines `import.meta.env` at build time), so these don't need to
// be reactive.
const workerStyle = computed(() => paletteStyle(uiTheme.worker));
const userStyle = computed(() => paletteStyle(uiTheme.user));
const assistantStyle = computed(() => paletteStyle(uiTheme.assistant));
const systemStyle = computed(() => paletteStyle(uiTheme.system));
const bubbleStyle = computed(() => {
    if (isUser.value)
        return userStyle.value;
    if (isAssistant.value)
        return assistantStyle.value;
    if (isSystem.value)
        return systemStyle.value;
    return null;
});
debugger; /* PartiallyEnd: #3632/scriptSetup.vue */
const __VLS_withDefaultsArg = (function (t) { return t; })({
    worker: false,
    lineMaxChars: () => uiTheme.lineMaxChars,
    optionsActionable: true,
});
const __VLS_ctx = {};
let __VLS_components;
let __VLS_directives;
if (__VLS_ctx.worker) {
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "flex justify-start" },
    });
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "max-w-[85%] text-xs truncate flex items-center gap-2" },
        ...{ class: (__VLS_ctx.workerStyle ? '' : 'text-success/80') },
        ...{ style: (__VLS_ctx.workerStyle ?? undefined) },
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
                __VLS_ctx.bubbleStyle ? '' : (__VLS_ctx.isUser ? 'bg-primary text-primary-content' : ''),
                __VLS_ctx.bubbleStyle ? '' : (__VLS_ctx.isAssistant ? 'bg-base-100 border border-base-300' : ''),
                __VLS_ctx.bubbleStyle ? '' : (__VLS_ctx.isSystem ? 'bg-base-200 text-sm italic opacity-80' : ''),
            ]) },
        ...{ style: (__VLS_ctx.bubbleStyle ?? undefined) },
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
    if (__VLS_ctx.showOptions) {
        __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
            ...{ class: "mt-3 flex flex-wrap gap-2" },
        });
        for (const [opt] of __VLS_getVForSourceType((__VLS_ctx.askUserOptions))) {
            __VLS_asFunctionalElement(__VLS_intrinsicElements.button, __VLS_intrinsicElements.button)({
                ...{ onClick: (...[$event]) => {
                        if (!!(__VLS_ctx.worker))
                            return;
                        if (!(__VLS_ctx.showOptions))
                            return;
                        __VLS_ctx.onPick(opt.label);
                    } },
                key: (opt.label),
                type: "button",
                disabled: (!__VLS_ctx.optionsActionable),
                ...{ class: "px-3 py-1.5 rounded-lg border text-sm font-medium transition-colors disabled:opacity-50 disabled:cursor-not-allowed" },
                ...{ class: (__VLS_ctx.optionsActionable
                        ? 'border-primary/40 bg-primary/10 hover:bg-primary/20'
                        : 'border-base-300 bg-base-200') },
                title: (opt.description ?? opt.label),
            });
            __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({});
            (opt.label);
            if (opt.description) {
                __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
                    ...{ class: "block text-[10px] font-normal opacity-70 mt-0.5" },
                });
                (opt.description);
            }
        }
    }
}
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['justify-start']} */ ;
/** @type {__VLS_StyleScopedClasses['max-w-[85%]']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
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
/** @type {__VLS_StyleScopedClasses['mt-3']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-wrap']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-2']} */ ;
/** @type {__VLS_StyleScopedClasses['px-3']} */ ;
/** @type {__VLS_StyleScopedClasses['py-1.5']} */ ;
/** @type {__VLS_StyleScopedClasses['rounded-lg']} */ ;
/** @type {__VLS_StyleScopedClasses['border']} */ ;
/** @type {__VLS_StyleScopedClasses['text-sm']} */ ;
/** @type {__VLS_StyleScopedClasses['font-medium']} */ ;
/** @type {__VLS_StyleScopedClasses['transition-colors']} */ ;
/** @type {__VLS_StyleScopedClasses['disabled:opacity-50']} */ ;
/** @type {__VLS_StyleScopedClasses['disabled:cursor-not-allowed']} */ ;
/** @type {__VLS_StyleScopedClasses['block']} */ ;
/** @type {__VLS_StyleScopedClasses['text-[10px]']} */ ;
/** @type {__VLS_StyleScopedClasses['font-normal']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-70']} */ ;
/** @type {__VLS_StyleScopedClasses['mt-0.5']} */ ;
var __VLS_dollars;
const __VLS_self = (await import('vue')).defineComponent({
    setup() {
        return {
            MarkdownView: MarkdownView,
            askUserOptions: askUserOptions,
            showOptions: showOptions,
            onPick: onPick,
            isUser: isUser,
            isAssistant: isAssistant,
            isSystem: isSystem,
            workerText: workerText,
            formatted: formatted,
            workerStyle: workerStyle,
            bubbleStyle: bubbleStyle,
        };
    },
    __typeEmits: {},
    __typeProps: {},
    props: {},
});
export default (await import('vue')).defineComponent({
    setup() {
        return {};
    },
    __typeEmits: {},
    __typeProps: {},
    props: {},
});
; /* PartiallyEnd: #4569/main.vue */
//# sourceMappingURL=MessageBubble.vue.js.map