import { VAlert, VButton, VCard } from '@vance/components';
import { computed, ref, watch } from 'vue';
import { useI18n } from 'vue-i18n';
import { applySettingForm, getSettingForm, resetSettingForm, RestError, validateSettingForm, } from '@vance/shared';
import FormFields from './FormFields.vue';
const props = withDefaults(defineProps(), {
    projectId: undefined,
    reloadKey: undefined,
});
const emit = defineEmits();
const { t, locale } = useI18n();
const form = ref(null);
const loading = ref(false);
const loadError = ref(null);
const values = ref({});
const errors = ref({});
const submitting = ref(false);
const submitError = ref(null);
const preview = ref(null);
async function loadForm() {
    loading.value = true;
    loadError.value = null;
    preview.value = null;
    try {
        form.value = await getSettingForm(props.name, props.projectId);
        values.value = initialValuesFor(form.value.fields ?? []);
    }
    catch (err) {
        loadError.value = err instanceof RestError ? err.message : String(err);
        form.value = null;
    }
    finally {
        loading.value = false;
    }
}
watch(() => [props.name, props.projectId, props.reloadKey], () => {
    void loadForm();
}, { immediate: true });
function initialValuesFor(fields) {
    const out = {};
    for (const f of fields) {
        // Passwords always start blank (PASSWORD-empty == "do not modify").
        if (f.type === 'password') {
            out[f.name] = '';
            continue;
        }
        // Live cascade value pre-fills direct-mapped fields when available.
        if (f.currentValue !== undefined && f.currentValue !== null && f.currentValue !== '') {
            out[f.name] = f.currentValue;
            continue;
        }
        if (f.type === 'multi_select')
            out[f.name] = [];
        else if (f.type === 'repeat')
            out[f.name] = [];
        else if (f.defaultValue !== undefined && f.defaultValue !== null)
            out[f.name] = f.defaultValue;
        else if (f.type === 'boolean')
            out[f.name] = 'false';
        else
            out[f.name] = '';
    }
    return out;
}
const directMappedFields = computed(() => {
    if (!form.value)
        return [];
    return (form.value.fields ?? []).filter((f) => f.bindsTo);
});
const computedSettings = computed(() => {
    return form.value?.settings ?? [];
});
async function onApply() {
    if (!form.value)
        return;
    submitting.value = true;
    submitError.value = null;
    errors.value = {};
    try {
        const res = await applySettingForm(form.value.name, values.value, props.projectId, locale.value);
        preview.value = { kind: 'apply', entries: res.applied ?? [] };
        emit('applied', res.applied ?? []);
        // Reload so the currentValue/currentSource indicators reflect the new state.
        await loadForm();
    }
    catch (err) {
        handleSubmitError(err);
    }
    finally {
        submitting.value = false;
    }
}
async function onValidate() {
    if (!form.value)
        return;
    submitting.value = true;
    submitError.value = null;
    errors.value = {};
    try {
        const res = await validateSettingForm(form.value.name, values.value, props.projectId, locale.value);
        preview.value = { kind: 'validate', entries: res.applied ?? [] };
    }
    catch (err) {
        handleSubmitError(err);
    }
    finally {
        submitting.value = false;
    }
}
async function onReset() {
    if (!form.value)
        return;
    if (!confirm(t('settingForms.confirmReset')))
        return;
    submitting.value = true;
    submitError.value = null;
    errors.value = {};
    try {
        const res = await resetSettingForm(form.value.name, props.projectId);
        preview.value = { kind: 'reset', entries: res.applied ?? [] };
        emit('applied', res.applied ?? []);
        await loadForm();
    }
    catch (err) {
        handleSubmitError(err);
    }
    finally {
        submitting.value = false;
    }
}
function handleSubmitError(err) {
    if (err instanceof RestError) {
        submitError.value = err.message;
        const parsed = parseValidationMessage(err.message);
        if (parsed)
            errors.value = parsed;
    }
    else {
        submitError.value = String(err);
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
function fieldDisplayValue(f) {
    if (f.currentValue === undefined || f.currentValue === null) {
        return t('settingForms.unset');
    }
    return f.currentValue;
}
function sourceLabel(src) {
    if (!src)
        return t('settingForms.unset');
    if (src === '_tenant')
        return t('settingForms.sourceTenant');
    if (src.startsWith('_user_'))
        return t('settingForms.sourceUser', { user: src.slice('_user_'.length) });
    return t('settingForms.sourceProject', { project: src });
}
function actionLabel(action) {
    return t('settingForms.actions.' + action);
}
debugger; /* PartiallyEnd: #3632/scriptSetup.vue */
const __VLS_withDefaultsArg = (function (t) { return t; })({
    projectId: undefined,
    reloadKey: undefined,
});
const __VLS_ctx = {};
let __VLS_components;
let __VLS_directives;
__VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
    ...{ class: "flex flex-col gap-4 min-h-0" },
});
if (__VLS_ctx.loadError) {
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
    (__VLS_ctx.loadError);
    var __VLS_3;
}
if (__VLS_ctx.loading) {
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "text-sm opacity-70" },
    });
    (__VLS_ctx.t('common.loading'));
}
else if (__VLS_ctx.form) {
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({});
    __VLS_asFunctionalElement(__VLS_intrinsicElements.h2, __VLS_intrinsicElements.h2)({
        ...{ class: "text-lg font-semibold" },
    });
    (__VLS_ctx.form.title);
    __VLS_asFunctionalElement(__VLS_intrinsicElements.p, __VLS_intrinsicElements.p)({
        ...{ class: "text-sm opacity-70 mt-1" },
    });
    (__VLS_ctx.form.description);
    if (__VLS_ctx.directMappedFields.length > 0) {
        const __VLS_4 = {}.VCard;
        /** @type {[typeof __VLS_components.VCard, typeof __VLS_components.VCard, ]} */ ;
        // @ts-ignore
        const __VLS_5 = __VLS_asFunctionalComponent(__VLS_4, new __VLS_4({}));
        const __VLS_6 = __VLS_5({}, ...__VLS_functionalComponentArgsRest(__VLS_5));
        __VLS_7.slots.default;
        __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
            ...{ class: "text-xs uppercase tracking-wide opacity-60 font-semibold mb-2" },
        });
        (__VLS_ctx.t('settingForms.currentValuesTitle'));
        __VLS_asFunctionalElement(__VLS_intrinsicElements.ul, __VLS_intrinsicElements.ul)({
            ...{ class: "flex flex-col gap-1.5 text-sm" },
        });
        for (const [f] of __VLS_getVForSourceType((__VLS_ctx.directMappedFields))) {
            __VLS_asFunctionalElement(__VLS_intrinsicElements.li, __VLS_intrinsicElements.li)({
                key: (f.name),
                ...{ class: "flex items-center justify-between gap-2 border-b border-base-300 last:border-0 pb-1.5 last:pb-0" },
            });
            __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
                ...{ class: "min-w-0 flex-1" },
            });
            __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
                ...{ class: "font-mono text-xs opacity-70" },
            });
            (f.bindsTo?.key);
            __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
                ...{ class: "opacity-50 mx-1" },
            });
            __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
                ...{ class: "font-mono" },
            });
            (__VLS_ctx.fieldDisplayValue(f));
            __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
                ...{ class: "text-xs opacity-60 whitespace-nowrap" },
            });
            (__VLS_ctx.sourceLabel(f.currentSource));
        }
        var __VLS_7;
    }
    if (__VLS_ctx.computedSettings.length > 0) {
        const __VLS_8 = {}.VCard;
        /** @type {[typeof __VLS_components.VCard, typeof __VLS_components.VCard, ]} */ ;
        // @ts-ignore
        const __VLS_9 = __VLS_asFunctionalComponent(__VLS_8, new __VLS_8({}));
        const __VLS_10 = __VLS_9({}, ...__VLS_functionalComponentArgsRest(__VLS_9));
        __VLS_11.slots.default;
        __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
            ...{ class: "text-xs uppercase tracking-wide opacity-60 font-semibold mb-2" },
        });
        (__VLS_ctx.t('settingForms.computedTitle'));
        __VLS_asFunctionalElement(__VLS_intrinsicElements.ul, __VLS_intrinsicElements.ul)({
            ...{ class: "flex flex-col gap-1 text-sm" },
        });
        for (const [cs] of __VLS_getVForSourceType((__VLS_ctx.computedSettings))) {
            __VLS_asFunctionalElement(__VLS_intrinsicElements.li, __VLS_intrinsicElements.li)({
                key: (cs.key),
                ...{ class: "font-mono text-xs" },
            });
            (cs.key);
            if (cs.settingType) {
                __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
                    ...{ class: "opacity-50" },
                });
                (cs.settingType);
            }
            if (cs.conditional) {
                __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
                    ...{ class: "opacity-60" },
                });
                (__VLS_ctx.t('settingForms.conditional'));
            }
        }
        var __VLS_11;
    }
    if (__VLS_ctx.submitError) {
        const __VLS_12 = {}.VAlert;
        /** @type {[typeof __VLS_components.VAlert, typeof __VLS_components.VAlert, ]} */ ;
        // @ts-ignore
        const __VLS_13 = __VLS_asFunctionalComponent(__VLS_12, new __VLS_12({
            variant: "error",
        }));
        const __VLS_14 = __VLS_13({
            variant: "error",
        }, ...__VLS_functionalComponentArgsRest(__VLS_13));
        __VLS_15.slots.default;
        (__VLS_ctx.submitError);
        var __VLS_15;
    }
    /** @type {[typeof FormFields, ]} */ ;
    // @ts-ignore
    const __VLS_16 = __VLS_asFunctionalComponent(FormFields, new FormFields({
        modelValue: (__VLS_ctx.values),
        fields: (__VLS_ctx.form.fields ?? []),
        errors: (__VLS_ctx.errors),
        disabled: (__VLS_ctx.submitting),
    }));
    const __VLS_17 = __VLS_16({
        modelValue: (__VLS_ctx.values),
        fields: (__VLS_ctx.form.fields ?? []),
        errors: (__VLS_ctx.errors),
        disabled: (__VLS_ctx.submitting),
    }, ...__VLS_functionalComponentArgsRest(__VLS_16));
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "flex gap-2 justify-end mt-2 flex-wrap" },
    });
    if (__VLS_ctx.form.clearable) {
        const __VLS_19 = {}.VButton;
        /** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
        // @ts-ignore
        const __VLS_20 = __VLS_asFunctionalComponent(__VLS_19, new __VLS_19({
            ...{ 'onClick': {} },
            variant: "ghost",
            size: "sm",
            disabled: (__VLS_ctx.submitting),
        }));
        const __VLS_21 = __VLS_20({
            ...{ 'onClick': {} },
            variant: "ghost",
            size: "sm",
            disabled: (__VLS_ctx.submitting),
        }, ...__VLS_functionalComponentArgsRest(__VLS_20));
        let __VLS_23;
        let __VLS_24;
        let __VLS_25;
        const __VLS_26 = {
            onClick: (__VLS_ctx.onReset)
        };
        __VLS_22.slots.default;
        (__VLS_ctx.t('settingForms.reset'));
        var __VLS_22;
    }
    const __VLS_27 = {}.VButton;
    /** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
    // @ts-ignore
    const __VLS_28 = __VLS_asFunctionalComponent(__VLS_27, new __VLS_27({
        ...{ 'onClick': {} },
        variant: "ghost",
        size: "sm",
        loading: (__VLS_ctx.submitting),
    }));
    const __VLS_29 = __VLS_28({
        ...{ 'onClick': {} },
        variant: "ghost",
        size: "sm",
        loading: (__VLS_ctx.submitting),
    }, ...__VLS_functionalComponentArgsRest(__VLS_28));
    let __VLS_31;
    let __VLS_32;
    let __VLS_33;
    const __VLS_34 = {
        onClick: (__VLS_ctx.onValidate)
    };
    __VLS_30.slots.default;
    (__VLS_ctx.t('settingForms.preview'));
    var __VLS_30;
    const __VLS_35 = {}.VButton;
    /** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
    // @ts-ignore
    const __VLS_36 = __VLS_asFunctionalComponent(__VLS_35, new __VLS_35({
        ...{ 'onClick': {} },
        variant: "primary",
        size: "sm",
        loading: (__VLS_ctx.submitting),
    }));
    const __VLS_37 = __VLS_36({
        ...{ 'onClick': {} },
        variant: "primary",
        size: "sm",
        loading: (__VLS_ctx.submitting),
    }, ...__VLS_functionalComponentArgsRest(__VLS_36));
    let __VLS_39;
    let __VLS_40;
    let __VLS_41;
    const __VLS_42 = {
        onClick: (__VLS_ctx.onApply)
    };
    __VLS_38.slots.default;
    (__VLS_ctx.t('settingForms.apply'));
    var __VLS_38;
    if (__VLS_ctx.preview) {
        const __VLS_43 = {}.VCard;
        /** @type {[typeof __VLS_components.VCard, typeof __VLS_components.VCard, ]} */ ;
        // @ts-ignore
        const __VLS_44 = __VLS_asFunctionalComponent(__VLS_43, new __VLS_43({}));
        const __VLS_45 = __VLS_44({}, ...__VLS_functionalComponentArgsRest(__VLS_44));
        __VLS_46.slots.default;
        __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
            ...{ class: "text-xs uppercase tracking-wide opacity-60 font-semibold mb-2" },
        });
        (__VLS_ctx.preview.kind === 'apply'
            ? __VLS_ctx.t('settingForms.appliedTitle')
            : __VLS_ctx.preview.kind === 'reset'
                ? __VLS_ctx.t('settingForms.resetTitle')
                : __VLS_ctx.t('settingForms.previewTitle'));
        if (__VLS_ctx.preview.entries.length > 0) {
            __VLS_asFunctionalElement(__VLS_intrinsicElements.ul, __VLS_intrinsicElements.ul)({
                ...{ class: "flex flex-col gap-1 text-sm" },
            });
            for (const [a, idx] of __VLS_getVForSourceType((__VLS_ctx.preview.entries))) {
                __VLS_asFunctionalElement(__VLS_intrinsicElements.li, __VLS_intrinsicElements.li)({
                    key: (idx),
                    ...{ class: "flex items-center gap-2" },
                });
                __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
                    ...{ class: "text-[10px] uppercase tracking-wide font-semibold px-1.5 py-0.5 rounded" },
                    ...{ class: ({
                            'bg-success/20 text-success': a.action === 'write',
                            'bg-warning/20 text-warning': a.action === 'delete',
                            'bg-base-300 text-base-content/60': a.action === 'skip',
                        }) },
                });
                (__VLS_ctx.actionLabel(a.action));
                __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
                    ...{ class: "font-mono text-xs" },
                });
                (a.key);
                __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
                    ...{ class: "text-xs opacity-50" },
                });
                (a.scope);
                if (a.valueMasked) {
                    __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
                        ...{ class: "text-xs opacity-50" },
                    });
                }
            }
        }
        else {
            __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
                ...{ class: "text-sm opacity-70" },
            });
            (__VLS_ctx.t('settingForms.noChanges'));
        }
        var __VLS_46;
    }
}
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-col']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-4']} */ ;
/** @type {__VLS_StyleScopedClasses['min-h-0']} */ ;
/** @type {__VLS_StyleScopedClasses['text-sm']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-70']} */ ;
/** @type {__VLS_StyleScopedClasses['text-lg']} */ ;
/** @type {__VLS_StyleScopedClasses['font-semibold']} */ ;
/** @type {__VLS_StyleScopedClasses['text-sm']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-70']} */ ;
/** @type {__VLS_StyleScopedClasses['mt-1']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['uppercase']} */ ;
/** @type {__VLS_StyleScopedClasses['tracking-wide']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-60']} */ ;
/** @type {__VLS_StyleScopedClasses['font-semibold']} */ ;
/** @type {__VLS_StyleScopedClasses['mb-2']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-col']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-1.5']} */ ;
/** @type {__VLS_StyleScopedClasses['text-sm']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['items-center']} */ ;
/** @type {__VLS_StyleScopedClasses['justify-between']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-2']} */ ;
/** @type {__VLS_StyleScopedClasses['border-b']} */ ;
/** @type {__VLS_StyleScopedClasses['border-base-300']} */ ;
/** @type {__VLS_StyleScopedClasses['last:border-0']} */ ;
/** @type {__VLS_StyleScopedClasses['pb-1.5']} */ ;
/** @type {__VLS_StyleScopedClasses['last:pb-0']} */ ;
/** @type {__VLS_StyleScopedClasses['min-w-0']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-1']} */ ;
/** @type {__VLS_StyleScopedClasses['font-mono']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-70']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-50']} */ ;
/** @type {__VLS_StyleScopedClasses['mx-1']} */ ;
/** @type {__VLS_StyleScopedClasses['font-mono']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-60']} */ ;
/** @type {__VLS_StyleScopedClasses['whitespace-nowrap']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['uppercase']} */ ;
/** @type {__VLS_StyleScopedClasses['tracking-wide']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-60']} */ ;
/** @type {__VLS_StyleScopedClasses['font-semibold']} */ ;
/** @type {__VLS_StyleScopedClasses['mb-2']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-col']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-1']} */ ;
/** @type {__VLS_StyleScopedClasses['text-sm']} */ ;
/** @type {__VLS_StyleScopedClasses['font-mono']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-50']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-60']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-2']} */ ;
/** @type {__VLS_StyleScopedClasses['justify-end']} */ ;
/** @type {__VLS_StyleScopedClasses['mt-2']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-wrap']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['uppercase']} */ ;
/** @type {__VLS_StyleScopedClasses['tracking-wide']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-60']} */ ;
/** @type {__VLS_StyleScopedClasses['font-semibold']} */ ;
/** @type {__VLS_StyleScopedClasses['mb-2']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-col']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-1']} */ ;
/** @type {__VLS_StyleScopedClasses['text-sm']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['items-center']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-2']} */ ;
/** @type {__VLS_StyleScopedClasses['text-[10px]']} */ ;
/** @type {__VLS_StyleScopedClasses['uppercase']} */ ;
/** @type {__VLS_StyleScopedClasses['tracking-wide']} */ ;
/** @type {__VLS_StyleScopedClasses['font-semibold']} */ ;
/** @type {__VLS_StyleScopedClasses['px-1.5']} */ ;
/** @type {__VLS_StyleScopedClasses['py-0.5']} */ ;
/** @type {__VLS_StyleScopedClasses['rounded']} */ ;
/** @type {__VLS_StyleScopedClasses['bg-success/20']} */ ;
/** @type {__VLS_StyleScopedClasses['text-success']} */ ;
/** @type {__VLS_StyleScopedClasses['bg-warning/20']} */ ;
/** @type {__VLS_StyleScopedClasses['text-warning']} */ ;
/** @type {__VLS_StyleScopedClasses['bg-base-300']} */ ;
/** @type {__VLS_StyleScopedClasses['text-base-content/60']} */ ;
/** @type {__VLS_StyleScopedClasses['font-mono']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-50']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-50']} */ ;
/** @type {__VLS_StyleScopedClasses['text-sm']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-70']} */ ;
var __VLS_dollars;
const __VLS_self = (await import('vue')).defineComponent({
    setup() {
        return {
            VAlert: VAlert,
            VButton: VButton,
            VCard: VCard,
            FormFields: FormFields,
            t: t,
            form: form,
            loading: loading,
            loadError: loadError,
            values: values,
            errors: errors,
            submitting: submitting,
            submitError: submitError,
            preview: preview,
            directMappedFields: directMappedFields,
            computedSettings: computedSettings,
            onApply: onApply,
            onValidate: onValidate,
            onReset: onReset,
            fieldDisplayValue: fieldDisplayValue,
            sourceLabel: sourceLabel,
            actionLabel: actionLabel,
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
//# sourceMappingURL=SettingFormView.vue.js.map