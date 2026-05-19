import { ref } from 'vue';
import { VButton } from '@/components';
import FileTreeNode from './FileTreeNode.vue';
const __VLS_props = defineProps();
const emit = defineEmits();
// Root + common script folders pre-expanded so /scripts/ is the
// default visible scope, but the user still sees the full project
// tree at /. Other path-prefixes collapse until clicked.
const expanded = ref(new Set(['', 'scripts']));
function toggle(path) {
    const next = new Set(expanded.value);
    if (next.has(path)) {
        next.delete(path);
    }
    else {
        next.add(path);
    }
    expanded.value = next;
}
debugger; /* PartiallyEnd: #3632/scriptSetup.vue */
const __VLS_ctx = {};
let __VLS_components;
let __VLS_directives;
__VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
    ...{ class: "p-2 text-sm" },
});
__VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
    ...{ class: "flex items-center justify-between mb-2 px-1" },
});
__VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
    ...{ class: "font-semibold opacity-80" },
});
const __VLS_0 = {}.VButton;
/** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
// @ts-ignore
const __VLS_1 = __VLS_asFunctionalComponent(__VLS_0, new __VLS_0({
    ...{ 'onClick': {} },
    size: "sm",
    variant: "ghost",
}));
const __VLS_2 = __VLS_1({
    ...{ 'onClick': {} },
    size: "sm",
    variant: "ghost",
}, ...__VLS_functionalComponentArgsRest(__VLS_1));
let __VLS_4;
let __VLS_5;
let __VLS_6;
const __VLS_7 = {
    onClick: (...[$event]) => {
        __VLS_ctx.emit('new-file', '');
    }
};
__VLS_3.slots.default;
var __VLS_3;
/** @type {[typeof FileTreeNode, ]} */ ;
// @ts-ignore
const __VLS_8 = __VLS_asFunctionalComponent(FileTreeNode, new FileTreeNode({
    ...{ 'onToggle': {} },
    ...{ 'onOpenFile': {} },
    ...{ 'onNewFile': {} },
    ...{ 'onDeleteFile': {} },
    node: (__VLS_ctx.root),
    depth: (0),
    activeFileId: (__VLS_ctx.activeFileId ?? null),
    expanded: (__VLS_ctx.expanded),
}));
const __VLS_9 = __VLS_8({
    ...{ 'onToggle': {} },
    ...{ 'onOpenFile': {} },
    ...{ 'onNewFile': {} },
    ...{ 'onDeleteFile': {} },
    node: (__VLS_ctx.root),
    depth: (0),
    activeFileId: (__VLS_ctx.activeFileId ?? null),
    expanded: (__VLS_ctx.expanded),
}, ...__VLS_functionalComponentArgsRest(__VLS_8));
let __VLS_11;
let __VLS_12;
let __VLS_13;
const __VLS_14 = {
    onToggle: (__VLS_ctx.toggle)
};
const __VLS_15 = {
    onOpenFile: ((id) => __VLS_ctx.emit('open-file', id))
};
const __VLS_16 = {
    onNewFile: ((p) => __VLS_ctx.emit('new-file', p))
};
const __VLS_17 = {
    onDeleteFile: ((id) => __VLS_ctx.emit('delete-file', id))
};
var __VLS_10;
/** @type {__VLS_StyleScopedClasses['p-2']} */ ;
/** @type {__VLS_StyleScopedClasses['text-sm']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['items-center']} */ ;
/** @type {__VLS_StyleScopedClasses['justify-between']} */ ;
/** @type {__VLS_StyleScopedClasses['mb-2']} */ ;
/** @type {__VLS_StyleScopedClasses['px-1']} */ ;
/** @type {__VLS_StyleScopedClasses['font-semibold']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-80']} */ ;
var __VLS_dollars;
const __VLS_self = (await import('vue')).defineComponent({
    setup() {
        return {
            VButton: VButton,
            FileTreeNode: FileTreeNode,
            emit: emit,
            expanded: expanded,
            toggle: toggle,
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
//# sourceMappingURL=FileTreeSidebar.vue.js.map