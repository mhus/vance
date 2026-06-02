import { computed, onMounted } from 'vue';
import { useI18n } from 'vue-i18n';
import { EditorShell, VAlert, VButton, VCard, VEmptyState, } from '@/components';
import { useOAuthConnectedAccounts } from '@/composables/useOAuthConnectedAccounts';
const { t } = useI18n();
const state = useOAuthConnectedAccounts();
onMounted(state.reload);
const sortedProviders = computed(() => [...state.providers.value].sort((a, b) => a.providerId.localeCompare(b.providerId)));
const banner = computed(() => {
    // Surface the return-from-callback signal in the URL — the callback
    // ends with a 302 to returnTo, which the Web-UI sets to this page +
    // a marker query param so the user gets a "Connected ✓" toast.
    if (typeof window === 'undefined')
        return null;
    const params = new URLSearchParams(window.location.search);
    if (params.get('connected') === '1') {
        const provider = params.get('provider') ?? '';
        return t('connectedAccounts.banner.justConnected', { provider });
    }
    return null;
});
function onConnect(providerId) {
    // Round-trip back to this page with a "connected" marker so the
    // success banner fires after the OAuth dance completes.
    const returnTo = `${window.location.pathname}?connected=1&provider=${encodeURIComponent(providerId)}`;
    state.connect(providerId, returnTo);
}
async function onDisconnect(providerId) {
    if (!confirm(t('connectedAccounts.confirmDisconnect', { provider: providerId })))
        return;
    await state.disconnect(providerId);
}
debugger; /* PartiallyEnd: #3632/scriptSetup.vue */
const __VLS_ctx = {};
let __VLS_components;
let __VLS_directives;
// CSS variable injection 
// CSS variable injection end 
const __VLS_0 = {}.EditorShell;
/** @type {[typeof __VLS_components.EditorShell, typeof __VLS_components.EditorShell, ]} */ ;
// @ts-ignore
const __VLS_1 = __VLS_asFunctionalComponent(__VLS_0, new __VLS_0({
    title: (__VLS_ctx.$t('connectedAccounts.pageTitle')),
}));
const __VLS_2 = __VLS_1({
    title: (__VLS_ctx.$t('connectedAccounts.pageTitle')),
}, ...__VLS_functionalComponentArgsRest(__VLS_1));
var __VLS_4 = {};
__VLS_3.slots.default;
__VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
    ...{ class: "p-6 flex flex-col gap-4 max-w-3xl" },
});
if (__VLS_ctx.state.error.value) {
    const __VLS_5 = {}.VAlert;
    /** @type {[typeof __VLS_components.VAlert, typeof __VLS_components.VAlert, ]} */ ;
    // @ts-ignore
    const __VLS_6 = __VLS_asFunctionalComponent(__VLS_5, new __VLS_5({
        variant: "error",
    }));
    const __VLS_7 = __VLS_6({
        variant: "error",
    }, ...__VLS_functionalComponentArgsRest(__VLS_6));
    __VLS_8.slots.default;
    __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({});
    (__VLS_ctx.state.error.value);
    var __VLS_8;
}
if (__VLS_ctx.banner) {
    const __VLS_9 = {}.VAlert;
    /** @type {[typeof __VLS_components.VAlert, typeof __VLS_components.VAlert, ]} */ ;
    // @ts-ignore
    const __VLS_10 = __VLS_asFunctionalComponent(__VLS_9, new __VLS_9({
        variant: "success",
    }));
    const __VLS_11 = __VLS_10({
        variant: "success",
    }, ...__VLS_functionalComponentArgsRest(__VLS_10));
    __VLS_12.slots.default;
    __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({});
    (__VLS_ctx.banner);
    var __VLS_12;
}
const __VLS_13 = {}.VCard;
/** @type {[typeof __VLS_components.VCard, typeof __VLS_components.VCard, ]} */ ;
// @ts-ignore
const __VLS_14 = __VLS_asFunctionalComponent(__VLS_13, new __VLS_13({}));
const __VLS_15 = __VLS_14({}, ...__VLS_functionalComponentArgsRest(__VLS_14));
__VLS_16.slots.default;
__VLS_asFunctionalElement(__VLS_intrinsicElements.p, __VLS_intrinsicElements.p)({
    ...{ class: "text-sm opacity-70" },
});
(__VLS_ctx.$t('connectedAccounts.intro'));
var __VLS_16;
if (__VLS_ctx.state.loading.value) {
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "text-sm opacity-60" },
    });
    (__VLS_ctx.$t('connectedAccounts.loading'));
}
else if (__VLS_ctx.sortedProviders.length === 0) {
    const __VLS_17 = {}.VEmptyState;
    /** @type {[typeof __VLS_components.VEmptyState, ]} */ ;
    // @ts-ignore
    const __VLS_18 = __VLS_asFunctionalComponent(__VLS_17, new __VLS_17({
        headline: (__VLS_ctx.$t('connectedAccounts.empty.headline')),
        body: (__VLS_ctx.$t('connectedAccounts.empty.body')),
    }));
    const __VLS_19 = __VLS_18({
        headline: (__VLS_ctx.$t('connectedAccounts.empty.headline')),
        body: (__VLS_ctx.$t('connectedAccounts.empty.body')),
    }, ...__VLS_functionalComponentArgsRest(__VLS_18));
}
else {
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "flex flex-col gap-2" },
    });
    for (const [p] of __VLS_getVForSourceType((__VLS_ctx.sortedProviders))) {
        const __VLS_21 = {}.VCard;
        /** @type {[typeof __VLS_components.VCard, typeof __VLS_components.VCard, ]} */ ;
        // @ts-ignore
        const __VLS_22 = __VLS_asFunctionalComponent(__VLS_21, new __VLS_21({
            key: (p.providerId),
        }));
        const __VLS_23 = __VLS_22({
            key: (p.providerId),
        }, ...__VLS_functionalComponentArgsRest(__VLS_22));
        __VLS_24.slots.default;
        __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
            ...{ class: "flex items-center justify-between gap-3" },
        });
        __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
            ...{ class: "flex flex-col gap-1" },
        });
        __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
            ...{ class: "font-mono text-base" },
        });
        (p.providerId);
        __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
            ...{ class: "flex items-center gap-2 text-xs opacity-70" },
        });
        __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
            ...{ class: "badge-type" },
        });
        (p.typeId);
        __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
            ...{ class: (p.connected ? 'badge-connected' : 'badge-unconnected') },
        });
        (p.connected
            ? __VLS_ctx.$t('connectedAccounts.statusConnected')
            : __VLS_ctx.$t('connectedAccounts.statusUnconnected'));
        __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
            ...{ class: "flex gap-2" },
        });
        if (!p.connected) {
            const __VLS_25 = {}.VButton;
            /** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
            // @ts-ignore
            const __VLS_26 = __VLS_asFunctionalComponent(__VLS_25, new __VLS_25({
                ...{ 'onClick': {} },
                variant: "primary",
                loading: (__VLS_ctx.state.busy.value),
            }));
            const __VLS_27 = __VLS_26({
                ...{ 'onClick': {} },
                variant: "primary",
                loading: (__VLS_ctx.state.busy.value),
            }, ...__VLS_functionalComponentArgsRest(__VLS_26));
            let __VLS_29;
            let __VLS_30;
            let __VLS_31;
            const __VLS_32 = {
                onClick: (...[$event]) => {
                    if (!!(__VLS_ctx.state.loading.value))
                        return;
                    if (!!(__VLS_ctx.sortedProviders.length === 0))
                        return;
                    if (!(!p.connected))
                        return;
                    __VLS_ctx.onConnect(p.providerId);
                }
            };
            __VLS_28.slots.default;
            (__VLS_ctx.$t('connectedAccounts.connect'));
            var __VLS_28;
        }
        else {
            const __VLS_33 = {}.VButton;
            /** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
            // @ts-ignore
            const __VLS_34 = __VLS_asFunctionalComponent(__VLS_33, new __VLS_33({
                ...{ 'onClick': {} },
                variant: "ghost",
                loading: (__VLS_ctx.state.busy.value),
            }));
            const __VLS_35 = __VLS_34({
                ...{ 'onClick': {} },
                variant: "ghost",
                loading: (__VLS_ctx.state.busy.value),
            }, ...__VLS_functionalComponentArgsRest(__VLS_34));
            let __VLS_37;
            let __VLS_38;
            let __VLS_39;
            const __VLS_40 = {
                onClick: (...[$event]) => {
                    if (!!(__VLS_ctx.state.loading.value))
                        return;
                    if (!!(__VLS_ctx.sortedProviders.length === 0))
                        return;
                    if (!!(!p.connected))
                        return;
                    __VLS_ctx.onConnect(p.providerId);
                }
            };
            __VLS_36.slots.default;
            (__VLS_ctx.$t('connectedAccounts.reconnect'));
            var __VLS_36;
            const __VLS_41 = {}.VButton;
            /** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
            // @ts-ignore
            const __VLS_42 = __VLS_asFunctionalComponent(__VLS_41, new __VLS_41({
                ...{ 'onClick': {} },
                variant: "danger",
                loading: (__VLS_ctx.state.busy.value),
            }));
            const __VLS_43 = __VLS_42({
                ...{ 'onClick': {} },
                variant: "danger",
                loading: (__VLS_ctx.state.busy.value),
            }, ...__VLS_functionalComponentArgsRest(__VLS_42));
            let __VLS_45;
            let __VLS_46;
            let __VLS_47;
            const __VLS_48 = {
                onClick: (...[$event]) => {
                    if (!!(__VLS_ctx.state.loading.value))
                        return;
                    if (!!(__VLS_ctx.sortedProviders.length === 0))
                        return;
                    if (!!(!p.connected))
                        return;
                    __VLS_ctx.onDisconnect(p.providerId);
                }
            };
            __VLS_44.slots.default;
            (__VLS_ctx.$t('connectedAccounts.disconnect'));
            var __VLS_44;
        }
        var __VLS_24;
    }
}
var __VLS_3;
/** @type {__VLS_StyleScopedClasses['p-6']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-col']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-4']} */ ;
/** @type {__VLS_StyleScopedClasses['max-w-3xl']} */ ;
/** @type {__VLS_StyleScopedClasses['text-sm']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-70']} */ ;
/** @type {__VLS_StyleScopedClasses['text-sm']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-60']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-col']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-2']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['items-center']} */ ;
/** @type {__VLS_StyleScopedClasses['justify-between']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-3']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-col']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-1']} */ ;
/** @type {__VLS_StyleScopedClasses['font-mono']} */ ;
/** @type {__VLS_StyleScopedClasses['text-base']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['items-center']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-2']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-70']} */ ;
/** @type {__VLS_StyleScopedClasses['badge-type']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-2']} */ ;
var __VLS_dollars;
const __VLS_self = (await import('vue')).defineComponent({
    setup() {
        return {
            EditorShell: EditorShell,
            VAlert: VAlert,
            VButton: VButton,
            VCard: VCard,
            VEmptyState: VEmptyState,
            state: state,
            sortedProviders: sortedProviders,
            banner: banner,
            onConnect: onConnect,
            onDisconnect: onDisconnect,
        };
    },
});
export default (await import('vue')).defineComponent({
    setup() {
        return {};
    },
});
; /* PartiallyEnd: #4569/main.vue */
//# sourceMappingURL=ConnectedAccountsApp.vue.js.map