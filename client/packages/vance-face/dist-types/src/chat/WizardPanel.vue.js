import { computed, onMounted, ref, watch } from 'vue';
import { useI18n } from 'vue-i18n';
import { getWizard, listWizards, renderWizard, RestError, } from '@vance/shared';
import { FormFields, VAlert, VButton, VEmptyState } from '@components/index';
const props = withDefaults(defineProps(), {
    projectId: undefined,
    sessionKey: undefined,
});
const emit = defineEmits();
const { t } = useI18n();
const mode = ref('list');
const listing = ref([]);
const listLoading = ref(false);
const listError = ref(null);
const active = ref(null);
const formLoading = ref(false);
const formValues = ref({});
const errors = ref({});
const submitting = ref(false);
const formError = ref(null);
async function refreshListing() {
    listLoading.value = true;
    listError.value = null;
    try {
        const res = await listWizards(props.projectId);
        listing.value = res.wizards ?? [];
    }
    catch (err) {
        listError.value = err instanceof RestError ? err.message : String(err);
    }
    finally {
        listLoading.value = false;
    }
}
const groupedByCategory = computed(() => {
    const groups = new Map();
    for (const w of listing.value) {
        const cat = w.category ?? '';
        if (!groups.has(cat))
            groups.set(cat, []);
        groups.get(cat).push(w);
    }
    for (const list of groups.values()) {
        list.sort((a, b) => a.title.localeCompare(b.title));
    }
    return [...groups.entries()].sort((a, b) => a[0].localeCompare(b[0]));
});
async function openWizard(name, prefill) {
    formLoading.value = true;
    formError.value = null;
    errors.value = {};
    try {
        active.value = await getWizard(name, props.projectId);
        const initial = initialValuesFor(active.value.fields ?? []);
        if (prefill) {
            mergePrefill(initial, prefill, active.value.fields ?? []);
        }
        formValues.value = initial;
        mode.value = 'form';
    }
    catch (err) {
        formError.value = err instanceof RestError ? err.message : String(err);
    }
    finally {
        formLoading.value = false;
    }
}
/**
 * Apply prefill values from a {@code vance:/wizards/<name>?...} URI
 * into the freshly-initialized form. Unknown keys are ignored
 * (rather than throwing) so renames in the target wizard don't
 * break in-flight follow-up links.
 */
function mergePrefill(target, prefill, fields) {
    const fieldByName = new Map(fields.map((f) => [f.name, f]));
    for (const [key, value] of Object.entries(prefill)) {
        const field = fieldByName.get(key);
        if (!field)
            continue;
        if (field.type === 'multi_select') {
            // Wire form: comma-separated. Skip when empty.
            target[key] = value ? value.split(',').map((s) => s.trim()) : [];
        }
        else if (field.type === 'repeat') {
            // Repeat prefill via URL is not supported in v1 — the link size
            // would explode and the encoding is fragile. Skip silently.
            continue;
        }
        else {
            target[key] = value;
        }
    }
}
function initialValuesFor(fields) {
    const out = {};
    for (const f of fields) {
        if (f.type === 'multi_select') {
            out[f.name] = [];
        }
        else if (f.type === 'repeat') {
            out[f.name] = [];
        }
        else if (f.defaultValue !== undefined && f.defaultValue !== null) {
            out[f.name] = f.defaultValue;
        }
        else if (f.type === 'boolean') {
            out[f.name] = 'false';
        }
        else {
            out[f.name] = '';
        }
    }
    return out;
}
function backToList() {
    mode.value = 'list';
    active.value = null;
    formValues.value = {};
    errors.value = {};
    formError.value = null;
}
async function submit() {
    if (!active.value)
        return;
    submitting.value = true;
    formError.value = null;
    errors.value = {};
    try {
        const res = await renderWizard(active.value.name, formValues.value, props.projectId);
        emit('promptReady', res.prompt);
        backToList();
    }
    catch (err) {
        if (err instanceof RestError) {
            formError.value = err.message;
            // Light heuristic: the BAD_REQUEST message ends with a list of
            // "field: error" pairs joined by "; ". Parse what we can so the
            // form can highlight at least the first offending field.
            const parsed = parseValidationMessage(err.message);
            if (parsed)
                errors.value = parsed;
        }
        else {
            formError.value = String(err);
        }
    }
    finally {
        submitting.value = false;
    }
}
function parseValidationMessage(msg) {
    const idx = msg.indexOf('form validation failed: ');
    if (idx < 0)
        return null;
    const tail = msg.slice(idx + 'form validation failed: '.length);
    const out = {};
    for (const part of tail.split(';')) {
        const colon = part.indexOf(':');
        if (colon < 0)
            continue;
        const field = part.slice(0, colon).trim();
        const code = part.slice(colon + 1).trim();
        if (field && code)
            out[field] = code;
    }
    return Object.keys(out).length > 0 ? out : null;
}
onMounted(refreshListing);
// Re-list when the session changes (different project may be active).
watch(() => props.sessionKey, refreshListing);
watch(() => props.projectId, refreshListing);
// Imperative handle for the host editor: ChatView's vance:-wizard click
// handler calls {@code wizardPanelRef.value?.openWizard(name, prefill)}
// after switching the side-panel tab.
const __VLS_exposed = { openWizard };
defineExpose(__VLS_exposed);
debugger; /* PartiallyEnd: #3632/scriptSetup.vue */
const __VLS_withDefaultsArg = (function (t) { return t; })({
    projectId: undefined,
    sessionKey: undefined,
});
const __VLS_ctx = {};
let __VLS_components;
let __VLS_directives;
__VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
    ...{ class: "p-3 flex flex-col gap-3 min-h-0" },
});
if (__VLS_ctx.mode === 'list') {
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "flex items-center justify-between px-1" },
    });
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "text-xs uppercase tracking-wide opacity-60 font-semibold" },
    });
    (__VLS_ctx.t('chat.wizards.title'));
    const __VLS_0 = {}.VButton;
    /** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
    // @ts-ignore
    const __VLS_1 = __VLS_asFunctionalComponent(__VLS_0, new __VLS_0({
        ...{ 'onClick': {} },
        variant: "ghost",
        size: "sm",
        loading: (__VLS_ctx.listLoading),
    }));
    const __VLS_2 = __VLS_1({
        ...{ 'onClick': {} },
        variant: "ghost",
        size: "sm",
        loading: (__VLS_ctx.listLoading),
    }, ...__VLS_functionalComponentArgsRest(__VLS_1));
    let __VLS_4;
    let __VLS_5;
    let __VLS_6;
    const __VLS_7 = {
        onClick: (__VLS_ctx.refreshListing)
    };
    __VLS_3.slots.default;
    var __VLS_3;
    if (__VLS_ctx.listError) {
        const __VLS_8 = {}.VAlert;
        /** @type {[typeof __VLS_components.VAlert, typeof __VLS_components.VAlert, ]} */ ;
        // @ts-ignore
        const __VLS_9 = __VLS_asFunctionalComponent(__VLS_8, new __VLS_8({
            variant: "error",
        }));
        const __VLS_10 = __VLS_9({
            variant: "error",
        }, ...__VLS_functionalComponentArgsRest(__VLS_9));
        __VLS_11.slots.default;
        (__VLS_ctx.listError);
        var __VLS_11;
    }
    else if (!__VLS_ctx.listLoading && __VLS_ctx.listing.length === 0) {
        const __VLS_12 = {}.VEmptyState;
        /** @type {[typeof __VLS_components.VEmptyState, ]} */ ;
        // @ts-ignore
        const __VLS_13 = __VLS_asFunctionalComponent(__VLS_12, new __VLS_12({
            headline: (__VLS_ctx.t('chat.wizards.emptyHeadline')),
            body: (__VLS_ctx.t('chat.wizards.emptyBody')),
        }));
        const __VLS_14 = __VLS_13({
            headline: (__VLS_ctx.t('chat.wizards.emptyHeadline')),
            body: (__VLS_ctx.t('chat.wizards.emptyBody')),
        }, ...__VLS_functionalComponentArgsRest(__VLS_13));
    }
    for (const [[cat, group]] of __VLS_getVForSourceType((__VLS_ctx.groupedByCategory))) {
        __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
            key: (cat),
            ...{ class: "flex flex-col gap-1.5" },
        });
        if (cat) {
            __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
                ...{ class: "text-[10px] uppercase tracking-wide opacity-50 font-semibold px-1 mt-1" },
            });
            (cat);
        }
        for (const [w] of __VLS_getVForSourceType((group))) {
            __VLS_asFunctionalElement(__VLS_intrinsicElements.button, __VLS_intrinsicElements.button)({
                ...{ onClick: (...[$event]) => {
                        if (!(__VLS_ctx.mode === 'list'))
                            return;
                        __VLS_ctx.openWizard(w.name);
                    } },
                key: (w.name),
                type: "button",
                ...{ class: "bg-base-200 hover:bg-base-300 rounded px-2.5 py-2 text-left text-sm transition-colors" },
            });
            __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
                ...{ class: "flex items-center gap-1.5" },
            });
            if (w.icon) {
                __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
                    ...{ class: "text-xs opacity-60 font-mono" },
                });
                (w.icon);
            }
            __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
                ...{ class: "font-semibold truncate" },
            });
            (w.title);
            __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
                ...{ class: "text-xs opacity-70 mt-0.5 line-clamp-2" },
            });
            (w.description);
        }
    }
}
else if (__VLS_ctx.mode === 'form' && __VLS_ctx.active) {
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "flex items-center gap-2 px-1" },
    });
    const __VLS_16 = {}.VButton;
    /** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
    // @ts-ignore
    const __VLS_17 = __VLS_asFunctionalComponent(__VLS_16, new __VLS_16({
        ...{ 'onClick': {} },
        variant: "ghost",
        size: "sm",
    }));
    const __VLS_18 = __VLS_17({
        ...{ 'onClick': {} },
        variant: "ghost",
        size: "sm",
    }, ...__VLS_functionalComponentArgsRest(__VLS_17));
    let __VLS_20;
    let __VLS_21;
    let __VLS_22;
    const __VLS_23 = {
        onClick: (__VLS_ctx.backToList)
    };
    __VLS_19.slots.default;
    var __VLS_19;
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "flex-1 min-w-0" },
    });
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "text-sm font-semibold truncate" },
    });
    (__VLS_ctx.active.title?.[__VLS_ctx.$i18n.locale] ?? __VLS_ctx.active.title?.en ?? __VLS_ctx.active.name);
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "text-xs opacity-70 line-clamp-2" },
    });
    (__VLS_ctx.active.description?.[__VLS_ctx.$i18n.locale] ?? __VLS_ctx.active.description?.en ?? '');
    if (__VLS_ctx.formError) {
        const __VLS_24 = {}.VAlert;
        /** @type {[typeof __VLS_components.VAlert, typeof __VLS_components.VAlert, ]} */ ;
        // @ts-ignore
        const __VLS_25 = __VLS_asFunctionalComponent(__VLS_24, new __VLS_24({
            variant: "error",
        }));
        const __VLS_26 = __VLS_25({
            variant: "error",
        }, ...__VLS_functionalComponentArgsRest(__VLS_25));
        __VLS_27.slots.default;
        (__VLS_ctx.formError);
        var __VLS_27;
    }
    if (!__VLS_ctx.formLoading) {
        const __VLS_28 = {}.FormFields;
        /** @type {[typeof __VLS_components.FormFields, ]} */ ;
        // @ts-ignore
        const __VLS_29 = __VLS_asFunctionalComponent(__VLS_28, new __VLS_28({
            modelValue: (__VLS_ctx.formValues),
            fields: (__VLS_ctx.active.fields ?? []),
            errors: (__VLS_ctx.errors),
            disabled: (__VLS_ctx.submitting),
        }));
        const __VLS_30 = __VLS_29({
            modelValue: (__VLS_ctx.formValues),
            fields: (__VLS_ctx.active.fields ?? []),
            errors: (__VLS_ctx.errors),
            disabled: (__VLS_ctx.submitting),
        }, ...__VLS_functionalComponentArgsRest(__VLS_29));
    }
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "flex gap-2 justify-end mt-2" },
    });
    const __VLS_32 = {}.VButton;
    /** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
    // @ts-ignore
    const __VLS_33 = __VLS_asFunctionalComponent(__VLS_32, new __VLS_32({
        ...{ 'onClick': {} },
        variant: "ghost",
        size: "sm",
        disabled: (__VLS_ctx.submitting),
    }));
    const __VLS_34 = __VLS_33({
        ...{ 'onClick': {} },
        variant: "ghost",
        size: "sm",
        disabled: (__VLS_ctx.submitting),
    }, ...__VLS_functionalComponentArgsRest(__VLS_33));
    let __VLS_36;
    let __VLS_37;
    let __VLS_38;
    const __VLS_39 = {
        onClick: (__VLS_ctx.backToList)
    };
    __VLS_35.slots.default;
    (__VLS_ctx.t('common.cancel'));
    var __VLS_35;
    const __VLS_40 = {}.VButton;
    /** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
    // @ts-ignore
    const __VLS_41 = __VLS_asFunctionalComponent(__VLS_40, new __VLS_40({
        ...{ 'onClick': {} },
        variant: "primary",
        size: "sm",
        loading: (__VLS_ctx.submitting),
    }));
    const __VLS_42 = __VLS_41({
        ...{ 'onClick': {} },
        variant: "primary",
        size: "sm",
        loading: (__VLS_ctx.submitting),
    }, ...__VLS_functionalComponentArgsRest(__VLS_41));
    let __VLS_44;
    let __VLS_45;
    let __VLS_46;
    const __VLS_47 = {
        onClick: (__VLS_ctx.submit)
    };
    __VLS_43.slots.default;
    (__VLS_ctx.t('chat.wizards.submit'));
    var __VLS_43;
}
/** @type {__VLS_StyleScopedClasses['p-3']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-col']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-3']} */ ;
/** @type {__VLS_StyleScopedClasses['min-h-0']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['items-center']} */ ;
/** @type {__VLS_StyleScopedClasses['justify-between']} */ ;
/** @type {__VLS_StyleScopedClasses['px-1']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['uppercase']} */ ;
/** @type {__VLS_StyleScopedClasses['tracking-wide']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-60']} */ ;
/** @type {__VLS_StyleScopedClasses['font-semibold']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-col']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-1.5']} */ ;
/** @type {__VLS_StyleScopedClasses['text-[10px]']} */ ;
/** @type {__VLS_StyleScopedClasses['uppercase']} */ ;
/** @type {__VLS_StyleScopedClasses['tracking-wide']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-50']} */ ;
/** @type {__VLS_StyleScopedClasses['font-semibold']} */ ;
/** @type {__VLS_StyleScopedClasses['px-1']} */ ;
/** @type {__VLS_StyleScopedClasses['mt-1']} */ ;
/** @type {__VLS_StyleScopedClasses['bg-base-200']} */ ;
/** @type {__VLS_StyleScopedClasses['hover:bg-base-300']} */ ;
/** @type {__VLS_StyleScopedClasses['rounded']} */ ;
/** @type {__VLS_StyleScopedClasses['px-2.5']} */ ;
/** @type {__VLS_StyleScopedClasses['py-2']} */ ;
/** @type {__VLS_StyleScopedClasses['text-left']} */ ;
/** @type {__VLS_StyleScopedClasses['text-sm']} */ ;
/** @type {__VLS_StyleScopedClasses['transition-colors']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['items-center']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-1.5']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-60']} */ ;
/** @type {__VLS_StyleScopedClasses['font-mono']} */ ;
/** @type {__VLS_StyleScopedClasses['font-semibold']} */ ;
/** @type {__VLS_StyleScopedClasses['truncate']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-70']} */ ;
/** @type {__VLS_StyleScopedClasses['mt-0.5']} */ ;
/** @type {__VLS_StyleScopedClasses['line-clamp-2']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['items-center']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-2']} */ ;
/** @type {__VLS_StyleScopedClasses['px-1']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-1']} */ ;
/** @type {__VLS_StyleScopedClasses['min-w-0']} */ ;
/** @type {__VLS_StyleScopedClasses['text-sm']} */ ;
/** @type {__VLS_StyleScopedClasses['font-semibold']} */ ;
/** @type {__VLS_StyleScopedClasses['truncate']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-70']} */ ;
/** @type {__VLS_StyleScopedClasses['line-clamp-2']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-2']} */ ;
/** @type {__VLS_StyleScopedClasses['justify-end']} */ ;
/** @type {__VLS_StyleScopedClasses['mt-2']} */ ;
var __VLS_dollars;
const __VLS_self = (await import('vue')).defineComponent({
    setup() {
        return {
            FormFields: FormFields,
            VAlert: VAlert,
            VButton: VButton,
            VEmptyState: VEmptyState,
            t: t,
            mode: mode,
            listing: listing,
            listLoading: listLoading,
            listError: listError,
            active: active,
            formLoading: formLoading,
            formValues: formValues,
            errors: errors,
            submitting: submitting,
            formError: formError,
            refreshListing: refreshListing,
            groupedByCategory: groupedByCategory,
            openWizard: openWizard,
            backToList: backToList,
            submit: submit,
        };
    },
    __typeEmits: {},
    __typeProps: {},
    props: {},
});
export default (await import('vue')).defineComponent({
    setup() {
        return {
            ...__VLS_exposed,
        };
    },
    __typeEmits: {},
    __typeProps: {},
    props: {},
});
; /* PartiallyEnd: #4569/main.vue */
//# sourceMappingURL=WizardPanel.vue.js.map