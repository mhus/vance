import { ref, watch } from 'vue';
import { VAlert, VButton, VCard, VEmptyState } from '@/components';
import { useRag } from '@/composables/useRag';
const props = defineProps();
const state = useRag();
const rebuildConfirmOpen = ref(false);
watch(() => props.projectId, (next) => {
    rebuildConfirmOpen.value = false;
    if (next)
        void state.load(next);
    else
        state.clear();
}, { immediate: true });
function refresh() {
    if (props.projectId)
        void state.load(props.projectId);
}
async function reindex() {
    if (!props.projectId)
        return;
    await state.reindex(props.projectId, false);
}
async function rebuild() {
    if (!props.projectId)
        return;
    rebuildConfirmOpen.value = false;
    await state.reindex(props.projectId, true);
}
function fmtTime(value) {
    if (!value)
        return '—';
    return String(value).replace('T', ' ').slice(0, 19);
}
debugger; /* PartiallyEnd: #3632/scriptSetup.vue */
const __VLS_ctx = {};
let __VLS_components;
let __VLS_directives;
// CSS variable injection 
// CSS variable injection end 
__VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
    ...{ class: "flex flex-col gap-3" },
});
if (__VLS_ctx.state.error.value) {
    const __VLS_0 = {}.VAlert;
    /** @type {[typeof __VLS_components.VAlert, typeof __VLS_components.VAlert, ]} */ ;
    // @ts-ignore
    const __VLS_1 = __VLS_asFunctionalComponent(__VLS_0, new __VLS_0({
        variant: "error",
    }));
    const __VLS_2 = __VLS_1({
        variant: "error",
    }, ...__VLS_functionalComponentArgsRest(__VLS_1));
    __VLS_3.slots.default;
    __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({});
    (__VLS_ctx.state.error.value);
    var __VLS_3;
}
if (!__VLS_ctx.projectId) {
    const __VLS_4 = {}.VEmptyState;
    /** @type {[typeof __VLS_components.VEmptyState, ]} */ ;
    // @ts-ignore
    const __VLS_5 = __VLS_asFunctionalComponent(__VLS_4, new __VLS_4({
        headline: "No project selected",
        body: "Pick a project from the sidebar filter to manage its RAG.",
    }));
    const __VLS_6 = __VLS_5({
        headline: "No project selected",
        body: "Pick a project from the sidebar filter to manage its RAG.",
    }, ...__VLS_functionalComponentArgsRest(__VLS_5));
}
else {
    const __VLS_8 = {}.VCard;
    /** @type {[typeof __VLS_components.VCard, typeof __VLS_components.VCard, ]} */ ;
    // @ts-ignore
    const __VLS_9 = __VLS_asFunctionalComponent(__VLS_8, new __VLS_8({
        title: "Project RAG — _documents",
    }));
    const __VLS_10 = __VLS_9({
        title: "Project RAG — _documents",
    }, ...__VLS_functionalComponentArgsRest(__VLS_9));
    __VLS_11.slots.default;
    if (__VLS_ctx.state.loading.value) {
        __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
            ...{ class: "opacity-70" },
        });
    }
    else if (__VLS_ctx.state.status.value) {
        __VLS_asFunctionalElement(__VLS_intrinsicElements.dl, __VLS_intrinsicElements.dl)({
            ...{ class: "grid grid-cols-2 gap-x-4 gap-y-1 text-sm" },
        });
        __VLS_asFunctionalElement(__VLS_intrinsicElements.dt, __VLS_intrinsicElements.dt)({
            ...{ class: "opacity-60" },
        });
        __VLS_asFunctionalElement(__VLS_intrinsicElements.dd, __VLS_intrinsicElements.dd)({});
        if (__VLS_ctx.state.status.value.exists) {
            __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
                ...{ class: "badge-ok" },
            });
        }
        else {
            __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
                ...{ class: "badge-empty" },
            });
        }
        if (__VLS_ctx.state.status.value.exists) {
            __VLS_asFunctionalElement(__VLS_intrinsicElements.dt, __VLS_intrinsicElements.dt)({
                ...{ class: "opacity-60" },
            });
            __VLS_asFunctionalElement(__VLS_intrinsicElements.dd, __VLS_intrinsicElements.dd)({
                ...{ class: "font-mono text-xs" },
            });
            (__VLS_ctx.state.status.value.ragId);
            __VLS_asFunctionalElement(__VLS_intrinsicElements.dt, __VLS_intrinsicElements.dt)({
                ...{ class: "opacity-60" },
            });
            __VLS_asFunctionalElement(__VLS_intrinsicElements.dd, __VLS_intrinsicElements.dd)({});
            (__VLS_ctx.state.status.value.embeddingProvider ?? '—');
            __VLS_asFunctionalElement(__VLS_intrinsicElements.dt, __VLS_intrinsicElements.dt)({
                ...{ class: "opacity-60" },
            });
            __VLS_asFunctionalElement(__VLS_intrinsicElements.dd, __VLS_intrinsicElements.dd)({});
            (__VLS_ctx.state.status.value.embeddingModel ?? '—');
            __VLS_asFunctionalElement(__VLS_intrinsicElements.dt, __VLS_intrinsicElements.dt)({
                ...{ class: "opacity-60" },
            });
            __VLS_asFunctionalElement(__VLS_intrinsicElements.dd, __VLS_intrinsicElements.dd)({});
            (__VLS_ctx.state.status.value.chunkCount);
            __VLS_asFunctionalElement(__VLS_intrinsicElements.dt, __VLS_intrinsicElements.dt)({
                ...{ class: "opacity-60" },
            });
            __VLS_asFunctionalElement(__VLS_intrinsicElements.dd, __VLS_intrinsicElements.dd)({});
            (__VLS_ctx.fmtTime(__VLS_ctx.state.status.value.createdAt));
        }
        if (!__VLS_ctx.state.status.value.exists) {
            __VLS_asFunctionalElement(__VLS_intrinsicElements.p, __VLS_intrinsicElements.p)({
                ...{ class: "text-xs opacity-70 mt-2" },
            });
            __VLS_asFunctionalElement(__VLS_intrinsicElements.em, __VLS_intrinsicElements.em)({});
        }
    }
    var __VLS_11;
    const __VLS_12 = {}.VCard;
    /** @type {[typeof __VLS_components.VCard, typeof __VLS_components.VCard, ]} */ ;
    // @ts-ignore
    const __VLS_13 = __VLS_asFunctionalComponent(__VLS_12, new __VLS_12({
        title: "Actions",
    }));
    const __VLS_14 = __VLS_13({
        title: "Actions",
    }, ...__VLS_functionalComponentArgsRest(__VLS_13));
    __VLS_15.slots.default;
    __VLS_asFunctionalElement(__VLS_intrinsicElements.p, __VLS_intrinsicElements.p)({
        ...{ class: "text-xs opacity-70 mb-3" },
    });
    __VLS_asFunctionalElement(__VLS_intrinsicElements.strong, __VLS_intrinsicElements.strong)({});
    __VLS_asFunctionalElement(__VLS_intrinsicElements.code, __VLS_intrinsicElements.code)({});
    __VLS_asFunctionalElement(__VLS_intrinsicElements.strong, __VLS_intrinsicElements.strong)({});
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "flex flex-wrap gap-2" },
    });
    const __VLS_16 = {}.VButton;
    /** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
    // @ts-ignore
    const __VLS_17 = __VLS_asFunctionalComponent(__VLS_16, new __VLS_16({
        ...{ 'onClick': {} },
        disabled: (__VLS_ctx.state.busy.value),
    }));
    const __VLS_18 = __VLS_17({
        ...{ 'onClick': {} },
        disabled: (__VLS_ctx.state.busy.value),
    }, ...__VLS_functionalComponentArgsRest(__VLS_17));
    let __VLS_20;
    let __VLS_21;
    let __VLS_22;
    const __VLS_23 = {
        onClick: (__VLS_ctx.reindex)
    };
    __VLS_19.slots.default;
    (__VLS_ctx.state.busy.value ? 'Working…' : 'Reindex');
    var __VLS_19;
    const __VLS_24 = {}.VButton;
    /** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
    // @ts-ignore
    const __VLS_25 = __VLS_asFunctionalComponent(__VLS_24, new __VLS_24({
        ...{ 'onClick': {} },
        variant: "ghost",
        disabled: (__VLS_ctx.state.busy.value),
    }));
    const __VLS_26 = __VLS_25({
        ...{ 'onClick': {} },
        variant: "ghost",
        disabled: (__VLS_ctx.state.busy.value),
    }, ...__VLS_functionalComponentArgsRest(__VLS_25));
    let __VLS_28;
    let __VLS_29;
    let __VLS_30;
    const __VLS_31 = {
        onClick: (...[$event]) => {
            if (!!(!__VLS_ctx.projectId))
                return;
            __VLS_ctx.rebuildConfirmOpen = true;
        }
    };
    __VLS_27.slots.default;
    var __VLS_27;
    const __VLS_32 = {}.VButton;
    /** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
    // @ts-ignore
    const __VLS_33 = __VLS_asFunctionalComponent(__VLS_32, new __VLS_32({
        ...{ 'onClick': {} },
        variant: "ghost",
        disabled: (__VLS_ctx.state.busy.value),
    }));
    const __VLS_34 = __VLS_33({
        ...{ 'onClick': {} },
        variant: "ghost",
        disabled: (__VLS_ctx.state.busy.value),
    }, ...__VLS_functionalComponentArgsRest(__VLS_33));
    let __VLS_36;
    let __VLS_37;
    let __VLS_38;
    const __VLS_39 = {
        onClick: (__VLS_ctx.refresh)
    };
    __VLS_35.slots.default;
    var __VLS_35;
    if (__VLS_ctx.rebuildConfirmOpen) {
        __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
            ...{ class: "mt-3 border border-warning/40 bg-warning/10 rounded p-3 text-sm" },
        });
        __VLS_asFunctionalElement(__VLS_intrinsicElements.p, __VLS_intrinsicElements.p)({
            ...{ class: "mb-2" },
        });
        __VLS_asFunctionalElement(__VLS_intrinsicElements.strong, __VLS_intrinsicElements.strong)({});
        __VLS_asFunctionalElement(__VLS_intrinsicElements.code, __VLS_intrinsicElements.code)({});
        __VLS_asFunctionalElement(__VLS_intrinsicElements.p, __VLS_intrinsicElements.p)({
            ...{ class: "text-xs opacity-70 mb-3" },
        });
        __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
            ...{ class: "flex gap-2" },
        });
        const __VLS_40 = {}.VButton;
        /** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
        // @ts-ignore
        const __VLS_41 = __VLS_asFunctionalComponent(__VLS_40, new __VLS_40({
            ...{ 'onClick': {} },
            variant: "danger",
        }));
        const __VLS_42 = __VLS_41({
            ...{ 'onClick': {} },
            variant: "danger",
        }, ...__VLS_functionalComponentArgsRest(__VLS_41));
        let __VLS_44;
        let __VLS_45;
        let __VLS_46;
        const __VLS_47 = {
            onClick: (__VLS_ctx.rebuild)
        };
        __VLS_43.slots.default;
        var __VLS_43;
        const __VLS_48 = {}.VButton;
        /** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
        // @ts-ignore
        const __VLS_49 = __VLS_asFunctionalComponent(__VLS_48, new __VLS_48({
            ...{ 'onClick': {} },
            variant: "ghost",
        }));
        const __VLS_50 = __VLS_49({
            ...{ 'onClick': {} },
            variant: "ghost",
        }, ...__VLS_functionalComponentArgsRest(__VLS_49));
        let __VLS_52;
        let __VLS_53;
        let __VLS_54;
        const __VLS_55 = {
            onClick: (...[$event]) => {
                if (!!(!__VLS_ctx.projectId))
                    return;
                if (!(__VLS_ctx.rebuildConfirmOpen))
                    return;
                __VLS_ctx.rebuildConfirmOpen = false;
            }
        };
        __VLS_51.slots.default;
        var __VLS_51;
    }
    if (__VLS_ctx.state.lastResult.value) {
        __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
            ...{ class: "mt-3 text-xs opacity-70" },
        });
        __VLS_asFunctionalElement(__VLS_intrinsicElements.strong, __VLS_intrinsicElements.strong)({});
        (__VLS_ctx.state.lastResult.value.rebuild ? 'rebuild' : 'reindex');
        (__VLS_ctx.state.lastResult.value.documentsQueued);
    }
    var __VLS_15;
}
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-col']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-3']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-70']} */ ;
/** @type {__VLS_StyleScopedClasses['grid']} */ ;
/** @type {__VLS_StyleScopedClasses['grid-cols-2']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-x-4']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-y-1']} */ ;
/** @type {__VLS_StyleScopedClasses['text-sm']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-60']} */ ;
/** @type {__VLS_StyleScopedClasses['badge-ok']} */ ;
/** @type {__VLS_StyleScopedClasses['badge-empty']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-60']} */ ;
/** @type {__VLS_StyleScopedClasses['font-mono']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-60']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-60']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-60']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-60']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-70']} */ ;
/** @type {__VLS_StyleScopedClasses['mt-2']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-70']} */ ;
/** @type {__VLS_StyleScopedClasses['mb-3']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-wrap']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-2']} */ ;
/** @type {__VLS_StyleScopedClasses['mt-3']} */ ;
/** @type {__VLS_StyleScopedClasses['border']} */ ;
/** @type {__VLS_StyleScopedClasses['border-warning/40']} */ ;
/** @type {__VLS_StyleScopedClasses['bg-warning/10']} */ ;
/** @type {__VLS_StyleScopedClasses['rounded']} */ ;
/** @type {__VLS_StyleScopedClasses['p-3']} */ ;
/** @type {__VLS_StyleScopedClasses['text-sm']} */ ;
/** @type {__VLS_StyleScopedClasses['mb-2']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-70']} */ ;
/** @type {__VLS_StyleScopedClasses['mb-3']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-2']} */ ;
/** @type {__VLS_StyleScopedClasses['mt-3']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-70']} */ ;
var __VLS_dollars;
const __VLS_self = (await import('vue')).defineComponent({
    setup() {
        return {
            VAlert: VAlert,
            VButton: VButton,
            VCard: VCard,
            VEmptyState: VEmptyState,
            state: state,
            rebuildConfirmOpen: rebuildConfirmOpen,
            refresh: refresh,
            reindex: reindex,
            rebuild: rebuild,
            fmtTime: fmtTime,
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
//# sourceMappingURL=RagTab.vue.js.map