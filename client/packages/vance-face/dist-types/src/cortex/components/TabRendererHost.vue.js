import { computed } from 'vue';
import { resolveRenderer } from '../docTypeRegistry';
const props = defineProps();
const emit = defineEmits();
const renderer = computed(() => resolveRenderer(props.document));
debugger; /* PartiallyEnd: #3632/scriptSetup.vue */
const __VLS_ctx = {};
let __VLS_components;
let __VLS_directives;
const __VLS_0 = ((__VLS_ctx.renderer.component));
// @ts-ignore
const __VLS_1 = __VLS_asFunctionalComponent(__VLS_0, new __VLS_0({
    ...{ 'onUpdate': {} },
    document: (__VLS_ctx.document),
}));
const __VLS_2 = __VLS_1({
    ...{ 'onUpdate': {} },
    document: (__VLS_ctx.document),
}, ...__VLS_functionalComponentArgsRest(__VLS_1));
let __VLS_4;
let __VLS_5;
let __VLS_6;
const __VLS_7 = {
    onUpdate: ((text) => __VLS_ctx.emit('update', text))
};
var __VLS_8 = {};
var __VLS_3;
var __VLS_dollars;
const __VLS_self = (await import('vue')).defineComponent({
    setup() {
        return {
            emit: emit,
            renderer: renderer,
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
//# sourceMappingURL=TabRendererHost.vue.js.map