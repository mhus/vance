import { ref } from 'vue';
import ProgressFeed from './ProgressFeed.vue';
import WizardPanel from './WizardPanel.vue';
const __VLS_props = withDefaults(defineProps(), {
    projectId: undefined,
    sessionKey: undefined,
});
const emit = defineEmits();
/** Right-aside tab selector — toggles between the live progress feed
 *  and the prompt-wizards panel. Default 'progress' preserves the
 *  pre-wizards behaviour for users that haven't engaged the feature
 *  yet. */
const rightTab = ref('progress');
const wizardPanelRef = ref(null);
/**
 * Deep-link entry point used by ChatView's `vance:/wizards/...` link
 * handler. Switches the tab to wizards (so the panel is mounted),
 * then calls into {@link WizardPanel.openWizard} on the next tick.
 */
function openWizard(name, prefill = {}) {
    rightTab.value = 'wizards';
    void Promise.resolve().then(() => {
        wizardPanelRef.value?.openWizard(name, prefill);
    });
}
const __VLS_exposed = { openWizard };
defineExpose(__VLS_exposed);
function onWizardPromptReady(prompt) {
    emit('prompt-ready', prompt);
}
debugger; /* PartiallyEnd: #3632/scriptSetup.vue */
const __VLS_withDefaultsArg = (function (t) { return t; })({
    projectId: undefined,
    sessionKey: undefined,
});
const __VLS_ctx = {};
let __VLS_components;
let __VLS_directives;
__VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
    ...{ class: "h-full flex flex-col" },
});
__VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
    ...{ class: "flex border-b border-base-300 text-xs uppercase tracking-wide font-semibold" },
});
__VLS_asFunctionalElement(__VLS_intrinsicElements.button, __VLS_intrinsicElements.button)({
    ...{ onClick: (...[$event]) => {
            __VLS_ctx.rightTab = 'progress';
        } },
    type: "button",
    ...{ class: (['flex-1 py-2 transition-colors',
            __VLS_ctx.rightTab === 'progress'
                ? 'bg-base-100 border-b-2 border-primary'
                : 'bg-base-200 opacity-70 hover:opacity-100']) },
});
(__VLS_ctx.$t('chat.wizards.progressTabLabel'));
__VLS_asFunctionalElement(__VLS_intrinsicElements.button, __VLS_intrinsicElements.button)({
    ...{ onClick: (...[$event]) => {
            __VLS_ctx.rightTab = 'wizards';
        } },
    type: "button",
    ...{ class: (['flex-1 py-2 transition-colors',
            __VLS_ctx.rightTab === 'wizards'
                ? 'bg-base-100 border-b-2 border-primary'
                : 'bg-base-200 opacity-70 hover:opacity-100']) },
});
(__VLS_ctx.$t('chat.wizards.tabLabel'));
__VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
    ...{ class: "flex-1 min-h-0 overflow-y-auto" },
});
if (__VLS_ctx.rightTab === 'progress') {
    /** @type {[typeof ProgressFeed, ]} */ ;
    // @ts-ignore
    const __VLS_0 = __VLS_asFunctionalComponent(ProgressFeed, new ProgressFeed({
        events: (__VLS_ctx.events),
    }));
    const __VLS_1 = __VLS_0({
        events: (__VLS_ctx.events),
    }, ...__VLS_functionalComponentArgsRest(__VLS_0));
}
else {
    /** @type {[typeof WizardPanel, ]} */ ;
    // @ts-ignore
    const __VLS_3 = __VLS_asFunctionalComponent(WizardPanel, new WizardPanel({
        ...{ 'onPromptReady': {} },
        ref: "wizardPanelRef",
        projectId: (__VLS_ctx.projectId),
        sessionKey: (__VLS_ctx.sessionKey),
    }));
    const __VLS_4 = __VLS_3({
        ...{ 'onPromptReady': {} },
        ref: "wizardPanelRef",
        projectId: (__VLS_ctx.projectId),
        sessionKey: (__VLS_ctx.sessionKey),
    }, ...__VLS_functionalComponentArgsRest(__VLS_3));
    let __VLS_6;
    let __VLS_7;
    let __VLS_8;
    const __VLS_9 = {
        onPromptReady: (__VLS_ctx.onWizardPromptReady)
    };
    /** @type {typeof __VLS_ctx.wizardPanelRef} */ ;
    var __VLS_10 = {};
    var __VLS_5;
}
/** @type {__VLS_StyleScopedClasses['h-full']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-col']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['border-b']} */ ;
/** @type {__VLS_StyleScopedClasses['border-base-300']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['uppercase']} */ ;
/** @type {__VLS_StyleScopedClasses['tracking-wide']} */ ;
/** @type {__VLS_StyleScopedClasses['font-semibold']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-1']} */ ;
/** @type {__VLS_StyleScopedClasses['py-2']} */ ;
/** @type {__VLS_StyleScopedClasses['transition-colors']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-1']} */ ;
/** @type {__VLS_StyleScopedClasses['py-2']} */ ;
/** @type {__VLS_StyleScopedClasses['transition-colors']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-1']} */ ;
/** @type {__VLS_StyleScopedClasses['min-h-0']} */ ;
/** @type {__VLS_StyleScopedClasses['overflow-y-auto']} */ ;
// @ts-ignore
var __VLS_11 = __VLS_10;
var __VLS_dollars;
const __VLS_self = (await import('vue')).defineComponent({
    setup() {
        return {
            ProgressFeed: ProgressFeed,
            WizardPanel: WizardPanel,
            rightTab: rightTab,
            wizardPanelRef: wizardPanelRef,
            onWizardPromptReady: onWizardPromptReady,
        };
    },
    __typeEmits: {},
    __typeProps: {},
    props: {},
});
export default (await import('vue')).defineComponent({
    setup() {
        return {
            ...__VLS_exposed,
        };
    },
    __typeEmits: {},
    __typeProps: {},
    props: {},
});
; /* PartiallyEnd: #4569/main.vue */
//# sourceMappingURL=ChatRightPanel.vue.js.map