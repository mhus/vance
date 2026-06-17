import { computed, ref, watch } from 'vue';
import { VAlert, VButton, VCard, VEmptyState, VInput } from '@/components';
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
const searchDisabled = computed(() => {
    if (!props.projectId)
        return true;
    if (state.searching.value)
        return true;
    if (!state.status.value?.exists)
        return true;
    return state.searchQuery.value.trim().length === 0;
});
/** Cascade-resolved tenant/project setting — `"none"` means RAG is off here. */
const embeddingDisabled = computed(() => !!state.status.value && !state.status.value.enabled);
const providerMismatch = computed(() => {
    const s = state.status.value;
    if (!s || !s.exists)
        return false;
    return !!s.embeddingProvider && s.embeddingProvider !== s.effectiveProvider;
});
async function runSearch() {
    if (!props.projectId)
        return;
    const query = state.searchQuery.value.trim();
    if (query.length === 0)
        return;
    await state.search(props.projectId, query);
}
function fmtTime(value) {
    if (!value)
        return '—';
    return String(value).replace('T', ' ').slice(0, 19);
}
function fmtScore(score) {
    return score.toFixed(4);
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
    if (__VLS_ctx.embeddingDisabled) {
        const __VLS_8 = {}.VAlert;
        /** @type {[typeof __VLS_components.VAlert, typeof __VLS_components.VAlert, ]} */ ;
        // @ts-ignore
        const __VLS_9 = __VLS_asFunctionalComponent(__VLS_8, new __VLS_8({
            variant: "info",
        }));
        const __VLS_10 = __VLS_9({
            variant: "info",
        }, ...__VLS_functionalComponentArgsRest(__VLS_9));
        __VLS_11.slots.default;
        __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({});
        __VLS_asFunctionalElement(__VLS_intrinsicElements.code, __VLS_intrinsicElements.code)({});
        __VLS_asFunctionalElement(__VLS_intrinsicElements.strong, __VLS_intrinsicElements.strong)({});
        __VLS_asFunctionalElement(__VLS_intrinsicElements.code, __VLS_intrinsicElements.code)({});
        __VLS_asFunctionalElement(__VLS_intrinsicElements.code, __VLS_intrinsicElements.code)({});
        __VLS_asFunctionalElement(__VLS_intrinsicElements.code, __VLS_intrinsicElements.code)({});
        var __VLS_11;
    }
    const __VLS_12 = {}.VCard;
    /** @type {[typeof __VLS_components.VCard, typeof __VLS_components.VCard, ]} */ ;
    // @ts-ignore
    const __VLS_13 = __VLS_asFunctionalComponent(__VLS_12, new __VLS_12({
        title: "Project RAG — _documents",
    }));
    const __VLS_14 = __VLS_13({
        title: "Project RAG — _documents",
    }, ...__VLS_functionalComponentArgsRest(__VLS_13));
    __VLS_15.slots.default;
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
        if (__VLS_ctx.embeddingDisabled) {
            __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
                ...{ class: "badge-empty" },
            });
        }
        else if (__VLS_ctx.state.status.value.exists) {
            __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
                ...{ class: "badge-ok" },
            });
        }
        else {
            __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
                ...{ class: "badge-empty" },
            });
        }
        __VLS_asFunctionalElement(__VLS_intrinsicElements.dt, __VLS_intrinsicElements.dt)({
            ...{ class: "opacity-60" },
        });
        __VLS_asFunctionalElement(__VLS_intrinsicElements.dd, __VLS_intrinsicElements.dd)({});
        __VLS_asFunctionalElement(__VLS_intrinsicElements.code, __VLS_intrinsicElements.code)({});
        (__VLS_ctx.state.status.value.effectiveProvider);
        if (__VLS_ctx.providerMismatch) {
            __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
                ...{ class: "ml-2 text-xs opacity-70" },
                title: ('RAG was created with ' + __VLS_ctx.state.status.value.embeddingProvider + ' — tenant now resolves to ' + __VLS_ctx.state.status.value.effectiveProvider + '. Use Rebuild to migrate.'),
            });
            __VLS_asFunctionalElement(__VLS_intrinsicElements.code, __VLS_intrinsicElements.code)({});
            (__VLS_ctx.state.status.value.embeddingProvider);
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
        if (!__VLS_ctx.embeddingDisabled && !__VLS_ctx.state.status.value.exists) {
            __VLS_asFunctionalElement(__VLS_intrinsicElements.p, __VLS_intrinsicElements.p)({
                ...{ class: "text-xs opacity-70 mt-2" },
            });
            __VLS_asFunctionalElement(__VLS_intrinsicElements.em, __VLS_intrinsicElements.em)({});
        }
    }
    var __VLS_15;
    if (!__VLS_ctx.embeddingDisabled) {
        const __VLS_16 = {}.VCard;
        /** @type {[typeof __VLS_components.VCard, typeof __VLS_components.VCard, ]} */ ;
        // @ts-ignore
        const __VLS_17 = __VLS_asFunctionalComponent(__VLS_16, new __VLS_16({
            title: "Actions",
        }));
        const __VLS_18 = __VLS_17({
            title: "Actions",
        }, ...__VLS_functionalComponentArgsRest(__VLS_17));
        __VLS_19.slots.default;
        __VLS_asFunctionalElement(__VLS_intrinsicElements.p, __VLS_intrinsicElements.p)({
            ...{ class: "text-xs opacity-70 mb-3" },
        });
        __VLS_asFunctionalElement(__VLS_intrinsicElements.strong, __VLS_intrinsicElements.strong)({});
        __VLS_asFunctionalElement(__VLS_intrinsicElements.code, __VLS_intrinsicElements.code)({});
        __VLS_asFunctionalElement(__VLS_intrinsicElements.strong, __VLS_intrinsicElements.strong)({});
        __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
            ...{ class: "flex flex-wrap gap-2" },
        });
        const __VLS_20 = {}.VButton;
        /** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
        // @ts-ignore
        const __VLS_21 = __VLS_asFunctionalComponent(__VLS_20, new __VLS_20({
            ...{ 'onClick': {} },
            disabled: (__VLS_ctx.state.busy.value),
        }));
        const __VLS_22 = __VLS_21({
            ...{ 'onClick': {} },
            disabled: (__VLS_ctx.state.busy.value),
        }, ...__VLS_functionalComponentArgsRest(__VLS_21));
        let __VLS_24;
        let __VLS_25;
        let __VLS_26;
        const __VLS_27 = {
            onClick: (__VLS_ctx.reindex)
        };
        __VLS_23.slots.default;
        (__VLS_ctx.state.busy.value ? 'Working…' : 'Reindex');
        var __VLS_23;
        const __VLS_28 = {}.VButton;
        /** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
        // @ts-ignore
        const __VLS_29 = __VLS_asFunctionalComponent(__VLS_28, new __VLS_28({
            ...{ 'onClick': {} },
            variant: "ghost",
            disabled: (__VLS_ctx.state.busy.value),
        }));
        const __VLS_30 = __VLS_29({
            ...{ 'onClick': {} },
            variant: "ghost",
            disabled: (__VLS_ctx.state.busy.value),
        }, ...__VLS_functionalComponentArgsRest(__VLS_29));
        let __VLS_32;
        let __VLS_33;
        let __VLS_34;
        const __VLS_35 = {
            onClick: (...[$event]) => {
                if (!!(!__VLS_ctx.projectId))
                    return;
                if (!(!__VLS_ctx.embeddingDisabled))
                    return;
                __VLS_ctx.rebuildConfirmOpen = true;
            }
        };
        __VLS_31.slots.default;
        var __VLS_31;
        const __VLS_36 = {}.VButton;
        /** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
        // @ts-ignore
        const __VLS_37 = __VLS_asFunctionalComponent(__VLS_36, new __VLS_36({
            ...{ 'onClick': {} },
            variant: "ghost",
            disabled: (__VLS_ctx.state.busy.value),
        }));
        const __VLS_38 = __VLS_37({
            ...{ 'onClick': {} },
            variant: "ghost",
            disabled: (__VLS_ctx.state.busy.value),
        }, ...__VLS_functionalComponentArgsRest(__VLS_37));
        let __VLS_40;
        let __VLS_41;
        let __VLS_42;
        const __VLS_43 = {
            onClick: (__VLS_ctx.refresh)
        };
        __VLS_39.slots.default;
        var __VLS_39;
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
            const __VLS_44 = {}.VButton;
            /** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
            // @ts-ignore
            const __VLS_45 = __VLS_asFunctionalComponent(__VLS_44, new __VLS_44({
                ...{ 'onClick': {} },
                variant: "danger",
            }));
            const __VLS_46 = __VLS_45({
                ...{ 'onClick': {} },
                variant: "danger",
            }, ...__VLS_functionalComponentArgsRest(__VLS_45));
            let __VLS_48;
            let __VLS_49;
            let __VLS_50;
            const __VLS_51 = {
                onClick: (__VLS_ctx.rebuild)
            };
            __VLS_47.slots.default;
            var __VLS_47;
            const __VLS_52 = {}.VButton;
            /** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
            // @ts-ignore
            const __VLS_53 = __VLS_asFunctionalComponent(__VLS_52, new __VLS_52({
                ...{ 'onClick': {} },
                variant: "ghost",
            }));
            const __VLS_54 = __VLS_53({
                ...{ 'onClick': {} },
                variant: "ghost",
            }, ...__VLS_functionalComponentArgsRest(__VLS_53));
            let __VLS_56;
            let __VLS_57;
            let __VLS_58;
            const __VLS_59 = {
                onClick: (...[$event]) => {
                    if (!!(!__VLS_ctx.projectId))
                        return;
                    if (!(!__VLS_ctx.embeddingDisabled))
                        return;
                    if (!(__VLS_ctx.rebuildConfirmOpen))
                        return;
                    __VLS_ctx.rebuildConfirmOpen = false;
                }
            };
            __VLS_55.slots.default;
            var __VLS_55;
        }
        if (__VLS_ctx.state.lastResult.value) {
            __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
                ...{ class: "mt-3 text-xs opacity-70" },
            });
            __VLS_asFunctionalElement(__VLS_intrinsicElements.strong, __VLS_intrinsicElements.strong)({});
            (__VLS_ctx.state.lastResult.value.rebuild ? 'rebuild' : 'reindex');
            (__VLS_ctx.state.lastResult.value.documentsQueued);
        }
        var __VLS_19;
        const __VLS_60 = {}.VCard;
        /** @type {[typeof __VLS_components.VCard, typeof __VLS_components.VCard, ]} */ ;
        // @ts-ignore
        const __VLS_61 = __VLS_asFunctionalComponent(__VLS_60, new __VLS_60({
            title: "Search",
        }));
        const __VLS_62 = __VLS_61({
            title: "Search",
        }, ...__VLS_functionalComponentArgsRest(__VLS_61));
        __VLS_63.slots.default;
        __VLS_asFunctionalElement(__VLS_intrinsicElements.p, __VLS_intrinsicElements.p)({
            ...{ class: "text-xs opacity-70 mb-3" },
        });
        __VLS_asFunctionalElement(__VLS_intrinsicElements.code, __VLS_intrinsicElements.code)({});
        __VLS_asFunctionalElement(__VLS_intrinsicElements.form, __VLS_intrinsicElements.form)({
            ...{ onSubmit: (__VLS_ctx.runSearch) },
            ...{ class: "flex gap-2 items-start" },
        });
        const __VLS_64 = {}.VInput;
        /** @type {[typeof __VLS_components.VInput, ]} */ ;
        // @ts-ignore
        const __VLS_65 = __VLS_asFunctionalComponent(__VLS_64, new __VLS_64({
            modelValue: (__VLS_ctx.state.searchQuery.value),
            placeholder: "Search the RAG…",
            disabled: (!__VLS_ctx.state.status.value?.exists || __VLS_ctx.state.searching.value),
            ...{ class: "flex-1" },
        }));
        const __VLS_66 = __VLS_65({
            modelValue: (__VLS_ctx.state.searchQuery.value),
            placeholder: "Search the RAG…",
            disabled: (!__VLS_ctx.state.status.value?.exists || __VLS_ctx.state.searching.value),
            ...{ class: "flex-1" },
        }, ...__VLS_functionalComponentArgsRest(__VLS_65));
        const __VLS_68 = {}.VButton;
        /** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
        // @ts-ignore
        const __VLS_69 = __VLS_asFunctionalComponent(__VLS_68, new __VLS_68({
            type: "submit",
            disabled: (__VLS_ctx.searchDisabled),
        }));
        const __VLS_70 = __VLS_69({
            type: "submit",
            disabled: (__VLS_ctx.searchDisabled),
        }, ...__VLS_functionalComponentArgsRest(__VLS_69));
        __VLS_71.slots.default;
        (__VLS_ctx.state.searching.value ? 'Searching…' : 'Search');
        var __VLS_71;
        if (!__VLS_ctx.state.status.value?.exists) {
            __VLS_asFunctionalElement(__VLS_intrinsicElements.p, __VLS_intrinsicElements.p)({
                ...{ class: "text-xs opacity-60 mt-2" },
            });
        }
        if (__VLS_ctx.state.searchError.value) {
            const __VLS_72 = {}.VAlert;
            /** @type {[typeof __VLS_components.VAlert, typeof __VLS_components.VAlert, ]} */ ;
            // @ts-ignore
            const __VLS_73 = __VLS_asFunctionalComponent(__VLS_72, new __VLS_72({
                variant: "error",
                ...{ class: "mt-3" },
            }));
            const __VLS_74 = __VLS_73({
                variant: "error",
                ...{ class: "mt-3" },
            }, ...__VLS_functionalComponentArgsRest(__VLS_73));
            __VLS_75.slots.default;
            __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({});
            (__VLS_ctx.state.searchError.value);
            var __VLS_75;
        }
        if (__VLS_ctx.state.searched.value && !__VLS_ctx.state.searchError.value) {
            if (__VLS_ctx.state.searchHits.value.length === 0) {
                __VLS_asFunctionalElement(__VLS_intrinsicElements.p, __VLS_intrinsicElements.p)({
                    ...{ class: "text-sm opacity-60 mt-3" },
                });
            }
            else {
                __VLS_asFunctionalElement(__VLS_intrinsicElements.ol, __VLS_intrinsicElements.ol)({
                    ...{ class: "mt-3 flex flex-col gap-2" },
                });
                for (const [hit, idx] of __VLS_getVForSourceType((__VLS_ctx.state.searchHits.value))) {
                    __VLS_asFunctionalElement(__VLS_intrinsicElements.li, __VLS_intrinsicElements.li)({
                        key: (`${hit.sourceRef ?? 'no-source'}-${hit.position}-${idx}`),
                        ...{ class: "border border-base-300 rounded p-3 text-sm bg-base-100/40" },
                    });
                    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
                        ...{ class: "flex justify-between gap-2 text-xs opacity-70 mb-1" },
                    });
                    __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
                        ...{ class: "font-mono truncate" },
                        title: (hit.sourceRef ?? ''),
                    });
                    (hit.sourceRef ?? '—');
                    __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
                        ...{ class: "opacity-50" },
                    });
                    (hit.position);
                    __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
                        ...{ class: "font-mono whitespace-nowrap" },
                    });
                    (__VLS_ctx.fmtScore(hit.score));
                    __VLS_asFunctionalElement(__VLS_intrinsicElements.pre, __VLS_intrinsicElements.pre)({
                        ...{ class: "whitespace-pre-wrap break-words text-xs opacity-90 m-0" },
                    });
                    (hit.content);
                }
            }
        }
        var __VLS_63;
    }
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
/** @type {__VLS_StyleScopedClasses['badge-empty']} */ ;
/** @type {__VLS_StyleScopedClasses['badge-ok']} */ ;
/** @type {__VLS_StyleScopedClasses['badge-empty']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-60']} */ ;
/** @type {__VLS_StyleScopedClasses['ml-2']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-70']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-60']} */ ;
/** @type {__VLS_StyleScopedClasses['font-mono']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
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
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-70']} */ ;
/** @type {__VLS_StyleScopedClasses['mb-3']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-2']} */ ;
/** @type {__VLS_StyleScopedClasses['items-start']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-1']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-60']} */ ;
/** @type {__VLS_StyleScopedClasses['mt-2']} */ ;
/** @type {__VLS_StyleScopedClasses['mt-3']} */ ;
/** @type {__VLS_StyleScopedClasses['text-sm']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-60']} */ ;
/** @type {__VLS_StyleScopedClasses['mt-3']} */ ;
/** @type {__VLS_StyleScopedClasses['mt-3']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-col']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-2']} */ ;
/** @type {__VLS_StyleScopedClasses['border']} */ ;
/** @type {__VLS_StyleScopedClasses['border-base-300']} */ ;
/** @type {__VLS_StyleScopedClasses['rounded']} */ ;
/** @type {__VLS_StyleScopedClasses['p-3']} */ ;
/** @type {__VLS_StyleScopedClasses['text-sm']} */ ;
/** @type {__VLS_StyleScopedClasses['bg-base-100/40']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['justify-between']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-2']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-70']} */ ;
/** @type {__VLS_StyleScopedClasses['mb-1']} */ ;
/** @type {__VLS_StyleScopedClasses['font-mono']} */ ;
/** @type {__VLS_StyleScopedClasses['truncate']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-50']} */ ;
/** @type {__VLS_StyleScopedClasses['font-mono']} */ ;
/** @type {__VLS_StyleScopedClasses['whitespace-nowrap']} */ ;
/** @type {__VLS_StyleScopedClasses['whitespace-pre-wrap']} */ ;
/** @type {__VLS_StyleScopedClasses['break-words']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-90']} */ ;
/** @type {__VLS_StyleScopedClasses['m-0']} */ ;
var __VLS_dollars;
const __VLS_self = (await import('vue')).defineComponent({
    setup() {
        return {
            VAlert: VAlert,
            VButton: VButton,
            VCard: VCard,
            VEmptyState: VEmptyState,
            VInput: VInput,
            state: state,
            rebuildConfirmOpen: rebuildConfirmOpen,
            refresh: refresh,
            reindex: reindex,
            rebuild: rebuild,
            searchDisabled: searchDisabled,
            embeddingDisabled: embeddingDisabled,
            providerMismatch: providerMismatch,
            runSearch: runSearch,
            fmtTime: fmtTime,
            fmtScore: fmtScore,
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