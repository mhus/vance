export default ((__VLS_props, __VLS_ctx, __VLS_expose, __VLS_setup = (async () => {
    const __VLS_props = withDefaults(defineProps(), {
        selectable: false,
        selectedId: null,
    });
    const __VLS_emit = defineEmits();
    function keyOf(item, index, extractor) {
        if (extractor)
            return extractor(item, index);
        return item.id ?? index;
    }
    debugger; /* PartiallyEnd: #3632/scriptSetup.vue */
    const __VLS_withDefaultsArg = (function (t) { return t; })({
        selectable: false,
        selectedId: null,
    });
    const __VLS_fnComponent = (await import('vue')).defineComponent({
        __typeEmits: {},
    });
    const __VLS_ctx = {};
    let __VLS_components;
    let __VLS_directives;
    __VLS_asFunctionalElement(__VLS_intrinsicElements.ul, __VLS_intrinsicElements.ul)({
        ...{ class: "flex flex-col gap-2" },
    });
    for (const [item, index] of __VLS_getVForSourceType((__VLS_ctx.items))) {
        __VLS_asFunctionalElement(__VLS_intrinsicElements.li, __VLS_intrinsicElements.li)({
            ...{ onClick: (...[$event]) => {
                    __VLS_ctx.selectable && __VLS_ctx.$emit('select', item);
                } },
            key: (__VLS_ctx.keyOf(item, index, __VLS_ctx.itemKey)),
            ...{ class: ([
                    'card bg-base-100 shadow-sm border border-base-300',
                    __VLS_ctx.selectable ? 'cursor-pointer hover:border-primary' : '',
                    __VLS_ctx.selectedId !== null && item.id === __VLS_ctx.selectedId ? 'border-primary' : '',
                ]) },
        });
        __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
            ...{ class: "card-body p-4" },
        });
        var __VLS_0 = {
            item: (item),
            index: (index),
        };
    }
    /** @type {__VLS_StyleScopedClasses['flex']} */ ;
    /** @type {__VLS_StyleScopedClasses['flex-col']} */ ;
    /** @type {__VLS_StyleScopedClasses['gap-2']} */ ;
    /** @type {__VLS_StyleScopedClasses['card']} */ ;
    /** @type {__VLS_StyleScopedClasses['bg-base-100']} */ ;
    /** @type {__VLS_StyleScopedClasses['shadow-sm']} */ ;
    /** @type {__VLS_StyleScopedClasses['border']} */ ;
    /** @type {__VLS_StyleScopedClasses['border-base-300']} */ ;
    /** @type {__VLS_StyleScopedClasses['card-body']} */ ;
    /** @type {__VLS_StyleScopedClasses['p-4']} */ ;
    // @ts-ignore
    var __VLS_1 = __VLS_0;
    var __VLS_dollars;
    const __VLS_self = (await import('vue')).defineComponent({
        setup() {
            return {
                keyOf: keyOf,
            };
        },
        __typeEmits: {},
        __typeProps: {},
        props: {},
    });
    return {};
})()) => ({})); /* PartiallyEnd: #4569/main.vue */
//# sourceMappingURL=VDataList.vue.js.map