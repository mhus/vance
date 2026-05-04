import { ref, watch } from 'vue';
import { useI18n } from 'vue-i18n';
import { VAlert, VEmptyState, VPagination } from '@/components';
import { useLlmTraces } from '@/composables/useLlmTraces';
const props = defineProps();
const { t } = useI18n();
const traces = useLlmTraces();
// Per-turn open/closed state — tracked client-side, not from the
// server. Fresh page-load defaults to all collapsed; the user can
// expand whichever round-trip they want to inspect.
const expanded = ref(new Set());
watch(() => props.processId, async (next) => {
    expanded.value = new Set();
    if (!next) {
        traces.reset();
        return;
    }
    await traces.loadPage(next, 0);
}, { immediate: true });
function toggle(turnId) {
    if (expanded.value.has(turnId)) {
        expanded.value.delete(turnId);
    }
    else {
        expanded.value.add(turnId);
    }
    // Trigger reactivity — Set mutations don't propagate by default.
    expanded.value = new Set(expanded.value);
}
async function changePage(p) {
    await traces.loadPage(props.processId, p);
    expanded.value = new Set();
}
function fmtTokens(n) {
    if (n == null)
        return '—';
    if (n < 1_000)
        return String(n);
    if (n < 1_000_000)
        return `${(n / 1_000).toFixed(1)}k`;
    return `${(n / 1_000_000).toFixed(1)}M`;
}
function fmtMs(ms) {
    if (ms == null)
        return '—';
    if (ms < 1_000)
        return `${ms}ms`;
    return `${(ms / 1_000).toFixed(1)}s`;
}
function fmtTime(iso) {
    if (!iso)
        return '';
    try {
        return new Date(iso).toLocaleString();
    }
    catch {
        return iso;
    }
}
function turnLabel(turn) {
    // Short prefix of the turnId — enough to differentiate but doesn't
    // dominate the header.
    const id = turn.turnId.startsWith('__loose:')
        ? t('insights.llmTrace.orphan')
        : turn.turnId.slice(0, 8);
    return id;
}
function legBadge(leg) {
    // input/output direction labels stay as the wire values — they
    // are role identifiers (USER/ASSISTANT/SYSTEM) on the input
    // side, and a fixed token on the output side.
    switch (leg.direction) {
        case 'input':
            return { label: leg.role ?? 'input', cls: 'leg--input' };
        case 'output':
            return { label: 'output', cls: 'leg--output' };
        case 'tool_call':
            return {
                label: t('insights.llmTrace.toolCall', { name: leg.toolName ?? '?' }),
                cls: 'leg--tool-call',
            };
        case 'tool_result':
            return {
                label: t('insights.llmTrace.toolResult', { name: leg.toolName ?? '?' }),
                cls: 'leg--tool-result',
            };
        default:
            return { label: leg.direction || '?', cls: 'leg--default' };
    }
}
debugger; /* PartiallyEnd: #3632/scriptSetup.vue */
const __VLS_ctx = {};
let __VLS_components;
let __VLS_directives;
/** @type {__VLS_StyleScopedClasses['turn-header']} */ ;
// CSS variable injection 
// CSS variable injection end 
__VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
    ...{ class: "flex flex-col gap-3" },
});
if (__VLS_ctx.traces.error.value) {
    const __VLS_0 = {}.VAlert;
    /** @type {[typeof __VLS_components.VAlert, typeof __VLS_components.VAlert, ]} */ ;
    // @ts-ignore
    const __VLS_1 = __VLS_asFunctionalComponent(__VLS_0, new __VLS_0({
        variant: "error",
    }));
    const __VLS_2 = __VLS_1({
        variant: "error",
    }, ...__VLS_functionalComponentArgsRest(__VLS_1));
    __VLS_3.slots.default;
    __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({});
    (__VLS_ctx.traces.error.value);
    var __VLS_3;
}
if (__VLS_ctx.traces.loading.value && __VLS_ctx.traces.items.value.length === 0) {
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "opacity-70" },
    });
    (__VLS_ctx.$t('insights.llmTrace.loading'));
}
else if (!__VLS_ctx.traces.loading.value && __VLS_ctx.traces.items.value.length === 0) {
    const __VLS_4 = {}.VEmptyState;
    /** @type {[typeof __VLS_components.VEmptyState, ]} */ ;
    // @ts-ignore
    const __VLS_5 = __VLS_asFunctionalComponent(__VLS_4, new __VLS_4({
        headline: (__VLS_ctx.$t('insights.llmTrace.emptyHeadline')),
        body: (__VLS_ctx.$t('insights.llmTrace.emptyBody')),
    }));
    const __VLS_6 = __VLS_5({
        headline: (__VLS_ctx.$t('insights.llmTrace.emptyHeadline')),
        body: (__VLS_ctx.$t('insights.llmTrace.emptyBody')),
    }, ...__VLS_functionalComponentArgsRest(__VLS_5));
}
else {
    __VLS_asFunctionalElement(__VLS_intrinsicElements.ul, __VLS_intrinsicElements.ul)({
        ...{ class: "flex flex-col gap-2" },
    });
    for (const [turn] of __VLS_getVForSourceType((__VLS_ctx.traces.turns.value))) {
        __VLS_asFunctionalElement(__VLS_intrinsicElements.li, __VLS_intrinsicElements.li)({
            key: (turn.turnId),
            ...{ class: "turn" },
        });
        __VLS_asFunctionalElement(__VLS_intrinsicElements.button, __VLS_intrinsicElements.button)({
            ...{ onClick: (...[$event]) => {
                    if (!!(__VLS_ctx.traces.loading.value && __VLS_ctx.traces.items.value.length === 0))
                        return;
                    if (!!(!__VLS_ctx.traces.loading.value && __VLS_ctx.traces.items.value.length === 0))
                        return;
                    __VLS_ctx.toggle(turn.turnId);
                } },
            type: "button",
            ...{ class: "turn-header" },
            'aria-expanded': (__VLS_ctx.expanded.has(turn.turnId)),
        });
        __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
            ...{ class: "turn-disclosure" },
        });
        (__VLS_ctx.expanded.has(turn.turnId) ? '▾' : '▸');
        __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
            ...{ class: "turn-id" },
        });
        (__VLS_ctx.turnLabel(turn));
        __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
            ...{ class: "turn-time" },
        });
        (__VLS_ctx.fmtTime(turn.startedAt));
        __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
            ...{ class: "turn-meta" },
        });
        if (turn.modelAlias) {
            __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({});
            (turn.modelAlias);
        }
        if (turn.tokensIn != null || turn.tokensOut != null) {
            __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({});
            (__VLS_ctx.$t('insights.llmTrace.tokensInOut', {
                tokensIn: __VLS_ctx.fmtTokens(turn.tokensIn),
                tokensOut: __VLS_ctx.fmtTokens(turn.tokensOut),
            }));
        }
        if (turn.elapsedMs != null) {
            __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({});
            (__VLS_ctx.fmtMs(turn.elapsedMs));
        }
        if (turn.toolCallCount > 0) {
            __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
                ...{ class: "turn-tools" },
            });
            (turn.toolCallCount === 1
                ? __VLS_ctx.$t('insights.llmTrace.toolCallSingular', { count: turn.toolCallCount })
                : __VLS_ctx.$t('insights.llmTrace.toolCallPlural', { count: turn.toolCallCount }));
        }
        __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
            ...{ class: "turn-leg-count" },
        });
        (__VLS_ctx.$t('insights.llmTrace.legCount', { count: turn.legs.length }));
        if (__VLS_ctx.expanded.has(turn.turnId)) {
            __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
                ...{ class: "turn-body" },
            });
            for (const [leg] of __VLS_getVForSourceType((turn.legs))) {
                __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
                    key: (leg.id ?? `${turn.turnId}-${leg.sequence}`),
                    ...{ class: "leg" },
                    ...{ class: (__VLS_ctx.legBadge(leg).cls) },
                });
                __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
                    ...{ class: "leg-header" },
                });
                __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
                    ...{ class: "leg-badge" },
                });
                (__VLS_ctx.legBadge(leg).label);
                if (leg.toolCallId) {
                    __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
                        ...{ class: "leg-tool-id" },
                    });
                    (__VLS_ctx.$t('insights.llmTrace.idLabel', { id: leg.toolCallId }));
                }
                __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
                    ...{ class: "leg-seq" },
                });
                (__VLS_ctx.$t('insights.llmTrace.seqLabel', { seq: leg.sequence }));
                if (leg.content) {
                    __VLS_asFunctionalElement(__VLS_intrinsicElements.pre, __VLS_intrinsicElements.pre)({
                        ...{ class: "leg-content" },
                    });
                    (leg.content);
                }
                else {
                    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
                        ...{ class: "leg-empty" },
                    });
                    (__VLS_ctx.$t('insights.llmTrace.emptyLeg'));
                }
            }
        }
    }
}
if (__VLS_ctx.traces.totalCount.value > __VLS_ctx.traces.pageSize.value) {
    const __VLS_8 = {}.VPagination;
    /** @type {[typeof __VLS_components.VPagination, ]} */ ;
    // @ts-ignore
    const __VLS_9 = __VLS_asFunctionalComponent(__VLS_8, new __VLS_8({
        ...{ 'onChange': {} },
        page: (__VLS_ctx.traces.page.value),
        pageSize: (__VLS_ctx.traces.pageSize.value),
        totalCount: (__VLS_ctx.traces.totalCount.value),
    }));
    const __VLS_10 = __VLS_9({
        ...{ 'onChange': {} },
        page: (__VLS_ctx.traces.page.value),
        pageSize: (__VLS_ctx.traces.pageSize.value),
        totalCount: (__VLS_ctx.traces.totalCount.value),
    }, ...__VLS_functionalComponentArgsRest(__VLS_9));
    let __VLS_12;
    let __VLS_13;
    let __VLS_14;
    const __VLS_15 = {
        onChange: (__VLS_ctx.changePage)
    };
    var __VLS_11;
}
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-col']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-3']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-70']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-col']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-2']} */ ;
/** @type {__VLS_StyleScopedClasses['turn']} */ ;
/** @type {__VLS_StyleScopedClasses['turn-header']} */ ;
/** @type {__VLS_StyleScopedClasses['turn-disclosure']} */ ;
/** @type {__VLS_StyleScopedClasses['turn-id']} */ ;
/** @type {__VLS_StyleScopedClasses['turn-time']} */ ;
/** @type {__VLS_StyleScopedClasses['turn-meta']} */ ;
/** @type {__VLS_StyleScopedClasses['turn-tools']} */ ;
/** @type {__VLS_StyleScopedClasses['turn-leg-count']} */ ;
/** @type {__VLS_StyleScopedClasses['turn-body']} */ ;
/** @type {__VLS_StyleScopedClasses['leg']} */ ;
/** @type {__VLS_StyleScopedClasses['leg-header']} */ ;
/** @type {__VLS_StyleScopedClasses['leg-badge']} */ ;
/** @type {__VLS_StyleScopedClasses['leg-tool-id']} */ ;
/** @type {__VLS_StyleScopedClasses['leg-seq']} */ ;
/** @type {__VLS_StyleScopedClasses['leg-content']} */ ;
/** @type {__VLS_StyleScopedClasses['leg-empty']} */ ;
var __VLS_dollars;
const __VLS_self = (await import('vue')).defineComponent({
    setup() {
        return {
            VAlert: VAlert,
            VEmptyState: VEmptyState,
            VPagination: VPagination,
            traces: traces,
            expanded: expanded,
            toggle: toggle,
            changePage: changePage,
            fmtTokens: fmtTokens,
            fmtMs: fmtMs,
            fmtTime: fmtTime,
            turnLabel: turnLabel,
            legBadge: legBadge,
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
//# sourceMappingURL=LlmTraceTab.vue.js.map