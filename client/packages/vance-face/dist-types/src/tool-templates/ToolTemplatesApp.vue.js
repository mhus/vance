import { computed, onMounted, ref } from 'vue';
import { EditorShell, VAlert, VButton, VCard, VEmptyState, VInput, VModal, } from '@/components';
import { useToolTemplates } from '@/composables/useToolTemplates';
import TemplateInputForm from './TemplateInputForm.vue';
import TemplateApplyResult from './TemplateApplyResult.vue';
const state = useToolTemplates();
const search = ref('');
const projectId = ref('_tenant');
const selected = ref(null);
const descriptor = ref(null);
const inputs = ref({});
const modalOpen = ref(false);
const applyResult = ref(null);
const applyError = ref(null);
onMounted(state.loadCatalog);
/** Group catalog rows by category for the list view; filters by the search box. */
const grouped = computed(() => {
    const needle = search.value.trim().toLowerCase();
    const filtered = state.catalog.value.filter((e) => {
        if (!needle)
            return true;
        const hay = `${e.name} ${e.title ?? ''} ${e.description ?? ''} ${e.category ?? ''}`.toLowerCase();
        return hay.includes(needle);
    });
    const buckets = new Map();
    for (const e of filtered) {
        const cat = e.category && e.category.trim() ? e.category : 'other';
        if (!buckets.has(cat))
            buckets.set(cat, []);
        buckets.get(cat).push(e);
    }
    return Array.from(buckets.entries())
        .sort((a, b) => a[0].localeCompare(b[0]))
        .map(([category, entries]) => ({
        category,
        entries: entries.sort((a, b) => (a.title ?? a.name).localeCompare(b.title ?? b.name)),
    }));
});
async function openDetail(entry) {
    selected.value = entry;
    descriptor.value = null;
    inputs.value = {};
    applyResult.value = null;
    applyError.value = null;
    modalOpen.value = true;
    try {
        descriptor.value = await state.describe(entry.name);
        const init = {};
        for (const input of descriptor.value.inputs ?? []) {
            if (input.defaultValue != null)
                init[input.name] = input.defaultValue;
        }
        inputs.value = init;
    }
    catch {
        /* state.error.value already carries the message */
    }
}
function closeDetail() {
    modalOpen.value = false;
    selected.value = null;
    descriptor.value = null;
    inputs.value = {};
    applyResult.value = null;
    applyError.value = null;
}
function onModalToggle(open) {
    if (!open)
        closeDetail();
}
async function onApply() {
    if (!selected.value || !descriptor.value)
        return;
    applyError.value = null;
    try {
        applyResult.value = await state.apply(selected.value.name, {
            projectId: projectId.value,
            inputs: inputs.value,
        });
    }
    catch (e) {
        applyError.value = e instanceof Error ? e.message : 'Apply failed.';
    }
}
debugger; /* PartiallyEnd: #3632/scriptSetup.vue */
const __VLS_ctx = {};
let __VLS_components;
let __VLS_directives;
const __VLS_0 = {}.EditorShell;
/** @type {[typeof __VLS_components.EditorShell, typeof __VLS_components.EditorShell, ]} */ ;
// @ts-ignore
const __VLS_1 = __VLS_asFunctionalComponent(__VLS_0, new __VLS_0({
    title: (__VLS_ctx.$t('toolTemplates.pageTitle')),
}));
const __VLS_2 = __VLS_1({
    title: (__VLS_ctx.$t('toolTemplates.pageTitle')),
}, ...__VLS_functionalComponentArgsRest(__VLS_1));
var __VLS_4 = {};
__VLS_3.slots.default;
__VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
    ...{ class: "p-6 flex flex-col gap-4 max-w-4xl" },
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
const __VLS_9 = {}.VCard;
/** @type {[typeof __VLS_components.VCard, typeof __VLS_components.VCard, ]} */ ;
// @ts-ignore
const __VLS_10 = __VLS_asFunctionalComponent(__VLS_9, new __VLS_9({}));
const __VLS_11 = __VLS_10({}, ...__VLS_functionalComponentArgsRest(__VLS_10));
__VLS_12.slots.default;
__VLS_asFunctionalElement(__VLS_intrinsicElements.p, __VLS_intrinsicElements.p)({
    ...{ class: "text-sm opacity-70" },
});
(__VLS_ctx.$t('toolTemplates.intro'));
var __VLS_12;
__VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
    ...{ class: "flex items-center gap-3" },
});
const __VLS_13 = {}.VInput;
/** @type {[typeof __VLS_components.VInput, ]} */ ;
// @ts-ignore
const __VLS_14 = __VLS_asFunctionalComponent(__VLS_13, new __VLS_13({
    modelValue: (__VLS_ctx.search),
    placeholder: (__VLS_ctx.$t('toolTemplates.searchPlaceholder')),
    ...{ class: "flex-1" },
}));
const __VLS_15 = __VLS_14({
    modelValue: (__VLS_ctx.search),
    placeholder: (__VLS_ctx.$t('toolTemplates.searchPlaceholder')),
    ...{ class: "flex-1" },
}, ...__VLS_functionalComponentArgsRest(__VLS_14));
__VLS_asFunctionalElement(__VLS_intrinsicElements.label, __VLS_intrinsicElements.label)({
    ...{ class: "flex items-center gap-2 text-xs opacity-70" },
});
__VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({});
(__VLS_ctx.$t('toolTemplates.projectIdLabel'));
const __VLS_17 = {}.VInput;
/** @type {[typeof __VLS_components.VInput, ]} */ ;
// @ts-ignore
const __VLS_18 = __VLS_asFunctionalComponent(__VLS_17, new __VLS_17({
    modelValue: (__VLS_ctx.projectId),
    ...{ class: "w-48 font-mono" },
}));
const __VLS_19 = __VLS_18({
    modelValue: (__VLS_ctx.projectId),
    ...{ class: "w-48 font-mono" },
}, ...__VLS_functionalComponentArgsRest(__VLS_18));
if (__VLS_ctx.state.loading.value) {
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "text-sm opacity-60" },
    });
    (__VLS_ctx.$t('toolTemplates.loading'));
}
else if (__VLS_ctx.grouped.length === 0) {
    const __VLS_21 = {}.VEmptyState;
    /** @type {[typeof __VLS_components.VEmptyState, ]} */ ;
    // @ts-ignore
    const __VLS_22 = __VLS_asFunctionalComponent(__VLS_21, new __VLS_21({
        headline: (__VLS_ctx.$t('toolTemplates.empty.headline')),
        body: (__VLS_ctx.$t('toolTemplates.empty.body')),
    }));
    const __VLS_23 = __VLS_22({
        headline: (__VLS_ctx.$t('toolTemplates.empty.headline')),
        body: (__VLS_ctx.$t('toolTemplates.empty.body')),
    }, ...__VLS_functionalComponentArgsRest(__VLS_22));
}
else {
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "flex flex-col gap-6" },
    });
    for (const [bucket] of __VLS_getVForSourceType((__VLS_ctx.grouped))) {
        __VLS_asFunctionalElement(__VLS_intrinsicElements.section, __VLS_intrinsicElements.section)({
            key: (bucket.category),
            ...{ class: "flex flex-col gap-2" },
        });
        __VLS_asFunctionalElement(__VLS_intrinsicElements.h2, __VLS_intrinsicElements.h2)({
            ...{ class: "text-xs uppercase tracking-wide opacity-60" },
        });
        (bucket.category);
        for (const [entry] of __VLS_getVForSourceType((bucket.entries))) {
            const __VLS_25 = {}.VCard;
            /** @type {[typeof __VLS_components.VCard, typeof __VLS_components.VCard, ]} */ ;
            // @ts-ignore
            const __VLS_26 = __VLS_asFunctionalComponent(__VLS_25, new __VLS_25({
                ...{ 'onClick': {} },
                key: (entry.name),
                ...{ class: "cursor-pointer hover:bg-base-200/40" },
            }));
            const __VLS_27 = __VLS_26({
                ...{ 'onClick': {} },
                key: (entry.name),
                ...{ class: "cursor-pointer hover:bg-base-200/40" },
            }, ...__VLS_functionalComponentArgsRest(__VLS_26));
            let __VLS_29;
            let __VLS_30;
            let __VLS_31;
            const __VLS_32 = {
                onClick: (...[$event]) => {
                    if (!!(__VLS_ctx.state.loading.value))
                        return;
                    if (!!(__VLS_ctx.grouped.length === 0))
                        return;
                    __VLS_ctx.openDetail(entry);
                }
            };
            __VLS_28.slots.default;
            __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
                ...{ class: "flex items-start justify-between gap-3" },
            });
            __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
                ...{ class: "flex flex-col gap-1 min-w-0" },
            });
            __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
                ...{ class: "font-medium" },
            });
            (entry.title ?? entry.name);
            __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
                ...{ class: "font-mono text-xs opacity-50" },
            });
            (entry.name);
            if (entry.description) {
                __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
                    ...{ class: "text-sm opacity-70" },
                });
                (entry.description);
            }
            const __VLS_33 = {}.VButton;
            /** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
            // @ts-ignore
            const __VLS_34 = __VLS_asFunctionalComponent(__VLS_33, new __VLS_33({
                ...{ 'onClick': {} },
                variant: "primary",
            }));
            const __VLS_35 = __VLS_34({
                ...{ 'onClick': {} },
                variant: "primary",
            }, ...__VLS_functionalComponentArgsRest(__VLS_34));
            let __VLS_37;
            let __VLS_38;
            let __VLS_39;
            const __VLS_40 = {
                onClick: (...[$event]) => {
                    if (!!(__VLS_ctx.state.loading.value))
                        return;
                    if (!!(__VLS_ctx.grouped.length === 0))
                        return;
                    __VLS_ctx.openDetail(entry);
                }
            };
            __VLS_36.slots.default;
            (__VLS_ctx.$t('toolTemplates.install'));
            var __VLS_36;
            var __VLS_28;
        }
    }
}
const __VLS_41 = {}.VModal;
/** @type {[typeof __VLS_components.VModal, typeof __VLS_components.VModal, ]} */ ;
// @ts-ignore
const __VLS_42 = __VLS_asFunctionalComponent(__VLS_41, new __VLS_41({
    ...{ 'onUpdate:modelValue': {} },
    modelValue: (__VLS_ctx.modalOpen),
    title: (__VLS_ctx.descriptor?.title ?? __VLS_ctx.selected?.title ?? __VLS_ctx.selected?.name ?? ''),
}));
const __VLS_43 = __VLS_42({
    ...{ 'onUpdate:modelValue': {} },
    modelValue: (__VLS_ctx.modalOpen),
    title: (__VLS_ctx.descriptor?.title ?? __VLS_ctx.selected?.title ?? __VLS_ctx.selected?.name ?? ''),
}, ...__VLS_functionalComponentArgsRest(__VLS_42));
let __VLS_45;
let __VLS_46;
let __VLS_47;
const __VLS_48 = {
    'onUpdate:modelValue': (__VLS_ctx.onModalToggle)
};
__VLS_44.slots.default;
if (__VLS_ctx.state.busy.value && !__VLS_ctx.descriptor) {
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "text-sm opacity-60" },
    });
    (__VLS_ctx.$t('toolTemplates.loading'));
}
else if (__VLS_ctx.descriptor && !__VLS_ctx.applyResult) {
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "flex flex-col gap-4" },
    });
    if (__VLS_ctx.descriptor.description) {
        __VLS_asFunctionalElement(__VLS_intrinsicElements.p, __VLS_intrinsicElements.p)({
            ...{ class: "text-sm opacity-70 whitespace-pre-wrap" },
        });
        (__VLS_ctx.descriptor.description);
    }
    if (__VLS_ctx.descriptor.inputs && __VLS_ctx.descriptor.inputs.length > 0) {
        /** @type {[typeof TemplateInputForm, ]} */ ;
        // @ts-ignore
        const __VLS_49 = __VLS_asFunctionalComponent(TemplateInputForm, new TemplateInputForm({
            inputs: (__VLS_ctx.descriptor.inputs),
            modelValue: (__VLS_ctx.inputs),
        }));
        const __VLS_50 = __VLS_49({
            inputs: (__VLS_ctx.descriptor.inputs),
            modelValue: (__VLS_ctx.inputs),
        }, ...__VLS_functionalComponentArgsRest(__VLS_49));
    }
    else {
        __VLS_asFunctionalElement(__VLS_intrinsicElements.p, __VLS_intrinsicElements.p)({
            ...{ class: "text-sm opacity-60" },
        });
        (__VLS_ctx.$t('toolTemplates.noInputs'));
    }
    if (__VLS_ctx.applyError) {
        const __VLS_52 = {}.VAlert;
        /** @type {[typeof __VLS_components.VAlert, typeof __VLS_components.VAlert, ]} */ ;
        // @ts-ignore
        const __VLS_53 = __VLS_asFunctionalComponent(__VLS_52, new __VLS_52({
            variant: "error",
        }));
        const __VLS_54 = __VLS_53({
            variant: "error",
        }, ...__VLS_functionalComponentArgsRest(__VLS_53));
        __VLS_55.slots.default;
        __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({});
        (__VLS_ctx.applyError);
        var __VLS_55;
    }
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "flex justify-end gap-2 pt-2" },
    });
    const __VLS_56 = {}.VButton;
    /** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
    // @ts-ignore
    const __VLS_57 = __VLS_asFunctionalComponent(__VLS_56, new __VLS_56({
        ...{ 'onClick': {} },
        variant: "ghost",
    }));
    const __VLS_58 = __VLS_57({
        ...{ 'onClick': {} },
        variant: "ghost",
    }, ...__VLS_functionalComponentArgsRest(__VLS_57));
    let __VLS_60;
    let __VLS_61;
    let __VLS_62;
    const __VLS_63 = {
        onClick: (__VLS_ctx.closeDetail)
    };
    __VLS_59.slots.default;
    (__VLS_ctx.$t('common.cancel'));
    var __VLS_59;
    const __VLS_64 = {}.VButton;
    /** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
    // @ts-ignore
    const __VLS_65 = __VLS_asFunctionalComponent(__VLS_64, new __VLS_64({
        ...{ 'onClick': {} },
        variant: "primary",
        loading: (__VLS_ctx.state.busy.value),
    }));
    const __VLS_66 = __VLS_65({
        ...{ 'onClick': {} },
        variant: "primary",
        loading: (__VLS_ctx.state.busy.value),
    }, ...__VLS_functionalComponentArgsRest(__VLS_65));
    let __VLS_68;
    let __VLS_69;
    let __VLS_70;
    const __VLS_71 = {
        onClick: (__VLS_ctx.onApply)
    };
    __VLS_67.slots.default;
    (__VLS_ctx.$t('toolTemplates.apply'));
    var __VLS_67;
}
else if (__VLS_ctx.applyResult) {
    /** @type {[typeof TemplateApplyResult, ]} */ ;
    // @ts-ignore
    const __VLS_72 = __VLS_asFunctionalComponent(TemplateApplyResult, new TemplateApplyResult({
        ...{ 'onClose': {} },
        result: (__VLS_ctx.applyResult),
    }));
    const __VLS_73 = __VLS_72({
        ...{ 'onClose': {} },
        result: (__VLS_ctx.applyResult),
    }, ...__VLS_functionalComponentArgsRest(__VLS_72));
    let __VLS_75;
    let __VLS_76;
    let __VLS_77;
    const __VLS_78 = {
        onClose: (__VLS_ctx.closeDetail)
    };
    var __VLS_74;
}
var __VLS_44;
var __VLS_3;
/** @type {__VLS_StyleScopedClasses['p-6']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-col']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-4']} */ ;
/** @type {__VLS_StyleScopedClasses['max-w-4xl']} */ ;
/** @type {__VLS_StyleScopedClasses['text-sm']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-70']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['items-center']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-3']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-1']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['items-center']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-2']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-70']} */ ;
/** @type {__VLS_StyleScopedClasses['w-48']} */ ;
/** @type {__VLS_StyleScopedClasses['font-mono']} */ ;
/** @type {__VLS_StyleScopedClasses['text-sm']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-60']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-col']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-6']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-col']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-2']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['uppercase']} */ ;
/** @type {__VLS_StyleScopedClasses['tracking-wide']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-60']} */ ;
/** @type {__VLS_StyleScopedClasses['cursor-pointer']} */ ;
/** @type {__VLS_StyleScopedClasses['hover:bg-base-200/40']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['items-start']} */ ;
/** @type {__VLS_StyleScopedClasses['justify-between']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-3']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-col']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-1']} */ ;
/** @type {__VLS_StyleScopedClasses['min-w-0']} */ ;
/** @type {__VLS_StyleScopedClasses['font-medium']} */ ;
/** @type {__VLS_StyleScopedClasses['font-mono']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-50']} */ ;
/** @type {__VLS_StyleScopedClasses['text-sm']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-70']} */ ;
/** @type {__VLS_StyleScopedClasses['text-sm']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-60']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-col']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-4']} */ ;
/** @type {__VLS_StyleScopedClasses['text-sm']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-70']} */ ;
/** @type {__VLS_StyleScopedClasses['whitespace-pre-wrap']} */ ;
/** @type {__VLS_StyleScopedClasses['text-sm']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-60']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['justify-end']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-2']} */ ;
/** @type {__VLS_StyleScopedClasses['pt-2']} */ ;
var __VLS_dollars;
const __VLS_self = (await import('vue')).defineComponent({
    setup() {
        return {
            EditorShell: EditorShell,
            VAlert: VAlert,
            VButton: VButton,
            VCard: VCard,
            VEmptyState: VEmptyState,
            VInput: VInput,
            VModal: VModal,
            TemplateInputForm: TemplateInputForm,
            TemplateApplyResult: TemplateApplyResult,
            state: state,
            search: search,
            projectId: projectId,
            selected: selected,
            descriptor: descriptor,
            inputs: inputs,
            modalOpen: modalOpen,
            applyResult: applyResult,
            applyError: applyError,
            grouped: grouped,
            openDetail: openDetail,
            closeDetail: closeDetail,
            onModalToggle: onModalToggle,
            onApply: onApply,
        };
    },
});
export default (await import('vue')).defineComponent({
    setup() {
        return {};
    },
});
; /* PartiallyEnd: #4569/main.vue */
//# sourceMappingURL=ToolTemplatesApp.vue.js.map