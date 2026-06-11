import DocumentTabShell from './DocumentTabShell.vue';
const __VLS_props = defineProps();
const emit = defineEmits();
debugger; /* PartiallyEnd: #3632/scriptSetup.vue */
const __VLS_ctx = {};
let __VLS_components;
let __VLS_directives;
/** @type {[typeof DocumentTabShell, ]} */ ;
// @ts-ignore
const __VLS_0 = __VLS_asFunctionalComponent(DocumentTabShell, new DocumentTabShell({
    ...{ 'onUpdate': {} },
    document: (__VLS_ctx.document),
    sessionId: (__VLS_ctx.sessionId ?? null),
}));
const __VLS_1 = __VLS_0({
    ...{ 'onUpdate': {} },
    document: (__VLS_ctx.document),
    sessionId: (__VLS_ctx.sessionId ?? null),
}, ...__VLS_functionalComponentArgsRest(__VLS_0));
let __VLS_3;
let __VLS_4;
let __VLS_5;
const __VLS_6 = {
    onUpdate: ((text) => __VLS_ctx.emit('update', text))
};
var __VLS_7 = {};
var __VLS_2;
var __VLS_dollars;
const __VLS_self = (await import('vue')).defineComponent({
    setup() {
        return {
            DocumentTabShell: DocumentTabShell,
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
//# sourceMappingURL=TabRendererHost.vue.js.map