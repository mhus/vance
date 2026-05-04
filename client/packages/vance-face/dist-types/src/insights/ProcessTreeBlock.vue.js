import { computed } from 'vue';
import { MarkdownView } from '@/components';
const props = defineProps();
const emit = defineEmits();
const events = computed(() => props.eventsByProcess[props.node.process.id] ?? []);
const collapsed = computed(() => props.collapsedProcesses.has(props.node.process.id));
function isExpanded(eventId) {
    return props.expandedEvents.has(props.node.process.id + '|' + eventId);
}
function fmtTime(at) {
    if (at == null)
        return '—';
    try {
        return new Date(at).toISOString().replace('T', ' ').slice(0, 19);
    }
    catch {
        return at;
    }
}
function chatRoleClass(label) {
    if (label.startsWith('USER:'))
        return 'badge-user';
    if (label.startsWith('ASSISTANT:'))
        return 'badge-assistant';
    if (label.startsWith('SYSTEM:'))
        return 'badge-system';
    return '';
}
debugger; /* PartiallyEnd: #3632/scriptSetup.vue */
const __VLS_ctx = {};
let __VLS_components;
let __VLS_directives;
/** @type {__VLS_StyleScopedClasses['tp-event-row']} */ ;
/** @type {__VLS_StyleScopedClasses['tp-children']} */ ;
// CSS variable injection 
// CSS variable injection end 
__VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
    ...{ class: "tp-block" },
});
__VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
    ...{ class: "tp-header" },
});
__VLS_asFunctionalElement(__VLS_intrinsicElements.button, __VLS_intrinsicElements.button)({
    ...{ onClick: (...[$event]) => {
            __VLS_ctx.emit('toggle-process', __VLS_ctx.node.process.id);
        } },
    ...{ class: "tp-chev" },
    type: "button",
});
(__VLS_ctx.collapsed ? '▸' : '▾');
__VLS_asFunctionalElement(__VLS_intrinsicElements.button, __VLS_intrinsicElements.button)({
    ...{ onClick: (...[$event]) => {
            __VLS_ctx.emit('select-process', __VLS_ctx.node.process.id);
        } },
    ...{ class: "tp-name" },
    type: "button",
    title: (__VLS_ctx.$t('insights.processTree.openInView')),
});
(__VLS_ctx.node.process.name);
__VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
    ...{ class: "tp-engine" },
});
(__VLS_ctx.node.process.thinkEngine);
__VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
    ...{ class: "tp-status" },
});
(__VLS_ctx.node.process.status);
if (__VLS_ctx.node.process.recipeName) {
    __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
        ...{ class: "tp-recipe" },
    });
    (__VLS_ctx.node.process.recipeName);
}
__VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
    ...{ class: "tp-count" },
});
(__VLS_ctx.events.length === 1
    ? __VLS_ctx.$t('insights.processTree.eventCountSingular', { count: __VLS_ctx.events.length })
    : __VLS_ctx.$t('insights.processTree.eventCountPlural', { count: __VLS_ctx.events.length }));
if (!__VLS_ctx.collapsed) {
    __VLS_asFunctionalElement(__VLS_intrinsicElements.ul, __VLS_intrinsicElements.ul)({
        ...{ class: "tp-events" },
    });
    for (const [ev] of __VLS_getVForSourceType((__VLS_ctx.events))) {
        __VLS_asFunctionalElement(__VLS_intrinsicElements.li, __VLS_intrinsicElements.li)({
            key: (ev.id),
            ...{ class: "tp-event" },
        });
        __VLS_asFunctionalElement(__VLS_intrinsicElements.button, __VLS_intrinsicElements.button)({
            ...{ onClick: (...[$event]) => {
                    if (!(!__VLS_ctx.collapsed))
                        return;
                    __VLS_ctx.emit('toggle-event', __VLS_ctx.node.process.id, ev.id);
                } },
            ...{ class: "tp-event-row" },
            type: "button",
        });
        __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
            ...{ class: "tp-event-chev" },
        });
        (__VLS_ctx.isExpanded(ev.id) ? '▾' : '▸');
        __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
            ...{ class: "tp-event-time" },
        });
        (__VLS_ctx.fmtTime(ev.at));
        __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
            ...{ class: "tp-event-kind" },
            ...{ class: (['kind-' + ev.kind, ev.kind === 'chat' ? __VLS_ctx.chatRoleClass(ev.label) : '']) },
        });
        (ev.kind);
        __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
            ...{ class: "tp-event-label" },
        });
        (ev.label);
        if (ev.tag) {
            __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
                ...{ class: "tp-event-tag" },
            });
            (ev.tag);
        }
        if (__VLS_ctx.isExpanded(ev.id)) {
            __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
                ...{ class: "tp-event-detail" },
            });
            if (ev.detailIsMarkdown) {
                const __VLS_0 = {}.MarkdownView;
                /** @type {[typeof __VLS_components.MarkdownView, ]} */ ;
                // @ts-ignore
                const __VLS_1 = __VLS_asFunctionalComponent(__VLS_0, new __VLS_0({
                    source: (ev.detail),
                }));
                const __VLS_2 = __VLS_1({
                    source: (ev.detail),
                }, ...__VLS_functionalComponentArgsRest(__VLS_1));
            }
            else {
                __VLS_asFunctionalElement(__VLS_intrinsicElements.pre, __VLS_intrinsicElements.pre)({
                    ...{ class: "tp-event-json" },
                });
                (ev.detail);
            }
        }
    }
}
if (!__VLS_ctx.collapsed && __VLS_ctx.node.children.length > 0) {
    __VLS_asFunctionalElement(__VLS_intrinsicElements.ul, __VLS_intrinsicElements.ul)({
        ...{ class: "tp-children" },
    });
    for (const [child] of __VLS_getVForSourceType((__VLS_ctx.node.children))) {
        __VLS_asFunctionalElement(__VLS_intrinsicElements.li, __VLS_intrinsicElements.li)({
            key: (child.process.id),
        });
        const __VLS_4 = {}.ProcessTreeBlock;
        /** @type {[typeof __VLS_components.ProcessTreeBlock, ]} */ ;
        // @ts-ignore
        const __VLS_5 = __VLS_asFunctionalComponent(__VLS_4, new __VLS_4({
            ...{ 'onSelectProcess': {} },
            ...{ 'onToggleProcess': {} },
            ...{ 'onToggleEvent': {} },
            node: (child),
            eventsByProcess: (__VLS_ctx.eventsByProcess),
            collapsedProcesses: (__VLS_ctx.collapsedProcesses),
            expandedEvents: (__VLS_ctx.expandedEvents),
        }));
        const __VLS_6 = __VLS_5({
            ...{ 'onSelectProcess': {} },
            ...{ 'onToggleProcess': {} },
            ...{ 'onToggleEvent': {} },
            node: (child),
            eventsByProcess: (__VLS_ctx.eventsByProcess),
            collapsedProcesses: (__VLS_ctx.collapsedProcesses),
            expandedEvents: (__VLS_ctx.expandedEvents),
        }, ...__VLS_functionalComponentArgsRest(__VLS_5));
        let __VLS_8;
        let __VLS_9;
        let __VLS_10;
        const __VLS_11 = {
            onSelectProcess: ((id) => __VLS_ctx.emit('select-process', id))
        };
        const __VLS_12 = {
            onToggleProcess: ((id) => __VLS_ctx.emit('toggle-process', id))
        };
        const __VLS_13 = {
            onToggleEvent: ((pid, eid) => __VLS_ctx.emit('toggle-event', pid, eid))
        };
        var __VLS_7;
    }
}
/** @type {__VLS_StyleScopedClasses['tp-block']} */ ;
/** @type {__VLS_StyleScopedClasses['tp-header']} */ ;
/** @type {__VLS_StyleScopedClasses['tp-chev']} */ ;
/** @type {__VLS_StyleScopedClasses['tp-name']} */ ;
/** @type {__VLS_StyleScopedClasses['tp-engine']} */ ;
/** @type {__VLS_StyleScopedClasses['tp-status']} */ ;
/** @type {__VLS_StyleScopedClasses['tp-recipe']} */ ;
/** @type {__VLS_StyleScopedClasses['tp-count']} */ ;
/** @type {__VLS_StyleScopedClasses['tp-events']} */ ;
/** @type {__VLS_StyleScopedClasses['tp-event']} */ ;
/** @type {__VLS_StyleScopedClasses['tp-event-row']} */ ;
/** @type {__VLS_StyleScopedClasses['tp-event-chev']} */ ;
/** @type {__VLS_StyleScopedClasses['tp-event-time']} */ ;
/** @type {__VLS_StyleScopedClasses['tp-event-kind']} */ ;
/** @type {__VLS_StyleScopedClasses['tp-event-label']} */ ;
/** @type {__VLS_StyleScopedClasses['tp-event-tag']} */ ;
/** @type {__VLS_StyleScopedClasses['tp-event-detail']} */ ;
/** @type {__VLS_StyleScopedClasses['tp-event-json']} */ ;
/** @type {__VLS_StyleScopedClasses['tp-children']} */ ;
var __VLS_dollars;
const __VLS_self = (await import('vue')).defineComponent({
    setup() {
        return {
            MarkdownView: MarkdownView,
            emit: emit,
            events: events,
            collapsed: collapsed,
            isExpanded: isExpanded,
            fmtTime: fmtTime,
            chatRoleClass: chatRoleClass,
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
//# sourceMappingURL=ProcessTreeBlock.vue.js.map