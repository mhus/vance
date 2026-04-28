const __VLS_props = defineProps();
const emit = defineEmits();
debugger; /* PartiallyEnd: #3632/scriptSetup.vue */
const __VLS_ctx = {};
let __VLS_components;
let __VLS_directives;
// CSS variable injection 
// CSS variable injection end 
__VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
    ...{ class: "marvin-node" },
});
__VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
    ...{ class: "marvin-node-head" },
});
__VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
    ...{ class: "marvin-kind" },
});
(__VLS_ctx.node.doc.taskKind);
__VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
    ...{ class: "marvin-status" },
});
(__VLS_ctx.node.doc.status);
__VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
    ...{ class: "marvin-goal" },
});
(__VLS_ctx.node.doc.goal || '(no goal)');
if (__VLS_ctx.node.doc.spawnedProcessId) {
    __VLS_asFunctionalElement(__VLS_intrinsicElements.button, __VLS_intrinsicElements.button)({
        ...{ onClick: (...[$event]) => {
                if (!(__VLS_ctx.node.doc.spawnedProcessId))
                    return;
                __VLS_ctx.emit('select-process', __VLS_ctx.node.doc.spawnedProcessId);
            } },
        ...{ class: "link ml-2" },
        type: "button",
    });
}
if (__VLS_ctx.node.doc.failureReason) {
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "marvin-failure" },
    });
    (__VLS_ctx.node.doc.failureReason);
}
if (__VLS_ctx.node.children.length > 0) {
    __VLS_asFunctionalElement(__VLS_intrinsicElements.ul, __VLS_intrinsicElements.ul)({
        ...{ class: "marvin-children" },
    });
    for (const [child] of __VLS_getVForSourceType((__VLS_ctx.node.children))) {
        __VLS_asFunctionalElement(__VLS_intrinsicElements.li, __VLS_intrinsicElements.li)({
            key: (child.doc.id),
        });
        const __VLS_0 = {}.MarvinTreeItem;
        /** @type {[typeof __VLS_components.MarvinTreeItem, ]} */ ;
        // @ts-ignore
        const __VLS_1 = __VLS_asFunctionalComponent(__VLS_0, new __VLS_0({
            ...{ 'onSelectProcess': {} },
            node: (child),
        }));
        const __VLS_2 = __VLS_1({
            ...{ 'onSelectProcess': {} },
            node: (child),
        }, ...__VLS_functionalComponentArgsRest(__VLS_1));
        let __VLS_4;
        let __VLS_5;
        let __VLS_6;
        const __VLS_7 = {
            onSelectProcess: ((id) => __VLS_ctx.emit('select-process', id))
        };
        var __VLS_3;
    }
}
/** @type {__VLS_StyleScopedClasses['marvin-node']} */ ;
/** @type {__VLS_StyleScopedClasses['marvin-node-head']} */ ;
/** @type {__VLS_StyleScopedClasses['marvin-kind']} */ ;
/** @type {__VLS_StyleScopedClasses['marvin-status']} */ ;
/** @type {__VLS_StyleScopedClasses['marvin-goal']} */ ;
/** @type {__VLS_StyleScopedClasses['link']} */ ;
/** @type {__VLS_StyleScopedClasses['ml-2']} */ ;
/** @type {__VLS_StyleScopedClasses['marvin-failure']} */ ;
/** @type {__VLS_StyleScopedClasses['marvin-children']} */ ;
var __VLS_dollars;
const __VLS_self = (await import('vue')).defineComponent({
    setup() {
        return {
            emit: emit,
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
//# sourceMappingURL=MarvinTreeItem.vue.js.map