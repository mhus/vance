import { computed } from 'vue';
import { useI18n } from 'vue-i18n';
import VButton from './VButton.vue';
import VCheckbox from './VCheckbox.vue';
import VInput from './VInput.vue';
import VSelect from './VSelect.vue';
import VTextarea from './VTextarea.vue';
const props = withDefaults(defineProps(), {
    errors: () => ({}),
    preferredLang: undefined,
    pathPrefix: '',
    disabled: false,
});
const emit = defineEmits();
const { locale } = useI18n();
const activeLang = computed(() => props.preferredLang ?? locale.value);
function resolveLocalized(map) {
    if (!map)
        return '';
    const preferred = activeLang.value;
    if (map[preferred] && map[preferred].trim())
        return map[preferred];
    if (map.en && map.en.trim())
        return map.en;
    for (const v of Object.values(map)) {
        if (v && v.trim())
            return v;
    }
    return '';
}
function labelOf(field) {
    const text = resolveLocalized(field.label);
    return field.required ? `${text} *` : text;
}
function helpOf(field) {
    const text = resolveLocalized(field.help);
    return text || undefined;
}
function pathOf(name, index) {
    const base = props.pathPrefix ? `${props.pathPrefix}.${name}` : name;
    return index !== undefined ? `${props.pathPrefix}[${index}].${name}` : base;
}
function errorOf(field) {
    return props.errors[pathOf(field.name)];
}
function setField(name, value) {
    emit('update:modelValue', { ...props.modelValue, [name]: value });
}
function stringValue(name) {
    const v = props.modelValue[name];
    return typeof v === 'string' ? v : '';
}
function boolValue(name) {
    const v = props.modelValue[name];
    return v === 'true' || v === '1' || v === 'yes';
}
function setBool(name, v) {
    setField(name, v ? 'true' : 'false');
}
function multiSelectedSet(name) {
    const v = props.modelValue[name];
    if (Array.isArray(v)) {
        return new Set(v.filter((x) => typeof x === 'string'));
    }
    return new Set();
}
function multiSelectIsChecked(name, value) {
    return multiSelectedSet(name).has(value);
}
function toggleMultiSelect(field, value, checked) {
    const set = multiSelectedSet(field.name);
    if (checked)
        set.add(value);
    else
        set.delete(value);
    // Preserve declaration order.
    const ordered = [];
    for (const c of field.choices ?? []) {
        if (set.has(c.value))
            ordered.push(c.value);
    }
    setField(field.name, ordered);
}
function selectOptionsOf(field) {
    return (field.choices ?? []).map((c) => ({
        value: c.value,
        label: resolveLocalized(c.label) || c.value,
    }));
}
// ─────────────────── Repeat helpers ───────────────────
function repeatItems(name) {
    const v = props.modelValue[name];
    if (Array.isArray(v)) {
        return v.filter((x) => typeof x === 'object' && x !== null && !Array.isArray(x));
    }
    return [];
}
function addRepeatItem(field) {
    const current = repeatItems(field.name);
    if (field.max !== undefined && current.length >= field.max)
        return;
    const blank = {};
    for (const item of field.item ?? []) {
        blank[item.name] = item.type === 'multi_select' ? [] : '';
    }
    setField(field.name, [...current, blank]);
}
function removeRepeatItem(field, index) {
    const current = repeatItems(field.name);
    if (field.min !== undefined && current.length <= field.min) {
        // Block removal below min; the user gets visual feedback via the
        // disabled remove button.
        return;
    }
    setField(field.name, current.filter((_, i) => i !== index));
}
function updateRepeatItem(field, index, sub) {
    const current = repeatItems(field.name);
    const next = [...current];
    next[index] = sub;
    setField(field.name, next);
}
function canAdd(field) {
    if (field.max === undefined)
        return true;
    return repeatItems(field.name).length < field.max;
}
function canRemove(field) {
    if (field.min === undefined)
        return true;
    return repeatItems(field.name).length > field.min;
}
debugger; /* PartiallyEnd: #3632/scriptSetup.vue */
const __VLS_withDefaultsArg = (function (t) { return t; })({
    errors: () => ({}),
    preferredLang: undefined,
    pathPrefix: '',
    disabled: false,
});
const __VLS_ctx = {};
let __VLS_components;
let __VLS_directives;
__VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
    ...{ class: "flex flex-col gap-3" },
});
for (const [field] of __VLS_getVForSourceType((__VLS_ctx.fields))) {
    (field.name);
    if (field.type === 'string') {
        /** @type {[typeof VInput, ]} */ ;
        // @ts-ignore
        const __VLS_0 = __VLS_asFunctionalComponent(VInput, new VInput({
            ...{ 'onUpdate:modelValue': {} },
            modelValue: (__VLS_ctx.stringValue(field.name)),
            label: (__VLS_ctx.labelOf(field)),
            placeholder: (field.defaultValue ?? ''),
            help: (__VLS_ctx.helpOf(field)),
            error: (__VLS_ctx.errorOf(field)),
            required: (field.required),
            disabled: (__VLS_ctx.disabled),
        }));
        const __VLS_1 = __VLS_0({
            ...{ 'onUpdate:modelValue': {} },
            modelValue: (__VLS_ctx.stringValue(field.name)),
            label: (__VLS_ctx.labelOf(field)),
            placeholder: (field.defaultValue ?? ''),
            help: (__VLS_ctx.helpOf(field)),
            error: (__VLS_ctx.errorOf(field)),
            required: (field.required),
            disabled: (__VLS_ctx.disabled),
        }, ...__VLS_functionalComponentArgsRest(__VLS_0));
        let __VLS_3;
        let __VLS_4;
        let __VLS_5;
        const __VLS_6 = {
            'onUpdate:modelValue': ((v) => __VLS_ctx.setField(field.name, v))
        };
        var __VLS_2;
    }
    else if (field.type === 'password') {
        /** @type {[typeof VInput, ]} */ ;
        // @ts-ignore
        const __VLS_7 = __VLS_asFunctionalComponent(VInput, new VInput({
            ...{ 'onUpdate:modelValue': {} },
            modelValue: (__VLS_ctx.stringValue(field.name)),
            type: "password",
            label: (__VLS_ctx.labelOf(field)),
            help: (__VLS_ctx.helpOf(field)),
            error: (__VLS_ctx.errorOf(field)),
            required: (field.required),
            disabled: (__VLS_ctx.disabled),
            autocomplete: "new-password",
        }));
        const __VLS_8 = __VLS_7({
            ...{ 'onUpdate:modelValue': {} },
            modelValue: (__VLS_ctx.stringValue(field.name)),
            type: "password",
            label: (__VLS_ctx.labelOf(field)),
            help: (__VLS_ctx.helpOf(field)),
            error: (__VLS_ctx.errorOf(field)),
            required: (field.required),
            disabled: (__VLS_ctx.disabled),
            autocomplete: "new-password",
        }, ...__VLS_functionalComponentArgsRest(__VLS_7));
        let __VLS_10;
        let __VLS_11;
        let __VLS_12;
        const __VLS_13 = {
            'onUpdate:modelValue': ((v) => __VLS_ctx.setField(field.name, v))
        };
        var __VLS_9;
    }
    else if (field.type === 'integer') {
        /** @type {[typeof VInput, ]} */ ;
        // @ts-ignore
        const __VLS_14 = __VLS_asFunctionalComponent(VInput, new VInput({
            ...{ 'onUpdate:modelValue': {} },
            modelValue: (__VLS_ctx.stringValue(field.name)),
            type: "number",
            label: (__VLS_ctx.labelOf(field)),
            placeholder: (field.defaultValue ?? ''),
            help: (__VLS_ctx.helpOf(field)),
            error: (__VLS_ctx.errorOf(field)),
            required: (field.required),
            disabled: (__VLS_ctx.disabled),
        }));
        const __VLS_15 = __VLS_14({
            ...{ 'onUpdate:modelValue': {} },
            modelValue: (__VLS_ctx.stringValue(field.name)),
            type: "number",
            label: (__VLS_ctx.labelOf(field)),
            placeholder: (field.defaultValue ?? ''),
            help: (__VLS_ctx.helpOf(field)),
            error: (__VLS_ctx.errorOf(field)),
            required: (field.required),
            disabled: (__VLS_ctx.disabled),
        }, ...__VLS_functionalComponentArgsRest(__VLS_14));
        let __VLS_17;
        let __VLS_18;
        let __VLS_19;
        const __VLS_20 = {
            'onUpdate:modelValue': ((v) => __VLS_ctx.setField(field.name, v))
        };
        var __VLS_16;
    }
    else if (field.type === 'textarea') {
        /** @type {[typeof VTextarea, ]} */ ;
        // @ts-ignore
        const __VLS_21 = __VLS_asFunctionalComponent(VTextarea, new VTextarea({
            ...{ 'onUpdate:modelValue': {} },
            modelValue: (__VLS_ctx.stringValue(field.name)),
            label: (__VLS_ctx.labelOf(field)),
            placeholder: (field.defaultValue ?? ''),
            help: (__VLS_ctx.helpOf(field)),
            error: (__VLS_ctx.errorOf(field)),
            rows: (field.rows ?? 3),
            required: (field.required),
            disabled: (__VLS_ctx.disabled),
        }));
        const __VLS_22 = __VLS_21({
            ...{ 'onUpdate:modelValue': {} },
            modelValue: (__VLS_ctx.stringValue(field.name)),
            label: (__VLS_ctx.labelOf(field)),
            placeholder: (field.defaultValue ?? ''),
            help: (__VLS_ctx.helpOf(field)),
            error: (__VLS_ctx.errorOf(field)),
            rows: (field.rows ?? 3),
            required: (field.required),
            disabled: (__VLS_ctx.disabled),
        }, ...__VLS_functionalComponentArgsRest(__VLS_21));
        let __VLS_24;
        let __VLS_25;
        let __VLS_26;
        const __VLS_27 = {
            'onUpdate:modelValue': ((v) => __VLS_ctx.setField(field.name, v))
        };
        var __VLS_23;
    }
    else if (field.type === 'boolean') {
        /** @type {[typeof VCheckbox, ]} */ ;
        // @ts-ignore
        const __VLS_28 = __VLS_asFunctionalComponent(VCheckbox, new VCheckbox({
            ...{ 'onUpdate:modelValue': {} },
            modelValue: (__VLS_ctx.boolValue(field.name)),
            label: (__VLS_ctx.labelOf(field)),
            help: (__VLS_ctx.helpOf(field)),
            disabled: (__VLS_ctx.disabled),
        }));
        const __VLS_29 = __VLS_28({
            ...{ 'onUpdate:modelValue': {} },
            modelValue: (__VLS_ctx.boolValue(field.name)),
            label: (__VLS_ctx.labelOf(field)),
            help: (__VLS_ctx.helpOf(field)),
            disabled: (__VLS_ctx.disabled),
        }, ...__VLS_functionalComponentArgsRest(__VLS_28));
        let __VLS_31;
        let __VLS_32;
        let __VLS_33;
        const __VLS_34 = {
            'onUpdate:modelValue': ((v) => __VLS_ctx.setBool(field.name, v))
        };
        var __VLS_30;
    }
    else if (field.type === 'select') {
        /** @type {[typeof VSelect, ]} */ ;
        // @ts-ignore
        const __VLS_35 = __VLS_asFunctionalComponent(VSelect, new VSelect({
            ...{ 'onUpdate:modelValue': {} },
            modelValue: (__VLS_ctx.stringValue(field.name) || null),
            label: (__VLS_ctx.labelOf(field)),
            options: (__VLS_ctx.selectOptionsOf(field)),
            help: (__VLS_ctx.helpOf(field)),
            placeholder: (field.required ? undefined : '—'),
            disabled: (__VLS_ctx.disabled),
        }));
        const __VLS_36 = __VLS_35({
            ...{ 'onUpdate:modelValue': {} },
            modelValue: (__VLS_ctx.stringValue(field.name) || null),
            label: (__VLS_ctx.labelOf(field)),
            options: (__VLS_ctx.selectOptionsOf(field)),
            help: (__VLS_ctx.helpOf(field)),
            placeholder: (field.required ? undefined : '—'),
            disabled: (__VLS_ctx.disabled),
        }, ...__VLS_functionalComponentArgsRest(__VLS_35));
        let __VLS_38;
        let __VLS_39;
        let __VLS_40;
        const __VLS_41 = {
            'onUpdate:modelValue': ((v) => __VLS_ctx.setField(field.name, v ?? ''))
        };
        var __VLS_37;
    }
    else if (field.type === 'multi_select') {
        __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
            ...{ class: "flex flex-col gap-1" },
        });
        __VLS_asFunctionalElement(__VLS_intrinsicElements.label, __VLS_intrinsicElements.label)({
            ...{ class: "text-sm" },
        });
        (__VLS_ctx.labelOf(field));
        __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
            ...{ class: "flex flex-col gap-1 pl-1" },
        });
        for (const [choice] of __VLS_getVForSourceType(((field.choices ?? [])))) {
            /** @type {[typeof VCheckbox, ]} */ ;
            // @ts-ignore
            const __VLS_42 = __VLS_asFunctionalComponent(VCheckbox, new VCheckbox({
                ...{ 'onUpdate:modelValue': {} },
                key: (choice.value),
                modelValue: (__VLS_ctx.multiSelectIsChecked(field.name, choice.value)),
                label: (__VLS_ctx.resolveLocalized(choice.label) || choice.value),
                disabled: (__VLS_ctx.disabled),
            }));
            const __VLS_43 = __VLS_42({
                ...{ 'onUpdate:modelValue': {} },
                key: (choice.value),
                modelValue: (__VLS_ctx.multiSelectIsChecked(field.name, choice.value)),
                label: (__VLS_ctx.resolveLocalized(choice.label) || choice.value),
                disabled: (__VLS_ctx.disabled),
            }, ...__VLS_functionalComponentArgsRest(__VLS_42));
            let __VLS_45;
            let __VLS_46;
            let __VLS_47;
            const __VLS_48 = {
                'onUpdate:modelValue': ((v) => __VLS_ctx.toggleMultiSelect(field, choice.value, v))
            };
            var __VLS_44;
        }
        if (__VLS_ctx.errorOf(field)) {
            __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
                ...{ class: "text-xs text-error" },
            });
            (__VLS_ctx.errorOf(field));
        }
        else if (__VLS_ctx.helpOf(field)) {
            __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
                ...{ class: "text-xs opacity-70" },
            });
            (__VLS_ctx.helpOf(field));
        }
    }
    else if (field.type === 'repeat') {
        __VLS_asFunctionalElement(__VLS_intrinsicElements.fieldset, __VLS_intrinsicElements.fieldset)({
            ...{ class: "border border-base-300 rounded-lg p-3 flex flex-col gap-3" },
        });
        __VLS_asFunctionalElement(__VLS_intrinsicElements.legend, __VLS_intrinsicElements.legend)({
            ...{ class: "px-2 text-sm font-semibold" },
        });
        (__VLS_ctx.labelOf(field));
        if (__VLS_ctx.helpOf(field)) {
            __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
                ...{ class: "text-xs opacity-70 -mt-1" },
            });
            (__VLS_ctx.helpOf(field));
        }
        if (__VLS_ctx.errorOf(field)) {
            __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
                ...{ class: "text-xs text-error -mt-1" },
            });
            (__VLS_ctx.errorOf(field));
        }
        for (const [entry, idx] of __VLS_getVForSourceType((__VLS_ctx.repeatItems(field.name)))) {
            __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
                key: (idx),
                ...{ class: "border border-base-200 rounded p-3 flex flex-col gap-3 bg-base-50" },
            });
            __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
                ...{ class: "flex justify-between items-center -mb-1" },
            });
            __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
                ...{ class: "text-xs uppercase tracking-wide opacity-60" },
            });
            (idx + 1);
            /** @type {[typeof VButton, typeof VButton, ]} */ ;
            // @ts-ignore
            const __VLS_49 = __VLS_asFunctionalComponent(VButton, new VButton({
                ...{ 'onClick': {} },
                variant: "ghost",
                size: "sm",
                disabled: (__VLS_ctx.disabled || !__VLS_ctx.canRemove(field)),
            }));
            const __VLS_50 = __VLS_49({
                ...{ 'onClick': {} },
                variant: "ghost",
                size: "sm",
                disabled: (__VLS_ctx.disabled || !__VLS_ctx.canRemove(field)),
            }, ...__VLS_functionalComponentArgsRest(__VLS_49));
            let __VLS_52;
            let __VLS_53;
            let __VLS_54;
            const __VLS_55 = {
                onClick: (...[$event]) => {
                    if (!!(field.type === 'string'))
                        return;
                    if (!!(field.type === 'password'))
                        return;
                    if (!!(field.type === 'integer'))
                        return;
                    if (!!(field.type === 'textarea'))
                        return;
                    if (!!(field.type === 'boolean'))
                        return;
                    if (!!(field.type === 'select'))
                        return;
                    if (!!(field.type === 'multi_select'))
                        return;
                    if (!(field.type === 'repeat'))
                        return;
                    __VLS_ctx.removeRepeatItem(field, idx);
                }
            };
            __VLS_51.slots.default;
            var __VLS_51;
            const __VLS_56 = {}.FormFields;
            /** @type {[typeof __VLS_components.FormFields, ]} */ ;
            // @ts-ignore
            const __VLS_57 = __VLS_asFunctionalComponent(__VLS_56, new __VLS_56({
                ...{ 'onUpdate:modelValue': {} },
                fields: (field.item ?? []),
                modelValue: entry,
                errors: (__VLS_ctx.errors),
                preferredLang: (__VLS_ctx.preferredLang),
                pathPrefix: (`${field.name}[${idx}]`),
                disabled: (__VLS_ctx.disabled),
            }));
            const __VLS_58 = __VLS_57({
                ...{ 'onUpdate:modelValue': {} },
                fields: (field.item ?? []),
                modelValue: entry,
                errors: (__VLS_ctx.errors),
                preferredLang: (__VLS_ctx.preferredLang),
                pathPrefix: (`${field.name}[${idx}]`),
                disabled: (__VLS_ctx.disabled),
            }, ...__VLS_functionalComponentArgsRest(__VLS_57));
            let __VLS_60;
            let __VLS_61;
            let __VLS_62;
            const __VLS_63 = {
                'onUpdate:modelValue': ((sub) => __VLS_ctx.updateRepeatItem(field, idx, sub))
            };
            var __VLS_59;
        }
        /** @type {[typeof VButton, typeof VButton, ]} */ ;
        // @ts-ignore
        const __VLS_64 = __VLS_asFunctionalComponent(VButton, new VButton({
            ...{ 'onClick': {} },
            variant: "ghost",
            size: "sm",
            disabled: (__VLS_ctx.disabled || !__VLS_ctx.canAdd(field)),
        }));
        const __VLS_65 = __VLS_64({
            ...{ 'onClick': {} },
            variant: "ghost",
            size: "sm",
            disabled: (__VLS_ctx.disabled || !__VLS_ctx.canAdd(field)),
        }, ...__VLS_functionalComponentArgsRest(__VLS_64));
        let __VLS_67;
        let __VLS_68;
        let __VLS_69;
        const __VLS_70 = {
            onClick: (...[$event]) => {
                if (!!(field.type === 'string'))
                    return;
                if (!!(field.type === 'password'))
                    return;
                if (!!(field.type === 'integer'))
                    return;
                if (!!(field.type === 'textarea'))
                    return;
                if (!!(field.type === 'boolean'))
                    return;
                if (!!(field.type === 'select'))
                    return;
                if (!!(field.type === 'multi_select'))
                    return;
                if (!(field.type === 'repeat'))
                    return;
                __VLS_ctx.addRepeatItem(field);
            }
        };
        __VLS_66.slots.default;
        (__VLS_ctx.labelOf(field));
        var __VLS_66;
    }
    else {
        __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
            ...{ class: "text-xs text-error" },
        });
        __VLS_asFunctionalElement(__VLS_intrinsicElements.code, __VLS_intrinsicElements.code)({
            ...{ class: "font-mono" },
        });
        (field.type);
    }
}
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-col']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-3']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-col']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-1']} */ ;
/** @type {__VLS_StyleScopedClasses['text-sm']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-col']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-1']} */ ;
/** @type {__VLS_StyleScopedClasses['pl-1']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['text-error']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-70']} */ ;
/** @type {__VLS_StyleScopedClasses['border']} */ ;
/** @type {__VLS_StyleScopedClasses['border-base-300']} */ ;
/** @type {__VLS_StyleScopedClasses['rounded-lg']} */ ;
/** @type {__VLS_StyleScopedClasses['p-3']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-col']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-3']} */ ;
/** @type {__VLS_StyleScopedClasses['px-2']} */ ;
/** @type {__VLS_StyleScopedClasses['text-sm']} */ ;
/** @type {__VLS_StyleScopedClasses['font-semibold']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-70']} */ ;
/** @type {__VLS_StyleScopedClasses['-mt-1']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['text-error']} */ ;
/** @type {__VLS_StyleScopedClasses['-mt-1']} */ ;
/** @type {__VLS_StyleScopedClasses['border']} */ ;
/** @type {__VLS_StyleScopedClasses['border-base-200']} */ ;
/** @type {__VLS_StyleScopedClasses['rounded']} */ ;
/** @type {__VLS_StyleScopedClasses['p-3']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-col']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-3']} */ ;
/** @type {__VLS_StyleScopedClasses['bg-base-50']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['justify-between']} */ ;
/** @type {__VLS_StyleScopedClasses['items-center']} */ ;
/** @type {__VLS_StyleScopedClasses['-mb-1']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['uppercase']} */ ;
/** @type {__VLS_StyleScopedClasses['tracking-wide']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-60']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['text-error']} */ ;
/** @type {__VLS_StyleScopedClasses['font-mono']} */ ;
var __VLS_dollars;
const __VLS_self = (await import('vue')).defineComponent({
    setup() {
        return {
            VButton: VButton,
            VCheckbox: VCheckbox,
            VInput: VInput,
            VSelect: VSelect,
            VTextarea: VTextarea,
            resolveLocalized: resolveLocalized,
            labelOf: labelOf,
            helpOf: helpOf,
            errorOf: errorOf,
            setField: setField,
            stringValue: stringValue,
            boolValue: boolValue,
            setBool: setBool,
            multiSelectIsChecked: multiSelectIsChecked,
            toggleMultiSelect: toggleMultiSelect,
            selectOptionsOf: selectOptionsOf,
            repeatItems: repeatItems,
            addRepeatItem: addRepeatItem,
            removeRepeatItem: removeRepeatItem,
            updateRepeatItem: updateRepeatItem,
            canAdd: canAdd,
            canRemove: canRemove,
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
//# sourceMappingURL=FormFields.vue.js.map