import { useI18n } from 'vue-i18n';
import { manualReconnect, useWsConnection } from './wsConnectionStore';
const { t } = useI18n();
const { status, reconnectAttempts, maxReconnectAttempts, lastError } = useWsConnection();
debugger; /* PartiallyEnd: #3632/scriptSetup.vue */
const __VLS_ctx = {};
let __VLS_components;
let __VLS_directives;
const __VLS_0 = {}.Teleport;
/** @type {[typeof __VLS_components.Teleport, typeof __VLS_components.Teleport, ]} */ ;
// @ts-ignore
const __VLS_1 = __VLS_asFunctionalComponent(__VLS_0, new __VLS_0({
    to: "body",
}));
const __VLS_2 = __VLS_1({
    to: "body",
}, ...__VLS_functionalComponentArgsRest(__VLS_1));
__VLS_3.slots.default;
if (__VLS_ctx.status === 'reconnecting' || __VLS_ctx.status === 'down') {
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "\u0066\u0069\u0078\u0065\u0064\u0020\u0069\u006e\u0073\u0065\u0074\u002d\u0030\u0020\u007a\u002d\u005b\u0039\u0039\u0039\u0039\u005d\u0020\u0066\u006c\u0065\u0078\u0020\u0069\u0074\u0065\u006d\u0073\u002d\u0063\u0065\u006e\u0074\u0065\u0072\u0020\u006a\u0075\u0073\u0074\u0069\u0066\u0079\u002d\u0063\u0065\u006e\u0074\u0065\u0072\u000a\u0020\u0020\u0020\u0020\u0020\u0020\u0020\u0020\u0020\u0020\u0020\u0020\u0020\u0062\u0067\u002d\u0062\u0061\u0073\u0065\u002d\u0033\u0030\u0030\u002f\u0037\u0030\u0020\u0062\u0061\u0063\u006b\u0064\u0072\u006f\u0070\u002d\u0062\u006c\u0075\u0072\u002d\u0073\u006d" },
        role: "dialog",
        'aria-modal': "true",
        'aria-label': (__VLS_ctx.t('reconnect.title')),
    });
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "\u0062\u0067\u002d\u0062\u0061\u0073\u0065\u002d\u0031\u0030\u0030\u0020\u0062\u006f\u0072\u0064\u0065\u0072\u0020\u0062\u006f\u0072\u0064\u0065\u0072\u002d\u0062\u0061\u0073\u0065\u002d\u0033\u0030\u0030\u0020\u0072\u006f\u0075\u006e\u0064\u0065\u0064\u002d\u006c\u0067\u0020\u0073\u0068\u0061\u0064\u006f\u0077\u002d\u0078\u006c\u000a\u0020\u0020\u0020\u0020\u0020\u0020\u0020\u0020\u0020\u0020\u0020\u0020\u0020\u0020\u0020\u0070\u002d\u0036\u0020\u006d\u0061\u0078\u002d\u0077\u002d\u0073\u006d\u0020\u0077\u002d\u0066\u0075\u006c\u006c\u0020\u006d\u0078\u002d\u0034\u0020\u0074\u0065\u0078\u0074\u002d\u0063\u0065\u006e\u0074\u0065\u0072" },
    });
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "text-base font-medium text-base-content mb-3" },
    });
    (__VLS_ctx.t('reconnect.title'));
    if (__VLS_ctx.status === 'reconnecting') {
        __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
            ...{ class: "flex flex-col items-center gap-3 text-sm text-base-content/70" },
        });
        __VLS_asFunctionalElement(__VLS_intrinsicElements.svg, __VLS_intrinsicElements.svg)({
            ...{ class: "animate-spin h-6 w-6 text-primary" },
            xmlns: "http://www.w3.org/2000/svg",
            fill: "none",
            viewBox: "0 0 24 24",
            'aria-hidden': "true",
        });
        __VLS_asFunctionalElement(__VLS_intrinsicElements.circle)({
            ...{ class: "opacity-25" },
            cx: "12",
            cy: "12",
            r: "10",
            stroke: "currentColor",
            'stroke-width': "4",
        });
        __VLS_asFunctionalElement(__VLS_intrinsicElements.path)({
            ...{ class: "opacity-75" },
            fill: "currentColor",
            d: "M4 12a8 8 0 018-8v4a4 4 0 00-4 4H4z",
        });
        __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({});
        (__VLS_ctx.t('reconnect.attempting', {
            n: __VLS_ctx.reconnectAttempts,
            total: __VLS_ctx.maxReconnectAttempts,
        }));
    }
    else {
        __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
            ...{ class: "flex flex-col items-center gap-4" },
        });
        __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
            ...{ class: "text-sm text-base-content/70" },
        });
        (__VLS_ctx.t('reconnect.giveUp'));
        if (__VLS_ctx.lastError) {
            __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
                ...{ class: "text-xs text-base-content/50 font-mono break-all" },
            });
            (__VLS_ctx.lastError);
        }
        __VLS_asFunctionalElement(__VLS_intrinsicElements.button, __VLS_intrinsicElements.button)({
            ...{ onClick: (__VLS_ctx.manualReconnect) },
            type: "button",
            ...{ class: "btn btn-primary btn-sm" },
        });
        (__VLS_ctx.t('reconnect.tryAgain'));
    }
}
var __VLS_3;
/** @type {__VLS_StyleScopedClasses['fixed']} */ ;
/** @type {__VLS_StyleScopedClasses['inset-0']} */ ;
/** @type {__VLS_StyleScopedClasses['z-[9999]']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['items-center']} */ ;
/** @type {__VLS_StyleScopedClasses['justify-center']} */ ;
/** @type {__VLS_StyleScopedClasses['bg-base-300/70']} */ ;
/** @type {__VLS_StyleScopedClasses['backdrop-blur-sm']} */ ;
/** @type {__VLS_StyleScopedClasses['bg-base-100']} */ ;
/** @type {__VLS_StyleScopedClasses['border']} */ ;
/** @type {__VLS_StyleScopedClasses['border-base-300']} */ ;
/** @type {__VLS_StyleScopedClasses['rounded-lg']} */ ;
/** @type {__VLS_StyleScopedClasses['shadow-xl']} */ ;
/** @type {__VLS_StyleScopedClasses['p-6']} */ ;
/** @type {__VLS_StyleScopedClasses['max-w-sm']} */ ;
/** @type {__VLS_StyleScopedClasses['w-full']} */ ;
/** @type {__VLS_StyleScopedClasses['mx-4']} */ ;
/** @type {__VLS_StyleScopedClasses['text-center']} */ ;
/** @type {__VLS_StyleScopedClasses['text-base']} */ ;
/** @type {__VLS_StyleScopedClasses['font-medium']} */ ;
/** @type {__VLS_StyleScopedClasses['text-base-content']} */ ;
/** @type {__VLS_StyleScopedClasses['mb-3']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-col']} */ ;
/** @type {__VLS_StyleScopedClasses['items-center']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-3']} */ ;
/** @type {__VLS_StyleScopedClasses['text-sm']} */ ;
/** @type {__VLS_StyleScopedClasses['text-base-content/70']} */ ;
/** @type {__VLS_StyleScopedClasses['animate-spin']} */ ;
/** @type {__VLS_StyleScopedClasses['h-6']} */ ;
/** @type {__VLS_StyleScopedClasses['w-6']} */ ;
/** @type {__VLS_StyleScopedClasses['text-primary']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-25']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-75']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-col']} */ ;
/** @type {__VLS_StyleScopedClasses['items-center']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-4']} */ ;
/** @type {__VLS_StyleScopedClasses['text-sm']} */ ;
/** @type {__VLS_StyleScopedClasses['text-base-content/70']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['text-base-content/50']} */ ;
/** @type {__VLS_StyleScopedClasses['font-mono']} */ ;
/** @type {__VLS_StyleScopedClasses['break-all']} */ ;
/** @type {__VLS_StyleScopedClasses['btn']} */ ;
/** @type {__VLS_StyleScopedClasses['btn-primary']} */ ;
/** @type {__VLS_StyleScopedClasses['btn-sm']} */ ;
var __VLS_dollars;
const __VLS_self = (await import('vue')).defineComponent({
    setup() {
        return {
            manualReconnect: manualReconnect,
            t: t,
            status: status,
            reconnectAttempts: reconnectAttempts,
            maxReconnectAttempts: maxReconnectAttempts,
            lastError: lastError,
        };
    },
});
export default (await import('vue')).defineComponent({
    setup() {
        return {};
    },
});
; /* PartiallyEnd: #4569/main.vue */
//# sourceMappingURL=ReconnectOverlay.vue.js.map