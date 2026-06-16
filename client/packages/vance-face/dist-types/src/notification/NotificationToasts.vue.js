import { useNotificationStore } from './notificationStore';
const store = useNotificationStore();
function colorClass(severity) {
    switch (severity) {
        case 'WARN': return 'bg-warning/15 text-warning border-warning/40';
        case 'ERROR': return 'bg-error/15 text-error border-error/40';
        case 'INFO':
        default: return 'bg-info/15 text-info border-info/40';
    }
}
function badgeClass(severity) {
    switch (severity) {
        case 'WARN': return 'bg-warning text-warning-content';
        case 'ERROR': return 'bg-error text-error-content';
        case 'INFO':
        default: return 'bg-info text-info-content';
    }
}
function sourceLine(n) {
    return n.sourceProcessTitle ?? n.sourceProcessName ?? '';
}
debugger; /* PartiallyEnd: #3632/scriptSetup.vue */
const __VLS_ctx = {};
let __VLS_components;
let __VLS_directives;
// CSS variable injection 
// CSS variable injection end 
__VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
    ...{ class: "notify-toast-stack" },
    'aria-live': "polite",
    'aria-atomic': "false",
});
const __VLS_0 = {}.TransitionGroup;
/** @type {[typeof __VLS_components.TransitionGroup, typeof __VLS_components.TransitionGroup, ]} */ ;
// @ts-ignore
const __VLS_1 = __VLS_asFunctionalComponent(__VLS_0, new __VLS_0({
    name: "notify-toast",
}));
const __VLS_2 = __VLS_1({
    name: "notify-toast",
}, ...__VLS_functionalComponentArgsRest(__VLS_1));
__VLS_3.slots.default;
for (const [t] of __VLS_getVForSourceType((__VLS_ctx.store.toasts))) {
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ onClick: (...[$event]) => {
                __VLS_ctx.store.dismiss(t.id);
            } },
        key: (t.id),
        ...{ class: "notify-toast" },
        ...{ class: (__VLS_ctx.colorClass(t.notification.severity)) },
    });
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "flex items-center gap-2 text-xs font-semibold uppercase tracking-wide" },
    });
    __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
        ...{ class: "px-1.5 py-0.5 rounded text-[10px]" },
        ...{ class: (__VLS_ctx.badgeClass(t.notification.severity)) },
    });
    (t.notification.severity);
    if (__VLS_ctx.sourceLine(t.notification)) {
        __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
            ...{ class: "opacity-70 truncate" },
        });
        (__VLS_ctx.sourceLine(t.notification));
    }
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "mt-1 text-sm break-words" },
    });
    (t.notification.text);
}
var __VLS_3;
/** @type {__VLS_StyleScopedClasses['notify-toast-stack']} */ ;
/** @type {__VLS_StyleScopedClasses['notify-toast']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['items-center']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-2']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['font-semibold']} */ ;
/** @type {__VLS_StyleScopedClasses['uppercase']} */ ;
/** @type {__VLS_StyleScopedClasses['tracking-wide']} */ ;
/** @type {__VLS_StyleScopedClasses['px-1.5']} */ ;
/** @type {__VLS_StyleScopedClasses['py-0.5']} */ ;
/** @type {__VLS_StyleScopedClasses['rounded']} */ ;
/** @type {__VLS_StyleScopedClasses['text-[10px]']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-70']} */ ;
/** @type {__VLS_StyleScopedClasses['truncate']} */ ;
/** @type {__VLS_StyleScopedClasses['mt-1']} */ ;
/** @type {__VLS_StyleScopedClasses['text-sm']} */ ;
/** @type {__VLS_StyleScopedClasses['break-words']} */ ;
var __VLS_dollars;
const __VLS_self = (await import('vue')).defineComponent({
    setup() {
        return {
            store: store,
            colorClass: colorClass,
            badgeClass: badgeClass,
            sourceLine: sourceLine,
        };
    },
});
export default (await import('vue')).defineComponent({
    setup() {
        return {};
    },
});
; /* PartiallyEnd: #4569/main.vue */
//# sourceMappingURL=NotificationToasts.vue.js.map