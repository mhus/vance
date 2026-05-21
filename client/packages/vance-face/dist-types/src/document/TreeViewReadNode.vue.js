defineOptions({ name: 'TreeViewReadNode' });
const __VLS_props = defineProps();
debugger; /* PartiallyEnd: #3632/scriptSetup.vue */
const __VLS_ctx = {};
let __VLS_components;
let __VLS_directives;
/** @type {__VLS_StyleScopedClasses['tree-read__children']} */ ;
/** @type {__VLS_StyleScopedClasses['tree-read__node']} */ ;
// CSS variable injection 
// CSS variable injection end 
__VLS_asFunctionalElement(__VLS_intrinsicElements.li, __VLS_intrinsicElements.li)({
    ...{ class: "tree-read__node" },
});
if (__VLS_ctx.item.text) {
    __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
        ...{ class: "tree-read__text" },
    });
    (__VLS_ctx.item.text);
}
else {
    __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
        ...{ class: "tree-read__empty-text" },
    });
}
if (__VLS_ctx.item.children && __VLS_ctx.item.children.length > 0) {
    __VLS_asFunctionalElement(__VLS_intrinsicElements.ul, __VLS_intrinsicElements.ul)({
        ...{ class: "tree-read__children" },
    });
    for (const [child, i] of __VLS_getVForSourceType((__VLS_ctx.item.children))) {
        const __VLS_0 = {}.TreeViewReadNode;
        /** @type {[typeof __VLS_components.TreeViewReadNode, ]} */ ;
        // @ts-ignore
        const __VLS_1 = __VLS_asFunctionalComponent(__VLS_0, new __VLS_0({
            key: (i),
            item: (child),
        }));
        const __VLS_2 = __VLS_1({
            key: (i),
            item: (child),
        }, ...__VLS_functionalComponentArgsRest(__VLS_1));
    }
}
/** @type {__VLS_StyleScopedClasses['tree-read__node']} */ ;
/** @type {__VLS_StyleScopedClasses['tree-read__text']} */ ;
/** @type {__VLS_StyleScopedClasses['tree-read__empty-text']} */ ;
/** @type {__VLS_StyleScopedClasses['tree-read__children']} */ ;
var __VLS_dollars;
const __VLS_self = (await import('vue')).defineComponent({
    setup() {
        return {};
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
//# sourceMappingURL=TreeViewReadNode.vue.js.map