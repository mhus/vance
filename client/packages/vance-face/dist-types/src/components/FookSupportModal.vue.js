import { ref, watch } from 'vue';
import { useI18n } from 'vue-i18n';
import { brainFetch } from '@vance/shared';
import { VAlert, VButton, VModal, VTextarea } from '@vance/components';
const props = defineProps();
const emit = defineEmits();
const { t } = useI18n();
const text = ref('');
const loading = ref(false);
const error = ref(null);
const submissionId = ref(null);
// Reset state every time the dialog opens. Avoids "previous error
// still visible when I open it again" and clears stale text from a
// successful prior submission.
watch(() => props.modelValue, (open) => {
    if (open) {
        text.value = '';
        error.value = null;
        submissionId.value = null;
        loading.value = false;
    }
});
function close() {
    if (loading.value)
        return;
    emit('update:modelValue', false);
}
function urlParam(name) {
    const v = new URLSearchParams(window.location.search).get(name);
    return v && v.length > 0 ? v : undefined;
}
async function onSubmit() {
    const trimmed = text.value.trim();
    if (trimmed.length === 0) {
        error.value = t('fook.errorEmpty');
        return;
    }
    loading.value = true;
    error.value = null;
    try {
        const body = {
            text: trimmed,
            projectId: urlParam('project'),
            sessionId: urlParam('sessionId'),
        };
        const res = await brainFetch('POST', 'fook/submit', { body });
        submissionId.value = res.submissionId;
    }
    catch (e) {
        error.value = e.message || t('fook.errorGeneric');
    }
    finally {
        loading.value = false;
    }
}
debugger; /* PartiallyEnd: #3632/scriptSetup.vue */
const __VLS_ctx = {};
let __VLS_components;
let __VLS_directives;
const __VLS_0 = {}.VModal;
/** @type {[typeof __VLS_components.VModal, typeof __VLS_components.VModal, ]} */ ;
// @ts-ignore
const __VLS_1 = __VLS_asFunctionalComponent(__VLS_0, new __VLS_0({
    ...{ 'onUpdate:modelValue': {} },
    modelValue: (__VLS_ctx.modelValue),
    title: (__VLS_ctx.t('fook.title')),
    closeOnBackdrop: (!__VLS_ctx.loading),
}));
const __VLS_2 = __VLS_1({
    ...{ 'onUpdate:modelValue': {} },
    modelValue: (__VLS_ctx.modelValue),
    title: (__VLS_ctx.t('fook.title')),
    closeOnBackdrop: (!__VLS_ctx.loading),
}, ...__VLS_functionalComponentArgsRest(__VLS_1));
let __VLS_4;
let __VLS_5;
let __VLS_6;
const __VLS_7 = {
    'onUpdate:modelValue': ((v) => __VLS_ctx.emit('update:modelValue', v))
};
var __VLS_8 = {};
__VLS_3.slots.default;
__VLS_asFunctionalElement(__VLS_intrinsicElements.p, __VLS_intrinsicElements.p)({
    ...{ class: "mb-3 text-sm opacity-80" },
});
(__VLS_ctx.t('fook.intro'));
if (!__VLS_ctx.submissionId) {
    const __VLS_9 = {}.VTextarea;
    /** @type {[typeof __VLS_components.VTextarea, ]} */ ;
    // @ts-ignore
    const __VLS_10 = __VLS_asFunctionalComponent(__VLS_9, new __VLS_9({
        modelValue: (__VLS_ctx.text),
        placeholder: (__VLS_ctx.t('fook.placeholder')),
        rows: (8),
        disabled: (__VLS_ctx.loading),
    }));
    const __VLS_11 = __VLS_10({
        modelValue: (__VLS_ctx.text),
        placeholder: (__VLS_ctx.t('fook.placeholder')),
        rows: (8),
        disabled: (__VLS_ctx.loading),
    }, ...__VLS_functionalComponentArgsRest(__VLS_10));
}
if (__VLS_ctx.submissionId) {
    const __VLS_13 = {}.VAlert;
    /** @type {[typeof __VLS_components.VAlert, typeof __VLS_components.VAlert, ]} */ ;
    // @ts-ignore
    const __VLS_14 = __VLS_asFunctionalComponent(__VLS_13, new __VLS_13({
        variant: "success",
        ...{ class: "mt-2" },
    }));
    const __VLS_15 = __VLS_14({
        variant: "success",
        ...{ class: "mt-2" },
    }, ...__VLS_functionalComponentArgsRest(__VLS_14));
    __VLS_16.slots.default;
    (__VLS_ctx.t('fook.submitted'));
    __VLS_asFunctionalElement(__VLS_intrinsicElements.code, __VLS_intrinsicElements.code)({
        ...{ class: "ml-1 font-mono text-xs" },
    });
    (__VLS_ctx.submissionId);
    var __VLS_16;
}
if (__VLS_ctx.error) {
    const __VLS_17 = {}.VAlert;
    /** @type {[typeof __VLS_components.VAlert, typeof __VLS_components.VAlert, ]} */ ;
    // @ts-ignore
    const __VLS_18 = __VLS_asFunctionalComponent(__VLS_17, new __VLS_17({
        variant: "error",
        ...{ class: "mt-2" },
    }));
    const __VLS_19 = __VLS_18({
        variant: "error",
        ...{ class: "mt-2" },
    }, ...__VLS_functionalComponentArgsRest(__VLS_18));
    __VLS_20.slots.default;
    (__VLS_ctx.error);
    var __VLS_20;
}
{
    const { actions: __VLS_thisSlot } = __VLS_3.slots;
    const __VLS_21 = {}.VButton;
    /** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
    // @ts-ignore
    const __VLS_22 = __VLS_asFunctionalComponent(__VLS_21, new __VLS_21({
        ...{ 'onClick': {} },
        variant: "ghost",
        disabled: (__VLS_ctx.loading),
    }));
    const __VLS_23 = __VLS_22({
        ...{ 'onClick': {} },
        variant: "ghost",
        disabled: (__VLS_ctx.loading),
    }, ...__VLS_functionalComponentArgsRest(__VLS_22));
    let __VLS_25;
    let __VLS_26;
    let __VLS_27;
    const __VLS_28 = {
        onClick: (__VLS_ctx.close)
    };
    __VLS_24.slots.default;
    (__VLS_ctx.submissionId ? __VLS_ctx.t('common.close') : __VLS_ctx.t('common.cancel'));
    var __VLS_24;
    if (!__VLS_ctx.submissionId) {
        const __VLS_29 = {}.VButton;
        /** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
        // @ts-ignore
        const __VLS_30 = __VLS_asFunctionalComponent(__VLS_29, new __VLS_29({
            ...{ 'onClick': {} },
            variant: "primary",
            loading: (__VLS_ctx.loading),
            disabled: (__VLS_ctx.loading || __VLS_ctx.text.trim().length === 0),
        }));
        const __VLS_31 = __VLS_30({
            ...{ 'onClick': {} },
            variant: "primary",
            loading: (__VLS_ctx.loading),
            disabled: (__VLS_ctx.loading || __VLS_ctx.text.trim().length === 0),
        }, ...__VLS_functionalComponentArgsRest(__VLS_30));
        let __VLS_33;
        let __VLS_34;
        let __VLS_35;
        const __VLS_36 = {
            onClick: (__VLS_ctx.onSubmit)
        };
        __VLS_32.slots.default;
        (__VLS_ctx.t('fook.submit'));
        var __VLS_32;
    }
}
var __VLS_3;
/** @type {__VLS_StyleScopedClasses['mb-3']} */ ;
/** @type {__VLS_StyleScopedClasses['text-sm']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-80']} */ ;
/** @type {__VLS_StyleScopedClasses['mt-2']} */ ;
/** @type {__VLS_StyleScopedClasses['ml-1']} */ ;
/** @type {__VLS_StyleScopedClasses['font-mono']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['mt-2']} */ ;
var __VLS_dollars;
const __VLS_self = (await import('vue')).defineComponent({
    setup() {
        return {
            VAlert: VAlert,
            VButton: VButton,
            VModal: VModal,
            VTextarea: VTextarea,
            emit: emit,
            t: t,
            text: text,
            loading: loading,
            error: error,
            submissionId: submissionId,
            close: close,
            onSubmit: onSubmit,
        };
    },
    __typeEmits: {},
    __typeProps: {},
});
export default (await import('vue')).defineComponent({
    setup() {
        return {};
    },
    __typeEmits: {},
    __typeProps: {},
});
; /* PartiallyEnd: #4569/main.vue */
//# sourceMappingURL=FookSupportModal.vue.js.map