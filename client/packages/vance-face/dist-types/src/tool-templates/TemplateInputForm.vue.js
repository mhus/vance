import { computed } from 'vue';
import { VCheckbox, VInput, VSelect } from '@/components';
const props = defineProps();
const emit = defineEmits();
function setField(name, value) {
    emit('update:modelValue', { ...props.modelValue, [name]: value });
}
function setBool(name, value) {
    setField(name, value ? 'true' : 'false');
}
function boolValue(name) {
    const v = props.modelValue[name];
    return v === 'true' || v === '1' || v === 'yes';
}
const selectOptionsByName = computed(() => {
    const out = {};
    for (const i of props.inputs) {
        if (i.type === 'select') {
            out[i.name] = (i.choices ?? []).map((c) => ({ value: c, label: c }));
        }
    }
    return out;
});
function helpFor(input) {
    if (input.help)
        return input.help;
    if (input.target === 'setting') {
        return 'Wird verschlüsselt in den Settings gespeichert.';
    }
    return undefined;
}
function labelFor(input) {
    return input.required ? `${input.label} *` : input.label;
}
debugger; /* PartiallyEnd: #3632/scriptSetup.vue */
const __VLS_ctx = {};
let __VLS_components;
let __VLS_directives;
__VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
    ...{ class: "flex flex-col gap-3" },
});
for (const [input] of __VLS_getVForSourceType((__VLS_ctx.inputs))) {
    (input.name);
    if (input.type === 'string') {
        const __VLS_0 = {}.VInput;
        /** @type {[typeof __VLS_components.VInput, ]} */ ;
        // @ts-ignore
        const __VLS_1 = __VLS_asFunctionalComponent(__VLS_0, new __VLS_0({
            ...{ 'onUpdate:modelValue': {} },
            modelValue: (__VLS_ctx.modelValue[input.name] ?? ''),
            label: (__VLS_ctx.labelFor(input)),
            placeholder: (input.defaultValue ?? ''),
            help: (__VLS_ctx.helpFor(input)),
            required: (input.required),
        }));
        const __VLS_2 = __VLS_1({
            ...{ 'onUpdate:modelValue': {} },
            modelValue: (__VLS_ctx.modelValue[input.name] ?? ''),
            label: (__VLS_ctx.labelFor(input)),
            placeholder: (input.defaultValue ?? ''),
            help: (__VLS_ctx.helpFor(input)),
            required: (input.required),
        }, ...__VLS_functionalComponentArgsRest(__VLS_1));
        let __VLS_4;
        let __VLS_5;
        let __VLS_6;
        const __VLS_7 = {
            'onUpdate:modelValue': ((v) => __VLS_ctx.setField(input.name, v))
        };
        var __VLS_3;
    }
    else if (input.type === 'password') {
        const __VLS_8 = {}.VInput;
        /** @type {[typeof __VLS_components.VInput, ]} */ ;
        // @ts-ignore
        const __VLS_9 = __VLS_asFunctionalComponent(__VLS_8, new __VLS_8({
            ...{ 'onUpdate:modelValue': {} },
            modelValue: (__VLS_ctx.modelValue[input.name] ?? ''),
            type: "password",
            label: (__VLS_ctx.labelFor(input)),
            help: (__VLS_ctx.helpFor(input)),
            required: (input.required),
            autocomplete: "new-password",
        }));
        const __VLS_10 = __VLS_9({
            ...{ 'onUpdate:modelValue': {} },
            modelValue: (__VLS_ctx.modelValue[input.name] ?? ''),
            type: "password",
            label: (__VLS_ctx.labelFor(input)),
            help: (__VLS_ctx.helpFor(input)),
            required: (input.required),
            autocomplete: "new-password",
        }, ...__VLS_functionalComponentArgsRest(__VLS_9));
        let __VLS_12;
        let __VLS_13;
        let __VLS_14;
        const __VLS_15 = {
            'onUpdate:modelValue': ((v) => __VLS_ctx.setField(input.name, v))
        };
        var __VLS_11;
    }
    else if (input.type === 'integer') {
        const __VLS_16 = {}.VInput;
        /** @type {[typeof __VLS_components.VInput, ]} */ ;
        // @ts-ignore
        const __VLS_17 = __VLS_asFunctionalComponent(__VLS_16, new __VLS_16({
            ...{ 'onUpdate:modelValue': {} },
            modelValue: (__VLS_ctx.modelValue[input.name] ?? ''),
            type: "number",
            label: (__VLS_ctx.labelFor(input)),
            placeholder: (input.defaultValue ?? ''),
            help: (__VLS_ctx.helpFor(input)),
            required: (input.required),
        }));
        const __VLS_18 = __VLS_17({
            ...{ 'onUpdate:modelValue': {} },
            modelValue: (__VLS_ctx.modelValue[input.name] ?? ''),
            type: "number",
            label: (__VLS_ctx.labelFor(input)),
            placeholder: (input.defaultValue ?? ''),
            help: (__VLS_ctx.helpFor(input)),
            required: (input.required),
        }, ...__VLS_functionalComponentArgsRest(__VLS_17));
        let __VLS_20;
        let __VLS_21;
        let __VLS_22;
        const __VLS_23 = {
            'onUpdate:modelValue': ((v) => __VLS_ctx.setField(input.name, v))
        };
        var __VLS_19;
    }
    else if (input.type === 'boolean') {
        const __VLS_24 = {}.VCheckbox;
        /** @type {[typeof __VLS_components.VCheckbox, ]} */ ;
        // @ts-ignore
        const __VLS_25 = __VLS_asFunctionalComponent(__VLS_24, new __VLS_24({
            ...{ 'onUpdate:modelValue': {} },
            modelValue: (__VLS_ctx.boolValue(input.name)),
            label: (__VLS_ctx.labelFor(input)),
            help: (__VLS_ctx.helpFor(input)),
        }));
        const __VLS_26 = __VLS_25({
            ...{ 'onUpdate:modelValue': {} },
            modelValue: (__VLS_ctx.boolValue(input.name)),
            label: (__VLS_ctx.labelFor(input)),
            help: (__VLS_ctx.helpFor(input)),
        }, ...__VLS_functionalComponentArgsRest(__VLS_25));
        let __VLS_28;
        let __VLS_29;
        let __VLS_30;
        const __VLS_31 = {
            'onUpdate:modelValue': ((v) => __VLS_ctx.setBool(input.name, v))
        };
        var __VLS_27;
    }
    else if (input.type === 'select') {
        const __VLS_32 = {}.VSelect;
        /** @type {[typeof __VLS_components.VSelect, ]} */ ;
        // @ts-ignore
        const __VLS_33 = __VLS_asFunctionalComponent(__VLS_32, new __VLS_32({
            ...{ 'onUpdate:modelValue': {} },
            modelValue: (__VLS_ctx.modelValue[input.name] ?? null),
            label: (__VLS_ctx.labelFor(input)),
            options: (__VLS_ctx.selectOptionsByName[input.name] ?? []),
            help: (__VLS_ctx.helpFor(input)),
            placeholder: (input.required ? undefined : '—'),
        }));
        const __VLS_34 = __VLS_33({
            ...{ 'onUpdate:modelValue': {} },
            modelValue: (__VLS_ctx.modelValue[input.name] ?? null),
            label: (__VLS_ctx.labelFor(input)),
            options: (__VLS_ctx.selectOptionsByName[input.name] ?? []),
            help: (__VLS_ctx.helpFor(input)),
            placeholder: (input.required ? undefined : '—'),
        }, ...__VLS_functionalComponentArgsRest(__VLS_33));
        let __VLS_36;
        let __VLS_37;
        let __VLS_38;
        const __VLS_39 = {
            'onUpdate:modelValue': ((v) => __VLS_ctx.setField(input.name, v ?? ''))
        };
        var __VLS_35;
    }
    else {
        __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
            ...{ class: "text-xs text-error" },
        });
        __VLS_asFunctionalElement(__VLS_intrinsicElements.code, __VLS_intrinsicElements.code)({
            ...{ class: "font-mono" },
        });
        (input.type);
    }
}
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-col']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-3']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['text-error']} */ ;
/** @type {__VLS_StyleScopedClasses['font-mono']} */ ;
var __VLS_dollars;
const __VLS_self = (await import('vue')).defineComponent({
    setup() {
        return {
            VCheckbox: VCheckbox,
            VInput: VInput,
            VSelect: VSelect,
            setField: setField,
            setBool: setBool,
            boolValue: boolValue,
            selectOptionsByName: selectOptionsByName,
            helpFor: helpFor,
            labelFor: labelFor,
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
//# sourceMappingURL=TemplateInputForm.vue.js.map