import { computed, ref } from 'vue';
import { resolveHelpPath } from '../help';
import CortexChatPanel from './CortexChatPanel.vue';
import CortexHelpPanel from './CortexHelpPanel.vue';
const props = defineProps();
const activeTab = ref('chat');
const helpPath = computed(() => resolveHelpPath(props.activeDocument));
debugger; /* PartiallyEnd: #3632/scriptSetup.vue */
const __VLS_ctx = {};
let __VLS_components;
let __VLS_directives;
__VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
    ...{ class: "h-full flex flex-col min-h-0" },
});
__VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
    ...{ class: "flex items-stretch border-b border-base-300 bg-base-200 text-sm shrink-0" },
    role: "tablist",
    'aria-label': "Right panel",
});
__VLS_asFunctionalElement(__VLS_intrinsicElements.button, __VLS_intrinsicElements.button)({
    ...{ onClick: (...[$event]) => {
            __VLS_ctx.activeTab = 'chat';
        } },
    type: "button",
    role: "tab",
    'aria-selected': (__VLS_ctx.activeTab === 'chat'),
    ...{ class: "px-4 py-1.5 border-r border-base-300" },
    ...{ class: (__VLS_ctx.activeTab === 'chat' ? 'bg-base-100 font-semibold' : 'opacity-70 hover:bg-base-100/40') },
});
__VLS_asFunctionalElement(__VLS_intrinsicElements.button, __VLS_intrinsicElements.button)({
    ...{ onClick: (...[$event]) => {
            __VLS_ctx.activeTab = 'help';
        } },
    type: "button",
    role: "tab",
    'aria-selected': (__VLS_ctx.activeTab === 'help'),
    ...{ class: "px-4 py-1.5" },
    ...{ class: (__VLS_ctx.activeTab === 'help' ? 'bg-base-100 font-semibold' : 'opacity-70 hover:bg-base-100/40') },
});
__VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
    ...{ class: "flex-1 min-h-0" },
});
__VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
    ...{ class: "h-full" },
});
__VLS_asFunctionalDirective(__VLS_directives.vShow)(null, { ...__VLS_directiveBindingRestFields, value: (__VLS_ctx.activeTab === 'chat') }, null, null);
/** @type {[typeof CortexChatPanel, ]} */ ;
// @ts-ignore
const __VLS_0 = __VLS_asFunctionalComponent(CortexChatPanel, new CortexChatPanel({
    sessionId: (__VLS_ctx.sessionId),
    projectId: (__VLS_ctx.projectId),
    toolService: (__VLS_ctx.toolService ?? null),
}));
const __VLS_1 = __VLS_0({
    sessionId: (__VLS_ctx.sessionId),
    projectId: (__VLS_ctx.projectId),
    toolService: (__VLS_ctx.toolService ?? null),
}, ...__VLS_functionalComponentArgsRest(__VLS_0));
__VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
    ...{ class: "h-full" },
});
__VLS_asFunctionalDirective(__VLS_directives.vShow)(null, { ...__VLS_directiveBindingRestFields, value: (__VLS_ctx.activeTab === 'help') }, null, null);
/** @type {[typeof CortexHelpPanel, ]} */ ;
// @ts-ignore
const __VLS_3 = __VLS_asFunctionalComponent(CortexHelpPanel, new CortexHelpPanel({
    helpPath: (__VLS_ctx.helpPath),
}));
const __VLS_4 = __VLS_3({
    helpPath: (__VLS_ctx.helpPath),
}, ...__VLS_functionalComponentArgsRest(__VLS_3));
/** @type {__VLS_StyleScopedClasses['h-full']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-col']} */ ;
/** @type {__VLS_StyleScopedClasses['min-h-0']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['items-stretch']} */ ;
/** @type {__VLS_StyleScopedClasses['border-b']} */ ;
/** @type {__VLS_StyleScopedClasses['border-base-300']} */ ;
/** @type {__VLS_StyleScopedClasses['bg-base-200']} */ ;
/** @type {__VLS_StyleScopedClasses['text-sm']} */ ;
/** @type {__VLS_StyleScopedClasses['shrink-0']} */ ;
/** @type {__VLS_StyleScopedClasses['px-4']} */ ;
/** @type {__VLS_StyleScopedClasses['py-1.5']} */ ;
/** @type {__VLS_StyleScopedClasses['border-r']} */ ;
/** @type {__VLS_StyleScopedClasses['border-base-300']} */ ;
/** @type {__VLS_StyleScopedClasses['px-4']} */ ;
/** @type {__VLS_StyleScopedClasses['py-1.5']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-1']} */ ;
/** @type {__VLS_StyleScopedClasses['min-h-0']} */ ;
/** @type {__VLS_StyleScopedClasses['h-full']} */ ;
/** @type {__VLS_StyleScopedClasses['h-full']} */ ;
var __VLS_dollars;
const __VLS_self = (await import('vue')).defineComponent({
    setup() {
        return {
            CortexChatPanel: CortexChatPanel,
            CortexHelpPanel: CortexHelpPanel,
            activeTab: activeTab,
            helpPath: helpPath,
        };
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
//# sourceMappingURL=CortexRightPanel.vue.js.map