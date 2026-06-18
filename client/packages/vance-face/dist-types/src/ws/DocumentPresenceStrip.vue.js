import { computed, onBeforeUnmount, watch } from 'vue';
import { subscribeDocument, unsubscribeDocument, useWsConnection, } from './wsConnectionStore';
const props = withDefaults(defineProps(), { size: 22, max: 5 });
const { documentViewers } = useWsConnection();
const viewers = computed(() => documentViewers.get(props.path) ?? []);
const visibleViewers = computed(() => viewers.value.slice(0, props.max));
const overflowCount = computed(() => Math.max(0, viewers.value.length - props.max));
watch(() => props.path, (next, prev) => {
    if (prev)
        void unsubscribeDocument(prev);
    if (next)
        void subscribeDocument(next);
}, { immediate: true });
onBeforeUnmount(() => {
    if (props.path)
        void unsubscribeDocument(props.path);
});
function initials(displayName) {
    const trimmed = displayName.trim();
    if (!trimmed)
        return '?';
    const parts = trimmed.split(/\s+/);
    if (parts.length === 1)
        return parts[0].slice(0, 2).toUpperCase();
    return (parts[0][0] + parts[parts.length - 1][0]).toUpperCase();
}
/**
 * Deterministic avatar color from the userId — same user always shows
 * the same color, across tabs and reconnects. Tailwind/DaisyUI hue rota.
 */
function colorFor(userId) {
    const palette = [
        'bg-primary text-primary-content',
        'bg-secondary text-secondary-content',
        'bg-accent text-accent-content',
        'bg-info text-info-content',
        'bg-success text-success-content',
        'bg-warning text-warning-content',
    ];
    let hash = 0;
    for (let i = 0; i < userId.length; i++)
        hash = (hash * 31 + userId.charCodeAt(i)) | 0;
    return palette[Math.abs(hash) % palette.length];
}
debugger; /* PartiallyEnd: #3632/scriptSetup.vue */
const __VLS_withDefaultsArg = (function (t) { return t; })({ size: 22, max: 5 });
const __VLS_ctx = {};
let __VLS_components;
let __VLS_directives;
if (__VLS_ctx.viewers.length > 0) {
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "flex items-center -space-x-1.5" },
        title: (__VLS_ctx.$t('documentPresence.tooltip', { n: __VLS_ctx.viewers.length })),
    });
    for (const [v, i] of __VLS_getVForSourceType((__VLS_ctx.visibleViewers))) {
        __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
            key: (v.editorId),
            ...{ class: "rounded-full ring-2 ring-base-100 flex items-center justify-center font-medium select-none" },
            ...{ class: (__VLS_ctx.colorFor(v.userId)) },
            ...{ style: ({
                    width: `${__VLS_ctx.size}px`,
                    height: `${__VLS_ctx.size}px`,
                    fontSize: `${Math.round(__VLS_ctx.size * 0.42)}px`,
                    zIndex: __VLS_ctx.visibleViewers.length - i,
                }) },
            title: (v.displayName),
        });
        (__VLS_ctx.initials(v.displayName));
    }
    if (__VLS_ctx.overflowCount > 0) {
        __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
            ...{ class: "\u0072\u006f\u0075\u006e\u0064\u0065\u0064\u002d\u0066\u0075\u006c\u006c\u0020\u0072\u0069\u006e\u0067\u002d\u0032\u0020\u0072\u0069\u006e\u0067\u002d\u0062\u0061\u0073\u0065\u002d\u0031\u0030\u0030\u0020\u0062\u0067\u002d\u0062\u0061\u0073\u0065\u002d\u0033\u0030\u0030\u0020\u0074\u0065\u0078\u0074\u002d\u0062\u0061\u0073\u0065\u002d\u0063\u006f\u006e\u0074\u0065\u006e\u0074\u000a\u0020\u0020\u0020\u0020\u0020\u0020\u0020\u0020\u0020\u0020\u0020\u0020\u0020\u0066\u006c\u0065\u0078\u0020\u0069\u0074\u0065\u006d\u0073\u002d\u0063\u0065\u006e\u0074\u0065\u0072\u0020\u006a\u0075\u0073\u0074\u0069\u0066\u0079\u002d\u0063\u0065\u006e\u0074\u0065\u0072\u0020\u0066\u006f\u006e\u0074\u002d\u006d\u0065\u0064\u0069\u0075\u006d\u0020\u0073\u0065\u006c\u0065\u0063\u0074\u002d\u006e\u006f\u006e\u0065" },
            ...{ style: ({
                    width: `${__VLS_ctx.size}px`,
                    height: `${__VLS_ctx.size}px`,
                    fontSize: `${Math.round(__VLS_ctx.size * 0.42)}px`,
                }) },
        });
        (__VLS_ctx.overflowCount);
    }
}
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['items-center']} */ ;
/** @type {__VLS_StyleScopedClasses['-space-x-1.5']} */ ;
/** @type {__VLS_StyleScopedClasses['rounded-full']} */ ;
/** @type {__VLS_StyleScopedClasses['ring-2']} */ ;
/** @type {__VLS_StyleScopedClasses['ring-base-100']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['items-center']} */ ;
/** @type {__VLS_StyleScopedClasses['justify-center']} */ ;
/** @type {__VLS_StyleScopedClasses['font-medium']} */ ;
/** @type {__VLS_StyleScopedClasses['select-none']} */ ;
/** @type {__VLS_StyleScopedClasses['rounded-full']} */ ;
/** @type {__VLS_StyleScopedClasses['ring-2']} */ ;
/** @type {__VLS_StyleScopedClasses['ring-base-100']} */ ;
/** @type {__VLS_StyleScopedClasses['bg-base-300']} */ ;
/** @type {__VLS_StyleScopedClasses['text-base-content']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['items-center']} */ ;
/** @type {__VLS_StyleScopedClasses['justify-center']} */ ;
/** @type {__VLS_StyleScopedClasses['font-medium']} */ ;
/** @type {__VLS_StyleScopedClasses['select-none']} */ ;
var __VLS_dollars;
const __VLS_self = (await import('vue')).defineComponent({
    setup() {
        return {
            viewers: viewers,
            visibleViewers: visibleViewers,
            overflowCount: overflowCount,
            initials: initials,
            colorFor: colorFor,
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
//# sourceMappingURL=DocumentPresenceStrip.vue.js.map