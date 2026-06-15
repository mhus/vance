import { computed } from 'vue';
import { MarkdownView } from '@components/index';
import { uiTheme, paletteStyle } from '@composables/useUiTheme';
import QuestionCanvas from './QuestionCanvas.vue';
// Action-type values mirror constants in
// {@code ChatMessageDocument.ACTION_TYPE_*}.
const ACTION_TYPE_ASK_USER = 'ASK_USER';
const ACTION_TYPE_REJECT = 'REJECT';
const ACTION_TYPE_WAIT = 'WAIT';
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
/**
 * Engine-action type from {@code meta.actionType}. Drives the
 * render-mode dispatch — see spec §11. Absent on USER messages,
 * fallback-text replies (LLM emitted raw text instead of an
 * arthur_action / eddie_action tool call), and legacy messages
 * persisted before the actionType tagging landed.
 */
const actionType = computed(() => {
    const v = props.meta?.['actionType'];
    return typeof v === 'string' && v.trim() ? v.trim().toUpperCase() : null;
});
const isAskUser = computed(() => actionType.value === ACTION_TYPE_ASK_USER || askUserOptions.value.length > 0);
const isReject = computed(() => actionType.value === ACTION_TYPE_REJECT);
const isWait = computed(() => actionType.value === ACTION_TYPE_WAIT);
function onPick(label) {
    if (!props.optionsActionable)
        return;
    emit('pickOption', label);
}
const isUser = computed(() => props.role === 'USER');
const isAssistant = computed(() => props.role === 'ASSISTANT');
const isSystem = computed(() => props.role === 'SYSTEM');
/**
 * True when the message contains rich-content artifacts (fenced code
 * blocks with a kind tag, or {@code vance:} Markdown links). Such
 * messages get a full-width bubble so the {@code <KindBox>} canvas
 * (mindmap, table, graph, PDF preview, …) is readable rather than
 * squeezed into the chat's default {@code max-w-[85%]}. See
 * specification/inline-and-embedded-content.md §11.6.
 */
const hasRichContent = computed(() => {
    const src = props.content;
    if (!src)
        return false;
    // Fenced block with non-empty lang tag (the kind discriminator).
    if (/^ {0,3}```[A-Za-z][\w-]*/m.test(src))
        return true;
    // Markdown link or image with vance: URI.
    if (/!?\[[^\]]*\]\(vance:/.test(src))
        return true;
    return false;
});
/**
 * Display-form of the message body. Replaces the engine-internal
 * "--- BEGIN/END CHILD REPLY ---" framing that
 * {@code ParentNotificationListener.enrichWithLastReply} emits in
 * {@code <process-event>} markers with compact visual chevrons
 * (>>>> / <<<<). Also drops the diagnostic lead-in line that
 * accompanies the BEGIN marker. New RELAY-output is already stripped
 * server-side by Arthur/Eddie's {@code unwrapChildReply}; this is
 * the safety net for historical chat-messages and for any fallback
 * paths where the LLM regurgitates the marker as content.
 */
const displayContent = computed(() => {
    const src = props.content;
    if (!src)
        return '';
    return src
        .replace(/Last assistant reply from this child \(verbatim\):\s*\n?/g, '')
        .replace(/\n?-{3,}\s*BEGIN CHILD REPLY\s*-{3,}\n?/g, '\n\n>>>>\n')
        .replace(/\n?-{3,}\s*END CHILD REPLY\s*-{3,}\n?/g, '\n<<<<\n\n');
});
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
        ...{ class: "rounded-2xl px-4 py-2.5 shadow-sm" },
        ...{ class: ([
                __VLS_ctx.hasRichContent ? 'w-full' : 'max-w-[85%]',
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
    if (__VLS_ctx.isAskUser) {
        /** @type {[typeof QuestionCanvas, ]} */ ;
        // @ts-ignore
        const __VLS_0 = __VLS_asFunctionalComponent(QuestionCanvas, new QuestionCanvas({
            ...{ 'onPick': {} },
            content: (__VLS_ctx.displayContent),
            options: (__VLS_ctx.askUserOptions),
            actionable: (__VLS_ctx.optionsActionable),
        }));
        const __VLS_1 = __VLS_0({
            ...{ 'onPick': {} },
            content: (__VLS_ctx.displayContent),
            options: (__VLS_ctx.askUserOptions),
            actionable: (__VLS_ctx.optionsActionable),
        }, ...__VLS_functionalComponentArgsRest(__VLS_0));
        let __VLS_3;
        let __VLS_4;
        let __VLS_5;
        const __VLS_6 = {
            onPick: (__VLS_ctx.onPick)
        };
        var __VLS_2;
    }
    else if (__VLS_ctx.isWait) {
        __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
            ...{ class: "text-xs italic opacity-70" },
        });
        __VLS_asFunctionalElement(__VLS_intrinsicElements.span)({
            ...{ class: "inline-block w-1.5 h-1.5 rounded-full bg-warning animate-pulse mr-2" },
        });
        (__VLS_ctx.displayContent);
    }
    else if (__VLS_ctx.isReject) {
        __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
            ...{ class: "text-sm italic opacity-80" },
        });
        const __VLS_7 = {}.MarkdownView;
        /** @type {[typeof __VLS_components.MarkdownView, ]} */ ;
        // @ts-ignore
        const __VLS_8 = __VLS_asFunctionalComponent(__VLS_7, new __VLS_7({
            source: (__VLS_ctx.displayContent),
        }));
        const __VLS_9 = __VLS_8({
            source: (__VLS_ctx.displayContent),
        }, ...__VLS_functionalComponentArgsRest(__VLS_8));
    }
    else {
        const __VLS_11 = {}.MarkdownView;
        /** @type {[typeof __VLS_components.MarkdownView, ]} */ ;
        // @ts-ignore
        const __VLS_12 = __VLS_asFunctionalComponent(__VLS_11, new __VLS_11({
            source: (__VLS_ctx.displayContent),
        }));
        const __VLS_13 = __VLS_12({
            source: (__VLS_ctx.displayContent),
        }, ...__VLS_functionalComponentArgsRest(__VLS_12));
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
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['italic']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-70']} */ ;
/** @type {__VLS_StyleScopedClasses['inline-block']} */ ;
/** @type {__VLS_StyleScopedClasses['w-1.5']} */ ;
/** @type {__VLS_StyleScopedClasses['h-1.5']} */ ;
/** @type {__VLS_StyleScopedClasses['rounded-full']} */ ;
/** @type {__VLS_StyleScopedClasses['bg-warning']} */ ;
/** @type {__VLS_StyleScopedClasses['animate-pulse']} */ ;
/** @type {__VLS_StyleScopedClasses['mr-2']} */ ;
/** @type {__VLS_StyleScopedClasses['text-sm']} */ ;
/** @type {__VLS_StyleScopedClasses['italic']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-80']} */ ;
var __VLS_dollars;
const __VLS_self = (await import('vue')).defineComponent({
    setup() {
        return {
            MarkdownView: MarkdownView,
            QuestionCanvas: QuestionCanvas,
            askUserOptions: askUserOptions,
            isAskUser: isAskUser,
            isReject: isReject,
            isWait: isWait,
            onPick: onPick,
            isUser: isUser,
            isAssistant: isAssistant,
            isSystem: isSystem,
            hasRichContent: hasRichContent,
            displayContent: displayContent,
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