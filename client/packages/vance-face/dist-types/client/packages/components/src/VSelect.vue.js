export default ((__VLS_props, __VLS_ctx, __VLS_expose, __VLS_setup = (async () => {
    const __VLS_props = withDefaults(defineProps(), { disabled: false });
    const emit = defineEmits();
    function onChange(event) {
        const raw = event.target.value;
        if (raw === '') {
            emit('update:modelValue', null);
            return;
        }
        // Number-coerce when the option type narrows to number; we detect via the
        // first non-null option's typeof.
        emit('update:modelValue', raw);
    }
    function groupedOptions(options) {
        const sections = [];
        for (const opt of options) {
            const group = opt.group ?? null;
            const last = sections[sections.length - 1];
            if (last && last.group === group) {
                last.options.push(opt);
            }
            else {
                sections.push({ group, options: [opt] });
            }
        }
        return sections;
    }
    debugger; /* PartiallyEnd: #3632/scriptSetup.vue */
    const __VLS_withDefaultsArg = (function (t) { return t; })({ disabled: false });
    const __VLS_fnComponent = (await import('vue')).defineComponent({
        __typeEmits: {},
    });
    const __VLS_ctx = {};
    let __VLS_components;
    let __VLS_directives;
    __VLS_asFunctionalElement(__VLS_intrinsicElements.label, __VLS_intrinsicElements.label)({
        ...{ class: "form-control w-full" },
    });
    if (__VLS_ctx.label) {
        __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
            ...{ class: "label" },
        });
        __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
            ...{ class: "label-text" },
        });
        (__VLS_ctx.label);
    }
    __VLS_asFunctionalElement(__VLS_intrinsicElements.select, __VLS_intrinsicElements.select)({
        ...{ onChange: (__VLS_ctx.onChange) },
        value: (__VLS_ctx.modelValue ?? ''),
        disabled: (__VLS_ctx.disabled),
        ...{ class: (['select', 'select-bordered', 'w-full', { 'select-error': !!__VLS_ctx.error }]) },
    });
    if (__VLS_ctx.placeholder) {
        __VLS_asFunctionalElement(__VLS_intrinsicElements.option, __VLS_intrinsicElements.option)({
            value: "",
            disabled: true,
        });
        (__VLS_ctx.placeholder);
    }
    for (const [section, idx] of __VLS_getVForSourceType((__VLS_ctx.groupedOptions(__VLS_ctx.options)))) {
        (idx);
        if (section.group) {
            __VLS_asFunctionalElement(__VLS_intrinsicElements.optgroup, __VLS_intrinsicElements.optgroup)({
                label: (section.group),
            });
            for (const [opt] of __VLS_getVForSourceType((section.options))) {
                __VLS_asFunctionalElement(__VLS_intrinsicElements.option, __VLS_intrinsicElements.option)({
                    key: (String(opt.value)),
                    value: (opt.value),
                    disabled: (opt.disabled),
                });
                (opt.label);
            }
        }
        for (const [opt] of __VLS_getVForSourceType(((section.group ? [] : section.options)))) {
            __VLS_asFunctionalElement(__VLS_intrinsicElements.option, __VLS_intrinsicElements.option)({
                key: (String(opt.value)),
                value: (opt.value),
                disabled: (opt.disabled),
            });
            (opt.label);
        }
    }
    if (__VLS_ctx.error || __VLS_ctx.help) {
        __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
            ...{ class: "label" },
        });
        __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
            ...{ class: (['label-text-alt', __VLS_ctx.error ? 'text-error' : 'opacity-70']) },
        });
        (__VLS_ctx.error || __VLS_ctx.help);
    }
    /** @type {__VLS_StyleScopedClasses['form-control']} */ ;
    /** @type {__VLS_StyleScopedClasses['w-full']} */ ;
    /** @type {__VLS_StyleScopedClasses['label']} */ ;
    /** @type {__VLS_StyleScopedClasses['label-text']} */ ;
    /** @type {__VLS_StyleScopedClasses['select']} */ ;
    /** @type {__VLS_StyleScopedClasses['select-bordered']} */ ;
    /** @type {__VLS_StyleScopedClasses['w-full']} */ ;
    /** @type {__VLS_StyleScopedClasses['select-error']} */ ;
    /** @type {__VLS_StyleScopedClasses['label']} */ ;
    /** @type {__VLS_StyleScopedClasses['label-text-alt']} */ ;
    var __VLS_dollars;
    const __VLS_self = (await import('vue')).defineComponent({
        setup() {
            return {
                onChange: onChange,
                groupedOptions: groupedOptions,
            };
        },
        __typeEmits: {},
        __typeProps: {},
        props: {},
    });
    return {};
})()) => ({})); /* PartiallyEnd: #4569/main.vue */
//# sourceMappingURL=VSelect.vue.js.map